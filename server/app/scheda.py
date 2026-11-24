"""La scheda del prof (contratto, sezione 5).

- Si aggiorna da sola in sottofondo quando arriva una verifica o un'interrogazione letta,
  o un esito; a mano con POST …/scheda/aggiorna.
- Le modifiche di Andrea (PUT) si tengono: si ricorda cosa ha aggiunto, tolto o cambiato
  (`di_andrea`) e ogni aggiornamento dell'AI lo rimette a posto sopra la risposta nuova.
- `note_mie` non entra mai nell'uscita dell'AI: si rimette sempre uguale."""

import json
import logging
import re
import sqlite3

from . import ai, clock, db, prompt, stile
from .rete import ErroreFornitore
from .spesa import TOKEN_SCHEDA_USCITA, TettoRaggiunto

log = logging.getLogger("uvicorn.error")

CAMPI_LISTA = ("cosa_corregge", "cosa_premia", "consigli")
CAMPI = ("argomenti", "tipi_domanda", "cosa_corregge", "cosa_premia", "lunghezza_risposte", "consigli")
CARATTERI_DOCUMENTI_MASSIMI = 60_000

# Il modulo delle verifiche (contratto 5.1, richiesta di Andrea del 24/11/2026): serve solo a fare verifiche di prova
# e interrogazioni uguali a quelle del prof. Nome del campo → "testo" (una frase), "lista" (voci brevi), "numero".
MODULO = {
    "verifiche": {"struttura": "testo", "consegne": "lista", "punti": "testo", "punti_totali": "numero",
                  "sufficienza": "numero", "regole_punti": "lista", "difficolta": "testo", "approfondimento": "testo",
                  "cosa_da": "testo", "richieste": "lista"},
    "interrogazioni": {"inizio": "testo", "andamento": "testo", "durata": "testo", "profondita": "testo",
                       "cosa_vuole": "lista", "esempi": "lista"},
}
LUNGHEZZA_VOCI = {"consegne": 300}  # le altre liste: 200 caratteri a voce
COPIATE = ("consegne", "esempi")  # voci copiate parola per parola dai documenti (le altre hanno la loro prova)


def modulo_vuoto(parte: str) -> dict:
    return {k: "" if t == "testo" else [] if t == "lista" else None for k, t in MODULO[parte].items()}


def modulo_pieno(m: dict | None) -> bool:
    return any(v not in ("", None, []) for v in (m or {}).values())


def vuota() -> dict:
    return {"argomenti": [], "tipi_domanda": [], "cosa_corregge": [], "cosa_premia": [], "lunghezza_risposte": "",
            "consigli": [], "verifiche": modulo_vuoto("verifiche"), "interrogazioni": modulo_vuoto("interrogazioni")}


def ha_contenuto(dati: dict) -> bool:
    return any(dati.get(k) for k in CAMPI) or any(modulo_pieno(dati.get(p)) for p in MODULO)


def crea(conn: sqlite3.Connection, materia_id: int) -> None:
    conn.execute("INSERT INTO schede (materia_id, dati) VALUES (?, ?)", (materia_id, json.dumps(vuota())))


