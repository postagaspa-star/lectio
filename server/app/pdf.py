"""I PDF che Lectio crea: lo schema da stampare e la verifica da fare su carta (contratto 18, 21, 25).

Su ogni pagina, in fondo, c'è la firma in bianco. Nel documento non c'è nessun riferimento a chi
l'ha scritto: nomi delle fonti e crediti delle foto sì, perché servono."""

import html
import io
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (CondPageBreak, Image, KeepTogether, ListFlowable, ListItem, Paragraph, SimpleDocTemplate,
                                Spacer, Table, TableStyle)

from .firma import FIRMA

CARTELLE_FONT = (Path("/usr/share/fonts/truetype/dejavu"),)
COLORE_MATERIA = {"ambra": "#C27C0E", "rosa": "#D6336C", "petrolio": "#0C8599", "viola": "#7048E8", "blu": "#1C7ED6", "verde": "#2B8A3E", "arancio": "#E8590C", "oliva": "#5C940D", "giada": "#00A089", "senape": "#928100", "orchidea": "#C04EC8", "prugna": "#A34A80", "cacao": "#916748", "lavagna": "#636F8E", "salvia": "#7B9575", "lavanda": "#A380C8"}
LARGHEZZA = A4[0] - 36 * mm
INCHIOSTRO = colors.HexColor("#1F2328")
GRIGIO = colors.HexColor("#6B7280")
LINEA = colors.HexColor("#D5D8DC")
_font_pronti = False


def _font() -> tuple[str, str]:
    """DejaVu sul NAS (ha tutti i simboli delle formule); altrimenti quello incluso nella libreria."""
    global _font_pronti
    if not _font_pronti:
        normale, grassetto = None, None
        for cartella in CARTELLE_FONT:
            if (cartella / "DejaVuSans.ttf").is_file():
                normale, grassetto = cartella / "DejaVuSans.ttf", cartella / "DejaVuSans-Bold.ttf"
        if normale is None:
            import reportlab

            base = Path(reportlab.__file__).parent / "fonts"
            normale, grassetto = base / "Vera.ttf", base / "VeraBd.ttf"
        pdfmetrics.registerFont(TTFont("Lectio", str(normale)))
        pdfmetrics.registerFont(TTFont("Lectio-Bold", str(grassetto)))
        pdfmetrics.registerFontFamily("Lectio", normal="Lectio", bold="Lectio-Bold", italic="Lectio", boldItalic="Lectio-Bold")
        _font_pronti = True
    return "Lectio", "Lectio-Bold"


def _stili(colore: colors.Color) -> dict:
    n, g = _font()
    base = ParagraphStyle("base", fontName=n, fontSize=10.5, leading=15, textColor=INCHIOSTRO, alignment=TA_LEFT, spaceAfter=5)
    return {
        "base": base,
        "titolo": ParagraphStyle("titolo", parent=base, fontName=g, fontSize=21, leading=26, spaceAfter=3),
        "sotto": ParagraphStyle("sotto", parent=base, fontSize=10, textColor=GRIGIO, spaceAfter=12),
        "sezione": ParagraphStyle("sezione", parent=base, fontName=g, fontSize=14.5, leading=19, textColor=colore, spaceBefore=12, spaceAfter=6),
        "titoletto": ParagraphStyle("titoletto", parent=base, fontName=g, fontSize=11, leading=15, spaceBefore=4, spaceAfter=3),
        "piccolo": ParagraphStyle("piccolo", parent=base, fontSize=8, leading=11, textColor=GRIGIO, spaceAfter=4),
        "cella": ParagraphStyle("cella", parent=base, fontSize=9.5, leading=13, spaceAfter=0),
        "testata": ParagraphStyle("testata", parent=base, fontName=g, fontSize=9.5, leading=13, spaceAfter=0, textColor=colors.white),
        "prof": ParagraphStyle("prof", parent=base, fontSize=10, leading=14, spaceAfter=0),
        "didascalia": ParagraphStyle("didascalia", parent=base, fontSize=9, leading=12, textColor=GRIGIO, spaceAfter=1),
    }


def _testo(t: str) -> str:
    """Il testo in forma sicura; **parola** diventa grassetto."""
    return re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", html.escape(str(t or ""), quote=False))


