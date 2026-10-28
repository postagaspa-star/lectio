"""Il lavoro in sottofondo vero (i due giri asincroni), non i passi chiamati a mano."""

import time

from fastapi.testclient import TestClient

from aiuti import VERIFICA_GEMINI, aggiungi_testo, crea_materia, nuova_lezione
from app import abbina, db
from conftest import monta_servizi


def test_i_giri_in_sottofondo_preparano_e_aggiornano(ambiente, monkeypatch):
    monkeypatch.setenv("LECTIO_SOTTOFONDO", "1")
    from app.main import create_app

    app = create_app()
    monta_servizi(app)
    with db.aperta(app.state.settings.db_path) as conn:
        codice, _ = abbina.nuovo_codice(conn)
        token, _ = abbina.abbina(conn, codice, "PC di prova", "pc", abbina.clock.now())
    auth = {"Authorization": f"Bearer {token}"}
    with TestClient(app) as client:
        m = crea_materia(client, auth)
        aggiungi_testo(client, auth, m["id"])
        aggiungi_testo(client, auth, m["id"], VERIFICA_GEMINI, tipo="verifica")
        l = nuova_lezione(client, auth, m["id"])
        limite = time.monotonic() + 60
        stato = scheda = None
        while time.monotonic() < limite:
            stato = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"]
            scheda = client.get(f"/api/materie/{m['id']}/scheda", headers=auth).json()["stato"]
            if stato == "pronta" and scheda == "pronta":
                break
            time.sleep(0.2)
        assert stato == "pronta" and scheda == "pronta"
    # All'uscita i giri si fermano senza errori (il blocco "with" è finito).
