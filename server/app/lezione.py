"""La preparazione di una lezione (contratto, sezione 9.1):
lettura → piano → scrittura → voce → montaggio.

Ogni fase salva quello che produce nella cartella della lezione (`/data/lezioni/<id>/`):
lettura.json, piano.json, scrittura/*.json, copione.json, pezzi/*. Una fase già fatta
non si rifà: un `riprova` riparte dalla fase fallita senza ripagare i pezzi già pronti.
Prima di ogni chiamata a pagamento si guarda se la lezione è stata annullata o
cancellata, e la Cassa ricontrolla il tetto."""

import concurrent.futures
import hashlib
import json
import logging
import os
import re
import shutil
import threading
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from . import ai, clock, controllo, db, memoria, misure, prompt, ripasso
from . import scheda as schede
from .audio import PAUSA_CAPITOLI, PAUSA_DENTRO_PARAGRAFO, PAUSA_DOPO_DOMANDA, PAUSA_PARAGRAFI, Montatore, taglia_in_pezzi
from .spesa import APPUNTI_MASSIMI, CARATTERI_APPUNTI_LETTI, CARATTERI_BLOCCO_MASSIMI, Cassa

log = logging.getLogger("uvicorn.error")

FASI = ("lettura", "piano", "scrittura", "voce", "montaggio")
FRASI_FASE = {
    "lettura": "Leggo il materiale",
    "piano": "Preparo il piano",
    "scrittura": "Scrivo la lezione",
    "voce": "Registro la voce",
    "montaggio": "Monto l'audio",
}
TIPI_PARAGRAFO = ("aggancio", "spiegazione", "definizione", "esempio")
EMOZIONE = {
    "apertura": "warm", "aggancio": "curious", "spiegazione": "confident", "esempio": "confident",
    "definizione": "calm", "domanda_blocco": "curious", "risposta": "confident", "riepilogo": "confident",
    "interrogazione": "determined", "domanda_interrogazione": "determined", "errori": "confident",
    "chiusura": "warm",
}
ORDINALI = ["Prima", "Seconda", "Terza", "Quarta", "Quinta", "Sesta", "Settima", "Ottava", "Nona", "Decima",
            "Undicesima", "Dodicesima"]
CAPITOLO_FINALE = "Ripasso e interrogazione"
CAPITOLO_FINALE_RIPASSO = "Le domande"
CAPITOLO_FINALE_INTERROGAZIONE = "Per finire"


class Annullata(Exception):
    """La lezione è stata annullata o cancellata: ci si ferma senza segnare errori."""


class ErroreLezione(Exception):
    def __init__(self, frase: str):
        super().__init__(frase)
        self.frase = frase


@dataclass
class Contesto:
    settings: object
    cassa: Cassa
    testo: object
    ocr: object
    catalogo: object
    montatore: Montatore
    vettori: object = None
    foto: object = None


# --------------------------------------------------------------------------- utilità di testo

def testo_da(v) -> str:
    """Il testo di un valore scritto dal modello: una stringa, una lista di stringhe (unite), un
    oggetto con "testo". Mai la forma Python di una lista ("['…']")."""
    if isinstance(v, str):
        return v
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return str(v)
    if isinstance(v, list):
        return " ".join(t for t in (testo_da(x) for x in v) if t)
    if isinstance(v, dict):
        return testo_da(v.get("testo"))
    return ""


def pulisci_parlato(testo) -> str:
    """Toglie quello che non si legge a voce (markdown, segni di voce scritti dal modello)."""
    t = testo_da(testo)
    t = re.sub(r"\[(?:emotion|pace)=[^\]]*\]", "", t, flags=re.I)
    t = re.sub(r"[*#_`]+", "", t)
    t = re.sub(r"^\s*[-•]\s+", "", t)
    return " ".join(t.split())


def _parole(testo: str) -> int:
    return len(testo.split())


# --------------------------------------------------------------------------- validazioni

def _testo_ok(v) -> bool:
    return isinstance(v, str) and bool(v.strip())


def _domanda_ok(d) -> bool:
    return _testo_ok(d) or (isinstance(d, dict) and _testo_ok(d.get("testo")))


