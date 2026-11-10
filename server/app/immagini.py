"""Le foto degli schemi (contratto 18): solo da Wikimedia Commons, solo con licenza libera, e
ognuna va guardata prima di usarla: deve mostrare davvero quello che dice la didascalia.

La richiesta dice solo "Lectio": niente dati dello studente vanno a Commons."""

import base64
import io
import logging
import re
from dataclasses import dataclass

import httpx

from . import ai, prompt_studio
from .rete import ErroreFornitore

log = logging.getLogger("uvicorn.error")

URL = "https://commons.wikimedia.org/w/api.php"
CLIENTE = "Lectio/1.2 (app personale per lo studio)"
LATO_FOTO = 1400
LATO_CONTROLLO = 512
LARGHEZZA_MINIMA = 400
RISULTATI = 8
FOTO_GUARDATE_PER_SOGGETTO = 4
MASSIMO_BYTE = 12 * 1024 * 1024
_LICENZA_BUONA = re.compile(r"^(?:cc[ -]by(?:[ -]sa)?\b|cc0|public domain|pd\b|pdm)", re.I)
_LICENZA_NO = re.compile(r"\bnc\b|\bnd\b|fair use|non-free", re.I)


@dataclass
class Trovata:
    titolo: str
    url: str
    credito: str


def _senza_html(testo: str) -> str:
    return " ".join(re.sub(r"<[^>]+>", " ", testo or "").split())


class Commons:
    def __init__(self, transport: httpx.BaseTransport | None = None, timeout: float = 25.0):
        self._transport, self._timeout = transport, timeout

    def _client(self) -> httpx.Client:
        return httpx.Client(transport=self._transport, timeout=self._timeout, follow_redirects=True,
                            headers={"User-Agent": CLIENTE})

    def cerca(self, testo: str) -> list[Trovata]:
        parametri = {"action": "query", "format": "json", "generator": "search", "gsrsearch": f"{testo} filetype:bitmap",
                     "gsrnamespace": "6", "gsrlimit": str(RISULTATI), "prop": "imageinfo",
                     "iiprop": "url|extmetadata|mime|size", "iiurlwidth": str(LATO_FOTO)}
        try:
            with self._client() as c:
                r = c.get(URL, params=parametri)
            r.raise_for_status()
            pagine = (r.json().get("query") or {}).get("pages") or {}
        except (httpx.HTTPError, ValueError) as e:
            log.warning("Commons non risponde (%s)", type(e).__name__)
            return []
        uscita = []
        for p in sorted(pagine.values(), key=lambda x: x.get("index", 99)):
            info = (p.get("imageinfo") or [{}])[0]
            meta = info.get("extmetadata") or {}
            licenza = _senza_html((meta.get("LicenseShortName") or {}).get("value", ""))
            if not _LICENZA_BUONA.search(licenza) or _LICENZA_NO.search(licenza):
                continue
            if info.get("mime") not in ("image/jpeg", "image/png", "image/webp") or int(info.get("width") or 0) < LARGHEZZA_MINIMA:
                continue
            autore = _senza_html((meta.get("Artist") or {}).get("value", ""))[:80]
            credito = ", ".join(x for x in (f"Foto di {autore}" if autore else "", licenza, "Wikimedia Commons") if x)
            uscita.append(Trovata(str(p.get("title") or ""), info.get("thumburl") or info.get("url"), credito))
        return uscita

    def scarica(self, url: str) -> bytes | None:
        try:
            with self._client() as c:
                r = c.get(url)
            r.raise_for_status()
        except httpx.HTTPError as e:
            log.warning("foto non scaricata (%s)", type(e).__name__)
            return None
        return r.content if len(r.content) <= MASSIMO_BYTE else None


def in_jpeg(dati: bytes, lato: int) -> bytes | None:
    """Una foto in JPEG con il lato lungo al massimo `lato`. None se il file non è una foto vera."""
    from PIL import Image, UnidentifiedImageError

    try:
        with Image.open(io.BytesIO(dati)) as im:
            im = im.convert("RGB")
            im.thumbnail((lato, lato))
            uscita = io.BytesIO()
            im.save(uscita, "JPEG", quality=85, optimize=True)
            return uscita.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError):
        return None


def _valida_sguardo(o: dict) -> list[str]:
    return [] if isinstance(o.get("mostra"), bool) else ['serve "mostra", vero o falso']


def trova_foto(ctx, soggetto: str, ricerca: str, escluse: set[str], *, materia_id, riferimento, prima=None) -> dict | None:
    """La prima foto con licenza libera che mostra davvero il soggetto: {"jpeg", "url", "credito"}."""
    foto = ctx.foto
    if foto is None:
        return None
    guardate = 0
    for voce in foto.cerca(ricerca or soggetto):
        if voce.url in escluse:
            continue
        escluse.add(voce.url)
        dati = foto.scarica(voce.url)
        jpeg = in_jpeg(dati, LATO_FOTO) if dati else None
        piccola = in_jpeg(jpeg, LATO_CONTROLLO) if jpeg else None
        if piccola is None:
            continue
        guardate += 1
        try:
            risposta = ai.chiedi_json(
                cassa=ctx.cassa, fornitore=ctx.testo, compito="foto",
                messaggi=prompt_studio.messaggi_guarda_foto(soggetto, base64.b64encode(piccola).decode("ascii")),
                modello=ctx.settings.modello_piano, valida=_valida_sguardo, max_token=80, cosa="Controllo di una foto",
                dati={}, materia_id=materia_id, prima_di_ogni_chiamata=prima, riferimento=riferimento,
            )
        except (ErroreFornitore, ai.RispostaNonUsabile) as e:
            log.warning("foto non controllata (%s)", type(e).__name__)
            return None
        if risposta["mostra"]:
            return {"jpeg": jpeg, "url": voce.url, "credito": voce.credito[:160]}
        if guardate >= FOTO_GUARDATE_PER_SOGGETTO:
            return None
    return None
