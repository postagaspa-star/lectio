"""La forma delle risposte delle API (contratto, sezioni 4, 6, 7, 9 e 15)."""

import json
import sqlite3

from . import clock, ripasso
from .lezione import FRASI_FASE
from .spesa import arrotonda


def centratura(conn: sqlite3.Connection, materia_id: int) -> dict | None:
    previste = totali = 0
    trovati = False
    for e in conn.execute("SELECT esito FROM eventi WHERE materia_id = ? AND esito IS NOT NULL", (materia_id,)):
        esito = json.loads(e["esito"])
        previste += int(esito.get("previste_uscite") or 0)
        totali += int(esito.get("totale_vere") or 0)
        trovati = True
    if not trovati:
        return None
    return {"previste_uscite": previste, "uscite_totali": totali,
            "percentuale": int(round(100 * previste / totali)) if totali else 0}


def materia(conn: sqlite3.Connection, r: sqlite3.Row) -> dict:
    c = conn.execute(
        "SELECT"
        " (SELECT COUNT(*) FROM materiali WHERE materia_id = :id AND stato = 'pronto' AND tipo = 'verifica') AS v,"
        " (SELECT COUNT(*) FROM materiali WHERE materia_id = :id AND stato = 'pronto' AND tipo = 'interrogazione') AS i,"
        " (SELECT COUNT(*) FROM materiali WHERE materia_id = :id AND stato = 'pronto'"
        "   AND tipo IN ('appunti', 'libro', 'altro')) AS a,"
        " (SELECT COUNT(*) FROM lezioni WHERE materia_id = :id AND stato = 'pronta') AS l",
        {"id": r["id"]},
    ).fetchone()
    return {
        "id": r["id"],
        "nome": r["nome"],
        "prof": r["prof"],
        "colore": r["colore"],
        "conteggi": {"verifiche": c["v"], "interrogazioni": c["i"], "appunti": c["a"], "lezioni": c["l"]},
        "centratura": centratura(conn, r["id"]),
        "da_ripassare": ripasso.conta_da_ripassare(conn, r["id"]),
    }


def _costo(conn: sqlite3.Connection, colonna: str, valore: int) -> float:
    riga = conn.execute(f"SELECT COALESCE(SUM(costo_eur), 0) AS s FROM spese WHERE {colonna} = ?", (valore,)).fetchone()
    return arrotonda(float(riga["s"]))


def materiale(conn: sqlite3.Connection, r: sqlite3.Row, con_testo: bool = False) -> dict:
    uscita = {
        "id": r["id"],
        "materia_id": r["materia_id"],
        "tipo": r["tipo"],
        "titolo": r["titolo"],
        "data": r["data"],
        "origine": r["origine"],
        "nome_file": r["nome_file"],
        "caratteri": len(r["testo"] or ""),
        "stato": r["stato"],
        "errore": r["errore"] if r["stato"] == "errore" else None,
        "avviso": r["avviso"],
        "costo_eur": _costo(conn, "materiale_id", r["id"]),
        "creato_il": clock.per_utente(r["creato_il"]),
    }
    if con_testo:
        uscita["testo"] = r["testo"]
    return uscita


def lezione_dell_evento(conn: sqlite3.Connection, evento_id: int) -> int | None:
    """L'ultima lezione pronta per questo evento; se nessuna è pronta, l'ultima (non annullata)."""
    r = conn.execute(
        "SELECT id FROM lezioni WHERE evento_id = ? AND stato != 'annullata'"
        " ORDER BY (stato = 'pronta') DESC, id DESC LIMIT 1", (evento_id,)
    ).fetchone()
    return r["id"] if r else None


def evento(conn: sqlite3.Connection, r: sqlite3.Row) -> dict:
    esito = json.loads(r["esito"]) if r["esito"] else None
    if esito:
        esito = {k: esito.get(k) for k in ("domande_uscite", "altre_domande", "voto", "previste_uscite",
                                            "totale_vere", "percentuale")}
    return {
        "id": r["id"],
        "materia_id": r["materia_id"],
        "tipo": r["tipo"],
        "argomento": r["argomento"],
        "data": r["data"],
        "esito": esito,
        "lezione_id": lezione_dell_evento(conn, r["id"]),
    }


def materia_breve(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "nome": r["nome"], "colore": r["colore"]}


def avanzamento(conn: sqlite3.Connection, r: sqlite3.Row) -> dict:
    stato, fase = r["stato"], r["fase"]
    if stato == "in_coda":
        davanti = conn.execute(
            "SELECT COUNT(*) FROM lezioni WHERE stato = 'in_preparazione' OR (stato = 'in_coda'"
            " AND (in_coda_dal < ? OR (in_coda_dal = ? AND id < ?)))",
            (r["in_coda_dal"], r["in_coda_dal"], r["id"]),
        ).fetchone()[0]
        frase = "In attesa: prima finisco l'altra lezione" if davanti else "Sta per partire"
        return {"fase": None, "fatto": 0, "totale": 0, "frase": frase}
    if stato in ("in_preparazione", "errore") and fase:
        frase = FRASI_FASE.get(fase, "")
        if stato == "in_preparazione" and r["totale"] and fase in ("scrittura", "voce"):
            frase += f" · {r['fatto']} di {r['totale']}"
        return {"fase": fase, "fatto": r["fatto"], "totale": r["totale"], "frase": frase}
    return {"fase": None, "fatto": 0, "totale": 0, "frase": None}


def lezione(conn: sqlite3.Connection, r: sqlite3.Row, completa: bool = False) -> dict:
    uscita = {
        "id": r["id"],
        "materia_id": r["materia_id"],
        "evento_id": r["evento_id"],
        "argomento": r["argomento"],
        "durata_min": r["durata_min"],
        "durata_s": r["durata_s"],
        "voce": r["voce"],
        "domande_saltabili": bool(r["domande_saltabili"]),
        "stato": r["stato"],
        "avanzamento": avanzamento(conn, r),
        "costo_eur": _costo(conn, "lezione_id", r["id"]),
        "creata_il": clock.per_utente(r["creata_il"]),
        "pronta_il": clock.per_utente(r["pronta_il"]),
        "errore": r["errore"] if r["stato"] == "errore" else None,
        "nota": r["nota"],
        "ascolto": {"posizione_s": round(float(r["ascolto_posizione"] or 0), 1), "finita": bool(r["ascolto_finita"]),
                    "aggiornato_il": clock.per_utente(r["ascolto_il"])},
        "audio": ({"byte": r["audio_byte"], "sha256": r["audio_sha256"]}
                  if r["stato"] == "pronta" and r["audio_sha256"] else None),
        "domande_totali": conn.execute("SELECT COUNT(*) FROM domande WHERE lezione_id = ?", (r["id"],)).fetchone()[0],
    }
    if completa:
        mie = ripasso.mie_risposte(conn, r["id"])
        uscita["capitoli"] = json.loads(r["capitoli"] or "[]")
        uscita["domande"] = [
            {"id": d["id"], "tipo": d["tipo"], "n": d["n"], "di": d["di"], "capitolo": d["capitolo"],
             "testo": d["testo"], "risposta": d["risposta"], "inizio_s": d["inizio_s"],
             "fine_domanda_s": d["fine_domanda_s"], "inizio_risposta_s": d["inizio_risposta_s"], "fine_s": d["fine_s"],
             "mia_risposta": mie.get(d["id"])}
            for d in conn.execute("SELECT * FROM domande WHERE lezione_id = ? ORDER BY n", (r["id"],))
        ]
        uscita["testo"] = json.loads(r["testo"] or "[]")
    return uscita
