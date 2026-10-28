"""Materiale (contratto 6 e 15.1): formati, limiti di dimensione, OCR a pagamento in sottofondo,
titoli e date dai testi di Gemini."""

from pathlib import Path

import pytest

from aiuti import (VERIFICA_GEMINI, aggiungi_testo, carica_file, crea_materia, docx, odt, pdf_con_testo,
                   pdf_scansionato, png, rtf, sfondo_tutto)
from app import db

pagamento = pytest.mark.pagamento


@pytest.fixture
def materia(client, auth):
    return crea_materia(client, auth)


def leggi(client, auth, materiale_id):
    return client.get(f"/api/materiale/{materiale_id}", headers=auth).json()


@pytest.mark.parametrize("nome,contenuto,atteso", [
    ("appunti.txt", "Augusto fu il primo imperatore.\r\nIl senato restò.".encode("utf-8"), "primo imperatore"),
    ("appunti.md", "# Augusto\n\nIl **principato** nasce nel 27 a.C.".encode("utf-8"), "principato"),
    ("vecchio.txt", "Città di Roma e libertà".encode("cp1252"), "Città di Roma"),
    ("bom.txt", "﻿Testo con BOM all'inizio".encode("utf-8"), "Testo con BOM"),
])
def test_testi_semplici(client, auth, materia, nome, contenuto, atteso):
    r = carica_file(client, auth, materia["id"], nome, contenuto)
    assert r.status_code == 201, r.text
    m = r.json()
    assert m["stato"] == "pronto" and m["origine"] == "file" and m["nome_file"] == nome
    assert m["tipo"] == "appunti" and m["costo_eur"] == 0
    assert m["caratteri"] > 0 and m["errore"] is None
    testo = leggi(client, auth, m["id"])["testo"]
    assert atteso in testo and "﻿" not in testo and "\r" not in testo


def test_pdf_con_testo_si_legge_gratis(client, auth, materia):
    righe = ["Augusto e il principato.", "Il senato restava in piedi ma il potere era suo.",
             "Nel 27 avanti Cristo ricevette il titolo di Augusto."] * 3
    r = carica_file(client, auth, materia["id"], "Lezione 3.pdf", pdf_con_testo(righe), tipo="libro")
    assert r.status_code == 201, r.text
    m = r.json()
    assert m["stato"] == "pronto" and m["tipo"] == "libro" and m["titolo"] == "Lezione 3"
    assert "titolo di Augusto" in leggi(client, auth, m["id"])["testo"]


def test_word_odt_rtf(client, auth, materia):
    r = carica_file(client, auth, materia["id"], "tema.docx", docx(["Primo paragrafo su Nerone.", "Secondo: l'incendio."]))
    assert r.status_code == 201, r.text
    t = leggi(client, auth, r.json()["id"])["testo"]
    assert t == "Primo paragrafo su Nerone.\nSecondo: l'incendio."
    r = carica_file(client, auth, materia["id"], "riassunto.odt", odt(["Caligola regnò poco."]))
    assert r.status_code == 201, r.text
    t = leggi(client, auth, r.json()["id"])["testo"]
    assert "Titolo" in t and "Caligola regnò poco." in t and "Due  spazi" in t
    r = carica_file(client, auth, materia["id"], "schema.rtf", rtf("Claudio conquist\\'f2 la Britannia."))
    assert r.status_code == 201, r.text
    t = leggi(client, auth, r.json()["id"])["testo"]
    assert "Claudio conquistò la Britannia." in t
    assert "Città di Roma è fine" in t
    assert "Times New Roman" not in t and "Prova" not in t  # tabelle dei caratteri e generatore tolti


