"""Lettura di foto e PDF scansionati: Mistral OCR (`POST /v1/ocr`, a pagamento), oppure in
modalità prova un lettore finto che lo dice chiaramente. Il costo si calcola dalle pagine
che Mistral dice di aver elaborato. Ogni chiamata è UN tentativo (i ritenti li fa la Cassa)."""

import base64
from dataclasses import dataclass, field

import httpx

from .config import MISTRAL_URL
from .db import TESTO_OCR_FINTO
from .rete import controlla, invia


@dataclass
class RispostaOcr:
    testo: str
    pagine: int
    testi_pagine: list[str] = field(default_factory=list)  # una voce per pagina, in ordine


def _data_uri(contenuto: bytes, mime: str) -> str:
    return f"data:{mime};base64,{base64.b64encode(contenuto).decode('ascii')}"


class OcrMistral:
    nome = "mistral"
    a_pagamento = True

    def __init__(self, chiave: str, transport: httpx.BaseTransport | None = None, timeout: float = 300.0,
                 url: str = MISTRAL_URL):
        self._chiave = chiave
        self._transport = transport
        self._timeout = timeout
        self._url = url

    def leggi(self, contenuto: bytes, mime: str, modello: str, pagine: int = 1) -> RispostaOcr:
        if mime == "application/pdf":
            documento = {"type": "document_url", "document_url": _data_uri(contenuto, mime)}
        else:
            documento = {"type": "image_url", "image_url": _data_uri(contenuto, mime)}
        corpo = {"model": modello, "document": documento}

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/ocr", json=corpo, headers={"Authorization": f"Bearer {self._chiave}"})

        d = controlla(invia("Mistral", manda), "Mistral").json()
        testi = [(p.get("markdown") or "").strip() for p in d.get("pages") or []]
        uso = d.get("usage_info") or {}
        elaborate = int(uso.get("pages_processed") or len(testi) or 1)
        return RispostaOcr(testo="\n\n".join(testi).strip(), pagine=elaborate, testi_pagine=testi)


class OcrFinto:
    """Modalità prova: niente lettura vera (costerebbe), ma un testo che lo dice. Quello che
    legge resta segnato come "da prova" e si rilegge quando arriva la chiave Mistral."""

    nome = "prova"
    a_pagamento = False

    def leggi(self, contenuto: bytes, mime: str, modello: str, pagine: int = 1) -> RispostaOcr:
        testo = (
            f"{TESTO_OCR_FINTO} le foto e i PDF scansionati: "
            "serve la chiave Mistral. Questo è un segnaposto per provare il resto dell'app."
        )
        return RispostaOcr(testo=testo, pagine=pagine, testi_pagine=[testo] + [""] * (max(1, pagine) - 1))
