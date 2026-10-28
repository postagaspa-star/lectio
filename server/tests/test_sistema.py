"""Abbinamento (contratto 2), versione e salute (3), materie (4 e 15.3), impostazioni (13),
testo per Gemini (12) ed errori sempre in JSON (1)."""

import hashlib
import re
from pathlib import Path

import pytest

from aiuti import VERIFICA_GEMINI, aggiungi_testo, crea_materia, lezione_pronta
from app import abbina, db

pagamento = pytest.mark.pagamento


def _codice(app):
    with db.aperta(app.state.settings.db_path) as conn:
        codice, _ = abbina.nuovo_codice(conn)
    return codice


# --- abbinamento ------------------------------------------------------------------------------


def test_comando_stampa_un_codice(app, capsys):
    abbina.main()
    uscita = capsys.readouterr().out
    assert re.search(r"Codice di abbinamento: \d{8}\b", uscita)
    assert "15 minuti" in uscita


def test_abbina_e_usa_il_token(client, app):
    codice = _codice(app)
    r = client.post("/api/abbina", json={"codice": codice, "nome": "Telefono di prova", "tipo": "android"})
    assert r.status_code == 200, r.text
    dati = r.json()
    assert len(dati["token"]) == 64 and dati["dispositivo_id"] >= 1
    headers = {"Authorization": f"Bearer {dati['token']}"}
    elenco = client.get("/api/dispositivi", headers=headers).json()
    assert elenco[0]["nome"] == "Telefono di prova" and elenco[0]["tipo"] == "android"
    assert elenco[0]["abbinato_il"].endswith("+01:00")
    with db.aperta(app.state.settings.db_path) as conn:
        contenuto = "\n".join(str(tuple(r)) for t in ("dispositivi", "codici_abbinamento")
                              for r in conn.execute(f"SELECT * FROM {t}"))
    assert dati["token"] not in contenuto and codice not in contenuto
    assert db.hash_segreto(dati["token"]) in contenuto


def test_codice_monouso_scaduto_e_sostituito(client, app, orologio):
    codice = _codice(app)
    assert client.post("/api/abbina", json={"codice": codice, "nome": "A", "tipo": "pc"}).status_code == 200
    r = client.post("/api/abbina", json={"codice": codice, "nome": "B", "tipo": "pc"})
    assert r.status_code == 401 and r.json()["codice"] == "non_autorizzato"
    scade = _codice(app)
    orologio.avanza(minutes=16)
    assert client.post("/api/abbina", json={"codice": scade, "nome": "A", "tipo": "pc"}).status_code == 401
    vecchio, nuovo = _codice(app), _codice(app)
    assert client.post("/api/abbina", json={"codice": vecchio, "nome": "A", "tipo": "pc"}).status_code == 401
    assert client.post("/api/abbina", json={"codice": nuovo, "nome": "A", "tipo": "pc"}).status_code == 200
    r = client.post("/api/abbina", json={"codice": nuovo, "nome": "A", "tipo": "tablet"})
    assert r.status_code == 422 and r.json()["campo"] == "tipo"


def test_dieci_sbagli_bloccano_e_annullano_i_codici(client, app, orologio):
    codice = _codice(app)
    for _ in range(10):
        assert client.post("/api/abbina", json={"codice": "00000000", "nome": "X", "tipo": "pc"}).status_code == 401
    r = client.post("/api/abbina", json={"codice": codice, "nome": "X", "tipo": "pc"})
    assert r.status_code == 429 and r.json()["codice"] == "troppi_tentativi"
    orologio.avanza(minutes=16)
    assert client.post("/api/abbina", json={"codice": codice, "nome": "X", "tipo": "pc"}).status_code == 401
    assert client.post("/api/abbina", json={"codice": _codice(app), "nome": "X", "tipo": "pc"}).status_code == 200


def test_senza_token_401_in_json(client, auth):
    for metodo, url in (("get", "/api/materie"), ("get", "/api/oggi"), ("post", "/api/lezioni"),
                        ("get", "/api/spesa"), ("get", "/api/lezioni/1/audio"), ("get", "/api/gemini")):
        r = getattr(client, metodo)(url)
        assert r.status_code == 401, url
        assert r.json() == {"errore": "Questo dispositivo non è abbinato o il suo accesso è stato revocato.",
                            "codice": "non_autorizzato"}
    assert client.get("/api/materie", headers={"Authorization": "Bearer sbagliato"}).status_code == 401


def test_revoca_dispositivo(client, auth):
    io = client.get("/api/dispositivi", headers=auth).json()[0]
    assert client.delete(f"/api/dispositivi/{io['id']}", headers=auth).status_code == 200
    assert client.get("/api/dispositivi", headers=auth).status_code == 401