def test_file_rovinati_e_tipi_sconosciuti(client, auth, materia):
    r = carica_file(client, auth, materia["id"], "rotto.docx", b"non sono uno zip")
    assert r.status_code == 422 and r.json()["campo"] == "file"
    assert "Word" in r.json()["errore"]
    r = carica_file(client, auth, materia["id"], "rotto.pdf", b"%PDF-1.4 niente")
    assert r.status_code == 422 and "PDF" in r.json()["errore"]
    r = carica_file(client, auth, materia["id"], "foto.heic", b"xxx")
    assert r.status_code == 422 and ".jpg" in r.json()["errore"]
    r = carica_file(client, auth, materia["id"], "vuoto.txt", b"")
    assert r.status_code == 422
    r = carica_file(client, auth, materia["id"], "spazi.txt", b"   \n  ")
    assert r.status_code == 422 and r.json()["errore"] == "In questo file non c'è testo da leggere."
    r = client.post(f"/api/materie/{materia['id']}/materiale", files={"tipo": (None, "appunti")}, headers=auth)
    assert r.status_code == 422 and r.json()["errore"] == "Manca il file."
    r = carica_file(client, auth, materia["id"], "a.txt", b"testo valido", tipo="compito")
    assert r.status_code == 422 and r.json()["campo"] == "tipo"
    r = carica_file(client, auth, materia["id"], "a.txt", b"testo valido", data="12/10/2026")
    assert r.status_code == 422 and r.json()["campo"] == "data"
    with db.aperta(client.app.state.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM materiali").fetchone()[0] == 0


def test_limiti_di_dimensione(client, auth, materia):
    grande = b"a" * (20 * 1024 * 1024 + 1)
    r = carica_file(client, auth, materia["id"], "enorme.txt", grande)
    assert r.status_code == 413
    assert r.json() == {"errore": "Il file è troppo grande: il massimo è 20 MB.", "codice": "troppo_grande"}
    troppo_testo = ("parola " * 30_000).encode()  # 210.000 caratteri
    r = carica_file(client, auth, materia["id"], "lungo.txt", troppo_testo)
    assert r.status_code == 413 and "200.000" in r.json()["errore"]
    r = client.post(f"/api/materie/{materia['id']}/materiale", json={"testo": "x" * 200_001}, headers=auth)
    assert r.status_code == 413 and r.json()["codice"] == "troppo_grande"
    giusto = ("parola " * 28_000).encode()  # 196.000 caratteri: va
    assert carica_file(client, auth, materia["id"], "giusto.txt", giusto).status_code == 201


def test_testo_di_gemini_titolo_tipo_data(client, auth, materia):
    r = carica_file(client, auth, materia["id"], "Gemini-trascrizione.txt", VERIFICA_GEMINI.encode())
    m = r.json()
    assert m["titolo"] == "Verifica 12 ottobre" and m["tipo"] == "verifica" and m["data"] == "2026-10-12"
    orale = VERIFICA_GEMINI.replace("TIPO: scritta", "TIPO: orale").replace("DATA: 12/10/2026", "DATA: 3 ottobre 2026")
    m = aggiungi_testo(client, auth, materia["id"], orale, tipo=None)
    assert m["tipo"] == "interrogazione" and m["titolo"] == "Interrogazione 3 ottobre" and m["data"] == "2026-10-03"
    # Quello che sceglie Andrea vale sopra la testata.
    m = aggiungi_testo(client, auth, materia["id"], VERIFICA_GEMINI, tipo="altro", titolo="Mio titolo", data="2026-10-20")
    assert (m["tipo"], m["titolo"], m["data"]) == ("altro", "Mio titolo", "2026-10-20")


def test_scritto_a_mano(client, auth, materia):
    m = aggiungi_testo(client, auth, materia["id"], "Il prof mi ha chiesto di Seiano.\nHo risposto bene.",
                       tipo="interrogazione")
    assert m["origine"] == "scritto" and m["titolo"] == "Il prof mi ha chiesto di Seiano."
    assert m["data"] == "2026-10-27"
    r = client.post(f"/api/materie/{materia['id']}/materiale", json={"testo": ""}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "testo"
    r = client.post(f"/api/materie/{materia['id']}/materiale", json={"testo": "x", "tipo": "pagella"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "tipo"
    r = client.post(f"/api/materie/{materia['id']}/materiale", content=b"non json",
                    headers={**auth, "Content-Type": "application/json"})
    assert r.status_code == 422


def test_elenco_dettaglio_cancella(client, auth, materia, servizi, orologio):
    a = aggiungi_testo(client, auth, materia["id"], "Primo testo di appunti.")
    orologio.avanza(minutes=1)
    r = carica_file(client, auth, materia["id"], "b.txt", b"Secondo testo di appunti.")
    b = r.json()
    elenco = client.get(f"/api/materie/{materia['id']}/materiale", headers=auth).json()
    assert [x["id"] for x in elenco] == [b["id"], a["id"]]  # più recenti prima
    assert "testo" not in elenco[0]
    percorso = Path(servizi.settings.dati_dir) / "materiale" / str(materia["id"]) / f"{b['id']}.txt"
    assert percorso.is_file()
    assert client.delete(f"/api/materiale/{b['id']}", headers=auth).status_code == 200
    assert not percorso.exists()
    assert client.get(f"/api/materiale/{b['id']}", headers=auth).status_code == 404
    assert client.get("/api/materiale/9999", headers=auth).json()["codice"] == "non_trovato"
    assert client.get("/api/materie/9999/materiale", headers=auth).status_code == 404


def test_foto_in_prova_letta_dal_finto(client, auth, materia, servizi):
    r = carica_file(client, auth, materia["id"], "verifica.jpg", png(), tipo="verifica")
    assert r.status_code == 201, r.text
    m = r.json()
    assert m["stato"] == "in_lettura" and m["origine"] == "foto" and m["titolo"] == "Foto 27 ottobre"
    sfondo_tutto(servizi)
    dopo = leggi(client, auth, m["id"])
    assert dopo["stato"] == "pronto" and "modalità prova" in dopo["testo"]
    assert dopo["costo_eur"] == 0
    novita = client.get("/api/novita", headers=auth).json()
    assert any(n["tipo"] == "materiale_letto" and n["materiale_id"] == m["id"] for n in novita)


@pagamento
def test_pdf_scansionato_ocr_a_pagamento(client, auth, materia, servizi, server):
    server.ocr_pagine = 3
    r = carica_file(client, auth, materia["id"], "scansione.pdf", pdf_scansionato(3), tipo="verifica")
    assert r.status_code == 201, r.text
    m = r.json()
    assert m["stato"] == "in_lettura" and m["origine"] == "file"
    sfondo_tutto(servizi)
    dopo = leggi(client, auth, m["id"])
    assert dopo["stato"] == "pronto" and "Augusto fu il primo imperatore" in dopo["testo"]
    # 3 pagine × 4 $ / 1000 × 0,86 = 0,01032 €
    assert dopo["costo_eur"] == 0.01
    corpo = [d for p, d in server.chiamate if p == "/v1/ocr"][0]
    assert corpo["model"] == "mistral-ocr-latest"
    assert corpo["document"]["type"] == "document_url"
    assert corpo["document"]["document_url"].startswith("data:application/pdf;base64,")
    with db.aperta(servizi.settings.db_path) as conn:
        spesa = conn.execute("SELECT * FROM spese WHERE materiale_id = ?", (m["id"],)).fetchone()
    assert spesa["quantita"] == 3 and spesa["unita"] == "pagine"
    assert spesa["costo_eur"] == pytest.approx(3 * 0.004 * 0.86)
    # Una verifica letta fa partire l'aggiornamento della scheda.
    scheda = client.get(f"/api/materie/{materia['id']}/scheda", headers=auth).json()
    assert scheda["stato"] == "pronta"


@pagamento
def test_foto_ocr_come_immagine(client, auth, materia, servizi, server):
    r = carica_file(client, auth, materia["id"], "foto.png", png())
    sfondo_tutto(servizi)
    corpo = [d for p, d in server.chiamate if p == "/v1/ocr"][0]
    assert corpo["document"] == {"type": "image_url", "image_url": corpo["document"]["image_url"]}
    assert corpo["document"]["image_url"].startswith("data:image/png;base64,")
    assert leggi(client, auth, r.json()["id"])["stato"] == "pronto"


@pagamento
def test_ocr_che_sforerebbe_il_tetto_402(client, auth, materia, servizi):
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur)"
                     " VALUES ('2026-10-27T08:00:00.000+00:00', '2026-10', 'x', 'mistral', 1, 'token', 1.999)")
    r = carica_file(client, auth, materia["id"], "foto.jpg", png())
    assert r.status_code == 402 and r.json()["codice"] == "tetto_raggiunto"
    assert "sforerebbe il tetto di 2 €" in r.json()["errore"]
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM materiali").fetchone()[0] == 0


@pagamento
def test_ocr_che_fallisce_e_riprova(client, auth, materia, servizi, server):
    server.guasti["/v1/ocr"] = [503, 503, 503, 503]  # "occupato": di sicuro non ha lavorato
    r = carica_file(client, auth, materia["id"], "foto.jpg", png())
    sfondo_tutto(servizi)
    m = leggi(client, auth, r.json()["id"])
    assert m["stato"] == "errore"
    assert m["errore"] == "Mistral non risponde in questo momento. Riprova tra qualche minuto."
    assert server.conta("/v1/ocr") == 4  # 1 + 3 ritenti
    assert m["costo_eur"] == 0
    novita = client.get("/api/novita", headers=auth).json()
    assert any(n["tipo"] == "materiale_errore" for n in novita)
    r = client.post(f"/api/materiale/{m['id']}/riprova", headers=auth)
    assert r.status_code == 200 and r.json()["stato"] == "in_lettura"
    sfondo_tutto(servizi)
    assert leggi(client, auth, m["id"])["stato"] == "pronto"
    assert client.post(f"/api/materiale/{m['id']}/riprova", headers=auth).status_code == 409
