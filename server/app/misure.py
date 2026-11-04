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


# --------------------------------------------------------------------------- tipi di lezione (16.1)

TIPI_LEZIONE = ("spiegazione", "ripasso", "interrogazione")
MINUTI_APERTURA_BREVE = 0.5  # ripasso e interrogazione: si entra subito nel vivo
MINUTI_PER_DOMANDA_RIPASSO = 0.75  # nel ripasso la risposta alla fine è più completa
DOMANDE_PER_GRUPPO = 5


def tipo_valido(tipo: str | None) -> str:
    return tipo if tipo in TIPI_LEZIONE else "spiegazione"


def struttura(tipo: str | None, durata_min: int) -> dict:
    """Com'è fatta una lezione: le stesse regole del riepilogo nelle app (contratto 16.1).
    - parti: i capitoli di spiegazione (spiegazione, ripasso) o i gruppi di domande (interrogazione);
    - domanda_per_parte: una domanda alla fine di ogni parte (solo spiegazione);
    - finali: le domande dell'interrogazione alla fine (spiegazione, ripasso);
    - domande: tutte le domande della lezione."""
    tipo = tipo_valido(tipo)
    if tipo == "ripasso":
        finali = arrotonda_mezzo_su(n_interrogazione(durata_min) * 1.6)
        parti = max(2, arrotonda_mezzo_su(durata_min / 7.5))
        return {"tipo": tipo, "parti": parti, "domanda_per_parte": False, "finali": finali, "domande": finali}
    if tipo == "interrogazione":
        domande = arrotonda_mezzo_su(durata_min * 0.6)
        parti = max(2, arrotonda_mezzo_su(domande / DOMANDE_PER_GRUPPO))
        return {"tipo": tipo, "parti": parti, "domanda_per_parte": False, "finali": 0, "domande": domande}
    parti, finali = n_blocchi(durata_min), n_interrogazione(durata_min)
    return {"tipo": tipo, "parti": parti, "domanda_per_parte": True, "finali": finali, "domande": parti + finali}


def domande_dei_gruppi(totale: int, gruppi: int) -> list[int]:
    """Le domande di un'interrogazione divise in gruppi quasi uguali (27 in 5 → 6, 6, 5, 5, 5)."""
    base, resto = divmod(totale, gruppi)
    return [base + (1 if i < resto else 0) for i in range(gruppi)]


def minuti_fissi_tipo(tipo: str | None, durata_min: int) -> float:
    s = struttura(tipo, durata_min)
    if s["tipo"] == "ripasso":
        return (MINUTI_APERTURA_BREVE + MINUTI_ERRORI + MINUTI_CHIUSURA
                + MINUTI_PER_DOMANDA_RIPASSO * s["finali"])
    if s["tipo"] == "interrogazione":
        return MINUTI_APERTURA_BREVE + MINUTI_ERRORI + MINUTI_CHIUSURA
    return minuti_fissi(durata_min)


def minuti_per_parte(tipo: str | None, durata_min: int) -> float:
    """Spiegazione e ripasso: i minuti di ogni parte. Interrogazione: i minuti di ogni domanda
    con la sua risposta."""
    s = struttura(tipo, durata_min)
    resto = durata_min - minuti_fissi_tipo(tipo, durata_min)
    if s["tipo"] == "interrogazione":
        return max(0.75, resto / s["domande"])
    return max(2.0, resto / s["parti"])


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
