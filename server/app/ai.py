"""Una richiesta JSON a chi scrive, con il conto e la correzione.

1. Il tetto si controlla prima di ogni tentativo (Cassa.chiama), con la stima peggiore (tutti
   i token di uscita permessi); la spesa si registra con i token veri restituiti dal fornitore.
2. La risposta deve essere un oggetto JSON che passa la validazione. Se no: UNA seconda
   richiesta che spiega cosa non va; se anche quella non va, errore chiaro.
3. Una risposta tagliata perché troppo lunga non si corregge: la seconda richiesta è la stessa
   di prima, con il doppio dello spazio."""

import json
import logging
import re

from . import misure, prompt
from .config import RAGIONAMENTO

log = logging.getLogger("uvicorn.error")

# Una foto piccola pesa come un testo di circa 1.500 token.
CARATTERI_PER_IMMAGINE = 5_000
TOKEN_USCITA_MASSIMI = 16_000
# Spazio in più per chi ragiona prima di rispondere: almeno quanto la risposta (l'11/11/2026 Large 4
# ha ragionato più di 2.000 token su una parte di schema da 1.800).
RAGIONAMENTO_MINIMO = 3_000


def spazio_ragionamento(modello: str, max_token: int) -> int:
    return max(RAGIONAMENTO_MINIMO, max_token) if RAGIONAMENTO.get(modello) else 0
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
                materiale_id: int | None = None, prima_di_ogni_chiamata=None, costi: list | None = None,
                riferimento: str | None = None) -> dict:
    """`valida(oggetto) -> list[str]` restituisce gli errori (lista vuota = va bene).
    Se si passa `costi`, ci finisce il costo vero di ogni chiamata fatta."""
    for tentativo in (1, 2):
        stima, token_stimati = 0.0, 0
        # Il ragionamento conta nei token di uscita: entra nel massimo chiesto e nella stima per il tetto.
        uscita = max_token + spazio_ragionamento(modello, max_token)
        if fornitore.a_pagamento:
            caratteri = sum(len(m["content"]) if isinstance(m["content"], str) else CARATTERI_PER_IMMAGINE
                            for m in messaggi)
            token_stimati = int(caratteri / misure.CARATTERI_PER_TOKEN) + 50 + uscita
            stima = cassa.prezzi.costo_testo(modello, token_stimati - uscita, uscita)
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
            riferimento=riferimento,
            fare=lambda: fornitore.genera(compito=compito, messaggi=messaggi, modello=modello, json_atteso=True,
                                          max_token=uscita, dati={**(dati or {}), "tentativo": tentativo},
                                          ragionamento=RAGIONAMENTO.get(modello)),
            registra=registra,
        )
        oggetto = estrai_json(risposta.testo)
        errori = ["non è un oggetto JSON"] if oggetto is None else valida(oggetto)
        if not errori:
            return oggetto
        log.warning("%s: risposta non valida (tentativo %s): %s", cosa, tentativo, "; ".join(errori[:5]))
        if oggetto is None and getattr(risposta, "troncata", False) and max_token < TOKEN_USCITA_MASSIMI:
            log.warning("%s: risposta tagliata a %s token, la richiedo con più spazio", cosa, max_token)
            max_token = min(TOKEN_USCITA_MASSIMI, max_token * 2)
            continue
        messaggi = messaggi + [
            {"role": "assistant", "content": (risposta.testo or "")[:30000]},
            {"role": "user", "content": prompt.correzione(errori)},
        ]
    raise RispostaNonUsabile()
