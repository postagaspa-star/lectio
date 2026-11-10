"""La memoria sui documenti (contratto 17).

Tutto quello che Lectio scrive parte da qui. Ogni materiale letto si divide in pezzi (tabella
`pezzi`) e ogni pezzo riceve un vettore che ne riassume il significato. Quando serve scrivere
qualcosa si cercano i pezzi giusti e al modello arrivano solo quelli, nell'ordine di priorità:
pagine del libro, verifiche, interrogazioni, appunti.

Il vettore costa pochissimo e si fa una volta sola. Senza chiave, a tetto finito o con Mistral
giù la ricerca va avanti lo stesso, guardando le parole in comune."""

import array
import hashlib
import logging
import math
import re
import sqlite3
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import httpx

from . import db, misure
from .audio import taglia_in_pezzi
from .config import MISTRAL_URL
from .rete import ErroreFornitore, controlla, invia
from .spesa import TettoRaggiunto

log = logging.getLogger("uvicorn.error")

PEZZO_CARATTERI = 1200
CARATTERI_MASSIMI = 1_000_000
RANGO = {"libro": 1, "verifica": 2, "interrogazione": 3, "appunti": 4, "altro": 4}
NOME_RANGO = {1: "pagine del libro", 2: "verifica", 3: "interrogazione", 4: "appunti"}
QUOTE = {1: 0.50, 2: 0.20, 3: 0.15, 4: 0.15}
DEL_PROF = ("verifica", "interrogazione")
MODELLO_VETTORI = "mistral-embed"
PEZZI_PER_CHIAMATA = 32
DIMENSIONI_FINTE = 256
SOGLIA_RELATIVA = 0.4
PESO_SIGNIFICATO = 0.7

_ETICHETTA = re.compile(
    r"^\s*(?:#+\s*|\*+\s*)*(domanda\b[^:]*|testo|mia risposta|correzioni del prof|punti presi|commento del prof|materia)"
    r"\s*\**\s*:", re.IGNORECASE)


def dividi_in_brani(testo: str, massimo: int = PEZZO_CARATTERI) -> list[str]:
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


def testo_per_fatti(testo: str, tipo: str) -> str:
    """Nelle verifiche contano domande e correzioni: quello che ha risposto lo studente può
    essere sbagliato, quindi nei testi con le etichette non entra in memoria."""
    if tipo not in DEL_PROF:
        return testo
    righe, nella_risposta = [], False
    for riga in testo.split("\n"):
        m = _ETICHETTA.match(riga)
        if m:
            nella_risposta = m.group(1).strip().lower() == "mia risposta"
        if not nella_risposta:
            righe.append(riga)
    return "\n".join(righe)


# --------------------------------------------------------------------------- i pezzi

def _testi_pagine(percorso: Path) -> list[str]:
    """Le pagine di un PDF, per poter dire "pag. 42". Se non si può, niente numero di pagina."""
    try:
        from pypdf import PdfReader

        return [(p.extract_text() or "") for p in PdfReader(str(percorso)).pages]
    except Exception:
        return []


def _solo_lettere(testo: str) -> str:
    return " ".join(re.findall(r"[a-zàèéìòù0-9]+", testo.lower()))


def _pagina_di(pezzo: str, pagine: list[str]) -> int | None:
    inizio = _solo_lettere(pezzo)[:60]
    if len(inizio) < 20:
        return None
    for i, p in enumerate(pagine, start=1):
        if inizio in p:
            return i
    return None


def indicizza(conn: sqlite3.Connection, m: sqlite3.Row | dict, dati_dir: str) -> int:
    """Divide un materiale letto in pezzi (rifà i suoi, se c'erano)."""
    pagine: list[str] = []
    if m["formato"] == "pdf" and m["percorso"] and m["tipo"] == "libro":
        pagine = [_solo_lettere(p) for p in _testi_pagine(Path(dati_dir) / m["percorso"])]
    pezzi = dividi_in_brani(testo_per_fatti(m["testo"] or "", m["tipo"]))
    conn.execute("DELETE FROM pezzi WHERE materiale_id = ?", (m["id"],))
    rango = RANGO.get(m["tipo"], 4)
    for n, testo in enumerate(pezzi, start=1):
        conn.execute("INSERT INTO pezzi (materia_id, materiale_id, n, pagina, rango, testo) VALUES (?, ?, ?, ?, ?, ?)",
                     (m["materia_id"], m["id"], n, _pagina_di(testo, pagine) if pagine else None, rango, testo))
    return len(pezzi)


SENZA_PEZZI = ("SELECT * FROM materiali m WHERE m.stato = 'pronto' AND length(m.testo) > 0"
               " AND NOT EXISTS (SELECT 1 FROM pezzi p WHERE p.materiale_id = m.id)")


