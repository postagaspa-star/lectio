"""Lettura del materiale (contratto, sezione 6): testo, Markdown, PDF, Word, ODT, RTF
gratis sul NAS; foto e pagine scansionate restano all'OCR a pagamento (ocr.py).

Niente programmi esterni: Word e ODT sono archivi zip con dentro XML (letti a flusso, con
un limite), l'RTF si legge con un piccolo interprete qui sotto, il PDF con pypdf.

Il PDF si decide pagina per pagina: le pagine con testo si leggono gratis, quelle vuote
(scansioni) vanno all'OCR. All'OCR si mandano al massimo le pagine che servono per arrivare
a 200.000 caratteri, e lo si dice con un avviso.

Qualsiasi file costruito male diventa FileIlleggibile (→ 422), mai un errore interno."""

import io
import logging
import math
import re
import zipfile
from dataclasses import dataclass, field
from datetime import date
from xml.etree import ElementTree

from . import clock
from .config import MASSIMO_CARATTERI

log = logging.getLogger("uvicorn.error")

TESTI = {"txt", "md"}
DOCUMENTI = {"pdf", "docx", "odt", "rtf"}
IMMAGINI = {"jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp"}
FORMATI = TESTI | DOCUMENTI | set(IMMAGINI)
FORMATI_IN_PAROLE = ".txt .md .pdf .docx .odt .rtf .jpg .jpeg .png .webp"

# Una pagina di PDF con meno di questi caratteri è una scansione (o è vuota): serve l'OCR.
CARATTERI_PER_PAGINA_MINIMI = 40
# Per decidere quante pagine mandare all'OCR: una pagina fitta di quaderno o di libro.
CARATTERI_PER_PAGINA_OCR = 2_500
# L'XML dentro un Word o un ODT: il testo vero di un documento sta ben sotto.
XML_MASSIMO_BYTE = 10 * 1024 * 1024
# <text:s text:c="…"/> sono spazi ripetuti: più di così è un file costruito apposta.
SPAZI_MASSIMI = 50

FRASE_ROVINATO = "Non riesco a leggere questo file: forse è rovinato. Prova a salvarlo di nuovo o in PDF."


class FileIlleggibile(Exception):
    def __init__(self, frase: str):
        super().__init__(frase)
        self.frase = frase


@dataclass
class Letto:
    testo: str | None  # il testo letto sul NAS; None = tutto all'OCR
    pagine: int
    mime: str
    pagine_ocr: list[int] = field(default_factory=list)  # pagine (da 0) da mandare all'OCR
    testi_pagine: list[str] | None = None  # PDF: il testo di ogni pagina ("" per quelle all'OCR)
    avviso: str | None = None


def estensione(nome_file: str | None) -> str:
    if not nome_file or "." not in nome_file:
        return ""
    return nome_file.rsplit(".", 1)[1].strip().lower()


def pulisci(testo: str) -> str:
    testo = testo.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    testo = re.sub(r"[\x01-\x08\x0b\x0c\x0e-\x1f\x7f]", "", testo)
    righe = [r.rstrip() for r in testo.split("\n")]
    testo = "\n".join(righe)
    testo = re.sub(r"\n{3,}", "\n\n", testo)
    return testo.strip()


def _decodifica(contenuto: bytes) -> str:
    for codifica in ("utf-8-sig", "utf-16"):
        try:
            if codifica == "utf-16" and not contenuto[:2] in (b"\xff\xfe", b"\xfe\xff"):
                continue
            return contenuto.decode(codifica)
        except UnicodeDecodeError:
            continue
    return contenuto.decode("cp1252", errors="replace")


# --------------------------------------------------------------------------- PDF

def _lettore_pdf(contenuto: bytes):
    from pypdf import PdfReader

    lettore = PdfReader(io.BytesIO(contenuto))
    if lettore.is_encrypted:
        try:
            lettore.decrypt("")
        except Exception:
            raise FileIlleggibile("Il PDF è protetto da una password: toglila e caricalo di nuovo.") from None
    return lettore


