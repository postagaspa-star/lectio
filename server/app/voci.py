"""Le voci (contratto, sezioni 8 e 9.1), dietro una stessa interfaccia:

    sintetizza(testo, emozione=..., lento=..., voce=...) -> Audio

- Mistral Voxtral (voce espressiva, a pagamento): i segni [emotion=…] e [pace=slow] vanno
  all'inizio del pezzo; costa per carattere inviato.
- Azure (voce gratis fino a 500.000 caratteri al mese): SSML, i segni si tolgono e
  [pace=slow] diventa <prosody rate="-10%">. Il piano F0 accetta circa 20 richieste al
  minuto: si va una alla volta con una pausa minima.
- Voce di prova (espeak-ng, robotica, dichiarata): solo in modalità prova, gratis."""

import base64
import logging
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass
from xml.sax.saxutils import escape

import httpx

from .config import MISTRAL_URL
from .rete import ErroreFornitore, controlla, invia

log = logging.getLogger("uvicorn.error")

EMOZIONI = ("warm", "curious", "confident", "calm", "determined")
SEGNI = re.compile(r"\[(?:emotion|pace)=[a-z_]+\]\s*", re.IGNORECASE)
AZURE_PREDEFINITA = "it-IT-IsabellaNeural"
AZURE_ELENCO_SECONDI = 24 * 3600
AZURE_ELENCO_RIPROVA_SECONDI = 600  # se l'elenco non arriva, si riprova dopo 10 minuti, non a ogni richiesta


@dataclass
class Audio:
    contenuto: bytes
    estensione: str  # "mp3" o "wav"
    caratteri: int  # quelli che contano per il costo o per il limite gratis


def senza_segni(testo: str) -> str:
    return SEGNI.sub("", testo).strip()


class VoceMistral:
    fornitore = "mistral"
    tipo = "espressiva"
    a_pagamento = True
    parallelo = 3

    def __init__(self, chiave: str, modello: str, voce_id: str, transport: httpx.BaseTransport | None = None,
                 timeout: float = 180.0, url: str = MISTRAL_URL):
        self._chiave = chiave
        self.modello = modello
        self.voce_id = voce_id
        self._transport = transport
        self._timeout = timeout
        self._url = url

    @staticmethod
    def con_segni(testo: str, emozione: str | None, lento: bool) -> str:
        segni = ""
        if emozione in EMOZIONI:
            segni += f"[emotion={emozione}]"
        if lento:
            segni += "[pace=slow]"
        return f"{segni} {testo}".strip() if segni else testo

    def caratteri(self, testo: str, emozione: str | None, lento: bool) -> int:
        return len(self.con_segni(testo, emozione, lento))

    def sintetizza(self, testo: str, *, emozione: str | None = None, lento: bool = False,
                   voce: str | None = None) -> Audio:
        entrata = self.con_segni(senza_segni(testo), emozione, lento)
        corpo = {"model": self.modello, "input": entrata, "voice_id": voce or self.voce_id, "response_format": "mp3"}

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._url}/audio/speech", json=corpo, headers={"Authorization": f"Bearer {self._chiave}"})

        r = controlla(invia("Mistral", manda), "Mistral")
        dati = r.json().get("audio_data")
        if not dati:
            # Ha risposto: potrebbe aver fatto pagare. La stima resta nel conto.
            raise ErroreFornitore("Mistral ha risposto senza audio. Tocca Riprova.", elaborata=True)
        return Audio(base64.b64decode(dati), "mp3", len(entrata))


