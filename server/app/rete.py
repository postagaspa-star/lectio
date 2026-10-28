"""Chiamate ai fornitori esterni (Mistral, Azure): un tentativo alla volta, e frasi d'errore.

Ogni funzione dei fornitori fa UN solo tentativo; i ritenti li decide la Cassa (spesa.py),
che prima di ogni tentativo ricontrolla il tetto. Per decidere serve sapere se il
fornitore ha lavorato (e quindi fatto pagare):

- NonElaborata: di sicuro non ha lavorato (connessione mai aperta, 429 "troppe
  richieste", 503 "occupato", 408/409/425). Si ritenta senza contare niente.
- ForseElaborata: non si sa (tempo scaduto mentre aspettavamo la risposta, connessione
  chiusa a metà, 500/502/504). La stima resta nel conto come spesa, poi si ritenta solo
  se il tetto lo permette ancora.
- ErroreFornitore: errore definitivo con risposta chiara (chiave rifiutata, credito finito,
  richiesta rifiutata): niente lavoro fatto, niente ritenti.

Le chiavi non finiscono mai nei messaggi né nei log."""

import logging

import httpx

log = logging.getLogger("uvicorn.error")

ATTESE_SECONDI = (2, 4, 8)


class ErroreFornitore(Exception):
    """Un errore che finisce davanti ad Andrea: `frase` è già in italiano semplice."""

    def __init__(self, frase: str, temporaneo: bool = False, elaborata: bool = False):
        super().__init__(frase)
        self.frase = frase
        self.temporaneo = temporaneo
        # True se il fornitore ha lavorato comunque (per esempio ha risposto senza audio).
        self.elaborata = elaborata


class Temporaneo(Exception):
    def __init__(self, nome: str, motivo: str):
        super().__init__(f"{nome}: {motivo}")
        self.nome = nome
        self.frase = f"{nome} non risponde in questo momento. Riprova tra qualche minuto."


class NonElaborata(Temporaneo):
    """Il fornitore di sicuro non ha lavorato: si può ritentare senza pagare."""


class ForseElaborata(Temporaneo):
    """Non si sa se il fornitore ha lavorato: si conta la stima come spesa."""


def controlla(r: httpx.Response, nome: str) -> httpx.Response:
    """Trasforma una risposta non riuscita nell'errore giusto."""
    if 200 <= r.status_code < 300:
        return r
    dettaglio = r.text[:300] if r.text else ""
    log.warning("%s ha risposto %s: %s", nome, r.status_code, dettaglio)
    if r.status_code in (408, 409, 425, 429, 503):
        raise NonElaborata(nome, str(r.status_code))
    if r.status_code >= 500:
        raise ForseElaborata(nome, str(r.status_code))
    if r.status_code in (401, 403):
        if "quota" in dettaglio.lower() and nome == "Azure":
            from .spesa import VoceGratisFinita

            raise VoceGratisFinita()
        raise ErroreFornitore(f"{nome} non accetta la chiave: rimettila con imposta-chiave sul NAS.")
    if r.status_code == 402:
        raise ErroreFornitore(f"Il credito di {nome} è finito: ricaricalo sul sito di {nome}, poi tocca Riprova.")
    if r.status_code == 404:
        raise ErroreFornitore(f"{nome} non trova il modello o la voce chiesti: controlla i nomi nel file .env.")
    if r.status_code == 413:
        raise ErroreFornitore(f"Il file è troppo grande per {nome}.")
    raise ErroreFornitore(f"{nome} ha rifiutato la richiesta. Riprova; se succede ancora, prova con meno materiale.")


def invia(nome: str, fare):
    """Esegue `fare()` (una richiesta httpx) e traduce gli errori di rete."""
    try:
        return fare()
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout) as e:
        # La richiesta non è mai arrivata al fornitore.
        raise NonElaborata(nome, type(e).__name__) from None
    except (httpx.TimeoutException, httpx.TransportError) as e:
        # Partita ma senza risposta completa: il fornitore potrebbe aver già lavorato.
        raise ForseElaborata(nome, type(e).__name__) from None
