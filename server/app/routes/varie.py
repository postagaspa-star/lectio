"""Oggi, novità, spesa, testo per Gemini e impostazioni (contratto, sezioni 8, 10-13, 15)."""

import os
import sqlite3
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends

from .. import clock, db, forme
from ..auth import richiede_token
from ..db import get_conn
from ..errori import ErroreApi, non_valido
from ..schemi import ImpostazioniPatch
from ..servizi import Servizi, servizi

router = APIRouter(dependencies=[Depends(richiede_token)])

CHIAVI_IMPOSTAZIONI = ("tetto_eur", "voce_predefinita", "durata_predefinita", "fermati_alle_domande", "velocita",
                       "nome", "risposte_scritte", "domande_saltabili")


# --- oggi -----------------------------------------------------------------------------------


def _voce_evento(conn: sqlite3.Connection, e: sqlite3.Row, oggi: date, con_lezione: bool = False) -> dict:
    materia = conn.execute("SELECT * FROM materie WHERE id = ?", (e["materia_id"],)).fetchone()
    voce = {"evento": forme.evento(conn, e), "materia": forme.materia_breve(materia)}
    if con_lezione:
        lezione_id = voce["evento"]["lezione_id"]
        r = conn.execute("SELECT * FROM lezioni WHERE id = ?", (lezione_id,)).fetchone() if lezione_id else None
        voce["lezione"] = forme.lezione(conn, r) if r else None
    voce["tra_giorni"] = clock.giorni_tra(oggi, date.fromisoformat(e["data"]))
    return voce


@router.get("/oggi")
def oggi(conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    giorno = clock.oggi()
    futuri = conn.execute("SELECT * FROM eventi WHERE data >= ? ORDER BY data, id LIMIT 5",
                          (giorno.isoformat(),)).fetchall()
    prossima = _voce_evento(conn, futuri[0], giorno, con_lezione=True) if futuri else None
    piu_avanti = [_voce_evento(conn, e, giorno) for e in futuri[1:5]]
    passati = conn.execute(
        "SELECT * FROM eventi WHERE data < ? AND data >= ? AND esito IS NULL ORDER BY data DESC, id DESC",
        (giorno.isoformat(), (giorno - timedelta(days=30)).isoformat()),
    ).fetchall()
    da_raccontare = []
    for e in passati:
        if conn.execute("SELECT 1 FROM lezioni WHERE evento_id = ? AND stato = 'pronta' LIMIT 1", (e["id"],)).fetchone():
            voce = _voce_evento(conn, e, giorno)
            da_raccontare.append({"evento": voce["evento"], "materia": voce["materia"]})
    r = conn.execute(
        "SELECT * FROM lezioni WHERE stato = 'pronta' AND ascolto_finita = 0 AND ascolto_posizione > 0"
        " AND ascolto_il IS NOT NULL ORDER BY ascolto_il DESC LIMIT 1").fetchone()
    spesa = s.cassa.riassunto(conn)
    return {
        "oggi": giorno.isoformat(),
        "prossima": prossima,
        "riprendi": forme.lezione(conn, r) if r else None,
        "da_raccontare": da_raccontare,
        "piu_avanti": piu_avanti,
        "spesa": {k: spesa[k] for k in ("speso_eur", "tetto_eur", "percentuale", "si_azzera_il", "avviso")},
        "modalita_prova": s.settings.modalita_prova,
    }


# --- novità ---------------------------------------------------------------------------------


@router.get("/novita")
def novita(dopo_id: int = 0, conn: sqlite3.Connection = Depends(get_conn)):
    if dopo_id < 0:
        raise non_valido("Il numero da cui leggere le novità non va bene.", campo="dopo_id")
    righe = conn.execute("SELECT * FROM novita WHERE id > ? ORDER BY id DESC LIMIT 100", (dopo_id,)).fetchall()
    return [
        {"id": n["id"], "tipo": n["tipo"], "testo": n["testo"], "lezione_id": n["lezione_id"],
         "materia_id": n["materia_id"], "materiale_id": n["materiale_id"], "quando": clock.per_utente(n["quando"])}
        for n in reversed(righe)
    ]


# --- spesa ----------------------------------------------------------------------------------


def _numero(x: float):
    return int(x) if float(x).is_integer() else x


@router.get("/spesa")
def spesa(conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = s.cassa.riassunto(conn)
    return {
        "mese": r["mese"],
        "speso_eur": r["speso_eur"],
        "tetto_eur": r["tetto_eur"],
        "percentuale": r["percentuale"],
        "si_azzera_il": r["si_azzera_il"],
        "voce_gratis": {"caratteri_usati": s.cassa.caratteri_gratis_usati(conn),
                        "caratteri_mese": s.cassa.prezzi.azure_gratis},
        # Le singole spese sono piccole (un pezzo di voce costa decimi di centesimo): 4 decimali.
        "voci": [
            {"quando": clock.per_utente(v["quando"]), "cosa": v["cosa"], "fornitore": v["fornitore"],
             "quantita": _numero(v["quantita"]), "unita": v["unita"], "costo_eur": round(v["costo_eur"], 4),
             "lezione_id": v["lezione_id"]}
            for v in conn.execute("SELECT * FROM spese ORDER BY id DESC LIMIT 50")
        ],
    }


# --- testo per Gemini -----------------------------------------------------------------------


@router.get("/gemini")
def gemini(s: Servizi = Depends(servizi)):
    try:
        with open(s.settings.gemini_path, encoding="utf-8") as f:
            testo = f.read().strip()
        quando = datetime.fromtimestamp(os.path.getmtime(s.settings.gemini_path), tz=timezone.utc)
    except OSError:
        raise ErroreApi(500, "Il testo per Gemini non si trova sul server.") from None
    return {"testo": testo, "aggiornato_il": clock.per_utente(clock.iso(quando))}


# --- impostazioni ---------------------------------------------------------------------------


def _uscita(conn: sqlite3.Connection, s: Servizi) -> dict:
    imp = db.impostazioni(conn, s.settings.voce_mistral)
    return {k: imp[k] for k in CHIAVI_IMPOSTAZIONI}


@router.get("/impostazioni")
def leggi(conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _uscita(conn, s)


@router.patch("/impostazioni")
def cambia(corpo: ImpostazioniPatch, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cambi = corpo.model_dump(exclude_none=True)
    if "voce_predefinita" in cambi:
        v = cambi["voce_predefinita"].strip()
        prefisso, _, nome = v.partition(":")
        if not ((prefisso == "mistral" and nome) or (prefisso == "azure" and nome.startswith("it-IT-"))
                or v == "prova:espeak"):
            raise non_valido("La voce predefinita non va bene.", campo="voce_predefinita")
        cambi["voce_predefinita"] = v
    if "nome" in cambi:
        cambi["nome"] = " ".join(cambi["nome"].split())
    if "tetto_eur" in cambi:
        cambi["tetto_eur"] = round(cambi["tetto_eur"], 2)
    if "velocita" in cambi:
        cambi["velocita"] = round(cambi["velocita"], 2)
    with db.transazione(conn):
        for chiave, valore in cambi.items():
            db.salva_impostazione(conn, chiave, valore)
    return _uscita(conn, s)