class VoceAzure:
    fornitore = "azure"
    tipo = "gratis"
    a_pagamento = False
    parallelo = 1

    def __init__(self, chiave: str, regione: str, transport: httpx.BaseTransport | None = None, dormi=time.sleep,
                 orologio=time.monotonic, timeout: float = 60.0):
        self._chiave = chiave
        self._regione = regione
        self._transport = transport
        self._dormi = dormi
        self._orologio = orologio
        self._timeout = timeout
        self._elenco: list[dict] | None = None
        self._elenco_il = 0.0
        self._lock = threading.Lock()
        self._ultima = 0.0
        self.pausa_minima = 3.1  # piano F0: circa 20 richieste al minuto

    @property
    def _base(self) -> str:
        return f"https://{self._regione}.tts.speech.microsoft.com/cognitiveservices"

    def elenco(self) -> list[dict]:
        """Le voci italiane dall'elenco ufficiale, tenute in memoria per un giorno."""
        ora = self._orologio()
        if self._elenco is not None and ora < self._elenco_il:
            return self._elenco
        try:
            with httpx.Client(transport=self._transport, timeout=15.0) as c:
                r = c.get(f"{self._base}/voices/list", headers={"Ocp-Apim-Subscription-Key": self._chiave})
            controlla(r, "Azure")
            voci = [v for v in r.json() if str(v.get("Locale", "")).lower() == "it-it" and v.get("ShortName")]
            if not voci:
                raise ValueError("nessuna voce italiana")
        except Exception as e:  # senza elenco si usa la voce sicura, e si riprova più tardi
            log.warning("elenco voci Azure non disponibile: %s", type(e).__name__)
            self._elenco = [{"ShortName": AZURE_PREDEFINITA, "DisplayName": "Isabella", "Gender": "Female"}]
            self._elenco_il = ora + AZURE_ELENCO_RIPROVA_SECONDI
            return self._elenco
        # _elenco_il = fin quando vale l'elenco in memoria.
        self._elenco, self._elenco_il = voci, ora + AZURE_ELENCO_SECONDI
        return voci

    def predefinita(self) -> str:
        voci = self.elenco()
        for v in voci:
            if "Multilingual" in v["ShortName"] and str(v.get("Gender", "")).lower() == "female":
                return v["ShortName"]
        return AZURE_PREDEFINITA

    @staticmethod
    def ssml(testo: str, voce: str, lento: bool) -> str:
        corpo = escape(senza_segni(testo))
        if lento:
            corpo = f'<prosody rate="-10%">{corpo}</prosody>'
        return (f'<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="it-IT">'
                f'<voice name="{escape(voce)}">{corpo}</voice></speak>')

    def caratteri(self, testo: str, emozione: str | None, lento: bool) -> int:
        return len(senza_segni(testo))

    def _aspetta_il_turno(self) -> None:
        with self._lock:
            attesa = self._ultima + self.pausa_minima - self._orologio()
            if attesa > 0:
                self._dormi(attesa)
            self._ultima = self._orologio()

    def sintetizza(self, testo: str, *, emozione: str | None = None, lento: bool = False,
                   voce: str | None = None) -> Audio:
        voce = voce or self.predefinita()
        corpo = self.ssml(testo, voce, lento).encode("utf-8")
        caratteri = len(senza_segni(testo))

        def manda() -> httpx.Response:
            with httpx.Client(transport=self._transport, timeout=self._timeout) as c:
                return c.post(f"{self._base}/v1", content=corpo, headers={
                    "Ocp-Apim-Subscription-Key": self._chiave,
                    "Content-Type": "application/ssml+xml",
                    "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
                    "User-Agent": "lectio",
                })

        self._aspetta_il_turno()
        r = controlla(invia("Azure", manda), "Azure")
        if not r.content:
            raise ErroreFornitore("Azure ha risposto senza audio. Tocca Riprova.", elaborata=True)
        return Audio(r.content, "mp3", caratteri)


class VoceProva:
    """espeak-ng in italiano: robotica, ma gratis e sul NAS. Solo per provare."""

    fornitore = "prova"
    tipo = "prova"
    a_pagamento = False
    parallelo = 2

    def __init__(self, programma: str = "espeak-ng"):
        self.programma = programma

    def disponibile(self) -> bool:
        return shutil.which(self.programma) is not None

    def caratteri(self, testo: str, emozione: str | None, lento: bool) -> int:
        return len(senza_segni(testo))

    def sintetizza(self, testo: str, *, emozione: str | None = None, lento: bool = False,
                   voce: str | None = None) -> Audio:
        if not self.disponibile():
            raise ErroreFornitore("Manca il programma della voce di prova (espeak-ng) sul server.")
        pulito = senza_segni(testo)
        with tempfile.TemporaryDirectory(prefix="lectio-voce-") as cartella:
            entrata = os.path.join(cartella, "testo.txt")
            uscita = os.path.join(cartella, "voce.wav")
            with open(entrata, "w", encoding="utf-8") as f:
                f.write(pulito)
            velocita = "145" if lento else "165"
            r = subprocess.run([self.programma, "-v", "it", "-s", velocita, "-w", uscita, "-f", entrata],
                               capture_output=True, timeout=120)
            if r.returncode != 0 or not os.path.exists(uscita):
                log.warning("espeak-ng non è riuscito: %s", r.stderr[-300:])
                raise ErroreFornitore("La voce di prova non è riuscita a leggere un pezzo. Tocca Riprova.")
            with open(uscita, "rb") as f:
                return Audio(f.read(), "wav", len(pulito))