def _pdf(contenuto: bytes) -> Letto:
    try:
        lettore = _lettore_pdf(contenuto)
        pagine = len(lettore.pages)
        testi = []
        for pagina in lettore.pages:
            try:
                testi.append(pulisci(pagina.extract_text() or ""))
            except Exception:  # una pagina rotta non deve fermare le altre
                testi.append("")
    except FileIlleggibile:
        raise
    except Exception:
        raise FileIlleggibile("Questo PDF è rovinato o non è un PDF vero: non riesco ad aprirlo.") from None
    if pagine == 0:
        raise FileIlleggibile("Questo PDF non ha pagine.")
    vuote = [i for i, t in enumerate(testi) if len(re.sub(r"\s", "", t)) < CARATTERI_PER_PAGINA_MINIMI]
    for i in vuote:
        testi[i] = ""
    if not vuote:
        return Letto(pulisci("\n\n".join(testi)), pagine, "application/pdf", testi_pagine=testi)
    # Le pagine vuote vanno all'OCR, ma solo quante ne servono per arrivare a 200.000 caratteri.
    gia = sum(len(t) for t in testi)
    massime = max(0, math.ceil((MASSIMO_CARATTERI - gia) / CARATTERI_PER_PAGINA_OCR))
    da_ocr = vuote[:massime]
    avviso = None
    if len(da_ocr) < len(vuote):
        if da_ocr:
            avviso = (f"Questo PDF ha {len(vuote)} pagine da leggere come foto: leggo solo le prime {len(da_ocr)}, "
                      f"il resto supererebbe i 200.000 caratteri. Per il resto carica un PDF a parte.")
        else:
            avviso = ("Le pagine scansionate di questo PDF non le leggo: il testo supera già i 200.000 caratteri. "
                      "Caricale in un PDF a parte.")
    testo = None if da_ocr else pulisci("\n\n".join(testi))
    return Letto(testo, pagine, "application/pdf", pagine_ocr=da_ocr, testi_pagine=testi, avviso=avviso)


def sotto_pdf(contenuto: bytes, indici: list[int]) -> bytes:
    """Un PDF con solo le pagine indicate (quelle da mandare all'OCR)."""
    from pypdf import PdfWriter

    lettore = _lettore_pdf(contenuto)
    scrittore = PdfWriter()
    for i in indici:
        scrittore.add_page(lettore.pages[i])
    uscita = io.BytesIO()
    scrittore.write(uscita)
    return uscita.getvalue()


def unisci_pagine(testi_pagine: list[str], pagine_ocr: list[int], letti: list[str]) -> str:
    """Rimette il testo letto dall'OCR al posto delle pagine vuote, nell'ordine giusto."""
    testi = list(testi_pagine)
    if len(letti) == len(pagine_ocr):
        for i, t in zip(pagine_ocr, letti):
            testi[i] = t
    elif pagine_ocr:  # l'OCR ha restituito un numero diverso di pagine: tutto al posto della prima
        testi[pagine_ocr[0]] = "\n\n".join(letti)
    return pulisci("\n\n".join(t for t in testi if t))


# --------------------------------------------------------------------------- Word e ODT

def _xml_dallo_zip(contenuto: bytes, nome: str, formato: str) -> bytes:
    """L'XML del documento, letto a flusso e mai oltre XML_MASSIMO_BYTE (anche se l'archivio
    dichiara una dimensione falsa)."""
    try:
        with zipfile.ZipFile(io.BytesIO(contenuto)) as z:
            info = z.getinfo(nome)
            if info.file_size > XML_MASSIMO_BYTE:
                raise FileIlleggibile(f"Questo file {formato} ha troppo testo da leggere: dividilo in parti.")
            with z.open(info) as f:
                dati = f.read(XML_MASSIMO_BYTE + 1)
    except FileIlleggibile:
        raise
    except Exception:
        raise FileIlleggibile(f"Questo file {formato} è rovinato o non è un {formato} vero: non riesco ad aprirlo.") from None
    if len(dati) > XML_MASSIMO_BYTE:
        raise FileIlleggibile(f"Questo file {formato} ha troppo testo da leggere: dividilo in parti.")
    return dati


