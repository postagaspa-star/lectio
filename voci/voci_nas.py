"""Le voci gratis di Lectio, sul NAS (contratto 16.5).

Un servizio piccolo e interno: lo chiama solo il server di Lectio, dentro la rete dei container
(nessuna porta verso fuori). Tre voci, tutte senza internet e senza costi:

- giovanni:        Kyutai Pocket TTS, modello italiano normale (il programma senza Python);
- giovanni_grande: Kyutai Pocket TTS, modello italiano da 24 strati (più curato, più lento);
- supertonic:      Supertonic 3, voce maschile M1 (ONNX, caricata una volta e liberata dopo 15
                   minuti senza lavoro, per lasciare la memoria alle altre app del NAS).

    GET  /salute  -> {"ok": true, "motori": [...]}
    POST /voce    {"motore": "giovanni", "testo": "..."} -> audio/wav

Una voce alla volta: il processore è uno e le lezioni si preparano una alla volta comunque."""

import gc
import io
import json
import logging
import os
import re
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("voci")

POCKET = os.environ.get("POCKET_TTS", "/opt/pocket-tts/pocket-tts")
MOTORI = {
    "giovanni": {"tipo": "pocket", "lingua": "italian", "voce": "giovanni"},
    "giovanni_grande": {"tipo": "pocket", "lingua": "italian_24l", "voce": "giovanni"},
    "supertonic": {"tipo": "supertonic", "voce": "M1"},
}
TESTO_MASSIMO = 4000
PEZZO_SUPERTONIC = 300  # caratteri: frasi intere, pezzi corti leggono meglio
SILENZIO_TRA_PEZZI = 0.25
LIBERA_DOPO_SECONDI = 15 * 60
SEGNI = re.compile(r"\[(?:emotion|pace)=[a-z_]+\]\s*", re.IGNORECASE)

_turno = threading.Lock()


class Errore(Exception):
    def __init__(self, frase: str, codice: int = 500):
        super().__init__(frase)
        self.frase = frase
        self.codice = codice


def pulisci(testo: str) -> str:
    return " ".join(SEGNI.sub("", str(testo or "")).split())


def dividi(testo: str, massimo: int = PEZZO_SUPERTONIC) -> list[str]:
    """Frasi intere, unite finché stanno nel massimo. Una frase troppo lunga si taglia alle virgole,
    poi agli spazi."""
    frasi = [f.strip() for f in re.split(r"(?<=[.!?;:])\s+", testo) if f.strip()]
    pezzi: list[str] = []
    for frase in frasi:
        parti = [frase]
        if len(frase) > massimo:
            parti, attuale = [], ""
            for parola in re.split(r"(?<=,)\s+|\s+", frase):
                if attuale and len(attuale) + 1 + len(parola) > massimo:
                    parti.append(attuale)
                    attuale = parola
                else:
                    attuale = f"{attuale} {parola}".strip()
            if attuale:
                parti.append(attuale)
        for p in parti:
            if pezzi and len(pezzi[-1]) + 1 + len(p) <= massimo:
                pezzi[-1] = f"{pezzi[-1]} {p}"
            else:
                pezzi.append(p)
    return pezzi


# --------------------------------------------------------------------------- Kyutai Pocket TTS


def pocket(testo: str, lingua: str, voce: str) -> bytes:
    with tempfile.TemporaryDirectory(prefix="voce-") as cartella:
        uscita = os.path.join(cartella, "voce.wav")
        r = subprocess.run([POCKET, "generate", "--language", lingua, "--voice", voce, "--text", testo,
                            "--output-path", uscita], capture_output=True, timeout=1800)
        if r.returncode != 0 or not os.path.exists(uscita):
            log.warning("pocket-tts non è riuscito: %s", r.stderr.decode("utf-8", "replace")[-500:])
            raise Errore("La voce Giovanni non è riuscita a leggere un pezzo.")
        with open(uscita, "rb") as f:
            return f.read()


# --------------------------------------------------------------------------- Supertonic 3


