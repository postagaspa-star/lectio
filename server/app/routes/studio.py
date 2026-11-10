"""Schemi, "Non ho capito", ripasso lampo e verifica simulata (contratto 18-21)."""

import json
import re
import sqlite3

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, Response

from .. import chiarimenti, clock, lampo, pdf, riassunti, schema_ripasso, simulata
from ..auth import richiede_token
from ..db import get_conn, transazione
from ..errori import conflitto, non_trovato, non_valido, tetto
from ..schemi import ChiarimentoIn, LampoIn, RiassuntoIn, SchemaIn, SchemaStimaIn, SimulataIn, SimulataPatch, SimulataStimaIn
from ..servizi import Servizi, servizi
from ..spesa import arrotonda_su
from .lezioni import _materiale
from .materie import trova_materia

router = APIRouter(dependencies=[Depends(richiede_token)])


def _nome_file(testo: str) -> str:
    nome = re.sub(r"[^A-Za-z0-9 _-]", "", (testo or "").encode("ascii", "ignore").decode()).strip().replace(" ", "-")
    return (nome or "lectio")[:60]


def _pdf(contenuto: bytes, titolo: str) -> Response:
    return Response(contenuto, media_type="application/pdf",
                    headers={"Content-Disposition": f'attachment; filename="{_nome_file(titolo)}.pdf"', "Cache-Control": "no-store"})


def _prepara(conn: sqlite3.Connection, materia_id: int, materiali: list[int] | None, evento_id: int | None, argomento: str) -> tuple[list[int] | None, int, str]:
    trova_materia(conn, materia_id)
    evento = None
    if evento_id is not None:
        evento = conn.execute("SELECT * FROM eventi WHERE id = ? AND materia_id = ?", (evento_id, materia_id)).fetchone()
        if evento is None:
            raise non_valido("La verifica indicata non è di questa materia.", campo="evento_id")
    ristretti = list(dict.fromkeys(materiali)) if materiali is not None else None
    appunti, verifiche, quanti = _materiale(conn, materia_id, ristretti)
    if quanti == 0:
        raise conflitto("Questa materia non ha ancora materiale letto: aggiungi il libro, gli appunti o le verifiche.")
    return ristretti, appunti + verifiche, " ".join(argomento.split()) or (evento["argomento"] if evento else "")


def _decidi(conn, s: Servizi, costo: float, quale: str) -> dict:
    st = s.settings
    return s.cassa.decisione(conn, costo, prova=st.modalita_prova, modello_ocr=st.modello_ocr, modello_piano=st.modello_piano, quale=quale)


# --- schemi --------------------------------------------------------------------------------


def _stima_schema(conn, s: Servizi, caratteri: int, livello: str, indicazioni: str | None) -> dict:
    st = s.settings
    immagini = schema_ripasso.quante_immagini(livello, indicazioni)
    decisione = _decidi(conn, s, s.cassa.stima_schema(caratteri, livello, immagini, st.modello_piano, st.modello_scrittura), "Questo schema")
    _, massimo = schema_ripasso.numero_sezioni(caratteri, livello)
    decisione["minuti_preparazione"] = int(round(2 + massimo * 0.4 + immagini * 0.3))
    decisione["immagini"] = immagini
    return decisione


