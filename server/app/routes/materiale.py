"""Materiale (contratto, sezioni 6 e 15.1): si carica solo nella materia.

Un file si legge subito sul NAS (gratis) se ha testo; foto e pagine scansionate vanno
all'OCR a pagamento in sottofondo, dopo una stima che non deve sforare il tetto (contando
anche il lavoro già in corso). I limiti di dimensione li fa valere LimiteCorpo (main.py)
mentre il corpo arriva, anche senza Content-Length."""

import asyncio
import json
import os
import re
import sqlite3
from datetime import date
from pathlib import Path

from fastapi import APIRouter, Depends, Request
from pydantic import ValidationError
from starlette.datastructures import UploadFile

from .. import clock, db, forme, lettura, titoli
from .. import scheda as schede
from ..auth import richiede_token
from ..config import MASSIMO_BYTE_FILE, MASSIMO_CARATTERI
from ..db import get_conn
from ..errori import FRASI_CAMPI, ErroreApi, conflitto, non_trovato, non_valido, tetto
from ..schemi import MaterialePatch, MaterialeTestoIn
from ..servizi import Servizi, servizi
from .materie import trova_materia

router = APIRouter(dependencies=[Depends(richiede_token)])

TIPI = ("verifica", "interrogazione", "appunti", "libro", "altro")
DEL_PROF = ("verifica", "interrogazione")
TROPPO_GRANDE = "Il file è troppo grande: il massimo è 20 MB."
TROPPO_TESTO = "C'è troppo testo (più di 200.000 caratteri): dividilo in parti più piccole."


def trova_materiale(conn: sqlite3.Connection, materiale_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM materiali WHERE id = ?", (materiale_id,)).fetchone()
    if r is None:
        raise non_trovato("Materiale non trovato.")
    return r


def _stima_ocr(conn: sqlite3.Connection, s: Servizi, pagine: int) -> None:
    """Prima di accettare un file da leggere a pagamento: se sfora il tetto (contando anche le
    lezioni in coda e le altre foto da leggere) → 402. Tutto per eccesso."""
    if not s.ctx.ocr.a_pagamento or pagine <= 0:
        return
    _, ci_sta, frase = s.cassa.stima_ocr(conn, pagine=pagine, prova=s.settings.modalita_prova,
                                         modello_ocr=s.settings.modello_ocr, modello_piano=s.settings.modello_piano)
    if not ci_sta:
        raise tetto(frase)


def _valori(tipo, titolo, data_testo, testo: str | None, nome_file: str | None, origine: str):
    """Tipo, titolo e data, con i valori predefiniti (anche dalla testata dei testi di Gemini).
    Il titolo esce con chi l'ha scelto (contratto 16.3): "utente" o "file" (da riscrivere)."""
    testata = lettura.testata_gemini(testo) if testo else None
    if tipo is None:
        tipo = (lettura.tipo_da_gemini(testata) if testata else None) or "appunti"
    giorno = None
    if data_testo:
        giorno = date.fromisoformat(data_testo)
    elif testata:
        giorno = lettura.data_da_testo(testata.data)
    giorno = giorno or clock.oggi()
    scelto = " ".join((titolo or "").split())
    if scelto:
        titolo_da = "file" if titoli.titolo_automatico(scelto, nome_file) else "utente"
    else:
        # "Verifica 12 ottobre" dalla testata di Gemini va già bene; gli altri titoli li scrive Lectio.
        titolo_da = "utente" if testata else "file"
    titolo = scelto or lettura.titolo_predefinito(testo, nome_file, origine, tipo, giorno)
    return tipo, titolo[:120], giorno.isoformat(), titolo_da


def _inserisci(conn: sqlite3.Connection, materia_id: int, tipo: str, titolo: str, data: str, origine: str,
               nome_file: str | None, formato: str | None, pagine: int | None, testo: str, stato: str,
               titolo_da: str = "utente", cartella_id: int | None = None) -> int:
    """Contratto 27: con la cartella scelta da Andrea il file va lì; senza, Lectio la sceglie dopo averlo letto."""
    if cartella_id is not None:
        cartella_della_materia(conn, materia_id, cartella_id)
    adesso = clock.adesso()
    cur = conn.execute(
        "INSERT INTO materiali (materia_id, tipo, titolo, data, origine, nome_file, formato, pagine, testo, stato,"
        " creato_il, titolo_da, cartella_id, cartella_da, cartella_il) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (materia_id, tipo, titolo, data, origine, nome_file, formato, pagine, testo, stato, adesso, titolo_da,
         cartella_id, "utente" if cartella_id is not None else "attesa", adesso if cartella_id is not None else None),
    )
    return cur.lastrowid


