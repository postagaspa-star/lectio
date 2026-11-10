"""Titoli del materiale e materiale suggerito per un argomento (contratto 16.2 e 16.3).

- Un titolo è "automatico" quando viene dal nome del file o è quello predefinito ("Foto 4 novembre"):
  Lectio lo riscrive leggendo il materiale, con il modello piccolo. Un titolo scelto da Andrea non si
  tocca più.
- I suggeriti: il materiale che parla dell'argomento, confrontando le parole per radice (senza
  accenti), con il titolo che conta il doppio del testo. Niente AI: è gratis e immediato."""

import re
import unicodedata
from collections import Counter

from . import clock

PAROLE_VUOTE = {
    "della", "delle", "dello", "degli", "dalla", "dalle", "dallo", "dagli", "nella", "nelle", "nello", "negli",
    "sulla", "sulle", "sullo", "sugli", "alla", "alle", "allo", "agli", "come", "cosa", "sono", "anche", "quando",
    "dove", "perche", "questo", "questa", "questi", "queste", "quello", "quella", "quelli", "quelle", "tutto",
    "tutta", "tutti", "tutte", "molto", "prima", "dopo", "fino", "verso", "contro", "senza", "sopra", "sotto",
    "oppure", "ovvero", "cioe", "loro", "essere", "avere", "lezione", "lezioni", "verifica", "verifiche",
    "interrogazione", "capitolo", "pagina", "pagine", "parte", "parti", "argomento", "ripasso", "domande",
    "domanda", "spiegazione", "appunti", "libro", "tema", "temi",
    # le preposizioni con l'apostrofo ("dell'opera" → "dell")
    "dell", "nell", "sull", "dall", "quell", "quest", "tutt",
}
_PAROLA = re.compile(r"[a-z]{4,}")
_FOTOCAMERE = re.compile(
    r"^(whatsapp\s+(image|immagine|video)|img|pxl|dsc|dcim|scan|scansione|screenshot|image|immagine|photo|foto|"
    r"cam|camera|documento|doc|file|pdf|mms)(?![a-z])", re.IGNORECASE)
_PREDEFINITO = re.compile(
    r"^(foto|appunti|libro|materiale|verifica|interrogazione)\s+\d{1,2}\s+(" + "|".join(clock.MESI) + r")$",
    re.IGNORECASE)
TITOLO_MINIMO_CARATTERI = 200  # sotto questo testo non c'è abbastanza per un titolo sensato
SOGLIA_COMUNE = 0.6


def senza_accenti(testo: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", str(testo or "").lower())
                   if unicodedata.category(c) != "Mn")


def radice(parola: str) -> str:
    """scultura/sculture → scultu, metope/metopa → meto, tempio/templi → temp."""
    return parola if len(parola) <= 4 else parola[: max(4, len(parola) - 2)]


def parole(testo: str) -> list[str]:
    return [p for p in _PAROLA.findall(senza_accenti(testo)) if p not in PAROLE_VUOTE]


def _solo_lettere(testo: str) -> str:
    return re.sub(r"[\W_]+", "", senza_accenti(testo))


def titolo_automatico(titolo: str | None, nome_file: str | None) -> bool:
    """Il titolo viene dal nome del file, da una fotocamera o è quello predefinito?"""
    titolo = " ".join((titolo or "").split())
    if not titolo:
        return True
    if nome_file:
        radice_file = nome_file.rsplit(".", 1)[0] if "." in nome_file else nome_file
        if _solo_lettere(titolo) == _solo_lettere(radice_file):
            return True
    if _PREDEFINITO.match(titolo):
        return True
    return bool(_FOTOCAMERE.match(titolo)) and sum(c.isdigit() for c in titolo) >= 4


# --------------------------------------------------------------------------- suggeriti


def suggeriti(argomento: str, materiali: list[dict]) -> dict:
    """`materiali`: [{"id", "titolo", "testo", "cartella"}] (solo quelli pronti; "cartella" = nome della
    cartella o None, conta come il titolo). Restituisce le parole usate, gli id suggeriti (nell'ordine dei
    materiali) e i punti di ognuno (solo quelli sopra zero)."""
    voci = list(dict.fromkeys(parole(argomento)))
    radici = list(dict.fromkeys(radice(p) for p in voci))
    if not radici or not materiali:
        return {"argomento": argomento, "parole": voci, "suggeriti": [], "punti": {}}
    analisi = []
    for m in materiali:
        nel_titolo = {radice(p) for p in parole((m.get("titolo") or "") + " " + (m.get("cartella") or ""))}
        nel_testo = Counter(radice(p) for p in parole(m.get("testo") or ""))
        analisi.append((m["id"], nel_titolo, nel_testo))
    # Le parole che stanno quasi dappertutto ("scultura" in Storia dell'Arte) non distinguono niente.
    utili = radici
    if len(materiali) >= 4:
        rare = [r for r in radici
                if sum(1 for _, t, x in analisi if r in t or x[r] > 0) / len(materiali) <= SOGLIA_COMUNE]
        if rare:
            utili = rare
    soglia = 2 if len(utili) >= 2 else 1
    punti, scelti = {}, []
    for ident, nel_titolo, nel_testo in analisi:
        # Nel titolo o tante volte nel testo: ne parla davvero. Due volte: ne parla un po'.
        p = sum(2 if r in nel_titolo or nel_testo[r] >= 3 else 1 if nel_testo[r] == 2 else 0 for r in utili)
        if p > 0:
            punti[str(ident)] = p
        if p >= soglia:
            scelti.append(ident)
    return {"argomento": argomento, "parole": voci, "suggeriti": scelti, "punti": punti}


# --------------------------------------------------------------------------- titolo scritto da Lectio


def valida_titolo(o: dict) -> list[str]:
    t = o.get("titolo")
    if not isinstance(t, str) or not t.strip():
        return ['manca "titolo"']
    t = pulisci_titolo(t)
    if len(t) < 3 or len(t) > 80 or len(t.split()) > 10:
        return ['il "titolo" deve avere da 2 a 7 parole']
    return []


def pulisci_titolo(t: str) -> str:
    t = " ".join(str(t).split()).strip(" \"'«»“”.:;-")
    return t[:1].upper() + t[1:] if t else t
