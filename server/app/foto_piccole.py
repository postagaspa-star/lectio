"""Le foto piccole lette da gpt-6-luna invece che dall'OCR (dal 22/11/2026).

Una foto è piccola se il lato lungo è al massimo 1600 px: per esempio quelle mandate con WhatsApp, che arrivano a
1024×768. A quella misura l'OCR di Mistral perde le sottolineature, completa le righe tagliate e inventa la scrittura a
mano (prova del 21/11/2026 sulle pagine di un libro). luna vede le foto e segue regole precise: la prova costava circa
0,07 centesimi a foto, contro 0,17 dell'OCR 3.

Come si legge:
1. la foto si taglia in strisce orizzontali che si sovrappongono un poco (`tagli`);
2. ogni striscia si ingrandisce con Lanczos, fino a 2 volte, e si rende un po' più contrastata e nitida (`strisce_jpeg`);
3. tutte le strisce, in ordine, vanno in UNA sola richiesta a luna con ragionamento "low" (`leggi`);
4. la risposta è il testo con i segni delle trascrizioni del 21/11: [[ ]] per le sottolineature, gli appunti a mano su
   una riga a parte, SEMPRE segnati da controllare (scelta dello studente), [?] e [tagliato] invece di indovinare. In
   cima Lectio aggiunge una riga che spiega i segni.

Il tetto di costo: ogni foto deve costare al massimo 0,1 centesimi (0,001 €) ANCHE nel caso peggiore. Le uniche cose
che si possono pagare sono i token in entrata (testo e immagini) e quelli in uscita (ragionamento compreso), quindi:
- l'uscita ha un massimo fisso (TOKEN_USCITA, passato come max_completion_tokens: OpenAI non va oltre);
- le immagini si contano come fa OpenAI con "detail": "high" (32×32 pixel = 1 patch, 1,2 token a patch) e le misure
  delle strisce le decidiamo noi, quindi il loro costo è noto prima di chiamare;
- l'ingrandimento si sceglie in `piano`: il più grande (al massimo 2 volte) con cui il costo peggiore resta nel tetto.
Il calcolo completo è sopra `costo_peggiore`.

Se luna non c'è (in prova, o senza la chiave OpenAI: allora scrive DeepSeek, che le foto non le vede) si legge con
l'OCR come prima. Se luna non riesce, o risponde tagliato o vuoto, si legge con l'OCR e il registro lo dice.
Una sola richiesta per foto: niente correzioni, niente riserva, niente ritenti se la richiesta forse è già partita
(una seconda farebbe sforare il tetto della foto). Si ritenta solo quando OpenAI di sicuro non ha lavorato."""

import base64
import io
import logging
import math
import re
from dataclasses import dataclass

from . import lettura
from .rete import ErroreFornitore, ForseElaborata
from .spesa import PrezzoSconosciuto, TettoRaggiunto

log = logging.getLogger("uvicorn.error")

MODELLO = "gpt-6-luna"
RAGIONAMENTO = "low"

# Quando una foto è piccola: il lato lungo, girata o no.
LATO_MASSIMO_PICCOLA = 1600

# Le strisce. Una striscia ogni ALTEZZA_STRISCIA pixel della foto (8-12 righe di libro in una foto da WhatsApp):
# 1024×768 → 3 strisce (come nella prova), 768×1024 → 4, lato lungo 1600 → 5.
ALTEZZA_STRISCIA = 320
STRISCE_MASSIME = 5
# Ogni striscia prende in più il 15% del suo passo sopra e sotto (come nella prova): una riga tagliata in una striscia
# è intera nella vicina.
SOVRAPPOSIZIONE = 0.15
INGRANDIMENTO_MASSIMO = 2.0
# Sotto questo ingrandimento la foto si rimpicciolirebbe troppo per stare nel tetto: meglio l'OCR.
INGRANDIMENTO_MINIMO = 0.8
PASSO_INGRANDIMENTO = 0.05
QUALITA_JPEG = 88

