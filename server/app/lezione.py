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

from . import ai, clock, db, misure, prompt, ripasso
from . import scheda as schede
from .audio import (PAUSA_CAPITOLI, PAUSA_DENTRO_PARAGRAFO, PAUSA_DOPO_DOMANDA, PAUSA_PARAGRAFI, Montatore,
                    taglia_in_pezzi)
from .spesa import (APPUNTI_MASSIMI, CARATTERI_APPUNTI_LETTI, CARATTERI_BLOCCO_MASSIMI, CARATTERI_VERIFICHE_MASSIMI,
                    Cassa)

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
BRANO_CARATTERI = 1200
CAPITOLO_FINALE = "Ripasso e interrogazione"


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


# --------------------------------------------------------------------------- utilità di testo

def dividi_in_brani(testo: str, massimo: int = BRANO_CARATTERI) -> list[str]:
    paragrafi = [p.strip() for p in re.split(r"\n\s*\n", testo) if p.strip()]
    piccoli: list[str] = []
    for p in paragrafi:
        if len(p) <= massimo:
            piccoli.append(p)
        else:
            piccoli.extend(taglia_in_pezzi(p.replace("\n", " "), massimo))
    brani, attuale = [], ""
    for p in piccoli:
        if attuale and len(attuale) + 2 + len(p) > massimo:
            brani.append(attuale)
            attuale = p
        else:
            attuale = f"{attuale}\n\n{p}".strip()
    if attuale:
        brani.append(attuale)
    return brani


def _taglia(testo: str, caratteri: int) -> str:
    if len(testo) <= caratteri:
        return testo
    pezzo = testo[:caratteri]
    taglio = max(pezzo.rfind("\n"), pezzo.rfind(". "))
    return pezzo[: taglio + 1] if taglio > caratteri // 2 else pezzo


_PAROLA = re.compile(r"[a-zàèéìòù]{4,}", re.IGNORECASE)


def scegli_brani(domanda: str, brani: list[dict], massimo: int, gia: list[str] | None = None) -> list[dict]:
    """I brani indicati (gia) più quelli che somigliano di più alla domanda, fino al massimo
    di caratteri, nell'ordine del materiale."""
    parole = {p.lower() for p in _PAROLA.findall(domanda)}
    per_id = {b["id"]: b for b in brani}
    scelti = [i for i in (gia or []) if i in per_id]
    totale = sum(len(per_id[i]["testo"]) for i in scelti)
    if totale < massimo // 3:
        punteggi = sorted(
            ((len(parole & {p.lower() for p in _PAROLA.findall(b["testo"])}), -n, b["id"])
             for n, b in enumerate(brani) if b["id"] not in scelti),
            reverse=True,
        )
        for punti, _, i in punteggi:
            if punti == 0 and scelti:
                break
            if totale + len(per_id[i]["testo"]) > massimo:
                continue
            scelti.append(i)
            totale += len(per_id[i]["testo"])
            if totale >= massimo // 2:
                break
    uscita, totale = [], 0
    ordine = {b["id"]: n for n, b in enumerate(brani)}
    for i in sorted(scelti, key=lambda x: ordine[x]):
        if totale + len(per_id[i]["testo"]) > massimo and uscita:
            continue
        uscita.append(per_id[i])
        totale += len(per_id[i]["testo"])
    return uscita


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


def valida_piano(o: dict, n: int, q: int) -> list[str]:
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
            d = b.get("domanda")
            if not (_testo_ok(d) or (isinstance(d, dict) and _testo_ok(d.get("testo")))):
                errori.append(f"il blocco {i} non ha la domanda di fine blocco")
    domande = o.get("interrogazione")
    if not isinstance(domande, list) or len(domande) < max(1, q - 1):
        errori.append(f'servono {q} domande in "interrogazione"')
    elif any(not (_testo_ok(d) or (isinstance(d, dict) and _testo_ok(d.get("testo")))) for d in domande):
        errori.append("ogni domanda dell'interrogazione deve avere il suo testo")
    return errori


def valida_selezione(o: dict, validi: set[str]) -> list[str]:
    scelti = o.get("scelti")
    if not isinstance(scelti, list) or not any(str(i) in validi for i in scelti):
        return ['servono gli id dei pezzi scelti in "scelti", per esempio ["B3", "B4"]']
    return []


def _tipo_domanda(v) -> str:
    t = str(v or "").strip().lower().replace(" ", "_")
    return t if t in prompt.TIPI_DOMANDA else "aperta"


