"""Le misure di una lezione (contratto, sezioni 8 e 9.1): servono sia alla stima del costo
sia alla preparazione, così le due cose non possono divergere."""

import math

PAROLE_AL_MINUTO = 145
CARATTERI_PER_PAROLA = 6.3
# In italiano un token è circa 3,5 caratteri (stima per i costi prima della chiamata).
CARATTERI_PER_TOKEN = 3.5

DURATA_MINIMA = 15
DURATA_MASSIMA = 60

# Parti fisse della lezione, in minuti (9.1).
MINUTI_APERTURA = 0.75
MINUTI_RIEPILOGO = 1.0
MINUTI_ERRORI = 0.75
MINUTI_CHIUSURA = 0.25
MINUTI_PER_DOMANDA_INTERROGAZIONE = 0.5  # domanda + 2,5 s di silenzio + risposta

# Le quattro durate che le app offrono. Il contratto dà "3 + round(durata/10)" e insieme
# gli esempi 15→4, 20→5, 30→6, 45→8: con l'arrotondamento normale 15 darebbe 5. Valgono
# gli esempi; per le altre durate si arrotonda per eccesso a metà (45 → 4,5 → 5 → 8).
DOMANDE_INTERROGAZIONE = {15: 4, 20: 5, 30: 6, 45: 8}


def arrotonda_mezzo_su(x: float) -> int:
    return int(math.floor(x + 0.5))


def n_blocchi(durata_min: int) -> int:
    return max(3, arrotonda_mezzo_su(durata_min / 5))


def n_interrogazione(durata_min: int) -> int:
    if durata_min in DOMANDE_INTERROGAZIONE:
        return DOMANDE_INTERROGAZIONE[durata_min]
    return 3 + arrotonda_mezzo_su(durata_min / 10)


def caratteri_stimati(durata_min: int) -> int:
    """parole = durata × 145; caratteri = parole × 6,3 (arrotondati alle centinaia, per eccesso)."""
    caratteri = durata_min * PAROLE_AL_MINUTO * CARATTERI_PER_PAROLA
    return int(math.ceil(round(caratteri, 6) / 100) * 100)


def minuti_fissi(durata_min: int) -> float:
    return (MINUTI_APERTURA + MINUTI_RIEPILOGO + MINUTI_ERRORI + MINUTI_CHIUSURA
            + MINUTI_PER_DOMANDA_INTERROGAZIONE * n_interrogazione(durata_min))


def minuti_per_blocco(durata_min: int) -> float:
    """Il tempo che resta ai blocchi, diviso in parti uguali (circa 4-6 minuti l'uno)."""
    return max(2.0, (durata_min - minuti_fissi(durata_min)) / n_blocchi(durata_min))


def parole(minuti: float) -> int:
    return max(20, int(round(minuti * PAROLE_AL_MINUTO)))
