"""Modifiche dopo: con l'AI, a mano, versione di prima, cache dei prompt (contratto 28, v1.7)."""

import json
from pathlib import Path

import pytest

from aiuti import aggiungi_testo, crea_materia, lezione_pronta, prepara_tutto
from app import ai, db, modifiche
from test_studio import _simulata, materia_pronta, schema_pronto, studio_tutto

pagamento = pytest.mark.pagamento


def modifica(client, auth, tipo, ident, richiesta="Aggiungi le date", parti=None, atteso=201):
    r = client.post("/api/modifiche", json={"tipo": tipo, "id": ident, "richiesta": richiesta, "parti": parti or []}, headers=auth)
    assert r.status_code == atteso, r.text
    return r.json()


def json_di(s: dict) -> str:
    return json.dumps(s, ensure_ascii=False)


def riassunto_pronto(client, auth, servizi, schema_id, forma="misto"):
    r = client.post(f"/api/schemi/{schema_id}/riassunti", json={"forma": forma}, headers=auth)
    assert r.status_code == 201, r.text
    studio_tutto(servizi)
    x = client.get(f"/api/riassunti/{r.json()['id']}", headers=auth).json()
    assert x["stato"] == "pronto"
    return x


# --------------------------------------------------------------------------- schema

def test_modifica_schema_solo_la_parte_scelta_e_torna_a_prima(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert len(sezioni) >= 2
    assert d["modifica"] is None and d["versione_da_parte"] is None
    assert [p["n"] for p in d["parti_modificabili"]] == [s["n"] for s in sezioni]
    carte_prima = client.get("/api/lampo", params={"materia_id": d["materia_id"]}, headers=auth).json()["da_fare"]

    st = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Più semplice", "parti": [1]}, headers=auth).json()
    assert st["consentita"] and st["costo_eur"] == 0 and st["fino_a"] is False and st["minuti_preparazione"] >= 1
    m = modifica(client, auth, "schema", d["id"], "Più semplice", [1])
    assert m["stato"] == "in_coda" and m["parti"] == [1]
    assert client.post("/api/modifiche", json={"tipo": "schema", "id": d["id"], "richiesta": "Altro"}, headers=auth).status_code == 409
    assert [x["id"] for x in client.get("/api/modifiche", params={"attive": 1}, headers=auth).json()] == [m["id"]]
    studio_tutto(servizi)

    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [1] and m["pronta_il"]
    assert client.get("/api/modifiche", params={"attive": 1}, headers=auth).json() == []
    dopo = client.get(f"/api/schemi/{d['id']}", headers=auth).json()
    nuove = dopo["contenuto"]["sezioni"]
    assert "Cambiato in prova: Più semplice" in json_di(nuove[0])
    assert nuove[1:] == sezioni[1:]
    assert dopo["modifica"]["id"] == m["id"]
    assert dopo["versione_da_parte"]["ruolo"] == "prima" and dopo["versione_da_parte"]["perche"] == "modifica"
    assert dopo["versione_da_parte"]["richiesta"] == "Più semplice"
    assert client.get("/api/lampo", params={"materia_id": d["materia_id"]}, headers=auth).json()["da_fare"] == carte_prima
    assert any(n["tipo"] == "modifica_pronta" and "Ho modificato lo schema" in n["testo"]
               for n in client.get("/api/novita", headers=auth).json())
    assert not modifiche.cartella(servizi.settings.dati_dir, m["id"]).exists()  # la cartella di lavoro se ne va

    indietro = client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth).json()
    assert indietro["contenuto"]["sezioni"] == sezioni and indietro["versione_da_parte"]["ruolo"] == "dopo"
    avanti = client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth).json()
    assert avanti["contenuto"]["sezioni"] == nuove and avanti["versione_da_parte"]["ruolo"] == "prima"


