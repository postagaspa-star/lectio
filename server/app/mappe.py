"""Le mappe di ripasso (contratto 29): da uno schema pronto, la linea del tempo con le date oppure la mappa dei
concetti fondamentali. La fonte è solo lo schema, che è già stato controllato sui documenti.

Prima di salvarla:
1. le date si cercano nello schema con una regola fissa, senza AI: una data che lì non c'è si fa correggere una volta,
   poi si toglie; un secolo scritto in lettere ("XV secolo", "Quattrocento") si traduce in anni da qui;
2. ogni legame ("Peste nera provoca Calo della popolazione") e ogni spiegazione passa dal solito controllo dei fatti,
   con una riscrittura possibile; quello che resta fuori dallo schema si toglie.
Il disegno (albero, raggiera, linea del tempo) lo fanno le app: qui ci sono solo i pezzi e come si legano."""

import json
import re
import sqlite3

from . import ai, clock, controllo, db, prompt_studio, schema_ripasso, stile
from .lezione import Annullata, ErroreLezione

TIPI = ("date", "concetti")
NOME_TIPO = {"date": "Le date", "concetti": "I concetti"}
CARATTERI_SCHEMA = 90_000
CONCETTI_MINIMI = 4
CONCETTI_MASSIMI = 25
PAROLE_CONCETTO = 8  # il prompt ne chiede 5: oltre 8 è una frase, si fa correggere
PAROLE_FRASE = 32  # 1.4.1: la "frase" sotto il nome (il prompt ne chiede al massimo 20); oltre, si fa accorciare
CARATTERI_FRASE = 240
FORME = ("riquadri", "raggiera")  # 1.4.1 (richiesta di Andrea del 21/11): i riquadri prendono il posto dell'albero
EVENTI_MASSIMI = 40
DATE_MINIME = 3  # sotto, la linea del tempo non ha senso: si propone quella dei concetti
ANNO_MASSIMO_DC = 2100
ANNO_MASSIMO_AC = 10_000_000
FRASE_POCHE_DATE = "In questo schema ci sono troppo poche date per una linea del tempo: prova la mappa dei concetti."

MESI = ("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre",
        "dicembre")
_SECOLI_PAROLA = {"duecento": 13, "trecento": 14, "quattrocento": 15, "cinquecento": 16, "seicento": 17,
                  "settecento": 18, "ottocento": 19, "novecento": 20}
_ROMANI = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100}
_DATA_AC = re.compile(r"\b(\d{1,7})\s*(?:a\.?\s?c\.?|d\.?\s?c\.?|avanti\s+cristo|dopo\s+cristo)", re.I)
_ANNO = re.compile(r"(?<![\d.,])(1\d{3}|20\d{2})(?![\d]|[.,]\d)")
_SECOLO_ROMANO = re.compile(r"\b([IVXLC]{1,6})\s+(?:sec(?:olo)?\b\.?)|\bsec(?:olo)?\.?\s+([IVXLC]{1,6})\b")
_SECOLO_PAROLA = re.compile(r"\b(" + "|".join(_SECOLI_PAROLA) + r")\b", re.I)
_NUMERI = re.compile(r"\d+")
_AVANTI_CRISTO = re.compile(r"\ba\.?\s?c\.?(?![a-z])|\bavanti\s+cristo\b", re.I)


# --------------------------------------------------------------------------- lo schema come lo legge il modello

def testo_numerato(contenuto: dict) -> str:
    """Le sezioni dello schema con il loro numero ("[Sezione 3] Titolo…"): la mappa dice da dove viene ogni pezzo."""
    parti, totale = [], 0
    for s in contenuto.get("sezioni") or []:
        testo = schema_ripasso.testo_di([s])
        if not testo.strip():
            continue
        pezzo = f"[Sezione {s['n']}] {testo}"
        if parti and totale + len(pezzo) > CARATTERI_SCHEMA:
            break
        parti.append(pezzo[:CARATTERI_SCHEMA])
        totale += len(pezzo) + 2
    return "\n\n".join(parti)


def sezioni_di(contenuto: dict) -> set[int]:
    return {int(s["n"]) for s in contenuto.get("sezioni") or [] if isinstance(s.get("n"), int)}


def date_nel_testo(testo: str) -> int:
    """Quante date diverse ci sono, più o meno (anni, "a.C."/"d.C.", secoli). Serve solo a dire se la linea del tempo ha senso."""
    trovate: set[str] = set()
    for m in _DATA_AC.finditer(testo):
        trovate.add(m.group(1) + ("ac" if _AVANTI_CRISTO.search(m.group(0)) else ""))
    trovate.update(m.group(1) for m in _ANNO.finditer(testo))
    for m in _SECOLO_ROMANO.finditer(testo):
        trovate.add("s" + (m.group(1) or m.group(2)))
    trovate.update("s" + m.group(1).lower() for m in _SECOLO_PAROLA.finditer(testo))
    return len(trovate)


