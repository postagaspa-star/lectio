"""Configurazione dall'ambiente (contratto, sezione 14). Le chiavi stanno solo qui:
mai nel database, mai nelle API, mai nei log."""

import os
from dataclasses import dataclass
from pathlib import Path

VERSIONE = "1.0.1"

CARTELLA_APP = Path(__file__).resolve().parent
VERSIONI_PATH_DEFAULT = str(CARTELLA_APP / "versioni.json")
PREZZI_PATH_DEFAULT = str(CARTELLA_APP / "prezzi.json")
GEMINI_PATH_DEFAULT = str(CARTELLA_APP / "testo_gemini.txt")
APK_DIR_DEFAULT = str(CARTELLA_APP.parent / "apk")
DATI_DIR_DEFAULT = str(CARTELLA_APP.parent / "dati")

MISTRAL_URL = "https://api.mistral.ai/v1"

MODELLO_PIANO_DEFAULT = "mistral-small-latest"
MODELLO_SCRITTURA_DEFAULT = "mistral-medium-latest"
MODELLO_OCR_DEFAULT = "mistral-ocr-latest"
MODELLO_VOCE_DEFAULT = "voxtral-mini-tts-3-0"
VOCE_MISTRAL_DEFAULT = "voice_482"  # Lorenzo, l'unica voce italiana preimpostata

# Abbinamento (contratto, sezione 2), come Sol: dietro Tailscale tutte le richieste
# arrivano dallo stesso indirizzo, quindi il limite dei tentativi è uno per tutto il server.
CIFRE_CODICE = 8
DURATA_CODICE_MINUTI = 15
TENTATIVI_SBAGLIATI_MASSIMI = 10
FINESTRA_TENTATIVI_MINUTI = 15

# Materiale (contratto, sezione 6).
MASSIMO_BYTE_FILE = 20 * 1024 * 1024
MASSIMO_CARATTERI = 200_000


@dataclass(frozen=True)
class Settings:
    db_path: str
    dati_dir: str
    apk_dir: str
    versioni_path: str
    prezzi_path: str
    gemini_path: str
    mistral_key: str | None
    azure_key: str | None
    azure_regione: str | None
    modello_piano: str
    modello_scrittura: str
    modello_ocr: str
    modello_voce: str
    voce_mistral: str
    # Il lavoro in sottofondo si spegne nei test (LECTIO_SOTTOFONDO=0): lì i passi
    # si chiamano a mano, uno alla volta.
    sottofondo: bool
    # Il container di Tailscale (le richieste da internet arrivano da lui): vedi origine.py.
    tailscale_host: str = "lectio-tailscale"
    # L'indirizzo https per usare Lectio fuori casa (Tailscale Funnel), da dare alle app.
    indirizzo_fuori_casa: str | None = None

    @property
    def modalita_prova(self) -> bool:
        """Senza la chiave Mistral tutto gira in prova: testo finto, voce di prova, costi 0."""
        return not self.mistral_key

    @property
    def azure_pronta(self) -> bool:
        return bool(self.azure_key and self.azure_regione)


def _vuoto_a_none(valore: str | None) -> str | None:
    if valore is None:
        return None
    valore = valore.strip()
    return valore or None


def carica_settings() -> Settings:
    e = os.environ.get
    dati_dir = e("LECTIO_DATI") or DATI_DIR_DEFAULT
    return Settings(
        db_path=e("LECTIO_DB") or str(Path(dati_dir) / "lectio.db"),
        dati_dir=dati_dir,
        apk_dir=e("LECTIO_APK_DIR") or APK_DIR_DEFAULT,
        versioni_path=e("LECTIO_VERSIONI") or VERSIONI_PATH_DEFAULT,
        prezzi_path=e("LECTIO_PREZZI") or PREZZI_PATH_DEFAULT,
        gemini_path=e("LECTIO_GEMINI") or GEMINI_PATH_DEFAULT,
        mistral_key=_vuoto_a_none(e("LECTIO_MISTRAL_KEY")),
        azure_key=_vuoto_a_none(e("LECTIO_AZURE_KEY")),
        azure_regione=_vuoto_a_none(e("LECTIO_AZURE_REGIONE")),
        modello_piano=_vuoto_a_none(e("LECTIO_MODELLO_PIANO")) or MODELLO_PIANO_DEFAULT,
        modello_scrittura=_vuoto_a_none(e("LECTIO_MODELLO_SCRITTURA")) or MODELLO_SCRITTURA_DEFAULT,
        modello_ocr=_vuoto_a_none(e("LECTIO_MODELLO_OCR")) or MODELLO_OCR_DEFAULT,
        modello_voce=_vuoto_a_none(e("LECTIO_MODELLO_VOCE")) or MODELLO_VOCE_DEFAULT,
        voce_mistral=_vuoto_a_none(e("LECTIO_VOCE_MISTRAL")) or VOCE_MISTRAL_DEFAULT,
        sottofondo=(e("LECTIO_SOTTOFONDO") or "1").strip().lower() not in ("0", "no", "false"),
        tailscale_host=_vuoto_a_none(e("LECTIO_TAILSCALE_HOST")) or "lectio-tailscale",
        indirizzo_fuori_casa=(_vuoto_a_none(e("LECTIO_INDIRIZZO_FUORI_CASA")) or "").rstrip("/") or None,
    )


def riassunto_config(s: Settings) -> str:
    """Riga per il log d'avvio: dice COSA è configurato, mai i valori segreti."""

    def stato(v: str | None) -> str:
        return "impostata" if v else "assente"

    return (
        f"db={s.db_path} dati={s.dati_dir} mistral={stato(s.mistral_key)} azure={stato(s.azure_key)}"
        f" regione={s.azure_regione or 'assente'} prova={'si' if s.modalita_prova else 'no'}"
        f" piano={s.modello_piano} scrittura={s.modello_scrittura} ocr={s.modello_ocr} voce={s.modello_voce}"
        f" sottofondo={'acceso' if s.sottofondo else 'spento'}"
    )


def assicura_cartelle(s: Settings) -> None:
    Path(s.db_path).resolve().parent.mkdir(parents=True, exist_ok=True)
    Path(s.dati_dir).mkdir(parents=True, exist_ok=True)
