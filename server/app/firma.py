"""La firma che va su tutto quello che Lectio crea e si può scaricare: in bianco in fondo
a ogni pagina dei PDF, nei dati del file per gli audio (che non hanno una pagina).

Il testo sta in firma.txt, nella cartella dati del server e non qui: il codice è pubblico."""

import os
from pathlib import Path

from .config import DATI_DIR_DEFAULT

SENZA_FILE = "Lectio"


def _leggi() -> str:
    file = Path(os.environ.get("LECTIO_DATI") or DATI_DIR_DEFAULT) / "firma.txt"
    try:
        return file.read_text(encoding="utf-8").strip() or SENZA_FILE
    except OSError:
        return SENZA_FILE


FIRMA = _leggi()
