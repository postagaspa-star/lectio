"""Risposte scritte alle domande e "Da ripassare" (contratto 15.2).

Ogni risposta si corregge con il modello piccolo (o il correttore finto in prova) e si
salva; vale l'ultima. Il flusso dell'ascolto non si blocca mai: tetto finito o
fornitore giù danno l'esito `non_corretta` con una frase, mai un errore."""

import json
import logging
import sqlite3

from . import ai, clock, prompt
from . import scheda as schede
from .rete import ErroreFornitore
from .spesa import TettoRaggiunto, VoceGratisFinita

log = logging.getLogger("uvicorn.error")

ESITI_CORRETTORE = ("giusta", "parziale", "sbagliata")
ESITI_DA_RIPASSARE = ("parziale", "sbagliata", "saltata")
PUNTEGGI = {"giusta": 1.0, "parziale": 0.5, "sbagliata": 0.0}
FRASE_SENZA_SOLDI = "Hai finito i soldi del mese: confronta tu con la risposta qui sotto"
FRASE_NON_CORRETTA = "Non sono riuscito a correggerla adesso: confronta tu con la risposta qui sotto"
FRASE_SALTATA = "L'hai saltata: rileggi la risposta qui sotto e riprovaci la prossima volta."

_ULTIMA = "r.id = (SELECT MAX(r2.id) FROM risposte r2 WHERE r2.domanda_id = r.domanda_id)"


def per_api(r: sqlite3.Row, risposta_attesa: str) -> dict:
    return {
        "id": r["id"],
        "domanda_id": r["domanda_id"],
        "esito": r["esito"],
        "punteggio": r["punteggio"],
        "commento": r["commento"],
        "mancava": json.loads(r["mancava"] or "[]"),
        "risposta_attesa": risposta_attesa,
        "costo_eur": round(float(r["costo_eur"] or 0), 4),
        "quando": clock.per_utente(r["quando"]),
    }


def mie_risposte(conn: sqlite3.Connection, lezione_id: int) -> dict[int, dict]:
    """L'ultima risposta di ogni domanda della lezione, nella forma di `mia_risposta`."""
    return {
        r["domanda_id"]: {"testo": r["testo"], "esito": r["esito"], "commento": r["commento"],
                          "mancava": json.loads(r["mancava"] or "[]")}
        for r in conn.execute(f"SELECT r.* FROM risposte r WHERE r.lezione_id = ? AND {_ULTIMA}", (lezione_id,))
    }


def da_ripassare(conn: sqlite3.Connection, materia_id: int, limite: int = 50) -> list[dict]:
    segni = ",".join("?" * len(ESITI_DA_RIPASSARE))
    return [
        {"domanda_id": r["domanda_id"], "lezione_id": r["lezione_id"], "lezione": r["argomento"],
         "testo": r["domanda"], "esito": r["esito"], "commento": r["commento"],
         "quando": clock.per_utente(r["quando"]), "mancava": json.loads(r["mancava"] or "[]")}
        for r in conn.execute(
            "SELECT r.*, d.testo AS domanda, l.argomento FROM risposte r JOIN domande d ON d.id = r.domanda_id"
            f" JOIN lezioni l ON l.id = r.lezione_id WHERE l.materia_id = ? AND {_ULTIMA} AND r.esito IN ({segni})"
            " ORDER BY r.id DESC LIMIT ?",
            (materia_id, *ESITI_DA_RIPASSARE, limite),
        )
    ]


def conta_da_ripassare(conn: sqlite3.Connection, materia_id: int) -> int:
    segni = ",".join("?" * len(ESITI_DA_RIPASSARE))
    return conn.execute(
        f"SELECT COUNT(*) FROM risposte r JOIN lezioni l ON l.id = r.lezione_id WHERE l.materia_id = ? AND {_ULTIMA}"
        f" AND r.esito IN ({segni})", (materia_id, *ESITI_DA_RIPASSARE),
    ).fetchone()[0]


