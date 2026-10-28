"""Aiuti comuni ai test: dati finti (Prof. Rossi, Prof. Bianchi) e file di prova costruiti qui."""

import io
import struct
import zipfile
import zlib

APPUNTI_STORIA = """Augusto e il principato

Nel 27 avanti Cristo Ottaviano ricevette dal senato il titolo di Augusto. Non si fece mai chiamare re. \
A Roma la parola re ricordava la tirannia dei Tarquini.

Augusto lasciò in piedi le istituzioni della repubblica. Il senato si riuniva ancora. I consoli venivano \
ancora eletti. Ma il comando dell'esercito restava a lui.

Il principato è il governo del primo cittadino, il princeps. Augusto controllava le province con le legioni \
e le casse dello Stato.

Dopo Augusto vennero Tiberio, Caligola, Claudio e Nerone. Tiberio non si fidava del senato e lasciò Roma \
nelle mani di Seiano, il capo dei pretoriani."""

VERIFICA_GEMINI = """MATERIA: Storia | PROF: Rossi | DATA: 12/10/2026 | TIPO: scritta | VOTO: 7
## Domanda 1 (punti: 3)
TESTO: Che cos'è il principato?
MIA RISPOSTA: Il governo di Augusto.
CORREZIONI DEL PROF: Manca la definizione di princeps
PUNTI PRESI: 2
## Domanda 2 (punti: 3)
TESTO: Quando Ottaviano ricevette il titolo di Augusto?
MIA RISPOSTA: Nel 27 avanti Cristo.
CORREZIONI DEL PROF: Vuole le date precise con il secolo
PUNTI PRESI: 3
COMMENTO DEL PROF: Buoni i collegamenti tra i fatti."""


def crea_materia(client, auth, nome="Storia", prof="Rossi", **altro) -> dict:
    r = client.post("/api/materie", json={"nome": nome, "prof": prof, **altro}, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def aggiungi_testo(client, auth, materia_id, testo=APPUNTI_STORIA, tipo="appunti", **altro) -> dict:
    corpo = {"testo": testo, "tipo": tipo, **altro}
    r = client.post(f"/api/materie/{materia_id}/materiale", json=corpo, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def carica_file(client, auth, materia_id, nome, contenuto: bytes, **campi):
    return client.post(f"/api/materie/{materia_id}/materiale", files={"file": (nome, contenuto)},
                       data={k: v for k, v in campi.items() if v is not None}, headers=auth)


def nuova_lezione(client, auth, materia_id, **altro) -> dict:
    corpo = {"materia_id": materia_id, "argomento": "Augusto e il principato", "durata_min": 15, **altro}
    r = client.post("/api/lezioni", json=corpo, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def prepara_tutto(servizi, massimo=20):
    for _ in range(massimo):
        if servizi.lavoro.passo_lezione() != "fatto":
            return
    raise AssertionError("la coda delle lezioni non finisce")


def sfondo_tutto(servizi, massimo=50):
    for _ in range(massimo):
        if servizi.lavoro.passo_sfondo() != "fatto":
            return
    raise AssertionError("il lavoro in sottofondo non finisce")


def lezione_pronta(client, auth, servizi, materia_id=None, **altro) -> dict:
    if materia_id is None:
        materia_id = crea_materia(client, auth)["id"]
        aggiungi_testo(client, auth, materia_id)
    lezione = nuova_lezione(client, auth, materia_id, **altro)
    prepara_tutto(servizi)
    dettaglio = client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()
    assert dettaglio["stato"] == "pronta", dettaglio
    return dettaglio


# --------------------------------------------------------------------------- file di prova

def _pdf(pagine_contenuto: list[bytes]) -> bytes:
    """Un PDF minimo ma corretto (con tabella xref), una pagina per contenuto."""
    oggetti: list[bytes] = []
    n = len(pagine_contenuto)
    kids = " ".join(f"{3 + 2 * i} 0 R" for i in range(n))
    oggetti.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    oggetti.append(f"<< /Type /Pages /Kids [{kids}] /Count {n} >>".encode())
    font = 3 + 2 * n
    for i, contenuto in enumerate(pagine_contenuto):
        oggetti.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents {4 + 2 * i} 0 R "
                       f"/Resources << /Font << /F1 {font} 0 R >> >> >>".encode())
        oggetti.append(f"<< /Length {len(contenuto)} >>\nstream\n".encode() + contenuto + b"\nendstream")
    oggetti.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    uscita = io.BytesIO()
    uscita.write(b"%PDF-1.4\n")
    posizioni = []
    for i, o in enumerate(oggetti, start=1):
        posizioni.append(uscita.tell())
        uscita.write(f"{i} 0 obj\n".encode() + o + b"\nendobj\n")
    xref = uscita.tell()
    uscita.write(f"xref\n0 {len(oggetti) + 1}\n0000000000 65535 f \n".encode())
    for p in posizioni:
        uscita.write(f"{p:010d} 00000 n \n".encode())
    uscita.write(f"trailer\n<< /Size {len(oggetti) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return uscita.getvalue()


def pdf_con_testo(righe: list[str]) -> bytes:
    def esc(t):
        return t.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    corpo = "BT /F1 11 Tf 14 TL 50 800 Td " + " ".join(f"({esc(r)}) Tj T*" for r in righe) + " ET"
    return _pdf([corpo.encode("latin-1")])


def pdf_scansionato(pagine: int = 2) -> bytes:
    """Pagine senza testo (come una scansione): solo un rettangolo disegnato."""
    return _pdf([b"0.5 g 50 50 400 600 re f"] * pagine)


def docx(paragrafi: list[str]) -> bytes:
    w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    corpo = "".join(f"<w:p><w:r><w:t xml:space=\"preserve\">{p}</w:t></w:r></w:p>" for p in paragrafi)
    documento = f'<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="{w}"><w:body>{corpo}</w:body></w:document>'
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        z.writestr("word/document.xml", documento)
    return buf.getvalue()


def odt(paragrafi: list[str]) -> bytes:
    t = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    o = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    corpo = "".join(f"<text:p>{p}</text:p>" for p in paragrafi)
    contenuto = (f'<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="{o}" '
                 f'xmlns:text="{t}"><office:body><office:text><text:h>Titolo</text:h>{corpo}'
                 f'<text:p>Due<text:s text:c="2"/>spazi</text:p></office:text></office:body></office:document-content>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("mimetype", "application/vnd.oasis.opendocument.text")
        z.writestr("content.xml", contenuto)
    return buf.getvalue()


def rtf(testo: str) -> bytes:
    return ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times New Roman;}}{\\*\\generator Prova;}"
            "\\f0\\fs24 " + testo + "\\par Citt\\'e0 di Roma \\u232? fine\\par}").encode("latin-1")


def png() -> bytes:
    def pezzo(tipo, dati):
        return struct.pack(">I", len(dati)) + tipo + dati + struct.pack(">I", zlib.crc32(tipo + dati) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 0, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + pezzo(b"IHDR", ihdr) + pezzo(b"IDAT", zlib.compress(b"\x00\x00")) + pezzo(b"IEND", b"")