# Come OpenAI conta le immagini con "detail": "high" (guida "Images and vision", letta il 22/11/2026). La guida non
# nomina ancora gpt-6-luna: i modelli vicini (gpt-6-astra, gpt-5.6-luna) contano a patch di 32×32 pixel, 1,2 token a
# patch, e fino a 2048 px per lato e 2.500 patch lasciano l'immagine com'è (oltre la rimpicciolirebbe OpenAI): le
# strisce restano sotto, quindi il conto è esatto. Se luna contasse di più, `leggi` lo scrive nel registro. Con "auto"
# (il valore se "detail" manca) gpt-6-astra tiene le misure originali: per questo "high" si scrive sempre.
DETTAGLIO = "high"
LATO_PATCH = 32
MOLTIPLICATORE_PATCH = 1.2
LATO_MASSIMO_IMMAGINE = 2048
PATCH_MASSIME_IMMAGINE = 2500
# Margine per i pochi token che racchiudono ogni immagine (la guida non li dice).
TOKEN_IN_PIU_PER_IMMAGINE = 20
# Il testo della richiesta: l'italiano fa circa 4 caratteri a token, qui ne contiamo 3 (per eccesso), più 10 token per
# messaggio.
CARATTERI_PER_TOKEN_PRUDENTE = 3
TOKEN_PER_MESSAGGIO = 10

# Ragionamento e trascrizione insieme. Dai costi della prova del 21/11 il ragionamento "low" si è preso circa 800 token:
# ne restano circa 700 per il testo, cioè circa 2.800 caratteri, più di quanto si leggeva nelle foto della prova
# (1.250-1.700 caratteri). Una pagina più fitta arriva tagliata e passa all'OCR.
TOKEN_USCITA = 1500
COSTO_MASSIMO_EUR = 0.001

LEGENDA = ("Trascrizione di una foto piccola fatta da Lectio. Segni: [[ ]] = sottolineato o evidenziato (più importante); "
           "«Appunto a mano (letto così, da controllare…)» = scritto a mano, da controllare sulla foto; "
           "[?] = parola che non si legge; [tagliato] = riga tagliata dal bordo della foto.")

SISTEMA = ("Trascrivi foto di pagine di scuola (libri, quaderni, fotocopie, slide) per uno studente di liceo. "
           "Rispondi solo con la trascrizione, in testo semplice: niente JSON, niente commenti.")

REGOLE = """Regole:
1. Il testo stampato parola per parola, nell'ordine di lettura (colonne da sinistra a destra, riquadri dopo il testo \
a cui stanno accanto). "## " davanti ai titoli. Riunisci le parole spezzate col trattino a fine riga. Il grassetto \
stampato: tra ** e **. Didascalie: "Didascalia: …".
2. Le parti sottolineate o evidenziate (a matita, a penna, col pennarello): tra [[ e ]], esattamente dove sono, anche \
dentro una frase. Il grassetto stampato non è una sottolineatura.
3. Ogni appunto scritto a mano va su una riga a parte, nel punto della pagina in cui sta, così:
Appunto a mano (letto così, da controllare; accanto a "parole stampate vicine"): quello che c'è scritto
Se una freccia o un asterisco lo lega a una parola stampata, metti quella parola tra le virgolette. Se non riesci a \
leggerlo quasi per niente:
Appunto a mano non leggibile (accanto a "parole stampate vicine").
4. Una parola che non leggi con sicurezza: [?] al suo posto. Meglio [?] che una parola indovinata.
5. Dove il bordo della foto taglia una riga, scrivi solo le parole intere che si vedono e poi [tagliato]. Non \
completare mai.
6. Lascia fuori numeri di pagina, testatine e le pagine accanto che si vedono solo in parte."""


def testo_richiesta(strisce: int) -> str:
    if strisce <= 1:
        inizio = ("L'immagine è una foto, ingrandita per leggere meglio la scrittura a mano e le sottolineature. "
                  "Trascrivila.")
    else:
        inizio = (f"Le {strisce} immagini sono UNA sola foto tagliata in strisce orizzontali, in ordine dall'alto in "
                  "basso, e ingrandite per leggere meglio la scrittura a mano e le sottolineature. Le strisce si "
                  "sovrappongono un po': le righe che si vedono in due strisce scrivile una volta sola. Trascrivi la "
                  "foto.")
    return f"{inizio}\n\n{REGOLE}"


def messaggi(jpeg: list[bytes]) -> list[dict]:
    """La richiesta: le regole, poi le strisce in ordine, tutte con "detail": "high"."""
    contenuto = [{"type": "text", "text": testo_richiesta(len(jpeg))}]
    contenuto += [{"type": "image_url", "image_url": {
        "url": "data:image/jpeg;base64," + base64.b64encode(j).decode("ascii"), "detail": DETTAGLIO}} for j in jpeg]
    return [{"role": "system", "content": SISTEMA}, {"role": "user", "content": contenuto}]


# --------------------------------------------------------------------------- misure e costo

def e_piccola(larghezza: int, altezza: int) -> bool:
    return max(larghezza, altezza) <= LATO_MASSIMO_PICCOLA