def _pagina(numero: bool):
    def disegna(c, doc):
        c.saveState()
        c.setFont("Lectio", 8)
        # La firma: bianca, in fondo a ogni pagina.
        c.setFillColor(colors.white)
        c.drawCentredString(A4[0] / 2, 7 * mm, FIRMA)
        if numero:
            c.setFillColor(GRIGIO)
            c.drawRightString(A4[0] - 18 * mm, 11 * mm, str(doc.page))
        c.restoreState()
    return disegna


def _documento(contenuti: list, titolo: str) -> bytes:
    uscita = io.BytesIO()
    doc = SimpleDocTemplate(uscita, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=17 * mm, bottomMargin=18 * mm,
                            title=titolo, author="", creator="Lectio", subject="")
    fare = _pagina(True)
    doc.build(contenuti, onFirstPage=fare, onLaterPages=fare)
    return uscita.getvalue()


def _elenco(voci: list[str], s: dict, numerato: bool = False) -> ListFlowable:
    return ListFlowable([ListItem(Paragraph(_testo(v), s["base"]), leftIndent=13) for v in voci],
                        bulletType="1" if numerato else "bullet", bulletFontName="Lectio", bulletFontSize=9 if numerato else 6,
                        start=None if numerato else "•", leftIndent=13)


def _tabella(b: dict, s: dict, colore) -> Table:
    intestazione = b.get("intestazione") or []
    righe = b.get("righe") or []
    colonne = max([len(intestazione), *[len(r) for r in righe], 1])
    dati = []
    if intestazione:
        dati.append([Paragraph(_testo(c), s["testata"]) for c in intestazione] + [""] * (colonne - len(intestazione)))
    for r in righe:
        dati.append([Paragraph(_testo(c), s["cella"]) for c in r] + [""] * (colonne - len(r)))
    t = Table(dati, colWidths=[LARGHEZZA / colonne] * colonne, repeatRows=1 if intestazione else 0)
    stile = [("GRID", (0, 0), (-1, -1), 0.4, LINEA), ("VALIGN", (0, 0), (-1, -1), "TOP"), ("TOPPADDING", (0, 0), (-1, -1), 4),
             ("BOTTOMPADDING", (0, 0), (-1, -1), 5)]
    if intestazione:
        stile.append(("BACKGROUND", (0, 0), (-1, 0), colore))
    t.setStyle(TableStyle(stile))
    return t


def _riquadro(contenuto: Paragraph, colore) -> Table:
    t = Table([[contenuto]], colWidths=[LARGHEZZA])
    t.setStyle(TableStyle([("LINEBEFORE", (0, 0), (0, -1), 2.5, colore), ("LEFTPADDING", (0, 0), (-1, -1), 9),
                           ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 5)]))
    return t


def _foto(percorso: Path, massimo_alto: float) -> Image | None:
    try:
        larghezza, altezza = ImageReader(str(percorso)).getSize()
    except (OSError, ValueError):
        return None
    scala = min(LARGHEZZA * 0.72 / larghezza, massimo_alto / altezza)
    im = Image(str(percorso), width=larghezza * scala, height=altezza * scala)
    im.hAlign = "LEFT"
    return im


def _blocco(b: dict, s: dict, colore, foto: Path) -> list:
    tipo = b.get("tipo")
    uscita: list = []
    if b.get("titolo") and tipo != "immagine":
        uscita.append(Paragraph(_testo(b["titolo"]), s["titoletto"]))
    if tipo == "paragrafo":
        uscita.append(Paragraph(_testo(b.get("testo")), s["base"]))
    elif tipo == "punti":
        uscita.append(_elenco(b.get("voci") or [], s))
    elif tipo == "passaggi":
        uscita.append(_elenco(b.get("voci") or [], s, numerato=True))
    elif tipo == "definizione":
        uscita.append(Paragraph(f"<b>{_testo(b.get('termine'))}</b>: {_testo(b.get('testo'))}", s["base"]))
    elif tipo == "tabella":
        uscita += [_tabella(b, s, colore), Spacer(1, 6)]
    elif tipo == "prof":
        uscita += [_riquadro(Paragraph(_testo(b.get("testo")), s["prof"]), colore), Spacer(1, 6)]
    elif tipo == "immagine":
        im = _foto(foto / f"{b.get('n')}.jpg", 80 * mm)
        if im is not None:
            uscita.append(KeepTogether([im, Spacer(1, 2), Paragraph(_testo(b.get("didascalia")), s["didascalia"]),
                                        Paragraph(_testo(b.get("credito")), s["piccolo"])]))
    return uscita


