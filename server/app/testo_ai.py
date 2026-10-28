"""Chi scrive i testi: Mistral (chat) oppure, in modalità prova, il generatore finto
(finto.py). Stessa interfaccia, sostituibili:

    genera(compito=..., messaggi=[{"role","content"}], modello=..., json_atteso=True,
           max_token=..., dati={...}) -> RispostaTesto

`compito` e `dati` servono solo al generatore finto (per usare davvero i materiali senza
leggere i prompt); Mistral li ignora. Ogni chiamata è UN tentativo: i ritenti li decide la
Cassa (spesa.py), che ricontrolla il tetto prima di ognuno."""

import logging
from dataclasses import dataclass

import httpx

from .config import MISTRAL_URL
from .rete import controlla, invia

log = logging.getLogger("uvicorn.error")


@dataclass
class RispostaTesto:
    testo: str
    token_entrata: int
    token_uscita: int
    modello: str


class TestoMistral:
    nome = "mistral"
    a_pagamento = True

    def __init__(self, chiave: str, transport: httpx.BaseTransport | None = None, timeout: float = 180.0,
                 url: str = MISTRAL_URL):
        self._chiave = chiave
        self._transport = transport
        self._timeout = timeout
        self._url = url

    def genera(self, *, compito: str, messaggi: list[dict], modello: str, json_atteso: bool = True,
               max_token: int = 4000, temperatura: float = 0.4, dati: dict | None = None) -> RispostaTesto:
        corpo = {"model": modello, "messages": messaggi, "temperature": temperatura, "max_tokens": max_token}
        if json_atteso:
            corpo["response_format"] = {"type": "json_object"}

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/chat/completions", json=corpo,
                              headers={"Authorization": f"Bearer {self._chiave}"})

        r = controlla(invia("Mistral", manda), "Mistral")
        d = r.json()
        contenuto = d["choices"][0]["message"]["content"]
        if isinstance(contenuto, list):  # alcuni modelli rispondono a pezzi
            contenuto = "".join(p.get("text", "") for p in contenuto if isinstance(p, dict))
        uso = d.get("usage") or {}
        return RispostaTesto(
            testo=contenuto or "",
            token_entrata=int(uso.get("prompt_tokens") or 0),
            token_uscita=int(uso.get("completion_tokens") or 0),
            modello=d.get("model") or modello,
        )
