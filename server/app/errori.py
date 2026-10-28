"""Errori nel formato del contratto (sezione 1): status HTTP + {"errore": frase, "codice": slug}.

Il campo `errore` è sempre una frase in italiano semplice; il nome tecnico del campo
sbagliato, quando serve alle app, va a parte in "campo". Anche gli errori inattesi
escono in JSON (500 errore_interno), mai in testo semplice."""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("uvicorn.error")

CODICI = {
    400: "non_valido",
    401: "non_autorizzato",
    402: "tetto_raggiunto",
    403: "non_autorizzato",
    404: "non_trovato",
    405: "non_trovato",
    409: "conflitto",
    413: "troppo_grande",
    415: "non_valido",
    416: "non_valido",
    422: "non_valido",
    429: "troppi_tentativi",
    500: "errore_interno",
    503: "fornitore_non_disponibile",
}

FRASI = {
    400: "La richiesta non va bene.",
    401: "Questo dispositivo non è abbinato o il suo accesso è stato revocato.",
    402: "Sforerebbe il tetto di spesa del mese.",
    403: "Questo dispositivo non è abbinato o il suo accesso è stato revocato.",
    404: "Non trovato.",
    405: "Non trovato.",
    409: "Non si può fare adesso.",
    413: "Il file è troppo grande: il massimo è 20 MB.",
    415: "Questo tipo di richiesta non va bene.",
    416: "La parte di file chiesta non esiste.",
    422: "Alcuni dati della richiesta non vanno bene.",
    429: "Troppi tentativi. Aspetta qualche minuto e riprova.",
    500: "Lectio ha avuto un problema interno. Riprova tra poco.",
    503: "Il servizio esterno non risponde. Riprova tra qualche minuto.",
}

# Frasi per i campi che le app mandano: nessun nome tecnico nella frase.
FRASI_CAMPI = {
    "codice": "Il codice di abbinamento non va bene.",
    "nome": "Il nome non può essere vuoto né più lungo di 40 caratteri.",
    "tipo": "Il tipo indicato non è tra quelli previsti.",
    "prof": "Il nome del prof può avere al massimo 40 caratteri.",
    "colore": "Questo colore non c'è. Scegli tra ambra, rosa, petrolio, viola, blu, verde, arancio e oliva.",
    "conferma": "Per cancellare serve la conferma esplicita.",
    "titolo": "Il titolo può avere al massimo 120 caratteri.",
    "data": "La data va scritta come AAAA-MM-GG, per esempio 2026-10-30.",
    "da": "La data va scritta come AAAA-MM-GG, per esempio 2026-10-30.",
    "testo": "Il testo non può essere vuoto.",
    "argomento": "L'argomento può avere al massimo 200 caratteri.",
    "materia_id": "La materia indicata non va bene.",
    "evento_id": "La verifica indicata non va bene.",
    "materiali": "L'elenco del materiale non va bene.",
    "durata_min": "La durata deve essere tra 15 e 60 minuti.",
    "voce": "La voce indicata non va bene.",
    "posizione_s": "La posizione deve essere un numero di secondi da 0 in su.",
    "finita": "Va indicato se la lezione è finita (sì o no).",
    "aggiornato_il": "L'ora dell'aggiornamento non va bene.",
    "domande_uscite": "L'elenco delle domande uscite non va bene.",
    "altre_domande": "Le altre domande possono essere lunghe al massimo 4000 caratteri.",
    "voto": "Il voto può avere al massimo 20 caratteri.",
    "tetto_eur": "Il tetto del mese deve essere tra 0,50 e 10 euro.",
    "voce_predefinita": "La voce predefinita non va bene.",
    "durata_predefinita": "La durata predefinita deve essere tra 15 e 60 minuti.",
    "fermati_alle_domande": "Va indicato se fermarsi alle domande (sì o no).",
    "velocita": "La velocità deve essere tra 0,5 e 2.",
    "argomenti": "Gli argomenti della scheda non vanno bene.",
    "tipi_domanda": "I tipi di domanda della scheda non vanno bene.",
    "cosa_corregge": "L'elenco «cosa corregge» non va bene.",
    "cosa_premia": "L'elenco «cosa premia» non va bene.",
    "lunghezza_risposte": "La lunghezza delle risposte può avere al massimo 200 caratteri.",
    "consigli": "L'elenco dei consigli non va bene.",
    "note_mie": "Le note possono essere lunghe al massimo 5000 caratteri.",
    "dopo_id": "Il numero da cui leggere le novità non va bene.",
    "file": "Manca il file.",
    "risposte_scritte": "Va indicato se scrivere le risposte (sì o no).",
    "saltata": "Va indicato se la domanda è saltata (sì o no).",
    "domande_saltabili": "Va indicato se le domande si possono saltare (sì o no).",
}


class ErroreApi(Exception):
    def __init__(self, status: int, errore: str, codice: str | None = None, campo: str | None = None,
                 extra: dict | None = None):
        super().__init__(errore)
        self.status = status
        self.errore = errore
        self.codice = codice or CODICI.get(status, "errore_interno")
        self.campo = campo
        self.extra = extra or {}


def non_trovato(cosa: str = "Non trovato.") -> ErroreApi:
    return ErroreApi(404, cosa)


def non_valido(frase: str, campo: str | None = None) -> ErroreApi:
    return ErroreApi(422, frase, campo=campo)


def conflitto(frase: str) -> ErroreApi:
    return ErroreApi(409, frase)


def tetto(frase: str) -> ErroreApi:
    return ErroreApi(402, frase)


def risposta(status: int, errore: str, codice: str | None = None, campo: str | None = None,
             extra: dict | None = None) -> JSONResponse:
    contenuto = {"errore": errore, "codice": codice or CODICI.get(status, "errore_interno")}
    if campo:
        contenuto["campo"] = campo
    contenuto.update(extra or {})
    return JSONResponse(status_code=status, content=contenuto)


def _campo(loc) -> str:
    parti = [str(p) for p in loc if p not in ("body", "query", "path") and not isinstance(p, int)]
    return parti[0] if parti else ""


async def gestore_inatteso(request: Request, e: Exception) -> JSONResponse:
    log.exception("errore inatteso su %s %s", request.method, request.url.path)
    return risposta(500, FRASI[500])


def registra(app: FastAPI) -> None:
    @app.exception_handler(ErroreApi)
    async def _errore_api(request: Request, e: ErroreApi):
        return risposta(e.status, e.errore, e.codice, e.campo, e.extra)

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, e: StarletteHTTPException):
        frase = e.detail if isinstance(e.detail, str) and e.status_code not in (404, 405, 400) else None
        return risposta(e.status_code, frase or FRASI.get(e.status_code, "Qualcosa non è andato."))

    @app.exception_handler(RequestValidationError)
    async def _validazione(request: Request, e: RequestValidationError):
        campi = [c for c in (_campo(err.get("loc", ())) for err in e.errors()) if c]
        campo = campi[0] if campi else None
        frase = FRASI_CAMPI.get(campo, FRASI[422]) if campo else FRASI[422]
        return risposta(422, frase, campo=campo)

    @app.exception_handler(OverflowError)
    async def _numero(request: Request, e: OverflowError):
        return risposta(422, "Un numero della richiesta è troppo grande.")

    # Ultima rete: qualsiasi errore non previsto diventa un 500 in JSON leggibile.
    app.add_exception_handler(Exception, gestore_inatteso)