def valida_piano(o: dict, n: int, q: int, tipo: str = "spiegazione", gruppi: list[int] | None = None) -> list[str]:
    errori = []
    if not _testo_ok(o.get("titolo")):
        errori.append('manca "titolo"')
    blocchi = o.get("blocchi")
    if not isinstance(blocchi, list) or len(blocchi) < max(2, n - 1):
        errori.append(f'servono esattamente {n} blocchi in "blocchi"')
    else:
        for i, b in enumerate(blocchi, start=1):
            if not isinstance(b, dict) or not _testo_ok(b.get("titolo")):
                errori.append(f"il blocco {i} non ha un titolo")
                continue
            if tipo == "spiegazione" and not _domanda_ok(b.get("domanda")):
                errori.append(f"il blocco {i} non ha la domanda di fine blocco")
            if tipo == "interrogazione":
                ds = b.get("domande")
                if not isinstance(ds, list) or not ds or not all(_domanda_ok(d) for d in ds):
                    errori.append(f'il blocco {i} deve avere le sue domande in "domande", ognuna con il suo testo')
    if tipo == "interrogazione":
        totale = sum(gruppi or [q])
        if not errori and sum(len(b["domande"]) for b in blocchi[:n]) < totale - max(1, totale // 6):
            errori.append(f"servono {totale} domande in tutto, divise così: {', '.join(map(str, gruppi or [q]))}")
        return errori
    domande = o.get("interrogazione")
    if not isinstance(domande, list) or len(domande) < max(1, q - 1):
        errori.append(f'servono {q} domande in "interrogazione"')
    elif any(not (_testo_ok(d) or (isinstance(d, dict) and _testo_ok(d.get("testo")))) for d in domande):
        errori.append("ogni domanda dell'interrogazione deve avere il suo testo")
    return errori


def _tipo_domanda(v) -> str:
    t = str(v or "").strip().lower().replace(" ", "_")
    return t if t in prompt.TIPI_DOMANDA else "aperta"


def normalizza_piano(o: dict, n: int, q: int, lettura: dict, tipo: str = "spiegazione",
                     gruppi: list[int] | None = None) -> dict:
    validi = {b["id"] for b in lettura["brani"]}

    def domanda(d) -> dict:
        d = {"testo": d} if isinstance(d, str) else d
        return {"testo": pulisci_parlato(d["testo"]), "tipo": _tipo_domanda(d.get("tipo")),
                "fonti": [str(f) for f in _paragrafi(d.get("fonti")) if str(f) in validi][:8]}

    blocchi = []
    for k, b in enumerate(o["blocchi"][:n]):
        punti = [t for t in (pulisci_parlato(p) for p in _paragrafi(b.get("punti"))) if t][:12]
        uscita = {
            "titolo": pulisci_parlato(b["titolo"])[:80],
            "punti": punti,
            "fonti": [str(f) for f in _paragrafi(b.get("fonti")) if str(f) in validi][:12],
            "domanda": None,
        }
        if tipo == "spiegazione":
            d = domanda(b["domanda"])
            uscita["domanda"] = {"testo": d["testo"], "tipo": d["tipo"]}
        elif tipo == "interrogazione":
            massimo = (gruppi or [])[k] if k < len(gruppi or []) else len(b["domande"])
            uscita["domande"] = [domanda(d) for d in b["domande"] if _domanda_ok(d)][:max(1, massimo)]
        blocchi.append(uscita)
    domande = [domanda(d) for d in (o.get("interrogazione") or [])[:q]] if tipo != "interrogazione" else []
    try:
        minuti = float(o.get("minuti_di_materiale", lettura["durata_min"]))
    except (TypeError, ValueError):
        minuti = float(lettura["durata_min"])
    errori = [t for t in (pulisci_parlato(e) for e in _paragrafi(o.get("errori_da_evitare"))) if t][:3]
    return {
        "tipo": tipo,
        "titolo": pulisci_parlato(o["titolo"])[:120],
        "minuti_di_materiale": max(0.0, min(float(lettura["durata_min"]), minuti)),
        "blocchi": blocchi,
        "interrogazione": domande,
        "errori_da_evitare": errori,
    }


def _paragrafi(v) -> list:
    return v if isinstance(v, list) else ([v] if isinstance(v, str) else [])


def valida_apertura(o: dict) -> list[str]:
    paragrafi = _paragrafi(o.get("paragrafi"))
    if not paragrafi or not all(_testo_ok(p) or (isinstance(p, dict) and _testo_ok(p.get("testo"))) for p in paragrafi):
        return ['servono i "paragrafi", ognuno con il suo "testo"']
    return []


def valida_blocco(o: dict, parole_minime: int) -> list[str]:
    errori = valida_apertura(o)
    if not _testo_ok(o.get("domanda")):
        errori.append('manca "domanda"')
    if not _testo_ok(o.get("risposta")):
        errori.append('manca "risposta"')
    if not errori and parole_minime:
        totale = sum(_parole(p if isinstance(p, str) else p["testo"]) for p in _paragrafi(o["paragrafi"]))
        if totale < parole_minime:
            errori.append(f"il blocco è troppo corto: servono circa {int(parole_minime / 0.45)} parole")
    return errori


def valida_parte(o: dict, parole_minime: int) -> list[str]:
    """Ripasso: solo paragrafi, abbastanza lunghi."""
    errori = valida_apertura(o)
    if not errori and parole_minime:
        totale = sum(_parole(p if isinstance(p, str) else p["testo"]) for p in _paragrafi(o["paragrafi"]))
        if totale < parole_minime:
            errori.append(f"la parte è troppo corta: servono circa {int(parole_minime / 0.45)} parole")
    return errori


def valida_gruppo(o: dict, n_domande: int, parole_minime: int) -> list[str]:
    """Interrogazione: le domande del gruppo, ognuna con la sua risposta."""
    domande = o.get("domande")
    if not isinstance(domande, list) or len(domande) < n_domande:
        return [f'servono {n_domande} domande in "domande", ognuna con "testo" e "risposta"']
    if any(not (isinstance(d, dict) and _testo_ok(d.get("testo")) and _testo_ok(d.get("risposta")))
           for d in domande[:n_domande]):
        return ['ogni domanda deve avere "testo" e "risposta"']
    if parole_minime:
        totale = sum(_parole(d["risposta"]) for d in domande[:n_domande])
        if totale < parole_minime:
            return [f"le risposte sono troppo corte: servono circa {int(parole_minime / 0.45 / n_domande)} parole "
                    "l'una"]
    return []


def valida_finale(o: dict, q: int, tipo: str = "spiegazione") -> list[str]:
    errori = []
    if tipo == "interrogazione":
        if not all(_testo_ok(e) for e in _paragrafi(o.get("errori"))):
            errori.append('"errori" deve essere una lista di frasi')
        return errori
    if tipo == "spiegazione" and (not _paragrafi(o.get("riepilogo"))
                                  or not all(_testo_ok(p) for p in _paragrafi(o.get("riepilogo")))):
        errori.append('manca "riepilogo"')
    domande = o.get("domande")
    if not isinstance(domande, list) or len(domande) < q:
        errori.append(f'servono {q} domande in "domande", ognuna con "testo" e "risposta"')
    elif any(not (isinstance(d, dict) and _testo_ok(d.get("testo")) and _testo_ok(d.get("risposta"))) for d in domande):
        errori.append('ogni domanda deve avere "testo" e "risposta"')
    if not all(_testo_ok(e) for e in _paragrafi(o.get("errori"))):
        errori.append('"errori" deve essere una lista di frasi')
    return errori


def _testi(v) -> list[dict]:
    uscita = []
    for p in _paragrafi(v):
        if isinstance(p, str):
            p = {"testo": p}
        testo = pulisci_parlato(p.get("testo"))
        if testo:
            tipo = str(p.get("tipo") or "spiegazione").lower()
            uscita.append({"tipo": tipo if tipo in TIPI_PARAGRAFO else "spiegazione", "testo": testo})
    return uscita


# --------------------------------------------------------------------------- composizione

def _segmento(ruolo: str, emozione_di: str, voce: str, testo: str, lento: bool = False, domanda: str | None = None,
              indice: int | None = None) -> dict:
    return {"ruolo": ruolo, "emozione": EMOZIONE[emozione_di], "lento": lento, "voce": voce, "testo": testo,
            "domanda": domanda, "indice": indice}


def _chiusura_errori(f: dict, il_prof: str) -> list[dict]:
    segmenti = []
    errori = [pulisci_parlato(e) for e in _paragrafi(f.get("errori")) if pulisci_parlato(e)]
    if errori:
        quanti = {1: "un errore", 2: "due errori"}.get(len(errori), "tre errori")
        lead = f"Prima di chiudere, {quanti} da non fare con {il_prof}."
        segmenti.append(_segmento("paragrafo", "interrogazione", lead, lead))
        for e in errori[:3]:
            segmenti.append(_segmento("paragrafo", "errori", e, e))
    chiusura = pulisci_parlato(f.get("chiusura")) or "Per oggi è tutto. Ripeti le risposte a voce prima della verifica."
    segmenti.append(_segmento("paragrafo", "chiusura", chiusura, chiusura))
    return segmenti


def _componi_interrogazione(lettura: dict, piano: dict, scritti: dict, il_prof: str) -> list[dict]:
    """Interrogazione (16.1): un capitolo per gruppo di domande, poi gli errori da non fare."""
    gruppi = []
    for k, blocco in enumerate(piano["blocchi"], start=1):
        s = scritti[f"blocco_{k:02d}"]
        voluto = len(blocco.get("domande") or [])
        domande = [(pulisci_parlato(d.get("testo")), pulisci_parlato(d.get("risposta")))
                   for d in (s.get("domande") or []) if isinstance(d, dict)][: voluto or None]
        gruppi.append((blocco, s, [d for d in domande if d[0] and d[1]]))
    totale = sum(len(d) for _, _, d in gruppi)
    capitoli, indice = [], 0
    for k, (blocco, s, domande) in enumerate(gruppi, start=1):
        segmenti = []
        if k == 1:
            for p in _testi(scritti["apertura"].get("paragrafi")):
                segmenti.append(_segmento("paragrafo", "apertura", p["testo"], p["testo"]))
        intro = pulisci_parlato(s.get("introduzione"))
        if intro:
            segmenti.append(_segmento("paragrafo", "interrogazione", intro, intro))
        for testo, risposta in domande:
            indice += 1
            etichetta = "Ultima domanda." if indice == totale and totale > 1 else f"Domanda {indice}."
            segmenti.append(_segmento("domanda", "domanda_interrogazione", f"{etichetta} {testo}", f"Domanda: {testo}",
                                      domanda="interrogazione", indice=indice))
            segmenti.append(_segmento("risposta", "risposta", f"Ecco la risposta. {risposta}", f"Risposta: {risposta}",
                                      indice=indice))
        if domande or (k == 1 and segmenti):
            capitoli.append({"n": len(capitoli) + 1, "titolo": blocco["titolo"], "segmenti": segmenti})
    capitoli.append({"n": len(capitoli) + 1, "titolo": CAPITOLO_FINALE_INTERROGAZIONE,
                     "segmenti": _chiusura_errori(scritti["finale"], il_prof)})
    return capitoli


def componi(lettura: dict, piano: dict, scritti: dict) -> list[dict]:
    """I capitoli con i loro segmenti (paragrafi, domande, risposte), nell'ordine dell'audio."""
    prof = (lettura["materia"].get("prof") or "").strip()
    il_prof = f"il prof {prof}" if prof else "il prof"
    tipo = piano.get("tipo") or "spiegazione"
    if tipo == "interrogazione":
        return _componi_interrogazione(lettura, piano, scritti, il_prof)
    capitoli = []
    indice_domanda = 0
    for k, blocco in enumerate(piano["blocchi"], start=1):
        segmenti = []
        if k == 1:
            for p in _testi(scritti["apertura"].get("paragrafi")):
                segmenti.append(_segmento("paragrafo", "apertura", p["testo"], p["testo"]))
        s = scritti[f"blocco_{k:02d}"]
        for i, p in enumerate(_testi(s.get("paragrafi"))):
            genere = "aggancio" if i == 0 and p["tipo"] == "aggancio" else p["tipo"]
            segmenti.append(_segmento("paragrafo", genere, p["testo"], p["testo"], lento=genere == "definizione"))
        domanda, risposta = pulisci_parlato(s.get("domanda")), pulisci_parlato(s.get("risposta"))
        if tipo == "ripasso" or not blocco.get("domanda") or not domanda or not risposta:
            if segmenti:  # una parte rimasta senza niente dopo il controllo non si legge
                capitoli.append({"n": len(capitoli) + 1, "titolo": blocco["titolo"], "segmenti": segmenti})
            continue
        indice_domanda += 1
        segmenti.append(_segmento("domanda", "domanda_blocco", f"Una domanda per te. {domanda}", f"Domanda: {domanda}",
                                  domanda="blocco", indice=indice_domanda))
        segmenti.append(_segmento("risposta", "risposta", f"Ecco la risposta. {risposta}", f"Risposta: {risposta}",
                                  indice=indice_domanda))
        capitoli.append({"n": len(capitoli) + 1, "titolo": blocco["titolo"], "segmenti": segmenti})
    if not capitoli:
        raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza su questo argomento per farci una lezione: "
                            "aggiungi le pagine del libro o gli appunti, poi prepara una nuova lezione.")

    f = scritti["finale"]
    segmenti = []
    for p in (_paragrafi(f.get("riepilogo")) if tipo != "ripasso" else []):
        t = pulisci_parlato(p)
        if t:
            segmenti.append(_segmento("paragrafo", "riepilogo", t, t))
    intro = (f"Adesso ti interrogo come farebbe {il_prof}. Dopo ogni domanda rispondi a voce, "
             f"poi ascolta la risposta.")
    if tipo == "ripasso":
        intro = (f"Adesso le domande, come le farebbe {il_prof}. Dopo ogni domanda rispondi, "
                 f"poi ascolta la risposta.")
    if f.get("domande"):
        segmenti.append(_segmento("paragrafo", "interrogazione", intro, intro))
    domande = [d for d in f.get("domande") or [] if isinstance(d, dict) and pulisci_parlato(d.get("testo"))
               and pulisci_parlato(d.get("risposta"))][: len(piano["interrogazione"])]
    for i, d in enumerate(domande):
        testo = pulisci_parlato(d["testo"])
        risposta = pulisci_parlato(d["risposta"])
        ordinale = "Ultima" if i == len(domande) - 1 and len(domande) > 1 else ORDINALI[min(i, len(ORDINALI) - 1)]
        indice_domanda += 1
        segmenti.append(_segmento("domanda", "domanda_interrogazione", f"{ordinale} domanda. {testo}",
                                  f"Domanda: {testo}", domanda="interrogazione", indice=indice_domanda))
        segmenti.append(_segmento("risposta", "risposta", f"Ecco la risposta. {risposta}", f"Risposta: {risposta}",
                                  indice=indice_domanda))
    segmenti += _chiusura_errori(f, il_prof)
    titolo = CAPITOLO_FINALE_RIPASSO if tipo == "ripasso" else CAPITOLO_FINALE
    capitoli.append({"n": len(capitoli) + 1, "titolo": titolo, "segmenti": segmenti})
    return capitoli


