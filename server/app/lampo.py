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


# Le carte nate da uno schema, da una lezione o da una verifica simulata fatti per una verifica che deve ancora
# esserci (Andrea, 23/11/2026: in Oggi le altre non servono; restano nella pagina della materia).
_PER_VERIFICHE = (
    " AND c.id IN ("
    "SELECT k.id FROM carte k JOIN schemi s ON k.origine = 'schema' AND k.chiave LIKE 'schema:' || s.id || ':%'"
    " JOIN eventi e ON e.id = s.evento_id AND e.data > ?"
    " UNION SELECT k.id FROM carte k JOIN domande d ON k.origine = 'lezione' AND k.chiave = 'lezione:' || d.id"
    " JOIN lezioni l ON l.id = d.lezione_id JOIN eventi e ON e.id = l.evento_id AND e.data > ?"
    " UNION SELECT k.id FROM carte k JOIN simulate x ON k.origine = 'simulata' AND k.chiave LIKE 'simulata:' || x.id || ':%'"
    " JOIN eventi e ON e.id = x.evento_id AND e.data > ?)"
)


def _filtro(materia_id: int | None, solo_verifiche: bool = False) -> tuple[str, list]:
    sql, parametri = (" AND c.materia_id = ?", [materia_id]) if materia_id is not None else ("", [])
    if solo_verifiche:
        fatte = clock.verifiche_fatte_fino().isoformat()
        sql, parametri = sql + _PER_VERIFICHE, [*parametri, fatte, fatte, fatte]
    return sql, parametri


def da_fare(conn: sqlite3.Connection, materia_id: int | None = None, giorno: date | None = None,
            solo_verifiche: bool = False) -> int:
    sql, parametri = _filtro(materia_id, solo_verifiche)
    return conn.execute(f"SELECT COUNT(*) FROM carte c WHERE c.prossima <= ?{sql}",
                        [(giorno or clock.oggi()).isoformat(), *parametri]).fetchone()[0]


def elenco(conn: sqlite3.Connection, materia_id: int | None, limite: int, solo_verifiche: bool = False) -> dict:
    oggi = clock.oggi()
    sql, parametri = _filtro(materia_id, solo_verifiche)
    righe = conn.execute(
        "SELECT c.*, m.nome AS materia, m.colore FROM carte c JOIN materie m ON m.id = c.materia_id"
        f" WHERE c.prossima <= ?{sql} ORDER BY c.scatola, c.prossima, c.id LIMIT ?",
        [oggi.isoformat(), *parametri, limite]).fetchall()
    quante = da_fare(conn, materia_id, solo_verifiche=solo_verifiche)
    domani = da_fare(conn, materia_id, oggi + timedelta(days=1), solo_verifiche) - quante
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
    """La scheda di Oggi: solo le carte per le verifiche in arrivo."""
    quante = da_fare(conn, solo_verifiche=True)
    return {"da_fare": quante, "minuti": ceil(quante * SECONDI_A_CARTA / 60)}
