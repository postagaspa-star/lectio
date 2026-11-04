"""Le voci gratis del NAS (contratto 16.5): Giovanni, Giovanni grande e Supertonic, servite dal
container "lectio-voci". Qui il servizio è quello finto di finti.py."""

import pytest
from aiuti import aggiungi_testo, crea_materia, nuova_lezione, prepara_tutto
from fastapi.testclient import TestClient

from app import db

pagamento = pytest.mark.pagamento


@pytest.fixture
def app(request, ambiente, orologio, server, monkeypatch):
    """Come quella di conftest, ma con l'indirizzo delle voci del NAS."""
    from conftest import monta_servizi

    if request.node.get_closest_marker("pagamento"):
        monkeypatch.setenv("LECTIO_MISTRAL_KEY", "chiave-finta-mistral")
    monkeypatch.setenv("LECTIO_VOCI_URL", "http://lectio-voci:8000/")
    from app.main import create_app

    applicazione = create_app()
    monta_servizi(applicazione, server)
    return applicazione


@pytest.fixture
def client(app):
    with TestClient(app) as c:
        yield c


def _materia(client, auth):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    return m


@pagamento
def test_elenco_con_le_tre_voci_del_nas(client, auth):
    voci = client.get("/api/voci", headers=auth).json()
    nas = [v for v in voci if v["fornitore"] == "nas"]
    assert [(v["id"], v["nome"]) for v in nas] == [
        ("nas:giovanni", "Giovanni"), ("nas:giovanni_grande", "Giovanni grande"), ("nas:supertonic", "Supertonic")]
    assert all(v["tipo"] == "gratis" and v["euro_al_minuto"] == 0 and v["disponibile"] for v in nas)
    assert [v["minuti_per_minuto"] for v in nas] == [0.6, 1.3, 1.1]
    # Senza Azure, il posto "voce gratis non ancora attiva" non serve più.
    assert not any(v["fornitore"] == "azure" for v in voci)
    # La voce predefinita resta Lorenzo.
    assert voci[0]["id"] == "mistral:voice_482"
    assert client.get("/api/impostazioni", headers=auth).json()["voce_predefinita"] == "mistral:voice_482"


@pagamento
def test_stima_gratis_con_i_minuti_di_preparazione(client, auth):
    m = _materia(client, auth)
    corpo = {"materia_id": m["id"], "durata_min": 30}
    lenta = client.post("/api/lezioni/stima", json=corpo | {"voce": "nas:giovanni_grande"}, headers=auth).json()
    assert lenta["costo_voce_eur"] == 0 and lenta["consentita"]
    assert lenta["minuti_preparazione"] == 41  # 2 + 30 × 1,3
    giovanni = client.post("/api/lezioni/stima", json=corpo | {"voce": "nas:giovanni"}, headers=auth).json()
    assert giovanni["minuti_preparazione"] == 20  # 2 + 30 × 0,6
    lorenzo = client.post("/api/lezioni/stima", json=corpo | {"voce": "mistral:voice_482"}, headers=auth).json()
    assert lorenzo["costo_voce_eur"] > 0 and lorenzo["minuti_preparazione"] == 5
    # Il testo costa uguale: cambia solo la voce.
    assert giovanni["costo_testo_eur"] == lorenzo["costo_testo_eur"]


def test_lezione_letta_dalle_voci_del_nas(client, auth, servizi, server, app):
    m = _materia(client, auth)
    l = nuova_lezione(client, auth, m["id"], voce="nas:supertonic")
    assert l["voce"] == "nas:supertonic"
    prepara_tutto(servizi)
    dettaglio = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dettaglio["stato"] == "pronta", dettaglio
    chiamate = [d for p, d in server.chiamate if p == "/voce"]
    assert chiamate and all(d["motore"] == "supertonic" for d in chiamate)
    assert not any("[emotion=" in d["testo"] or "[pace=" in d["testo"] for d in chiamate)
    # Niente spese, niente caratteri gratis di Azure.
    with db.aperta(app.state.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM spese WHERE fornitore = 'nas' AND costo_eur > 0").fetchone()[0] == 0
    assert client.get("/api/spesa", headers=auth).json()["voce_gratis"]["caratteri_usati"] == 0


def test_un_pezzo_rotto_si_ritenta(client, auth, servizi, server):
    m = _materia(client, auth)
    server.voci_rotte = 2
    l = nuova_lezione(client, auth, m["id"], voce="nas:giovanni")
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"] == "pronta"


def test_voce_sempre_rotta_dice_cosa_fare(client, auth, servizi, server):
    m = _materia(client, auth)
    server.voci_rotte = 1000
    l = nuova_lezione(client, auth, m["id"], voce="nas:giovanni")
    prepara_tutto(servizi)
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore"
    assert dopo["errore"].startswith("La voce Giovanni non è riuscita a leggere un pezzo.")


def test_voci_spente(client, auth, server):
    m = _materia(client, auth)
    server.voci_spente = True
    voci = client.get("/api/voci", headers=auth).json()
    assert all(not v["disponibile"] for v in voci if v["fornitore"] == "nas")
    r = client.post("/api/lezioni/stima", json={"materia_id": m["id"], "durata_min": 15, "voce": "nas:giovanni"},
                    headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "voce"
    assert "non rispondono" in r.json()["errore"]
    salute = client.get("/api/salute", headers=auth).json()
    assert salute["voce_gratis"] == {"stato": "non_disponibile", "fornitore": "nas"}
    assert "le voci gratis del NAS (non rispondono)" in salute["mancano"]


@pagamento
def test_salute_e_impostazioni(client, auth):
    salute = client.get("/api/salute", headers=auth).json()
    assert salute["voce_gratis"] == {"stato": "pronta", "fornitore": "nas"}
    assert not any("Azure" in x for x in salute["mancano"])
    r = client.patch("/api/impostazioni", json={"voce_predefinita": "nas:supertonic"}, headers=auth)
    assert r.status_code == 200 and r.json()["voce_predefinita"] == "nas:supertonic"
    # Senza voce scelta, la lezione usa la predefinita.
    m = _materia(client, auth)
    assert nuova_lezione(client, auth, m["id"])["voce"] == "nas:supertonic"
    r = client.patch("/api/impostazioni", json={"voce_predefinita": "nas:nessuna"}, headers=auth)
    assert r.status_code == 422


def test_in_prova_la_voce_del_nas_prima_di_quella_robotica(client, auth):
    m = _materia(client, auth)
    assert nuova_lezione(client, auth, m["id"])["voce"] == "nas:giovanni"