def trova_schema(conn: sqlite3.Connection, schema_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM schemi WHERE id = ?", (schema_id,)).fetchone()
    if r is None:
        raise non_trovato("Schema non trovato.")
    return r


def _stima_riassunto(s: Servizi, r: sqlite3.Row) -> float:
    if s.settings.modalita_prova:
        return 0.0
    testo = riassunti.testo_schema(json.loads(r["contenuto"] or "{}"))
    return arrotonda_su(s.cassa.stima_riassunto(len(testo), s.settings.modello_piano, s.settings.modello_scrittura))


def _schema_completo(conn: sqlite3.Connection, s: Servizi, schema_id: int) -> dict:
    r = trova_schema(conn, schema_id)
    uscita = schema_ripasso.per_api(r, completo=True)
    uscita["riassunti"] = None
    if r["stato"] == "pronto":
        uscita["riassunti"] = {"stima_eur": _stima_riassunto(s, r), "fatti": [
            {"id": x["id"], "forma": x["forma"], "stato": x["stato"]}
            for x in conn.execute("SELECT id, forma, stato FROM riassunti WHERE schema_id = ? ORDER BY id", (schema_id,))]}
    return uscita


@router.post("/schemi/stima")
def stima_schema(corpo: SchemaStimaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    _, caratteri, _ = _prepara(conn, corpo.materia_id, corpo.materiali, None, corpo.argomento)
    return _stima_schema(conn, s, caratteri, corpo.livello, corpo.indicazioni)


@router.post("/schemi", status_code=201)
def crea_schema(corpo: SchemaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    ristretti, caratteri, argomento = _prepara(conn, corpo.materia_id, corpo.materiali, corpo.evento_id, corpo.argomento)
    if not argomento:
        raise non_valido("Scrivi l'argomento dello schema.", campo="argomento")
    st = _stima_schema(conn, s, caratteri, corpo.livello, corpo.indicazioni)
    if not st["consentita"]:
        raise tetto(st["motivo"])
    ora = clock.adesso()
    cur = conn.execute(
        "INSERT INTO schemi (materia_id, evento_id, argomento, forma, livello, indicazioni, materiali, stato, stima_eur, creato_il, in_coda_dal)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, 'in_coda', ?, ?, ?)",
        (corpo.materia_id, corpo.evento_id, argomento, corpo.forma, corpo.livello, corpo.indicazioni or "", json.dumps(ristretti or []), st["costo_eur"], ora, ora))
    s.lavoro.sveglia("studio")
    return _schema_completo(conn, s, cur.lastrowid)


@router.get("/schemi")
def elenco_schemi(materia_id: int | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    sql, parametri = "SELECT * FROM schemi", []
    if materia_id is not None:
        sql += " WHERE materia_id = ?"
        parametri.append(materia_id)
    return [schema_ripasso.per_api(r) for r in conn.execute(sql + " ORDER BY creato_il DESC, id DESC", parametri)]


@router.get("/schemi/{schema_id}")
def uno_schema(schema_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    return _schema_completo(conn, s, schema_id)


@router.post("/schemi/{schema_id}/annulla")
def annulla_schema(schema_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute("UPDATE schemi SET stato = 'annullato', fase = NULL, fatto = 0, totale = 0, frase = NULL WHERE id = ?"
                       " AND stato IN ('in_coda', 'in_preparazione')", (schema_id,))
    if cur.rowcount == 0:
        trova_schema(conn, schema_id)
        raise conflitto("Si può annullare solo uno schema in coda o in preparazione.")
    schema_ripasso.cancella_file(s.settings.dati_dir, schema_id)
    return _schema_completo(conn, s, schema_id)


@router.post("/schemi/{schema_id}/riprova")
def riprova_schema(schema_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute("UPDATE schemi SET stato = 'in_coda', errore = NULL, in_coda_dal = ? WHERE id = ? AND stato = 'errore'", (clock.adesso(), schema_id))
    if cur.rowcount == 0:
        trova_schema(conn, schema_id)
        raise conflitto("Si può riprovare solo uno schema che si è fermato per un errore.")
    s.lavoro.sveglia("studio")
    return _schema_completo(conn, s, schema_id)


@router.delete("/schemi/{schema_id}")
def cancella_schema(schema_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_schema(conn, schema_id)
    conn.execute("DELETE FROM schemi WHERE id = ?", (schema_id,))
    schema_ripasso.cancella_file(s.settings.dati_dir, schema_id)
    return {"ok": True}


@router.get("/schemi/{schema_id}/pdf")
def pdf_schema(schema_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_schema(conn, schema_id)
    if r["stato"] != "pronto":
        raise conflitto("Questo schema non è ancora pronto.")
    materia = trova_materia(conn, r["materia_id"])
    contenuto = pdf.schema_pdf(dict(r), json.loads(r["contenuto"]), dict(materia), schema_ripasso.cartella(s.settings.dati_dir, schema_id) / "immagini")
    return _pdf(contenuto, r["titolo"] or r["argomento"])


@router.get("/schemi/{schema_id}/immagini/{n}")
def foto_schema(schema_id: int, n: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_schema(conn, schema_id)
    percorso = schema_ripasso.cartella(s.settings.dati_dir, schema_id) / "immagini" / f"{n}.jpg"
    if not percorso.is_file():
        raise non_trovato("Foto non trovata.")
    return FileResponse(percorso, media_type="image/jpeg", headers={"cache-control": "private, max-age=0, must-revalidate"})


@router.post("/schemi/{schema_id}/immagini/{n}/cambia")
def cambia_foto(schema_id: int, n: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_schema(conn, schema_id)
    if r["stato"] != "pronto":
        raise conflitto("Questo schema non è ancora pronto.")
    if not schema_ripasso.cambia_foto(s.ctx, r, n):
        raise non_trovato("Non ho trovato un'altra foto sicura per questo.")
    return _schema_completo(conn, s, schema_id)


@router.delete("/schemi/{schema_id}/immagini/{n}")
def togli_foto(schema_id: int, n: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_schema(conn, schema_id)
    if not schema_ripasso.togli_foto(s.ctx, conn, r, n):
        raise non_trovato("Foto non trovata.")
    return _schema_completo(conn, s, schema_id)


# --- riassunti ------------------------------------------------------------------------------


def trova_riassunto(conn: sqlite3.Connection, riassunto_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM riassunti WHERE id = ?", (riassunto_id,)).fetchone()
    if r is None:
        raise non_trovato("Riassunto non trovato.")
    return r


@router.post("/schemi/{schema_id}/riassunti", status_code=201)
def crea_riassunto(schema_id: int, corpo: RiassuntoIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_schema(conn, schema_id)
    if r["stato"] != "pronto":
        raise conflitto("Questo schema non è ancora pronto.")
    esiste = conn.execute("SELECT * FROM riassunti WHERE schema_id = ? AND forma = ?", (schema_id, corpo.forma)).fetchone()
    if esiste is not None and esiste["stato"] != "errore":
        return riassunti.per_api(esiste)
    st = _decidi(conn, s, _stima_riassunto(s, r), "Questo riassunto")
    if not st["consentita"]:
        raise tetto(st["motivo"])
    ora = clock.adesso()
    if esiste is not None:
        conn.execute("UPDATE riassunti SET stato = 'in_coda', errore = NULL, stima_eur = ?, in_coda_dal = ? WHERE id = ?", (st["costo_eur"], ora, esiste["id"]))
        ident = esiste["id"]
    else:
        ident = conn.execute("INSERT INTO riassunti (schema_id, materia_id, forma, stato, stima_eur, creato_il, in_coda_dal) VALUES (?, ?, ?, 'in_coda', ?, ?, ?)",
                             (schema_id, r["materia_id"], corpo.forma, st["costo_eur"], ora, ora)).lastrowid
    s.lavoro.sveglia("studio")
    return riassunti.per_api(trova_riassunto(conn, ident))


@router.get("/schemi/{schema_id}/riassunti")
def elenco_riassunti(schema_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_schema(conn, schema_id)
    return [riassunti.per_api(r) for r in conn.execute("SELECT * FROM riassunti WHERE schema_id = ? ORDER BY id", (schema_id,))]


@router.get("/riassunti/{riassunto_id}")
def un_riassunto(riassunto_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return riassunti.per_api(trova_riassunto(conn, riassunto_id))


@router.post("/riassunti/{riassunto_id}/riprova")
def riprova_riassunto(riassunto_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute("UPDATE riassunti SET stato = 'in_coda', errore = NULL, in_coda_dal = ? WHERE id = ? AND stato = 'errore'", (clock.adesso(), riassunto_id))
    if cur.rowcount == 0:
        trova_riassunto(conn, riassunto_id)
        raise conflitto("Si può riprovare solo un riassunto che si è fermato per un errore.")
    s.lavoro.sveglia("studio")
    return riassunti.per_api(trova_riassunto(conn, riassunto_id))


@router.delete("/riassunti/{riassunto_id}")
def cancella_riassunto(riassunto_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_riassunto(conn, riassunto_id)
    conn.execute("DELETE FROM riassunti WHERE id = ?", (riassunto_id,))
    return {"ok": True}


@router.get("/riassunti/{riassunto_id}/pdf")
def pdf_riassunto(riassunto_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    r = trova_riassunto(conn, riassunto_id)
    if r["stato"] != "pronto":
        raise conflitto("Questo riassunto non è ancora pronto.")
    materia = trova_materia(conn, r["materia_id"])
    return _pdf(pdf.riassunto_pdf(dict(r), json.loads(r["contenuto"]), dict(materia), riassunti.NOME_FORMA[r["forma"]]), r["titolo"] or "Riassunto")


# --- non ho capito -------------------------------------------------------------------------


@router.post("/chiarimenti")
def chiarimento(corpo: ChiarimentoIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    materia = trova_materia(conn, corpo.materia_id)
    passaggio = corpo.passaggio
    if corpo.lezione_id is not None:
        lezione = conn.execute("SELECT * FROM lezioni WHERE id = ? AND materia_id = ?", (corpo.lezione_id, corpo.materia_id)).fetchone()
        if lezione is None:
            raise non_trovato("Lezione non trovata.")
        passaggio = passaggio or chiarimenti.passaggio_lezione(lezione, corpo.posizione_s or 0.0)
    if corpo.schema_id is not None:
        r = conn.execute("SELECT id FROM schemi WHERE id = ? AND materia_id = ?", (corpo.schema_id, corpo.materia_id)).fetchone()
        if r is None:
            raise non_trovato("Schema non trovato.")
    if not passaggio.strip() and not corpo.domanda.strip():
        raise non_valido("Scrivi cosa non hai capito.", campo="domanda")
    return chiarimenti.chiedi(s.ctx, conn, materia, passaggio=passaggio, domanda=corpo.domanda, lezione_id=corpo.lezione_id, schema_id=corpo.schema_id)


@router.get("/chiarimenti")
def elenco_chiarimenti(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    return chiarimenti.elenco(conn, materia_id)


# --- ripasso lampo -------------------------------------------------------------------------


@router.get("/lampo")
def carte(materia_id: int | None = None, limite: int = lampo.LIMITE_PREDEFINITO, conn: sqlite3.Connection = Depends(get_conn)):
    if materia_id is not None:
        trova_materia(conn, materia_id)
    return lampo.elenco(conn, materia_id, max(1, min(50, limite)))


def _carta(conn: sqlite3.Connection, carta_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM carte WHERE id = ?", (carta_id,)).fetchone()
    if r is None:
        raise non_trovato("Carta non trovata.")
    return r


@router.post("/lampo/{carta_id}")
def rispondi_carta(carta_id: int, corpo: LampoIn, conn: sqlite3.Connection = Depends(get_conn)):
    return lampo.rispondi(conn, _carta(conn, carta_id), corpo.esito)


@router.delete("/lampo/{carta_id}")
def togli_carta(carta_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    _carta(conn, carta_id)
    conn.execute("DELETE FROM carte WHERE id = ?", (carta_id,))
    return {"ok": True}


# --- verifica simulata ---------------------------------------------------------------------


def trova_simulata(conn: sqlite3.Connection, simulata_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM simulate WHERE id = ?", (simulata_id,)).fetchone()
    if r is None:
        raise non_trovato("Verifica non trovata.")
    return r


def _stima_simulata(conn, s: Servizi, caratteri: int, minuti: int) -> dict:
    st = s.settings
    decisione = _decidi(conn, s, s.cassa.stima_simulata(caratteri, minuti, st.modello_piano, st.modello_scrittura), "Questa verifica")
    decisione["minuti_preparazione"] = 2
    return decisione


@router.post("/simulate/stima")
def stima_simulata(corpo: SimulataStimaIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    _, caratteri, _ = _prepara(conn, corpo.materia_id, corpo.materiali, None, corpo.argomento)
    return _stima_simulata(conn, s, caratteri, corpo.minuti)


@router.post("/simulate", status_code=201)
def crea_simulata(corpo: SimulataIn, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    ristretti, caratteri, argomento = _prepara(conn, corpo.materia_id, corpo.materiali, corpo.evento_id, corpo.argomento)
    if not argomento:
        raise non_valido("Scrivi l'argomento della verifica.", campo="argomento")
    st = _stima_simulata(conn, s, caratteri, corpo.minuti)
    if not st["consentita"]:
        raise tetto(st["motivo"])
    ora = clock.adesso()
    cur = conn.execute(
        "INSERT INTO simulate (materia_id, evento_id, argomento, minuti, materiali, indicazioni, stato, stima_eur, creata_il, in_coda_dal)"
        " VALUES (?, ?, ?, ?, ?, ?, 'in_coda', ?, ?, ?)",
        (corpo.materia_id, corpo.evento_id, argomento, corpo.minuti, json.dumps(ristretti or []), corpo.indicazioni or "", st["costo_eur"], ora, ora))
    s.lavoro.sveglia("studio")
    return simulata.per_api(trova_simulata(conn, cur.lastrowid))


@router.get("/simulate")
def elenco_simulate(materia_id: int | None = None, conn: sqlite3.Connection = Depends(get_conn)):
    sql, parametri = "SELECT * FROM simulate", []
    if materia_id is not None:
        sql += " WHERE materia_id = ?"
        parametri.append(materia_id)
    return [simulata.per_api(r, completa=False) for r in conn.execute(sql + " ORDER BY creata_il DESC, id DESC", parametri)]


@router.get("/simulate/{simulata_id}")
def una_simulata(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return simulata.per_api(trova_simulata(conn, simulata_id))


@router.patch("/simulate/{simulata_id}")
def salva_simulata(simulata_id: int, corpo: SimulataPatch, conn: sqlite3.Connection = Depends(get_conn)):
    with transazione(conn):
        r = trova_simulata(conn, simulata_id)
        if r["stato"] not in ("pronta", "in_corso"):
            raise conflitto("Questa verifica non si può più modificare.")
        if corpo.risposte is not None:
            risposte = {**json.loads(r["risposte"] or "{}"), **corpo.risposte}
            conn.execute("UPDATE simulate SET risposte = ? WHERE id = ?", (json.dumps(risposte, ensure_ascii=False), simulata_id))
        if corpo.iniziata and r["stato"] == "pronta":
            conn.execute("UPDATE simulate SET stato = 'in_corso', iniziata_il = ? WHERE id = ?", (clock.adesso(), simulata_id))
    return simulata.per_api(trova_simulata(conn, simulata_id))


@router.post("/simulate/{simulata_id}/consegna")
def consegna(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute("UPDATE simulate SET stato = 'in_correzione', frase = 'Correggo la verifica', consegnata_il = ?, errore = NULL WHERE id = ?"
                       " AND stato IN ('pronta', 'in_corso')", (clock.adesso(), simulata_id))
    if cur.rowcount == 0:
        trova_simulata(conn, simulata_id)
        raise conflitto("Questa verifica è già stata consegnata.")
    s.lavoro.sveglia("studio")
    return simulata.per_api(trova_simulata(conn, simulata_id))


@router.post("/simulate/{simulata_id}/annulla")
def annulla_simulata(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    cur = conn.execute("UPDATE simulate SET stato = 'annullata', fase = NULL, frase = NULL WHERE id = ? AND stato IN ('in_coda', 'in_preparazione')", (simulata_id,))
    if cur.rowcount == 0:
        trova_simulata(conn, simulata_id)
        raise conflitto("Si può annullare solo una verifica in coda o in preparazione.")
    simulata.cancella_file(s.settings.dati_dir, simulata_id)
    return simulata.per_api(trova_simulata(conn, simulata_id))


@router.post("/simulate/{simulata_id}/riprova")
def riprova_simulata(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    """Riparte dalla correzione se era già stata consegnata, altrimenti dalla preparazione."""
    r = trova_simulata(conn, simulata_id)
    if r["stato"] != "errore":
        raise conflitto("Si può riprovare solo una verifica che si è fermata per un errore.")
    stato = "in_correzione" if r["consegnata_il"] else "in_coda"
    conn.execute("UPDATE simulate SET stato = ?, errore = NULL, in_coda_dal = ? WHERE id = ?", (stato, clock.adesso(), simulata_id))
    s.lavoro.sveglia("studio")
    return simulata.per_api(trova_simulata(conn, simulata_id))


@router.delete("/simulate/{simulata_id}")
def cancella_simulata(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    trova_simulata(conn, simulata_id)
    conn.execute("DELETE FROM simulate WHERE id = ?", (simulata_id,))
    simulata.cancella_file(s.settings.dati_dir, simulata_id)
    return {"ok": True}


@router.get("/simulate/{simulata_id}/pdf")
def pdf_simulata(simulata_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    r = trova_simulata(conn, simulata_id)
    domande = json.loads(r["domande"] or "[]")
    if not domande:
        raise conflitto("Questa verifica non è ancora pronta.")
    materia = trova_materia(conn, r["materia_id"])
    return _pdf(pdf.simulata_pdf(dict(r), domande, dict(materia)), r["titolo"] or r["argomento"])
