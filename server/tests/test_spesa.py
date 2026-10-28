"""I soldi (contratto 0, 8 e 9.1): stime, tetto controllato prima di OGNI chiamata a pagamento
(anche a metà lezione), spese registrate con i numeri veri, riprova senza ripagare.
Tutto con fornitori finti: nessuna chiamata vera a Mistral o Azure."""

import math
from datetime import datetime, timezone

import pytest

from aiuti import aggiungi_testo, crea_materia, nuova_lezione, prepara_tutto
from app import clock, db
from app.spesa import Cassa, Prezzi, PrezzoSconosciuto, TettoRaggiunto, VoceGratisFinita, arrotonda_su, euro

pagamento = pytest.mark.pagamento


def spendi(servizi, euro_: float, fornitore="mistral", quantita=1000, unita="token", mese=None):
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute(
            "INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (clock.adesso(), mese or clock.mese(), "Spesa di prova", fornitore, quantita, unita, euro_),
        )


def spese(servizi, filtro="1=1"):
    with db.aperta(servizi.settings.db_path) as conn:
        return [dict(r) for r in conn.execute(f"SELECT * FROM spese WHERE {filtro} ORDER BY id")]


def test_arrotondamenti_e_frasi():
    assert arrotonda_su(0.25) == 0.25
    assert arrotonda_su(0.2500001) == 0.26
    assert arrotonda_su(0.0001) == 0.01
    assert euro(2) == "2 €" and euro(1.5) == "1,50 €" and euro(0.25) == "0,25 €"


@pagamento
def test_stima_voce_espressiva(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    r = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 20, "voce": "mistral:voice_482"},
                    headers=auth)
    assert r.status_code == 200, r.text
    s = r.json()
    # parole = 20 × 145; caratteri = parole × 6,3 (alle centinaia) → 18.300
    assert s["caratteri_stimati"] == 18300
    # voce: 18,3 × 0,016 $ × 0,86 = 0,2518 € → per eccesso 0,26
    assert s["costo_voce_eur"] == 0.26
    assert 0 < s["costo_testo_eur"] <= 0.05
    assert s["costo_eur"] == round(s["costo_testo_eur"] + s["costo_voce_eur"], 2)
    assert s["spesa_mese_eur"] == 0 and s["tetto_eur"] == 2.0
    assert s["dopo_eur"] == s["costo_eur"] and s["consentita"] is True and s["motivo"] is None
    assert s["voce_gratis_caratteri_rimasti"] == 500000
    assert s["si_azzera_il"] == "2026-11-01"


@pagamento
def test_stima_voce_gratis_e_euro_al_minuto(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    voci = client.get("/api/voci", headers=auth).json()
    lorenzo = voci[0]
    assert lorenzo == {"id": "mistral:voice_482", "nome": "Lorenzo", "tipo": "espressiva", "fornitore": "mistral",
                       "euro_al_minuto": 0.0126, "disponibile": True}
    gratis = [v for v in voci if v["tipo"] == "gratis"]
    assert gratis[0]["id"] == "azure:it-IT-IsabellaMultilingualNeural"  # la prima Multilingual femminile
    assert {v["nome"] for v in gratis} == {"Isabella multilingue", "Isabella", "Diego"}
    assert all(v["id"].startswith("azure:it-IT-") for v in gratis)
    assert not any(v["tipo"] == "prova" for v in voci)
    r = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 30, "voce": gratis[0]["id"]},
                    headers=auth).json()
    assert r["costo_voce_eur"] == 0 and r["costo_testo_eur"] > 0 and r["consentita"] is True


def test_stima_in_prova_costa_zero(client, auth):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    s = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 45}, headers=auth).json()
    assert s["costo_eur"] == 0 and s["costo_testo_eur"] == 0 and s["costo_voce_eur"] == 0
    voci = client.get("/api/voci", headers=auth).json()
    assert {"id": "prova:espeak", "nome": "Voce di prova", "tipo": "prova", "fornitore": "espeak",
            "euro_al_minuto": 0, "disponibile": True} in voci
    assert voci[0]["id"] == "mistral:voice_482" and voci[0]["disponibile"] is False