def copione(capitoli: list[dict]) -> list[dict]:
    """I pezzi di voce: un segmento = uno o più pezzi da ≤ 1.500 caratteri; domande e
    risposte sempre a parte. Ogni pezzo sa che silenzio va messo prima."""
    pezzi = []
    for c in capitoli:
        for s_i, s in enumerate(c["segmenti"]):
            parti = taglia_in_pezzi(s["voce"])
            for j, parte in enumerate(parti):
                if not pezzi:
                    pausa = 0.0
                elif j > 0:
                    pausa = PAUSA_DENTRO_PARAGRAFO
                elif s_i == 0:
                    pausa = PAUSA_CAPITOLI
                elif pezzi[-1]["ruolo"] == "domanda":
                    pausa = PAUSA_DOPO_DOMANDA
                else:
                    pausa = PAUSA_PARAGRAFI
                pezzi.append({"n": len(pezzi) + 1, "capitolo": c["n"], "segmento": s_i, "parte": j,
                              "ruolo": s["ruolo"], "testo": parte, "emozione": s["emozione"], "lento": s["lento"],
                              "pausa_prima": pausa})
    return pezzi


# --------------------------------------------------------------------------- la preparazione

class Preparazione:
    def __init__(self, ctx: Contesto, lezione_id: int):
        self.ctx = ctx
        self.id = lezione_id
        self.cartella = Path(ctx.settings.dati_dir) / "lezioni" / str(lezione_id)
        with db.aperta(ctx.settings.db_path) as conn:
            r = conn.execute(
                "SELECT l.*, m.nome AS materia_nome, m.prof AS materia_prof FROM lezioni l"
                " JOIN materie m ON m.id = l.materia_id WHERE l.id = ?", (lezione_id,)
            ).fetchone()
        if r is None:
            raise Annullata()
        self.riga = dict(r)
        self._lock = threading.Lock()

    # --- piccoli aiuti

    def _file(self, nome: str) -> Path:
        return self.cartella / nome

    def _leggi(self, nome: str):
        try:
            with open(self._file(nome), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def _scrivi(self, nome: str, oggetto) -> None:
        percorso = self._file(nome)
        percorso.parent.mkdir(parents=True, exist_ok=True)
        provvisorio = percorso.with_suffix(percorso.suffix + ".tmp")
        with open(provvisorio, "w", encoding="utf-8") as f:
            json.dump(oggetto, f, ensure_ascii=False)
        os.replace(provvisorio, percorso)

    def controlla(self) -> None:
        """Annullata o cancellata? Allora ci si ferma prima di spendere altro."""
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM lezioni WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def avanza(self, fase: str, fatto: int = 0, totale: int = 0) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            conn.execute("UPDATE lezioni SET fase = ?, fatto = ?, totale = ? WHERE id = ? AND stato = 'in_preparazione'",
                         (fase, fatto, totale, self.id))

    def _chiedi(self, compito: str, messaggi: list[dict], modello: str, valida, max_token: int, cosa: str,
                dati: dict) -> dict:
        return ai.chiedi_json(
            cassa=self.ctx.cassa, fornitore=self.ctx.testo, compito=compito, messaggi=messaggi, modello=modello,
            valida=valida, max_token=max_token, cosa=cosa, dati=dati, lezione_id=self.id,
            materia_id=self.riga["materia_id"], prima_di_ogni_chiamata=self.controlla,
        )

    # --- il giro completo

    def esegui(self) -> None:
        self.cartella.mkdir(parents=True, exist_ok=True)
        # La voce si controlla subito: scoprirlo dopo aver pagato il testo sarebbe uno spreco.
        self.avanza("lettura")
        self.scelta = self.ctx.catalogo.risolvi(self.riga["voce"])
        if self.scelta is None:
            raise ErroreLezione("La voce scelta adesso non si può usare. Prepara la lezione con un'altra voce.")
        for fase in FASI:
            self.controlla()
            self.avanza(fase)
            getattr(self, f"_{fase}")()

    # --- 1. lettura

    def _lettura(self) -> None:
        """Contratto 15.1 e 17: i pezzi di tutto il materiale della materia, in ordine di priorità.
        Se Andrea ha scelto il materiale con "Cambia", solo quello."""
        if self._leggi("lettura.json"):
            return
        ristretti = set(json.loads(self.riga["materiali"] or "[]"))
        materia_id = self.riga["materia_id"]
        with db.aperta(self.ctx.settings.db_path) as conn:
            appunti = conn.execute(
                "SELECT * FROM materiali WHERE materia_id = ? AND stato = 'pronto' AND tipo IN ('appunti', 'libro', 'altro')"
                " ORDER BY data, id", (materia_id,)).fetchall()
            verifiche = conn.execute(
                "SELECT * FROM materiali WHERE materia_id = ? AND stato = 'pronto' AND tipo IN ('verifica', 'interrogazione')"
                " ORDER BY data DESC, id DESC", (materia_id,)).fetchall()
            if ristretti:
                appunti = [r for r in appunti if r["id"] in ristretti]
                verifiche = [r for r in verifiche if r["id"] in ristretti]
                if not appunti and not verifiche:
                    # Mai allargare a "tutto il materiale": era stato scelto apposta.
                    raise ErroreLezione("Il materiale scelto per questa lezione è stato cancellato o non è più "
                                        "letto: prepara una nuova lezione scegliendo altro materiale.")
            if not appunti and not verifiche:
                raise ErroreLezione("Questa materia non ha ancora materiale letto: aggiungi appunti o verifiche, "
                                    "poi prepara una nuova lezione.")
            memoria.assicura(conn, materia_id, self.ctx.settings.dati_dir)
            brani, tagliato = memoria.brani_di(conn, materia_id, sorted(ristretti) or None, CARATTERI_APPUNTI_LETTI)
            if not brani:
                raise ErroreLezione("Nel materiale scelto non ho trovato testo da usare: aggiungi appunti o verifiche, "
                                    "poi prepara una nuova lezione.")
            s = schede.per_lezione(conn, materia_id)
            evento = None
            if self.riga["evento_id"]:
                e = conn.execute("SELECT * FROM eventi WHERE id = ?", (self.riga["evento_id"],)).fetchone()
                if e:
                    giorno = date.fromisoformat(e["data"])
                    evento = {"tipo": e["tipo"], "data": e["data"], "argomento": e["argomento"],
                              "data_in_parole": f"{giorno.day} {clock.MESI[giorno.month - 1]}"}
            nome = str(db.impostazioni(conn).get("nome") or "")
            ripassare = ripasso.da_ripassare(conn, materia_id, limite=12)
        caratteri_appunti = sum(len(b["testo"]) for b in brani if b["id"].startswith("B"))
        self._scrivi("lettura.json", {
            "argomento": self.riga["argomento"],
            "durata_min": self.riga["durata_min"],
            "tipo": misure.tipo_valido(self.riga.get("tipo")),
            "indicazioni": self.riga.get("indicazioni") or "",
            "materia": {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]},
            "evento": evento,
            "scheda": s,
            "nome_studente": nome,
            "brani": brani,
            "caratteri_appunti": caratteri_appunti,
            "tagliato": tagliato,
            "da_ripassare": ripassare,
        })

    def _brani(self, lettura: dict) -> list[dict]:
        """I pezzi che il piano può leggere: tutti, o quelli sull'argomento se sono troppi."""
        selezione = self._leggi("selezione.json")
        if selezione is None:
            return lettura["brani"]
        scelti = set(selezione["scelti"])
        return [b for b in lettura["brani"] if b["id"] in scelti]

    def _cerca(self, domanda: str, brani: list[dict], massimo: int, gia: list[str] | None = None) -> list[dict]:
        return memoria.scegli(self.ctx, domanda, brani, massimo, gia, materia_id=self.riga["materia_id"],
                              lezione_id=self.id, prima=self.controlla)

    # --- 2. piano

    def _seleziona(self, lettura: dict) -> None:
        """Con troppo materiale al piano arrivano solo i pezzi sull'argomento."""
        if sum(len(b["testo"]) for b in lettura["brani"]) <= APPUNTI_MASSIMI or self._leggi("selezione.json") is not None:
            return
        cerca = " ".join(x for x in (lettura["argomento"], lettura.get("indicazioni")) if x)
        self._scrivi("selezione.json", {"scelti": [b["id"] for b in self._cerca(cerca, lettura["brani"], APPUNTI_MASSIMI)]})

    def _piano(self) -> None:
        if self._leggi("piano.json"):
            return
        lettura = self._leggi("lettura.json")
        self._seleziona(lettura)
        per_piano = {**lettura, "brani": self._brani(lettura)}
        durata = lettura["durata_min"]
        tipo = misure.tipo_valido(lettura.get("tipo"))
        st = misure.struttura(tipo, durata)
        n, q = st["parti"], st["finali"]
        gruppi = misure.domande_dei_gruppi(st["domande"], n) if tipo == "interrogazione" else None
        minuti_blocco = misure.minuti_per_parte(tipo, durata)
        oggetto = self._chiedi(
            "piano", prompt.messaggi_piano(per_piano, n, q, minuti_blocco, tipo=tipo, gruppi=gruppi),
            self.ctx.settings.modello_piano, lambda o: valida_piano(o, n, q, tipo, gruppi),
            min(8000, 800 + 350 * n + 150 * st["domande"]), "Piano della lezione",
            {"lettura": per_piano, "n_blocchi": n, "n_interrogazione": q, "minuti_blocco": minuti_blocco,
             "tipo": tipo, "gruppi": gruppi},
        )
        piano = normalizza_piano(oggetto, n, q, per_piano, tipo, gruppi)
        self._scrivi("piano.json", piano)
        parti = []
        if piano["minuti_di_materiale"] < durata - 1:
            parti.append(f"I tuoi documenti bastavano per circa {max(1, round(piano['minuti_di_materiale']))} minuti: "
                         f"la lezione è più corta.")
        if self._leggi("selezione.json") is not None:
            parti.append("I documenti erano tanti: ho usato le parti che riguardano l'argomento.")
        if lettura.get("tagliato"):
            parti.append("Il materiale era molto lungo: di alcuni file ho usato solo la prima parte.")
        if self.ctx.testo.a_pagamento is False:
            parti.append("Lezione di prova: testo finto e voce robotica. Serve la chiave Mistral per quella vera.")
        with db.aperta(self.ctx.settings.db_path) as conn:
            conn.execute("UPDATE lezioni SET nota = ? WHERE id = ?", (" ".join(parti) or None, self.id))

    # --- 3. scrittura

    def _scrittura(self) -> None:
        lettura, piano = self._leggi("lettura.json"), self._leggi("piano.json")
        n = len(piano["blocchi"])
        sezioni = ["apertura"] + [f"blocco_{k:02d}" for k in range(1, n + 1)] + ["finale"]
        modello = self.ctx.settings.modello_scrittura
        for i, nome in enumerate(sezioni):
            self.avanza("scrittura", i, len(sezioni))
            if self._leggi(f"scrittura/{nome}.json"):
                continue
            s = self._richiesta(nome, lettura, piano)
            # Il testo appena scritto si salva subito: un Riprova non lo ripaga.
            grezzo = self._leggi(f"scrittura/{nome}.grezzo.json")
            if grezzo is None:
                grezzo = self._chiedi(s["compito"], s["messaggi"], modello, s["valida"], s["max_token"], s["cosa"], s["dati"])
                self._scrivi(f"scrittura/{nome}.grezzo.json", grezzo)
            self._scrivi(f"scrittura/{nome}.json", controllo.rivedi(
                self.ctx, grezzo, s["brani"], compito=s["compito"], messaggi=s["messaggi"], modello=modello,
                valida=s["struttura"], max_token=s["max_token"], cosa=s["cosa"], altro=s["altro"],
                materia_id=self.riga["materia_id"], lezione_id=self.id, prima=self.controlla, dati=s["dati"]))
        self.avanza("scrittura", len(sezioni), len(sezioni))

    def _richiesta(self, nome: str, lettura: dict, piano: dict) -> dict:
        """Quello che serve per scrivere una parte e per poterla controllare dopo."""
        n = len(piano["blocchi"])
        prova = self.ctx.testo.a_pagamento is False
        tipo = piano.get("tipo") or "spiegazione"
        # Con pochi documenti la lezione viene più corta: niente da aggiungere per riempire.
        fattore = max(0.3, min(1.0, float(piano.get("minuti_di_materiale") or lettura["durata_min"]) / lettura["durata_min"]))
        parole_blocco = max(60, int(misure.parole(misure.minuti_per_parte(tipo, lettura["durata_min"])) * fattore))
        tutti = lettura["brani"]
        sul_prof = prompt.scheda_in_parole(lettura.get("scheda"))
        comune = {"brani": [], "altro": sul_prof}
        if nome == "apertura":
            parole = misure.parole(misure.MINUTI_APERTURA if tipo == "spiegazione" else misure.MINUTI_APERTURA_BREVE)
            titoli = prompt._piano_breve(piano)
            return {**comune, "altro": f"{piano['titolo']}\n{titoli}\n{lettura['argomento']}\n{sul_prof}",
                    "compito": "apertura", "messaggi": prompt.messaggi_apertura(lettura, piano, parole),
                    "valida": valida_apertura, "struttura": valida_apertura, "max_token": 600, "cosa": "Testo · apertura",
                    "dati": {"lettura": lettura, "piano": piano, "parole": parole}}
        if nome == "finale" and tipo == "interrogazione":
            parole_e = misure.parole(misure.MINUTI_ERRORI)
            errori = "\n".join(piano.get("errori_da_evitare") or [])
            v = lambda o: valida_finale(o, 0, tipo)  # noqa: E731
            return {**comune, "altro": f"{sul_prof}\n{errori}", "compito": "finale",
                    "messaggi": prompt.messaggi_finale(lettura, piano, [], 0, parole_e), "valida": v, "struttura": v,
                    "max_token": 900, "cosa": "Testo · parte finale",
                    "dati": {"lettura": lettura, "piano": piano, "brani": []}}
        if nome == "finale":
            fonti = [f for d in piano["interrogazione"] for f in d["fonti"]]
            domande = " ".join(d["testo"] for d in piano["interrogazione"])
            brani = self._cerca(domande, tutti, CARATTERI_BLOCCO_MASSIMI, fonti)
            q = len(piano["interrogazione"])
            parole_r, parole_e = misure.parole(misure.MINUTI_RIEPILOGO), misure.parole(misure.MINUTI_ERRORI)
            parole_risposta = max(40, misure.parole(misure.MINUTI_PER_DOMANDA_RIPASSO) - 20)
            return {"brani": brani, "altro": f"{sul_prof}\n" + "\n".join(piano.get("errori_da_evitare") or []),
                    "compito": "finale", "messaggi": prompt.messaggi_finale(lettura, piano, brani, parole_r, parole_e, parole_risposta),
                    "valida": lambda o: valida_finale(o, q, tipo), "struttura": lambda o: valida_finale(o, 0, tipo),
                    "max_token": min(8000, 900 + (420 if tipo == "ripasso" else 280) * q), "cosa": "Testo · parte finale",
                    "dati": {"lettura": lettura, "piano": piano, "brani": brani}}
        k = int(nome.split("_")[1])
        blocco = piano["blocchi"][k - 1]
        if tipo == "interrogazione":
            domande = blocco.get("domande") or []
            cerca = " ".join([blocco["titolo"], *blocco["punti"], *(d["testo"] for d in domande)])
            fonti = list(dict.fromkeys(blocco["fonti"] + [f for d in domande for f in d.get("fonti") or []]))
            brani = self._cerca(cerca, tutti, CARATTERI_BLOCCO_MASSIMI, fonti)
            parole_risposta = max(40, misure.parole(misure.minuti_per_parte(tipo, lettura["durata_min"])) - 25)
            minime = 0 if prova else int(parole_risposta * len(domande) * 0.45)
            return {"brani": brani, "altro": sul_prof, "compito": "gruppo",
                    "messaggi": prompt.messaggi_gruppo(lettura, piano, k, brani, parole_risposta),
                    "valida": lambda o, nd=len(domande), m=minime: valida_gruppo(o, nd, m),
                    "struttura": lambda o: [] if isinstance(o.get("domande"), list) else ['servono "domande"'],
                    "max_token": min(8000, int(parole_risposta * len(domande) * 2.6) + 800),
                    "cosa": f"Testo · domande {k} di {n}",
                    "dati": {"lettura": lettura, "piano": piano, "k": k, "brani": brani, "parole": parole_risposta}}
        prima = self._leggi(f"scrittura/blocco_{k - 1:02d}.json") if k > 1 else None
        testi = _testi(prima.get("paragrafi")) if prima else []
        finale_prima = testi[-1]["testo"] if testi else None
        minime = 0 if prova else int(parole_blocco * 0.45)
        comuni = {"altro": sul_prof, "max_token": min(8000, int(parole_blocco * 2.6) + 800)}
        if tipo == "ripasso":
            cerca = " ".join([blocco["titolo"], *blocco["punti"]])
            brani = self._cerca(cerca, tutti, CARATTERI_BLOCCO_MASSIMI, blocco["fonti"])
            return {**comuni, "brani": brani, "compito": "parte",
                    "messaggi": prompt.messaggi_parte(lettura, piano, k, brani, parole_blocco, finale_prima),
                    "valida": lambda o, m=minime: valida_parte(o, m), "struttura": lambda o: valida_parte(o, 0),
                    "cosa": f"Testo · parte {k} di {n}",
                    "dati": {"lettura": lettura, "piano": piano, "k": k, "brani": brani, "parole": parole_blocco}}
        cerca = " ".join([blocco["titolo"], *blocco["punti"], blocco["domanda"]["testo"]])
        brani = self._cerca(cerca, tutti, CARATTERI_BLOCCO_MASSIMI, blocco["fonti"])
        return {**comuni, "brani": brani, "compito": "blocco",
                "messaggi": prompt.messaggi_blocco(lettura, piano, k, brani, parole_blocco, finale_prima),
                "valida": lambda o: valida_blocco(o, minime), "struttura": lambda o: valida_blocco(o, 0),
                "cosa": f"Testo · blocco {k} di {n}",
                "dati": {"lettura": lettura, "piano": piano, "k": k, "brani": brani, "parole": parole_blocco}}

    # --- 4. voce

    def _pezzi_fatti(self) -> dict[int, Path]:
        cartella = self.cartella / "pezzi"
        fatti = {}
        if cartella.is_dir():
            for f in cartella.iterdir():
                m = re.match(r"^pezzo-(\d{4})\.(mp3|wav)$", f.name)
                if m and f.stat().st_size > 0:
                    fatti[int(m.group(1))] = f
        return fatti

    def _voce(self) -> None:
        lettura, piano = self._leggi("lettura.json"), self._leggi("piano.json")
        capitoli = self._leggi("capitoli.json")
        if capitoli is None:
            n = len(piano["blocchi"])
            scritti = {nome: self._leggi(f"scrittura/{nome}.json")
                       for nome in ["apertura", "finale"] + [f"blocco_{k:02d}" for k in range(1, n + 1)]}
            capitoli = componi(lettura, piano, scritti)
            self._scrivi("capitoli.json", capitoli)
        pezzi = self._leggi("copione.json")
        if pezzi is None:
            pezzi = copione(capitoli)
            self._scrivi("copione.json", pezzi)
        scelta = self.scelta
        voce = scelta.fornitore
        prezzi = self.ctx.cassa.prezzi
        modello_voce = self.ctx.settings.modello_voce
        totale = len(pezzi)
        fatti = self._pezzi_fatti()
        contati = [len(fatti)]
        self.avanza("voce", len(fatti), totale)
        (self.cartella / "pezzi").mkdir(parents=True, exist_ok=True)
        ferma = threading.Event()

        def fai(p: dict) -> None:
            if ferma.is_set():
                return
            caratteri = voce.caratteri(p["testo"], p["emozione"], p["lento"])
            if voce.fornitore == "mistral":
                stima, gratis = prezzi.costo_voce(modello_voce, caratteri), 0
            elif voce.fornitore == "azure":
                stima, gratis = prezzi.costo_azure(caratteri), caratteri
            else:
                stima, gratis = 0.0, 0

            def registra(ricevuta, audio):
                if voce.fornitore == "mistral":
                    ricevuta.registra(audio.caratteri, "caratteri", prezzi.costo_voce(modello_voce, audio.caratteri))
                elif voce.fornitore == "azure":
                    ricevuta.registra(audio.caratteri, "caratteri", prezzi.costo_azure(audio.caratteri))

            audio = self.ctx.cassa.chiama(
                cosa=f"Voce · pezzo {p['n']} di {totale}", fornitore=voce.fornitore, stima_eur=stima,
                caratteri_gratis=gratis, quantita_stimata=caratteri, unita="caratteri", lezione_id=self.id,
                materia_id=self.riga["materia_id"], prima=self.controlla, registra=registra,
                fare=lambda: voce.sintetizza(p["testo"], emozione=p["emozione"], lento=p["lento"],
                                             voce=scelta.nome_voce),
            )
            percorso = self.cartella / "pezzi" / f"pezzo-{p['n']:04d}.{audio.estensione}"
            provvisorio = percorso.with_suffix(".tmp")
            with open(provvisorio, "wb") as f:
                f.write(audio.contenuto)
            os.replace(provvisorio, percorso)
            with self._lock:
                contati[0] += 1
                fatto = contati[0]
            self.avanza("voce", fatto, totale)

        da_fare = [p for p in pezzi if p["n"] not in fatti]
        errori: list[BaseException] = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, voce.parallelo)) as pool:
            futuri = [pool.submit(fai, p) for p in da_fare]
            for futuro in concurrent.futures.as_completed(futuri):
                try:
                    futuro.result()
                except Exception as e:  # il primo errore ferma i pezzi non ancora partiti
                    ferma.set()
                    errori.append(e)
        if errori:
            # Un errore vero (tetto, fornitore) conta più di un "annullata" arrivato insieme.
            veri = [e for e in errori if not isinstance(e, Annullata)]
            raise (veri or errori)[0]
        if len(self._pezzi_fatti()) < totale:
            raise ErroreLezione("Mancano alcuni pezzi di voce. Tocca Riprova.")

    # --- 5. montaggio

    def _montaggio(self) -> None:
        capitoli = self._leggi("capitoli.json")
        pezzi = self._leggi("copione.json")
        fatti = self._pezzi_fatti()
        self.avanza("montaggio", 0, 1)
        risultato = self.ctx.montatore.monta(
            [(fatti[p["n"]], p["pausa_prima"]) for p in pezzi], self.cartella / "lezione.mp3",
            self.cartella / "montaggio",
        )
        tempi = risultato.tempi
        # Tempi dei capitoli e delle domande dalle durate vere dei pezzi.
        cap_tempi: dict[int, list[float]] = {}
        seg_tempi: dict[tuple[int, int], list[float]] = {}
        for p, (inizio, fine) in zip(pezzi, tempi):
            c = cap_tempi.setdefault(p["capitolo"], [inizio, fine])
            c[1] = fine
            s = seg_tempi.setdefault((p["capitolo"], p["segmento"]), [inizio, fine])
            s[1] = fine
        uscita_capitoli = [{"n": c["n"], "titolo": c["titolo"], "inizio_s": round(cap_tempi[c["n"]][0], 1),
                            "fine_s": round(cap_tempi[c["n"]][1], 1)} for c in capitoli]
        domande = []
        for c in capitoli:
            for s_i, s in enumerate(c["segmenti"]):
                if s["ruolo"] != "domanda":
                    continue
                risposta = c["segmenti"][s_i + 1]
                q = seg_tempi[(c["n"], s_i)]
                r = seg_tempi[(c["n"], s_i + 1)]
                domande.append({"tipo": s["domanda"], "capitolo": c["n"], "testo": s["testo"].removeprefix("Domanda: "),
                                "risposta": risposta["testo"].removeprefix("Risposta: "),
                                "inizio_s": round(q[0], 1), "fine_domanda_s": round(q[1], 1),
                                "inizio_risposta_s": round(r[0], 1), "fine_s": round(r[1], 1)})
        testo = [{"capitolo": c["n"], "paragrafi": [s["testo"] for s in c["segmenti"]]} for c in capitoli]
        mp3 = self.cartella / "lezione.mp3"
        h = hashlib.sha256()
        with open(mp3, "rb") as f:
            for pezzo in iter(lambda: f.read(1024 * 1024), b""):
                h.update(pezzo)
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            r = conn.execute("SELECT stato FROM lezioni WHERE id = ?", (self.id,)).fetchone()
            if r is None or r["stato"] != "in_preparazione":
                raise Annullata()
            conn.execute("DELETE FROM domande WHERE lezione_id = ?", (self.id,))
            for i, d in enumerate(domande, start=1):
                conn.execute(
                    "INSERT INTO domande (lezione_id, tipo, capitolo, n, di, testo, risposta, inizio_s, fine_domanda_s,"
                    " inizio_risposta_s, fine_s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (self.id, d["tipo"], d["capitolo"], i, len(domande), d["testo"], d["risposta"], d["inizio_s"],
                     d["fine_domanda_s"], d["inizio_risposta_s"], d["fine_s"]),
                )
            conn.execute(
                "UPDATE lezioni SET stato = 'pronta', fase = NULL, fatto = 0, totale = 0, errore = NULL, pronta_il = ?,"
                " durata_s = ?, audio_byte = ?, audio_sha256 = ?, capitoli = ?, testo = ? WHERE id = ?",
                (clock.adesso(), int(round(risultato.durata)), mp3.stat().st_size, h.hexdigest(),
                 json.dumps(uscita_capitoli, ensure_ascii=False), json.dumps(testo, ensure_ascii=False), self.id),
            )
            db.aggiungi_novita(conn, "lezione_pronta", f"La lezione di {self.riga['materia_nome']} è pronta",
                               lezione_id=self.id, materia_id=self.riga["materia_id"])
        # I pezzi non servono più: sul NAS lo spazio conta.
        shutil.rmtree(self.cartella / "pezzi", ignore_errors=True)
        shutil.rmtree(self.cartella / "montaggio", ignore_errors=True)
