import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from fastapi import Request

from . import errori
from .config import VERSIONE, assicura_cartelle, carica_settings, riassunto_config
from .db import init_db, ripristina_dopo_riavvio
from .limiti import LimiteCorpo
from .rete import ErroreFornitore
from .routes import eventi, lezioni, materiale, materie, sistema, varie
from .scheda import dopo_le_chiavi
from .servizi import crea_servizi
from .spesa import TettoRaggiunto, VoceGratisFinita

# uvicorn.error ha già un gestore a livello INFO: le righe finiscono nel log del container.
log = logging.getLogger("uvicorn.error")


@asynccontextmanager
async def _ciclo_di_vita(app: FastAPI):
    compito, ferma = None, asyncio.Event()
    if app.state.settings.sottofondo:
        compito = asyncio.create_task(app.state.servizi.lavoro.gira(ferma))
    try:
        yield
    finally:
        ferma.set()
        if compito is not None:
            await compito


def create_app() -> FastAPI:
    settings = carica_settings()
    log.info("Lectio avvio: %s", riassunto_config(settings))
    assicura_cartelle(settings)
    init_db(settings.db_path)
    # Una lezione che risulta "in preparazione" all'avvio si era fermata a metà: va in errore
    # con la sua frase, e Riprova riparte dalla fase in cui era.
    ripristina_dopo_riavvio(settings.db_path)
    if not settings.modalita_prova:
        # Arrivata la chiave Mistral: foto lette e schede scritte dal finto si rifanno davvero.
        dopo_le_chiavi(settings.db_path)

    # Niente CORS: le app non sono pagine web, e un sito qualsiasi aperto nel browser
    # non deve poter parlare col server.
    app = FastAPI(title="Lectio", version=VERSIONE, lifespan=_ciclo_di_vita, docs_url=None, redoc_url=None,
                  openapi_url=None)
    app.state.settings = settings
    app.state.servizi = crea_servizi(settings)
    errori.registra(app)
    app.add_middleware(LimiteCorpo)

    # Errori dei fornitori e del tetto che arrivano fino a una richiesta (per esempio un modello
    # senza prezzo durante una stima): sempre la frase, mai un 500.
    @app.exception_handler(ErroreFornitore)
    async def _fornitore(request: Request, e: ErroreFornitore):
        return errori.risposta(503, e.frase)

    @app.exception_handler(TettoRaggiunto)
    async def _tetto(request: Request, e: TettoRaggiunto):
        return errori.risposta(402, e.frase)

    @app.exception_handler(VoceGratisFinita)
    async def _gratis(request: Request, e: VoceGratisFinita):
        return errori.risposta(402, e.frase)

    app.include_router(sistema.pubblico, prefix="/api")
    app.include_router(sistema.protetto, prefix="/api")
    app.include_router(sistema.scarica_router)
    app.include_router(materie.router, prefix="/api")
    app.include_router(materiale.router, prefix="/api")
    app.include_router(eventi.router, prefix="/api")
    app.include_router(lezioni.router, prefix="/api")
    app.include_router(varie.router, prefix="/api")
    return app
