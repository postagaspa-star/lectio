"""Foto e file aggiunti a una risposta della verifica di prova (contratto 21.1).

Scelta di Andrea del 23/11/2026: i conti si scrivono con la barra della matematica, e in più, se si vuole, si aggiungono
foto o PDF (per esempio il foglio fatto a mano). Si leggono solo alla correzione, con la stessa strada dei materiali:
il testo del PDF gratis se c'è, le foto piccole con luna (foto_piccole.py), il resto con l'OCR. Il testo letto si tiene
accanto al file, così una seconda correzione non paga di nuovo."""

import json
import logging
import secrets
from pathlib import Path

from . import foto_piccole, lettura
from .spesa import TettoRaggiunto

log = logging.getLogger("uvicorn.error")

FORMATI = {"jpg", "jpeg", "png", "webp", "heic", "heif", "pdf"}
PER_DOMANDA = 4
PAGINE_MASSIME = 6
CARATTERI_MASSIMI = 6000
FRASE_FORMATO = "Questo file non lo leggo: usa una foto (JPG, PNG, WEBP, HEIC) o un PDF."
FRASE_TROPPI = f"A questa domanda ci sono già {PER_DOMANDA} file: togline uno per aggiungerne un altro."


def cartella(dati_dir: str, simulata_id: int) -> Path:
    return Path(dati_dir) / "simulate" / str(simulata_id) / "allegati"


def elenco(r) -> dict:
    try:
        return json.loads(r["allegati"] or "{}")
    except (KeyError, IndexError, TypeError, ValueError):
        return {}


def per_api(allegati: dict) -> dict:
    return {n: [{"id": a["id"], "nome": a["nome"], "tipo": a["tipo"]} for a in voci] for n, voci in allegati.items() if voci}


def salva(dati_dir: str, simulata_id: int, nome: str, est: str, contenuto: bytes) -> dict:
    """Il file sul disco; la riga da mettere in `allegati` (senza scriverla: lo fa chi chiama, nella transazione)."""
    ident = secrets.token_urlsafe(6).replace("-", "x").replace("_", "y")
    dove = cartella(dati_dir, simulata_id)
    dove.mkdir(parents=True, exist_ok=True)
    (dove / f"{ident}.{est}").write_bytes(contenuto)
    return {"id": ident, "nome": nome, "tipo": "pdf" if est == "pdf" else "foto", "file": f"{ident}.{est}"}


def togli_file(dati_dir: str, simulata_id: int, allegato: dict) -> None:
    for nome in (allegato.get("file"), f"{allegato.get('id')}.txt"):
        if nome:
            try:
                (cartella(dati_dir, simulata_id) / nome).unlink(missing_ok=True)
            except OSError:
                pass


def testo(ctx, simulata_id: int, materia_id: int | None, allegato: dict) -> str:
    """Il testo del file, letto una volta sola; "" se non si legge (la correzione va avanti lo stesso).
    Solo il tetto del mese ferma tutto."""
    dove = cartella(ctx.settings.dati_dir, simulata_id)
    salvato = dove / f"{allegato['id']}.txt"
    if salvato.is_file():
        return salvato.read_text(encoding="utf-8")
    try:
        contenuto = (dove / allegato["file"]).read_bytes()
    except (OSError, KeyError):
        return ""
    est = lettura.estensione(allegato["file"])
    riferimento = f"simulata:{simulata_id}"
    try:
        letto = lettura.leggi(contenuto, est)
        if not letto.pagine_ocr:
            uscita = letto.testo or ""
        else:
            uscita = foto_piccole.leggi(ctx, contenuto, est, titolo=allegato["nome"], materia_id=materia_id,
                                        riferimento=riferimento) or ""
            if not uscita:
                uscita = _ocr(ctx, contenuto, est, letto, allegato["nome"], materia_id, riferimento)
    except TettoRaggiunto:
        raise
    except Exception:
        log.warning("simulata %s: non sono riuscito a leggere un file allegato", simulata_id, exc_info=True)
        return ""
    uscita = lettura.pulisci(uscita)[:CARATTERI_MASSIMI]
    if uscita:
        salvato.write_text(uscita, encoding="utf-8")
    return uscita


def _ocr(ctx, contenuto: bytes, est: str, letto, nome: str, materia_id, riferimento: str) -> str:
    ocr = ctx.ocr
    modello = ctx.settings.modello_ocr
    prezzi = ctx.cassa.prezzi
    pagine = letto.pagine_ocr[:PAGINE_MASSIME]
    if est == "pdf" and len(pagine) < letto.pagine:
        contenuto = lettura.sotto_pdf(contenuto, pagine)
    mime = lettura.IMMAGINI.get(est, "application/pdf")

    def registra(ricevuta, risposta):
        if ocr.a_pagamento:
            ricevuta.registra(risposta.pagine, "pagine", prezzi.costo_ocr(modello, risposta.pagine))

    risposta = ctx.cassa.chiama(
        cosa=f"Lettura di «{nome}» (verifica di prova)", fornitore=ocr.nome,
        stima_eur=prezzi.costo_ocr(modello, len(pagine)) if ocr.a_pagamento else 0.0,
        quantita_stimata=len(pagine), unita="pagine", materia_id=materia_id, riferimento=riferimento,
        fare=lambda: ocr.leggi(contenuto, mime, modello, pagine=len(pagine)), registra=registra)
    if letto.testi_pagine is not None:
        return lettura.unisci_pagine(letto.testi_pagine, pagine, risposta.testi_pagine or [risposta.testo])
    return risposta.testo