def schema_pdf(riga: dict, contenuto: dict, materia: dict, cartella_foto: Path) -> bytes:
    colore = colors.HexColor(COLORE_MATERIA.get(materia.get("colore"), COLORE_MATERIA["ambra"]))
    s = _stili(colore)
    fonti = contenuto.get("fonti") or {}
    sotto = " · ".join(x for x in (materia.get("nome"), materia.get("prof")) if x)
    storia: list = [Paragraph(_testo(riga.get("titolo") or riga.get("argomento")), s["titolo"]), Paragraph(_testo(sotto), s["sotto"])]
    for sezione in contenuto.get("sezioni") or []:
        storia += [CondPageBreak(30 * mm), Paragraph(_testo(sezione.get("titolo")), s["sezione"])]
        if sezione.get("campi"):
            righe = [[Paragraph(f"<b>{_testo(c.get('nome'))}</b>", s["cella"]), Paragraph(_testo(c.get("valore")), s["cella"])]
                     for c in sezione["campi"]]
            t = Table(righe, colWidths=[LARGHEZZA * 0.26, LARGHEZZA * 0.74])
            t.setStyle(TableStyle([("LINEBELOW", (0, 0), (-1, -1), 0.3, LINEA), ("VALIGN", (0, 0), (-1, -1), "TOP")]))
            storia += [t, Spacer(1, 7)]
        usate: list[str] = []
        for b in sezione.get("blocchi") or []:
            storia += _blocco(b, s, colore, cartella_foto)
            for i in b.get("fonti") or []:
                nome = (fonti.get(i) or {}).get("etichetta")
                if nome and nome not in usate:
                    usate.append(nome)
        if usate:
            storia.append(Paragraph(_testo("Fonti: " + "; ".join(usate)), s["piccolo"]))
    return _documento(storia, str(riga.get("titolo") or "Schema"))


def simulata_pdf(riga: dict, domande: list[dict], materia: dict) -> bytes:
    colore = colors.HexColor(COLORE_MATERIA.get(materia.get("colore"), COLORE_MATERIA["ambra"]))
    s = _stili(colore)
    sotto = " · ".join(x for x in (materia.get("nome"), materia.get("prof"), f"{riga.get('minuti')} minuti") if x)
    storia: list = [Paragraph(_testo(riga.get("titolo") or riga.get("argomento")), s["titolo"]), Paragraph(_testo(sotto), s["sotto"]),
                    Paragraph("Nome e cognome: ________________________________     Data: ______________", s["base"]), Spacer(1, 8)]
    sezione_attuale = None
    for d in domande:
        parti = []
        if d.get("sezione") and d["sezione"] != sezione_attuale:
            sezione_attuale = d["sezione"]
            parti.append(Paragraph(_testo(sezione_attuale), s["sezione"]))
        punti = f"  <font color='#6B7280'>({d.get('punti')} punti)</font>" if d.get("punti") else ""
        parti.append(Paragraph(f"<b>{d.get('n')}.</b> {_testo(d.get('testo'))}{punti}", s["base"]))
        righe_vuote = 9 if d.get("tipo") in ("aperta", "collegamento", "esercizio") else 4
        spazi = Table([[""]] * righe_vuote, colWidths=[LARGHEZZA], rowHeights=[8 * mm] * righe_vuote)
        spazi.setStyle(TableStyle([("LINEBELOW", (0, 0), (-1, -1), 0.3, LINEA)]))
        parti += [spazi, Spacer(1, 10)]
        storia.append(KeepTogether(parti))
    return _documento(storia, str(riga.get("titolo") or "Verifica"))


def riassunto_pdf(riga: dict, contenuto: dict, materia: dict, nome_forma: str) -> bytes:
    colore = colors.HexColor(COLORE_MATERIA.get(materia.get("colore"), COLORE_MATERIA["ambra"]))
    s = _stili(colore)
    sotto = " · ".join(x for x in (materia.get("nome"), materia.get("prof"), nome_forma) if x)
    storia: list = [Paragraph(_testo(riga.get("titolo")), s["titolo"]), Paragraph(_testo(sotto), s["sotto"])]
    for parte in contenuto.get("parti") or []:
        if parte.get("titolo"):
            storia += [CondPageBreak(30 * mm), Paragraph(_testo(parte["titolo"]), s["sezione"])]
        storia += [Paragraph(_testo(p), s["base"]) for p in parte.get("paragrafi") or []]
        if parte.get("punti"):
            storia.append(_elenco(parte["punti"], s))
    return _documento(storia, str(riga.get("titolo") or "Riassunto"))
