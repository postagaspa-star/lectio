"""Cartelle dentro la materia (contratto, sezione 27): un livello solo, solo per il materiale."""

import sqlite3

from fastapi import APIRouter, Depends

from .. import cartelle, clock, db
from ..auth import richiede_token
from ..db import get_conn
from ..errori import conflitto, non_trovato, non_valido
from ..schemi import CartellaIn, EliminaCartellaIn, SpostaIn
from ..servizi import Servizi, servizi
from .materiale import metti_in_cartella, togli_file, togli_materiali
from .materie import trova_materia

router = APIRouter(dependencies=[Depends(richiede_token)])

NOME_GIA_USATO = "C'è già una cartella con questo nome in questa materia."
NOME_NON_VALIDO = "Il nome della cartella non può essere vuoto né più lungo di 40 caratteri."


def trova_cartella(conn: sqlite3.Connection, cartella_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM cartelle WHERE id = ?", (cartella_id,)).fetchone()
    if r is None:
        raise non_trovato("Cartella non trovata.")
    return r


def _nome(conn: sqlite3.Connection, materia_id: int, nome: str, escluso: int | None = None) -> str:
    """Nome unico nella materia senza distinguere maiuscole (anche con le lettere accentate)."""
    nome = cartelle.nome_pulito(nome)
    if not nome or len(nome) > cartelle.NOME_MASSIMO:
        raise non_valido(NOME_NON_VALIDO, campo="nome")
    for r in conn.execute("SELECT id, nome FROM cartelle WHERE materia_id = ?", (materia_id,)):
        if r["id"] != escluso and r["nome"].casefold() == nome.casefold():
            raise conflitto(NOME_GIA_USATO)
    return nome


@router.get("/materie/{materia_id}/cartelle")
def elenco(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    return cartelle.elenco(conn, materia_id)


@router.post("/materie/{materia_id}/cartelle", status_code=201)
def crea(materia_id: int, corpo: CartellaIn, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    with db.transazione(conn):
        nome = _nome(conn, materia_id, corpo.nome)
        try:
            cur = conn.execute("INSERT INTO cartelle (materia_id, nome, creata_il) VALUES (?, ?, ?)",
                               (materia_id, nome, clock.adesso()))
        except sqlite3.IntegrityError:
            raise conflitto(NOME_GIA_USATO) from None
    return cartelle.forma(conn, trova_cartella(conn, cur.lastrowid))


@router.patch("/cartelle/{cartella_id}")
def rinomina(cartella_id: int, corpo: CartellaIn, conn: sqlite3.Connection = Depends(get_conn)):
    with db.transazione(conn):
        r = trova_cartella(conn, cartella_id)
        nome = _nome(conn, r["materia_id"], corpo.nome, escluso=cartella_id)
        try:
            conn.execute("UPDATE cartelle SET nome = ? WHERE id = ?", (nome, cartella_id))
        except sqlite3.IntegrityError:
            raise conflitto(NOME_GIA_USATO) from None
    return cartelle.forma(conn, trova_cartella(conn, cartella_id))


@router.delete("/cartelle/{cartella_id}")
def cancella(cartella_id: int, corpo: EliminaCartellaIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
             s: Servizi = Depends(servizi)):
    """Con `con_i_file: false` i file restano nella materia, fuori dalle cartelle; con `true` si cancellano."""
    trova_cartella(conn, cartella_id)
    if corpo is None or corpo.conferma is not True:
        raise non_valido("Per eliminare la cartella serve la conferma.", campo="conferma")
    percorsi: list[str] = []
    with db.transazione(conn):
        dentro = conn.execute("SELECT * FROM materiali WHERE cartella_id = ?", (cartella_id,)).fetchall()
        if corpo.con_i_file:
            percorsi = togli_materiali(conn, dentro)
        else:
            # Fuori dalle cartelle per scelta di Andrea: Lectio non li rimette da nessuna parte.
            conn.execute("UPDATE materiali SET cartella_id = NULL, cartella_da = 'utente', cartella_il = ?"
                         " WHERE cartella_id = ?", (clock.adesso(), cartella_id))
        conn.execute("DELETE FROM cartelle WHERE id = ?", (cartella_id,))
    if corpo.con_i_file:
        s.lavoro.sveglia("sfondo")
        togli_file(s, percorsi)
    return {"ok": True, "file_eliminati": len(dentro) if corpo.con_i_file else 0}


@router.get("/cartelle/{cartella_id}/proposti")
def proposti(cartella_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    """Per "Metti dei file qui": il materiale fuori dalle cartelle che parla di questa (gratis, senza AI)."""
    r = trova_cartella(conn, cartella_id)
    return {"proposti": cartelle.confronto(conn, r["materia_id"]).proposti(cartella_id)}


@router.post("/materie/{materia_id}/sposta")
def sposta(materia_id: int, corpo: SpostaIn, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    with db.transazione(conn):
        n = metti_in_cartella(conn, materia_id, corpo.materiali, corpo.cartella_id)
    return {"spostati": n}