def _primo_testo(letto) -> str | None:
    """Per il titolo di un PDF misto: il testo delle pagine già leggibili."""
    if letto.testi_pagine:
        return "\n\n".join(t for t in letto.testi_pagine if t) or None
    return None


def _nome_sicuro(nome: str) -> str:
    nome = os.path.basename(nome.replace("\\", "/"))
    nome = re.sub(r"[^\w.\- ]", "_", nome).strip(" .") or "file"
    return nome[:120]


def _campo_form(form, nome: str):
    v = form.get(nome)
    if v is None or isinstance(v, UploadFile):
        return None
    v = str(v).strip()
    return v or None


@router.post("/materie/{materia_id}/materiale", status_code=201)
async def aggiungi(materia_id: int, request: Request, conn: sqlite3.Connection = Depends(get_conn),
                   s: Servizi = Depends(servizi)):
    trova_materia(conn, materia_id)
    tipo_contenuto = request.headers.get("content-type", "")
    if tipo_contenuto.startswith("multipart/form-data"):
        return await _da_file(materia_id, request, conn, s)
    try:
        corpo = MaterialeTestoIn.model_validate(await request.json())
    except ValueError as e:
        campo = None
        if isinstance(e, ValidationError) and e.errors():
            campo = next((str(x) for x in e.errors()[0].get("loc", ()) if isinstance(x, str)), None)
        raise non_valido(FRASI_CAMPI.get(campo, "Alcuni dati della richiesta non vanno bene."), campo=campo) from None
    testo = lettura.pulisci(corpo.testo)
    if not testo:
        raise non_valido("Il testo non può essere vuoto.", campo="testo")
    if len(testo) > MASSIMO_CARATTERI:
        raise ErroreApi(413, TROPPO_TESTO)
    tipo, titolo, data, titolo_da = _valori(corpo.tipo, corpo.titolo, corpo.data, testo, None, "scritto")
    with db.transazione(conn):
        nuovo = _inserisci(conn, materia_id, tipo, titolo, data, "scritto", None, None, None, testo, "pronto",
                           titolo_da, corpo.cartella_id)
        if tipo in ("verifica", "interrogazione"):
            schede.segna_da_aggiornare(conn, materia_id)
    s.lavoro.sveglia("sfondo")
    return forme.materiale(conn, trova_materiale(conn, nuovo))


async def _da_file(materia_id: int, request: Request, conn: sqlite3.Connection, s: Servizi):
    try:
        lunghezza = int(request.headers.get("content-length") or 0)
    except ValueError:
        lunghezza = 0
    if lunghezza > MASSIMO_BYTE_FILE + 512 * 1024:
        raise ErroreApi(413, TROPPO_GRANDE)
    # Senza Content-Length il limite lo fa LimiteCorpo mentre i byte arrivano.
    form = await request.form(max_files=1, max_fields=12)
    try:
        file = form.get("file")
        if not isinstance(file, UploadFile):
            raise non_valido("Manca il file.", campo="file")
        contenuto = await file.read(MASSIMO_BYTE_FILE + 1)
        nome_file = _nome_sicuro(file.filename or "file")
    finally:
        await form.close()
    if len(contenuto) > MASSIMO_BYTE_FILE:
        raise ErroreApi(413, TROPPO_GRANDE)
    if not contenuto:
        raise non_valido("Il file è vuoto.", campo="file")
    tipo = _campo_form(form, "tipo")
    if tipo is not None and tipo not in TIPI:
        raise non_valido(FRASI_CAMPI["tipo"], campo="tipo")
    titolo = _campo_form(form, "titolo")
    if titolo and len(titolo) > 120:
        raise non_valido(FRASI_CAMPI["titolo"], campo="titolo")
    data = _campo_form(form, "data")
    if data and not clock.data_valida(data):
        raise non_valido(FRASI_CAMPI["data"], campo="data")
    cartella = _campo_form(form, "cartella_id")
    if cartella is not None:
        if not cartella.isdigit():
            raise non_valido("Questa cartella non c'è nella materia.", campo="cartella_id")
        cartella_id = int(cartella)
        cartella_della_materia(conn, materia_id, cartella_id)  # prima di leggere il file (o pagare l'OCR)
    else:
        cartella_id = None
    est = lettura.estensione(nome_file)
    if est not in lettura.FORMATI:
        raise non_valido(f"Questo tipo di file non lo so leggere. Vanno bene: {lettura.FORMATI_IN_PAROLE}.",
                         campo="file")
    if est in lettura.HEIC:
        # Da qui in poi è un JPEG come gli altri: si salva .jpg e l'OCR lo legge.
        try:
            contenuto = await asyncio.to_thread(lettura.da_heic, contenuto)
        except lettura.FileIlleggibile as e:
            raise non_valido(e.frase, campo="file") from None
        est, nome_file = "jpg", lettura.nome_jpg(nome_file)
    try:
        letto = await asyncio.to_thread(lettura.leggi, contenuto, est)
    except lettura.FileIlleggibile as e:
        raise non_valido(e.frase, campo="file") from None
    origine = "foto" if est in lettura.IMMAGINI else "file"
    if not letto.pagine_ocr:
        if not letto.testo:
            raise non_valido("In questo file non c'è testo da leggere.", campo="file")
        if len(letto.testo) > MASSIMO_CARATTERI:
            raise ErroreApi(413, TROPPO_TESTO)
        stato, pagine = "pronto", None
    else:
        # PDF misto o scansionato, o foto: all'OCR vanno solo le pagine vuote (e non più del necessario).
        _stima_ocr(conn, s, len(letto.pagine_ocr))
        stato, pagine = "in_lettura", letto.pagine
    tipo, titolo, data, titolo_da = _valori(tipo, titolo, data, letto.testo or _primo_testo(letto), nome_file,
                                            origine)
    with db.transazione(conn):
        nuovo = _inserisci(conn, materia_id, tipo, titolo, data, origine, nome_file, est, pagine,
                           letto.testo if stato == "pronto" else "", stato, titolo_da, cartella_id)
        conn.execute("UPDATE materiali SET pagine_ocr = ?, avviso = ? WHERE id = ?",
                     (json.dumps(letto.pagine_ocr) if letto.pagine_ocr else None, letto.avviso, nuovo))
        relativo = Path("materiale") / str(materia_id) / f"{nuovo}.{est}"
        destinazione = Path(s.settings.dati_dir) / relativo
        destinazione.parent.mkdir(parents=True, exist_ok=True)
        destinazione.write_bytes(contenuto)
        conn.execute("UPDATE materiali SET percorso = ? WHERE id = ?", (relativo.as_posix(), nuovo))
        if stato == "pronto" and tipo in ("verifica", "interrogazione"):
            schede.segna_da_aggiornare(conn, materia_id)
    s.lavoro.sveglia("sfondo")
    return forme.materiale(conn, trova_materiale(conn, nuovo))