def _paragrafi_xml(dati: bytes, formato: str, tag_paragrafo: tuple[str, ...], testo_di) -> list[str]:
    """Scorre l'XML a flusso: ogni paragrafo finito si trasforma in testo e si libera."""
    paragrafi, caratteri = [], 0
    try:
        for _, el in ElementTree.iterparse(io.BytesIO(dati), events=("end",)):
            if el.tag in tag_paragrafo:
                testo = testo_di(el)
                paragrafi.append(testo)
                caratteri += len(testo)
                el.clear()
                if caratteri > 2 * MASSIMO_CARATTERI:
                    break  # troppo testo comunque: lo dirà il controllo dei 200.000 caratteri
    except ElementTree.ParseError:
        raise FileIlleggibile(f"Questo file {formato} è rovinato: non riesco a leggerne il testo.") from None
    return paragrafi


W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def _testo_docx(p: ElementTree.Element) -> str:
    pezzi = []
    for el in p.iter():  # iter() non usa la ricorsione di Python
        if el.tag == f"{W}t" and el.text:
            pezzi.append(el.text)
        elif el.tag == f"{W}tab":
            pezzi.append("\t")
        elif el.tag in (f"{W}br", f"{W}cr"):
            pezzi.append("\n")
    return "".join(pezzi)


def _docx(contenuto: bytes) -> Letto:
    dati = _xml_dallo_zip(contenuto, "word/document.xml", "Word")
    paragrafi = _paragrafi_xml(dati, "Word", (f"{W}p",), _testo_docx)
    return Letto(pulisci("\n".join(paragrafi)), 1,
                 "application/vnd.openxmlformats-officedocument.wordprocessingml.document")


T = "{urn:oasis:names:tc:opendocument:xmlns:text:1.0}"


def _spazi(el: ElementTree.Element) -> str:
    try:
        quanti = int(el.get(f"{T}c", "1") or 1)
    except ValueError:
        quanti = 1
    return " " * max(1, min(quanti, SPAZI_MASSIMI))


def _testo_odt(radice: ElementTree.Element) -> str:
    """Il testo di un paragrafo ODT nell'ordine giusto, con una pila (niente ricorsione:
    un file con migliaia di livelli annidati non deve far cadere il server)."""
    pezzi = [radice.text or ""]
    pila = [(figlio, False) for figlio in reversed(list(radice))]
    while pila:
        el, chiuso = pila.pop()
        if chiuso:
            pezzi.append(el.tail or "")
            continue
        if el.tag == f"{T}s":
            pezzi.append(_spazi(el))
        elif el.tag == f"{T}tab":
            pezzi.append("\t")
        elif el.tag == f"{T}line-break":
            pezzi.append("\n")
        elif el.tag != f"{T}note":
            pezzi.append(el.text or "")
            pila.append((el, True))
            pila.extend((figlio, False) for figlio in reversed(list(el)))
            continue
        pezzi.append(el.tail or "")
    return "".join(pezzi)


def _odt(contenuto: bytes) -> Letto:
    dati = _xml_dallo_zip(contenuto, "content.xml", "ODT")
    paragrafi = _paragrafi_xml(dati, "ODT", (f"{T}p", f"{T}h"), _testo_odt)
    return Letto(pulisci("\n".join(paragrafi)), 1, "application/vnd.oasis.opendocument.text")


# --------------------------------------------------------------------------- RTF

