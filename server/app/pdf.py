"""I PDF che Lectio crea: lo schema da stampare e la verifica da fare su carta (contratto 18, 21, 25).

Su ogni pagina, in fondo, c'è la firma in bianco. Nel documento non c'è nessun riferimento a chi
l'ha scritto: nomi delle fonti e crediti delle foto sì, perché servono."""

import html
import io
import math
import re
from pathlib import Path

from reportlab.graphics.shapes import Circle, Drawing, Line, PolyLine, Rect, String
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


def _numero_breve(v: float) -> str:
    t = f"{v:.6f}".rstrip("0").rstrip(".")
    return ("0" if t in ("-0", "") else t).replace(".", ",")


def _tacche(a: float, b: float, quante: int = 5) -> list[float]:
    """Tacche "tonde" (1, 2, 2,5, 5 per una potenza di dieci) che coprono da a a b."""
    if a == b:
        a, b = a - 1, b + 1
    grezzo = (b - a) / quante
    scala = 10 ** math.floor(math.log10(grezzo))
    passo = next(m * scala for m in (1, 2, 2.5, 5, 10) if m * scala >= grezzo)
    inizio, fine = math.floor(a / passo) * passo, math.ceil(b / passo) * passo
    return [round(inizio + i * passo, 10) for i in range(int(round((fine - inizio) / passo)) + 1)]


def _intervallo(valori: list[float]) -> tuple[float, float]:
    """Un po' di spazio intorno ai punti; dati tutti positivi e vicini allo 0 partono da 0, come sul quaderno."""
    a, b = min(valori), max(valori)
    margine = (b - a) * 0.04
    a, b = a - margine, b + margine
    if min(valori) >= 0 and min(valori) <= 0.5 * max(valori):
        a = 0.0
    return a, b


def _tabella_pdf(tab: dict, s: dict, colore) -> Table:
    righe = [[Paragraph(_testo(c), s["testata"]) for c in tab["colonne"]]]
    righe += [[Paragraph(_testo(c), s["cella"]) for c in r] for r in tab["valori"]]
    t = Table(righe, hAlign="LEFT", repeatRows=1)
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colore), ("GRID", (0, 0), (-1, -1), 0.4, LINEA),
                           ("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("LEFTPADDING", (0, 0), (-1, -1), 6),
                           ("RIGHTPADDING", (0, 0), (-1, -1), 8)]))
    return t


def _grafico_pdf(g: dict, colore) -> Drawing:
    """Il grafico della domanda (contratto 21.1), disegnato a mano: assi, tacche, punti, linee o barre."""
    font, _ = _font()
    larghezza, altezza = LARGHEZZA * 0.72, 64 * mm
    sx, dx, su, giu = 16 * mm, 4 * mm, 6 * mm, 11 * mm
    d = Drawing(larghezza, altezza)
    barre = g["tipo"] == "barre"
    if barre:
        xs = [-0.5, len(g["categorie"]) - 0.5]
        ys = [0.0] + [float(v) for s in g["serie"] for v in s["valori"]]
    else:
        xs = [float(p[0]) for s in g["serie"] for p in s["punti"]]
        ys = [float(p[1]) for s in g["serie"] for p in s["punti"]]
    tx = None if barre else _tacche(*_intervallo(xs))
    ty = _tacche(*_intervallo(ys))
    x0, x1 = (xs[0], xs[1]) if barre else (tx[0], tx[-1])
    y0, y1 = ty[0], ty[-1]
    pw, ph = larghezza - sx - dx, altezza - su - giu

    def px(x):
        return sx + (x - x0) / (x1 - x0) * pw

    def py(y):
        return giu + (y - y0) / (y1 - y0) * ph

    for y in ty:
        d.add(Line(sx, py(y), sx + pw, py(y), strokeColor=LINEA, strokeWidth=0.3))
        d.add(String(sx - 2, py(y) - 2.5, _numero_breve(y), fontName=font, fontSize=7, fillColor=GRIGIO, textAnchor="end"))
    for x in tx or []:
        d.add(Line(px(x), giu, px(x), giu + ph, strokeColor=LINEA, strokeWidth=0.3))
        d.add(String(px(x), giu - 9, _numero_breve(x), fontName=font, fontSize=7, fillColor=GRIGIO, textAnchor="middle"))
    asse_y = px(0) if not barre and x0 <= 0 <= x1 else sx
    asse_x = py(0) if y0 <= 0 <= y1 else giu
    d.add(Line(sx, asse_x, sx + pw, asse_x, strokeColor=INCHIOSTRO, strokeWidth=0.8))
    d.add(Line(asse_y, giu, asse_y, giu + ph, strokeColor=INCHIOSTRO, strokeWidth=0.8))
    tinte = [colore, colors.HexColor("#636F8E"), colors.HexColor("#C27C0E"), colors.HexColor("#2B8A3E"), colors.HexColor("#A34A80")]
    if barre:
        quante = len(g["serie"])
        for i, nome in enumerate(g["categorie"]):
            d.add(String(px(i), giu - 9, nome[:14], fontName=font, fontSize=7, fillColor=GRIGIO, textAnchor="middle"))
            for k, s in enumerate(g["serie"]):
                largo = 0.7 / quante
                sinistra = px(i - 0.35 + k * largo)
                v = float(s["valori"][i])
                d.add(Rect(sinistra, min(asse_x, py(v)), px(i - 0.35 + (k + 1) * largo) - sinistra - 1, abs(py(v) - asse_x),
                           fillColor=tinte[k % len(tinte)], strokeColor=None))
    else:
        for k, s in enumerate(g["serie"]):
            tinta = tinte[k % len(tinte)]
            punti = [(px(float(p[0])), py(float(p[1]))) for p in s["punti"]]
            if g["tipo"] == "linee" and len(punti) > 1:
                d.add(PolyLine([c for p in punti for c in p], strokeColor=tinta, strokeWidth=1.3))
            if g["tipo"] == "punti" or len(punti) <= 12:
                for x, y in punti:
                    d.add(Circle(x, y, 1.8, fillColor=tinta, strokeColor=None))
    if g.get("asse_x"):
        d.add(String(sx + pw, giu + 3 if asse_x <= giu + 1 else asse_x + 3, g["asse_x"], fontName=font, fontSize=7.5,
                     fillColor=INCHIOSTRO, textAnchor="end"))
    if g.get("asse_y"):
        d.add(String(asse_y + 3, giu + ph - 2, g["asse_y"], fontName=font, fontSize=7.5, fillColor=INCHIOSTRO))
    nomi = [(s["nome"], tinte[k % len(tinte)]) for k, s in enumerate(g["serie"]) if s.get("nome")]
    for k, (nome, tinta) in enumerate(nomi):
        d.add(Rect(sx + 4 + k * 38 * mm, altezza - 4, 6, 3, fillColor=tinta, strokeColor=None))
        d.add(String(sx + 12 + k * 38 * mm, altezza - 4.5, nome[:30], fontName=font, fontSize=7, fillColor=INCHIOSTRO))
    return d