def normalizza_piano(o: dict, n: int, q: int, lettura: dict) -> dict:
    validi = {b["id"] for b in lettura["brani"]}
    blocchi = []
    for b in o["blocchi"][:n]:
        d = b.get("domanda")
        d = {"testo": d, "tipo": "aperta"} if isinstance(d, str) else d
        punti = [t for t in (pulisci_parlato(p) for p in _paragrafi(b.get("punti"))) if t][:12]
        blocchi.append({
            "titolo": pulisci_parlato(b["titolo"])[:80],
            "punti": punti,
            "fonti": [str(f) for f in _paragrafi(b.get("fonti")) if str(f) in validi][:12],
            "domanda": {"testo": pulisci_parlato(d["testo"]), "tipo": _tipo_domanda(d.get("tipo"))},
        })
    domande = []
    for d in o["interrogazione"][:q]:
        d = {"testo": d} if isinstance(d, str) else d
        domande.append({"testo": pulisci_parlato(d["testo"]), "tipo": _tipo_domanda(d.get("tipo")),
                        "fonti": [str(f) for f in _paragrafi(d.get("fonti")) if str(f) in validi][:8]})
    try:
        minuti = float(o.get("minuti_di_materiale", lettura["durata_min"]))
    except (TypeError, ValueError):
        minuti = float(lettura["durata_min"])
    errori = [t for t in (pulisci_parlato(e) for e in _paragrafi(o.get("errori_da_evitare"))) if t][:3]
    return {
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


def valida_finale(o: dict, q: int) -> list[str]:
    errori = []
    if not _paragrafi(o.get("riepilogo")) or not all(_testo_ok(p) for p in _paragrafi(o.get("riepilogo"))):
        errori.append('manca "riepilogo"')
    domande = o.get("domande")
    if not isinstance(domande, list) or len(domande) < q:
        errori.append(f'servono {q} domande in "domande", ognuna con "testo" e "risposta"')
    elif any(not (isinstance(d, dict) and _testo_ok(d.get("testo")) and _testo_ok(d.get("risposta"))) for d in domande):
        errori.append('ogni domanda deve avere "testo" e "risposta"')
    if not _paragrafi(o.get("errori")) or not all(_testo_ok(e) for e in _paragrafi(o.get("errori"))):
        errori.append('mancano gli "errori"')
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


def componi(lettura: dict, piano: dict, scritti: dict) -> list[dict]:
    """I capitoli con i loro segmenti (paragrafi, domande, risposte), nell'ordine dell'audio."""
    prof = (lettura["materia"].get("prof") or "").strip()
    il_prof = f"il prof {prof}" if prof else "il prof"
    capitoli = []
    indice_domanda = 0
    for k, blocco in enumerate(piano["blocchi"], start=1):
        segmenti = []
        if k == 1:
            for p in _testi(scritti["apertura"].get("paragrafi")):
                segmenti.append(_segmento("paragrafo", "apertura", p["testo"], p["testo"]))
        s = scritti[f"blocco_{k:02d}"]
        for i, p in enumerate(_testi(s.get("paragrafi"))):
            tipo = "aggancio" if i == 0 and p["tipo"] == "aggancio" else p["tipo"]
            segmenti.append(_segmento("paragrafo", tipo, p["testo"], p["testo"], lento=tipo == "definizione"))
        domanda = pulisci_parlato(s["domanda"])
        risposta = pulisci_parlato(s["risposta"])
        indice_domanda += 1
        segmenti.append(_segmento("domanda", "domanda_blocco", f"Una domanda per te. {domanda}", f"Domanda: {domanda}",
                                  domanda="blocco", indice=indice_domanda))
        segmenti.append(_segmento("risposta", "risposta", f"Ecco la risposta. {risposta}", f"Risposta: {risposta}",
                                  indice=indice_domanda))
        capitoli.append({"n": k, "titolo": blocco["titolo"], "segmenti": segmenti})

    f = scritti["finale"]
    segmenti = []
    for p in _paragrafi(f.get("riepilogo")):
        t = pulisci_parlato(p)
        if t:
            segmenti.append(_segmento("paragrafo", "riepilogo", t, t))
    intro = (f"Adesso ti interrogo come farebbe {il_prof}. Dopo ogni domanda rispondi a voce, "
             f"poi ascolta la risposta.")
    segmenti.append(_segmento("paragrafo", "interrogazione", intro, intro))
    domande = [d for d in f.get("domande") or [] if isinstance(d, dict)][: len(piano["interrogazione"])]
    for i, d in enumerate(domande):
        testo = pulisci_parlato(d["testo"])
        risposta = pulisci_parlato(d["risposta"])
        ordinale = "Ultima" if i == len(domande) - 1 and len(domande) > 1 else ORDINALI[min(i, len(ORDINALI) - 1)]
        indice_domanda += 1
        segmenti.append(_segmento("domanda", "domanda_interrogazione", f"{ordinale} domanda. {testo}",
                                  f"Domanda: {testo}", domanda="interrogazione", indice=indice_domanda))
        segmenti.append(_segmento("risposta", "risposta", f"Ecco la risposta. {risposta}", f"Risposta: {risposta}",
                                  indice=indice_domanda))
    errori = [pulisci_parlato(e) for e in _paragrafi(f.get("errori")) if pulisci_parlato(e)]
    if errori:
        lead = f"Prima di chiudere, tre errori da non fare con {il_prof}."
        segmenti.append(_segmento("paragrafo", "interrogazione", lead, lead))
        for e in errori[:3]:
            segmenti.append(_segmento("paragrafo", "errori", e, e))
    chiusura = pulisci_parlato(f.get("chiusura")) or "Per oggi è tutto. Ripeti le risposte a voce prima della verifica."
    segmenti.append(_segmento("paragrafo", "chiusura", chiusura, chiusura))
    capitoli.append({"n": len(capitoli) + 1, "titolo": CAPITOLO_FINALE, "segmenti": segmenti})
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
        """Contratto 15.1: di base tutti gli appunti/libro della materia e tutte le verifiche e
        interrogazioni (come esempi di domande). Se Andrea ha scelto il materiale con "Cambia",
        solo quello (appunti e verifiche compresi)."""
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
        brani, letti, tagliato = [], 0, False
        for r in appunti:
            testo = _taglia(r["testo"], max(0, CARATTERI_APPUNTI_LETTI - letti))
            tagliato = tagliato or len(testo) < len(r["testo"])
            letti += len(testo)
            for pezzo in dividi_in_brani(testo):
                brani.append({"id": f"B{len(brani) + 1}", "materiale_id": r["id"], "titolo": r["titolo"],
                              "tipo": r["tipo"], "data": r["data"], "testo": pezzo})
        caratteri_appunti = sum(len(b["testo"]) for b in brani)
        resto, n_verifiche = CARATTERI_VERIFICHE_MASSIMI, 0
        for r in verifiche:
            testo = _taglia(r["testo"], resto)
            if not testo:
                break
            resto -= len(testo)
            for pezzo in dividi_in_brani(testo):
                n_verifiche += 1
                brani.append({"id": f"V{n_verifiche}", "materiale_id": r["id"], "titolo": r["titolo"],
                              "tipo": r["tipo"], "data": r["data"], "testo": pezzo})
        self._scrivi("lettura.json", {
            "argomento": self.riga["argomento"],
            "durata_min": self.riga["durata_min"],
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
        """I brani che piano e scrittura possono usare: gli appunti scelti (tutti, se non
        c'è stata una scelta) più le verifiche."""
        selezione = self._leggi("selezione.json")
        if selezione is None:
            return lettura["brani"]
        scelti = set(selezione["scelti"])
        return [b for b in lettura["brani"] if b["id"].startswith("V") or b["id"] in scelti]

    # --- 2. piano

    def _seleziona(self, lettura: dict) -> None:
        """Contratto 15.1: oltre ~60.000 caratteri di appunti, il modello piccolo sceglie prima
        i pezzi sull'argomento guardando solo titoli e inizi; la scrittura riceve solo quelli."""
        if lettura["caratteri_appunti"] <= APPUNTI_MASSIMI or self._leggi("selezione.json") is not None:
            return
        appunti = [b for b in lettura["brani"] if b["id"].startswith("B")]
        lungo = 200 if len(appunti) <= 600 else 100
        indice = [{"id": b["id"], "titolo": b["titolo"], "caratteri": len(b["testo"]),
                   "inizio": " ".join(b["testo"].split())[:lungo]} for b in appunti]
        validi = {b["id"] for b in appunti}
        oggetto = self._chiedi(
            "selezione", prompt.messaggi_selezione(lettura["argomento"], indice, APPUNTI_MASSIMI),
            self.ctx.settings.modello_piano, lambda o: valida_selezione(o, validi), 2000, "Scelta degli appunti",
            {"argomento": lettura["argomento"], "brani": appunti, "massimo": APPUNTI_MASSIMI},
        )
        voluti = {str(i) for i in oggetto["scelti"]}
        scelti, totale = [], 0
        for b in appunti:  # nell'ordine degli appunti, fino al massimo (con un po' di margine)
            if b["id"] in voluti and (not scelti or totale + len(b["testo"]) <= APPUNTI_MASSIMI * 1.2):
                scelti.append(b["id"])
                totale += len(b["testo"])
        self._scrivi("selezione.json", {"scelti": scelti})

    def _piano(self) -> None:
        if self._leggi("piano.json"):
            return
        lettura = self._leggi("lettura.json")
        self._seleziona(lettura)
        per_piano = {**lettura, "brani": self._brani(lettura)}
        durata = lettura["durata_min"]
        n, q = misure.n_blocchi(durata), misure.n_interrogazione(durata)
        minuti_blocco = misure.minuti_per_blocco(durata)
        oggetto = self._chiedi(
            "piano", prompt.messaggi_piano(per_piano, n, q, minuti_blocco), self.ctx.settings.modello_piano,
            lambda o: valida_piano(o, n, q), min(8000, 800 + 350 * n + 150 * q), "Piano della lezione",
            {"lettura": per_piano, "n_blocchi": n, "n_interrogazione": q, "minuti_blocco": minuti_blocco},
        )
        piano = normalizza_piano(oggetto, n, q, per_piano)
        self._scrivi("piano.json", piano)
        parti = []
        if piano["minuti_di_materiale"] < durata - 1:
            parti.append(f"Gli appunti bastavano per circa {max(1, round(piano['minuti_di_materiale']))} minuti: "
                         f"ho aggiunto esempi e domande.")
        if self._leggi("selezione.json") is not None:
            parti.append("Gli appunti erano tanti: ho usato le parti che riguardano l'argomento.")
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
        prova = self.ctx.testo.a_pagamento is False
        modello = self.ctx.settings.modello_scrittura
        parole_blocco = misure.parole(misure.minuti_per_blocco(lettura["durata_min"]))
        for i, nome in enumerate(sezioni):
            self.avanza("scrittura", i, len(sezioni))
            if self._leggi(f"scrittura/{nome}.json"):
                continue
            if nome == "apertura":
                parole = misure.parole(misure.MINUTI_APERTURA)
                oggetto = self._chiedi("apertura", prompt.messaggi_apertura(lettura, piano, parole), modello,
                                       valida_apertura, 600, "Testo · apertura",
                                       {"lettura": lettura, "piano": piano, "parole": parole})
            elif nome == "finale":
                fonti = [f for d in piano["interrogazione"] for f in d["fonti"]]
                domande = " ".join(d["testo"] for d in piano["interrogazione"])
                brani = scegli_brani(domande, self._brani(lettura), CARATTERI_BLOCCO_MASSIMI, fonti)
                q = len(piano["interrogazione"])
                parole_r, parole_e = misure.parole(misure.MINUTI_RIEPILOGO), misure.parole(misure.MINUTI_ERRORI)
                oggetto = self._chiedi(
                    "finale", prompt.messaggi_finale(lettura, piano, brani, parole_r, parole_e), modello,
                    lambda o: valida_finale(o, q), min(8000, 900 + 280 * q), "Testo · parte finale",
                    {"lettura": lettura, "piano": piano, "brani": brani},
                )
            else:
                k = int(nome.split("_")[1])
                blocco = piano["blocchi"][k - 1]
                cerca = " ".join([blocco["titolo"], *blocco["punti"], blocco["domanda"]["testo"]])
                brani = scegli_brani(cerca, self._brani(lettura), CARATTERI_BLOCCO_MASSIMI, blocco["fonti"])
                prima = self._leggi(f"scrittura/blocco_{k - 1:02d}.json") if k > 1 else None
                finale_prima = None
                if prima:
                    testi = _testi(prima.get("paragrafi"))
                    finale_prima = testi[-1]["testo"] if testi else None
                minime = 0 if prova else int(parole_blocco * 0.45)
                oggetto = self._chiedi(
                    "blocco", prompt.messaggi_blocco(lettura, piano, k, brani, parole_blocco, finale_prima), modello,
                    lambda o: valida_blocco(o, minime), min(8000, int(parole_blocco * 2.6) + 800),
                    f"Testo · blocco {k} di {n}",
                    {"lettura": lettura, "piano": piano, "k": k, "brani": brani, "parole": parole_blocco},
                )
            self._scrivi(f"scrittura/{nome}.json", oggetto)
        self.avanza("scrittura", len(sezioni), len(sezioni))

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
