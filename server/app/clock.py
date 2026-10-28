"""Unica fonte dell'ora. Nel database le date stanno in UTC (si ordinano come testo);
verso le app escono nell'ora italiana con il fuso (contratto, sezione 1).
Il mese della spesa è quello di Europe/Rome. I test sostituiscono clock.now()."""

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

FUSO = ZoneInfo("Europe/Rome")

MESI = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre",
        "ottobre", "novembre", "dicembre"]


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    """Per il database: sempre UTC, al millisecondo (l'ascolto confronta istanti vicini)."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).isoformat(timespec="milliseconds")


def adesso() -> str:
    return iso(now())


def da_iso(ts: str) -> datetime:
    dt = datetime.fromisoformat(ts)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt


def per_utente(ts: str | None) -> str | None:
    """Per le API: ora italiana con il fuso, es. 2026-10-28T09:30:00+01:00."""
    if not ts:
        return None
    return da_iso(ts).astimezone(FUSO).isoformat(timespec="seconds")


def oggi() -> date:
    return now().astimezone(FUSO).date()


def mese(dt: datetime | None = None) -> str:
    """Il mese della spesa, es. "2026-10" (ora italiana)."""
    return (dt or now()).astimezone(FUSO).strftime("%Y-%m")


def primo_del_mese_prossimo(dt: datetime | None = None) -> date:
    giorno = (dt or now()).astimezone(FUSO).date()
    if giorno.month == 12:
        return date(giorno.year + 1, 1, 1)
    return date(giorno.year, giorno.month + 1, 1)


def data_valida(testo) -> bool:
    if not isinstance(testo, str) or len(testo) != 10:
        return False
    try:
        return date.fromisoformat(testo).isoformat() == testo
    except ValueError:
        return False


def data_in_parole(giorno: date) -> str:
    return f"{giorno.day} {MESI[giorno.month - 1]} {giorno.year}"


def inizio_giorno(giorno: date) -> datetime:
    return datetime.combine(giorno, time(0, 0), tzinfo=FUSO).astimezone(timezone.utc)


def giorni_tra(da: date, a: date) -> int:
    return (a - da) // timedelta(days=1)
