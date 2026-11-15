"""Chi scrive i testi: OpenAI o Mistral (chat) oppure, in modalità prova, il generatore finto
(finto.py). Stessa interfaccia, sostituibili:

    genera(compito=..., messaggi=[{"role","content"}], modello=..., json_atteso=True,
           max_token=..., dati={...}) -> RispostaTesto

`compito` e `dati` servono solo al generatore finto (per usare davvero i materiali senza
leggere i prompt); OpenAI e Mistral li ignorano. Ogni chiamata è UN tentativo: i ritenti li decide la
Cassa (spesa.py), che ricontrolla il tetto prima di ognuno.

Scrittori (1.3.9) mette insieme OpenAI e Mistral: per un modello di OpenAI la `catena` è
[OpenAI con quel modello, Mistral con la riserva]; ai.chiedi_json passa alla riserva se il primo non va."""

import logging
from dataclasses import dataclass

import httpx

from .config import MISTRAL_URL, OPENAI_URL, di_openai
from .rete import controlla, invia

log = logging.getLogger("uvicorn.error")


@dataclass
class RispostaTesto:
    testo: str
    token_entrata: int
    token_uscita: int
    modello: str
    troncata: bool = False  # finita perché era arrivata al massimo di token: il testo è a metà
    token_cache: int = 0  # quanti dei token d'entrata il fornitore ha preso dalla cache (si pagano il 10%)
    token_cache_scritti: int = 0  # quanti token d'entrata OpenAI ha messo in cache (si pagano 1,25 volte)


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


def _messaggi_per_openai(messaggi: list[dict], json_atteso: bool) -> list[dict]:
    """Le foto nel formato di OpenAI ({"url": ...} invece della sola stringa di Mistral). In modalità JSON OpenAI
    vuole la parola "JSON" da qualche parte nei messaggi: i prompt di Lectio ce l'hanno, ma se mancasse si aggiunge."""
    fuori = []
    for m in messaggi:
        contenuto = m.get("content")
        if isinstance(contenuto, list):
            pezzi = []
            for p in contenuto:
                if isinstance(p, dict) and p.get("type") == "image_url" and isinstance(p.get("image_url"), str):
                    p = {"type": "image_url", "image_url": {"url": p["image_url"]}}
                pezzi.append(p)
            m = {**m, "content": pezzi}
        fuori.append(m)
    if json_atteso:
        testi = " ".join(m["content"] if isinstance(m.get("content"), str)
                         else " ".join(str(p.get("text") or "") for p in m.get("content") or [] if isinstance(p, dict))
                         for m in fuori)
        if "json" not in testi.lower():
            fuori = [{"role": "system", "content": "Rispondi solo con un oggetto JSON."}] + fuori
    return fuori


class TestoOpenAI:
    nome = "openai"
    a_pagamento = True

    def __init__(self, chiave: str, transport: httpx.BaseTransport | None = None, timeout: float = 180.0,
                 url: str = OPENAI_URL):
        self._chiave = chiave
        self._transport = transport
        self._timeout = timeout
        self._url = url

    def genera(self, *, compito: str, messaggi: list[dict], modello: str, json_atteso: bool = True,
               max_token: int = 4000, temperatura: float = 0.4, dati: dict | None = None,
               ragionamento: str | None = None, chiave_cache: str | None = None) -> RispostaTesto:
        # max_completion_tokens comprende anche il ragionamento (ai.py lascia lo spazio in più).
        corpo = {"model": modello, "messages": _messaggi_per_openai(messaggi, json_atteso),
                 "max_completion_tokens": max_token}
        if ragionamento:
            corpo["reasoning_effort"] = ragionamento
        if not ragionamento or ragionamento == "none":
            corpo["temperature"] = temperatura  # con il ragionamento acceso OpenAI non la accetta
        if chiave_cache:
            corpo["prompt_cache_key"] = chiave_cache
        if json_atteso:
            corpo["response_format"] = {"type": "json_object"}

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/chat/completions", json=corpo,
                              headers={"Authorization": f"Bearer {self._chiave}"})

        r = controlla(invia("OpenAI", manda), "OpenAI")
        d = r.json()
        scelta = d["choices"][0]
        contenuto = (scelta.get("message") or {}).get("content")
        if isinstance(contenuto, list):
            contenuto = "".join(str(p.get("text") or "") for p in contenuto if isinstance(p, dict))
        uso = d.get("usage") or {}
        dettagli = uso.get("prompt_tokens_details") or {}
        if not isinstance(dettagli, dict):
            dettagli = {}
        return RispostaTesto(
            testo=contenuto or "",
            token_entrata=int(uso.get("prompt_tokens") or 0),
            # Comprende i token del ragionamento, che si pagano come uscita.
            token_uscita=int(uso.get("completion_tokens") or 0),
            modello=d.get("model") or modello,
            troncata=scelta.get("finish_reason") == "length",
            token_cache=int(dettagli.get("cached_tokens") or 0),
            token_cache_scritti=int(dettagli.get("cache_write_tokens") or 0),
        )


class Scrittori:
    """OpenAI per i suoi modelli, con la riserva su Mistral; Mistral da solo per i suoi."""

    a_pagamento = True

    def __init__(self, mistral: TestoMistral, openai: TestoOpenAI | None, riserva: str):
        self.mistral = mistral
        self.openai = openai
        self.riserva = riserva

    @property
    def nome(self) -> str:
        return self.openai.nome if self.openai else self.mistral.nome

    def catena(self, modello: str) -> list[tuple[object, str]]:
        """Chi provare, in ordine: (fornitore, modello)."""
        if di_openai(modello):
            primo = [(self.openai, modello)] if self.openai else []
            return primo + [(self.mistral, self.riserva)]
        return [(self.mistral, modello)]

    def genera(self, *, modello: str, **altro) -> RispostaTesto:
        fornitore, quale = self.catena(modello)[0]
        return fornitore.genera(modello=quale, **altro)
