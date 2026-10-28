"""Il conto dei soldi (contratto, sezioni 0 e 8).

Regola che nessun pezzo può aggirare: UN SOLO punto, la Cassa, controlla il tetto PRIMA di
ogni tentativo di chiamata a pagamento (piano, ogni blocco scritto, ogni pezzo di voce, OCR,
scheda, correttore) e registra ogni spesa.

Come funziona un tentativo (`Cassa.chiamata`):
1. nella stessa transazione: controllo "speso del mese + stima ≤ tetto" e una RIGA DI
   PRENOTAZIONE nella tabella spese con la stima. Se il database non scrive (disco pieno,
   file bloccato) la chiamata non parte: niente si paga senza essere contato;
2. la chiamata al fornitore;
3. la riga si aggiorna con il costo vero restituito dal fornitore. Se il fornitore di sicuro
   non ha lavorato (connessione mai aperta, 429, 503, errore con risposta chiara) la riga si
   toglie; se non si sa (risposta persa) resta la stima.
Le prenotazioni sono righe vere: tre pezzi di voce in parallelo si vedono a vicenda.

I ritenti (`Cassa.chiama`) sono tentativi nuovi: ognuno ricontrolla il tetto.

La voce gratis di Azure ha un limite di 500.000 caratteri al mese: si contano allo stesso modo
(righe con i caratteri come quantità; il prezzo Azure sta in prezzi.json, 0 di base).

Un modello senza prezzo in prezzi.json blocca le chiamate a pagamento con una frase chiara:
senza prezzo non c'è tetto che tenga."""

import json
import logging
import math
import sqlite3
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass

from . import clock, db, misure
from .rete import ATTESE_SECONDI, ErroreFornitore, NonElaborata, Temporaneo

log = logging.getLogger("uvicorn.error")

SOGLIA_AVVISO = 0.8
FRASE_VOCE_GRATIS_FINITA = "Hai finito la voce gratis di questo mese"

# Quanto materiale entra (vedi lezione.py). Oltre APPUNTI_MASSIMI di appunti, il piano
# sceglie prima i pezzi sull'argomento (contratto 15.1).
APPUNTI_MASSIMI = 60_000
CARATTERI_APPUNTI_LETTI = 1_000_000
CARATTERI_VERIFICHE_MASSIMI = 30_000
CARATTERI_BLOCCO_MASSIMI = 14_000

# Un aggiornamento della scheda: fino a 60.000 caratteri di verifiche + risposta.
TOKEN_SCHEDA_ENTRATA = 20_000
TOKEN_SCHEDA_USCITA = 2_500


class TettoRaggiunto(Exception):
    def __init__(self, frase: str):
        super().__init__(frase)
        self.frase = frase


class VoceGratisFinita(Exception):
    def __init__(self, frase: str = FRASE_VOCE_GRATIS_FINITA + ": scegli la voce espressiva o aspetta il mese prossimo."):
        super().__init__(frase)
        self.frase = frase


class PrezzoSconosciuto(ErroreFornitore):
    def __init__(self, modello: str):
        # La frase va a schermo: niente nomi tecnici. Il nome del modello finisce nel log.
        log.warning("prezzo mancante in prezzi.json per il modello %s: chiamate a pagamento ferme", modello)
        super().__init__("Sul server manca il prezzo di un modello di Mistral: finché non c'è, Lectio non fa "
                         "chiamate a pagamento. Va aggiunto nel file dei prezzi del server.")
        self.modello = modello


def arrotonda_su(eur: float) -> float:
    """Per eccesso al centesimo (le stime, contratto sezione 1). round() toglie i
    rimasugli dei numeri con la virgola (0,25000000001 non deve diventare 0,26)."""
    return math.ceil(round(eur * 100, 6)) / 100


def arrotonda(eur: float) -> float:
    return round(eur + 1e-9, 2)


def euro(valore: float) -> str:
    """2 → "2 €", 1.5 → "1,50 €"."""
    if abs(valore - round(valore)) < 0.005:
        return f"{int(round(valore))} €"
    return f"{valore:.2f}".replace(".", ",") + " €"


