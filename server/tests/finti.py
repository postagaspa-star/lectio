"""Fornitori finti per i test: niente rete, niente chiavi vere.

- VoceFinta: al posto di espeak-ng, un WAV fatto in Python (durata proporzionale al testo).
- MontatoreFinto: al posto di ffmpeg, unisce i WAV con il modulo `wave` (stessi conti).
- ServerFinto: risponde come Mistral (chat, voce, OCR) e Azure (elenco voci, voce), dentro
  un httpx.MockTransport; conta le chiamate e può fallire a comando."""

import base64
import io
import json
import math
import re
import shutil
import struct
import wave
from pathlib import Path

import httpx

from app.audio import FREQUENZA, Montatore
from app.voci import Audio, senza_segni

SECONDI_PER_CARATTERE = 1 / 60


def wav_di(secondi: float, frequenza: int = FREQUENZA, tono: float = 220.0) -> bytes:
    n = max(1, int(secondi * frequenza))
    campioni = b"".join(struct.pack("<h", int(3000 * math.sin(2 * math.pi * tono * i / frequenza))) for i in range(n))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(frequenza)
        w.writeframes(campioni)
    return buf.getvalue()


def wav_veloce(secondi: float) -> bytes:
    """Silenzio (più rapido da creare del tono) per i test che non ascoltano."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(FREQUENZA)
        w.writeframes(b"\x01\x00" * max(1, int(secondi * FREQUENZA)))
    return buf.getvalue()


class VoceFinta:
    fornitore = "prova"
    tipo = "prova"
    a_pagamento = False
    parallelo = 2

    def __init__(self):
        self.chiamate: list[str] = []

    def disponibile(self) -> bool:
        return True

    def caratteri(self, testo, emozione, lento) -> int:
        return len(senza_segni(testo))

    def sintetizza(self, testo, *, emozione=None, lento=False, voce=None) -> Audio:
        self.chiamate.append(testo)
        return Audio(wav_veloce(len(testo) * SECONDI_PER_CARATTERE), "wav", len(testo))


class MontatoreFinto(Montatore):
    """Stessi conti del montatore vero, senza ffmpeg: i pezzi sono già WAV 24 kHz mono."""

    def disponibile(self) -> bool:
        return True

    def in_wav(self, sorgente: Path, destinazione: Path) -> None:
        shutil.copyfile(sorgente, destinazione)

    def durata(self, percorso: Path) -> float:
        with wave.open(str(percorso), "rb") as w:
            return w.getnframes() / w.getframerate()

    def codifica(self, wav: Path, mp3: Path) -> None:
        shutil.copyfile(wav, mp3)


# --------------------------------------------------------------------------- server finto

def _parole(n: int, seme: str = "spiegazione") -> str:
    base = ("Augusto governò Roma con prudenza e lasciò al senato un ruolo di facciata mentre teneva per sé "
            "il comando dell esercito e le province più ricche").split()
    uscita = []
    while len(uscita) < n:
        uscita.extend(base)
    frasi, attuale = [], []
    for p in uscita[:n]:
        attuale.append(p)
        if len(attuale) == 12:
            frasi.append(" ".join(attuale) + ".")
            attuale = []
    if attuale:
        frasi.append(" ".join(attuale) + ".")
    return " ".join(frasi)


class ServerFinto:
    def __init__(self):
        self.chiamate: list[tuple[str, dict | str]] = []
        self.guasti: dict[str, list[int]] = {}  # percorso → codici da restituire, uno per chiamata
        self.chat_rotta = 0  # quante risposte di chat non valide dare prima di quelle buone
        self.chat_forzata = None  # un testo fisso per la prossima chat
        self.scheda_forzata = None
        self.correttore = {"esito": "parziale", "punteggio": 0.5,
                           "commento": "Giusto il senato. Manca chi governava al posto suo.",
                           "mancava": ["il ruolo di Seiano"]}
        self.ocr_pagine = None
        self.azure_quota_finita = False

    def conta(self, pezzo: str) -> int:
        return sum(1 for percorso, _ in self.chiamate if pezzo in percorso)

    def trasporto(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.gestisci)

    def gestisci(self, richiesta: httpx.Request) -> httpx.Response:
        percorso = richiesta.url.path
        corpo = richiesta.content.decode("utf-8", "replace")
        try:
            dati = json.loads(corpo) if corpo else {}
        except ValueError:
            dati = corpo
        self.chiamate.append((percorso, dati))
        coda = self.guasti.get(percorso)
        if coda:
            codice = coda.pop(0)
            return httpx.Response(codice, json={"message": "guasto finto"})
        if percorso == "/v1/chat/completions":
            return self._chat(dati)
        if percorso == "/v1/audio/speech":
            testo = dati["input"]
            audio = wav_veloce(len(testo) * SECONDI_PER_CARATTERE)
            return httpx.Response(200, json={"audio_data": base64.b64encode(audio).decode()})
        if percorso == "/v1/ocr":
            pagine = self.ocr_pagine or 1
            return httpx.Response(200, json={"pages": [{"index": i, "markdown": f"Testo letto pagina {i + 1}. "
                                                        "Augusto fu il primo imperatore di Roma."} for i in range(pagine)],
                                             "usage_info": {"pages_processed": pagine}})
        if percorso.endswith("/voices/list"):
            return httpx.Response(200, json=[
                {"ShortName": "it-IT-DiegoNeural", "DisplayName": "Diego", "Gender": "Male", "Locale": "it-IT"},
                {"ShortName": "it-IT-IsabellaNeural", "DisplayName": "Isabella", "Gender": "Female", "Locale": "it-IT"},
                {"ShortName": "it-IT-IsabellaMultilingualNeural", "DisplayName": "Isabella Multilingual",
                 "Gender": "Female", "Locale": "it-IT"},
                {"ShortName": "en-US-AvaNeural", "DisplayName": "Ava", "Gender": "Female", "Locale": "en-US"},
            ])
        if percorso.endswith("/cognitiveservices/v1"):
            if self.azure_quota_finita:
                return httpx.Response(403, text="Quota exceeded: free tier characters for this month")
            testo = re.sub(r"<[^>]+>", "", corpo)
            return httpx.Response(200, content=wav_veloce(len(testo) * SECONDI_PER_CARATTERE))
        return httpx.Response(404, json={"message": "sconosciuto"})

    def _risposta_chat(self, testo: str, dati: dict) -> httpx.Response:
        entrata = sum(len(m["content"]) for m in dati["messages"]) // 4
        return httpx.Response(200, json={
            "model": dati["model"],
            "choices": [{"message": {"role": "assistant", "content": testo}}],
            "usage": {"prompt_tokens": entrata, "completion_tokens": max(1, len(testo) // 4)},
        })

    def _chat(self, dati: dict) -> httpx.Response:
        if self.chat_rotta > 0:
            self.chat_rotta -= 1
            return self._risposta_chat("Ecco il piano: non è JSON", dati)
        if self.chat_forzata is not None:
            testo, self.chat_forzata = self.chat_forzata, None
            return self._risposta_chat(testo, dati)
        primo = dati["messages"][1]["content"] if len(dati["messages"]) > 1 else ""
        if "Prepara il PIANO" in primo:
            m = re.search(r"esattamente (\d+) blocchi e (\d+) domande", primo)
            n, q = int(m.group(1)), int(m.group(2))
            ids = re.findall(r'<materiale id="([BV]\d+)"', primo)
            oggetto = {
                "titolo": "Augusto e il principato",
                "minuti_di_materiale": 12,
                "blocchi": [{"titolo": f"Parte {k}", "punti": [f"punto {k}"], "fonti": ids[:2],
                             "domanda": {"testo": f"Domanda del blocco {k}?", "tipo": "aperta"}} for k in range(1, n + 1)],
                "interrogazione": [{"testo": f"Domanda finale {i}?", "tipo": "definizione", "fonti": ids[:1]}
                                   for i in range(1, q + 1)],
                "errori_da_evitare": ["Confondere Augusto con Cesare", "Dimenticare le date", "Non citare il senato"],
            }
        elif "Scrivi il BLOCCO" in primo:
            parole = int(re.search(r"circa (\d+) parole", primo).group(1))
            oggetto = {"paragrafi": [{"tipo": "aggancio", "testo": "Ti sei mai chiesto perché Augusto non volle la corona?"},
                                     {"tipo": "spiegazione", "testo": _parole(parole // 2)},
                                     {"tipo": "definizione", "testo": "Il principato è il governo del primo cittadino."},
                                     {"tipo": "esempio", "testo": _parole(parole // 3)}],
                       "domanda": "Perché Augusto non si fece mai chiamare re?",
                       "risposta": "Perché a Roma la parola re ricordava la tirannia."}
        elif "APERTURA" in primo:
            oggetto = {"paragrafi": [{"testo": "Oggi vediamo come Augusto cambiò Roma senza dirlo."}]}
        elif "PARTE FINALE" in primo:
            q = int(re.search(r"le (\d+) domande dell'interrogazione", primo).group(1))
            oggetto = {"riepilogo": ["Ricapitoliamo: Augusto tenne il potere vero."],
                       "domande": [{"testo": f"Domanda finale {i}?", "risposta": f"Risposta finale {i}."}
                                   for i in range(1, q + 1)],
                       "errori": ["Non confondere Augusto e Cesare.", "Dai sempre le date.", "Cita il senato."],
                       "chiusura": "Per oggi è tutto."}
        elif "Aggiorna la scheda" in primo:
            oggetto = self.scheda_forzata or {
                "argomenti": [{"nome": "Principato", "volte": 3, "esempio": "Che cos'è il principato?"}],
                "tipi_domanda": [{"tipo": "aperta", "quota": 0.7}, {"tipo": "date", "quota": 0.3}],
                "cosa_corregge": ["Vuole le date precise"], "cosa_premia": ["I collegamenti"],
                "lunghezza_risposte": "5-8 righe", "consigli": ["Parti dalla definizione"],
            }
        elif "Lo studente vuole una lezione su" in primo:
            ids = re.findall(r'<materiale id="(B\d+)"', primo)
            oggetto = {"scelti": ids[:3]}
        elif "Risposta attesa:" in primo:
            oggetto = self.correttore
        else:
            oggetto = {}
        return self._risposta_chat(json.dumps(oggetto, ensure_ascii=False), dati)

