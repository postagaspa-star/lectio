"""Materie, scheda del prof e Da ripassare (contratto, sezioni 4, 5, 15.2 e 15.3)."""

import shutil
import sqlite3
from pathlib import Path

from fastapi import APIRouter, Depends

from .. import clock, db, forme, ripasso
from .. import scheda as schede
from ..auth import richiede_token
from ..db import get_conn
from ..errori import conflitto, non_trovato, non_valido
from ..schemi import ConfermaIn, MateriaIn, MateriaPatch, SchedaPut
from ..servizi import Servizi, servizi

router = APIRouter(dependencies=[Depends(richiede_token)])

NOME_GIA_USATO = "Esiste già una materia con questo nome"


def trova_materia(conn: sqlite3.Connection, materia_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM materie WHERE id = ?", (materia_id,)).fetchone()
    if r is None:
        raise non_trovato("Materia non trovata.")
    return r


def _nome_libero(conn: sqlite3.Connection, nome: str, escluso: int | None = None) -> None:
    """Nome unico senza distinguere maiuscole (anche con le lettere accentate)."""
    for r in conn.execute("SELECT id, nome FROM materie"):
        if r["id"] != escluso and r["nome"].casefold() == nome.casefold():
            raise conflitto(NOME_GIA_USATO)


def _pulito(testo: str) -> str:
    return " ".join(testo.split())


@router.get("/materie")
def elenco(conn: sqlite3.Connection = Depends(get_conn)):
    righe = conn.execute("SELECT * FROM materie").fetchall()
    return [forme.materia(conn, r) for r in sorted(righe, key=lambda r: r["nome"].casefold())]


@router.post("/materie", status_code=201)
def crea(corpo: MateriaIn, conn: sqlite3.Connection = Depends(get_conn)):
    nome = _pulito(corpo.nome)
    if not nome:
        raise non_valido("Il nome non può essere vuoto né più lungo di 40 caratteri.", campo="nome")
    with db.transazione(conn):
        _nome_libero(conn, nome)
        try:
            cur = conn.execute("INSERT INTO materie (nome, prof, colore, creata_il) VALUES (?, ?, ?, ?)",
                               (nome, _pulito(corpo.prof), corpo.colore or db.colore_libero(conn), clock.adesso()))
        except sqlite3.IntegrityError:
            raise conflitto(NOME_GIA_USATO) from None
        schede.crea(conn, cur.lastrowid)
    return forme.materia(conn, trova_materia(conn, cur.lastrowid))


@router.get("/materie/{materia_id}")
def una(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.materia(conn, trova_materia(conn, materia_id))


@router.patch("/materie/{materia_id}")
def cambia(materia_id: int, corpo: MateriaPatch, conn: sqlite3.Connection = Depends(get_conn)):
    cambi = corpo.model_dump(exclude_none=True)
    with db.transazione(conn):
        trova_materia(conn, materia_id)
        if "nome" in cambi:
            cambi["nome"] = _pulito(cambi["nome"])
            if not cambi["nome"]:
                raise non_valido("Il nome non può essere vuoto né più lungo di 40 caratteri.", campo="nome")
            _nome_libero(conn, cambi["nome"], escluso=materia_id)
        if "prof" in cambi:
            cambi["prof"] = _pulito(cambi["prof"])
        for campo, valore in cambi.items():
            try:
                conn.execute(f"UPDATE materie SET {campo} = ? WHERE id = ?", (valore, materia_id))
            except sqlite3.IntegrityError:
                raise conflitto(NOME_GIA_USATO) from None
    return forme.materia(conn, trova_materia(conn, materia_id))


@router.delete("/materie/{materia_id}")
def cancella(materia_id: int, corpo: ConfermaIn | None = None, conn: sqlite3.Connection = Depends(get_conn),
             s: Servizi = Depends(servizi)):
    trova_materia(conn, materia_id)
    if corpo is None or corpo.conferma is not True:
        raise non_valido("Per cancellare la materia serve la conferma: si cancellano anche materiale, scheda, "
                         "verifiche in arrivo e lezioni.", campo="conferma")
    lezioni = [r["id"] for r in conn.execute("SELECT id FROM lezioni WHERE materia_id = ?", (materia_id,))]
    with db.transazione(conn):
        conn.execute("DELETE FROM materie WHERE id = ?", (materia_id,))
    # I file dopo il database: se il server cade qui, restano solo file orfani, mai righe senza file.
    dati = Path(s.settings.dati_dir)
    shutil.rmtree(dati / "materiale" / str(materia_id), ignore_errors=True)
    for lezione_id in lezioni:
        shutil.rmtree(dati / "lezioni" / str(lezione_id), ignore_errors=True)
    return {"ok": True}


# --- scheda del prof --------------------------------------------------------------------


@router.get("/materie/{materia_id}/scheda")
def leggi_scheda(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    return schede.per_api(conn, materia_id)


@router.put("/materie/{materia_id}/scheda")
def scrivi_scheda(materia_id: int, corpo: SchedaPut, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    cambi = {k: v for k, v in corpo.model_dump(exclude_unset=True).items() if v is not None}
    schede.per_api(conn, materia_id)  # crea la scheda se manca
    schede.modifica(conn, materia_id, cambi)
    return schede.per_api(conn, materia_id)


@router.post("/materie/{materia_id}/scheda/aggiorna", status_code=202)
def aggiorna_scheda(materia_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_materia(conn, materia_id)
    if not schede.ha_fonti(conn, materia_id):
        raise conflitto("Per aggiornare la scheda serve almeno una verifica o un'interrogazione letta.")
    with db.transazione(conn):
        schede.segna_da_aggiornare(conn, materia_id)
    s.lavoro.libera("scheda", materia_id)
    s.lavoro.sveglia("sfondo")
    return schede.per_api(conn, materia_id)


# --- da ripassare (15.2) -----------------------------------------------------------------


@router.get("/materie/{materia_id}/da_ripassare")
def da_ripassare(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    return ripasso.da_ripassare(conn, materia_id)
