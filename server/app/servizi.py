"""I pezzi del server che parlano con l'esterno, raccolti in un posto: le route li
trovano in request.app.state.servizi, i test li sostituiscono con dei finti
(per esempio passando un `transport` httpx finto: nessuna chiamata vera)."""

import time
from dataclasses import dataclass

import httpx
from fastapi import Request

from .audio import Montatore
from .config import Settings
from .finto import TestoFinto
from .lavoro import Lavoro
from .lezione import Contesto
from .origine import Origine
from .ocr import OcrFinto, OcrMistral
from .spesa import Cassa, Prezzi
from .testo_ai import TestoMistral
from .voci import Catalogo, VoceAzure, VoceMistral, VoceProva


@dataclass
class Servizi:
    settings: Settings
    ctx: Contesto
    lavoro: Lavoro
    origine: Origine

    @property
    def cassa(self) -> Cassa:
        return self.ctx.cassa

    @property
    def catalogo(self) -> Catalogo:
        return self.ctx.catalogo


def crea_servizi(settings: Settings, transport: httpx.BaseTransport | None = None, dormi=time.sleep,
                 montatore: Montatore | None = None, voce_prova: VoceProva | None = None) -> Servizi:
    prezzi = Prezzi.carica(settings.prezzi_path)
    cassa = Cassa(settings.db_path, prezzi, dormi=dormi)
    if settings.mistral_key:
        testo = TestoMistral(settings.mistral_key, transport=transport)
        ocr = OcrMistral(settings.mistral_key, transport=transport)
        voce_mistral = VoceMistral(settings.mistral_key, settings.modello_voce, settings.voce_mistral,
                                   transport=transport)
    else:
        testo, ocr, voce_mistral = TestoFinto(), OcrFinto(), None
    azure = None
    if settings.azure_pronta:
        azure = VoceAzure(settings.azure_key, settings.azure_regione, transport=transport, dormi=dormi)
    catalogo = Catalogo(
        prova=settings.modalita_prova,
        euro_al_minuto_mistral=prezzi.euro_al_minuto_voce(settings.modello_voce),
        euro_al_minuto_azure=prezzi.euro_al_minuto_azure(),
        voce_mistral_id=settings.voce_mistral,
        mistral=voce_mistral,
        azure=azure,
        voce_prova=voce_prova or VoceProva(),
    )
    ctx = Contesto(settings=settings, cassa=cassa, testo=testo, ocr=ocr, catalogo=catalogo,
                   montatore=montatore or Montatore())
    return Servizi(settings=settings, ctx=ctx, lavoro=Lavoro(ctx), origine=Origine(settings.tailscale_host))


def servizi(request: Request) -> Servizi:
    return request.app.state.servizi