@pagamento
def test_sforerebbe_il_tetto_402_con_motivo(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    spendi(servizi, 1.90)
    s = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 20, "voce": "mistral:voice_482"},
                    headers=auth).json()
    assert s["consentita"] is False
    assert s["motivo"] == "Sforerebbe il tetto di 2 €: usa la voce gratis"
    assert s["dopo_eur"] > 2
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "Augusto", "durata_min": 20,
                                          "voce": "mistral:voice_482"}, headers=auth)
    assert r.status_code == 402
    assert r.json() == {"errore": "Sforerebbe il tetto di 2 €: usa la voce gratis", "codice": "tetto_raggiunto"}
    # Con la voce gratis resta solo il testo: si può.
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "Augusto", "durata_min": 20,
                                          "voce": "azure:it-IT-IsabellaNeural"}, headers=auth)
    assert r.status_code == 201
    # Quasi tutto speso: nemmeno il testo ci sta.
    spendi(servizi, 0.095)
    s = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 45,
                                                "voce": "azure:it-IT-IsabellaNeural"}, headers=auth).json()
    assert s["consentita"] is False and "alza il tetto" in s["motivo"] and "1° novembre" in s["motivo"]


@pagamento
def test_voce_gratis_finita(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    spendi(servizi, 0, fornitore="azure", quantita=495_000, unita="caratteri")
    s = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 15,
                                                "voce": "azure:it-IT-IsabellaNeural"}, headers=auth).json()
    assert s["voce_gratis_caratteri_rimasti"] == 5000
    assert s["consentita"] is False and s["motivo"].startswith("Hai finito la voce gratis di questo mese")
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "A", "durata_min": 15,
                                          "voce": "azure:it-IT-IsabellaNeural"}, headers=auth)
    assert r.status_code == 402 and r.json()["errore"].startswith("Hai finito la voce gratis")
    spesa = client.get("/api/spesa", headers=auth).json()
    assert spesa["voce_gratis"] == {"caratteri_usati": 495000, "caratteri_mese": 500000}


def test_cassa_prenota_le_chiamate_in_parallelo(app):
    """Tre pezzi insieme non possono sforare anche se ognuno da solo ci starebbe: le
    prenotazioni sono righe vere nel conto, scritte prima della chiamata."""
    s = app.state.settings
    cassa = Cassa(s.db_path, Prezzi.carica(s.prezzi_path))
    with db.aperta(s.db_path) as conn:
        db.salva_impostazione(conn, "tetto_eur", 1.0)
    with cassa.chiamata(cosa="a", fornitore="mistral", stima_eur=0.4) as ra:
        with cassa.chiamata(cosa="b", fornitore="mistral", stima_eur=0.4) as r:
            with db.aperta(s.db_path) as conn:
                assert [x["stato"] for x in conn.execute("SELECT stato FROM spese")] == ["prenotata", "prenotata"]
            with pytest.raises(TettoRaggiunto) as e:
                with cassa.chiamata(cosa="c", fornitore="mistral", stima_eur=0.4):
                    pass
            assert "tetto di spesa del mese (1 €)" in e.value.frase
            r.registra(100, "token", 0.1)  # costava meno della stima: la riga prende il costo vero
        with cassa.chiamata(cosa="d", fornitore="mistral", stima_eur=0.4) as rd:
            rd.registra(50, "token", 0.05)
        ra.registra(10, "token", 0.02)
    with db.aperta(s.db_path) as conn:
        assert cassa.speso(conn) == pytest.approx(0.17)
        assert {x["stato"] for x in conn.execute("SELECT stato FROM spese")} == {"fatta"}
    # Una chiamata finita senza costo registrato (risposta strana) tiene la stima: mai sottostimare.
    with cassa.chiamata(cosa="e", fornitore="mistral", stima_eur=0.3):
        pass
    with db.aperta(s.db_path) as conn:
        assert cassa.speso(conn) == pytest.approx(0.47)
    # Le chiamate gratis non toccano il tetto, la voce gratis ha il suo limite.
    with cassa.chiamata(cosa="gratis", fornitore="prova"):
        pass
    with pytest.raises(VoceGratisFinita):
        with cassa.chiamata(cosa="azure", fornitore="azure", caratteri_gratis=500_001):
            pass


