"""Dal 22/11/2026 le foto piccole (lato lungo fino a 1600 px, come quelle di WhatsApp) le legge gpt-6-luna a strisce
ingrandite, con le regole delle trascrizioni; le altre restano all'OCR. Tutto con fornitori finti."""

import io
import logging
import math
from pathlib import Path

import pytest

from aiuti import carica_file, crea_materia, pdf_scansionato
from app import db, foto_piccole as fp
from app.spesa import Prezzi

pagamento = pytest.mark.pagamento
openai = pytest.mark.openai
CHAT_OPENAI = "api.openai.com/v1/chat/completions"
PREZZI = Path(__file__).resolve().parents[1] / "app" / "prezzi.json"
TRASCRIZIONE = ("## TYLOR E IL CONCETTO DI CULTURA\n"
                "Uno dei più importanti antropologi evoluzionisti è stato [[Edward Burnett Tylor]] (1832-1917).\n"
                'Appunto a mano (letto così, da controllare; accanto a "Cultura primitiva"): fornisce la 1° definizione\n'
                'Appunto a mano (accanto a "animismo"): credenza [?]\n'
                "La seconda colonna comincia qui [tagliato]")


def jpeg(larghezza: int, altezza: int) -> bytes:
    """Una pagina bianca con un quadrato nero in alto a sinistra e uno in basso a destra: così si vede l'ordine
    delle strisce."""
    from PIL import Image, ImageDraw

    im = Image.new("RGB", (larghezza, altezza), (235, 235, 225))
    d = ImageDraw.Draw(im)
    lato = max(2, min(larghezza, altezza) // 10)
    d.rectangle((0, 0, lato, lato), fill=(20, 20, 20))
    d.rectangle((larghezza - lato, altezza - lato, larghezza, altezza), fill=(20, 20, 20))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80)
    return buf.getvalue()


@pytest.fixture
def prezzi():
    return Prezzi.carica(str(PREZZI))


@pytest.fixture
def registro():
    """Le righe del registro del server (logger uvicorn.error), per controllare gli avvisi."""
    righe: list[str] = []

    class Raccogli(logging.Handler):
        def emit(self, record):
            righe.append(record.getMessage())

    logger = logging.getLogger("uvicorn.error")
    gestore, livello = Raccogli(level=logging.INFO), logger.level
    logger.addHandler(gestore)
    logger.setLevel(logging.INFO)
    yield righe
    logger.removeHandler(gestore)
    logger.setLevel(livello)


@pytest.fixture
def materia(client, auth):
    return crea_materia(client, auth, nome="Scienze Umane")


def carica_e_leggi(client, auth, servizi, materia, contenuto: bytes, nome="foto.jpg") -> dict:
    """Carica la foto e la fa leggere (solo la lettura, non gli altri lavori in sottofondo)."""
    r = carica_file(client, auth, materia["id"], nome, contenuto)
    assert r.status_code == 201, r.text
    servizi.lavoro.leggi_materiale(r.json()["id"])
    return client.get(f"/api/materiale/{r.json()['id']}", headers=auth).json()


def letture_luna(server) -> list[dict]:
    """Le richieste a OpenAI con le immagini (quelle delle foto piccole)."""
    return [d for (p, d), o in zip(server.chiamate, server.ospiti) if p == "/v1/chat/completions"
            and o == "api.openai.com" and any(isinstance(m["content"], list) for m in d["messages"])]


def spese(servizi, materiale_id) -> list[dict]:
    with db.aperta(servizi.settings.db_path) as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM spese WHERE materiale_id = ? ORDER BY id", (materiale_id,))]


# --- foto piccola o grande ---------------------------------------------------------------------------------