def data_breve(giorno) -> str:
    return f"{'1°' if giorno.day == 1 else giorno.day} {clock.MESI[giorno.month - 1]}"


@dataclass
class Prezzi:
    cambio: float
    testo: dict
    ocr: dict
    voce: dict
    azure_per_mille: float
    azure_gratis: int

    @classmethod
    def carica(cls, percorso: str) -> "Prezzi":
        with open(percorso, encoding="utf-8") as f:
            d = json.load(f)
        return cls(
            cambio=float(d.get("cambio_usd_eur", 0.86)),
            testo=d.get("testo", {}),
            ocr=d.get("ocr", {}),
            voce=d.get("voce", {}),
            azure_per_mille=float(d.get("azure", {}).get("per_mille_caratteri", 0.0)),
            azure_gratis=int(d.get("azure", {}).get("caratteri_gratis_al_mese", 500_000)),
        )

    @staticmethod
    def _voce_listino(listino: dict, modello: str) -> dict:
        if modello not in listino:
            raise PrezzoSconosciuto(modello)
        return listino[modello]

    def mancanti(self, settings) -> list[str]:
        """A cosa servono i modelli configurati che non hanno un prezzo (per /api/salute, a
        parole: "per la scrittura", "per la voce espressiva"…)."""
        usi = [(self.testo, settings.modello_piano, "per il piano e la scheda"),
               (self.testo, settings.modello_scrittura, "per la scrittura"),
               (self.ocr, settings.modello_ocr, "per leggere le foto"),
               (self.voce, settings.modello_voce, "per la voce espressiva")]
        return [uso for listino, modello, uso in usi if modello not in listino]

    def costo_testo(self, modello: str, token_entrata: int, token_uscita: int) -> float:
        p = self._voce_listino(self.testo, modello)
        dollari = token_entrata / 1e6 * p["entrata_per_milione"] + token_uscita / 1e6 * p["uscita_per_milione"]
        return dollari * self.cambio

    def costo_ocr(self, modello: str, pagine: int) -> float:
        p = self._voce_listino(self.ocr, modello)
        return pagine / 1000 * p["per_mille_pagine"] * self.cambio

    def costo_voce(self, modello: str, caratteri: int) -> float:
        p = self._voce_listino(self.voce, modello)
        return caratteri / 1000 * p["per_mille_caratteri"] * self.cambio

    def costo_azure(self, caratteri: int) -> float:
        return caratteri / 1000 * self.azure_per_mille * self.cambio

    def euro_al_minuto_voce(self, modello: str) -> float | None:
        if modello not in self.voce:
            return None
        return round(self.costo_voce(modello, misure.PAROLE_AL_MINUTO * misure.CARATTERI_PER_PAROLA), 4)

    def euro_al_minuto_azure(self) -> float:
        return round(self.costo_azure(misure.PAROLE_AL_MINUTO * misure.CARATTERI_PER_PAROLA), 4)


class Ricevuta:
    """Il tentativo in corso: la sua riga di prenotazione e il modo di scrivere il costo vero."""

    def __init__(self, cassa: "Cassa", riga_id: int | None, cosa: str, fornitore: str, lezione_id, materiale_id,
                 materia_id):
        self._cassa = cassa
        self.riga_id = riga_id
        self.cosa = cosa
        self.fornitore = fornitore
        self.lezione_id = lezione_id
        self.materiale_id = materiale_id
        self.materia_id = materia_id
        self.registrata = False

    def registra(self, quantita: float, unita: str, costo_eur: float, cosa: str | None = None) -> None:
        self._cassa._registra(self, quantita, unita, costo_eur, cosa or self.cosa)
        self.registrata = True


def _non_elaborata(e: BaseException) -> bool:
    """Il fornitore di sicuro non ha lavorato (quindi non ha fatto pagare)."""
    return (isinstance(e, (NonElaborata, VoceGratisFinita))
            or (isinstance(e, ErroreFornitore) and not getattr(e, "elaborata", False)))