def test_modifica_con_tutto_sceglie_le_parti(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    st = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Più corto"}, headers=auth).json()
    assert st["fino_a"] is True
    m = modifica(client, auth, "schema", d["id"], "Più corto")
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [s["n"] for s in d["contenuto"]["sezioni"]]


def test_richieste_sbagliate(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    corpo = {"tipo": "schema", "id": d["id"], "richiesta": "Aggiungi le date"}
    assert client.post("/api/modifiche", json={**corpo, "richiesta": "   "}, headers=auth).json()["campo"] == "richiesta"
    assert client.post("/api/modifiche", json={**corpo, "richiesta": "a" * 601}, headers=auth).status_code == 422
    r = client.post("/api/modifiche", json={**corpo, "parti": [99]}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "parti"
    assert client.post("/api/modifiche", json={**corpo, "id": 999}, headers=auth).status_code == 404
    assert client.post("/api/modifiche", json={**corpo, "tipo": "altro"}, headers=auth).status_code == 422
    assert client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth).status_code == 409  # niente da parte
    assert client.get("/api/modifiche/999", headers=auth).status_code == 404


def test_modifica_ferma_si_riprova_o_si_lascia_stare(client, auth, servizi, monkeypatch):
    _, d = schema_pronto(client, auth, servizi)
    vera = modifiche.PreparazioneModifica._schema

    def rotta(self):
        raise modifiche.ErroreLezione("Mistral non risponde. Tocca Riprova.")

    monkeypatch.setattr(modifiche.PreparazioneModifica, "_schema", rotta)
    m = modifica(client, auth, "schema", d["id"], "Aggiungi le date", [1])
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "errore" and m["errore"] == "Mistral non risponde. Tocca Riprova."
    assert any(n["tipo"] == "modifica_errore" and "La modifica dello schema" in n["testo"]
               for n in client.get("/api/novita", headers=auth).json())
    assert client.get(f"/api/schemi/{d['id']}", headers=auth).json()["contenuto"] == d["contenuto"]
    monkeypatch.setattr(modifiche.PreparazioneModifica, "_schema", vera)
    assert client.post(f"/api/modifiche/{m['id']}/riprova", headers=auth).json()["stato"] == "in_coda"
    assert client.post(f"/api/modifiche/{m['id']}/riprova", headers=auth).status_code == 409
    assert client.post(f"/api/modifiche/{m['id']}/annulla", headers=auth).json()["stato"] == "annullata"
    assert client.post(f"/api/modifiche/{m['id']}/annulla", headers=auth).status_code == 409
    studio_tutto(servizi)
    assert client.get(f"/api/schemi/{d['id']}", headers=auth).json()["contenuto"] == d["contenuto"]


def test_correzione_a_mano_dello_schema(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    contenuto = json.loads(json.dumps(d["contenuto"]))
    s1 = contenuto["sezioni"][0]
    i = next(k for k, b in enumerate(s1["blocchi"]) if b.get("testo"))
    s1["blocchi"][i]["testo"] = "  Augusto  tenne il **potere** vero.  "
    s1["blocchi"][i]["tipo_finto"] = "non conta"
    s1["titolo"] = "Il potere"
    for b in s1["blocchi"]:
        if b.get("voci"):
            b["voci"] = ["", "Una voce nuova"]
    r = client.put(f"/api/schemi/{d['id']}/testo", json={"titolo": "Augusto corretto", "contenuto": contenuto}, headers=auth)
    assert r.status_code == 200, r.text
    x = r.json()
    nuovo = x["contenuto"]["sezioni"][0]
    assert x["titolo"] == "Augusto corretto" and nuovo["titolo"] == "Il potere"
    assert nuovo["blocchi"][i]["testo"] == "Augusto tenne il **potere** vero." and "tipo_finto" not in nuovo["blocchi"][i]
    assert all(b["voci"] == ["Una voce nuova"] for b in nuovo["blocchi"] if "voci" in b)
    assert x["versione_da_parte"]["perche"] == "a_mano"
    # La forma deve tornare: niente parti in meno, niente tipi cambiati.
    meno = {**contenuto, "sezioni": contenuto["sezioni"][:1]}
    r = client.put(f"/api/schemi/{d['id']}/testo", json={"titolo": "", "contenuto": meno}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "contenuto"
    lungo = json.loads(json.dumps(contenuto))
    lungo["sezioni"][0]["blocchi"][i]["testo"] = "a" * 20_001
    assert client.put(f"/api/schemi/{d['id']}/testo", json={"contenuto": lungo}, headers=auth).status_code == 422
    # Un blocco svuotato sparisce.
    vuoto = json.loads(json.dumps(x["contenuto"]))
    vuoto["sezioni"][0]["blocchi"][i]["testo"] = ""
    y = client.put(f"/api/schemi/{d['id']}/testo", json={"contenuto": vuoto}, headers=auth).json()
    assert len(y["contenuto"]["sezioni"][0]["blocchi"]) == len(nuovo["blocchi"]) - 1
    assert y["titolo"] == "Augusto corretto"
    # Torna a prima: la versione con il blocco.
    z = client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth).json()
    assert z["contenuto"]["sezioni"][0]["blocchi"][i]["testo"] == "Augusto tenne il **potere** vero."


# --------------------------------------------------------------------------- riassunto

def test_riassunto_modificato_e_corretto_e_schema_cambiato(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    x = riassunto_pronto(client, auth, servizi, d["id"])
    parti = x["contenuto"]["parti"]
    assert len(parti) >= 2 and x["schema_cambiato"] is False
    assert [p["n"] for p in x["parti_modificabili"]] == list(range(1, len(parti) + 1))
    m = modifica(client, auth, "riassunto", x["id"], "Più corto", [2])
    studio_tutto(servizi)
    assert client.get(f"/api/modifiche/{m['id']}", headers=auth).json()["cambiate"] == [2]
    y = client.get(f"/api/riassunti/{x['id']}", headers=auth).json()
    assert y["contenuto"]["parti"][0] == parti[0] and "Cambiato in prova: Più corto" in json_di(y["contenuto"]["parti"][1])
    assert y["parole"] > x["parole"] and y["versione_da_parte"]["ruolo"] == "prima"

    a_mano = {"parti": [{**p, "paragrafi": ["Scritto da me."] if i == 0 else p["paragrafi"]} for i, p in enumerate(y["contenuto"]["parti"])]}
    z = client.put(f"/api/riassunti/{x['id']}/testo", json={"titolo": "Il mio riassunto", "contenuto": a_mano}, headers=auth).json()
    assert z["titolo"] == "Il mio riassunto" and z["contenuto"]["parti"][0]["paragrafi"] == ["Scritto da me."]
    assert z["versione_da_parte"]["perche"] == "a_mano"
    assert client.put(f"/api/riassunti/{x['id']}/testo", json={"contenuto": {"parti": []}}, headers=auth).status_code == 422

    # Lo schema cambia: il riassunto lo dice; tornando allo schema di prima non è più vecchio.
    modifica(client, auth, "schema", d["id"], "Più semplice", [1])
    studio_tutto(servizi)
    assert client.get(f"/api/riassunti/{x['id']}", headers=auth).json()["schema_cambiato"] is True
    client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth)
    assert client.get(f"/api/riassunti/{x['id']}", headers=auth).json()["schema_cambiato"] is False
    client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth)
    # Rifatto dopo il cambiamento: non è più vecchio.
    client.delete(f"/api/riassunti/{x['id']}", headers=auth)
    nuovo = riassunto_pronto(client, auth, servizi, d["id"])
    assert nuovo["schema_cambiato"] is False


# --------------------------------------------------------------------------- verifica simulata

def test_simulata_modificata_in_corso_e_dopo_il_voto(client, auth, servizi):
    m = materia_pronta(client, auth, servizi)
    s = _simulata(client, auth, m["id"])
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert d["stato"] == "pronta" and len(d["domande"]) >= 2
    client.patch(f"/api/simulate/{s['id']}", json={"risposte": {"1": "La mia risposta", "2": "Altra"}, "iniziata": True}, headers=auth)
    modifica(client, auth, "simulata", s["id"], "Più difficile", [2])
    studio_tutto(servizi)
    x = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert x["stato"] == "in_corso" and x["domande"][0] == d["domande"][0]
    assert "Cambiata in prova: Più difficile" in x["domande"][1]["testo"]
    assert x["risposte"] == {"1": "La mia risposta"}  # la domanda 2 è cambiata: la sua risposta non vale più

    assert client.post(f"/api/simulate/{s['id']}/consegna", headers=auth).status_code == 200
    r = client.post("/api/modifiche", json={"tipo": "simulata", "id": s["id"], "richiesta": "Altro"}, headers=auth)
    assert r.status_code == 409 and "correggendo" in r.json()["errore"]
    studio_tutto(servizi)
    corretta = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert corretta["stato"] == "corretta" and corretta["esito"]
    modifica(client, auth, "simulata", s["id"], "Più facile", [1])
    studio_tutto(servizi)
    nuova = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert nuova["stato"] == "pronta" and nuova["risposte"] == {} and nuova["esito"] is None
    assert "Cambiata in prova: Più facile" in nuova["domande"][0]["testo"]
    di_prima = client.post(f"/api/simulate/{s['id']}/scambia-versione", headers=auth).json()
    assert di_prima["stato"] == "corretta" and di_prima["esito"] == corretta["esito"]


# --------------------------------------------------------------------------- lezione

def test_lezione_modificata_rifa_la_voce_solo_dei_capitoli_cambiati(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    capitoli = lezione["capitoli"]
    assert len(capitoli) >= 3
    assert [p["n"] for p in lezione["parti_modificabili"]] == [c["n"] for c in capitoli]
    # Una risposta data a una domanda di un capitolo che non cambia resta.
    dom = next(d for d in lezione["domande"] if d["capitolo"] != 2)
    r = client.post(f"/api/lezioni/{lezione['id']}/domande/{dom['id']}/risposta", json={"testo": "Augusto"}, headers=auth)
    assert r.status_code == 201, r.text
    client.put(f"/api/lezioni/{lezione['id']}/ascolto", json={"posizione_s": capitoli[2]["inizio_s"] + 1.0, "finita": False,
                                                                 "aggiornato_il": "2026-10-27T10:00:00+01:00"}, headers=auth)

    m = modifica(client, auth, "lezione", lezione["id"], "Più esempi", [2])
    assert client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()["stato"] == "pronta"  # si ascolta quella di prima
    prepara_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [2], m
    dopo = client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()
    assert dopo["audio"]["sha256"] != lezione["audio"]["sha256"]
    assert "Cambiato in prova: Più esempi" in json.dumps(dopo["testo"][1], ensure_ascii=False)
    assert dopo["testo"][0] == lezione["testo"][0] and dopo["testo"][2:] == lezione["testo"][2:]
    # I capitoli uguali hanno la stessa durata (audio tagliato da quello di prima).
    for a, b in zip(capitoli, dopo["capitoli"]):
        if a["n"] != 2:
            assert abs((a["fine_s"] - a["inizio_s"]) - (b["fine_s"] - b["inizio_s"])) <= 0.2
    stessa = next(d for d in dopo["domande"] if d["id"] == dom["id"])
    assert stessa["mia_risposta"] is not None and stessa["testo"] == dom["testo"]
    assert len(dopo["domande"]) == len(lezione["domande"]) == dopo["domande_totali"]
    # Ascolto: stesso punto del capitolo 3 (non cambiato), spostato con il capitolo.
    assert abs(dopo["ascolto"]["posizione_s"] - (dopo["capitoli"][2]["inizio_s"] + 1.0)) <= 0.2
    assert dopo["versione_da_parte"]["ruolo"] == "prima"
    audio = client.get(f"/api/lezioni/{lezione['id']}/audio", headers=auth)
    assert audio.status_code == 200 and len(audio.content) == dopo["audio"]["byte"]

    indietro = client.post(f"/api/lezioni/{lezione['id']}/scambia-versione", headers=auth).json()
    assert indietro["audio"]["sha256"] == lezione["audio"]["sha256"] and indietro["capitoli"] == capitoli
    assert indietro["versione_da_parte"]["ruolo"] == "dopo"
    assert {d["id"] for d in indietro["domande"]} == {d["id"] for d in lezione["domande"]}
    avanti = client.post(f"/api/lezioni/{lezione['id']}/scambia-versione", headers=auth).json()
    assert avanti["audio"]["sha256"] == dopo["audio"]["sha256"]
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(lezione["id"])
    assert len(list((cartella / "versioni").iterdir())) == 1  # le lezioni tengono una sola versione (spazio)
    assert not list(cartella.glob("rimetti-*"))


def test_domande_tolte_restano_con_le_risposte(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    d = lezione["domande"][0]
    client.post(f"/api/lezioni/{lezione['id']}/domande/{d['id']}/risposta", json={"testo": "Boh"}, headers=auth)
    with db.aperta(servizi.settings.db_path) as conn:
        resto = [dict(x) for x in conn.execute("SELECT * FROM domande WHERE lezione_id = ? AND id <> ? ORDER BY n",
                                                (lezione["id"], d["id"]))]
        modifiche.imposta_domande(conn, lezione["id"], resto)
        assert conn.execute("SELECT tolta FROM domande WHERE id = ?", (d["id"],)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM risposte WHERE domanda_id = ?", (d["id"],)).fetchone()[0] == 1
        # Tornata uguale (stesso testo): riprende la sua riga.
        modifiche.imposta_domande(conn, lezione["id"], [{**{k: d[k] for k in ("tipo", "capitolo", "testo", "risposta", "inizio_s",
                                                                                "fine_domanda_s", "inizio_risposta_s", "fine_s")}},
                                                         *resto])
        assert conn.execute("SELECT tolta, n FROM domande WHERE id = ?", (d["id"],)).fetchone()[:] == (0, 1)
    x = client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()
    assert len(x["domande"]) == len(lezione["domande"])


def test_lezione_cancellata_porta_via_modifiche_e_versioni(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    modifica(client, auth, "lezione", lezione["id"], "Più esempi", [1])
    prepara_tutto(servizi)
    assert client.delete(f"/api/lezioni/{lezione['id']}", headers=auth).status_code == 200
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM modifiche").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM versioni").fetchone()[0] == 0


# --------------------------------------------------------------------------- cache dei prompt

def test_cache_dei_prompt_costa_un_decimo(servizi):
    prezzi = servizi.cassa.prezzi
    pieno = prezzi.costo_testo("mistral-medium-latest", 10_000, 1000)
    con_cache = prezzi.costo_testo("mistral-medium-latest", 10_000, 1000, token_cache=8_000)
    entrata = prezzi.costo_testo("mistral-medium-latest", 8_000, 0)
    assert con_cache == pytest.approx(pieno - entrata * 0.9)
    assert ai.chiave_cache("blocco", "modifica:4", 7) == "lectio:modifica:4:blocco"
    assert ai.chiave_cache("blocco", None, 7) == "lectio:lezione:7:blocco"
    assert ai.chiave_cache("titolo", None, None) == "lectio:lectio:titolo"


# --------------------------------------------------------------------------- con l'AI (finta) a pagamento

@pagamento
def test_modifica_vera_registra_le_spese_e_usa_la_richiesta(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    st = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Aggiungi le date"}, headers=auth).json()
    assert st["costo_eur"] > 0 and st["fino_a"] is True
    m = modifica(client, auth, "schema", d["id"], "Aggiungi le date")
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [1]  # il server finto sceglie la parte 1
    assert server.richieste_modifica and "Aggiungi le date" in server.richieste_modifica[0]
    dopo = client.get(f"/api/schemi/{d['id']}", headers=auth).json()
    assert "Cambiato in prova: prova vera" in json_di(dopo["contenuto"]["sezioni"][0])
    with db.aperta(servizi.settings.db_path) as conn:
        spese = [dict(x) for x in conn.execute("SELECT cosa, costo_eur FROM spese WHERE riferimento = ?", (f"modifica:{m['id']}",))]
    assert any(x["cosa"] == "Modifica · scelta delle parti" for x in spese)
    assert any(x["cosa"].startswith("Modifica · schema · parte 1") for x in spese)
    assert sum(x["costo_eur"] for x in spese) > 0