@pagamento
def test_lezione_vera_registra_le_spese_con_i_numeri_veri(client, auth, servizi, server):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="mistral:voice_482")
    prepara_tutto(servizi)
    dettaglio = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dettaglio["stato"] == "pronta", dettaglio
    assert dettaglio["nota"] is not None and "prova" not in dettaglio["nota"].lower()
    righe = spese(servizi, f"lezione_id = {l['id']}")
    testo = [r for r in righe if r["unita"] == "token"]
    voce = [r for r in righe if r["unita"] == "caratteri"]
    assert len(testo) == server.conta("/v1/chat/completions") == 1 + 3 + 2  # piano, 3 blocchi, apertura, finale
    assert len(voce) == server.conta("/v1/audio/speech")
    prezzi = servizi.cassa.prezzi
    # Ogni pezzo di voce costa i caratteri inviati davvero (con i segni di Voxtral).
    inviati = [d["input"] for p, d in server.chiamate if p == "/v1/audio/speech"]
    assert sorted(r["quantita"] for r in voce) == sorted(len(t) for t in inviati)
    for r in voce:
        assert r["costo_eur"] == pytest.approx(prezzi.costo_voce("voxtral-mini-tts-3-0", r["quantita"]))
    assert all(t.startswith("[emotion=") for t in inviati)
    assert any("[pace=slow]" in t for t in inviati)
    corpi = [d for p, d in server.chiamate if p == "/v1/audio/speech"]
    assert all(c["model"] == "voxtral-mini-tts-3-0" and c["voice_id"] == "voice_482" and c["response_format"] == "mp3"
               for c in corpi)
    piano = [d for p, d in server.chiamate if p == "/v1/chat/completions"][0]
    assert piano["model"] == "mistral-small-latest" and piano["response_format"] == {"type": "json_object"}
    scrittura = [d for p, d in server.chiamate if p == "/v1/chat/completions"][1]
    assert scrittura["model"] == "mistral-medium-latest"
    # Il costo della lezione è la somma vera.
    assert dettaglio["costo_eur"] == round(sum(r["costo_eur"] for r in righe) + 1e-9, 2)
    spesa = client.get("/api/spesa", headers=auth).json()
    assert spesa["mese"] == "2026-10"
    assert spesa["speso_eur"] == dettaglio["costo_eur"]
    assert len(spesa["voci"]) == min(50, len(righe))
    assert spesa["voci"][0]["lezione_id"] == l["id"]


@pagamento
def test_tetto_a_meta_lezione_e_riprova_senza_ripagare(client, auth, servizi, server):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="mistral:voice_482")
    # Dopo la partenza il conto sale (altre spese) e il tetto scende: a metà voce si ferma.
    spendi(servizi, 0.40)
    client.patch("/api/impostazioni", json={"tetto_eur": 0.5}, headers=auth)
    prepara_tutto(servizi)
    ferma = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert ferma["stato"] == "errore"
    assert ferma["errore"].startswith("Hai raggiunto il tetto di spesa del mese (0,50 €)")
    assert ferma["errore"].endswith("Poi tocca Riprova.")
    assert ferma["avanzamento"]["fase"] == "voce"
    fatti_prima = server.conta("/v1/audio/speech")
    assert fatti_prima > 0
    with db.aperta(servizi.settings.db_path) as conn:
        assert Cassa.speso(conn) <= 0.5 + 1e-9  # mai oltre il tetto
    assert len(spese(servizi, f"lezione_id = {l['id']} AND unita = 'caratteri'")) == fatti_prima
    chat_prima = server.conta("/v1/chat/completions")
    client.patch("/api/impostazioni", json={"tetto_eur": 5}, headers=auth)
    assert client.post(f"/api/lezioni/{l['id']}/riprova", headers=auth).status_code == 200
    prepara_tutto(servizi)
    pronta = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert pronta["stato"] == "pronta"
    # Niente testo riscritto, e ogni pezzo di voce pagato una volta sola.
    assert server.conta("/v1/chat/completions") == chat_prima
    pezzi = len(spese(servizi, f"lezione_id = {l['id']} AND unita = 'caratteri'"))
    assert pezzi == server.conta("/v1/audio/speech")
    testi = [d["input"] for p, d in server.chiamate if p == "/v1/audio/speech"]
    assert len(testi) == len(set(testi)) or len(testi) == pezzi
    novita = client.get("/api/novita", headers=auth).json()
    assert [n["tipo"] for n in novita].count("tetto_vicino") == 1