@pytest.mark.parametrize("larghezza,altezza,piccola", [
    (1024, 768, True), (768, 1024, True), (1600, 1200, True), (1200, 1600, True), (1600, 1600, True), (1, 1, True),
    (1601, 1200, False), (1200, 1601, False), (2250, 3000, False), (4032, 3024, False),
])
def test_quando_una_foto_e_piccola(larghezza, altezza, piccola, prezzi):
    assert fp.e_piccola(larghezza, altezza) is piccola
    assert (fp.piano(larghezza, altezza, prezzi) is not None) is piccola


@pagamento
@openai
def test_foto_piccola_letta_da_luna(client, auth, servizi, server, materia, prezzi):
    server.chat_forzata = TRASCRIZIONE
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto"
    assert server.conta("/v1/ocr") == 0
    testo = m["testo"]
    assert testo.startswith(fp.LEGENDA)
    assert "[[Edward Burnett Tylor]]" in testo and "[tagliato]" in testo
    # Anche l'appunto senza il segno diventa "da controllare".
    assert 'Appunto a mano (letto così, da controllare; accanto a "animismo"): credenza [?]' in testo
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT da_prova FROM materiali WHERE id = ?", (m["id"],)).fetchone()[0] == 0
    # UNA richiesta, con le 3 strisce in ordine, tutte "high", ragionamento basso e l'uscita limitata.
    (corpo,) = letture_luna(server)
    assert corpo["model"] == "gpt-6-luna"
    assert corpo["reasoning_effort"] == "low"
    assert corpo["max_completion_tokens"] == fp.TOKEN_USCITA
    assert "response_format" not in corpo and "temperature" not in corpo
    immagini = [p for p in corpo["messages"][1]["content"] if p["type"] == "image_url"]
    assert len(immagini) == 3
    assert all(p["image_url"]["detail"] == "high" and p["image_url"]["url"].startswith("data:image/jpeg;base64,")
               for p in immagini)
    # Il costo vero, dai token del fornitore, passato dalla Cassa.
    (riga,) = spese(servizi, m["id"])
    assert riga["fornitore"] == "openai" and riga["unita"] == "token" and riga["stato"] == "fatta"
    assert riga["cosa"] == "Lettura di «Foto 27 ottobre» (foto piccola)"
    entrata = (len(corpo["messages"][0]["content"]) + len(corpo["messages"][1]["content"])) // 4
    uscita = len(TRASCRIZIONE) // 4
    assert riga["quantita"] == entrata + uscita
    assert riga["costo_eur"] == pytest.approx(prezzi.costo_testo("gpt-6-luna", entrata, uscita))
    assert riga["costo_eur"] <= fp.COSTO_MASSIMO_EUR


@pagamento
@openai
def test_foto_grande_e_pdf_restano_all_ocr(client, auth, servizi, server, materia):
    grande = carica_e_leggi(client, auth, servizi, materia, jpeg(1700, 1200))
    assert grande["stato"] == "pronto" and "Augusto fu il primo imperatore" in grande["testo"]
    r = carica_file(client, auth, materia["id"], "scansione.pdf", pdf_scansionato(1))
    servizi.lavoro.leggi_materiale(r.json()["id"])
    assert server.conta("/v1/ocr") == 2
    assert letture_luna(server) == []


@pagamento
def test_senza_chiave_openai_legge_l_ocr(client, auth, servizi, server, materia):
    # Senza la chiave OpenAI scrive DeepSeek, che le foto non le vede.
    assert fp.lettore(servizi.ctx) is None
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto" and "Augusto fu il primo imperatore" in m["testo"]
    assert server.conta("/v1/ocr") == 1
    assert "api.openai.com" not in server.ospiti


def test_in_prova_legge_il_finto(client, auth, servizi, server, materia):
    assert fp.lettore(servizi.ctx) is None
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto" and "modalità prova" in m["testo"]
    assert server.chiamate == []


# --- le strisce ----------------------------------------------------------------------------------------------


@pytest.mark.parametrize("altezza,quante", [(1, 1), (200, 1), (320, 1), (600, 2), (768, 3), (1024, 4), (1200, 4),
                                             (1600, 5)])