_RTF = re.compile(r"\\([a-z]{1,32})(-?\d{1,10})?[ ]?|\\'([0-9a-f]{2})|\\([^a-z])|([{}])|[\r\n]+|(.)", re.I | re.S)
_RTF_DESTINAZIONI = {
    "aftncn", "aftnsep", "aftnsepc", "annotation", "atnauthor", "atndate", "atnicn", "atnid", "atnparent", "atnref",
    "atntime", "atrfend", "atrfstart", "author", "background", "bkmkend", "bkmkstart", "blipuid", "buptim",
    "category", "colorschememapping", "colortbl", "comment", "company", "creatim", "datafield", "datastore",
    "defchp", "defpap", "do", "doccomm", "docvar", "dptxbxtext", "ebcend", "ebcstart", "factoidname", "falt",
    "fchars", "ffdeftext", "ffentrymcr", "ffexitmcr", "ffformat", "ffhelptext", "ffl", "ffname", "ffstattext",
    "fldinst", "fontemb", "fontfile", "fonttbl", "footer", "footerf", "footerl", "footerr", "footnote",
    "formfield", "ftncn", "ftnsep", "ftnsepc", "g", "generator", "gridtbl", "header", "headerf", "headerl",
    "headerr", "hl", "hlfr", "hlinkbase", "hlloc", "hlsrc", "hsv", "htmltag", "info", "keycode", "keywords",
    "latentstyles", "lchars", "levelnumbers", "leveltext", "lfolevel", "linkval", "list", "listlevel", "listname",
    "listoverride", "listoverridetable", "listpicture", "liststylename", "listtable", "listtext", "lsdlockedexcept",
    "macc", "maccPr", "mailmerge", "maln", "malnScr", "manager", "margPr", "mbar", "mbarPr", "mbaseJc", "mbegChr",
    "mborderBox", "mborderBoxPr", "mbox", "mboxPr", "mchr", "mcount", "mctrlPr", "md", "mdeg", "mdegHide", "mden",
    "mdiff", "mdPr", "me", "mendChr", "meqArr", "meqArrPr", "mf", "mfName", "mfPr", "mfunc", "mfuncPr", "mgroupChr",
    "mgroupChrPr", "mgrow", "mhideBot", "mhideLeft", "mhideRight", "mhideTop", "mhtmltag", "mlim", "mlimloc",
    "mlimlow", "mlimlowPr", "mlimupp", "mlimuppPr", "mm", "mmaddfieldname", "mmath", "mmathPict", "mmathPr",
    "mmaxdist", "mmc", "mmcJc", "mmconnectstr", "mmconnectstrdata", "mmcPr", "mmcs", "mmdatasource",
    "mmheadersource", "mmmailsubject", "mmodso", "mmodsofilter", "mmodsofldmpdata", "mmodsomappedname",
    "mmodsoname", "mmodsorecipdata", "mmodsosort", "mmodsosrc", "mmodsotable", "mmodsoudl", "mmodsoudldata",
    "mmodsouniquetag", "mmPr", "mmquery", "mmr", "mnary", "mnaryPr", "mnoBreak", "mnum", "mobjDist", "moMath",
    "moMathPara", "moMathParaPr", "mopEmu", "mphant", "mphantPr", "mplcHide", "mpos", "mr", "mrad", "mradPr",
    "mrPr", "msepChr", "mshow", "mshp", "msPre", "msPrePr", "msSub", "msSubPr", "msSubSup", "msSubSupPr", "msSup",
    "msSupPr", "mstrikeBLTR", "mstrikeH", "mstrikeTLBR", "mstrikeV", "msub", "msubHide", "msup", "msupHide",
    "mtransp", "mtype", "mvertJc", "mvfmf", "mvfml", "mvtof", "mvtol", "mzeroAsc", "mzeroDesc", "mzeroWid",
    "nesttableprops", "nextfile", "nonesttables", "objalias", "objclass", "objdata", "object", "objname",
    "objsect", "objtime", "oldcprops", "oldpprops", "oldsprops", "oldtprops", "oleclsid", "operator", "panose",
    "password", "passwordhash", "pgp", "pgptbl", "picprop", "pict", "pn", "pnseclvl", "pntext", "pntxta",
    "pntxtb", "printim", "private", "propname", "protend", "protstart", "protusertbl", "pxe", "result",
    "revtbl", "revtim", "rsidtbl", "rxe", "shp", "shpgrp", "shpinst", "shppict", "shprslt", "shptxt", "sn", "sp",
    "staticval", "stylesheet", "subject", "sv", "svb", "tc", "template", "themedata", "title", "txe", "ud",
    "upr", "userprops", "wgrffmtfilter", "windowcaption", "writereservation", "writereservhash", "xe", "xform",
    "xmlattrname", "xmlattrvalue", "xmlclose", "xmlname", "xmlnstbl", "xmlopen",
}
_RTF_SPECIALI = {"par": "\n", "sect": "\n\n", "page": "\n\n", "line": "\n", "tab": "\t", "emdash": "—",
                 "endash": "–", "emspace": " ", "enspace": " ", "qmspace": " ", "bullet": "•", "lquote": "‘",
                 "rquote": "’", "ldblquote": "“", "rdblquote": "”", "row": "\n", "cell": " "}


