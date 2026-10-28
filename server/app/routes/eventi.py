"""Verifiche e interrogazioni in arrivo, esiti e centratura (contratto, sezione 7)."""

import json
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, Depends

from .. import clock, db, forme
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
    altre = [r.strip() for r in corpo.altre_domande.split("\n") if r.strip()]
    previste = len(ids)
    totale = previste + len(altre)
    risultato = {"previste_uscite": previste, "totale_vere": totale,
                 "percentuale": int(round(100 * previste / totale)) if totale else 0}
    salvato = {"domande_uscite": ids, "altre_domande": corpo.altre_domande.strip(),
               "voto": (corpo.voto or "").strip() or None, **risultato, "salvato_il": clock.adesso()}
    with db.transazione(conn):
        conn.execute("UPDATE eventi SET esito = ? WHERE id = ?", (json.dumps(salvato, ensure_ascii=False), evento_id))
        schede.segna_da_aggiornare(conn, e["materia_id"])
    s.lavoro.sveglia("sfondo")
    return risultato