def test_tagli_in_strisce_che_si_sovrappongono(altezza, quante):
    tagli = fp.tagli(altezza)
    assert len(tagli) == quante
    assert tagli[0][0] == 0 and tagli[-1][1] == altezza  # tutta la foto, dall'alto in basso
    passo = altezza / quante
    for (a1, b1), (a2, b2) in zip(tagli, tagli[1:]):
        assert a1 < a2 and b1 < b2  # in ordine
        assert b1 - a2 >= math.floor(2 * passo * fp.SOVRAPPOSIZIONE)  # la riga tagliata in una è intera nella vicina


def test_strisce_ingrandite_in_ordine(prezzi):
    from PIL import Image

    p = fp.piano(1024, 768, prezzi)
    assert len(p.strisce) == 3 and 1.0 < p.ingrandimento <= fp.INGRANDIMENTO_MASSIMO
    jpeg_strisce = fp.strisce_jpeg(fp.apri(jpeg(1024, 768)), p)
    angoli = []  # (in alto a sinistra scuro?, in basso a destra scuro?)
    for s, dati in zip(p.strisce, jpeg_strisce):
        with Image.open(io.BytesIO(dati)) as im:
            assert im.format == "JPEG"
            assert im.size == (s.larghezza, s.altezza) == (round(1024 * p.ingrandimento),
                                                            round((s.basso - s.alto) * p.ingrandimento))
            grigio = im.convert("L")
            angoli.append((grigio.getpixel((10, 10)) < 100, grigio.getpixel((im.width - 10, im.height - 10)) < 100))
    # Il quadrato in alto è nella prima striscia, quello in basso nell'ultima: le strisce vanno dall'alto in basso.
    assert angoli == [(True, False), (False, False), (False, True)]
    richiesta = fp.messaggi(jpeg_strisce)
    assert [p["type"] for p in richiesta[1]["content"]] == ["text", "image_url", "image_url", "image_url"]


def test_foto_piccolissima_e_trasparente(prezzi):
    from PIL import Image

    p = fp.piano(300, 200, prezzi)
    assert len(p.strisce) == 1 and p.ingrandimento == fp.INGRANDIMENTO_MASSIMO
    buf = io.BytesIO()
    Image.new("RGBA", (300, 200), (0, 0, 0, 0)).save(buf, "PNG")
    foto = fp.apri(buf.getvalue())
    assert foto.mode == "RGB" and foto.getpixel((5, 5)) == (255, 255, 255)  # il trasparente diventa bianco
    (dati,) = fp.strisce_jpeg(foto, p)
    with Image.open(io.BytesIO(dati)) as im:
        assert im.size == (600, 400)


# --- il limite di costo -----------------------------------------------------------------------------------


def test_costo_peggiore_sotto_0_1_centesimi_per_ogni_foto_piccola(prezzi):
    lati = [1, 50, 333, 540, 600, 720, 768, 800, 900, 1024, 1080, 1200, 1280, 1440, 1536, 1599, 1600]
    for larghezza in lati:
        for altezza in lati:
            p = fp.piano(larghezza, altezza, prezzi)
            assert p is not None, (larghezza, altezza)
            assert fp.INGRANDIMENTO_MINIMO <= p.ingrandimento <= fp.INGRANDIMENTO_MASSIMO
            for s in p.strisce:  # OpenAI non rimpicciolisce niente: il conto delle patch è esatto
                assert max(s.larghezza, s.altezza) <= fp.LATO_MASSIMO_IMMAGINE
                assert fp.patch(s.larghezza, s.altezza) <= fp.PATCH_MASSIME_IMMAGINE
            # Il conto rifatto a mano, con i numeri di prezzi.json: tutto l'input al prezzo della cache scritta,
            # tutta l'uscita permessa.
            entrata = fp.token_testo(len(p.strisce)) + sum(
                math.ceil(fp.patch(s.larghezza, s.altezza) * 1.2) + 20 for s in p.strisce)
            assert entrata == p.token_entrata
            euro = (entrata * 0.125 + fp.TOKEN_USCITA * 0.50) / 1e6 * 0.86
            assert euro == pytest.approx(p.costo_peggiore_eur)
            assert euro <= 0.001, (larghezza, altezza, euro)