def _rtf(contenuto: bytes) -> Letto:
    testo_rtf = contenuto.decode("latin-1")
    if not testo_rtf.lstrip().startswith("{\\rtf"):
        raise FileIlleggibile("Questo file RTF è rovinato o non è un RTF vero: non riesco a leggerlo.")
    pila: list[tuple[int, bool]] = []
    ignora = False
    salta_unicode = 1
    da_saltare = 0
    uscita: list[str] = []
    for m in _RTF.finditer(testo_rtf):
        parola, arg, esadecimale, carattere, graffa, testo = m.groups()
        if graffa:
            da_saltare = 0
            if graffa == "{":
                pila.append((salta_unicode, ignora))
            elif pila:
                salta_unicode, ignora = pila.pop()
        elif carattere:
            da_saltare = 0
            if carattere == "~":
                if not ignora:
                    uscita.append("\xa0")
            elif carattere in "{}\\":
                if not ignora:
                    uscita.append(carattere)
            elif carattere == "*":
                ignora = True
        elif parola:
            da_saltare = 0
            if parola in _RTF_DESTINAZIONI:
                ignora = True
            elif ignora:
                pass
            elif parola in _RTF_SPECIALI:
                uscita.append(_RTF_SPECIALI[parola])
            elif parola == "uc":
                salta_unicode = int(arg or 1)
            elif parola == "u":
                codice = int(arg or 0)
                if codice < 0:
                    codice += 0x10000
                if 0 < codice < 0x110000:
                    uscita.append(chr(codice))
                da_saltare = salta_unicode
        elif esadecimale:
            if da_saltare > 0:
                da_saltare -= 1
            elif not ignora:
                uscita.append(bytes([int(esadecimale, 16)]).decode("cp1252", errors="replace"))
        elif testo:
            if da_saltare > 0:
                da_saltare -= 1
            elif not ignora:
                uscita.append(testo)
    return Letto(pulisci("".join(uscita)), 1, "application/rtf")


# --------------------------------------------------------------------------- ingresso

def _leggi(contenuto: bytes, est: str) -> Letto:
    if est in TESTI:
        return Letto(pulisci(_decodifica(contenuto)), 1, "text/plain")
    if est == "pdf":
        return _pdf(contenuto)
    if est == "docx":
        return _docx(contenuto)
    if est == "odt":
        return _odt(contenuto)
    if est == "rtf":
        return _rtf(contenuto)
    if est in IMMAGINI:
        return Letto(None, 1, IMMAGINI[est], pagine_ocr=[0])
    raise FileIlleggibile(f"Questo tipo di file non lo so leggere. Vanno bene: {FORMATI_IN_PAROLE}.")


