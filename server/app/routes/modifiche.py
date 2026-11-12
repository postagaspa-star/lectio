"""Modifiche dopo: con l'AI, a mano, e la versione di prima (contratto 28)."""

import json
import sqlite3
from pathlib import Path

from fastapi import APIRouter, Depends

from .. import clock, forme, modifiche, riassunti
from ..auth import richiede_token
from ..db import get_conn, transazione
from ..errori import conflitto, non_trovato, non_valido, tetto
from ..lezione import ErroreLezione
from ..schemi import ModificaIn, TestoRiassuntoIn, TestoSchemaIn
from ..servizi import Servizi, servizi
from .studio import _schema_completo, riassunto_api, simulata_api

router = APIRouter(dependencies=[Depends(richiede_token)])

NON_TROVATO = {"lezione": "Lezione non trovata.", "schema": "Schema non trovato.", "riassunto": "Riassunto non trovato.",
               "simulata": "Verifica non trovata."}


def _oggetto(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> sqlite3.Row:
    r = modifiche.trova_oggetto(conn, tipo, oggetto_id)
    if r is None:
        raise non_trovato(NON_TROVATO[tipo])
    return r


def _per_api(conn: sqlite3.Connection, s: Servizi, tipo: str, oggetto_id: int) -> dict:
    r = _oggetto(conn, tipo, oggetto_id)
    if tipo == "lezione":
        return forme.lezione(conn, r, completa=True)
    if tipo == "schema":
        return _schema_completo(conn, s, oggetto_id)
    if tipo == "riassunto":
        return riassunto_api(conn, r)
    return simulata_api(conn, r)


def _file_mancanti(s: Servizi, tipo: str, oggetto_id: int) -> bool:
    """Le cose fatte con versioni molto vecchie di Lectio non hanno i file che servono per riscriverle."""
    dati = Path(s.settings.dati_dir)
    if tipo == "lezione":
        c = dati / "lezioni" / str(oggetto_id)
        return not all((c / f).is_file() for f in ("lettura.json", "piano.json", "capitoli.json", "lezione.mp3")) or \
            not (c / "scrittura").is_dir()
    if tipo == "schema":
        return not (dati / "schemi" / str(oggetto_id) / "lettura.json").is_file()
    if tipo == "simulata":
        return not (dati / "simulate" / str(oggetto_id) / "lettura.json").is_file()
    return False


def _controlla(conn: sqlite3.Connection, s: Servizi, corpo: ModificaIn) -> sqlite3.Row:
    r = _oggetto(conn, corpo.tipo, corpo.id)
    motivo = modifiche.perche_no(conn, corpo.tipo, r)
    if motivo:
        raise conflitto(motivo)
    numeri = {p["n"] for p in modifiche.parti_di(corpo.tipo, r)}
    if any(n not in numeri for n in corpo.parti):
        raise non_valido("Le parti scelte non ci sono: riapri e scegli di nuovo.", campo="parti")
    if _file_mancanti(s, corpo.tipo, corpo.id):
        raise conflitto(modifiche.FRASE_VECCHIA)
    if corpo.tipo == "lezione" and s.ctx.catalogo.risolvi(r["voce"]) is None:
        raise conflitto("La voce di questa lezione adesso non si può usare: riprova tra poco.")
    return r


def trova_modifica(conn: sqlite3.Connection, modifica_id: int) -> sqlite3.Row:
    m = conn.execute("SELECT * FROM modifiche WHERE id = ?", (modifica_id,)).fetchone()
    if m is None:
        raise non_trovato("Modifica non trovata.")
    return m


def _sveglia(s: Servizi, tipo: str) -> None:
    s.lavoro.sveglia("lezioni" if tipo == "lezione" else "studio")


# --- 28.1 modifica con l'AI --------------------------------------------------------------------------------------

@router.post("/modifiche/stima")
def stima(corpo: ModificaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = _controlla(conn, s, corpo)
    return modifiche.stima(s, conn, corpo.tipo, r, list(dict.fromkeys(corpo.parti)))


@router.post("/modifiche", status_code=201)
def crea(corpo: ModificaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    parti = sorted(set(corpo.parti))
    with transazione(conn):
        r = _controlla(conn, s, corpo)
        st = modifiche.stima(s, conn, corpo.tipo, r, parti)
        if not st["consentita"]:
            raise tetto(st["motivo"])
        modifiche.chiudi_ferme(conn, s.settings.dati_dir, corpo.tipo, corpo.id)
        ora = clock.adesso()
        ident = conn.execute(
            "INSERT INTO modifiche (tipo, oggetto_id, materia_id, richiesta, parti, stato, stima_eur, creata_il, in_coda_dal)"
            " VALUES (?, ?, ?, ?, ?, 'in_coda', ?, ?, ?)",
            (corpo.tipo, corpo.id, r["materia_id"], corpo.richiesta, json.dumps(parti), st["costo_eur"], ora, ora)).lastrowid
    _sveglia(s, corpo.tipo)
    return modifiche.per_api(trova_modifica(conn, ident))


@router.get("/modifiche")
def elenco(attive: int = 0, conn: sqlite3.Connection = Depends(get_conn)):
    sql = "SELECT * FROM modifiche"
    if attive:
        sql += " WHERE stato IN ('in_coda', 'in_preparazione')"
    return [modifiche.per_api(m) for m in conn.execute(sql + " ORDER BY id DESC LIMIT 100")]


@router.get("/modifiche/{modifica_id}")
def una(modifica_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return modifiche.per_api(trova_modifica(conn, modifica_id))


@router.post("/modifiche/{modifica_id}/annulla")
def annulla(modifica_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    with transazione(conn):
        m = trova_modifica(conn, modifica_id)
        if m["stato"] not in ("in_coda", "in_preparazione", "errore"):
            raise conflitto("Questa modifica è già finita.")
        conn.execute("UPDATE modifiche SET stato = 'annullata', fase = NULL, frase = NULL WHERE id = ?", (modifica_id,))
    if m["stato"] != "in_preparazione":  # quella in lavorazione la pulisce chi ci sta lavorando, appena se ne accorge
        modifiche.cancella_file(s.settings.dati_dir, modifica_id)
    return modifiche.per_api(trova_modifica(conn, modifica_id))


@router.post("/modifiche/{modifica_id}/riprova")
def riprova(modifica_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    with transazione(conn):
        m = trova_modifica(conn, modifica_id)
        if m["stato"] != "errore":
            raise conflitto("Si può riprovare solo una modifica che si è fermata per un errore.")
        r = _oggetto(conn, m["tipo"], m["oggetto_id"])
        motivo = modifiche.perche_no(conn, m["tipo"], r)
        if motivo:
            raise conflitto(motivo)
        conn.execute("UPDATE modifiche SET stato = 'in_coda', errore = NULL, in_coda_dal = ? WHERE id = ?",
                     (clock.adesso(), modifica_id))
    _sveglia(s, m["tipo"])
    return modifiche.per_api(trova_modifica(conn, modifica_id))


# --- 28.2 versione di prima --------------------------------------------------------------------------------------

def _scambia(conn: sqlite3.Connection, s: Servizi, tipo: str, oggetto_id: int) -> dict:
    with transazione(conn):
        r = _oggetto(conn, tipo, oggetto_id)
        motivo = modifiche.perche_no(conn, tipo, r)
        if motivo:
            raise conflitto(motivo)
        try:
            modifiche.scambia(conn, s.settings.dati_dir, tipo, r)
        except ErroreLezione as e:
            raise conflitto(e.frase) from None
        modifiche.chiudi_ferme(conn, s.settings.dati_dir, tipo, oggetto_id)
    return _per_api(conn, s, tipo, oggetto_id)


@router.post("/lezioni/{oggetto_id}/scambia-versione")
def scambia_lezione(oggetto_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _scambia(conn, s, "lezione", oggetto_id)


@router.post("/schemi/{oggetto_id}/scambia-versione")
def scambia_schema(oggetto_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _scambia(conn, s, "schema", oggetto_id)


@router.post("/riassunti/{oggetto_id}/scambia-versione")
def scambia_riassunto(oggetto_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _scambia(conn, s, "riassunto", oggetto_id)


@router.post("/simulate/{oggetto_id}/scambia-versione")
def scambia_simulata(oggetto_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _scambia(conn, s, "simulata", oggetto_id)


# --- 28.4 correzione a mano --------------------------------------------------------------------------------------

def _prima_di_correggere(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> sqlite3.Row:
    r = _oggetto(conn, tipo, oggetto_id)
    if r["stato"] != "pronto":
        raise conflitto("Si può correggere solo quando è pronto.")
    if modifiche.attiva(conn, tipo, oggetto_id):
        raise conflitto("C'è una modifica in corso: aspetta che finisca.")
    return r


@router.put("/schemi/{oggetto_id}/testo")
def correggi_schema(oggetto_id: int, corpo: TestoSchemaIn, conn: sqlite3.Connection = Depends(get_conn),
                    s: Servizi = Depends(servizi)):
    with transazione(conn):
        r = _prima_di_correggere(conn, "schema", oggetto_id)
        attuale = json.loads(r["contenuto"] or "{}")
        try:
            nuovo = modifiche.pulisci_schema(attuale, corpo.contenuto)
        except modifiche.FormaSbagliata:
            raise non_valido("Il testo corretto non ha la forma giusta: riapri e riprova.", campo="contenuto") from None
        titolo = " ".join(corpo.titolo.split()) or r["titolo"]
        if nuovo != attuale or titolo != r["titolo"]:
            modifiche.metti_da_parte(conn, s.settings.dati_dir, "schema", r, "prima", "a_mano", None)
            conn.execute("UPDATE schemi SET titolo = ?, contenuto = ?, cambiato_il = ? WHERE id = ?",
                         (titolo, json.dumps(nuovo, ensure_ascii=False), clock.adesso(), oggetto_id))
            modifiche.chiudi_ferme(conn, s.settings.dati_dir, "schema", oggetto_id)
    return _schema_completo(conn, s, oggetto_id)


@router.put("/riassunti/{oggetto_id}/testo")
def correggi_riassunto(oggetto_id: int, corpo: TestoRiassuntoIn, conn: sqlite3.Connection = Depends(get_conn),
                       s: Servizi = Depends(servizi)):
    with transazione(conn):
        r = _prima_di_correggere(conn, "riassunto", oggetto_id)
        attuale = json.loads(r["contenuto"] or "{}")
        try:
            parti = modifiche.pulisci_riassunto(attuale, corpo.contenuto)
        except modifiche.FormaSbagliata:
            raise non_valido("Il testo corretto non ha la forma giusta: riapri e riprova.", campo="contenuto") from None
        titolo = " ".join(corpo.titolo.split()) or r["titolo"]
        if parti != attuale.get("parti") or titolo != r["titolo"]:
            modifiche.metti_da_parte(conn, s.settings.dati_dir, "riassunto", r, "prima", "a_mano", None)
            conn.execute("UPDATE riassunti SET titolo = ?, contenuto = ?, parole = ? WHERE id = ?",
                         (titolo, json.dumps({"parti": parti}, ensure_ascii=False), riassunti.conta_parole(parti), oggetto_id))
            modifiche.chiudi_ferme(conn, s.settings.dati_dir, "riassunto", oggetto_id)
    return riassunto_api(conn, trova_riassunto_riga(conn, oggetto_id))


def trova_riassunto_riga(conn: sqlite3.Connection, oggetto_id: int) -> sqlite3.Row:
    return _oggetto(conn, "riassunto", oggetto_id)
