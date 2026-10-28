import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from finti import MontatoreFinto, ServerFinto, VoceFinta  # noqa: E402

# Martedì 27 ottobre 2026, 09:00 UTC = 10:00 a Roma (ora solare).
ORA_INIZIALE = datetime(2026, 10, 27, 9, 0, 0, tzinfo=timezone.utc)


def pytest_configure(config):
    config.addinivalue_line("markers", "pagamento: server con chiavi (finte) e fornitori finti a pagamento")


class Orologio:
    def __init__(self, iniziale: datetime):
        self.corrente = iniziale

    def avanza(self, **kwargs):
        self.corrente += timedelta(**kwargs)


@pytest.fixture
def orologio(monkeypatch):
    from app import clock

    o = Orologio(ORA_INIZIALE)
    monkeypatch.setattr(clock, "now", lambda: o.corrente)
    return o


@pytest.fixture
def ambiente(tmp_path, monkeypatch):
    monkeypatch.setenv("LECTIO_DATI", str(tmp_path / "dati"))
    monkeypatch.setenv("LECTIO_DB", str(tmp_path / "dati" / "lectio-test.db"))
    monkeypatch.setenv("LECTIO_SOTTOFONDO", "0")
    monkeypatch.setenv("LECTIO_APK_DIR", str(tmp_path / "apk"))
    for nome in ("LECTIO_MISTRAL_KEY", "LECTIO_AZURE_KEY", "LECTIO_AZURE_REGIONE", "LECTIO_VERSIONI",
                 "LECTIO_MODELLO_PIANO", "LECTIO_MODELLO_SCRITTURA", "LECTIO_MODELLO_OCR", "LECTIO_MODELLO_VOCE",
                 "LECTIO_VOCE_MISTRAL", "LECTIO_PREZZI", "LECTIO_GEMINI"):
        monkeypatch.delenv(nome, raising=False)
    return tmp_path


@pytest.fixture
def server():
    return ServerFinto()


def monta_servizi(app, server: ServerFinto | None = None):
    """Mette i finti al posto di rete, ffmpeg ed espeak; le attese diventano istantanee."""
    from app.servizi import crea_servizi

    s = crea_servizi(app.state.settings, transport=server.trasporto() if server else None,
                     dormi=lambda secondi: None, montatore=MontatoreFinto(), voce_prova=VoceFinta())
    if s.catalogo.azure is not None:
        s.catalogo.azure.pausa_minima = 0
    app.state.servizi = s
    return s


@pytest.fixture
def app(request, ambiente, orologio, server, monkeypatch):
    if request.node.get_closest_marker("pagamento"):
        monkeypatch.setenv("LECTIO_MISTRAL_KEY", "chiave-finta-mistral")
        monkeypatch.setenv("LECTIO_AZURE_KEY", "chiave-finta-azure")
        monkeypatch.setenv("LECTIO_AZURE_REGIONE", "westeurope")
    from app.main import create_app

    applicazione = create_app()
    monta_servizi(applicazione, server)
    return applicazione


@pytest.fixture
def client(app):
    with TestClient(app) as c:
        yield c


@pytest.fixture
def servizi(app):
    return app.state.servizi


@pytest.fixture
def token(app):
    from app import abbina, db

    with db.aperta(app.state.settings.db_path) as conn:
        codice, _ = abbina.nuovo_codice(conn)
        t, _ = abbina.abbina(conn, codice, "Telefono di prova", "android", abbina.clock.now())
    return t


@pytest.fixture
def auth(token):
    return {"Authorization": f"Bearer {token}"}
