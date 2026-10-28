"""Limite al corpo delle richieste, contato mentre i byte arrivano.

Il Content-Length si può non mandare (invio "a pezzi"): per questo i byte si contano sul
flusso, e appena si supera il limite ci si ferma con un 413, senza leggere il resto.
- caricamento di un file (multipart verso …/materiale): 20 MB più la cornice del modulo;
- tutto il resto (JSON): 2 MB, abbondanti anche per 200.000 caratteri di appunti scritti."""

import json

from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import MASSIMO_BYTE_FILE

LIMITE_FILE = MASSIMO_BYTE_FILE + 1024 * 1024
LIMITE_JSON = 2 * 1024 * 1024
FRASE_FILE = "Il file è troppo grande: il massimo è 20 MB."
FRASE_JSON = "La richiesta è troppo grande."


class CorpoTroppoGrande(StarletteHTTPException):
    """Un HTTPException: FastAPI la lascia passare così com'è anche mentre legge il corpo."""

    def __init__(self, frase: str):
        super().__init__(status_code=413, detail=frase)


class LimiteCorpo:
    def __init__(self, app, limite_file: int = LIMITE_FILE, limite_json: int = LIMITE_JSON):
        self.app = app
        self.limite_file = limite_file
        self.limite_json = limite_json

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] in ("GET", "HEAD", "OPTIONS"):
            await self.app(scope, receive, send)
            return
        intestazioni = dict(scope.get("headers") or [])
        tipo = intestazioni.get(b"content-type", b"").decode("latin-1").lower()
        if tipo.startswith("multipart/form-data") and scope["path"].endswith("/materiale"):
            limite, frase = self.limite_file, FRASE_FILE
        else:
            limite, frase = self.limite_json, FRASE_JSON
        try:
            dichiarato = int(intestazioni.get(b"content-length", b"0") or 0)
        except ValueError:
            dichiarato = 0
        if dichiarato > limite:
            corpo = json.dumps({"errore": frase, "codice": "troppo_grande"}).encode("utf-8")
            await send({"type": "http.response.start", "status": 413,
                        "headers": [(b"content-type", b"application/json"),
                                    (b"content-length", str(len(corpo)).encode()), (b"connection", b"close")]})
            await send({"type": "http.response.body", "body": corpo})
            return
        contati = 0

        async def ricevi():
            nonlocal contati
            messaggio = await receive()
            if messaggio["type"] == "http.request":
                contati += len(messaggio.get("body", b""))
                if contati > limite:
                    raise CorpoTroppoGrande(frase)
            return messaggio

        await self.app(scope, ricevi, send)