# --- versione, salute, scarica -----------------------------------------------------------------


def test_versione_con_impronta_dei_file(client, app):
    v = client.get("/api/versione").json()
    assert v["server"] == "1.0.1" and v["android"]["codice"] == 1 and v["android"]["sha256"] is None
    cartella = Path(app.state.settings.apk_dir)
    cartella.mkdir(parents=True, exist_ok=True)
    (cartella / "lectio.apk").write_bytes(b"apk finto")
    v = client.get("/api/versione").json()
    assert v["android"]["sha256"] == hashlib.sha256(b"apk finto").hexdigest()
    r = client.get("/scarica/lectio.apk")
    assert r.status_code == 200 and r.content == b"apk finto"
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    r = client.get("/scarica/lectio-pc.zip")
    assert r.status_code == 404 and r.json()["codice"] == "non_trovato"
    assert client.get("/scarica/altro.exe").status_code == 404


def test_salute_ridotta_e_completa_in_prova(client, auth):
    ridotta = client.get("/api/salute").json()
    assert set(ridotta) == {"ok", "versione", "server_id"} and ridotta["versione"] == "1.0.1"
    s = client.get("/api/salute", headers=auth).json()
    assert s["modalita_prova"] is True
    assert s["testo"] == {"stato": "prova", "fornitore": "prova"}
    assert s["voce_espressiva"]["stato"] == "prova"
    assert s["voce_gratis"] == {"stato": "non_disponibile", "fornitore": "azure"}
    assert s["mancano"][:3] == ["la chiave Mistral", "la chiave Azure", "la regione Azure"]
    assert s["coda"] == {"lezioni_in_preparazione": 0}


@pagamento
def test_salute_con_le_chiavi(client, auth):
    s = client.get("/api/salute", headers=auth).json()
    assert s["modalita_prova"] is False
    assert s["testo"] == {"stato": "pronto", "fornitore": "mistral"}
    assert s["voce_espressiva"] == {"stato": "pronta", "fornitore": "mistral"}
    assert s["voce_gratis"] == {"stato": "pronta", "fornitore": "azure"}
    assert s["mancano"] == []


# --- materie -----------------------------------------------------------------------------------