def assicura(conn: sqlite3.Connection, materia_id: int, dati_dir: str) -> None:
    """Un materiale appena letto può non avere ancora i suoi pezzi: si fanno adesso."""
    for m in conn.execute(SENZA_PEZZI + " AND m.materia_id = ?", (materia_id,)).fetchall():
        indicizza(conn, m, dati_dir)


def brani_di(conn: sqlite3.Connection, materia_id: int, ristretti: list[int] | None = None,
             massimo: int = CARATTERI_MASSIMI) -> tuple[list[dict], bool]:
    """Tutti i pezzi utilizzabili, in ordine di priorità: (brani, tagliato?)."""
    righe = conn.execute(
        "SELECT p.id AS pezzo_id, p.n, p.pagina, p.rango, p.testo, m.id AS materiale_id, m.titolo, m.tipo, m.data"
        " FROM pezzi p JOIN materiali m ON m.id = p.materiale_id WHERE p.materia_id = ? AND m.stato = 'pronto'"
        " ORDER BY p.rango, m.data DESC, m.id, p.n", (materia_id,)).fetchall()
    scelti = set(ristretti) if ristretti else None
    brani, totale, tagliato, conti = [], 0, False, {"B": 0, "V": 0}
    for r in righe:
        if scelti is not None and r["materiale_id"] not in scelti:
            continue
        if totale + len(r["testo"]) > massimo:
            tagliato = True
            continue
        totale += len(r["testo"])
        lettera = "V" if r["tipo"] in DEL_PROF else "B"
        conti[lettera] += 1
        brani.append({"id": f"{lettera}{conti[lettera]}", "pezzo_id": r["pezzo_id"], "n": r["n"], "pagina": r["pagina"],
                      "rango": r["rango"], "materiale_id": r["materiale_id"], "titolo": r["titolo"], "tipo": r["tipo"],
                      "data": r["data"], "testo": testo_pulito(r["testo"])})
    return brani, tagliato


def testo_pulito(testo: str) -> str:
    """Le lettere legate dei PDF ("ﬁ", "ﬂ") tornano lettere normali: se no "ﬁne" non si trova cercando "fine"."""
    return unicodedata.normalize("NFKC", testo or "")


def _impronte(testo: str) -> set[tuple[str, ...]]:
    parole = re.findall(r"[a-zàèéìòù0-9]+", testo_pulito(testo).lower())
    return {tuple(parole[i:i + 4]) for i in range(len(parole) - 3)}


def senza_doppioni(brani: list[dict], soglia: float = 0.7) -> list[dict]:
    """Toglie i pezzi che dicono quasi parola per parola cose già dette da un pezzo tenuto: per esempio la
    stessa pagina fotografata due volte. Resta il primo, che in `brani_di` è quello con la priorità più alta.
    I pezzi corti restano sempre: troppo poche parole per dire che sono doppi."""
    viste: set[tuple[str, ...]] = set()
    tenuti = []
    for b in brani:
        impronte = _impronte(b["testo"])
        if len(impronte) >= 20 and len(impronte & viste) / len(impronte) >= soglia:
            continue
        viste |= impronte
        tenuti.append(b)
    return tenuti


def _data_breve(iso: str | None) -> str:
    m = re.match(r"\d{4}-(\d{2})-(\d{2})", iso or "")
    return f"{m.group(2)}/{m.group(1)}" if m else ""


def etichetta(b: dict) -> str:
    if b.get("tipo") == "libro":
        return f"Libro, pag. {b['pagina']}" if b.get("pagina") else f"Libro: {str(b.get('titolo') or '')[:50]}"
    if b.get("tipo") in DEL_PROF:
        parola = "Verifica" if b["tipo"] == "verifica" else "Interrogazione"
        data = _data_breve(b.get("data"))
        return f"{parola} del {data}" if data else parola
    return str(b.get("titolo") or "Appunti")[:60]


def fonte(b: dict) -> dict:
    return {"materiale_id": b["materiale_id"], "titolo": b.get("titolo"), "tipo": b.get("tipo"),
            "pagina": b.get("pagina"), "etichetta": etichetta(b)}


def fonti_di(ids, brani: list[dict]) -> list[dict]:
    """Le fonti (una per materiale e pagina) dei pezzi indicati."""
    per_id = {b["id"]: b for b in brani}
    uscita, visti = [], set()
    for i in ids or []:
        b = per_id.get(str(i))
        if b is None:
            continue
        f = fonte(b)
        if f["etichetta"] not in visti:
            visti.add(f["etichetta"])
            uscita.append(f)
    return uscita