def date_possibili(contenuto: dict) -> bool:
    return date_nel_testo(testo_numerato(contenuto)) >= DATE_MINIME


# --------------------------------------------------------------------------- piccoli aiuti

def _chiave(v) -> str | None:
    if v is None or isinstance(v, bool) or isinstance(v, (dict, list)):
        return None
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    t = str(v).strip()
    return t or None


def _vuoto(v) -> bool:
    return v is None or (isinstance(v, str) and v.strip().lower() in ("", "null", "none", "nessuno"))


def _intero(v) -> int | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float) and v.is_integer():
        return int(v)
    if isinstance(v, str) and re.fullmatch(r"\s*-?\d{1,8}\s*", v):
        return int(v)
    return None


def _breve(v, massimo: int) -> str:
    """Un pezzo corto (concetto, verbo, data): niente punto in fondo, tranne quello delle sigle ("a.C.", "S.")."""
    t = stile.formule_in_testo(" ".join(str(v or "").replace("**", "").split())).strip(" ;,")
    if t.endswith(".") and not re.search(r"(?:^|[\s.])[A-Za-z]{1,2}\.$", t):
        t = t.rstrip(" .")
    return t if len(t) <= massimo else t[:massimo].rsplit(" ", 1)[0]


def _testo(v, massimo: int = 400) -> str:
    t = stile.ripulisci(stile.formule_in_testo(" ".join(str(v or "").replace("**", "").split())), parlato=False)
    return t if len(t) <= massimo else t[:massimo].rsplit(" ", 1)[0] + "…"


def titolo_pulito(v) -> str:
    return stile.ripulisci(_breve(v, 120))


def _sezione(v, valide: set[int]) -> int | None:
    n = _intero(v)
    return n if n in valide else None


def romano(t: str) -> int | None:
    if not t or any(c not in _ROMANI for c in t):
        return None
    totale, prima = 0, 0
    for c in reversed(t):
        v = _ROMANI[c]
        totale = totale - v if v < prima else totale + v
        prima = max(prima, v)
    return totale if 0 < totale <= 30 else None


def secolo_di(data: str) -> int | None:
    """Il secolo scritto in una data senza cifre: "XV secolo" → 15, "il Quattrocento" → 15."""
    m = _SECOLO_ROMANO.search(data)
    if m:
        return romano(m.group(1) or m.group(2))
    m = _SECOLO_PAROLA.search(data)
    return _SECOLI_PAROLA[m.group(1).lower()] if m else None


def astronomico(anno: int, ac: bool) -> int:
    """L'anno sulla linea: 1 a.C. = 0, 753 a.C. = -752 (non c'è un anno zero)."""
    return 1 - anno if ac else anno


# --------------------------------------------------------------------------- validazione (si fa correggere)

def _ha_giri(concetti: list[dict]) -> bool:
    genitore = {_chiave(c.get("id")): (None if _vuoto(c.get("genitore")) else _chiave(c.get("genitore"))) for c in concetti}
    for k in genitore:
        visti, x = set(), k
        while x is not None:
            if x in visti:
                return True
            visti.add(x)
            x = genitore.get(x)
    return False


