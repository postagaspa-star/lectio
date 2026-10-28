"""Pezzi di voce e montaggio (contratto, sezione 9.1, passi 4 e 5).

Montaggio con ffmpeg:
1. ogni pezzo (mp3 di Mistral o Azure, wav della voce di prova) diventa un WAV mono
   24 kHz a 16 bit, e la sua durata vera si misura con ffprobe;
2. i WAV si uniscono qui, con i silenzi del contratto (0,6 s tra paragrafi, 1,2 s tra
   capitoli, 2,5 s dopo ogni domanda): i tempi di capitoli e domande escono da questi
   conti, quindi corrispondono al file;
3. un solo passaggio finale normalizza il volume e codifica l'MP3 mono 64 kbps 24 kHz."""

import logging
import os
import re
import shutil
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("uvicorn.error")

FREQUENZA = 24_000
PEZZO_CARATTERI_MASSIMI = 1500

PAUSA_PARAGRAFI = 0.6
PAUSA_CAPITOLI = 1.2
PAUSA_DOPO_DOMANDA = 2.5
PAUSA_DENTRO_PARAGRAFO = 0.25


class ErroreMontaggio(Exception):
    def __init__(self, frase: str):
        super().__init__(frase)
        self.frase = frase


def taglia_in_pezzi(testo: str, massimo: int = PEZZO_CARATTERI_MASSIMI) -> list[str]:
    """Pezzi da al massimo `massimo` caratteri, tagliati a fine frase (o, se una frase
    da sola è troppo lunga, a una virgola o a uno spazio)."""
    testo = " ".join(testo.split())
    if len(testo) <= massimo:
        return [testo] if testo else []
    frasi = [f for f in re.split(r"(?<=[.!?…;:])\s+", testo) if f]
    piccole: list[str] = []
    for f in frasi:
        while len(f) > massimo:
            taglio = f.rfind(", ", 0, massimo)
            if taglio < massimo // 3:
                taglio = f.rfind(" ", 0, massimo)
            if taglio <= 0:
                taglio = massimo
            piccole.append(f[: taglio + 1].strip())
            f = f[taglio + 1:].strip()
        if f:
            piccole.append(f)
    pezzi, attuale = [], ""
    for f in piccole:
        if attuale and len(attuale) + 1 + len(f) > massimo:
            pezzi.append(attuale)
            attuale = f
        else:
            attuale = f"{attuale} {f}".strip()
    if attuale:
        pezzi.append(attuale)
    return pezzi


@dataclass
class Montaggio:
    tempi: list[tuple[float, float]]  # inizio e fine di ogni pezzo nel file finale
    durata: float


class Montatore:
    def __init__(self, ffmpeg: str = "ffmpeg", ffprobe: str = "ffprobe"):
        self.ffmpeg = ffmpeg
        self.ffprobe = ffprobe

    def disponibile(self) -> bool:
        return shutil.which(self.ffmpeg) is not None and shutil.which(self.ffprobe) is not None

    def _esegui(self, argomenti: list[str], cosa: str) -> str:
        try:
            r = subprocess.run(argomenti, capture_output=True, timeout=1800)
        except FileNotFoundError:
            raise ErroreMontaggio("Manca il programma per montare l'audio (ffmpeg) sul server.") from None
        except subprocess.TimeoutExpired:
            raise ErroreMontaggio("Il montaggio dell'audio ci ha messo troppo. Tocca Riprova.") from None
        if r.returncode != 0:
            log.warning("%s non riuscito: %s", cosa, r.stderr.decode("utf-8", "replace")[-500:])
            raise ErroreMontaggio("Non sono riuscito a montare l'audio. Tocca Riprova.")
        return r.stdout.decode("utf-8", "replace")

    # Tre operazioni che i test possono sostituire (senza ffmpeg sul PC).
    def in_wav(self, sorgente: Path, destinazione: Path) -> None:
        self._esegui([self.ffmpeg, "-nostdin", "-y", "-v", "error", "-i", str(sorgente), "-ac", "1",
                      "-ar", str(FREQUENZA), "-c:a", "pcm_s16le", "-f", "wav", str(destinazione)], "conversione")

    def durata(self, percorso: Path) -> float:
        uscita = self._esegui([self.ffprobe, "-v", "error", "-show_entries", "format=duration",
                               "-of", "default=noprint_wrappers=1:nokey=1", str(percorso)], "misura")
        try:
            return float(uscita.strip().splitlines()[0])
        except (ValueError, IndexError):
            raise ErroreMontaggio("Non riesco a misurare la durata di un pezzo di voce. Tocca Riprova.") from None

    def codifica(self, wav: Path, mp3: Path) -> None:
        self._esegui([self.ffmpeg, "-nostdin", "-y", "-v", "error", "-i", str(wav),
                      "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-ar", str(FREQUENZA), "-ac", "1",
                      "-c:a", "libmp3lame", "-b:a", "64k", "-f", "mp3", str(mp3)], "codifica")

    def monta(self, pezzi: list[tuple[Path, float]], uscita: Path, lavoro: Path) -> Montaggio:
        """`pezzi` = (file, silenzio prima). Scrive `uscita` (MP3) e restituisce i tempi."""
        lavoro.mkdir(parents=True, exist_ok=True)
        tutto = lavoro / "tutto.wav"
        tempi: list[tuple[float, float]] = []
        cornici = 0
        with wave.open(str(tutto), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(FREQUENZA)
            for i, (sorgente, pausa) in enumerate(pezzi):
                wav = lavoro / f"{i:04d}.wav"
                self.in_wav(sorgente, wav)
                misura = self.durata(wav)
                silenzio = int(round(max(0.0, pausa) * FREQUENZA)) if i > 0 else 0
                out.writeframes(b"\x00\x00" * silenzio)
                cornici += silenzio
                with wave.open(str(wav), "rb") as w:
                    if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != FREQUENZA:
                        raise ErroreMontaggio("Un pezzo di voce ha un formato inatteso. Tocca Riprova.")
                    dati = w.readframes(w.getnframes())
                    n = w.getnframes()
                inizio = cornici / FREQUENZA
                out.writeframes(dati)
                cornici += n
                tempi.append((inizio, inizio + misura))
                os.remove(wav)
        provvisorio = uscita.with_suffix(".tmp.mp3")
        self.codifica(tutto, provvisorio)
        os.replace(provvisorio, uscita)
        durata = self.durata(uscita)
        os.remove(tutto)
        return Montaggio(tempi, durata)