@router.get("/materie/{materia_id}/materiale")
def elenco(materia_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    trova_materia(conn, materia_id)
    return [forme.materiale(conn, r) for r in conn.execute(
        "SELECT * FROM materiali WHERE materia_id = ? ORDER BY creato_il DESC, id DESC", (materia_id,))]


@router.get("/materie/{materia_id}/suggeriti")
def suggeriti(materia_id: int, argomento: str = "", conn: sqlite3.Connection = Depends(get_conn)):
    """Contratto 16.2: il materiale che parla dell'argomento (gratis, senza AI). Il nome della cartella
    conta come il titolo (contratto 27): "Arte greca" sceglie i file della cartella Arte greca."""
    trova_materia(conn, materia_id)
    argomento = " ".join(argomento.split())[:200]
    righe = [dict(r) for r in conn.execute(
        "SELECT m.id, m.titolo, m.testo, c.nome AS cartella FROM materiali m LEFT JOIN cartelle c ON c.id = m.cartella_id"
        " WHERE m.materia_id = ? AND m.stato = 'pronto' ORDER BY m.creato_il DESC, m.id DESC",
        (materia_id,))]
    return titoli.suggeriti(argomento, righe)


def cartella_della_materia(conn: sqlite3.Connection, materia_id: int, cartella_id: int) -> sqlite3.Row:
    r = conn.execute("SELECT * FROM cartelle WHERE id = ?", (cartella_id,)).fetchone()
    if r is None or r["materia_id"] != materia_id:
        raise non_valido("Questa cartella non c'è nella materia.", campo="cartella_id")
    return r


def metti_in_cartella(conn: sqlite3.Connection, materia_id: int, materiali: list[int], cartella_id: int | None) -> int:
    """Dentro una transazione: sposta il materiale (della stessa materia) in una cartella o fuori (None).
    È una scelta di Andrea: Lectio non lo sposterà più."""
    if cartella_id is not None:
        cartella_della_materia(conn, materia_id, cartella_id)
    ids = list(dict.fromkeys(materiali))
    segnaposti = ",".join("?" * len(ids))
    trovati = conn.execute(f"SELECT COUNT(*) AS n FROM materiali WHERE materia_id = ? AND id IN ({segnaposti})",
                           (materia_id, *ids)).fetchone()["n"]
    if trovati != len(ids):
        raise non_valido("Alcuni file non ci sono più o non sono di questa materia.", campo="materiali")
    adesso = clock.adesso()
    conn.execute(f"UPDATE materiali SET cartella_id = ?, cartella_da = 'utente', cartella_il = ? WHERE id IN ({segnaposti})",
                 (cartella_id, adesso, *ids))
    return len(ids)


@router.patch("/materiale/{materiale_id}")
def cambia(materiale_id: int, corpo: MaterialePatch, conn: sqlite3.Connection = Depends(get_conn),
           s: Servizi = Depends(servizi)):
    """Contratto 16.3: titolo e tipo. Un titolo scritto qui è di Andrea: Lectio non lo tocca più."""
    with db.transazione(conn):
        r = trova_materiale(conn, materiale_id)
        if corpo.titolo is not None and corpo.titolo != r["titolo"]:
            conn.execute("UPDATE materiali SET titolo = ?, titolo_da = 'utente' WHERE id = ?",
                         (corpo.titolo, materiale_id))
        elif corpo.titolo is not None and r["titolo_da"] != "utente":
            conn.execute("UPDATE materiali SET titolo_da = 'utente' WHERE id = ?", (materiale_id,))
        if corpo.tipo is not None and corpo.tipo != r["tipo"]:
            conn.execute("UPDATE materiali SET tipo = ? WHERE id = ?", (corpo.tipo, materiale_id))
            conn.execute("DELETE FROM pezzi WHERE materiale_id = ?", (materiale_id,))  # cambia la priorità
            if r["stato"] == "pronto" and (corpo.tipo in DEL_PROF or r["tipo"] in DEL_PROF):
                schede.segna_da_aggiornare(conn, r["materia_id"])
        if "cartella_id" in corpo.model_fields_set:
            # Contratto 27: spostato da Andrea (anche fuori dalle cartelle): Lectio non lo sposta più.
            metti_in_cartella(conn, r["materia_id"], [materiale_id], corpo.cartella_id)
    s.lavoro.sveglia("sfondo")
    return forme.materiale(conn, trova_materiale(conn, materiale_id))


@router.get("/materiale/{materiale_id}")
def uno(materiale_id: int, conn: sqlite3.Connection = Depends(get_conn)):
    return forme.materiale(conn, trova_materiale(conn, materiale_id), con_testo=True)


def togli_materiali(conn: sqlite3.Connection, righe: list[sqlite3.Row]) -> list[str]:
    """Dentro una transazione: cancella il materiale e segna da rifare la scheda del prof se è andata
    via una verifica o un'interrogazione. Torna i file da togliere dal disco dopo il database.

    Le lezioni ristrette a questo materiale lo tengono nell'elenco: se non resta niente, la
    preparazione si ferma con una frase chiara invece di allargarsi a tutto il materiale."""
    da_rifare = set()
    for r in righe:
        conn.execute("DELETE FROM materiali WHERE id = ?", (r["id"],))
        if r["stato"] == "pronto" and r["tipo"] in DEL_PROF:
            da_rifare.add(r["materia_id"])
    for materia_id in da_rifare:
        schede.segna_da_aggiornare(conn, materia_id)
    return [r["percorso"] for r in righe if r["percorso"]]


def togli_file(s: Servizi, percorsi: list[str]) -> None:
    for percorso in percorsi:
        try:
            (Path(s.settings.dati_dir) / percorso).unlink()
        except OSError:
            pass


@router.delete("/materiale/{materiale_id}")
def cancella(materiale_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_materiale(conn, materiale_id)
    with db.transazione(conn):
        percorsi = togli_materiali(conn, [r])
    s.lavoro.sveglia("sfondo")
    togli_file(s, percorsi)
    return {"ok": True}


@router.post("/materiale/{materiale_id}/riprova")
def riprova(materiale_id: int, conn: sqlite3.Connection = Depends(get_conn), s: Servizi = Depends(servizi)):
    r = trova_materiale(conn, materiale_id)
    if r["stato"] != "errore":
        raise conflitto("Si può riprovare solo un materiale che non sono riuscito a leggere.")
    if not r["percorso"] or not (Path(s.settings.dati_dir) / r["percorso"]).is_file():
        raise conflitto("Il file non c'è più sul server: caricalo di nuovo.")
    pagine = len(json.loads(r["pagine_ocr"])) if r["pagine_ocr"] else int(r["pagine"] or 1)
    _stima_ocr(conn, s, pagine)
    conn.execute("UPDATE materiali SET stato = 'in_lettura', errore = NULL WHERE id = ?", (materiale_id,))
    s.lavoro.libera("materiale", materiale_id)
    s.lavoro.sveglia("sfondo")
    return forme.materiale(conn, trova_materiale(conn, materiale_id))