def test_esempio_whatsapp_nel_commento(prezzi):
    p = fp.piano(1024, 768, prezzi)
    assert (len(p.strisce), p.ingrandimento, p.patch, p.token_entrata) == (3, 1.5, 2112, 3174)
    assert p.costo_peggiore_eur == pytest.approx(0.000986, abs=1e-6)
    # Il testo vero della richiesta sta nel conto per eccesso (3 caratteri a token; l'italiano ne fa circa 4).
    assert len(fp.SISTEMA) + len(fp.testo_richiesta(3)) <= (fp.token_testo(3) - 20) * 3
    # Le foto fino a 800×600 si ingrandiscono 2 volte piene.
    assert fp.piano(800, 600, prezzi).ingrandimento == 2.0


def test_con_prezzi_piu_alti_la_foto_va_all_ocr(prezzi):
    prezzi.testo["gpt-6-luna"] = {k: v * 2 for k, v in prezzi.testo["gpt-6-luna"].items()}
    assert fp.piano(1024, 768, prezzi) is None  # neanche rimpicciolita: tutta l'uscita da sola sfora


@pagamento
@openai
def test_tetto_del_mese_ferma_anche_luna(client, auth, servizi, server, materia):
    r = carica_file(client, auth, materia["id"], "foto.jpg", jpeg(1024, 768))
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur)"
                     " VALUES ('2026-10-27T08:00:00.000+00:00', '2026-10', 'x', 'mistral', 1, 'token', 1.9995)")
    servizi.lavoro.leggi_materiale(r.json()["id"])
    m = client.get(f"/api/materiale/{r.json()['id']}", headers=auth).json()
    assert m["stato"] == "errore" and "tetto" in m["errore"]
    assert letture_luna(server) == [] and server.conta("/v1/ocr") == 0


# --- il ripiego sull'OCR ----------------------------------------------------------------------------------


@pagamento
@openai
def test_luna_rifiuta_si_legge_con_l_ocr(client, auth, servizi, server, materia, registro):
    server.guasti[CHAT_OPENAI] = [400]
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto" and "Augusto fu il primo imperatore" in m["testo"]
    assert len(letture_luna(server)) == 1 and server.conta("/v1/ocr") == 1
    assert server.ospiti.count("api.eu.mistral.ai") == 1  # solo l'OCR: niente riserva DeepSeek, che le foto non le vede
    assert [r["fornitore"] for r in spese(servizi, m["id"])] == ["mistral"]  # luna non ha lavorato: niente conto
    assert any("luna non è riuscita" in r and "OCR" in r for r in registro)


@pagamento
@openai
def test_luna_forse_elaborata_non_si_ritenta(client, auth, servizi, server, materia, registro):
    server.guasti[CHAT_OPENAI] = [500]
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto" and "Augusto fu il primo imperatore" in m["testo"]
    assert len(letture_luna(server)) == 1  # un secondo tentativo potrebbe raddoppiare il costo della foto
    luna, ocr = spese(servizi, m["id"])
    assert luna["stato"] == "stimata" and luna["costo_eur"] <= fp.COSTO_MASSIMO_EUR
    assert ocr["fornitore"] == "mistral" and ocr["unita"] == "pagine"
    assert any("OCR" in r for r in registro)


