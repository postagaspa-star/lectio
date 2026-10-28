"""La scheda del prof (contratto, sezione 5).

- Si aggiorna da sola in sottofondo quando arriva una verifica o un'interrogazione letta,
  o un esito; a mano con POST …/scheda/aggiorna.
- Le modifiche di Andrea (PUT) si tengono: si ricorda cosa ha aggiunto, tolto o cambiato
  (`di_andrea`) e ogni aggiornamento dell'AI lo rimette a posto sopra la risposta nuova.
- `note_mie` non entra mai nell'uscita dell'AI: si rimette sempre uguale."""

import json
import logging
import sqlite3

from . import ai, clock, db, prompt
from .rete import ErroreFornitore
from .spesa import TettoRaggiunto

log = logging.getLogger("uvicorn.error")

CAMPI_LISTA = ("cosa_corregge", "cosa_premia", "consigli")
CAMPI = ("argomenti", "tipi_domanda", "cosa_corregge", "cosa_premia", "lunghezza_risposte", "consigli")
CARATTERI_DOCUMENTI_MASSIMI = 60_000


def vuota() -> dict:
    return {"argomenti": [], "tipi_domanda": [], "cosa_corregge": [], "cosa_premia": [], "lunghezza_risposte": "",
            "consigli": []}


def crea(conn: sqlite3.Connection, materia_id: int) -> None:
    conn.execute("INSERT INTO schede (materia_id, dati) VALUES (?, ?)", (materia_id, json.dumps(vuota())))


def _riga(conn: sqlite3.Connection, materia_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM schede WHERE materia_id = ?", (materia_id,)).fetchone()


def dati_di(riga: sqlite3.Row) -> dict:
    d = vuota()
    d.update(json.loads(riga["dati"] or "{}"))
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
        "note_mie": r["note_mie"],
        "stato": r["stato"],
        "errore": r["errore"] if r["stato"] == "errore" else None,
    }


def per_lezione(conn: sqlite3.Connection, materia_id: int) -> dict | None:
    r = _riga(conn, materia_id)
    if r is None:
        return None
    d = dati_di(r)
    if not any(d[k] for k in CAMPI) and not r["note_mie"]:
        return None
    return {**{k: d[k] for k in CAMPI}, "note_mie": r["note_mie"]}


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


def normalizza(o: dict) -> dict:
    return {
        "argomenti": normalizza_argomenti(o.get("argomenti")),
        "tipi_domanda": normalizza_tipi(o.get("tipi_domanda")),
        "cosa_corregge": _lista_testi(o.get("cosa_corregge")),
        "cosa_premia": _lista_testi(o.get("cosa_premia")),
        "lunghezza_risposte": _testo(o.get("lunghezza_risposte") or "", 200),
        "consigli": _lista_testi(o.get("consigli"), massimo_voci=6),
    }


def valida(o: dict) -> list[str]:
    errori = []
    for campo in ("argomenti", "tipi_domanda", "cosa_corregge", "cosa_premia", "consigli"):
        if campo in o and not isinstance(o[campo], (list, str)):
            errori.append(f'"{campo}" deve essere una lista')
    if "argomenti" not in o or "tipi_domanda" not in o:
        errori.append('mancano "argomenti" o "tipi_domanda"')
    if isinstance(o.get("argomenti"), list) and any(not isinstance(a, (dict, str)) for a in o["argomenti"]):
        errori.append('ogni argomento deve essere un oggetto con "nome", "volte", "esempio"')
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
    return d


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
        di_andrea = registra_modifiche(json.loads(r["di_andrea"] or "{}"), vecchi, nuovi)
        dati = {**vecchi, **nuovi}
        note = cambi["note_mie"] if "note_mie" in cambi else r["note_mie"]
        stato = r["stato"]
        if stato == "vuota" and (any(dati[k] for k in CAMPI) or note):
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
                stato = "pronta" if any(dati[k] for k in CAMPI) or r["note_mie"] else "vuota"
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
        attuale, di_andrea, note = dati_di(r), json.loads(r["di_andrea"] or "{}"), r["note_mie"]
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
        chiudi("pronta" if any(attuale[k] for k in CAMPI) or note else "vuota")
        return
    try:
        oggetto = ai.chiedi_json(
            cassa=ctx.cassa, fornitore=ctx.testo, compito="scheda",
            messaggi=prompt.messaggi_scheda(nome_materia, attuale, di_andrea, note, documenti, esiti),
            modello=ctx.settings.modello_piano, valida=valida, max_token=2500,
            cosa=f"Scheda del prof di {materia['nome']}",
            dati={"materia": nome_materia, "attuale": attuale, "documenti": documenti, "esiti": esiti},
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
    chiudi("pronta", dati=normalizza(oggetto),
           novita=f"Ho aggiornato la scheda del prof {prof}" if prof else f"Ho aggiornato la scheda di {materia['nome']}")
