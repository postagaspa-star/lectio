"""Voci, stime e lezioni (contratto, sezioni 8, 9, 15.1, 15.2 e 15.4)."""

import json
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header
from fastapi.responses import FileResponse, Response

from .. import clock, db, forme, ripasso
from ..auth import richiede_token
from ..db import get_conn
from ..errori import ErroreApi, conflitto, non_trovato, non_valido, tetto
from ..schemi import AscoltoIn, LezioneIn, LezionePatch, RispostaIn, StimaIn
from ..servizi import Servizi, servizi
from ..spesa import CARATTERI_VERIFICHE_MASSIMI
from .materie import trova_materia

router = APIRouter(dependencies=[Depends(richiede_token)])

TIPI_APPUNTI = ("appunti", "libro", "altro")


def trova_lezione(conn: sqlite3.Connection, lezione_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM lezioni WHERE id = ?", (lezione_id,)).fetchone()
    if r is None:
        raise non_trovato("Lezione non trovata.")
    return r


def _impostazioni(conn: sqlite3.Connection, s: Servizi) -> dict:
    return db.impostazioni(conn, s.settings.voce_mistral)


def _scegli_voce(conn: sqlite3.Connection, s: Servizi, voce: str | None):
    if voce:
        scelta = s.catalogo.risolvi(voce)
        if scelta is None:
            raise non_valido("Questa voce adesso non si può usare: scegline un'altra.", campo="voce")
        return scelta
    voce_id = s.catalogo.ripiego(_impostazioni(conn, s)["voce_predefinita"])
    scelta = s.catalogo.risolvi(voce_id)
    if scelta is None:
        raise ErroreApi(503, "Nessuna voce è pronta: manca la chiave Mistral o quella di Azure.")
    return scelta


def _materiale(conn: sqlite3.Connection, materia_id: int, ristretti: list[int] | None) -> tuple[int, int, int]:
    """Caratteri di appunti e verifiche che la lezione userà (contratto 15.1), e quanti file in tutto.
    `ristretti` None = tutto il materiale letto della materia; una lista = solo quello."""
    if ristretti is not None and not ristretti:
        raise non_valido("Scegli almeno un appunto o una verifica.", campo="materiali")
    righe = conn.execute("SELECT id, tipo, titolo, stato, length(testo) AS n FROM materiali WHERE materia_id = ?",
                         (materia_id,)).fetchall()
    per_id = {r["id"]: r for r in righe}
    if ristretti:
        if any(i not in per_id for i in ristretti):
            raise non_valido("Alcuni materiali indicati non sono di questa materia.", campo="materiali")
        for i in ristretti:
            if per_id[i]["stato"] != "pronto":
                raise conflitto(f"«{per_id[i]['titolo']}» non è ancora stato letto: aspetta o toglilo dalla lista.")
    appunti = [r for r in righe if r["stato"] == "pronto" and r["tipo"] in TIPI_APPUNTI
               and (not ristretti or r["id"] in ristretti)]
    verifiche = [r for r in righe if r["stato"] == "pronto" and r["tipo"] not in TIPI_APPUNTI
                 and (not ristretti or r["id"] in ristretti)]
    caratteri_appunti = sum(r["n"] or 0 for r in appunti)
    caratteri_verifiche = min(CARATTERI_VERIFICHE_MASSIMI, sum(r["n"] or 0 for r in verifiche))
    return caratteri_appunti, caratteri_verifiche, len(appunti) + len(verifiche)


def _stima(conn: sqlite3.Connection, s: Servizi, durata: int, tipo_voce: str, appunti: int, verifiche: int) -> dict:
    st = s.settings
    return s.cassa.stima_lezione(conn, durata_min=durata, tipo_voce=tipo_voce, caratteri_appunti=appunti,
                                 caratteri_verifiche=verifiche, prova=st.modalita_prova,
                                 modello_piano=st.modello_piano, modello_scrittura=st.modello_scrittura,
                                 modello_voce=st.modello_voce, modello_ocr=st.modello_ocr)


# --- voci e stima -------------------------------------------------------------------------


@router.get("/voci")
def voci(s: Servizi = Depends(servizi)):
    return s.catalogo.elenco()


@router.post("/lezioni/stima")
def stima(corpo: StimaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_materia(conn, corpo.materia_id)
    scelta = _scegli_voce(conn, s, corpo.voce)
    appunti, verifiche, _ = _materiale(conn, corpo.materia_id, corpo.materiali)
    return _stima(conn, s, corpo.durata_min, scelta.tipo, appunti, verifiche)


# --- lezioni ------------------------------------------------------------------------------


@router.post("/lezioni", status_code=201)
def crea(corpo: LezioneIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_materia(conn, corpo.materia_id)
    imp = _impostazioni(conn, s)
    evento = None
    if corpo.evento_id is not None:
        evento = conn.execute("SELECT * FROM eventi WHERE id = ? AND materia_id = ?",
                              (corpo.evento_id, corpo.materia_id)).fetchone()
        if evento is None:
            raise non_valido("La verifica indicata non è di questa materia.", campo="evento_id")
    argomento = " ".join(corpo.argomento.split()) or (evento["argomento"] if evento else "")
    if not argomento:
        raise non_valido("Scrivi l'argomento della lezione.", campo="argomento")
    durata = corpo.durata_min or int(imp["durata_predefinita"])
    scelta = _scegli_voce(conn, s, corpo.voce)
    ristretti = list(dict.fromkeys(corpo.materiali)) if corpo.materiali is not None else None
    appunti, verifiche, quanti = _materiale(conn, corpo.materia_id, ristretti)
    if quanti == 0:
        raise conflitto("Questa materia non ha ancora materiale letto: aggiungi appunti o verifiche prima di "
                        "preparare una lezione.")
    st = _stima(conn, s, durata, scelta.tipo, appunti, verifiche)
    if not st["consentita"]:
        raise tetto(st["motivo"])
    saltabili = corpo.domande_saltabili if corpo.domande_saltabili is not None else bool(imp["domande_saltabili"])
    ora = clock.adesso()
    # La stima resta con la lezione: finché è in coda conta nelle stime delle lezioni dopo.
    cur = conn.execute(
        "INSERT INTO lezioni (materia_id, evento_id, argomento, materiali, durata_min, voce, domande_saltabili, stato,"
        " creata_il, in_coda_dal, stima_eur, stima_caratteri) VALUES (?, ?, ?, ?, ?, ?, ?, 'in_coda', ?, ?, ?, ?)",
        (corpo.materia_id, corpo.evento_id, argomento, json.dumps(ristretti or []), durata, scelta.id, int(saltabili),
         ora, ora, st["costo_eur"], st["caratteri_stimati"] if scelta.tipo == "gratis" else 0),
    )
    s.lavoro.sveglia("lezioni")
    return forme.lezione(conn, trova_lezione(conn, cur.lastrowid))


@router.get("/lezioni")
def elenco(materia_id: int | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    sql, parametri = "SELECT * FROM lezioni", []
    if materia_id is not None:
        sql += " WHERE materia_id = ?"
        parametri.append(materia_id)
    return [forme.lezione(conn, r) for r in conn.execute(sql + " ORDER BY creata_il DESC, id DESC", parametri)]


@router.get("/lezioni/{lezione_id}")
def una(lezione_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.lezione(conn, trova_lezione(conn, lezione_id), completa=True)


@router.patch("/lezioni/{lezione_id}")
def cambia(lezione_id: int, corpo: LezionePatch, conn: sqlite3.Connection = Depends(get_conn)):
    trova_lezione(conn, lezione_id)
    if corpo.domande_saltabili is not None:
        conn.execute("UPDATE lezioni SET domande_saltabili = ? WHERE id = ?", (int(corpo.domande_saltabili), lezione_id))
    return forme.lezione(conn, trova_lezione(conn, lezione_id))


@router.get("/lezioni/{lezione_id}/audio")
def audio(lezione_id: int, if_none_match: Annotated[str | None, Header()] = None,
          conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_lezione(conn, lezione_id)
    percorso = Path(s.settings.dati_dir) / "lezioni" / str(lezione_id) / "lezione.mp3"
    if r["stato"] != "pronta" or not percorso.is_file():
        raise non_trovato("L'audio di questa lezione non è ancora pronto.")
    etag = f'"{r["audio_sha256"]}"'
    intestazioni = {"etag": etag, "cache-control": "private, max-age=0, must-revalidate"}
    if if_none_match and etag in [x.strip() for x in if_none_match.split(",")]:
        return Response(status_code=304, headers=intestazioni)
    return FileResponse(percorso, media_type="audio/mpeg", headers=intestazioni)


def _istante(testo: str) -> datetime:
    try:
        dt = datetime.fromisoformat(testo.strip().replace("Z", "+00:00"))
    except ValueError:
        raise non_valido("L'ora dell'aggiornamento non va bene.", campo="aggiornato_il") from None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=clock.FUSO)
    return dt.astimezone(timezone.utc)


@router.put("/lezioni/{lezione_id}/ascolto")
def ascolto(lezione_id: int, corpo: AscoltoIn, conn: sqlite3.Connection = Depends(get_conn)):
    """Vince il più recente: un aggiornamento con un'ora più vecchia di quella salvata si ignora
    (per esempio il telefono che manda la posizione dopo essere tornato in rete)."""
    with db.transazione(conn):
        r = trova_lezione(conn, lezione_id)
        if r["stato"] != "pronta":
            raise conflitto("Questa lezione non è ancora pronta da ascoltare.")
        ora = clock.now()
        quando = min(_istante(corpo.aggiornato_il), ora) if corpo.aggiornato_il else ora
        salvato = clock.da_iso(r["ascolto_il"]) if r["ascolto_il"] else None
        if salvato is None or quando >= salvato:
            conn.execute(
                "UPDATE lezioni SET ascolto_posizione = ?, ascolto_finita = ?, ascolto_il = ? WHERE id = ?",
                (round(corpo.posizione_s, 1), int(corpo.finita), clock.iso(quando), lezione_id),
            )
    return forme.lezione(conn, trova_lezione(conn, lezione_id))["ascolto"]


@router.post("/lezioni/{lezione_id}/annulla")
def annulla(lezione_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute(
        "UPDATE lezioni SET stato = 'annullata', fase = NULL, fatto = 0, totale = 0 WHERE id = ?"
        " AND stato IN ('in_coda', 'in_preparazione')", (lezione_id,))
    if cur.rowcount == 0:
        trova_lezione(conn, lezione_id)
        raise conflitto("Si può annullare solo una lezione in coda o in preparazione.")
    # Le spese già fatte restano nel conto; i pezzi a metà non servono più.
    shutil.rmtree(Path(s.settings.dati_dir) / "lezioni" / str(lezione_id), ignore_errors=True)
    s.lavoro.sveglia("lezioni")
    return forme.lezione(conn, trova_lezione(conn, lezione_id))


@router.post("/lezioni/{lezione_id}/riprova")
def riprova(lezione_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute(
        "UPDATE lezioni SET stato = 'in_coda', errore = NULL, in_coda_dal = ? WHERE id = ? AND stato = 'errore'",
        (clock.adesso(), lezione_id))
    if cur.rowcount == 0:
        trova_lezione(conn, lezione_id)
        raise conflitto("Si può riprovare solo una lezione che si è fermata per un errore.")
    s.lavoro.sveglia("lezioni")
    return forme.lezione(conn, trova_lezione(conn, lezione_id))


@router.delete("/lezioni/{lezione_id}")
def cancella(lezione_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_lezione(conn, lezione_id)
    conn.execute("DELETE FROM lezioni WHERE id = ?", (lezione_id,))
    shutil.rmtree(Path(s.settings.dati_dir) / "lezioni" / str(lezione_id), ignore_errors=True)
    s.lavoro.sveglia("lezioni")
    return {"ok": True}


# --- risposte scritte (15.2) ---------------------------------------------------------------


@router.post("/lezioni/{lezione_id}/domande/{domanda_id}/risposta", status_code=201)
def rispondi(lezione_id: int, domanda_id: int, corpo: RispostaIn, conn: sqlite3.Connection = Depends(get_conn),
             s: Servizi = Depends(servizi)):
    lezione = trova_lezione(conn, lezione_id)
    domanda = conn.execute("SELECT * FROM domande WHERE id = ? AND lezione_id = ?", (domanda_id, lezione_id)).fetchone()
    if domanda is None:
        raise non_trovato("Domanda non trovata in questa lezione.")
    if not corpo.saltata and not corpo.testo.strip():
        raise non_valido("Scrivi la risposta, oppure salta la domanda.", campo="testo")
    # Le domande saltabili o obbligatorie sono una regola delle app (15.4): qui "saltata" vale sempre.
    return ripasso.rispondi(s.ctx, conn, lezione, domanda, corpo.testo, corpo.saltata)
