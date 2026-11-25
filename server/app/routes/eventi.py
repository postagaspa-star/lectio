"""Verifiche e interrogazioni in arrivo, esiti e centratura (contratto, sezione 7)."""

import json
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends

from .. import clock, db, forme, simulata
from .. import scheda as schede
from ..auth import richiede_token
from ..db import get_conn
from ..errori import non_trovato, non_valido
from ..schemi import EsitoIn, EventoIn, EventoPatch
from ..servizi import Servizi, servizi
from .materie import trova_materia

router = APIRouter(dependencies=[Depends(richiede_token)])


def trova_evento(conn: sqlite3.Connection, evento_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM eventi WHERE id = ?", (evento_id,)).fetchone()
    if r is None:
        raise non_trovato("Verifica o interrogazione non trovata.")
    return r


@router.get("/eventi")
def elenco(da: str | None = None, materia_id: int | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    if da is not None and not clock.data_valida(da):
        raise non_valido("La data va scritta come AAAA-MM-GG, per esempio 2026-10-30.", campo="da")
    dal = da or (clock.oggi() - timedelta(days=30)).isoformat()
    sql, parametri = "SELECT * FROM eventi WHERE data >= ?", [dal]
    if materia_id is not None:
        sql += " AND materia_id = ?"
        parametri.append(materia_id)
    return [forme.evento(conn, r) for r in conn.execute(sql + " ORDER BY data, id", parametri)]


@router.post("/materie/{materia_id}/eventi", status_code=201)
def crea(materia_id: int, corpo: EventoIn, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    cur = conn.execute(
        "INSERT INTO eventi (materia_id, tipo, argomento, data, creato_il) VALUES (?, ?, ?, ?, ?)",
        (materia_id, corpo.tipo, " ".join(corpo.argomento.split()), corpo.data, clock.adesso()),
    )
    return forme.evento(conn, trova_evento(conn, cur.lastrowid))


@router.patch("/eventi/{evento_id}")
def cambia(evento_id: int, corpo: EventoPatch, conn: sqlite3.Connection = Depends(get_conn)):
    trova_evento(conn, evento_id)
    cambi = corpo.model_dump(exclude_none=True)
    if "argomento" in cambi:
        cambi["argomento"] = " ".join(cambi["argomento"].split())
    for campo, valore in cambi.items():
        conn.execute(f"UPDATE eventi SET {campo} = ? WHERE id = ?", (valore, evento_id))
    return forme.evento(conn, trova_evento(conn, evento_id))


@router.delete("/eventi/{evento_id}")
def cancella(evento_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_evento(conn, evento_id)
    conn.execute("DELETE FROM eventi WHERE id = ?", (evento_id,))
    return {"ok": True}


# Le verifiche simulate che contano come preparate (come per "Dopo la verifica" in Oggi).
STATI_SIMULATA_FATTA = ("pronta", "in_corso", "in_correzione", "corretta")


def _parti_dello_schema(contenuto: dict) -> list[tuple[int, str]]:
    """Le parti con un titolo; il quadro d'insieme finale (una sola tabella) non è un argomento della verifica."""
    sezioni = contenuto.get("sezioni") or []
    parti = []
    for n, s in enumerate(sezioni, start=1):
        blocchi = s.get("blocchi") or []
        if n == len(sezioni) and n > 1 and len(blocchi) == 1 and blocchi[0].get("tipo") == "tabella":
            continue
        titolo = " ".join(str(s.get("titolo") or "").split())
        if titolo:
            parti.append((n, titolo))
    return parti


def previste(conn: sqlite3.Connection, evento_id: int) -> list[dict]:
    """1.4.6: quello che Lectio aveva preparato per la verifica, da spuntare in "Com'è andata": le domande della
    lezione, gli argomenti delle verifiche di prova (1.6.3), le parti dello schema. Ogni voce ha una chiave stabile."""
    gruppi = []
    lezione_id = forme.lezione_dell_evento(conn, evento_id)
    if lezione_id is not None:
        voci = [{"chiave": f"domanda:{d['id']}", "testo": d["testo"]} for d in conn.execute(
            "SELECT id, testo FROM domande WHERE lezione_id = ? AND tolta = 0 ORDER BY n", (lezione_id,))]
        if voci:
            gruppi.append({"fonte": "lezione", "id": lezione_id, "voci": voci})
    # 1.6.3: il prof non ripete gli esercizi delle verifiche di prova, ma ne chiede gli argomenti: si spuntano gli
    # argomenti di tutte le verifiche di prova della verifica (dalla più recente), ognuno una volta sola.
    voci, visti, ultima = [], set(), None
    for x in conn.execute(
            f"SELECT id, domande FROM simulate WHERE evento_id = ? AND stato IN ({','.join('?' * len(STATI_SIMULATA_FATTA))})"
            " ORDER BY id DESC", (evento_id, *STATI_SIMULATA_FATTA)):
        ultima = ultima or x["id"]
        for d in json.loads(x["domande"] or "[]"):
            if not isinstance(d, dict) or d.get("n") is None or not d.get("testo"):
                continue
            testo = simulata.argomento_di(d)
            if testo.casefold() not in visti:
                visti.add(testo.casefold())
                voci.append({"chiave": f"simulata:{x['id']}:{d['n']}", "testo": testo})
    if voci:
        gruppi.append({"fonte": "simulata", "id": ultima, "voci": voci})
    s = conn.execute("SELECT id, contenuto FROM schemi WHERE evento_id = ? AND stato = 'pronto' AND contenuto IS NOT NULL"
                     " ORDER BY id DESC LIMIT 1", (evento_id,)).fetchone()
    if s is not None:
        voci = [{"chiave": f"schema:{s['id']}:{n}", "testo": titolo} for n, titolo in _parti_dello_schema(json.loads(s["contenuto"]))]
        if voci:
            gruppi.append({"fonte": "schema", "id": s["id"], "voci": voci})
    return gruppi


@router.get("/eventi/{evento_id}/previste")
def elenco_previste(evento_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_evento(conn, evento_id)
    return {"gruppi": previste(conn, evento_id)}


@router.post("/eventi/{evento_id}/esito")
def esito(evento_id: int, corpo: EsitoIn, conn: sqlite3.Connection = Depends(get_conn),
          s: Servizi = Depends(servizi)):
    e = trova_evento(conn, evento_id)
    ids = list(dict.fromkeys(corpo.domande_uscite))
    if ids:
        validi = {r["id"] for r in conn.execute(
            f"SELECT d.id FROM domande d JOIN lezioni l ON l.id = d.lezione_id WHERE l.evento_id = ?"
            f" AND d.id IN ({','.join('?' * len(ids))})", (evento_id, *ids))}
        if len(validi) != len(ids):
            raise non_valido("Alcune domande indicate non sono della lezione di questa verifica.",
                             campo="domande_uscite")
    # 1.4.6: le voci di "Com'è andata" (domande della lezione o della simulata, parti dello schema).
    chiavi = list(dict.fromkeys(corpo.voci_uscite))
    voci = {v["chiave"]: (g["fonte"], v["testo"]) for g in previste(conn, evento_id) for v in g["voci"]} if chiavi else {}
    if any(c not in voci for c in chiavi):
        raise non_valido("Alcune cose indicate non sono tra quelle preparate per questa verifica. Riapri la pagina e riprova.",
                         campo="voci_uscite")
    altre_previste = []
    for c in chiavi:
        fonte, testo = voci[c]
        if fonte == "lezione":
            ids.append(int(c.split(":")[1]))
        else:
            altre_previste.append(f"argomento: {testo}")
    ids = list(dict.fromkeys(ids))
    altre = [r.strip() for r in corpo.altre_domande.split("\n") if r.strip()]
    previste_uscite = len(ids) + len(altre_previste)
    totale = previste_uscite + len(altre)
    risultato = {"previste_uscite": previste_uscite, "totale_vere": totale,
                 "percentuale": int(round(100 * previste_uscite / totale)) if totale else 0}
    salvato = {"domande_uscite": ids, "voci_uscite": [c for c in chiavi if not c.startswith("domanda:")],
               "testi_uscite": altre_previste, "altre_domande": corpo.altre_domande.strip(),
               "voto": (corpo.voto or "").strip() or None, **risultato, "salvato_il": clock.adesso()}
    with db.transazione(conn):
        conn.execute("UPDATE eventi SET esito = ? WHERE id = ?", (json.dumps(salvato, ensure_ascii=False), evento_id))
        schede.segna_da_aggiornare(conn, e["materia_id"])
    s.lavoro.sveglia("sfondo")
    return risultato


@router.post("/eventi/{evento_id}/nascondi-racconto")
def nascondi_racconto(evento_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    """1.4.5: chiude la scheda "Dopo la verifica" di Oggi senza raccontare (l'esito resta vuoto)."""
    trova_evento(conn, evento_id)
    conn.execute("UPDATE eventi SET racconto_nascosto_il = ? WHERE id = ?", (clock.adesso(), evento_id))
    return {"ok": True}