def _brano_pdf(brano: dict, s: dict, colore) -> Table:
    righe = [Paragraph("<b>Testo da leggere</b>", s["base"])]
    righe += [Paragraph(_testo(r), s["base"]) for r in str(brano["testo"]).splitlines() if r.strip()]
    if brano.get("fonte"):
        righe.append(Paragraph(f"<font color='#6B7280'>{_testo(brano['fonte'])}</font>", s["base"]))
    riquadro = Table([[r] for r in righe], colWidths=[LARGHEZZA])
    riquadro.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.6, colore), ("LEFTPADDING", (0, 0), (-1, -1), 10),
                                  ("RIGHTPADDING", (0, 0), (-1, -1), 10), ("TOPPADDING", (0, 0), (0, 0), 8),
                                  ("BOTTOMPADDING", (0, -1), (-1, -1), 8)]))
    return riquadro


def simulata_pdf(riga: dict, domande: list[dict], materia: dict) -> bytes:
    colore = colors.HexColor(COLORE_MATERIA.get(materia.get("colore"), COLORE_MATERIA["ambra"]))
    s = _stili(colore)
    sotto = " · ".join(x for x in (materia.get("nome"), materia.get("prof"), f"{riga.get('minuti')} minuti") if x)
    storia: list = [Paragraph(_testo(riga.get("titolo") or riga.get("argomento")), s["titolo"]), Paragraph(_testo(sotto), s["sotto"]),
                    Paragraph("Nome e cognome: ________________________________     Data: ______________", s["base"]), Spacer(1, 8)]
    sezione_attuale = None
    brano_attuale = None
    for d in domande:
        parti = []
        if d.get("sezione") and d["sezione"] != sezione_attuale:
            sezione_attuale = d["sezione"]
            parti.append(Paragraph(_testo(sezione_attuale), s["sezione"]))
        brano = d.get("brano") if isinstance(d.get("brano"), dict) else None
        if brano and brano.get("testo") and brano["testo"] != brano_attuale:
            # 21.2: il testo da leggere una volta sola, sopra la prima domanda che lo usa.
            storia.append(_brano_pdf(brano, s, colore))
            storia.append(Spacer(1, 8))
        brano_attuale = brano.get("testo") if brano else None
        punti = f"  <font color='#6B7280'>({d.get('punti')} punti)</font>" if d.get("punti") else ""
        parti.append(Paragraph(f"<b>{d.get('n')}.</b> {_testo(d.get('testo'))}{punti}", s["base"]))
        if d.get("tabella"):
            parti += [_tabella_pdf(d["tabella"], s, colore), Spacer(1, 6)]
        if d.get("grafico"):
            try:
                parti += [_grafico_pdf(d["grafico"], colore), Spacer(1, 4)]
            except (ValueError, ZeroDivisionError, KeyError, IndexError, TypeError):
                pass  # un grafico che non si disegna non deve fermare il PDF
        righe_vuote = 9 if d.get("tipo") in ("aperta", "collegamento", "esercizio") else 4
        spazi = Table([[""]] * righe_vuote, colWidths=[LARGHEZZA], rowHeights=[8 * mm] * righe_vuote)
        spazi.setStyle(TableStyle([("LINEBELOW", (0, 0), (-1, -1), 0.3, LINEA)]))
        parti.append(spazi)
        if d.get("forma") == "conti":
            parti.append(Spacer(1, 6))
            parti += [Paragraph(f"<b>{_testo(nome)}</b>: ______________________________", s["base"]) for nome in d.get("risultati") or []]
        parti.append(Spacer(1, 10))
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