# --------------------------------------------------------------------------- i vettori

@dataclass
class RispostaVettori:
    vettori: list[list[float]]
    token: int


class VettoriMistral:
    nome = "mistral"
    a_pagamento = True
    modello = MODELLO_VETTORI

    def __init__(self, chiave: str, transport: httpx.BaseTransport | None = None, timeout: float = 90.0,
                 url: str = MISTRAL_URL):
        self._chiave, self._transport, self._timeout, self._url = chiave, transport, timeout, url

    def vettori(self, testi: list[str]) -> RispostaVettori:
        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/embeddings", json={"model": self.modello, "input": testi},
                              headers={"Authorization": f"Bearer {self._chiave}"})

        d = controlla(invia("Mistral", manda), "Mistral").json()
        dati = sorted(d["data"], key=lambda x: x.get("index", 0))
        return RispostaVettori([x["embedding"] for x in dati], int((d.get("usage") or {}).get("prompt_tokens") or 0))


class VettoriFinti:
    """In modalità prova: un vettore fatto contando le parole. Nessuna chiamata."""
    nome = "prova"
    a_pagamento = False
    modello = "prova"

    def vettori(self, testi: list[str]) -> RispostaVettori:
        uscita = []
        for t in testi:
            v = [0.0] * DIMENSIONI_FINTE
            for p in re.findall(r"[a-zàèéìòù]{3,}", t.lower()):
                v[int(hashlib.sha256(p.encode()).hexdigest()[:8], 16) % DIMENSIONI_FINTE] += 1.0
            uscita.append(v)
        return RispostaVettori(uscita, 0)


def _unitario(v: list[float]) -> array.array:
    norma = math.sqrt(sum(x * x for x in v)) or 1.0
    return array.array("f", (x / norma for x in v))


def _chiedi_vettori(ctx, testi: list[str], *, materia_id: int | None, lezione_id: int | None = None, cosa: str,
                    prima=None) -> list[array.array]:
    fornitore = ctx.vettori
    stima, token_stimati = 0.0, 0
    prezzi = ctx.cassa.prezzi
    if fornitore.a_pagamento:
        token_stimati = int(sum(len(t) for t in testi) / misure.CARATTERI_PER_TOKEN) + 10
        stima = prezzi.costo_testo(fornitore.modello, token_stimati, 0)

    def registra(ricevuta, risposta):
        if fornitore.a_pagamento:
            ricevuta.registra(risposta.token or token_stimati, "token",
                              prezzi.costo_testo(fornitore.modello, risposta.token or token_stimati, 0))

    risposta = ctx.cassa.chiama(cosa=cosa, fornitore=fornitore.nome, stima_eur=stima, quantita_stimata=token_stimati,
                                unita="token", materia_id=materia_id, lezione_id=lezione_id, prima=prima,
                                fare=lambda: fornitore.vettori(testi), registra=registra)
    return [_unitario(v) for v in risposta.vettori]


DA_VETTORIZZARE = "SELECT id, materia_id, testo FROM pezzi WHERE modello IS NULL OR modello != ? ORDER BY materia_id, id"


def vettorizza(ctx) -> int:
    """Un gruppo di pezzi senza vettore. Restituisce quanti ne ha fatti (0 = non si può, si riprova più tardi)."""
    fornitore = ctx.vettori
    with db.aperta(ctx.settings.db_path) as conn:
        righe = conn.execute(DA_VETTORIZZARE + " LIMIT ?", (fornitore.modello, PEZZI_PER_CHIAMATA)).fetchall()
    if not righe:
        return 0
    materia_id = righe[0]["materia_id"]
    righe = [r for r in righe if r["materia_id"] == materia_id]
    try:
        vettori = _chiedi_vettori(ctx, [r["testo"] for r in righe], materia_id=materia_id, cosa="Memoria sui documenti")
    except (TettoRaggiunto, ErroreFornitore) as e:
        log.warning("memoria: vettori rimandati (%s)", type(e).__name__)
        return 0
    with db.aperta(ctx.settings.db_path) as conn:
        for r, v in zip(righe, vettori):
            conn.execute("UPDATE pezzi SET vettore = ?, modello = ? WHERE id = ?", (v.tobytes(), fornitore.modello, r["id"]))
    return len(righe)


# --------------------------------------------------------------------------- la ricerca

_VUOTE = {"della", "delle", "degli", "dello", "nella", "nelle", "negli", "nello", "dalla", "dalle", "dagli", "sulla",
          "sulle", "sugli", "alla", "alle", "agli", "come", "cosa", "quale", "quali", "questo", "questa", "questi",
          "queste", "quello", "quella", "sono", "erano", "aveva", "anche", "perché", "quando", "dove", "però", "molto"}