class Cassa:
    def __init__(self, db_path: str, prezzi: Prezzi, dormi=time.sleep, attese=ATTESE_SECONDI):
        self.db_path = db_path
        self.prezzi = prezzi
        self.dormi = dormi
        self.attese = attese
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ letture

    @staticmethod
    def speso(conn: sqlite3.Connection, mese: str | None = None) -> float:
        """Tutto quello che è nel conto del mese: spese vere, prenotazioni in corso, stime di
        risposte perse."""
        riga = conn.execute("SELECT COALESCE(SUM(costo_eur), 0) AS s FROM spese WHERE mese = ?",
                            (mese or clock.mese(),)).fetchone()
        return float(riga["s"])

    @staticmethod
    def caratteri_gratis_usati(conn: sqlite3.Connection, mese: str | None = None) -> int:
        riga = conn.execute(
            "SELECT COALESCE(SUM(quantita), 0) AS s FROM spese WHERE mese = ? AND fornitore = 'azure'",
            (mese or clock.mese(),),
        ).fetchone()
        return int(riga["s"])

    @staticmethod
    def tetto(conn: sqlite3.Connection) -> float:
        return float(db.impostazioni(conn)["tetto_eur"])

    def caratteri_gratis_rimasti(self, conn: sqlite3.Connection) -> int:
        return max(0, self.prezzi.azure_gratis - self.caratteri_gratis_usati(conn))

    def riassunto(self, conn: sqlite3.Connection) -> dict:
        speso = arrotonda(self.speso(conn))
        tetto = self.tetto(conn)
        percentuale = int(round(100 * speso / tetto)) if tetto > 0 else 0
        return {
            "mese": clock.mese(),
            "speso_eur": speso,
            "tetto_eur": tetto,
            "percentuale": percentuale,
            "si_azzera_il": clock.primo_del_mese_prossimo().isoformat(),
            "avviso": speso >= SOGLIA_AVVISO * tetto - 1e-9,
        }

    def frase_tetto(self, conn: sqlite3.Connection) -> str:
        giorno = clock.primo_del_mese_prossimo()
        return (f"Hai raggiunto il tetto di spesa del mese ({euro(self.tetto(conn))}). "
                f"Alza il tetto nelle impostazioni o aspetta il {data_breve(giorno)}.")

    def impegni(self, conn: sqlite3.Connection, *, prova: bool, modello_ocr: str, modello_piano: str) -> tuple[float, int]:
        """Le stime che restano da spendere per quello che è già in corso: lezioni in coda o in
        preparazione, foto da leggere, schede da aggiornare (euro, caratteri della voce gratis)."""
        eur, caratteri = 0.0, 0
        for r in conn.execute(
            "SELECT l.stima_eur, l.stima_caratteri,"
            " (SELECT COALESCE(SUM(costo_eur), 0) FROM spese WHERE lezione_id = l.id) AS speso,"
            " (SELECT COALESCE(SUM(quantita), 0) FROM spese WHERE lezione_id = l.id AND fornitore = 'azure') AS usati"
            " FROM lezioni l WHERE l.stato IN ('in_coda', 'in_preparazione')"
        ):
            eur += max(0.0, float(r["stima_eur"]) - float(r["speso"]))
            caratteri += max(0, int(r["stima_caratteri"]) - int(r["usati"]))
        if prova:
            return eur, caratteri
        try:
            for r in conn.execute(
                "SELECT m.pagine, m.pagine_ocr,"
                " (SELECT COALESCE(SUM(costo_eur), 0) FROM spese WHERE materiale_id = m.id) AS speso"
                " FROM materiali m WHERE m.stato = 'in_lettura'"
            ):
                pagine = len(json.loads(r["pagine_ocr"])) if r["pagine_ocr"] else int(r["pagine"] or 1)
                eur += max(0.0, self.prezzi.costo_ocr(modello_ocr, pagine) - float(r["speso"]))
            schede = conn.execute("SELECT COUNT(*) FROM schede WHERE da_aggiornare > 0").fetchone()[0]
            eur += schede * self.prezzi.costo_testo(modello_piano, TOKEN_SCHEDA_ENTRATA, TOKEN_SCHEDA_USCITA)
        except PrezzoSconosciuto:
            pass  # quelle chiamate restano comunque ferme finché manca il prezzo
        return eur, caratteri

    # ------------------------------------------------------------------ il punto unico

    @contextmanager
    def chiamata(self, *, cosa: str, fornitore: str, stima_eur: float = 0.0, caratteri_gratis: int = 0,
                 quantita_stimata: float = 0, unita: str = "", lezione_id: int | None = None,
                 materiale_id: int | None = None, materia_id: int | None = None):
        """UN tentativo di chiamata a un fornitore:

            with cassa.chiamata(cosa="Voce · pezzo 3", fornitore="mistral", stima_eur=0.002) as r:
                risposta = fornitore.una_volta(...)
                r.registra(caratteri, "caratteri", costo_vero)

        Solleva TettoRaggiunto (o VoceGratisFinita) PRIMA che la chiamata parta; se la riga di
        prenotazione non si scrive, l'errore del database ferma la chiamata."""
        stima_eur = max(0.0, float(stima_eur))
        riga_id = None
        if stima_eur > 0 or caratteri_gratis > 0:
            ora = clock.now()
            with self._lock, db.aperta(self.db_path) as conn, db.transazione(conn):
                if stima_eur > 0 and self.speso(conn) + stima_eur > self.tetto(conn) + 1e-9:
                    raise TettoRaggiunto(self.frase_tetto(conn))
                if caratteri_gratis > 0 and self.caratteri_gratis_usati(conn) + caratteri_gratis > self.prezzi.azure_gratis:
                    raise VoceGratisFinita()
                riga_id = conn.execute(
                    "INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur, lezione_id,"
                    " materiale_id, materia_id, stato) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'prenotata')",
                    (clock.iso(ora), clock.mese(ora), f"{cosa} (in corso)", fornitore,
                     float(caratteri_gratis or quantita_stimata), unita or ("caratteri" if caratteri_gratis else ""),
                     stima_eur, lezione_id, materiale_id, materia_id),
                ).lastrowid
        ricevuta = Ricevuta(self, riga_id, cosa, fornitore, lezione_id, materiale_id, materia_id)
        try:
            yield ricevuta
        except BaseException as e:
            if riga_id is not None and not ricevuta.registrata:
                if _non_elaborata(e):
                    self._togli(riga_id)
                else:
                    self._tieni_stima(riga_id, cosa)
            raise
        else:
            if riga_id is not None and not ricevuta.registrata:
                self._tieni_stima(riga_id, cosa)

    def chiama(self, *, cosa: str, fornitore: str, fare, registra=None, stima_eur: float = 0.0,
               caratteri_gratis: int = 0, quantita_stimata: float = 0, unita: str = "", lezione_id: int | None = None,
               materiale_id: int | None = None, materia_id: int | None = None, prima=None):
        """`fare()` fa UN tentativo; `registra(ricevuta, risultato)` scrive il costo vero.
        Sugli errori temporanei si ritenta (2, 4, 8 secondi), e ogni tentativo è una chiamata
        nuova: tetto ricontrollato, prenotazione nuova. `prima()` si chiama prima di ognuno
        (per esempio: la lezione è stata annullata?)."""
        for n in range(len(self.attese) + 1):
            if prima:
                prima()
            etichetta = cosa if n == 0 else f"{cosa} (tentativo {n + 1})"
            try:
                with self.chiamata(cosa=etichetta, fornitore=fornitore, stima_eur=stima_eur,
                                   caratteri_gratis=caratteri_gratis, quantita_stimata=quantita_stimata, unita=unita,
                                   lezione_id=lezione_id, materiale_id=materiale_id, materia_id=materia_id) as r:
                    risultato = fare()
                    if registra is not None:
                        registra(r, risultato)
                    return risultato
            except Temporaneo as e:
                log.warning("%s: tentativo %s non riuscito (%s)", cosa, n + 1, e)
                if n >= len(self.attese):
                    raise ErroreFornitore(e.frase, temporaneo=True) from None
                self.dormi(self.attese[n])
        raise AssertionError("non raggiungibile")

    def _togli(self, riga_id: int) -> None:
        with db.aperta(self.db_path) as conn:
            conn.execute("DELETE FROM spese WHERE id = ? AND stato = 'prenotata'", (riga_id,))

    def _tieni_stima(self, riga_id: int, cosa: str) -> None:
        """Risposta persa: il fornitore potrebbe aver lavorato, la stima resta nel conto."""
        with db.aperta(self.db_path) as conn:
            conn.execute("UPDATE spese SET stato = 'stimata', cosa = ? WHERE id = ? AND stato = 'prenotata'",
                         (f"{cosa} (risposta persa: conto la stima)", riga_id))

    def _registra(self, r: Ricevuta, quantita: float, unita: str, costo_eur: float, cosa: str) -> None:
        ora = clock.now()
        costo_eur = max(0.0, float(costo_eur))
        with db.aperta(self.db_path) as conn, db.transazione(conn):
            if r.riga_id is not None:
                conn.execute("UPDATE spese SET quantita = ?, unita = ?, costo_eur = ?, cosa = ?, stato = 'fatta'"
                             " WHERE id = ?", (float(quantita), unita, costo_eur, cosa, r.riga_id))
                mese = conn.execute("SELECT mese FROM spese WHERE id = ?", (r.riga_id,)).fetchone()["mese"]
            else:
                mese = clock.mese(ora)
                conn.execute(
                    "INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur, lezione_id,"
                    " materiale_id, materia_id, stato) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'fatta')",
                    (clock.iso(ora), mese, cosa, r.fornitore, float(quantita), unita, costo_eur, r.lezione_id,
                     r.materiale_id, r.materia_id),
                )
            tetto = self.tetto(conn)
            dopo = self.speso(conn, mese)
            # Una volta al mese, alla prima spesa che porta (o trova) il conto all'80% del tetto.
            if (tetto > 0 and costo_eur > 0 and dopo >= SOGLIA_AVVISO * tetto - 1e-9
                    and db.leggi_interno(conn, "tetto_vicino_mese") != mese):
                db.salva_interno(conn, "tetto_vicino_mese", mese)
                db.aggiungi_novita(conn, "tetto_vicino",
                                   f"Questo mese hai speso {euro(arrotonda(dopo))} di {euro(tetto)}: sei vicino al tetto")

    # ------------------------------------------------------------------ stime

    def stima_testo_lezione(self, durata_min: int, caratteri_appunti: int, caratteri_verifiche: int,
                            modello_piano: str, modello_scrittura: str) -> float:
        blocchi = misure.n_blocchi(durata_min)
        domande = misure.n_interrogazione(durata_min)
        t = misure.CARATTERI_PER_TOKEN
        appunti = min(caratteri_appunti, CARATTERI_APPUNTI_LETTI)
        scelta = 0.0
        if appunti > APPUNTI_MASSIMI:  # la scelta dei pezzi sull'argomento: circa 230 caratteri a pezzo
            scelta = self.prezzi.costo_testo(modello_piano, int((appunti / 1100 * 230 + 3000) / t), 800)
        materiali = min(appunti, APPUNTI_MASSIMI) + min(caratteri_verifiche, CARATTERI_VERIFICHE_MASSIMI)
        piano = self.prezzi.costo_testo(modello_piano, int((materiali + 7000) / t), 300 + 250 * blocchi + 120 * domande)
        per_blocco = min(materiali, CARATTERI_BLOCCO_MASSIMI) + 6000
        entrata = blocchi * per_blocco + 4000 + (per_blocco + 2000)
        # Uscita: il testo parlato più la cornice JSON (tipi dei paragrafi, virgolette).
        uscita = misure.caratteri_stimati(durata_min) * 1.25
        scrittura = self.prezzi.costo_testo(modello_scrittura, int(entrata / t), int(uscita / t))
        return scelta + piano + scrittura

    def stima_lezione(self, conn: sqlite3.Connection, *, durata_min: int, tipo_voce: str, caratteri_appunti: int,
                      caratteri_verifiche: int, prova: bool, modello_piano: str, modello_scrittura: str,
                      modello_voce: str, modello_ocr: str) -> dict:
        """La stima di una lezione nuova. `spesa_mese_eur` comprende anche quello che resta da
        spendere per il lavoro già in corso (altre lezioni in coda, foto da leggere): così due
        lezioni messe in coda insieme non passano entrambe la stima. Tutto per eccesso."""
        caratteri = misure.caratteri_stimati(durata_min)
        costo_testo = 0.0 if prova else arrotonda_su(self.stima_testo_lezione(
            durata_min, caratteri_appunti, caratteri_verifiche, modello_piano, modello_scrittura))
        if tipo_voce == "espressiva":
            costo_voce = arrotonda_su(self.prezzi.costo_voce(modello_voce, caratteri))
        elif tipo_voce == "gratis":
            costo_voce = arrotonda_su(self.prezzi.costo_azure(caratteri))
        else:
            costo_voce = 0.0
        costo = arrotonda(costo_testo + costo_voce)
        impegnato, caratteri_impegnati = self.impegni(conn, prova=prova, modello_ocr=modello_ocr,
                                                      modello_piano=modello_piano)
        speso = arrotonda_su(self.speso(conn) + impegnato)
        tetto = self.tetto(conn)
        dopo = arrotonda(speso + costo)
        rimasti = max(0, self.caratteri_gratis_rimasti(conn) - caratteri_impegnati)
        consentita, motivo = True, None
        if dopo > tetto + 1e-9:
            consentita = False
            if tipo_voce == "espressiva" and arrotonda(speso + costo_testo) <= tetto + 1e-9:
                motivo = f"Sforerebbe il tetto di {euro(tetto)}: usa la voce gratis"
            else:
                motivo = (f"Sforerebbe il tetto di {euro(tetto)}: alza il tetto nelle impostazioni o aspetta il "
                          f"{data_breve(clock.primo_del_mese_prossimo())}")
            if impegnato > 0:
                motivo += " (conto anche le lezioni già in preparazione)"
        elif tipo_voce == "gratis" and caratteri > rimasti:
            consentita = False
            motivo = (f"{FRASE_VOCE_GRATIS_FINITA}: restano {rimasti} caratteri, questa lezione ne vuole "
                      f"circa {caratteri}. Scegli una durata più corta o la voce espressiva")
        return {
            "caratteri_stimati": caratteri,
            "costo_testo_eur": costo_testo,
            "costo_voce_eur": costo_voce,
            "costo_eur": costo,
            "spesa_mese_eur": speso,
            "impegnato_eur": arrotonda_su(impegnato),
            "tetto_eur": tetto,
            "dopo_eur": dopo,
            "consentita": consentita,
            "motivo": motivo,
            "voce_gratis_caratteri_rimasti": rimasti,
            "si_azzera_il": clock.primo_del_mese_prossimo().isoformat(),
        }

    def stima_ocr(self, conn: sqlite3.Connection, *, pagine: int, prova: bool, modello_ocr: str,
                  modello_piano: str) -> tuple[float, bool, str]:
        """Prima di accettare un file da leggere a pagamento: (costo, ci sta?, frase)."""
        costo = arrotonda_su(self.prezzi.costo_ocr(modello_ocr, pagine))
        impegnato, _ = self.impegni(conn, prova=prova, modello_ocr=modello_ocr, modello_piano=modello_piano)
        speso = arrotonda_su(self.speso(conn) + impegnato)
        limite = self.tetto(conn)
        ci_sta = speso + costo <= limite + 1e-9
        frase = (f"Leggere questo file costa circa {euro(costo)}: sforerebbe il tetto di {euro(limite)} "
                 f"di questo mese.")
        return costo, ci_sta, frase
