"""Versione, salute, download, abbinamento e dispositivi (contratto, sezioni 2 e 3)."""

import hashlib
import json
import sqlite3
import threading
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, Header, Request
from fastapi.responses import FileResponse

from .. import abbina, clock, db
from ..auth import Dispositivo, dispositivo_del_token, richiede_token, token_da_header
from ..config import VERSIONE
from ..db import get_conn
from ..errori import ErroreApi, non_trovato
from ..schemi import AbbinaIn
from ..servizi import Servizi, servizi

pubblico = APIRouter()
protetto = APIRouter(dependencies=[Depends(richiede_token)])
scarica_router = APIRouter()

VERSIONI_DEFAULT = {
    "server": VERSIONE,
    "android": {"codice": 1, "nome": "1.0.0"},
    "pc": {"codice": 1, "nome": "1.0.0"},
    "nota": None,
}
FILE_SCARICABILI = {
    "lectio.apk": "application/vnd.android.package-archive",
    "lectio-pc.zip": "application/zip",
    "lectio-pc.zip.sig": "text/plain; charset=utf-8",  # la firma dello zip del PC
}
FILE_PER_APP = {"android": "lectio.apk", "pc": "lectio-pc.zip"}
SENZA_CACHE = {"Cache-Control": "no-store, max-age=0"}


def carica_versioni(percorso: str) -> dict:
    """Letto a ogni richiesta: si aggiorna il file senza riavviare. Se manca o è rotto,
    i valori di base."""
    try:
        with open(percorso, encoding="utf-8") as f:
            dati = json.load(f)
    except (OSError, ValueError):
        return json.loads(json.dumps(VERSIONI_DEFAULT))
    return dati if isinstance(dati, dict) else json.loads(json.dumps(VERSIONI_DEFAULT))


_impronte: dict[str, tuple[float, int, str]] = {}
_impronte_lock = threading.Lock()


def impronta(percorso: Path) -> tuple[str | None, int | None]:
    """sha256 e dimensione del file scaricabile, ricalcolata solo se il file è cambiato."""
    try:
        info = percorso.stat()
    except OSError:
        return None, None
    chiave = str(percorso)
    with _impronte_lock:
        nota = _impronte.get(chiave)
    if nota and nota[0] == info.st_mtime and nota[1] == info.st_size:
        return nota[2], info.st_size
    h = hashlib.sha256()
    with open(percorso, "rb") as f:
        for blocco in iter(lambda: f.read(1024 * 1024), b""):
            h.update(blocco)
    with _impronte_lock:
        _impronte[chiave] = (info.st_mtime, info.st_size, h.hexdigest())
    return h.hexdigest(), info.st_size


@pubblico.get("/versione")
def versione(request: Request):
    dati = carica_versioni(request.app.state.settings.versioni_path)
    cartella = Path(request.app.state.settings.apk_dir)
    for app, nome_file in FILE_PER_APP.items():
        voce = dati.get(app)
        if isinstance(voce, dict):
            voce["sha256"], voce["byte"] = impronta(cartella / nome_file)
    if isinstance(dati.get("pc"), dict):
        dati["pc"]["firma"] = (cartella / "lectio-pc.zip.sig").is_file()
    return dati