def tagli(altezza: int) -> list[tuple[int, int]]:
    """Dove comincia e dove finisce ogni striscia (pixel della foto), dall'alto in basso."""
    altezza = max(1, int(altezza))
    n = max(1, min(STRISCE_MASSIME, math.ceil(altezza / ALTEZZA_STRISCIA)))
    passo = altezza / n
    margine = passo * SOVRAPPOSIZIONE
    return [(max(0, math.floor(i * passo - margine)), min(altezza, math.ceil((i + 1) * passo + margine)))
            for i in range(n)]


def patch(larghezza: int, altezza: int) -> int:
    return math.ceil(larghezza / LATO_PATCH) * math.ceil(altezza / LATO_PATCH)


def token_immagine(larghezza: int, altezza: int) -> int:
    return math.ceil(patch(larghezza, altezza) * MOLTIPLICATORE_PATCH) + TOKEN_IN_PIU_PER_IMMAGINE


def token_testo(strisce: int) -> int:
    """Il testo della richiesta, contato per eccesso."""
    caratteri = len(SISTEMA) + len(testo_richiesta(strisce))
    return math.ceil(caratteri / CARATTERI_PER_TOKEN_PRUDENTE) + 2 * TOKEN_PER_MESSAGGIO


# Il calcolo del caso peggiore (prezzi.json: luna 0,10 $ per milione in entrata, 0,125 $ se OpenAI li scrive in cache,
# 0,50 $ in uscita; cambio 0,86):
#   tetto per foto         0,001 € / 0,86 = 0,0011628 $
#   uscita massima         1.500 token × 0,50 $ / milione = 0,00075 $ (ragionamento compreso: OpenAI non va oltre)
#   resta per l'entrata    0,0004128 $, contata TUTTA al prezzo più alto (0,125 $, cache scritta) = 3.302 token
#   testo della richiesta  1.672 caratteri / 3 + 20 = 578 token (per eccesso)
#   immagini               restano 2.724 token: per ogni striscia patch × 1,2 + 20
# Esempi (calcolati da `piano`, controllati in tests/test_foto_piccole.py):
#   1024×768 (WhatsApp)  3 strisce da 1536×442-501 px, ingrandite 1,5 volte: 2.112 patch, 3.174 token in entrata
#                        → peggio (3.174 × 0,125 + 1.500 × 0,50) / 10^6 × 0,86 = 0,000986 € (0,099 centesimi)
#   768×1024             4 strisce, ingrandite 1,5 volte: 2.160 patch → 0,000995 €
#   1600×1200            4 strisce, ingrandite 0,9 volte (un poco rimpicciolite) → 0,000960 €
#   fino a 800×600       ingrandimento pieno, 2 volte.
# Di solito si spende meno: il ragionamento non usa tutta l'uscita e la cache di solito non si scrive (nella prova del
# 21/11, con strisce non ingrandite, una foto è costata circa 0,07 centesimi).
def costo_peggiore(prezzi, token_entrata: int, token_uscita: int = TOKEN_USCITA) -> float:
    """Euro, con TUTTI i token d'entrata al prezzo più alto (normale o cache scritta) e tutta l'uscita permessa."""
    p = prezzi.testo.get(MODELLO)
    if p is None:
        raise PrezzoSconosciuto(MODELLO)
    entrata = max(p["entrata_per_milione"], p.get("cache_scrittura_per_milione", 0.0))
    return (token_entrata * entrata + token_uscita * p["uscita_per_milione"]) / 1e6 * prezzi.cambio


@dataclass
class Striscia:
    alto: int  # pixel della foto
    basso: int
    larghezza: int  # dopo l'ingrandimento: le misure che vede OpenAI
    altezza: int


@dataclass
class Piano:
    strisce: list[Striscia]
    ingrandimento: float
    token_entrata: int  # per eccesso
    costo_peggiore_eur: float

    @property
    def patch(self) -> int:
        return sum(patch(s.larghezza, s.altezza) for s in self.strisce)