def _riga(conn: sqlite3.Connection, materia_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM schede WHERE materia_id = ?", (materia_id,)).fetchone()


def dati_di(riga: sqlite3.Row) -> dict:
    d = vuota()
    d.update(json.loads(riga["dati"] or "{}"))
    for parte in MODULO:  # le schede scritte prima della 1.6.0 non hanno il modulo
        d[parte] = {**modulo_vuoto(parte), **{k: v for k, v in (d.get(parte) or {}).items() if k in MODULO[parte]}}
        for k in COPIATE:  # anche quelle salvate prima della pulizia delle consegne
            if isinstance(d[parte].get(k), list):
                d[parte][k] = [copiata_pulita(str(t), LUNGHEZZA_VOCI.get(k, 200)) for t in d[parte][k]]
    for a in d.get("argomenti") or []:  # gli esempi copiati dalle domande vere, come le consegne
        if isinstance(a, dict) and isinstance(a.get("esempio"), str):
            a["esempio"] = copiata_pulita(a["esempio"])
    return d


def per_api(conn: sqlite3.Connection, materia_id: int) -> dict:
    r = _riga(conn, materia_id)
    if r is None:
        crea(conn, materia_id)
        r = _riga(conn, materia_id)
    d = dati_di(r)
    return {
        "materia_id": materia_id,
        "aggiornata_il": clock.per_utente(r["aggiornata_il"]),
        "basata_su": json.loads(r["basata_su"]),
        **{k: d[k] for k in CAMPI},
        "verifiche": d["verifiche"],
        "interrogazioni": d["interrogazioni"],
        "note_mie": r["note_mie"],
        "stato": r["stato"],
        "errore": r["errore"] if r["stato"] == "errore" else None,
    }


def per_lezione(conn: sqlite3.Connection, materia_id: int) -> dict | None:
    r = _riga(conn, materia_id)
    if r is None:
        return None
    d = dati_di(r)
    if not ha_contenuto(d) and not r["note_mie"]:
        return None
    return {**{k: d[k] for k in CAMPI}, "verifiche": d["verifiche"], "interrogazioni": d["interrogazioni"],
            "note_mie": r["note_mie"]}


def sufficienza_del_prof(scheda: dict | None) -> float | None:
    """La parte dei punti che fa la sufficienza per questo prof (18 su 30 → 0,6), se il modulo la sa."""
    v = (scheda or {}).get("verifiche") or {}
    totale, sufficienza = v.get("punti_totali"), v.get("sufficienza")
    if isinstance(totale, int) and isinstance(sufficienza, (int, float)) and 0 < sufficienza < totale:
        return sufficienza / totale
    return None


# --------------------------------------------------------------------------- forma dei dati

def _testo(v, massimo: int) -> str:
    return " ".join(str(v).split())[:massimo] if isinstance(v, (str, int, float)) else ""


def _lista_testi(v, massimo_voci: int = 10, massimo: int = 200) -> list[str]:
    if isinstance(v, str):
        v = [v]
    if not isinstance(v, list):
        return []
    uscita, visti = [], set()
    for x in v:
        t = _testo(x, massimo)
        if t and t.casefold() not in visti:
            visti.add(t.casefold())
            uscita.append(t)
    return uscita[:massimo_voci]


def normalizza_tipi(v) -> list[dict]:
    if not isinstance(v, list):
        return []
    somme: dict[str, float] = {}
    for x in v:
        if not isinstance(x, dict):
            continue
        tipo = str(x.get("tipo", "")).strip().lower().replace(" ", "_")
        tipo = tipo if tipo in prompt.TIPI_DOMANDA else "altro"
        try:
            quota = max(0.0, float(x.get("quota", 0)))
        except (TypeError, ValueError):
            quota = 0.0
        somme[tipo] = somme.get(tipo, 0.0) + quota
    totale = sum(somme.values())
    if totale <= 0:
        return []
    tipi = [{"tipo": t, "quota": round(q / totale, 2)} for t, q in somme.items() if q > 0]
    return sorted(tipi, key=lambda t: -t["quota"])


def normalizza_argomenti(v) -> list[dict]:
    if not isinstance(v, list):
        return []
    uscita, visti = [], set()
    for x in v:
        if isinstance(x, str):
            x = {"nome": x}
        if not isinstance(x, dict):
            continue
        nome = _testo(x.get("nome"), 80)
        if not nome or nome.casefold() in visti:
            continue
        visti.add(nome.casefold())
        try:
            volte = max(0, int(x.get("volte") or 0))
        except (TypeError, ValueError):
            volte = 0
        uscita.append({"nome": nome, "volte": volte, "esempio": _testo(x.get("esempio") or "", 200)})
    return sorted(uscita, key=lambda a: -a["volte"])[:15]


_DEFINIZIONE = re.compile(r"defini|che cos['’ ]?[èe]\b|cos['’][èe]\b|cosa (?:si intende|significa|vuol dire)|significato d", re.I)
_ESERCIZIO = re.compile(r"risolv|calcol|semplific|determina|dimostra|scomponi|trova |verifica che|disegna|rappresenta"
                        r"|equazion|disequazion|sistema|espressione|[=+^]|\d\s*[a-z]\b", re.I)


def _normale(testo: str) -> str:
    return " ".join(re.findall(r"[a-zàèéìòù0-9]+", str(testo or "").lower()))


def _trovata(frammento: str, testo_normale: str, parole: set[str]) -> bool:
    """Il pezzo è davvero nel documento? Si perdona qualche differenza (lettura delle foto)."""
    f = _normale(frammento)
    if not f:
        return False
    if f in testo_normale:
        return True
    voci = f.split()
    return len(voci) >= 3 and sum(1 for v in voci if v in parole) / len(voci) >= 0.8


def tipo_vero(tipo, testo: str) -> str:
    """Il tipo dichiarato dal modello, corretto da quello che la domanda chiede davvero."""
    t = str(tipo or "").strip().lower().replace(" ", "_")
    t = t if t in prompt.TIPI_DOMANDA else "altro"
    if t == "definizione" and not _DEFINIZIONE.search(testo):
        return "esercizio" if _ESERCIZIO.search(testo) else "aperta"
    return t


def _con_prova(voci, testi: str, parole: set[str], massimo_voci: int = 10) -> list[str]:
    """Solo le voci con una prova che si trova nei documenti: quelle senza si scartano."""
    if not isinstance(voci, list):
        return []
    uscita, visti = [], set()
    for v in voci:
        if not isinstance(v, dict):
            continue
        frase = _testo(v.get("frase"), 200)
        if frase and frase.casefold() not in visti and _trovata(v.get("prova") or "", testi, parole):
            visti.add(frase.casefold())
            uscita.append(frase)
    return uscita[:massimo_voci]


def _intero(v) -> int | None:
    n = _numero(v)
    return int(n) if n is not None and n == int(n) else None


def _numero(v) -> int | float | None:
    if isinstance(v, bool) or v is None:
        return None
    try:
        n = round(float(str(v).replace(",", ".")), 2)
    except (TypeError, ValueError):
        return None
    if not 0 < n <= 1000:
        return None
    return int(n) if n == int(n) else n


def _sistema_numeri(m: dict) -> None:
    """La sufficienza ha senso solo con i punti in tutto, e sotto di loro (come controlla il computer)."""
    totale, sufficienza = m.get("punti_totali"), m.get("sufficienza")
    if sufficienza is not None and (totale is None or sufficienza >= totale):
        m["sufficienza"] = None


def _numero_scritto(n: int | float, testo_normale: str) -> bool:
    """Il numero si legge nei documenti (già passati da `_normale`: "18,5" è diventato "18 5")?"""
    intero, _, decimali = f"{n}".partition(".")
    forma = intero + (f" {decimali}" if decimali else "")
    return re.search(rf"(?<![0-9]){re.escape(forma)}(?![0-9])", testo_normale) is not None


def normalizza_modulo(parte: str, v: dict, base: dict | None = None) -> dict:
    """I campi mandati da Andrea (PUT) sopra quelli che ci sono già."""
    uscita = dict(base) if base else modulo_vuoto(parte)
    for k, tipo in MODULO[parte].items():
        if k not in v:
            continue
        if tipo == "testo":
            uscita[k] = _testo(v[k] or "", 300)
        elif tipo == "lista":
            uscita[k] = _lista_testi(v[k], massimo_voci=6, massimo=LUNGHEZZA_VOCI.get(k, 200))
        else:
            uscita[k] = _intero(v[k]) if k == "punti_totali" else _numero(v[k])
    _sistema_numeri(uscita)
    return uscita


_NUMERO_INIZIALE = re.compile(r"^\s*(?:\d{1,2}|[a-hA-H])\s*[).]\s+")


def copiata_pulita(voce: str, massimo: int = 200) -> str:
    """Una consegna o una domanda copiata dai documenti, da leggere: senza il numero davanti ("2) …") e con le formule
    in testo normale al posto del LaTeX della lettura delle foto."""
    return _testo(stile.formule_in_testo(_NUMERO_INIZIALE.sub("", voce)), massimo)


def modulo_dai_documenti(parte: str, v, testo: str, parole: set[str], copie: tuple[str, set[str]] | None = None) -> dict:
    """La parte del modulo scritta dall'AI: resta solo quello che ha una prova nei documenti di quella parte (le verifiche
    per `verifiche`, le interrogazioni per `interrogazioni`), come il resto della scheda. Le voci copiate (consegne,
    domande) si cercano in `copie` quando c'è: le consegne solo nelle verifiche vere, mai nei racconti degli esiti."""
    uscita = modulo_vuoto(parte)
    if not isinstance(v, dict) or not testo:
        return uscita
    for k, tipo in MODULO[parte].items():
        x = v.get(k)
        if tipo == "testo":
            if isinstance(x, dict) and _trovata(x.get("prova") or "", testo, parole):
                uscita[k] = _testo(x.get("frase") or "", 300)
        elif tipo == "lista" and k in COPIATE:
            dove, dove_parole = copie if copie is not None else (testo, parole)
            voci = _lista_testi(x, massimo_voci=12, massimo=LUNGHEZZA_VOCI.get(k, 200))
            uscita[k] = [copiata_pulita(t, LUNGHEZZA_VOCI.get(k, 200)) for t in voci if dove and _trovata(t, dove, dove_parole)][:6]
        elif tipo == "lista":
            uscita[k] = _con_prova(x, testo, parole, massimo_voci=6)
    punti = v.get("punti") if parte == "verifiche" else None
    if isinstance(punti, dict):
        totale, sufficienza = _intero(punti.get("totale")), _numero(punti.get("sufficienza"))
        uscita["punti_totali"] = totale if totale is not None and _numero_scritto(totale, testo) else None
        uscita["sufficienza"] = sufficienza if sufficienza is not None and _numero_scritto(sufficienza, testo) else None
    _sistema_numeri(uscita)
    return uscita


def conta(o: dict, documenti: list[dict], esiti: list[dict]) -> dict:
    """La scheda dalle domande vere: il modello le elenca, i numeri li fa il codice."""
    testi = {d["id"]: _normale(d["testo"]) for d in documenti}
    for i, e in enumerate(esiti, start=1):
        testi[f"E{i}"] = _normale(" ".join(e["uscite"] + e["altre"]))
    parole_doc = {k: set(v.split()) for k, v in testi.items()}
    domande, viste = [], set()
    for d in o.get("domande") or []:
        if not isinstance(d, dict):
            continue
        doc, testo = str(d.get("doc") or "").strip(), _testo(d.get("testo"), 400)
        chiave = (doc, _normale(testo))
        if doc not in testi or not testo or chiave in viste or not _trovata(testo, testi[doc], parole_doc[doc]):
            continue
        viste.add(chiave)
        domande.append({"doc": doc, "testo": testo, "tipo": tipo_vero(d.get("tipo"), testo),
                        "argomento": _testo(d.get("argomento"), 80)})
    quanti: dict[str, int] = {}
    for d in domande:
        quanti[d["tipo"]] = quanti.get(d["tipo"], 0) + 1
    totale = sum(quanti.values())
    tipi = sorted(({"tipo": t, "quota": round(n / totale, 2)} for t, n in quanti.items()), key=lambda x: -x["quota"])
    gruppi: dict[str, dict] = {}
    for d in domande:
        if d["argomento"]:
            g = gruppi.setdefault(d["argomento"].casefold(), {"nome": d["argomento"], "docs": set(), "esempio": copiata_pulita(d["testo"]), "n": 0})
            g["docs"].add(d["doc"])
            g["n"] += 1
    argomenti = [{"nome": g["nome"], "volte": len(g["docs"]), "esempio": g["esempio"]}
                 for g in sorted(gruppi.values(), key=lambda g: (-len(g["docs"]), -g["n"]))][:15]
    tutto = " ".join(testi.values())
    parole = set(tutto.split())
    lunghezza = o.get("lunghezza_risposte")
    solo_verifiche = " ".join(testi[d["id"]] for d in documenti if d.get("tipo") == "verifica")
    scritti = " ".join([solo_verifiche] + [testi[f"E{i}"] for i, e in enumerate(esiti, start=1) if e.get("tipo") != "orale"])
    orali = " ".join([testi[d["id"]] for d in documenti if d.get("tipo") == "interrogazione"]
                     + [testi[f"E{i}"] for i, e in enumerate(esiti, start=1) if e.get("tipo") == "orale"])
    return {
        "verifiche": modulo_dai_documenti("verifiche", o.get("verifiche"), scritti, set(scritti.split()),
                                          copie=(solo_verifiche, set(solo_verifiche.split()))),
        "interrogazioni": modulo_dai_documenti("interrogazioni", o.get("interrogazioni"), orali, set(orali.split())),
        "argomenti": argomenti,
        "tipi_domanda": tipi,
        "cosa_corregge": _con_prova(o.get("cosa_corregge"), tutto, parole),
        "cosa_premia": _con_prova(o.get("cosa_premia"), tutto, parole),
        "lunghezza_risposte": _testo(lunghezza.get("frase"), 200) if isinstance(lunghezza, dict)
        and _trovata(lunghezza.get("prova") or "", tutto, parole) else "",
        "consigli": _con_prova(o.get("consigli"), tutto, parole, massimo_voci=6),
    }


def valida(o: dict) -> list[str]:
    errori = []
    if not isinstance(o.get("domande"), list):
        errori.append('serve "domande", la lista delle domande copiate dai documenti')
    elif any(not isinstance(d, dict) for d in o["domande"]):
        errori.append('ogni domanda deve avere "doc", "testo", "tipo", "argomento"')
    for campo in ("cosa_corregge", "cosa_premia", "consigli"):
        if campo in o and not isinstance(o[campo], list):
            errori.append(f'"{campo}" deve essere una lista')
    for parte in MODULO:
        if o.get(parte) is not None and not isinstance(o[parte], dict):
            errori.append(f'"{parte}" deve essere un oggetto, oppure null')
    return errori


# --------------------------------------------------------------------------- le parti di Andrea

def _chiave(x: str) -> str:
    return " ".join(str(x).split()).casefold()


def registra_modifiche(di_andrea: dict, vecchi: dict, nuovi: dict) -> dict:
    """Confronta la scheda prima e dopo un PUT e ricorda cosa ha fatto Andrea."""
    d = json.loads(json.dumps(di_andrea or {}))
    liste = d.setdefault("liste", {})
    for campo in CAMPI_LISTA:
        if campo not in nuovi:
            continue
        prima = {_chiave(x): x for x in vecchi.get(campo, [])}
        dopo = {_chiave(x): x for x in nuovi[campo]}
        stato = liste.setdefault(campo, {"aggiunti": [], "tolti": []})
        aggiunti = {_chiave(x): x for x in stato["aggiunti"]}
        tolti = {_chiave(x): x for x in stato["tolti"]}
        for k, x in dopo.items():
            if k not in prima:
                aggiunti[k] = x
                tolti.pop(k, None)
        for k, x in prima.items():
            if k not in dopo:
                tolti[k] = x
                aggiunti.pop(k, None)
        stato["aggiunti"], stato["tolti"] = list(aggiunti.values()), list(tolti.values())
    if "argomenti" in nuovi:
        stato = d.setdefault("argomenti", {"aggiunti": [], "tolti": []})
        prima = {_chiave(a["nome"]): a for a in vecchi.get("argomenti", [])}
        dopo = {_chiave(a["nome"]): a for a in nuovi["argomenti"]}
        aggiunti = {_chiave(a["nome"]): a for a in stato["aggiunti"]}
        tolti = {_chiave(n): n for n in stato["tolti"]}
        for k, a in dopo.items():
            if k not in prima or prima[k] != a:
                aggiunti[k] = a
                tolti.pop(k, None)
        for k, a in prima.items():
            if k not in dopo:
                tolti[k] = a["nome"]
                aggiunti.pop(k, None)
        stato["aggiunti"], stato["tolti"] = list(aggiunti.values()), list(tolti.values())
    for campo in ("lunghezza_risposte", "tipi_domanda"):
        if campo in nuovi and nuovi[campo] != vecchi.get(campo):
            d[campo] = nuovi[campo]
    for parte in MODULO:
        if parte not in nuovi:
            continue
        prima_p, dopo_p = vecchi.get(parte) or modulo_vuoto(parte), nuovi[parte]
        stato_p = d.setdefault("modulo", {}).setdefault(parte, {"campi": {}, "liste": {}})
        for k, tipo in MODULO[parte].items():
            if tipo == "lista":
                _segna_lista(stato_p["liste"].setdefault(k, {"aggiunti": [], "tolti": []}), prima_p.get(k) or [], dopo_p.get(k) or [])
            elif dopo_p.get(k) != prima_p.get(k):
                stato_p["campi"][k] = dopo_p.get(k)
    return d


def _segna_lista(stato: dict, prima: list[str], dopo: list[str]) -> None:
    prima_k, dopo_k = {_chiave(x): x for x in prima}, {_chiave(x): x for x in dopo}
    aggiunti = {_chiave(x): x for x in stato["aggiunti"]}
    tolti = {_chiave(x): x for x in stato["tolti"]}
    for k, x in dopo_k.items():
        if k not in prima_k:
            aggiunti[k] = x
            tolti.pop(k, None)
    for k, x in prima_k.items():
        if k not in dopo_k:
            tolti[k] = x
            aggiunti.pop(k, None)
    stato["aggiunti"], stato["tolti"] = list(aggiunti.values()), list(tolti.values())


def _unisci_lista(voci: list[str], stato: dict) -> list[str]:
    tolti = {_chiave(x) for x in stato.get("tolti", [])}
    voci = [x for x in voci if _chiave(x) not in tolti]
    presenti = {_chiave(x) for x in voci}
    return voci + [x for x in stato.get("aggiunti", []) if _chiave(x) not in presenti]


def unisci(dati_ai: dict, di_andrea: dict) -> dict:
    """La risposta dell'AI con sopra le parti di Andrea: niente di suo si perde."""
    uscita = json.loads(json.dumps(dati_ai))
    for campo, stato in (di_andrea.get("liste") or {}).items():
        tolti = {_chiave(x) for x in stato.get("tolti", [])}
        voci = [x for x in uscita.get(campo, []) if _chiave(x) not in tolti]
        presenti = {_chiave(x) for x in voci}
        voci += [x for x in stato.get("aggiunti", []) if _chiave(x) not in presenti]
        uscita[campo] = voci
    argomenti = di_andrea.get("argomenti")
    if argomenti:
        tolti = {_chiave(n) for n in argomenti.get("tolti", [])}
        suoi = {_chiave(a["nome"]): a for a in argomenti.get("aggiunti", [])}
        voci = [suoi.pop(_chiave(a["nome"]), a) for a in uscita.get("argomenti", []) if _chiave(a["nome"]) not in tolti]
        uscita["argomenti"] = voci + list(suoi.values())
    for campo in ("lunghezza_risposte", "tipi_domanda"):
        if campo in di_andrea:
            uscita[campo] = di_andrea[campo]
    for parte, stato_p in (di_andrea.get("modulo") or {}).items():
        if parte not in MODULO:
            continue
        m = {**modulo_vuoto(parte), **(uscita.get(parte) or {})}
        for k, stato in (stato_p.get("liste") or {}).items():
            if k in m:
                m[k] = _unisci_lista(m[k] or [], stato)
        for k, v in (stato_p.get("campi") or {}).items():
            if k in m:
                m[k] = v
        _sistema_numeri(m)
        uscita[parte] = m
    return uscita


def modifica(conn: sqlite3.Connection, materia_id: int, cambi: dict) -> None:
    """PUT della scheda: i campi mandati diventano quelli di Andrea."""
    with db.transazione(conn):
        r = _riga(conn, materia_id)
        vecchi = dati_di(r)
        nuovi = {}
        if "argomenti" in cambi:
            nuovi["argomenti"] = normalizza_argomenti(cambi["argomenti"])
        if "tipi_domanda" in cambi:
            nuovi["tipi_domanda"] = normalizza_tipi(cambi["tipi_domanda"])
        for campo in CAMPI_LISTA:
            if campo in cambi:
                nuovi[campo] = _lista_testi(cambi[campo], massimo_voci=20)
        if "lunghezza_risposte" in cambi:
            nuovi["lunghezza_risposte"] = _testo(cambi["lunghezza_risposte"] or "", 200)
        for parte in MODULO:
            if isinstance(cambi.get(parte), dict):
                nuovi[parte] = normalizza_modulo(parte, cambi[parte], base=vecchi[parte])
        di_andrea = registra_modifiche(json.loads(r["di_andrea"] or "{}"), vecchi, nuovi)
        dati = {**vecchi, **nuovi}
        note = cambi["note_mie"] if "note_mie" in cambi else r["note_mie"]
        stato = r["stato"]
        if stato == "vuota" and (ha_contenuto(dati) or note):
            stato = "pronta"
        conn.execute(
            "UPDATE schede SET dati = ?, di_andrea = ?, note_mie = ?, stato = ? WHERE materia_id = ?",
            (json.dumps(dati, ensure_ascii=False), json.dumps(di_andrea, ensure_ascii=False), note, stato, materia_id),
        )


# --------------------------------------------------------------------------- dopo la modalità prova

def dopo_le_chiavi(db_path: str) -> None:
    """Arrivata la chiave Mistral, quello che aveva fatto il finto non vale più:
    - le foto lette in prova (testo segnaposto) si rileggono: prima sul NAS, poi con l'OCR
      solo se serve davvero, sempre con stima e tetto;
    - le schede scritte in prova si rifanno da zero (restano le parti di Andrea e le sue note)."""
    with db.aperta(db_path) as conn, db.transazione(conn):
        conn.execute("UPDATE materiali SET stato = 'in_lettura', errore = NULL WHERE da_prova = 1"
                     " AND stato != 'in_lettura'")
        for r in conn.execute("SELECT * FROM schede WHERE da_prova = 1").fetchall():
            di_andrea = json.loads(r["di_andrea"] or "{}")
            dati = unisci(vuota(), di_andrea)
            conn.execute("UPDATE schede SET dati = ?, da_prova = 0, aggiornata_il = NULL,"
                         " basata_su = '{\"verifiche\": 0, \"interrogazioni\": 0, \"esiti\": 0}' WHERE materia_id = ?",
                         (json.dumps(dati, ensure_ascii=False), r["materia_id"]))
            if ha_fonti(conn, r["materia_id"]):
                segna_da_aggiornare(conn, r["materia_id"])
            else:
                stato = "pronta" if ha_contenuto(dati) or r["note_mie"] else "vuota"
                conn.execute("UPDATE schede SET stato = ? WHERE materia_id = ?", (stato, r["materia_id"]))


# --------------------------------------------------------------------------- aggiornamento AI

def segna_da_aggiornare(conn: sqlite3.Connection, materia_id: int) -> None:
    if _riga(conn, materia_id) is None:
        crea(conn, materia_id)
    conn.execute(
        "UPDATE schede SET da_aggiornare = da_aggiornare + 1, stato = 'in_aggiornamento', errore = NULL"
        " WHERE materia_id = ?",
        (materia_id,),
    )


def ha_fonti(conn: sqlite3.Connection, materia_id: int) -> bool:
    return conn.execute(
        "SELECT (SELECT COUNT(*) FROM materiali WHERE materia_id = ? AND stato = 'pronto'"
        " AND tipo IN ('verifica', 'interrogazione')) + (SELECT COUNT(*) FROM eventi WHERE materia_id = ?"
        " AND esito IS NOT NULL) AS n",
        (materia_id, materia_id),
    ).fetchone()["n"] > 0


def _documenti(conn: sqlite3.Connection, materia_id: int) -> list[dict]:
    documenti, totale = [], 0
    for i, m in enumerate(conn.execute(
        "SELECT id, tipo, titolo, data, testo FROM materiali WHERE materia_id = ? AND stato = 'pronto'"
        " AND tipo IN ('verifica', 'interrogazione') ORDER BY data DESC, id DESC",
        (materia_id,),
    ), start=1):
        testo = m["testo"][: max(0, CARATTERI_DOCUMENTI_MASSIMI - totale)]
        if not testo:
            break
        totale += len(testo)
        documenti.append({"id": f"V{i}", "tipo": m["tipo"], "titolo": m["titolo"], "data": m["data"], "testo": testo})
    return documenti


def _esiti(conn: sqlite3.Connection, materia_id: int) -> list[dict]:
    esiti = []
    for e in conn.execute(
        "SELECT * FROM eventi WHERE materia_id = ? AND esito IS NOT NULL ORDER BY data DESC LIMIT 10", (materia_id,)
    ):
        esito = json.loads(e["esito"])
        ids = esito.get("domande_uscite") or []
        uscite = [r["testo"] for r in conn.execute(
            f"SELECT testo FROM domande WHERE id IN ({','.join('?' * len(ids))})", ids)] if ids else []
        uscite += [str(x) for x in esito.get("testi_uscite") or [] if str(x).strip()]  # 1.4.6: simulata e schema
        altre = [r.strip() for r in (esito.get("altre_domande") or "").split("\n") if r.strip()]
        esiti.append({"quando": e["data"], "tipo": "orale" if e["tipo"] == "orale" else "scritta",
                      "voto": esito.get("voto") or "", "uscite": uscite, "altre": altre})
    return esiti


def conta_basi(conn: sqlite3.Connection, materia_id: int) -> dict:
    r = conn.execute(
        "SELECT (SELECT COUNT(*) FROM materiali WHERE materia_id = ? AND stato = 'pronto' AND tipo = 'verifica') AS v,"
        " (SELECT COUNT(*) FROM materiali WHERE materia_id = ? AND stato = 'pronto' AND tipo = 'interrogazione') AS i,"
        " (SELECT COUNT(*) FROM eventi WHERE materia_id = ? AND esito IS NOT NULL) AS e",
        (materia_id, materia_id, materia_id),
    ).fetchone()
    return {"verifiche": r["v"], "interrogazioni": r["i"], "esiti": r["e"]}


def aggiorna(ctx, materia_id: int) -> None:
    """Un aggiornamento in sottofondo. Finisce sempre in 'pronta' o 'errore' (con frase)."""
    with db.aperta(ctx.settings.db_path) as conn:
        r = _riga(conn, materia_id)
        materia = conn.execute("SELECT * FROM materie WHERE id = ?", (materia_id,)).fetchone()
        if r is None or materia is None or r["da_aggiornare"] == 0:
            return
        richiesta = r["da_aggiornare"]
        documenti, esiti = _documenti(conn, materia_id), _esiti(conn, materia_id)
        di_andrea, note = json.loads(r["di_andrea"] or "{}"), r["note_mie"]
        basi = conta_basi(conn, materia_id)
    nome_materia = {"nome": materia["nome"], "prof": materia["prof"]}

    def chiudi(stato: str, dati: dict | None = None, errore: str | None = None, novita: str | None = None):
        with db.aperta(ctx.settings.db_path) as conn, db.transazione(conn):
            attuale_r = _riga(conn, materia_id)
            if attuale_r is None:
                return  # materia cancellata nel frattempo
            ancora = attuale_r["da_aggiornare"] != richiesta  # è arrivata un'altra richiesta: si rifà
            if dati is not None:
                # Le parti di Andrea si rileggono adesso: un PUT fatto durante l'attesa vale.
                dati = unisci(dati, json.loads(attuale_r["di_andrea"] or "{}"))
                # In modalità prova la scheda la scrive il generatore finto: si segna, e quando
                # arriva la chiave Mistral si rifà da zero (dopo_le_chiavi).
                conn.execute(
                    "UPDATE schede SET dati = ?, aggiornata_il = ?, basata_su = ?, da_prova = ? WHERE materia_id = ?",
                    (json.dumps(dati, ensure_ascii=False), clock.adesso(), json.dumps(basi),
                     0 if ctx.testo.a_pagamento else 1, materia_id),
                )
            conn.execute(
                "UPDATE schede SET stato = ?, errore = ?, da_aggiornare = ? WHERE materia_id = ?",
                ("in_aggiornamento" if ancora else stato, None if ancora else errore,
                 attuale_r["da_aggiornare"] if ancora else 0, materia_id),
            )
            if novita and not ancora:
                db.aggiungi_novita(conn, "scheda_aggiornata", novita, materia_id=materia_id)

    if not documenti and not esiti:
        # Nessuna verifica né esito (per esempio un materiale passato da verifica ad appunti): di quello
        # che aveva scritto l'AI non resta niente, restano solo le parti scritte da Andrea.
        dati = unisci(vuota(), di_andrea)
        chiudi("pronta" if ha_contenuto(dati) or note else "vuota", dati=dati)
        return
    try:
        oggetto = ai.chiedi_json(
            cassa=ctx.cassa, fornitore=ctx.testo, compito="scheda",
            messaggi=prompt.messaggi_scheda(nome_materia, documenti, esiti),
            modello=ctx.settings.modello_piano, valida=valida, max_token=TOKEN_SCHEDA_USCITA,
            cosa=f"Scheda del prof di {materia['nome']}",
            dati={"materia": nome_materia, "documenti": documenti, "esiti": esiti},
            materia_id=materia_id,
        )
    except TettoRaggiunto as e:
        chiudi("errore", errore="Non ho aggiornato la scheda: " + e.frase[0].lower() + e.frase[1:])
        return
    except (ErroreFornitore, ai.RispostaNonUsabile) as e:
        chiudi("errore", errore="Non ho aggiornato la scheda. " + e.frase)
        return
    except Exception:
        log.exception("scheda %s: errore inatteso", materia_id)
        chiudi("errore", errore="Lectio ha avuto un problema aggiornando la scheda. Riprova con Aggiorna.")
        return
    prof = (materia["prof"] or "").strip()
    chiudi("pronta", dati=conta(oggetto, documenti, esiti),
           novita=f"Ho aggiornato la scheda del prof {prof}" if prof else f"Ho aggiornato la scheda di {materia['nome']}")