@pagamento
@openai
def test_luna_occupata_si_ritenta_gratis(client, auth, servizi, server, materia):
    server.guasti[CHAT_OPENAI] = [503]
    server.chat_forzata = TRASCRIZIONE
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["testo"].startswith(fp.LEGENDA)
    assert len(letture_luna(server)) == 2 and server.conta("/v1/ocr") == 0
    assert [r["stato"] for r in spese(servizi, m["id"])] == ["fatta"]


@pagamento
@openai
def test_risposta_tagliata_si_legge_con_l_ocr(client, auth, servizi, server, materia, registro):
    server.tronca = 1
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(1024, 768))
    assert m["stato"] == "pronto" and "Augusto fu il primo imperatore" in m["testo"]
    assert server.conta("/v1/ocr") == 1
    assert [r["fornitore"] for r in spese(servizi, m["id"])] == ["openai", "mistral"]  # luna ha lavorato: si paga
    assert any("tagliata" in r and "OCR" in r for r in registro)


@pagamento
@openai
def test_risposta_vuota_si_legge_con_l_ocr(client, auth, servizi, server, materia, registro):
    server.chat_forzata = "  \n```\n```  "
    m = carica_e_leggi(client, auth, servizi, materia, jpeg(768, 1024))
    assert m["stato"] == "pronto" and "Augusto fu il primo imperatore" in m["testo"]
    assert server.conta("/v1/ocr") == 1
    assert any("vuoto" in r and "OCR" in r for r in registro)


@pagamento
@openai
def test_foto_rovinata_va_all_ocr(client, auth, servizi, server, materia, registro):
    m = carica_e_leggi(client, auth, servizi, materia, b"\xff\xd8\xff non una foto vera")
    assert letture_luna(server) == [] and server.conta("/v1/ocr") == 1
    assert m["stato"] == "pronto"
    assert any("OCR" in r for r in registro)


# --- il prompt e il testo ---------------------------------------------------------------------------------


def test_prompt_con_le_regole():
    testo = fp.testo_richiesta(3)
    for regola in ("Le 3 immagini sono UNA sola foto", "una volta sola", "parola per parola", "[[ e ]]",
                   'Appunto a mano (letto così, da controllare; accanto a "', 'Appunto a mano non leggibile (accanto a "',
                   "[?] al suo posto", "Meglio [?] che una parola indovinata", "[tagliato]", "Non completare mai",
                   "Il grassetto stampato non è una sottolineatura"):
        assert regola in testo, regola
    assert "UNA sola foto" not in fp.testo_richiesta(1)
    assert "solo con la trascrizione" in fp.SISTEMA and "JSON" in fp.SISTEMA
    assert "studente" in fp.SISTEMA  # niente nomi: il repository è pubblico


def test_testo_con_la_riga_dei_segni_e_gli_appunti_da_controllare():
    risposta = ("```text\n## Titolo\nTesto [[sottolineato]].\n"
                'Appunto a mano (accanto a "Tylor (1871)"): la definizione\n'
                "Appunto a mano: senza posto\n"
                '- appunto a mano (letto così; accanto a "y"): con il trattino\n'
                'Appunto a mano non leggibile (accanto a "x").\n'
                'Appunto a mano (letto così, da controllare; accanto a "z"): già giusto\n```')
    righe = fp.componi(risposta).split("\n")
    assert righe[0] == fp.LEGENDA and righe[1] == ""
    for segno in ("[[ ]]", "da controllare", "[?]", "[tagliato]"):
        assert segno in fp.LEGENDA
    assert righe[2:] == [
        "## Titolo",
        "Testo [[sottolineato]].",
        'Appunto a mano (letto così, da controllare; accanto a "Tylor (1871)"): la definizione',
        "Appunto a mano (letto così, da controllare): senza posto",
        '- Appunto a mano (letto così, da controllare; accanto a "y"): con il trattino',
        'Appunto a mano non leggibile (accanto a "x").',
        'Appunto a mano (letto così, da controllare; accanto a "z"): già giusto',
    ]
    assert fp.componi("") == "" and fp.componi("```\n```") == ""
