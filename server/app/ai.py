"""Una richiesta JSON a chi scrive, con il conto e la correzione.

1. Il tetto si controlla prima di ogni tentativo (Cassa.chiama), con la stima peggiore (tutti
   i token di uscita permessi); la spesa si registra con i token veri restituiti dal fornitore.
2. La risposta deve essere un oggetto JSON che passa la validazione. Se no: UNA seconda
   richiesta che spiega cosa non va; se anche quella non va, errore chiaro."""

import json
import logging
import re

from . import misure, prompt

log = logging.getLogger("uvicorn.error")

FRASE_NON_USABILE = "L'AI ha risposto in un modo che non riesco a usare, anche dopo averle chiesto di correggersi. Tocca Riprova."


class RispostaNonUsabile(Exception):
    def __init__(self, frase: str = FRASE_NON_USABILE):
        super().__init__(frase)
        self.frase = frase


def estrai_json(testo: str) -> dict | None:
    t = (testo or "").strip()
    t = re.sub(r"^```(?:json)?\s*", "", t)
    t = re.sub(r"\s*```$", "", t)
    for candidato in (t, t[t.find("{"): t.rfind("}") + 1] if "{" in t and "}" in t else ""):
        if not candidato:
            continue
        try:
            oggetto = json.loads(candidato)
        except ValueError:
            continue
        if isinstance(oggetto, dict):
            return oggetto
    return None


def chiedi_json(*, cassa, fornitore, compito: str, messaggi: list[dict], modello: str, valida, max_token: int,
                cosa: str, dati: dict | None = None, lezione_id: int | None = None, materia_id: int | None = None,
                materiale_id: int | None = None, prima_di_ogni_chiamata=None, costi: list | None = None) -> dict:
    """`valida(oggetto) -> list[str]` restituisce gli errori (lista vuota = va bene).
    Se si passa `costi`, ci finisce il costo vero di ogni chiamata fatta."""
    for tentativo in (1, 2):
        stima, token_stimati = 0.0, 0
        if fornitore.a_pagamento:
            caratteri = sum(len(m["content"]) for m in messaggi)
            token_stimati = int(caratteri / misure.CARATTERI_PER_TOKEN) + 50 + max_token
            stima = cassa.prezzi.costo_testo(modello, token_stimati - max_token, max_token)
        etichetta = cosa if tentativo == 1 else f"{cosa} (correzione)"

        def registra(ricevuta, risposta):
            if fornitore.a_pagamento:
                costo = cassa.prezzi.costo_testo(modello, risposta.token_entrata, risposta.token_uscita)
                ricevuta.registra(risposta.token_entrata + risposta.token_uscita, "token", costo)
                if costi is not None:
                    costi.append(costo)

        risposta = cassa.chiama(
            cosa=etichetta, fornitore=fornitore.nome, stima_eur=stima, quantita_stimata=token_stimati, unita="token",
            lezione_id=lezione_id, materia_id=materia_id, materiale_id=materiale_id, prima=prima_di_ogni_chiamata,
            fare=lambda: fornitore.genera(compito=compito, messaggi=messaggi, modello=modello, json_atteso=True,
                                          max_token=max_token, dati={**(dati or {}), "tentativo": tentativo}),
            registra=registra,
        )
        oggetto = estrai_json(risposta.testo)
        errori = ["non è un oggetto JSON"] if oggetto is None else valida(oggetto)
        if not errori:
            return oggetto
        log.warning("%s: risposta non valida (tentativo %s): %s", cosa, tentativo, "; ".join(errori[:5]))
        messaggi = messaggi + [
            {"role": "assistant", "content": (risposta.testo or "")[:30000]},
            {"role": "user", "content": prompt.correzione(errori)},
        ]
    raise RispostaNonUsabile()
