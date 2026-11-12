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
    troncata: bool = False  # finita perché era arrivata al massimo di token: il testo è a metà
    token_cache: int = 0  # quanti dei token d'entrata Mistral ha preso dalla cache (si pagano il 10%)


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
               max_token: int = 4000, temperatura: float = 0.4, dati: dict | None = None,
               ragionamento: str | None = None, chiave_cache: str | None = None) -> RispostaTesto:
        corpo = {"model": modello, "messages": messaggi, "temperature": temperatura, "max_tokens": max_token}
        if chiave_cache:
            # Le richieste con lo stesso inizio e la stessa chiave trovano più spesso la cache (dati, mai segreti).
            corpo["prompt_cache_key"] = chiave_cache
        if json_atteso:
            corpo["response_format"] = {"type": "json_object"}
        if ragionamento:
            corpo["reasoning_effort"] = ragionamento  # i pezzi "thinking" della risposta si scartano qui sotto

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/chat/completions", json=corpo,
                              headers={"Authorization": f"Bearer {self._chiave}"})

        r = controlla(invia("Mistral", manda), "Mistral")
        d = r.json()
        contenuto = d["choices"][0]["message"]["content"]
        if isinstance(contenuto, list):  # alcuni modelli rispondono a pezzi; il ragionamento ("thinking") non serve
            contenuto = "".join(str(p.get("text") or "") for p in contenuto
                                if isinstance(p, dict) and p.get("type") != "thinking")
        uso = d.get("usage") or {}
        dettagli = uso.get("prompt_tokens_details") or {}
        return RispostaTesto(
            testo=contenuto or "",
            token_entrata=int(uso.get("prompt_tokens") or 0),
            token_uscita=int(uso.get("completion_tokens") or 0),
            modello=d.get("model") or modello,
            troncata=d["choices"][0].get("finish_reason") == "length",
            token_cache=int((dettagli.get("cached_tokens") if isinstance(dettagli, dict) else 0) or 0),
        )
