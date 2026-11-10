"""Il ripasso lampo (contratto 20): carte domanda e risposta, che tornano dopo 1, 2, 4, 9, 21
giorni se le sai, e subito se non le sai. Ripassare non costa niente."""

import json
import sqlite3
from datetime import date, timedelta
from math import ceil

from . import clock

GIORNI = {1: 1, 2: 2, 3: 4, 4: 9, 5: 21}
SCATOLA_MASSIMA = 5
SECONDI_A_CARTA = 20  # lo stesso conto delle app, così Oggi e Materia dicono lo stesso tempo
LIMITE_PREDEFINITO = 12


def aggiungi(conn: sqlite3.Connection, materia_id: int, origine: str, chiave: str, domanda: str, risposta: str,
             fonti: list[dict] | None = None) -> bool:
    """Una carta nuova: oggi è già da fare. La stessa domanda non entra due volte."""
    domanda, risposta = " ".join((domanda or "").split()), " ".join((risposta or "").split())
    if not domanda or not risposta:
        return False
    cur = conn.execute(
        "INSERT OR IGNORE INTO carte (materia_id, origine, chiave, domanda, risposta, fonti, prossima, creata_il)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (materia_id, origine, chiave, domanda, risposta, json.dumps(fonti or [], ensure_ascii=False),
         clock.oggi().isoformat(), clock.adesso()))
    return cur.rowcount > 0


def _carta(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "materia_id": r["materia_id"], "materia": r["materia"], "colore": r["colore"],
            "domanda": r["domanda"], "risposta": r["risposta"], "fonti": json.loads(r["fonti"] or "[]"), "scatola": r["scatola"]}


def _filtro(materia_id: int | None) -> tuple[str, list]:
    return (" AND c.materia_id = ?", [materia_id]) if materia_id is not None else ("", [])


def da_fare(conn: sqlite3.Connection, materia_id: int | None = None, giorno: date | None = None) -> int:
    sql, parametri = _filtro(materia_id)
    return conn.execute(f"SELECT COUNT(*) FROM carte c WHERE c.prossima <= ?{sql}",
                        [(giorno or clock.oggi()).isoformat(), *parametri]).fetchone()[0]


def elenco(conn: sqlite3.Connection, materia_id: int | None, limite: int) -> dict:
    oggi = clock.oggi()
    sql, parametri = _filtro(materia_id)
    righe = conn.execute(
        "SELECT c.*, m.nome AS materia, m.colore FROM carte c JOIN materie m ON m.id = c.materia_id"
        f" WHERE c.prossima <= ?{sql} ORDER BY c.scatola, c.prossima, c.id LIMIT ?",
        [oggi.isoformat(), *parametri, limite]).fetchall()
    quante = da_fare(conn, materia_id)
    domani = da_fare(conn, materia_id, oggi + timedelta(days=1)) - quante
    return {"carte": [_carta(r) for r in righe], "da_fare": quante, "domani": domani}


def _prossima(conn: sqlite3.Connection, materia_id: int, scatola: int, oggi: date) -> date:
    """Una carta non deve saltare la verifica: se ce n'è una prima, torna il giorno prima."""
    giorno = oggi + timedelta(days=GIORNI[scatola])
    evento = conn.execute("SELECT MIN(data) AS d FROM eventi WHERE materia_id = ? AND data > ?",
                          (materia_id, oggi.isoformat())).fetchone()["d"]
    if evento:
        vigilia = date.fromisoformat(evento) - timedelta(days=1)
        if oggi < vigilia < giorno:
            return vigilia
    return giorno


def rispondi(conn: sqlite3.Connection, carta: sqlite3.Row, esito: str) -> dict:
    oggi = clock.oggi()
    if esito == "sapevo":
        scatola = min(SCATOLA_MASSIMA, carta["scatola"] + 1)
        prossima = _prossima(conn, carta["materia_id"], scatola, oggi)
    elif esito == "quasi":
        scatola = carta["scatola"]
        prossima = _prossima(conn, carta["materia_id"], scatola, oggi)
    else:
        scatola = 1
        prossima = oggi + timedelta(days=1)
    conn.execute("UPDATE carte SET scatola = ?, prossima = ? WHERE id = ?", (scatola, prossima.isoformat(), carta["id"]))
    return {"scatola": scatola, "prossima": prossima.isoformat(), "da_fare": da_fare(conn, carta["materia_id"])}


def per_oggi(conn: sqlite3.Connection) -> dict:
    quante = da_fare(conn)
    return {"da_fare": quante, "minuti": ceil(quante * SECONDI_A_CARTA / 60)}