_PAROLA = re.compile(r"[a-zàèéìòù0-9]{3,}", re.IGNORECASE)


def _parole(testo: str) -> set[str]:
    return {p.lower() for p in _PAROLA.findall(testo or "") if p.lower() not in _VUOTE}


def _prodotto(a: array.array, b: array.array) -> float:
    return sum(x * y for x, y in zip(a, b))


def _in_scala(valori: dict[str, float]) -> dict[str, float]:
    if not valori:
        return {}
    basso, alto = min(valori.values()), max(valori.values())
    if alto - basso < 1e-9:
        return {k: 1.0 for k in valori}
    return {k: (v - basso) / (alto - basso) for k, v in valori.items()}


def punteggi(ctx, domanda: str, brani: list[dict], *, materia_id: int | None = None,
             lezione_id: int | None = None, prima=None) -> dict[str, float]:
    """Da 0 a 1 per ogni brano: significato e parole in comune, o solo le parole se i vettori non si possono avere."""
    parole = _parole(domanda)
    lessicale = _in_scala({b["id"]: len(parole & _parole(b["testo"])) / (len(parole) or 1) for b in brani})
    vicinanza: dict[str, float] = {}
    if getattr(ctx, "vettori", None) is not None:
        try:
            q = _chiedi_vettori(ctx, [domanda[:4000]], materia_id=materia_id, lezione_id=lezione_id,
                                cosa="Ricerca nei documenti", prima=prima)[0]
        except (TettoRaggiunto, ErroreFornitore) as e:
            log.warning("memoria: ricerca solo per parole (%s)", type(e).__name__)
            q = None
        if q is not None:
            with db.aperta(ctx.settings.db_path) as conn:
                segni = ",".join("?" * len(brani))
                trovati = {r["id"]: r["vettore"] for r in conn.execute(
                    f"SELECT id, vettore FROM pezzi WHERE modello = ? AND id IN ({segni})",
                    (ctx.vettori.modello, *[b["pezzo_id"] for b in brani]))} if brani else {}
            for b in brani:
                dati = trovati.get(b["pezzo_id"])
                if dati is not None:
                    v = array.array("f")
                    v.frombytes(dati)
                    vicinanza[b["id"]] = _prodotto(q, v)
    significato = _in_scala(vicinanza)
    return {b["id"]: (PESO_SIGNIFICATO * significato[b["id"]] + (1 - PESO_SIGNIFICATO) * lessicale[b["id"]])
            if b["id"] in significato else lessicale[b["id"]] for b in brani}


def scegli(ctx, domanda: str, brani: list[dict], massimo: int, gia: list[str] | None = None, *,
           materia_id: int | None = None, lezione_id: int | None = None, prima=None) -> list[dict]:
    """I pezzi per questa domanda, entro `massimo` caratteri: ogni fascia di priorità ha una sua
    quota e quella che non usa passa alla fascia dopo. Prima il libro, poi le verifiche e così via."""
    if not brani:
        return []
    per_id = {b["id"]: b for b in brani}
    punti = punteggi(ctx, domanda, brani, materia_id=materia_id, lezione_id=lezione_id, prima=prima)
    obbligati = [i for i in dict.fromkeys(gia or []) if i in per_id]
    buoni = sorted((i for i, p in punti.items() if p >= SOGLIA_RELATIVA and i not in obbligati),
                   key=lambda i: -punti[i])
    scelti: list[str] = []
    totale = 0
    for i in obbligati:
        if totale + len(per_id[i]["testo"]) <= massimo or not scelti:
            scelti.append(i)
            totale += len(per_id[i]["testo"])
    riporto = 0.0
    for rango in sorted(QUOTE):
        quota = massimo * QUOTE[rango] + riporto
        usati = sum(len(per_id[i]["testo"]) for i in scelti if per_id[i]["rango"] == rango)
        for i in buoni:
            b = per_id[i]
            if b["rango"] != rango or usati + len(b["testo"]) > quota or totale + len(b["testo"]) > massimo:
                continue
            scelti.append(i)
            usati += len(b["testo"])
            totale += len(b["testo"])
        riporto = max(0.0, quota - usati)
    for i in buoni:  # lo spazio rimasto va di nuovo in ordine di priorità
        b = per_id[i]
        if i not in scelti and totale + len(b["testo"]) <= massimo:
            scelti.append(i)
            totale += len(b["testo"])
    if not scelti:
        migliore = max(punti, key=punti.get)
        scelti = [migliore]
    ordine = {b["id"]: n for n, b in enumerate(brani)}
    return [per_id[i] for i in sorted(scelti, key=lambda x: ordine[x])]
