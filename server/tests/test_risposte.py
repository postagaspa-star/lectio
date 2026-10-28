"""Risposte scritte alle domande, correttore e Da ripassare (contratto 15.2)."""

import json
from pathlib import Path

import pytest

from aiuti import aggiungi_testo, crea_materia, lezione_pronta, nuova_lezione, prepara_tutto
from app import db

pagamento = pytest.mark.pagamento


def rispondi(client, auth, lezione_id, domanda_id, **corpo):
    return client.post(f"/api/lezioni/{lezione_id}/domande/{domanda_id}/risposta", json=corpo, headers=auth)


def test_correttore_finto_in_prova(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    d = l["domande"][0]
    giusta = rispondi(client, auth, l["id"], d["id"], testo=d["risposta"])
    assert giusta.status_code == 201, giusta.text
    g = giusta.json()
    assert g["esito"] == "giusta" and g["punteggio"] == 1.0 and g["mancava"] == []
    assert g["domanda_id"] == d["id"] and g["risposta_attesa"] == d["risposta"]
    assert g["costo_eur"] == 0 and g["quando"].endswith("+01:00")
    sbagliata = rispondi(client, auth, l["id"], d["id"], testo="Non lo so proprio").json()
    assert sbagliata["esito"] == "sbagliata" and sbagliata["mancava"]
    # Vale l'ultima risposta.
    dettaglio = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    mia = dettaglio["domande"][0]["mia_risposta"]
    assert mia == {"testo": "Non lo so proprio", "esito": "sbagliata", "commento": sbagliata["commento"],
                   "mancava": sbagliata["mancava"]}
    assert dettaglio["domande"][1]["mia_risposta"] is None


def test_saltata_e_validazioni(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    d = l["domande"][1]
    r = rispondi(client, auth, l["id"], d["id"], testo="", saltata=True)
    assert r.status_code == 201 and r.json()["esito"] == "saltata" and r.json()["costo_eur"] == 0
    r = rispondi(client, auth, l["id"], d["id"], testo="   ")
    assert r.status_code == 422 and r.json()["campo"] == "testo"
    r = rispondi(client, auth, l["id"], d["id"], testo="x" * 2001)
    assert r.status_code == 422 and r.json()["campo"] == "testo"
    r = rispondi(client, auth, l["id"], 99999, testo="ciao")
    assert r.status_code == 404
    r = rispondi(client, auth, 99999, d["id"], testo="ciao")
    assert r.status_code == 404
    # Domande saltabili o no, il server accetta sempre "saltata" (contratto 15.4).
    client.patch(f"/api/lezioni/{l['id']}", json={"domande_saltabili": False}, headers=auth)
    assert rispondi(client, auth, l["id"], d["id"], saltata=True).json()["esito"] == "saltata"


def test_da_ripassare(client, auth, servizi, orologio):
    l = lezione_pronta(client, auth, servizi)
    materia_id = l["materia_id"]
    d1, d2, d3 = l["domande"][:3]
    rispondi(client, auth, l["id"], d1["id"], testo="Non lo so")
    orologio.avanza(minutes=1)
    rispondi(client, auth, l["id"], d2["id"], saltata=True)
    orologio.avanza(minutes=1)
    rispondi(client, auth, l["id"], d3["id"], testo=d3["risposta"])
    elenco = client.get(f"/api/materie/{materia_id}/da_ripassare", headers=auth).json()
    assert [x["domanda_id"] for x in elenco] == [d2["id"], d1["id"]]  # più recenti prima
    assert elenco[0] == {**elenco[0], "lezione_id": l["id"], "lezione": "Augusto e il principato",
                         "testo": d2["testo"], "esito": "saltata"}
    assert set(elenco[0]) >= {"domanda_id", "lezione_id", "lezione", "testo", "esito", "commento", "quando"}
    assert client.get(f"/api/materie/{materia_id}", headers=auth).json()["da_ripassare"] == 2
    # Rispondendo bene esce da Da ripassare.
    rispondi(client, auth, l["id"], d1["id"], testo=d1["risposta"])
    assert client.get(f"/api/materie/{materia_id}", headers=auth).json()["da_ripassare"] == 1
    assert client.get("/api/materie/999/da_ripassare", headers=auth).status_code == 404


def test_i_punti_da_ripassare_vanno_al_piano(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    d = l["domande"][-1]
    rispondi(client, auth, l["id"], d["id"], saltata=True)
    nuova = nuova_lezione(client, auth, l["materia_id"], argomento=d["testo"][:150])
    prepara_tutto(servizi)
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(nuova["id"])
    lettura = json.loads((cartella / "lettura.json").read_text(encoding="utf-8"))
    assert [x["domanda_id"] for x in lettura["da_ripassare"]] == [d["id"]]
    piano = json.loads((cartella / "piano.json").read_text(encoding="utf-8"))
    assert any("punto da ripassare" in q["testo"] for q in piano["interrogazione"])


def test_impostazione_risposte_scritte(client, auth):
    imp = client.get("/api/impostazioni", headers=auth).json()
    assert imp["risposte_scritte"] is True
    r = client.patch("/api/impostazioni", json={"risposte_scritte": False}, headers=auth)
    assert r.json()["risposte_scritte"] is False
    r = client.patch("/api/impostazioni", json={"risposte_scritte": "no"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "risposte_scritte"


@pagamento
def test_correttore_vero_registra_il_costo(client, auth, servizi, server):
    l = lezione_pronta(client, auth, servizi, voce="azure:it-IT-IsabellaNeural")
    d = l["domande"][0]
    r = rispondi(client, auth, l["id"], d["id"], testo="Perché non si fidava del senato")
    assert r.status_code == 201
    v = r.json()
    assert v["esito"] == "parziale" and v["punteggio"] == 0.5 and v["mancava"] == ["il ruolo di Seiano"]
    assert 0 < v["costo_eur"] < 0.01
    corpo = [x for p, x in server.chiamate if p == "/v1/chat/completions"][-1]
    assert corpo["model"] == "mistral-small-latest" and corpo["response_format"] == {"type": "json_object"}
    testo = corpo["messages"][1]["content"]
    assert "<risposta>\nPerché non si fidava del senato\n</risposta>" in testo
    assert d["risposta"] in testo  # la risposta attesa
    with db.aperta(servizi.settings.db_path) as conn:
        spesa = conn.execute("SELECT * FROM spese WHERE cosa = 'Correzione di una risposta'").fetchone()
    assert spesa["costo_eur"] == pytest.approx(v["costo_eur"], abs=1e-4)
    assert spesa["lezione_id"] == l["id"]


@pagamento
def test_tetto_finito_non_blocca(client, auth, servizi, server):
    l = lezione_pronta(client, auth, servizi, voce="azure:it-IT-IsabellaNeural")
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur)"
                     " VALUES ('2026-10-27T09:00:00.000+00:00', '2026-10', 'x', 'mistral', 1, 'token', 2.0)")
    chiamate = server.conta("/v1/chat/completions")
    r = rispondi(client, auth, l["id"], l["domande"][0]["id"], testo="Una risposta")
    assert r.status_code == 201
    assert r.json()["esito"] == "non_corretta"
    assert r.json()["commento"] == "Hai finito i soldi del mese: confronta tu con la risposta qui sotto"
    assert r.json()["risposta_attesa"] == l["domande"][0]["risposta"]
    assert server.conta("/v1/chat/completions") == chiamate  # nessuna chiamata partita


@pagamento
def test_fornitore_giu_non_blocca(client, auth, servizi, server):
    l = lezione_pronta(client, auth, servizi, voce="azure:it-IT-IsabellaNeural")
    server.guasti["/v1/chat/completions"] = [503] * 4
    r = rispondi(client, auth, l["id"], l["domande"][0]["id"], testo="Una risposta")
    assert r.status_code == 201 and r.json()["esito"] == "non_corretta"
    server.correttore = {"esito": "voto 10", "commento": ""}
    r = rispondi(client, auth, l["id"], l["domande"][0]["id"], testo="Una risposta")
    assert r.json()["esito"] == "non_corretta"