def piano(larghezza: int, altezza: int, prezzi) -> Piano | None:
    """Le strisce e il loro ingrandimento: il più grande (fino a 2 volte, a passi di 0,05) con cui il costo peggiore
    resta entro COSTO_MASSIMO_EUR. None se la foto non è piccola o non ci sta nemmeno con INGRANDIMENTO_MINIMO."""
    if larghezza < 1 or altezza < 1 or not e_piccola(larghezza, altezza):
        return None
    pezzi = tagli(altezza)
    testo = token_testo(len(pezzi))
    passi = round((INGRANDIMENTO_MASSIMO - INGRANDIMENTO_MINIMO) / PASSO_INGRANDIMENTO)
    for k in range(passi + 1):
        f = round(INGRANDIMENTO_MASSIMO - k * PASSO_INGRANDIMENTO, 2)
        strisce = [Striscia(a, b, max(1, round(larghezza * f)), max(1, round((b - a) * f))) for a, b in pezzi]
        if any(max(s.larghezza, s.altezza) > LATO_MASSIMO_IMMAGINE or patch(s.larghezza, s.altezza) > PATCH_MASSIME_IMMAGINE
               for s in strisce):
            continue
        entrata = testo + sum(token_immagine(s.larghezza, s.altezza) for s in strisce)
        costo = costo_peggiore(prezzi, entrata)
        if costo <= COSTO_MASSIMO_EUR:
            return Piano(strisce, f, entrata, costo)
    return None


# --------------------------------------------------------------------------- le immagini

def apri(contenuto: bytes):
    """La foto girata come va vista, in RGB (le parti trasparenti bianche). Il file si legge tutto solo qui."""
    from PIL import Image, ImageOps

    with Image.open(io.BytesIO(contenuto)) as im:
        im = ImageOps.exif_transpose(im)
        if im.mode in ("RGBA", "LA", "P", "PA"):
            con_alfa = im.convert("RGBA")
            foto = Image.new("RGB", con_alfa.size, "white")
            foto.paste(con_alfa, mask=con_alfa.getchannel("A"))
            return foto
        return im.convert("RGB")


def misure(contenuto: bytes) -> tuple[int, int]:
    """Larghezza e altezza senza decodificare la foto (girata o no, il lato lungo è lo stesso)."""
    from PIL import Image

    with Image.open(io.BytesIO(contenuto)) as im:
        return im.size


def strisce_jpeg(foto, p: Piano) -> list[bytes]:
    """Ogni striscia tagliata, ingrandita con Lanczos, un po' più contrastata e nitida, in JPEG."""
    from PIL import Image, ImageEnhance, ImageFilter, ImageOps

    uscita = []
    for s in p.strisce:
        pezzo = foto.crop((0, s.alto, foto.width, s.basso))
        pezzo = pezzo.resize((s.larghezza, s.altezza), Image.Resampling.LANCZOS)
        pezzo = ImageOps.autocontrast(pezzo, cutoff=1, preserve_tone=True)
        pezzo = ImageEnhance.Contrast(pezzo).enhance(1.15)
        pezzo = pezzo.filter(ImageFilter.UnsharpMask(radius=2, percent=70, threshold=3))
        buf = io.BytesIO()
        pezzo.save(buf, "JPEG", quality=QUALITA_JPEG)
        uscita.append(buf.getvalue())
    return uscita


# --------------------------------------------------------------------------- la risposta

_RECINTO = re.compile(r"^\s*```[a-zA-Z]*\s*\n?|\n?\s*```\s*$")
_APPUNTO = re.compile(r"^(\s*(?:[-*•]\s*)?)appunto a mano\b(.*)$", re.IGNORECASE)


def segna_appunti(testo: str) -> str:
    """Ogni appunto a mano letto da una foto piccola è "da controllare", anche se il modello si dimentica di dirlo."""
    righe = []
    for riga in testo.split("\n"):
        m = _APPUNTO.match(riga)
        basso = riga.lower()
        if m and "da controllare" not in basso and "non leggibile" not in basso:
            prima, resto = m.group(1), m.group(2).strip()
            if resto.startswith("("):
                dentro = re.sub(r"^\s*letto così\s*[,;]?\s*", "", resto[1:], flags=re.IGNORECASE)
                if dentro.startswith(")"):
                    riga = f"{prima}Appunto a mano (letto così, da controllare){dentro[1:]}"
                else:
                    riga = f"{prima}Appunto a mano (letto così, da controllare; {dentro}"
            else:
                riga = f"{prima}Appunto a mano (letto così, da controllare): {resto.lstrip(':').strip()}"
        righe.append(riga)
    return "\n".join(righe)


def componi(risposta: str) -> str:
    """Il testo da salvare: la riga dei segni e la trascrizione. Vuoto se la trascrizione è vuota."""
    testo = _RECINTO.sub("", (risposta or "").strip()).strip()
    if not testo:
        return ""
    return f"{LEGENDA}\n\n{segna_appunti(testo)}"


# --------------------------------------------------------------------------- la lettura

def lettore(ctx):
    """Chi legge le foto piccole: OpenAI, se c'è la sua chiave e non si è in prova. Se no None (→ OCR)."""
    if ctx.settings.modalita_prova or not getattr(ctx.ocr, "a_pagamento", False):
        return None
    openai = getattr(ctx.testo, "openai", None)
    if openai is None or not getattr(openai, "a_pagamento", False):
        return None
    return openai