@pagamento
def test_tetto_prima_del_piano(client, auth, servizi, server):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="azure:it-IT-IsabellaNeural")
    spendi(servizi, 2.0)
    prepara_tutto(servizi)
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore" and dopo["avanzamento"]["fase"] == "piano"
    assert server.conta("/v1/chat/completions") == 0


@pagamento
def test_voce_gratis_conta_i_caratteri(client, auth, servizi, server):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="azure:it-IT-IsabellaNeural")
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"] == "pronta"
    azure = spese(servizi, "fornitore = 'azure'")
    assert azure and all(r["costo_eur"] == 0 and r["unita"] == "caratteri" for r in azure)
    ssml = [d for p, d in server.chiamate if p.endswith("/cognitiveservices/v1")]
    assert len(ssml) == len(azure)
    assert all("[emotion" not in x and 'name="it-IT-IsabellaNeural"' in x for x in ssml)
    assert any('<prosody rate="-10%">' in x for x in ssml)
    spesa = client.get("/api/spesa", headers=auth).json()
    assert spesa["voce_gratis"]["caratteri_usati"] == sum(int(r["quantita"]) for r in azure)


@pagamento
def test_azure_rifiuta_per_quota(client, auth, servizi, server):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="azure:it-IT-IsabellaNeural")
    server.azure_quota_finita = True
    prepara_tutto(servizi)
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore"
    assert dopo["errore"].startswith("Hai finito la voce gratis di questo mese")


def test_mese_di_roma(client, auth, servizi, orologio):
    """La spesa delle 00:30 del 1° novembre a Roma (23:30 UTC del 31) è di novembre."""
    spendi(servizi, 0.5)
    orologio.corrente = datetime(2026, 10, 31, 23, 30, tzinfo=timezone.utc)
    assert clock.mese() == "2026-11"
    spendi(servizi, 0.2)
    s = client.get("/api/spesa", headers=auth).json()
    assert s["mese"] == "2026-11" and s["speso_eur"] == 0.2
    assert s["si_azzera_il"] == "2026-12-01"
    assert s["percentuale"] == 10


def test_avviso_all_ottanta_per_cento(client, auth, servizi):
    spendi(servizi, 1.6)
    oggi = client.get("/api/oggi", headers=auth).json()
    assert oggi["spesa"]["avviso"] is True and oggi["spesa"]["percentuale"] == 80
    client.patch("/api/impostazioni", json={"tetto_eur": 10}, headers=auth)
    assert client.get("/api/oggi", headers=auth).json()["spesa"]["avviso"] is False


@pagamento
def test_tetto_vicino_una_volta_al_mese(app, servizi):
    cassa = servizi.cassa
    for costo in (0.5, 0.5, 0.7, 0.2):
        with cassa.chiamata(cosa="x", fornitore="mistral", stima_eur=costo) as r:
            r.registra(1, "token", costo)
    with db.aperta(servizi.settings.db_path) as conn:
        tipi = [n["tipo"] for n in conn.execute("SELECT tipo FROM novita")]
    assert tipi.count("tetto_vicino") == 1


def test_prezzi_dal_file(app):
    p = Prezzi.carica(app.state.settings.prezzi_path)
    assert p.cambio == 0.86
    assert p.costo_testo("mistral-small-latest", 1_000_000, 1_000_000) == pytest.approx(0.8 * 0.86)
    assert p.costo_testo("mistral-medium-latest", 1_000_000, 1_000_000) == pytest.approx(2.4 * 0.86)
    assert p.costo_ocr("mistral-ocr-latest", 1000) == pytest.approx(4 * 0.86)
    assert p.costo_voce("voxtral-mini-tts-3-0", 1000) == pytest.approx(0.016 * 0.86)
    # Un modello che non è nel listino non ha prezzo: le chiamate a pagamento si fermano.
    with pytest.raises(PrezzoSconosciuto) as e:
        p.costo_testo("mistral-large-latest", 1_000_000, 0)
    assert e.value.modello == "mistral-large-latest"
    assert e.value.frase.startswith("Sul server manca il prezzo") and "prezzi.json" not in e.value.frase
    assert p.euro_al_minuto_voce("voxtral-mini-tts-3-0") == 0.0126
    assert math.isclose(p.azure_gratis, 500_000)