@dataclass
class VoceScelta:
    id: str
    tipo: str  # espressiva | gratis | prova
    fornitore: object
    nome_voce: str | None


class Catalogo:
    """Quali voci ci sono, quali si possono usare adesso e chi le fa."""

    def __init__(self, prova: bool, euro_al_minuto_mistral: float | None, voce_mistral_id: str,
                 mistral: VoceMistral | None, azure: VoceAzure | None, voce_prova: VoceProva,
                 euro_al_minuto_azure: float = 0.0):
        self.prova = prova
        # None = il modello della voce non ha un prezzo in prezzi.json: la voce espressiva resta ferma.
        self.euro_al_minuto_mistral = euro_al_minuto_mistral
        self.euro_al_minuto_azure = euro_al_minuto_azure
        self.voce_mistral_id = voce_mistral_id
        self.mistral = mistral
        self.azure = azure
        self.voce_prova = voce_prova

    @property
    def mistral_disponibile(self) -> bool:
        return self.mistral is not None and not self.prova and self.euro_al_minuto_mistral is not None

    @staticmethod
    def _nomi_azure(voci: list[dict]) -> dict[str, str]:
        nomi: dict[str, str] = {}
        semplici = {str(v.get("DisplayName") or v["ShortName"]).replace(" Multilingual", "").strip(): 0 for v in voci}
        for v in voci:
            base = str(v.get("DisplayName") or v["ShortName"])
            nome = base.replace(" Multilingual", "").strip()
            semplici[nome] += 1
        for v in voci:
            base = str(v.get("DisplayName") or v["ShortName"])
            nome = base.replace(" Multilingual", "").strip()
            if semplici[nome] > 1 and "Multilingual" in base:
                nome = f"{nome} multilingue"
            nomi[v["ShortName"]] = nome
        return nomi

    def elenco(self) -> list[dict]:
        uscita = [{
            "id": f"mistral:{self.voce_mistral_id}",
            "nome": "Lorenzo" if self.voce_mistral_id == "voice_482" else self.voce_mistral_id,
            "tipo": "espressiva",
            "fornitore": "mistral",
            "euro_al_minuto": self.euro_al_minuto_mistral,
            "disponibile": self.mistral_disponibile,
        }]
        if self.azure is not None:
            voci = self.azure.elenco()
            predefinita = self.azure.predefinita()
            voci = sorted(voci, key=lambda v: (v["ShortName"] != predefinita, v["ShortName"]))
            nomi = self._nomi_azure(voci)
            for v in voci:
                uscita.append({"id": f"azure:{v['ShortName']}", "nome": nomi[v["ShortName"]], "tipo": "gratis",
                               "fornitore": "azure", "euro_al_minuto": self.euro_al_minuto_azure, "disponibile": True})
        else:
            uscita.append({"id": f"azure:{AZURE_PREDEFINITA}", "nome": "Isabella", "tipo": "gratis",
                           "fornitore": "azure", "euro_al_minuto": self.euro_al_minuto_azure, "disponibile": False})
        if self.prova:
            uscita.append({"id": "prova:espeak", "nome": "Voce di prova", "tipo": "prova", "fornitore": "espeak",
                           "euro_al_minuto": 0, "disponibile": self.voce_prova.disponibile()})
        return uscita

    def risolvi(self, voce_id: str | None) -> VoceScelta | None:
        """La voce se si può usare adesso, altrimenti None."""
        if not voce_id or ":" not in voce_id:
            return None
        prefisso, nome = voce_id.split(":", 1)
        if prefisso == "mistral" and self.mistral_disponibile and nome == self.voce_mistral_id:
            return VoceScelta(voce_id, "espressiva", self.mistral, nome)
        if prefisso == "azure" and self.azure is not None and nome.startswith("it-IT-"):
            return VoceScelta(voce_id, "gratis", self.azure, nome)
        if prefisso == "prova" and nome == "espeak" and self.prova:
            return VoceScelta(voce_id, "prova", self.voce_prova, None)
        return None

    def ripiego(self, preferita: str | None) -> str | None:
        """La voce da usare quando l'app non ne sceglie una: la preferita se si può,
        altrimenti quella di prova (in prova) o la gratis."""
        if self.risolvi(preferita):
            return preferita
        if self.prova:
            return "prova:espeak"
        if self.mistral_disponibile:
            return f"mistral:{self.voce_mistral_id}"
        if self.azure is not None:
            return f"azure:{self.azure.predefinita()}"
        return None