class Supertonic:
    def __init__(self):
        self._tts = None
        self._stili: dict[str, object] = {}
        self._ultimo_uso = 0.0
        self._lock = threading.Lock()
        threading.Thread(target=self._libera_quando_ferma, daemon=True).start()

    def _carica(self):
        if self._tts is None:
            from supertonic import TTS

            self._tts = TTS(auto_download=True)
            self._stili = {}
        return self._tts

    def _libera_quando_ferma(self) -> None:
        while True:
            time.sleep(60)
            with self._lock:
                if self._tts is not None and time.monotonic() - self._ultimo_uso > LIBERA_DOPO_SECONDI:
                    self._tts, self._stili = None, {}
                    gc.collect()
                    log.info("Supertonic liberato dalla memoria")

    def leggi(self, testo: str, voce: str) -> bytes:
        import numpy as np
        import soundfile as sf

        with self._lock:
            self._ultimo_uso = time.monotonic()
            tts = self._carica()
            stile = self._stili.get(voce) or tts.get_voice_style(voice_name=voce)
            self._stili[voce] = stile
            frequenza = int(getattr(tts, "sample_rate", 44100))
            silenzio = np.zeros(int(frequenza * SILENZIO_TRA_PEZZI), dtype=np.float32)
            parti = []
            for i, pezzo in enumerate(dividi(testo)):
                wav, _ = tts.synthesize(pezzo, voice_style=stile, lang="it")
                if i:
                    parti.append(silenzio)
                parti.append(np.asarray(wav, dtype=np.float32).reshape(-1))
            self._ultimo_uso = time.monotonic()
        if not parti:
            raise Errore("Non c'era testo da leggere.", 400)
        buf = io.BytesIO()
        sf.write(buf, np.concatenate(parti), frequenza, format="WAV", subtype="PCM_16")
        return buf.getvalue()


SUPERTONIC = None


def leggi(motore: str, testo: str) -> bytes:
    global SUPERTONIC
    m = MOTORI.get(motore)
    if m is None:
        raise Errore("Questa voce non c'è.", 400)
    testo = pulisci(testo)
    if not testo:
        raise Errore("Non c'era testo da leggere.", 400)
    if len(testo) > TESTO_MASSIMO:
        raise Errore("Il pezzo da leggere è troppo lungo.", 400)
    with _turno:
        if m["tipo"] == "pocket":
            return pocket(testo, m["lingua"], m["voce"])
        if SUPERTONIC is None:
            SUPERTONIC = Supertonic()
        return SUPERTONIC.leggi(testo, m["voce"])


# --------------------------------------------------------------------------- HTTP


class Gestore(BaseHTTPRequestHandler):
    def _json(self, codice: int, oggetto: dict) -> None:
        corpo = json.dumps(oggetto, ensure_ascii=False).encode("utf-8")
        self.send_response(codice)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(corpo)))
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):
        if self.path == "/salute":
            return self._json(200, {"ok": True, "motori": list(MOTORI), "pocket": os.path.exists(POCKET)})
        return self._json(404, {"errore": "Non trovato."})

    def do_POST(self):
        if self.path != "/voce":
            return self._json(404, {"errore": "Non trovato."})
        try:
            lunghezza = int(self.headers.get("Content-Length") or 0)
            if lunghezza <= 0 or lunghezza > 64 * 1024:
                raise Errore("Richiesta non valida.", 400)
            dati = json.loads(self.rfile.read(lunghezza).decode("utf-8"))
            if not isinstance(dati, dict):
                raise Errore("Richiesta non valida.", 400)
            inizio = time.monotonic()
            audio = leggi(str(dati.get("motore") or ""), str(dati.get("testo") or ""))
            log.info("%s: %s caratteri in %.1f s", dati.get("motore"), len(str(dati.get("testo"))),
                     time.monotonic() - inizio)
        except Errore as e:
            return self._json(e.codice, {"errore": e.frase})
        except (ValueError, UnicodeDecodeError):
            return self._json(400, {"errore": "Richiesta non valida."})
        except Exception:
            log.exception("errore inatteso")
            return self._json(500, {"errore": "La voce ha avuto un problema."})
        self.send_response(200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Content-Length", str(len(audio)))
        self.end_headers()
        self.wfile.write(audio)

    def log_message(self, formato, *argomenti):  # niente righe per ogni richiesta
        return


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # Precedenza bassa (vale anche per pocket-tts, che parte da qui): le voci usano tutto il processore
    # quando il NAS è libero, ma le altre app passano sempre davanti.
    try:
        os.nice(int(os.environ.get("PRECEDENZA", "10")))
    except (OSError, AttributeError, ValueError):
        pass
    porta = int(os.environ.get("PORTA", "8000"))
    log.info("voci di Lectio pronte sulla porta %s", porta)
    ThreadingHTTPServer(("0.0.0.0", porta), Gestore).serve_forever()


if __name__ == "__main__":
    main()
