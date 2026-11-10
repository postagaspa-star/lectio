"""La cartella di un lavoro lungo (schema, verifica simulata): ogni passo salva il suo risultato,
così un Riprova riparte da dove si era fermato senza ripagare niente."""

import json
import os
from pathlib import Path


class Cartella:
    def __init__(self, percorso: Path):
        self.percorso = Path(percorso)

    def file(self, nome: str) -> Path:
        return self.percorso / nome

    def leggi(self, nome: str):
        try:
            with open(self.file(nome), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def scrivi(self, nome: str, oggetto) -> None:
        percorso = self.file(nome)
        percorso.parent.mkdir(parents=True, exist_ok=True)
        provvisorio = percorso.with_suffix(percorso.suffix + ".tmp")
        with open(provvisorio, "w", encoding="utf-8") as f:
            json.dump(oggetto, f, ensure_ascii=False)
        os.replace(provvisorio, percorso)