@pubblico.get("/salute")
def salute(
    authorization: Annotated[str | None, Header()] = None,
    conn: sqlite3.Connection = Depends(get_conn),
    s: Servizi = Depends(servizi),
):
    # server_id: sempre uguale per questo server. Le app lo confrontano prima di mandare il
    # token, per non darlo a un indirizzo che non è il server di casa.
    server_id = db.server_id(conn)
    if dispositivo_del_token(conn, token_da_header(authorization)) is None:
        return {"ok": True, "versione": VERSIONE, "server_id": server_id}
    st = s.settings
    prova = st.modalita_prova
    voce_prova_ok = s.catalogo.voce_prova.disponibile()
    mancano = []
    if not st.mistral_key:
        mancano.append("la chiave Mistral")
    if not st.azure_key:
        mancano.append("la chiave Azure")
    if not st.azure_regione:
        mancano.append("la regione Azure")
    # Frasi che finiscono a schermo: niente nomi di programmi o di file.
    if not s.ctx.montatore.disponibile():
        mancano.append("il programma che monta l'audio")
    if prova and not voce_prova_ok:
        mancano.append("la voce di prova")
    for uso in s.cassa.prezzi.mancanti(st):
        mancano.append(f"il prezzo del modello {uso}")
    if prova:
        testo = {"stato": "prova", "fornitore": "prova"}
        espressiva = ({"stato": "prova", "fornitore": "espeak"} if voce_prova_ok
                      else {"stato": "non_disponibile", "fornitore": "mistral"})
    else:
        testo = {"stato": "pronto", "fornitore": "mistral"}
        espressiva = {"stato": "pronta", "fornitore": "mistral"}
    gratis = {"stato": "pronta" if st.azure_pronta else "non_disponibile", "fornitore": "azure"}
    in_lavoro = conn.execute(
        "SELECT COUNT(*) FROM lezioni WHERE stato IN ('in_coda', 'in_preparazione')").fetchone()[0]
    return {
        "ok": True,
        "versione": VERSIONE,
        "server_id": server_id,
        "indirizzo_fuori_casa": st.indirizzo_fuori_casa,
        "modalita_prova": prova,
        "testo": testo,
        "voce_espressiva": espressiva,
        "voce_gratis": gratis,
        "mancano": mancano,
        "coda": {"lezioni_in_preparazione": in_lavoro},
    }


@scarica_router.get("/scarica/{nome_file}")
def scarica(nome_file: str, request: Request):
    tipo = FILE_SCARICABILI.get(nome_file)
    percorso = Path(request.app.state.settings.apk_dir) / nome_file
    if tipo is None or not percorso.is_file():
        raise non_trovato("Questo file non è ancora disponibile.")
    return FileResponse(percorso, media_type=tipo, filename=nome_file, headers=SENZA_CACHE)


# --- abbinamento -----------------------------------------------------------------------


@pubblico.post("/abbina")
def abbina_dispositivo(corpo: AbbinaIn, request: Request, conn: sqlite3.Connection = Depends(get_conn)):
    ip = request.client.host if request.client else "sconosciuto"
    gruppo = request.app.state.servizi.origine.gruppo(ip)
    esito = abbina.abbina(conn, corpo.codice.strip(), corpo.nome.strip(), corpo.tipo, clock.now(), ip, gruppo)
    if esito == abbina.BLOCCATO:
        raise ErroreApi(429, "Troppi codici sbagliati. Aspetta 15 minuti e chiedi un codice nuovo.")
    if esito is None:
        raise ErroreApi(401, "Il codice è sbagliato o scaduto. Chiedine uno nuovo.")
    token, dispositivo_id = esito
    return {"token": token, "dispositivo_id": dispositivo_id, "server_id": db.server_id(conn),
            "indirizzo_fuori_casa": request.app.state.settings.indirizzo_fuori_casa}


@protetto.get("/dispositivi")
def dispositivi(conn: sqlite3.Connection = Depends(get_conn)):
    return [
        {"id": d["id"], "nome": d["nome"], "tipo": d["tipo"], "abbinato_il": clock.per_utente(d["abbinato_il"]),
         "ultimo_accesso": clock.per_utente(d["ultimo_accesso"])}
        for d in conn.execute("SELECT * FROM dispositivi ORDER BY id")
    ]


@protetto.delete("/dispositivi/{dispositivo_id}")
def revoca(dispositivo_id: int, conn: sqlite3.Connection = Depends(get_conn),
           chi: Dispositivo = Depends(richiede_token)):
    cur = conn.execute("DELETE FROM dispositivi WHERE id = ?", (dispositivo_id,))
    if cur.rowcount == 0:
        raise non_trovato("Dispositivo non trovato.")
    return {"ok": True}