def leggi(ctx, contenuto: bytes, formato: str, *, titolo: str, materiale_id: int | None = None,
          materia_id: int | None = None, riferimento: str | None = None) -> str | None:
    """Il testo di una foto piccola letto da luna; None se va letta con l'OCR (foto grande, PDF, luna che non c'è o
    non riesce). Solo il tetto del mese ferma tutto (TettoRaggiunto): l'OCR costerebbe di più."""
    if formato not in lettura.IMMAGINI:
        return None
    openai = lettore(ctx)
    if openai is None:
        return None
    try:
        larghezza, altezza = misure(contenuto)
    except Exception as e:  # file rotto: l'OCR dirà la sua
        log.warning("materiale %s: non apro la foto per luna (%s), la leggo con l'OCR", materiale_id, type(e).__name__)
        return None
    if not e_piccola(larghezza, altezza):
        return None
    prezzi = ctx.cassa.prezzi
    try:
        p = piano(larghezza, altezza, prezzi)
    except PrezzoSconosciuto:
        log.warning("materiale %s: manca il prezzo di %s, leggo la foto piccola con l'OCR", materiale_id, MODELLO)
        return None
    if p is None:
        log.warning("materiale %s: la foto %s×%s non sta in %s € nemmeno rimpicciolita, la leggo con l'OCR",
                    materiale_id, larghezza, altezza, COSTO_MASSIMO_EUR)
        return None
    try:
        foto = apri(contenuto)
        richiesta = messaggi(strisce_jpeg(foto, p))
    except Exception as e:
        log.warning("materiale %s: non preparo le strisce (%s), leggo la foto con l'OCR", materiale_id, type(e).__name__)
        return None

    def fare():
        try:
            return openai.genera(compito="foto_piccola", messaggi=richiesta, modello=MODELLO, json_atteso=False,
                                 max_token=TOKEN_USCITA, ragionamento=RAGIONAMENTO, dati={})
        except ForseElaborata as e:
            # Forse OpenAI ha già lavorato (e fatto pagare): un altro tentativo potrebbe raddoppiare il costo della foto.
            # La stima resta nel conto e si passa all'OCR.
            raise ErroreFornitore(e.frase, temporaneo=True, elaborata=True) from None

    def registra(ricevuta, risposta):
        cache = int(getattr(risposta, "token_cache", 0) or 0)
        scritti = int(getattr(risposta, "token_cache_scritti", 0) or 0)
        costo = prezzi.costo_testo(MODELLO, risposta.token_entrata, risposta.token_uscita, cache, scritti)
        ricevuta.registra(risposta.token_entrata + risposta.token_uscita, "token", costo, cache=cache)
        if costo > COSTO_MASSIMO_EUR:
            log.warning("materiale %s: la foto piccola è costata %.6f € (più di %s €, %s token in entrata e %s in "
                        "uscita): rivedere i conti di foto_piccole.py", materiale_id, costo, COSTO_MASSIMO_EUR,
                        risposta.token_entrata, risposta.token_uscita)

    try:
        risposta = ctx.cassa.chiama(
            cosa=f"Lettura di «{titolo}» (foto piccola)", fornitore=openai.nome, stima_eur=p.costo_peggiore_eur,
            quantita_stimata=p.token_entrata + TOKEN_USCITA, unita="token", materiale_id=materiale_id,
            materia_id=materia_id, riferimento=riferimento, fare=fare, registra=registra)
    except TettoRaggiunto:
        raise
    except ErroreFornitore as e:
        log.warning("materiale %s: luna non è riuscita a leggere la foto (%s), la leggo con l'OCR", materiale_id, e.frase)
        return None
    except Exception:
        log.warning("materiale %s: errore inatteso leggendo la foto con luna, la leggo con l'OCR", materiale_id,
                    exc_info=True)
        return None
    if getattr(risposta, "troncata", False):
        log.warning("materiale %s: la lettura di luna è arrivata tagliata (%s token in uscita), leggo la foto con l'OCR",
                    materiale_id, risposta.token_uscita)
        return None
    testo = componi(risposta.testo)
    if not testo:
        log.warning("materiale %s: luna ha risposto vuoto, leggo la foto con l'OCR", materiale_id)
        return None
    log.info("materiale %s: foto %s×%s letta da luna (%s strisce, ingrandite %s volte, %s token in entrata)",
             materiale_id, larghezza, altezza, len(p.strisce), p.ingrandimento, risposta.token_entrata)
    return testo
