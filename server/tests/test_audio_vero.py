"""Montaggio con ffmpeg vero e voce di prova con espeak-ng vero.

Sul PC di Andrea (Windows) di solito questi programmi non ci sono: i test si saltano
con il motivo. Nel container sul NAS ci sono entrambi:
    docker exec lectio sh -c "pip install pytest && cd /srv/lectio && python -m pytest -q tests"
"""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from aiuti import aggiungi_testo, crea_materia, nuova_lezione, prepara_tutto
from app.audio import PAUSA_DOPO_DOMANDA, Montatore
from app.voci import VoceProva
from finti import wav_di

serve_ffmpeg = pytest.mark.skipif(
    not (shutil.which("ffmpeg") and shutil.which("ffprobe")),
    reason="ffmpeg e ffprobe non ci sono su questa macchina (ci sono nel container Docker sul NAS)")
serve_espeak = pytest.mark.skipif(
    not shutil.which("espeak-ng"),
    reason="espeak-ng non c'è su questa macchina (c'è nel container Docker sul NAS)")


def ffprobe(percorso: Path) -> dict:
    uscita = subprocess.run(["ffprobe", "-v", "error", "-show_entries",
                             "stream=codec_name,channels,sample_rate,bit_rate:format=duration", "-of", "json",
                             str(percorso)], capture_output=True, check=True).stdout
    return json.loads(uscita)


@serve_ffmpeg
def test_montaggio_vero_mp3_mono_64k_24khz(tmp_path):
    pezzi = []
    for i, (secondi, frequenza) in enumerate(((1.5, 24000), (2.0, 44100), (0.8, 16000))):
        p = tmp_path / f"p{i}.wav"
        p.write_bytes(wav_di(secondi, frequenza=frequenza, tono=200 + 100 * i))
        pezzi.append(p)
    # Uno dei pezzi come MP3, come quelli di Mistral e Azure.
    mp3 = tmp_path / "p1.mp3"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(pezzi[1]), "-c:a", "libmp3lame", str(mp3)], check=True)
    pezzi[1] = mp3
    uscita = tmp_path / "lezione.mp3"
    m = Montatore().monta([(pezzi[0], 0), (pezzi[1], PAUSA_DOPO_DOMANDA), (pezzi[2], 0.6)], uscita, tmp_path / "lavoro")
    info = ffprobe(uscita)
    flusso = info["streams"][0]
    assert flusso["codec_name"] == "mp3" and flusso["channels"] == 1 and flusso["sample_rate"] == "24000"
    assert abs(int(flusso["bit_rate"]) - 64000) <= 2000
    (i0, f0), (i1, f1), (i2, f2) = m.tempi
    assert i0 == 0 and abs(f0 - 1.5) < 0.01
    assert abs(i1 - f0 - PAUSA_DOPO_DOMANDA) < 0.01
    assert abs(i2 - f1 - 0.6) < 0.01
    assert abs(m.durata - f2) < 0.15  # l'MP3 aggiunge pochi millisecondi
    assert not (tmp_path / "lavoro" / "tutto.wav").exists()


@serve_ffmpeg
def test_lezione_intera_con_ffmpeg_vero(client, auth, servizi):
    servizi.ctx.montatore = Montatore()
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    d = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert d["stato"] == "pronta", d
    mp3 = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"]) / "lezione.mp3"
    durata = float(ffprobe(mp3)["format"]["duration"])
    assert abs(d["durata_s"] - durata) <= 1
    assert d["domande"][-1]["fine_s"] <= durata + 0.2


@serve_espeak
def test_voce_di_prova_espeak():
    a = VoceProva().sintetizza("[emotion=warm] Ciao, questa è la voce di prova.", lento=True)
    assert a.estensione == "wav" and a.contenuto[:4] == b"RIFF" and a.caratteri > 10


@serve_espeak
@serve_ffmpeg
def test_lezione_di_prova_con_espeak_e_ffmpeg(client, auth, servizi):
    servizi.ctx.montatore = Montatore()
    servizi.catalogo.voce_prova = VoceProva()
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    d = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert d["stato"] == "pronta", d
    assert d["durata_s"] > 30
    for q in d["domande"]:
        assert abs(q["inizio_risposta_s"] - q["fine_domanda_s"] - PAUSA_DOPO_DOMANDA) < 0.15