def leggi(contenuto: bytes, est: str) -> Letto:
    """Il testo del file e le pagine da mandare all'OCR (foto, pagine scansionate).
    Qualsiasi errore di un file costruito male diventa FileIlleggibile."""
    try:
        return _leggi(contenuto, est)
    except FileIlleggibile:
        raise
    except Exception as e:  # zip strani, XML annidato all'infinito, memoria, …
        log.warning("file illeggibile (%s): %s", est, type(e).__name__)
        raise FileIlleggibile(FRASE_ROVINATO) from None


# --------------------------------------------------------------------------- testi di Gemini

_RIGA_GEMINI = re.compile(r"^\s*\**\s*MATERIA\s*:.*\|.*TIPO\s*:", re.IGNORECASE | re.MULTILINE)
_MESI_NUMERO = {nome: i + 1 for i, nome in enumerate(clock.MESI)}


@dataclass
class TestataGemini:
    materia: str
    prof: str
    data: str
    tipo: str
    voto: str


def testata_gemini(testo: str) -> TestataGemini | None:
    """La riga `MATERIA: … | PROF: … | DATA: … | TIPO: … | VOTO: …` dei testi di Gemini."""
    m = _RIGA_GEMINI.search(testo[:3000])
    if not m:
        return None
    riga = testo[m.start():].split("\n", 1)[0].replace("*", "")
    campi = {}
    for parte in riga.split("|"):
        if ":" in parte:
            chiave, valore = parte.split(":", 1)
            campi[chiave.strip().upper()] = valore.strip()
    return TestataGemini(campi.get("MATERIA", ""), campi.get("PROF", ""), campi.get("DATA", ""),
                         campi.get("TIPO", ""), campi.get("VOTO", ""))


def data_da_testo(testo: str) -> date | None:
    testo = (testo or "").strip().lower()
    m = re.search(r"(\d{4})-(\d{1,2})-(\d{1,2})", testo)
    try:
        if m:
            return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        m = re.search(r"(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})", testo)
        if m:
            anno = int(m.group(3))
            anno = anno + 2000 if anno < 100 else anno
            return date(anno, int(m.group(2)), int(m.group(1)))
        m = re.search(r"(\d{1,2})\s+([a-zàèéìòù]+)\s+(\d{4})", testo)
        if m and m.group(2) in _MESI_NUMERO:
            return date(int(m.group(3)), _MESI_NUMERO[m.group(2)], int(m.group(1)))
    except ValueError:
        return None
    return None


def tipo_da_gemini(t: TestataGemini) -> str | None:
    tipo = t.tipo.lower()
    if "oral" in tipo or "interrog" in tipo:
        return "interrogazione"
    if "scritt" in tipo or "verifica" in tipo or "compito" in tipo:
        return "verifica"
    return None


def titolo_predefinito(testo: str | None, nome_file: str | None, origine: str, tipo: str, giorno: date) -> str:
    quando = f"{giorno.day} {clock.MESI[giorno.month - 1]}"
    if testo:
        t = testata_gemini(testo)
        if t:
            parola = "Interrogazione" if tipo_da_gemini(t) == "interrogazione" else "Verifica"
            d = data_da_testo(t.data)
            if d:
                return f"{parola} {d.day} {clock.MESI[d.month - 1]}"
            return f"{parola} {t.data}".strip()[:120] if t.data else parola
    if origine == "foto":
        return f"Foto {quando}"
    if origine == "file" and nome_file:
        radice = nome_file.rsplit(".", 1)[0] if "." in nome_file else nome_file
        radice = re.sub(r"[_\-]+", " ", radice).strip()
        if radice:
            return radice[:120]
    if testo:
        prima = next((r.strip(" #*") for r in testo.split("\n") if r.strip(" #*")), "")
        if prima:
            return prima[:80]
    nomi = {"verifica": "Verifica", "interrogazione": "Interrogazione", "appunti": "Appunti", "libro": "Libro",
            "altro": "Materiale"}
    return f"{nomi.get(tipo, 'Materiale')} {quando}"