def test_materie_crea_elenca_cambia(client, auth):
    s = crea_materia(client, auth, "Storia", "Rossi")
    assert s["colore"] == "ambra" and s["conteggi"] == {"verifiche": 0, "interrogazioni": 0, "appunti": 0, "lezioni": 0}
    assert s["centratura"] is None and s["da_ripassare"] == 0
    a = crea_materia(client, auth, "arte", "")  # prof facoltativo
    assert a["colore"] == "rosa" and a["prof"] == ""
    c = crea_materia(client, auth, "Chimica", colore="verde")
    assert c["colore"] == "verde"
    assert [m["nome"] for m in client.get("/api/materie", headers=auth).json()] == ["arte", "Chimica", "Storia"]
    # Nome unico senza distinguere maiuscole (anche accentate).
    for nome in ("STORIA", "  storia  "):
        r = client.post("/api/materie", json={"nome": nome}, headers=auth)
        assert r.status_code == 409 and r.json() == {"errore": "Esiste già una materia con questo nome",
                                                     "codice": "conflitto"}
    crea_materia(client, auth, "Èpica")
    assert client.post("/api/materie", json={"nome": "èpica"}, headers=auth).status_code == 409
    r = client.patch(f"/api/materie/{a['id']}", json={"nome": "Storia"}, headers=auth)
    assert r.status_code == 409
    r = client.patch(f"/api/materie/{a['id']}", json={"nome": "Arte", "prof": "Bianchi", "colore": "oliva"}, headers=auth)
    assert r.json()["nome"] == "Arte" and r.json()["prof"] == "Bianchi" and r.json()["colore"] == "oliva"
    assert client.patch(f"/api/materie/{s['id']}", json={"nome": "Storia"}, headers=auth).status_code == 200
    r = client.post("/api/materie", json={"nome": "Fisica", "prof": "x" * 41}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "prof"
    r = client.post("/api/materie", json={"nome": "x" * 41}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "nome"
    r = client.post("/api/materie", json={"nome": "Fisica", "colore": "rosso"}, headers=auth)
    assert r.status_code == 422 and "ambra" in r.json()["errore"]
    assert client.patch("/api/materie/999", json={"nome": "X"}, headers=auth).status_code == 404


def test_conteggi_della_materia(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    aggiungi_testo(client, auth, m["id"], VERIFICA_GEMINI, tipo="verifica")
    aggiungi_testo(client, auth, m["id"], "Il prof mi ha chiesto di Nerone.", tipo="interrogazione")
    lezione_pronta(client, auth, servizi, m["id"])
    c = client.get(f"/api/materie/{m['id']}", headers=auth).json()["conteggi"]
    assert c == {"verifiche": 1, "interrogazioni": 1, "appunti": 1, "lezioni": 1}


def test_cancella_materia_serve_conferma_e_cancella_tutto(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = lezione_pronta(client, auth, servizi, m["id"])
    r = client.request("DELETE", f"/api/materie/{m['id']}", headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "conferma"
    r = client.request("DELETE", f"/api/materie/{m['id']}", json={"conferma": False}, headers=auth)
    assert r.status_code == 422
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])
    assert cartella.exists()
    r = client.request("DELETE", f"/api/materie/{m['id']}", json={"conferma": True}, headers=auth)
    assert r.status_code == 200
    assert not cartella.exists()
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).status_code == 404
    with db.aperta(servizi.settings.db_path) as conn:
        for tabella in ("materiali", "schede", "eventi", "lezioni", "domande"):
            assert conn.execute(f"SELECT COUNT(*) FROM {tabella}").fetchone()[0] == 0, tabella


# --- impostazioni, Gemini, errori --------------------------------------------------------------


def test_impostazioni(client, auth):
    assert client.get("/api/impostazioni", headers=auth).json() == {
        "tetto_eur": 2.0, "voce_predefinita": "mistral:voice_482", "durata_predefinita": 20,
        "fermati_alle_domande": True, "velocita": 1.0, "nome": "", "risposte_scritte": True,
        "domande_saltabili": False}
    r = client.patch("/api/impostazioni", json={"tetto_eur": 3.5, "velocita": 1.2, "nome": " Luca ",
                                                "voce_predefinita": "azure:it-IT-IsabellaNeural"}, headers=auth)
    assert r.status_code == 200
    assert r.json()["tetto_eur"] == 3.5 and r.json()["velocita"] == 1.2 and r.json()["nome"] == "Luca"
    assert r.json()["voce_predefinita"] == "azure:it-IT-IsabellaNeural"
    for corpo, campo in (({"tetto_eur": 0.4}, "tetto_eur"), ({"tetto_eur": 10.5}, "tetto_eur"),
                         ({"durata_predefinita": 10}, "durata_predefinita"), ({"velocita": 3}, "velocita"),
                         ({"fermati_alle_domande": "si"}, "fermati_alle_domande"),
                         ({"voce_predefinita": "google:x"}, "voce_predefinita")):
        r = client.patch("/api/impostazioni", json=corpo, headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == campo, corpo
        assert r.json()["codice"] == "non_valido"


def test_testo_per_gemini_esatto(client, auth, app):
    r = client.get("/api/gemini", headers=auth).json()
    atteso = (Path(app.state.settings.gemini_path)).read_text(encoding="utf-8").strip()
    assert r["testo"] == atteso
    assert r["testo"].startswith("Trascrivi questa verifica in un file di testo, senza inventare niente.")
    assert "MATERIA: … | PROF: … | DATA: … | TIPO: scritta/orale | VOTO: …" in r["testo"]
    assert r["testo"].endswith("Se una parte non si legge scrivi [illeggibile], non indovinare.")
    assert r["aggiornato_il"]
    # Nel repo c'è anche il contratto: il testo deve essere ESATTAMENTE quello della sezione 12.
    contratto = Path(__file__).resolve().parents[2] / "docs" / "contratto-api.md"
    if contratto.is_file():
        sezione = contratto.read_text(encoding="utf-8").split("## 12. Testo per Gemini", 1)[1]
        blocco = sezione.split("```", 2)[1]
        assert r["testo"] == blocco.strip()


def test_errori_sempre_in_json(client, auth, app):
    r = client.get("/api/non-esiste", headers=auth)
    assert r.status_code == 404 and r.json() == {"errore": "Non trovato.", "codice": "non_trovato"}
    r = client.post("/api/materie", content=b"{rotto", headers={**auth, "Content-Type": "application/json"})
    assert r.status_code == 422 and r.json()["codice"] == "non_valido"
    r = client.get("/api/lezioni/abc", headers=auth)
    assert r.status_code == 422 and r.json()["codice"] == "non_valido"

    @app.get("/api/esplode")
    def esplode():
        raise RuntimeError("guasto")

    from fastapi.testclient import TestClient

    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get("/api/esplode")
    assert r.status_code == 500
    assert r.json() == {"errore": "Lectio ha avuto un problema interno. Riprova tra poco.", "codice": "errore_interno"}