# --------------------------------------------------------------------------- correttore

def valida(o: dict) -> list[str]:
    errori = []
    if str(o.get("esito", "")).strip().lower() not in ESITI_CORRETTORE:
        errori.append('"esito" deve essere "giusta", "parziale" o "sbagliata"')
    if not isinstance(o.get("commento"), str) or not o["commento"].strip():
        errori.append('manca "commento"')
    if "mancava" in o and not isinstance(o["mancava"], (list, str)):
        errori.append('"mancava" deve essere una lista')
    return errori


def normalizza(o: dict) -> dict:
    esito = str(o["esito"]).strip().lower()
    try:
        punteggio = min(1.0, max(0.0, float(o.get("punteggio"))))
    except (TypeError, ValueError):
        punteggio = PUNTEGGI[esito]
    mancava = o.get("mancava") or []
    mancava = [mancava] if isinstance(mancava, str) else mancava
    mancava = [" ".join(str(m).split())[:80] for m in mancava if str(m).strip()][:3]
    commento = " ".join(o["commento"].split()).replace("!", ".")[:400]
    return {"esito": esito, "punteggio": round(punteggio, 2), "commento": commento, "mancava": mancava}


def _testo_capitolo(lezione: sqlite3.Row, capitolo: int) -> str:
    for c in json.loads(lezione["testo"] or "[]"):
        if c.get("capitolo") == capitolo:
            return "\n".join(c.get("paragrafi") or [])
    return ""


def rispondi(ctx, conn: sqlite3.Connection, lezione: sqlite3.Row, domanda: sqlite3.Row, testo: str,
             saltata: bool) -> dict:
    """Corregge e salva la risposta. Non solleva mai per soldi o fornitori."""
    testo = (testo or "").strip()
    costo = 0.0
    if saltata:
        esito = {"esito": "saltata", "punteggio": 0.0, "commento": FRASE_SALTATA, "mancava": []}
    else:
        costi: list[float] = []
        try:
            oggetto = ai.chiedi_json(
                cassa=ctx.cassa, fornitore=ctx.testo, compito="correttore",
                messaggi=prompt.messaggi_correttore(domanda["testo"], domanda["risposta"],
                                                    _testo_capitolo(lezione, domanda["capitolo"]),
                                                    schede.per_lezione(conn, lezione["materia_id"]), testo),
                modello=ctx.settings.modello_piano, valida=valida, max_token=500, cosa="Correzione di una risposta",
                dati={"attesa": domanda["risposta"], "risposta": testo}, lezione_id=lezione["id"],
                materia_id=lezione["materia_id"], costi=costi,
            )
            esito = normalizza(oggetto)
        except (TettoRaggiunto, VoceGratisFinita):
            esito = {"esito": "non_corretta", "punteggio": None, "commento": FRASE_SENZA_SOLDI, "mancava": []}
        except (ErroreFornitore, ai.RispostaNonUsabile):
            esito = {"esito": "non_corretta", "punteggio": None, "commento": FRASE_NON_CORRETTA, "mancava": []}
        except Exception:
            log.exception("correzione della domanda %s: errore inatteso", domanda["id"])
            esito = {"esito": "non_corretta", "punteggio": None, "commento": FRASE_NON_CORRETTA, "mancava": []}
        costo = sum(costi)
    cur = conn.execute(
        "INSERT INTO risposte (domanda_id, lezione_id, testo, esito, punteggio, commento, mancava, costo_eur, quando)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (domanda["id"], lezione["id"], testo, esito["esito"], esito["punteggio"], esito["commento"],
         json.dumps(esito["mancava"], ensure_ascii=False), costo, clock.adesso()),
    )
    riga = conn.execute("SELECT * FROM risposte WHERE id = ?", (cur.lastrowid,)).fetchone()
    return per_api(riga, domanda["risposta"])