def valida_concetti(o: dict) -> list[str]:
    concetti = o.get("concetti")
    if not isinstance(concetti, list) or not concetti or any(not isinstance(c, dict) for c in concetti):
        return ['servono i "concetti", una lista di oggetti']
    ids = [_chiave(c.get("id")) for c in concetti]
    if any(i is None for i in ids) or len(set(ids)) != len(ids):
        return ['ogni concetto ha un "id" diverso dagli altri']
    errori = []
    if len(concetti) < CONCETTI_MINIMI:
        errori.append(f"servono almeno {CONCETTI_MINIMI} concetti")
    if len(concetti) > CONCETTI_MASSIMI:
        errori.append(f"hai messo {len(concetti)} concetti: al massimo {CONCETTI_MASSIMI}, tieni solo i più importanti")
    radici = [c for c in concetti if _vuoto(c.get("genitore"))]
    if len(radici) != 1:
        errori.append(f'serve un solo concetto senza "genitore" (quello in cima), ne hai messi {len(radici)}')
    presenti = set(ids)
    senza_frase = []
    for c in concetti:
        if not _vuoto(c.get("genitore")) and _chiave(c.get("genitore")) not in presenti:
            errori.append(f'il concetto {c.get("id")} ha un "genitore" che non c\'è')
        testo = str(c.get("testo") or "").strip()
        if not testo:
            errori.append(f'il concetto {c.get("id")} non ha il "testo"')
        elif len(testo.split()) > PAROLE_CONCETTO:
            errori.append(f'"{testo[:60]}" è una frase: un concetto ha al massimo 5 parole (la frase va in "frase")')
        frase = str(c.get("frase") or "").strip()
        if not _vuoto(c.get("genitore")) and not frase:
            senza_frase.append(str(c.get("id")))
        elif len(frase.split()) > PAROLE_FRASE:
            errori.append(f'la "frase" del concetto {c.get("id")} è troppo lunga: al massimo 20 parole')
    # Qualche frase mancante si accetta (resta solo il nome); se mancano quasi tutte, si chiede di scriverle.
    if len(senza_frase) > max(2, len(concetti) // 4):
        errori.append('ai concetti ' + ", ".join(senza_frase[:8]) + ' manca la "frase" che li spiega')
    if not errori and _ha_giri(concetti):
        errori.append("i genitori formano un giro chiuso: ogni concetto deve scendere da quello in cima")
    return errori[:6]


def valida_date(o: dict) -> list[str]:
    eventi = o.get("eventi")
    if not isinstance(eventi, list) or any(not isinstance(e, dict) for e in eventi):
        return ['servono gli "eventi", una lista di oggetti']
    if len(eventi) > EVENTI_MASSIMI + 10:
        return [f"hai messo {len(eventi)} date: al massimo {EVENTI_MASSIMI}, tieni le più importanti"]
    errori = []
    for i, e in enumerate(eventi, start=1):
        if _intero(e.get("anno")) is None and secolo_di(str(e.get("data") or "")) is None:
            errori.append(f'la data {i} ("{str(e.get("data") or "")[:30]}") non ha l\'"anno" in cifre')
        elif not str(e.get("cosa") or "").strip():
            errori.append(f'la data {i} non dice "cosa" è successo')
    return errori[:6]


VALIDA = {"concetti": valida_concetti, "date": valida_date}


# --------------------------------------------------------------------------- nella forma che usano le app

def normalizza_concetti(o: dict, sezioni: set[int]) -> dict:
    """Un albero solo, a partire da quello in cima: ciò che non ci si attacca resta fuori. Gli id diventano 1, 2, 3…
    in ordine dall'alto (così un taglio a CONCETTI_MASSIMI toglie i più in basso)."""
    concetti = [c for c in o.get("concetti") or [] if isinstance(c, dict) and _chiave(c.get("id")) is not None]
    per_chiave: dict[str, dict] = {}
    for c in concetti:
        per_chiave.setdefault(_chiave(c["id"]), c)
    radice, figli = None, {}
    for k, c in per_chiave.items():
        if _vuoto(c.get("genitore")):
            radice = radice or k
        else:
            figli.setdefault(_chiave(c.get("genitore")), []).append(k)
    ordine, visti, coda = [], set(), [radice] if radice else []
    while coda:
        k = coda.pop(0)
        if k in visti or k not in per_chiave:
            continue
        visti.add(k)
        ordine.append(k)
        coda.extend(figli.get(k, []))
    ordine = ordine[:CONCETTI_MASSIMI]
    nuovi = {k: i for i, k in enumerate(ordine, start=1)}
    tutti = []
    for k in ordine:
        c = per_chiave[k]
        g = None if k == radice else nuovi.get(_chiave(c.get("genitore")))
        testo = _breve(c.get("testo"), 80) or ("Argomento" if g is None else "")
        tutti.append({"id": nuovi[k], "testo": testo, "genitore": g, "verbo": (_breve(c.get("verbo"), 40) or None) if g else None,
                      "frase": _testo(c.get("frase"), CARATTERI_FRASE) or None,
                      "spiegazione": _testo(c.get("spiegazione")), "sezione": _sezione(c.get("sezione"), sezioni)})
    # Un concetto rimasto senza testo si salta: i suoi figli salgono al concetto sopra, senza verbo.
    per_id = {c["id"]: c for c in tutti}
    saltati = {c["id"] for c in tutti if not c["testo"]}
    for c in tutti:
        if c["genitore"] in saltati:
            g = c["genitore"]
            while g in saltati:
                g = per_id[g]["genitore"]
            c["genitore"], c["verbo"] = g, None
    uscita = [c for c in tutti if c["id"] not in saltati]
    # 1.4.1 (Andrea, 21/11): non ci sono più frecce tra rami diversi ("è chiaro che sono collegate, ma quelle frecce non
    # servono a nulla") e il verbo c'è solo tra due concetti: mai dall'argomento o da una parte (sono titoli di gruppo,
    # e la prova vera ha dato frasi come "Bachofen e Frazer ordina il pensiero in Frazer").
    togli_verbi_dei_gruppi(uscita)
    return {"domanda": _breve(o.get("domanda"), 160) or None, "forma": o.get("forma") if o.get("forma") in FORME else "riquadri",
            "concetti": uscita, "collegamenti": []}


def senza_verbo(concetti: list[dict], c: dict) -> bool:
    """Il concetto sta sotto l'argomento o sotto una parte: lì il verbo non c'è (lo dice il riquadro)."""
    per_id = {x.get("id"): x for x in concetti}
    g = per_id.get(c.get("genitore"))
    return g is None or g.get("genitore") is None or (per_id.get(g.get("genitore")) or {}).get("genitore", 0) is None


def togli_verbi_dei_gruppi(concetti: list[dict]) -> None:
    for c in [x for x in concetti if senza_verbo(concetti, x)]:
        c["verbo"] = None


def _anni_evento(e: dict) -> tuple[int, bool, int | None, bool] | None:
    """(anno, ac, fine, fine_ac) controllati. Un secolo scritto in lettere vale più dei numeri del modello."""
    data = str(e.get("data") or "")
    ac = bool(e.get("ac")) or bool(_AVANTI_CRISTO.search(data))
    secolo = secolo_di(data) if not _NUMERI.search(data) else None
    if secolo:
        inizio, fine = (secolo - 1) * 100 + 1, secolo * 100
        return (fine, True, inizio, True) if ac else (inizio, False, fine, False)
    anno = _intero(e.get("anno"))
    if anno is None:
        return None
    if anno < 0:
        anno, ac = -anno, True
    anno = max(1, anno)
    if anno > (ANNO_MASSIMO_AC if ac else ANNO_MASSIMO_DC):
        return None
    fine = _intero(e.get("fine"))
    fine_ac = bool(e.get("fine_ac")) if e.get("fine_ac") is not None else ac
    if fine is not None and fine < 0:
        fine, fine_ac = -fine, True
    if fine is not None and (fine < 1 or fine > (ANNO_MASSIMO_AC if fine_ac else ANNO_MASSIMO_DC)):
        fine = None
    if fine is not None and astronomico(fine, fine_ac) <= astronomico(anno, ac):
        fine = None
    return anno, ac, fine, fine_ac


def normalizza_date(o: dict, sezioni: set[int]) -> dict:
    eventi, visti = [], set()
    for e in o.get("eventi") or []:
        if not isinstance(e, dict):
            continue
        anni = _anni_evento(e)
        cosa = _breve(e.get("cosa"), 120)
        if anni is None or not cosa:
            continue
        anno, ac, fine, fine_ac = anni
        mese = _intero(e.get("mese"))
        mese = mese if mese and 1 <= mese <= 12 and not ac else None
        x = astronomico(anno, ac) + ((mese - 1) / 12 if mese else 0)
        data = _breve(e.get("data"), 40) or (f"{anno} a.C." if ac else str(anno))
        chiave = (data.lower(), cosa.lower())
        if chiave in visti:
            continue
        visti.add(chiave)
        eventi.append({"data": data, "anno": anno, "ac": ac, "fine": fine, "fine_ac": fine_ac if fine else None,
                       "x": x, "x_fine": astronomico(fine, fine_ac) if fine else None, "circa": bool(e.get("circa")) or "circa" in data.lower()
                       or bool(re.match(r"^c\.\s", data)), "mese": mese, "cosa": cosa, "spiegazione": _testo(e.get("spiegazione")),
                       "sezione": _sezione(e.get("sezione"), sezioni)})
    eventi.sort(key=lambda e: (e["x"], e["x_fine"] if e["x_fine"] is not None else e["x"]))
    for i, e in enumerate(eventi[:EVENTI_MASSIMI], start=1):
        e["id"] = i
    return {"eventi": eventi[:EVENTI_MASSIMI]}


def normalizza(tipo: str, o: dict, sezioni: set[int]) -> dict:
    return normalizza_concetti(o, sezioni) if tipo == "concetti" else normalizza_date(o, sezioni)


def elementi(tipo: str, contenuto: dict) -> int:
    return len(contenuto.get("concetti" if tipo == "concetti" else "eventi") or [])


def per_modello(tipo: str, titolo: str, contenuto: dict) -> dict:
    """La mappa salvata, nella forma che il modello conosce (per Modifica)."""
    if tipo == "concetti":
        forma = contenuto.get("forma") if contenuto.get("forma") in FORME else "riquadri"
        tenuti = ("id", "testo", "genitore", "verbo", "frase", "spiegazione", "sezione")
        concetti = [{k: c.get(k) for k in tenuti} for c in contenuto.get("concetti") or []]
        togli_verbi_dei_gruppi(concetti)  # le mappe di prima: il modello non deve ricopiare i verbi dei gruppi
        return {"titolo": titolo, "domanda": contenuto.get("domanda"), "forma": forma, "concetti": concetti}
    tenute = ("data", "anno", "ac", "fine", "fine_ac", "circa", "mese", "cosa", "spiegazione", "sezione")
    return {"titolo": titolo, "eventi": [{k: e.get(k) for k in tenute} for e in contenuto.get("eventi") or []]}


# --------------------------------------------------------------------------- le date si cercano nello schema

def _parole(testo: str) -> set[str]:
    return set(re.findall(r"[A-Za-zÀ-ÿ]+", testo))


def data_nello_schema(e: dict, numeri_testo: set[str], parole_testo: set[str], minuscolo: str) -> bool:
    """La data scritta sta nello schema: ogni numero c'è, e l'anno è uno di quei numeri. Senza cifre: il secolo (in
    romani o in lettere) o la scritta intera ("età del bronzo") devono esserci."""
    data = str(e.get("data") or "").strip()
    if not data:
        return False
    numeri = [n.lstrip("0") or "0" for n in _NUMERI.findall(data)]
    if numeri:
        if any(n not in numeri_testo for n in numeri):
            return False
        if re.search(r"milion|mila\b|miliard", data, re.I):
            return True
        anno = _intero(e.get("anno"))
        return anno is not None and str(abs(anno)) in numeri
    m = _SECOLO_ROMANO.search(data)
    if m:
        return (m.group(1) or m.group(2)) in parole_testo
    m = _SECOLO_PAROLA.search(data)
    if m:
        return m.group(1).lower() in minuscolo
    return " ".join(data.lower().split()) in minuscolo


def date_fuori(o: dict, testo: str) -> list[str]:
    numeri = {n.lstrip("0") or "0" for n in _NUMERI.findall(testo)}
    parole, minuscolo = _parole(testo), " ".join(testo.lower().split())
    return [f'{str(e.get("data") or "")[:40]}: {str(e.get("cosa") or "")[:80]}' for e in o.get("eventi") or []
            if isinstance(e, dict) and not data_nello_schema(e, numeri, parole, minuscolo)]


def togli_date_fuori(o: dict, testo: str) -> dict:
    numeri = {n.lstrip("0") or "0" for n in _NUMERI.findall(testo)}
    parole, minuscolo = _parole(testo), " ".join(testo.lower().split())
    return {**o, "eventi": [e for e in o.get("eventi") or [] if isinstance(e, dict) and data_nello_schema(e, numeri, parole, minuscolo)]}


# --------------------------------------------------------------------------- il controllo dei fatti

def frasi_da_controllare(tipo: str, contenuto: dict) -> tuple[dict, list[tuple]]:
    """Le frasi della mappa come le legge chi controlla: ogni legame diventa una frase ("Peste nera provoca Calo della
    popolazione."). Insieme, da dove viene ognuna."""
    voci, da_dove = [], []
    if tipo == "concetti":
        per_id = {c["id"]: c for c in contenuto["concetti"]}
        for c in contenuto["concetti"]:
            if c["genitore"] in per_id:
                # Il concetto dentro il suo genitore; il verbo a parte, così un verbo sbagliato toglie solo la freccia.
                voci.append(f"{per_id[c['genitore']]['testo']}: {c['testo']}.")
                da_dove.append(("concetto", c["id"]))
                if c.get("verbo"):
                    voci.append(f"{per_id[c['genitore']]['testo']} {c['verbo']} {c['testo']}.")
                    da_dove.append(("verbo", c["id"]))
            if c.get("frase"):
                voci.append(f"{c['testo']}: {c['frase']}")
                da_dove.append(("frase", c["id"]))
            if c["spiegazione"]:
                voci.append(c["spiegazione"])
                da_dove.append(("spiegazione", c["id"]))
        for k, leg in enumerate(contenuto["collegamenti"]):
            if leg["da"] in per_id and leg["a"] in per_id:
                legame = f" {leg['verbo']} " if leg["verbo"] else ": "
                voci.append(f"{per_id[leg['da']]['testo']}{legame}{per_id[leg['a']]['testo']}.")
                da_dove.append(("collegamento", k))
            if leg["spiegazione"]:
                voci.append(leg["spiegazione"])
                da_dove.append(("spiegazione_collegamento", k))
    else:
        for e in contenuto["eventi"]:
            voci.append(f"{e['data']}: {e['cosa']}.")
            da_dove.append(("evento", e["id"]))
            if e["spiegazione"]:
                voci.append(e["spiegazione"])
                da_dove.append(("spiegazione", e["id"]))
    return {"voci": voci}, da_dove


def _senza_frasi(testo: str, indici: set[int]) -> str:
    return " ".join(f for i, f in enumerate(stile.dividi_frasi(testo)) if i not in indici)


def togli_fuori(tipo: str, contenuto: dict, fuori: list[tuple], da_dove: list[tuple]) -> dict:
    """Toglie quello che il controllo ha trovato fuori dallo schema. Un concetto tolto lascia i suoi figli al concetto
    sopra, senza verbo (il verbo era per quello tolto)."""
    c = json.loads(json.dumps(contenuto))
    via: set[tuple] = set()
    frasi: dict[tuple, set[int]] = {}
    for _, indice, i, _ in fuori:
        if not isinstance(indice, int) or not 0 <= indice < len(da_dove):
            continue
        cosa = da_dove[indice]
        if cosa[0] in ("concetto", "collegamento", "evento"):
            via.add(cosa)
        else:
            frasi.setdefault(cosa, set()).add(i)
    if tipo == "concetti":
        per_id = {x["id"]: x for x in c["concetti"]}
        for x in c["concetti"]:
            if ("spiegazione", x["id"]) in frasi:
                x["spiegazione"] = _senza_frasi(x["spiegazione"], frasi[("spiegazione", x["id"])])
            if ("frase", x["id"]) in frasi:
                x["frase"] = _senza_frasi(x.get("frase") or "", frasi[("frase", x["id"])]) or None
            if ("verbo", x["id"]) in frasi:
                x["verbo"] = None
        tolti = {k for (t, k) in via if t == "concetto"}
        for x in c["concetti"]:
            g = x["genitore"]
            if g in tolti:
                while g in tolti:
                    g = per_id[g]["genitore"]
                x["genitore"], x["verbo"] = g, None
        c["concetti"] = [x for x in c["concetti"] if x["id"] not in tolti]
        rimasti = {x["id"] for x in c["concetti"]}
        collegamenti = []
        for k, leg in enumerate(c["collegamenti"]):
            if ("collegamento", k) in via or leg["da"] not in rimasti or leg["a"] not in rimasti:
                continue
            if ("spiegazione_collegamento", k) in frasi:
                leg["spiegazione"] = _senza_frasi(leg["spiegazione"], frasi[("spiegazione_collegamento", k)])
            collegamenti.append(leg)
        c["collegamenti"] = collegamenti
    else:
        eventi = []
        for e in c["eventi"]:
            if ("evento", e["id"]) in via:
                continue
            if ("spiegazione", e["id"]) in frasi:
                e["spiegazione"] = _senza_frasi(e["spiegazione"], frasi[("spiegazione", e["id"])])
            eventi.append(e)
        c["eventi"] = eventi
    return c


def abbastanza(tipo: str, contenuto: dict) -> bool:
    return elementi(tipo, contenuto) >= (3 if tipo == "concetti" else 2)


def scrivi(ctx, *, tipo: str, messaggi: list[dict], testo: str, sezioni: set[int], materia_id, prima, riferimento: str,
           cosa: str, compito: str, dati: dict) -> tuple[dict, dict]:
    """La mappa chiesta, corretta e controllata: (risposta del modello, contenuto per le app)."""
    valida = VALIDA[tipo]
    comuni = {"cassa": ctx.cassa, "fornitore": ctx.testo, "compito": compito, "modello": ctx.settings.modello_scrittura,
              "valida": valida, "max_token": 6000 if tipo == "concetti" else 7000, "dati": dati, "materia_id": materia_id,
              "prima_di_ogni_chiamata": prima, "riferimento": riferimento}
    grezzo = ai.chiedi_json(messaggi=messaggi, cosa=cosa, **comuni)
    if tipo == "date":
        sbagliate = date_fuori(grezzo, testo)
        if sbagliate:
            try:
                grezzo = ai.chiedi_json(messaggi=messaggi + [{"role": "assistant", "content": json.dumps(grezzo, ensure_ascii=False)},
                                                             {"role": "user", "content": prompt_studio.correzione_date(sbagliate)}],
                                        cosa=f"{cosa} (date da sistemare)", **comuni)
            except ai.RispostaNonUsabile:
                pass  # restano le date di prima: quelle che non sono nello schema si tolgono qui sotto
        grezzo = togli_date_fuori(grezzo, testo)
    contenuto = normalizza(tipo, grezzo, sezioni)
    if not ctx.testo.a_pagamento:
        return grezzo, contenuto
    controlla = {"cosa": cosa, "materia_id": materia_id, "lezione_id": None, "prima": prima, "riferimento": riferimento}
    oggetto, da_dove = frasi_da_controllare(tipo, contenuto)
    fuori = controllo.frasi_fuori(ctx, oggetto, testo, **controlla)
    if fuori:
        try:
            nuovo = ai.chiedi_json(messaggi=messaggi + [{"role": "assistant", "content": json.dumps(grezzo, ensure_ascii=False)},
                                                        {"role": "user", "content": prompt_studio.richiesta_riscrittura([f[3] for f in fuori], [])}],
                                   cosa=f"{cosa} (riscrittura)", **comuni)
        except ai.RispostaNonUsabile:
            nuovo = None
        if nuovo is not None:
            if tipo == "date":
                nuovo = togli_date_fuori(nuovo, testo)
            rifatto = normalizza(tipo, nuovo, sezioni)
            if abbastanza(tipo, rifatto):
                grezzo, contenuto = nuovo, rifatto
                oggetto, da_dove = frasi_da_controllare(tipo, contenuto)
                fuori = controllo.frasi_fuori(ctx, oggetto, testo, **controlla)
        if fuori:
            contenuto = togli_fuori(tipo, contenuto, fuori, da_dove)
    return grezzo, contenuto


# --------------------------------------------------------------------------- la preparazione

class PreparazioneMappa:
    def __init__(self, ctx, mappa_id: int):
        self.ctx = ctx
        self.id = mappa_id
        with db.aperta(ctx.settings.db_path) as conn:
            r = conn.execute(
                "SELECT p.*, s.titolo AS schema_titolo, s.argomento, s.contenuto AS schema_contenuto, s.cambiato_il AS schema_cambiato_il,"
                " m.nome AS materia_nome, m.prof AS materia_prof"
                " FROM mappe p JOIN schemi s ON s.id = p.schema_id JOIN materie m ON m.id = p.materia_id WHERE p.id = ?",
                (mappa_id,)).fetchone()
        if r is None:
            raise Annullata()
        self.riga = dict(r)

    def controlla(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM mappe WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def esegui(self) -> None:
        self.controlla()
        contenuto_schema = json.loads(self.riga["schema_contenuto"] or "{}")
        testo = testo_numerato(contenuto_schema)
        if not testo.strip():
            raise ErroreLezione("Questo schema è vuoto: non c'è niente da mettere nella mappa.")
        tipo = self.riga["tipo"]
        if tipo == "date" and date_nel_testo(testo) < DATE_MINIME:
            raise ErroreLezione(FRASE_POCHE_DATE)
        materia = {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]}
        titolo_schema = self.riga["schema_titolo"] or self.riga["argomento"]
        sezioni = sezioni_di(contenuto_schema)
        dati = {"tipo": tipo, "titolo": titolo_schema,
                "sezioni": [{"n": s["n"], "titolo": s["titolo"], "testo": schema_ripasso.testo_di([s])} for s in contenuto_schema.get("sezioni") or []]}
        grezzo, contenuto = scrivi(self.ctx, tipo=tipo, messaggi=prompt_studio.messaggi_mappa(materia, titolo_schema, testo, tipo), testo=testo,
                                   sezioni=sezioni, materia_id=self.riga["materia_id"], prima=self.controlla, riferimento=f"mappa:{self.id}",
                                   cosa=f"Mappa · {NOME_TIPO[tipo].lower()}", compito="mappa", dati=dati)
        if not abbastanza(tipo, contenuto):
            raise ErroreLezione(FRASE_POCHE_DATE if tipo == "date" else "La mappa è venuta troppo piccola: tocca Riprova.")
        nota = "Mappa di prova: pezzi presi pari pari dallo schema. Serve la chiave per quella vera." if not self.ctx.testo.a_pagamento else None
        base = ("Le date: " if tipo == "date" else "Mappa: ") + titolo_schema
        titolo = titolo_pulito(grezzo.get("titolo")) or base
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            cur = conn.execute("UPDATE mappe SET stato = 'pronta', frase = NULL, errore = NULL, nota = ?, titolo = ?, elementi = ?, contenuto = ?,"
                               " pronto_il = ?, schema_del = ? WHERE id = ? AND stato = 'in_preparazione'",
                               (nota, titolo, elementi(tipo, contenuto), json.dumps(contenuto, ensure_ascii=False), clock.adesso(),
                                self.riga["schema_cambiato_il"], self.id))
            if cur.rowcount == 0:
                raise Annullata()
            quale = "La linea del tempo" if tipo == "date" else "La mappa dei concetti"
            db.aggiungi_novita(conn, "mappa_pronta", f"{quale} di {self.riga['materia_nome']} è pronta", materia_id=self.riga["materia_id"])


def in_errore(db_path: str, mappa_id: int, frase: str) -> None:
    with db.aperta(db_path) as conn, db.transazione(conn):
        r = conn.execute("SELECT p.materia_id, m.nome FROM mappe p JOIN materie m ON m.id = p.materia_id WHERE p.id = ? AND p.stato = 'in_preparazione'",
                         (mappa_id,)).fetchone()
        if r is None:
            return
        conn.execute("UPDATE mappe SET stato = 'errore', errore = ?, frase = NULL WHERE id = ?", (frase, mappa_id))
        db.aggiungi_novita(conn, "mappa_errore", f"La mappa di {r['nome']} si è fermata: tocca Riprova", materia_id=r["materia_id"])


def per_api(r: sqlite3.Row) -> dict:
    lavora = r["stato"] in ("in_coda", "in_preparazione")
    return {
        "id": r["id"], "schema_id": r["schema_id"], "materia_id": r["materia_id"], "tipo": r["tipo"], "titolo": r["titolo"], "stato": r["stato"],
        "frase": r["frase"] or (("Cerco le date" if r["tipo"] == "date" else "Disegno la mappa") if lavora else None),
        "errore": r["errore"], "nota": r["nota"], "stima_eur": r["stima_eur"], "creato_il": clock.per_utente(r["creato_il"]),
        "pronto_il": clock.per_utente(r["pronto_il"]) if r["pronto_il"] else None, "elementi": r["elementi"],
        "contenuto": json.loads(r["contenuto"]) if r["contenuto"] else None,
    }


# --------------------------------------------------------------------------- correzione a mano (contratto 29.5)

class FormaSbagliata(ValueError):
    pass


def _campo(v, massimo: int) -> str:
    if v is None:
        return ""
    if not isinstance(v, str):
        raise FormaSbagliata("un testo non è un testo")
    t = " ".join(v.split())
    if len(t) > massimo:
        raise FormaSbagliata("un testo è troppo lungo")
    return t


def pulisci(tipo: str, attuale: dict, nuovo: dict) -> dict:
    """Il contenuto corretto a mano: dal nuovo si prendono solo i testi, la forma (chi sta sotto a chi, l'ordine) resta
    quella di adesso. Un concetto lasciato vuoto tiene il testo di prima; una data con "cosa" vuoto si toglie. Una data
    riscritta con un anno diverso si sposta sulla linea."""
    if not isinstance(nuovo, dict):
        raise FormaSbagliata("il contenuto non va bene")
    uscita = json.loads(json.dumps(attuale))
    if tipo == "concetti":
        concetti, nuovi = uscita.get("concetti") or [], nuovo.get("concetti")
        if not isinstance(nuovi, list) or len(nuovi) != len(concetti):
            raise FormaSbagliata("i concetti non sono gli stessi")
        for c, n in zip(concetti, nuovi):
            if not isinstance(n, dict):
                raise FormaSbagliata("un concetto non va bene")
            c["testo"] = _campo(n.get("testo"), 80) or c["testo"]
            if c.get("genitore") is not None and "verbo" in n:
                # Sotto l'argomento o sotto una parte la freccia non c'è: stanno dentro il riquadro.
                c["verbo"] = None if senza_verbo(concetti, c) else (_campo(n.get("verbo"), 40) or None)
            if "frase" in n:
                c["frase"] = _campo(n.get("frase"), 300) or None
            c["spiegazione"] = _campo(n.get("spiegazione"), 600)
        # Le mappe fatte prima della 1.4.1 possono avere frecce tra rami: se il PC non le manda, restano come sono.
        collegamenti = uscita.get("collegamenti") or []
        nuovi = nuovo.get("collegamenti") if "collegamenti" in nuovo else [dict(x) for x in collegamenti]
        if not isinstance(nuovi, list) or len(nuovi) != len(collegamenti):
            raise FormaSbagliata("i collegamenti non sono gli stessi")
        for leg, n in zip(collegamenti, nuovi):
            if not isinstance(n, dict):
                raise FormaSbagliata("un collegamento non va bene")
            leg["verbo"] = _campo(n.get("verbo"), 40) or None
            leg["spiegazione"] = _campo(n.get("spiegazione"), 600)
        if "domanda" in nuovo:
            uscita["domanda"] = _campo(nuovo.get("domanda"), 160) or None
        return uscita
    eventi, nuovi = uscita.get("eventi") or [], nuovo.get("eventi")
    if not isinstance(nuovi, list) or len(nuovi) != len(eventi):
        raise FormaSbagliata("le date non sono le stesse")
    tenuti = []
    for e, n in zip(eventi, nuovi):
        if not isinstance(n, dict):
            raise FormaSbagliata("una data non va bene")
        e["cosa"] = _campo(n.get("cosa"), 120)
        e["spiegazione"] = _campo(n.get("spiegazione"), 600)
        data = _campo(n.get("data"), 40)
        if data and data != e["data"]:
            e["data"] = data
            numeri = _NUMERI.findall(data)
            ac = bool(_AVANTI_CRISTO.search(data))
            secolo = secolo_di(data) if not numeri else None
            mese = next((i for i, nome in enumerate(MESI, start=1) if re.search(rf"\b{nome}\b", data, re.I)), None)
            if secolo:
                e["anno"], e["ac"], e["fine"], e["fine_ac"] = ((secolo * 100, True, (secolo - 1) * 100 + 1, True) if ac
                                                              else ((secolo - 1) * 100 + 1, False, secolo * 100, False))
                e["mese"] = None
            elif numeri:
                anni = [int(x) for x in numeri if 0 < int(x) <= (ANNO_MASSIMO_AC if ac else ANNO_MASSIMO_DC)]
                # "12 ottobre 1492": il giorno non è un anno; "1914-1918": il primo e l'ultimo.
                grandi = [a for a in anni if a > 31 or mese is None] if anni else []
                if grandi:
                    e["anno"], e["ac"] = grandi[0], ac
                    lunghi = len(grandi) > 1 and astronomico(grandi[-1], ac) > astronomico(grandi[0], ac)
                    e["fine"], e["fine_ac"] = (grandi[-1], ac) if lunghi else (None, None)
                    e["mese"] = mese if not ac else None
            e["x"] = astronomico(e["anno"], e["ac"]) + ((e["mese"] - 1) / 12 if e.get("mese") else 0)
            e["x_fine"] = astronomico(e["fine"], e["fine_ac"]) if e.get("fine") else None
        if e["cosa"]:
            tenuti.append(e)
    tenuti.sort(key=lambda e: (e["x"], e["x_fine"] if e["x_fine"] is not None else e["x"]))
    uscita["eventi"] = tenuti
    return uscita
