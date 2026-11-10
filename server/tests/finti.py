"""Fornitori finti per i test: niente rete, niente chiavi vere.

- VoceFinta: al posto di espeak-ng, un WAV fatto in Python (durata proporzionale al testo).
- MontatoreFinto: al posto di ffmpeg, unisce i WAV con il modulo `wave` (stessi conti).
- ServerFinto: risponde come Mistral (chat, voce, OCR), Azure (elenco voci, voce) e le voci del
  NAS (/salute, /voce), dentro un httpx.MockTransport; conta le chiamate e può fallire a comando."""

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


def vettore_di(testo: str, dimensioni: int = 64) -> list[float]:
    """Un vettore che dipende dalle parole: testi con parole in comune risultano vicini."""
    v = [0.0] * dimensioni
    for p in re.findall(r"[a-zàèéìòù]{3,}", testo.lower()):
        v[sum(p.encode()) % dimensioni] += 1.0
    return v


def jpeg_di_prova() -> bytes:
    from PIL import Image

    buf = io.BytesIO()
    Image.new("RGB", (900, 700), (120, 90, 60)).save(buf, "JPEG")
    return buf.getvalue()


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
        self.titolo_forzato = None
        self.ultimo_prompt = ""
        self.azure_quota_finita = False
        self.voci_spente = False  # le voci del NAS non rispondono
        self.voci_rotte = 0  # quante risposte 500 dare prima di quelle buone
        self.fuori_coda: list[list] = []  # che cosa risponde il controllo dei fatti, una risposta per chiamata
        self.mancanti: list[str] = []  # i concetti che il controllo di copertura dice mancare
        self.tronca = 0  # quante risposte di chat dare tagliate a metà, come quando finiscono i token
        self.foto_buona = True
        self.piano_senza_foto = False
        self.chiarimento = None
        self.simulata = None
        self.vettori_rotti = False

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
        if percorso == "/v1/embeddings":
            if self.vettori_rotti:
                return httpx.Response(500, json={"message": "guasto finto"})
            return httpx.Response(200, json={"data": [{"index": i, "embedding": vettore_di(t)} for i, t in enumerate(dati["input"])],
                                             "usage": {"prompt_tokens": sum(len(t) // 4 for t in dati["input"])}})
        if percorso == "/w/api.php":
            pagine = {str(i): {"index": i, "title": f"File:Foto {i}.jpg", "imageinfo": [{
                "mime": "image/jpeg", "width": 1200, "thumburl": f"https://upload.wikimedia.org/foto/{i}.jpg",
                "url": f"https://upload.wikimedia.org/foto/{i}.jpg",
                "extmetadata": {"LicenseShortName": {"value": "CC BY-SA 4.0"}, "Artist": {"value": "Autore <b>Prova</b>"}}}]}
                for i in range(1, 7)}
            return httpx.Response(200, json={"query": {"pages": pagine}})
        if percorso.startswith("/foto/"):
            return httpx.Response(200, content=jpeg_di_prova())
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
        if percorso == "/salute":  # le voci del NAS
            if self.voci_spente:
                raise httpx.ConnectError("spento", request=richiesta)
            return httpx.Response(200, json={"ok": True, "motori": ["giovanni", "giovanni_grande", "supertonic"]})
        if percorso == "/voce":
            if self.voci_spente:
                raise httpx.ConnectError("spento", request=richiesta)
            if dati.get("motore") not in ("giovanni", "giovanni_grande", "supertonic"):
                return httpx.Response(400, json={"errore": "Questa voce non c'è."})
            if self.voci_rotte > 0:
                self.voci_rotte -= 1
                return httpx.Response(500, json={"errore": "La voce Giovanni non è riuscita a leggere un pezzo."})
            return httpx.Response(200, content=wav_veloce(len(dati["testo"]) * SECONDI_PER_CARATTERE),
                                  headers={"Content-Type": "audio/wav"})
        if percorso.endswith("/cognitiveservices/v1"):
            if self.azure_quota_finita:
                return httpx.Response(403, text="Quota exceeded: free tier characters for this month")
            testo = re.sub(r"<[^>]+>", "", corpo)
            return httpx.Response(200, content=wav_veloce(len(testo) * SECONDI_PER_CARATTERE))
        return httpx.Response(404, json={"message": "sconosciuto"})

    def _risposta_chat(self, testo: str, dati: dict, fine: str = "stop") -> httpx.Response:
        entrata = sum(len(m["content"]) for m in dati["messages"]) // 4
        return httpx.Response(200, json={
            "model": dati["model"],
            "choices": [{"message": {"role": "assistant", "content": testo}, "finish_reason": fine}],
            "usage": {"prompt_tokens": entrata, "completion_tokens": max(1, len(testo) // 4)},
        })

    def _chat(self, dati: dict) -> httpx.Response:
        if self.chat_rotta > 0:
            self.chat_rotta -= 1
            return self._risposta_chat("Ecco il piano: non è JSON", dati)
        if self.chat_forzata is not None:
            testo, self.chat_forzata = self.chat_forzata, None
            return self._risposta_chat(testo, dati)
        if self.tronca > 0:
            self.tronca -= 1
            return self._risposta_chat('{"titolo": "Augusto in breve", "parti": [{"titolo": "Il pot', dati, fine="length")
        primo = dati["messages"][1]["content"] if len(dati["messages"]) > 1 else ""
        if isinstance(primo, list):
            primo = primo[0]["text"]
        if "Qui sotto ci sono delle frasi numerate" not in primo:
            self.ultimo_prompt = primo
        ids = re.findall(r'<materiale id="([BV]\d+)"', primo)
        if "Qui sotto ci sono delle frasi numerate" in primo:
            oggetto = {"fuori": self.fuori_coda.pop(0) if self.fuori_coda else []}
        elif "Qui sotto ci sono i concetti" in primo:
            numerati = re.findall(r"^C(\d+)\. (.+)$", primo, re.MULTILINE)
            oggetto = {"mancanti": [f"C{n}" for n, testo in numerati if testo.strip() in self.mancanti]}
        elif "segna TUTTI i punti da sapere" in primo:
            oggetto = {"punti": [{"testo": "Il principato di Augusto", "fonti": ids[:1]},
                                 {"testo": "Il senato restò di facciata", "fonti": ids[:1]},
                                 {"testo": "Le legioni a Teutoburgo", "fonti": ids[:1]}]}
        elif re.search(r"Nella PARTE \d+ di \d+ dello schema mancano", primo):
            oggetto = {"blocchi": [{"tipo": "paragrafo", "titolo": None, "testo": "Le legioni furono sconfitte a Teutoburgo.",
                                    "fonti": ids[:1]}],
                       "carte": [{"domanda": "Dove furono sconfitte le legioni?", "risposta": "A Teutoburgo."}]}
        elif "Guarda la foto." in primo:
            oggetto = {"mostra": self.foto_buona, "cosa_si_vede": "il soggetto"}
        elif "Prepara il PIANO di uno schema di ripasso" in primo:
            oggetto = {"titolo": "Augusto sul quaderno", "forma": "capitoli",
                       "sezioni": [{"titolo": "Il principato", "punti": ["P1"]}, {"titolo": "Il senato", "punti": ["P2"]}],
                       "immagini": [{"sezione": 1, "soggetto": "Augusto", "ricerca": "Augusto statua", "didascalia": "Augusto"},
                                    {"sezione": 2, "soggetto": "Personaggio inventato", "ricerca": "nessuno", "didascalia": "No"}]}
            if self.piano_senza_foto:
                oggetto["immagini"] = []
        elif "Elenca le immagini" in primo:
            oggetto = {"immagini": [{"sezione": 1, "soggetto": "Augusto", "ricerca": "Augusto statua", "didascalia": "Augusto"}]}
        elif re.search(r"Scrivi la PARTE \d+ di \d+ dello schema", primo):
            oggetto = {"blocchi": [{"tipo": "paragrafo", "titolo": None, "testo": "Augusto tenne il potere vero.", "fonti": ids[:1]},
                                   {"tipo": "punti", "titolo": None, "voci": ["Il senato restò di facciata."], "fonti": ids[:1]},
                                   {"tipo": "sconosciuto", "testo": "non deve restare"}],
                       "carte": [{"domanda": "Chi tenne il potere vero?", "risposta": "Augusto."}]}
        elif "Riassumi in UNA tabella" in primo:
            oggetto = {"titolo": "Quadro d'insieme", "intestazione": ["Chi", "Cosa"], "righe": [["Augusto", "Il potere vero"]]}
        elif "Scrivi il riassunto di questo schema" in primo:
            parte: dict = {"titolo": "Il potere", "paragrafi": [], "punti": []}
            if "Forma: PER PUNTI" in primo:
                parte["punti"] = ["Augusto tenne il potere vero.", "Il senato restò di facciata."]
            elif "Forma: DISCORSIVO" in primo:
                parte["paragrafi"] = ["Augusto tenne il potere vero, mentre il senato restò di facciata."]
            else:
                parte["paragrafi"] = ["Augusto tenne il potere vero."]
                parte["punti"] = ["Il senato restò di facciata."]
            oggetto = {"titolo": "Augusto in breve", "parti": [parte]}
        elif "Lo studente non ha capito il passaggio" in primo:
            oggetto = self.chiarimento or {"trovato": True, "risposta": "Vuol dire che comandava Augusto.", "fonti": ids[:1], "cosa_manca": None}
        elif "Prepara una verifica di prova da" in primo:
            oggetto = self.simulata or {"titolo": "Verifica su Augusto", "domande": [
                {"tipo": "aperta", "testo": "Che cos'è il principato?", "punti": 60, "sezione": None,
                 "risposta_attesa": "Il governo di Augusto.", "fonti": ids[:1]},
                {"tipo": "date", "testo": "Quando morì Augusto?", "punti": 40, "sezione": None,
                 "risposta_attesa": "Nel 14 dopo Cristo.", "fonti": ids[:1]},
                {"tipo": "esercizio", "testo": "Risolvi 7x + 3 = 24", "punti": 10, "sezione": None, "risposta_attesa": "x = 3", "fonti": []},
            ]}
        elif "Correggi ogni risposta confrontandola" in primo:
            oggetto = {"domande": [{"n": int(n), "esito": "parziale", "punti": int(p) // 2, "commento": "Quasi.", "mancava": ["la data"]}
                                   for n, p in re.findall(r"Domanda (\d+) \(massimo (\d+) punti\)", primo)]}
        elif "Prepara il PIANO di un RIPASSO" in primo:
            m = re.search(r'esattamente (\d+) parti in "blocchi" e (\d+) domande', primo)
            n, q = int(m.group(1)), int(m.group(2))
            ids = re.findall(r'<materiale id="([BV]\d+)"', primo)
            oggetto = {
                "titolo": "Ripasso di Augusto",
                "minuti_di_materiale": 12,
                "blocchi": [{"titolo": f"Punti chiave {k}", "punti": [f"punto {k}"], "fonti": ids[:2]}
                            for k in range(1, n + 1)],
                "interrogazione": [{"testo": f"Domanda di ripasso {i}?", "tipo": "aperta", "fonti": ids[:1]}
                                   for i in range(1, q + 1)],
                "errori_da_evitare": ["Confondere Augusto con Cesare", "Dimenticare le date", "Non citare il senato"],
            }
        elif "Prepara il PIANO di una INTERROGAZIONE" in primo:
            m = re.search(r'gruppi in "blocchi", con ([\d, ]+) domande in "domande"', primo)
            gruppi = [int(x) for x in m.group(1).split(",")]
            ids = re.findall(r'<materiale id="([BV]\d+)"', primo)
            oggetto = {
                "titolo": "Interrogazione su Augusto",
                "minuti_di_materiale": 12,
                "blocchi": [{"titolo": f"Gruppo {k}", "punti": [f"punto {k}"], "fonti": ids[:2],
                             "domande": [{"testo": f"Domanda {k}.{i}?", "tipo": "aperta", "fonti": ids[:1]}
                                         for i in range(1, g + 1)]} for k, g in enumerate(gruppi, start=1)],
                "errori_da_evitare": ["Confondere Augusto con Cesare", "Dimenticare le date", "Non citare il senato"],
            }
        elif "Prepara il PIANO" in primo:
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
            oggetto = {"paragrafi": [{"tipo": "spiegazione", "testo": _parole(parole // 2)},
                                     {"tipo": "definizione", "testo": "Il principato è il governo del primo cittadino."},
                                     {"tipo": "esempio", "testo": _parole(parole // 3)}],
                       "domanda": "Perché Augusto non si fece mai chiamare re?",
                       "risposta": "Perché a Roma la parola re ricordava la tirannia."}
        elif "APERTURA" in primo:
            oggetto = {"paragrafi": [{"testo": "Oggi vediamo come Augusto cambiò Roma senza dirlo."}]}
        elif "PARTE FINALE dell'interrogazione" in primo:
            oggetto = {"errori": ["Non confondere Augusto e Cesare.", "Dai sempre le date.", "Cita il senato."],
                       "chiusura": "Rifai le domande sbagliate."}
        elif "Scrivi il GRUPPO" in primo:
            q = int(re.search(r"le (\d+) domande del gruppo", primo).group(1))
            parole = int(re.search(r"circa (\d+) parole", primo).group(1))
            oggetto = {"introduzione": "Adesso domande su Augusto.",
                       "domande": [{"testo": f"Domanda del gruppo {i}?", "risposta": _parole(parole, "risposta")}
                                   for i in range(1, q + 1)]}
        elif "Scrivi il titolo di questo materiale" in primo:
            oggetto = self.titolo_forzato or {"titolo": "Augusto e il principato"}
        elif "PARTE FINALE" in primo:
            q = int(re.search(r"le (\d+) domande dell'interrogazione", primo).group(1))
            oggetto = {"riepilogo": ["Ricapitoliamo: Augusto tenne il potere vero."],
                       "domande": [{"testo": f"Domanda finale {i}?", "risposta": f"Risposta finale {i}."}
                                   for i in range(1, q + 1)],
                       "errori": ["Non confondere Augusto e Cesare.", "Dai sempre le date.", "Cita il senato."],
                       "chiusura": "Per oggi è tutto."}
        elif "Scrivi la PARTE" in primo:
            parole = int(re.search(r"circa (\d+) parole", primo).group(1))
            oggetto = {"paragrafi": [{"tipo": "spiegazione", "testo": _parole(parole // 2)},
                                     {"tipo": "definizione", "testo": "Il principato è il governo del primo cittadino."},
                                     {"tipo": "esempio", "testo": _parole(parole // 2, "esempio")}]}
        elif "Studia come interroga" in primo:
            doc = next((i for i in ids if i.startswith("V")), "V1")
            oggetto = self.scheda_forzata or {
                "domande": [{"doc": doc, "testo": "Che cos'è il principato?", "tipo": "definizione", "argomento": "Principato"},
                            {"doc": doc, "testo": "Quando Ottaviano ricevette il titolo di Augusto?", "tipo": "date", "argomento": "Principato"}],
                "cosa_corregge": [{"frase": "Vuole le date precise", "prova": "Vuole le date precise con il secolo"}],
                "cosa_premia": [{"frase": "I collegamenti", "prova": "Buoni i collegamenti tra i fatti."}],
                "lunghezza_risposte": None, "consigli": [{"frase": "Parti dalla definizione", "prova": "Manca la definizione di princeps"}],
            }
        elif "Risposta attesa:" in primo:
            oggetto = self.correttore
        else:
            oggetto = {}
        return self._risposta_chat(json.dumps(oggetto, ensure_ascii=False), dati)

