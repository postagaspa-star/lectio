"""Il piano della modifica (server 1.4.3): la modifica legge tutte le parti, toglie e unisce davvero e la nota dice
quello che Lectio ha fatto (contratto 28.6)."""

import json
from pathlib import Path

import pytest

from aiuti import lezione_pronta, prepara_tutto
from app import db, modifiche
from app.lezione import ErroreLezione
from test_modifiche import json_di, modifica, riassunto_pronto
from test_studio import schema_pronto, studio_tutto


def carte_di(servizi, schema_id: int) -> list[str]:
    with db.aperta(servizi.settings.db_path) as conn:
        return sorted(r[0] for r in conn.execute("SELECT chiave FROM carte WHERE chiave LIKE ?", (f"schema:{schema_id}:%",)))


def numero_parte(chiave: str) -> int:
    return int(chiave.split(":")[2])


# --------------------------------------------------------------------------- pezzi puri

def test_pulisci_piano_tiene_solo_quello_che_si_puo_fare():
    o = {"parti": [{"n": 5, "azione": "riscrivi", "compito": "  Metti   qui Morgan ", "unisci": [11, 5, 99, "x"]},
                   {"n": 11, "azione": "togli"}, {"n": 11, "azione": "riscrivi"}, {"n": 1, "azione": "togli"},
                   {"n": 99, "azione": "togli"}, "rotto", {"n": "x"}], "nota": "Non posso aggiungere parti nuove."}
    p = modifiche.pulisci_piano(o, list(range(1, 13)), [], fisse=(1,))
    assert p["riscrivi"] == {"5": {"compito": "Metti qui Morgan", "unisci": [11]}}
    assert p["togli"] == [11]
    assert p["note"] == ["La parte 1 non si può togliere: l'ho lasciata.", "Non posso aggiungere parti nuove."]
    # Con le parti indicate dallo studente si toccano solo quelle (e si unisce solo da quelle).
    p = modifiche.pulisci_piano(o, list(range(1, 13)), [5], fisse=())
    assert p["riscrivi"] == {"5": {"compito": "Metti qui Morgan", "unisci": []}} and p["togli"] == []
    # Una parte non può essere tolta e riscritta insieme: vince la prima azione.
    p = modifiche.pulisci_piano({"parti": [{"n": 2, "azione": "riscrivi"}, {"n": 2, "azione": "togli"}], "nota": "null"}, [1, 2], [])
    assert p == {"riscrivi": {"2": {"compito": None, "unisci": []}}, "togli": [], "note": []}
    with pytest.raises(ErroreLezione):
        modifiche.pulisci_piano({"parti": [{"n": 1, "azione": "togli"}, {"n": 2, "azione": "togli"}]}, [1, 2], [])


def test_la_nota_dice_quello_che_lectio_ha_fatto_e_i_numeri_nuovi():
    titoli = {5: "Tylor e Morgan", 11: "Altri sviluppi", 3: "Victor"}
    assert modifiche.frase_fatti(titoli, [], {5: {"unisci": []}}) is None
    assert modifiche.frase_fatti(titoli, [11, 3], {5: {"unisci": [11]}}) == (
        "Ho tolto la parte «Victor». Ho tolto la parte «Altri sviluppi» e ho messo il suo contenuto in «Tylor e Morgan».")
    assert modifiche.rinumera([2], [1, 2, 3, 4]) == {1: 1, 3: 2, 4: 3}
    assert modifiche.rinumera([], [1, 2]) == {1: 1, 2: 2}


# --------------------------------------------------------------------------- schema

def test_schema_toglie_una_parte_la_unisce_a_un_altra_e_torna_a_prima(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert len(sezioni) >= 3
    carte_prima = carte_di(servizi, d["id"])
    st = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Togli"}, headers=auth).json()
    assert st["consentita"]

    m = modifica(client, auth, "schema", d["id"], "Togli la parte 2 e mettila nella parte 1")
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [1], m
    assert m["nota"].startswith(f"Ho tolto la parte «{sezioni[1]['titolo']}» e ho messo il suo contenuto in «{sezioni[0]['titolo']}».")
    dopo = client.get(f"/api/schemi/{d['id']}", headers=auth).json()
    nuove = dopo["contenuto"]["sezioni"]
    assert [s["n"] for s in nuove] == list(range(1, len(sezioni)))
    assert "Cambiato in prova" in json_di(nuove[0])
    assert nuove[1:] == [{**s, "n": s["n"] - 1} for s in sezioni[2:]]
    assert [p["n"] for p in dopo["parti_modificabili"]] == [s["n"] for s in nuove]
    # Le carte seguono i numeri nuovi: quelle della parte 3 ora sono della 2, nessuna oltre l'ultima parte.
    carte = carte_di(servizi, d["id"])
    assert all(numero_parte(k) <= len(nuove) for k in carte)
    for vecchio in range(3, len(sezioni) + 1):
        assert len([k for k in carte if numero_parte(k) == vecchio - 1]) == len([k for k in carte_prima if numero_parte(k) == vecchio])

    indietro = client.post(f"/api/schemi/{d['id']}/scambia-versione", headers=auth).json()
    assert indietro["contenuto"]["sezioni"] == sezioni
    assert carte_di(servizi, d["id"]) == carte_prima


def test_le_modifiche_di_prima_seguono_i_numeri_nuovi(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert len(sezioni) >= 3
    prima = modifica(client, auth, "schema", d["id"], "Più semplice", [3])
    studio_tutto(servizi)
    modifica(client, auth, "schema", d["id"], "Togli la parte 2")
    studio_tutto(servizi)
    prima = client.get(f"/api/modifiche/{prima['id']}", headers=auth).json()
    assert prima["parti"] == [2] and prima["cambiate"] == [2]
    assert len(client.get(f"/api/schemi/{d['id']}", headers=auth).json()["contenuto"]["sezioni"]) == len(sezioni) - 1


def test_le_parti_indicate_limitano_il_piano(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    # Lo studente ha indicato solo la parte 1: "togli la parte 2" non si fa, si riscrive la 1.
    m = modifica(client, auth, "schema", d["id"], "Togli la parte 2", [1])
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [1] and not (m["nota"] or "").startswith("Ho tolto")
    assert len(client.get(f"/api/schemi/{d['id']}", headers=auth).json()["contenuto"]["sezioni"]) == len(sezioni)


# --------------------------------------------------------------------------- riassunto

def test_riassunto_toglie_una_parte(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    x = riassunto_pronto(client, auth, servizi, d["id"])
    parti = x["contenuto"]["parti"]
    assert len(parti) >= 2
    m = modifica(client, auth, "riassunto", x["id"], "Togli la parte 2")
    studio_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta" and m["cambiate"] == [], m
    assert m["nota"] == f"Ho tolto la parte «{parti[1]['titolo']}»."
    y = client.get(f"/api/riassunti/{x['id']}", headers=auth).json()
    assert y["contenuto"]["parti"] == [parti[0], *parti[2:]]
    assert [p["n"] for p in y["parti_modificabili"]] == list(range(1, len(parti)))


# --------------------------------------------------------------------------- lezione

def test_lezione_toglie_un_capitolo_lo_unisce_e_torna_a_prima(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    capitoli = lezione["capitoli"]
    assert len(capitoli) >= 3
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(lezione["id"])
    piano_prima = json.loads((cartella / "piano.json").read_text(encoding="utf-8"))
    tolte = [dd for dd in lezione["domande"] if dd["capitolo"] == 2]

    m = modifica(client, auth, "lezione", lezione["id"], "Togli il capitolo 2 e mettilo nel capitolo 1")
    prepara_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    assert m["stato"] == "pronta", m
    assert m["nota"].startswith(f"Ho tolto la parte «{capitoli[1]['titolo']}» e ho messo il suo contenuto in «{capitoli[0]['titolo']}».")
    dopo = client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()
    assert [c["titolo"] for c in dopo["capitoli"]] == [capitoli[0]["titolo"], *(c["titolo"] for c in capitoli[2:])]
    assert m["cambiate"] == [1]
    assert "Cambiato in prova" in json.dumps(dopo["testo"][0], ensure_ascii=False)
    assert [t["paragrafi"] for t in dopo["testo"][1:]] == [t["paragrafi"] for t in lezione["testo"][2:]]
    # I capitoli dopo quello tolto si riusano dall'audio di prima (stessa durata) con il numero nuovo.
    for a, b in zip(capitoli[2:], dopo["capitoli"][1:]):
        assert abs((a["fine_s"] - a["inizio_s"]) - (b["fine_s"] - b["inizio_s"])) <= 0.2
    # Le domande del capitolo tolto non restano con il loro numero: il capitolo 1 riscritto ha le sue (anche sul
    # contenuto unito), dopo ci sono quelle di prima con il numero nuovo.
    assert tolte and {dd["capitolo"] for dd in dopo["domande"]} <= set(range(1, len(dopo["capitoli"]) + 1))
    assert [(dd["testo"], dd["capitolo"] - 1) for dd in dopo["domande"] if dd["capitolo"] > 1] == [
        (dd["testo"], dd["capitolo"] - 2) for dd in lezione["domande"] if dd["capitolo"] > 2]
    piano = json.loads((cartella / "piano.json").read_text(encoding="utf-8"))
    assert len(piano["blocchi"]) == len(piano_prima["blocchi"]) - 1
    assert len(list((cartella / "scrittura").glob("blocco_??.json"))) == len(piano["blocchi"])

    indietro = client.post(f"/api/lezioni/{lezione['id']}/scambia-versione", headers=auth).json()
    assert indietro["capitoli"] == capitoli and indietro["audio"]["sha256"] == lezione["audio"]["sha256"]
    assert json.loads((cartella / "piano.json").read_text(encoding="utf-8")) == piano_prima
    assert len(list((cartella / "scrittura").glob("blocco_??.json"))) == len(piano_prima["blocchi"])


def test_il_primo_e_l_ultimo_capitolo_non_si_tolgono(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    ultimo = lezione["capitoli"][-1]["n"]
    m = modifica(client, auth, "lezione", lezione["id"], f"Togli il capitolo {ultimo}")
    prepara_tutto(servizi)
    m = client.get(f"/api/modifiche/{m['id']}", headers=auth).json()
    # Il piano finto non lo propone nemmeno: si riscrive tutto, niente capitoli in meno.
    assert m["stato"] == "pronta", m
    assert len(client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()["capitoli"]) == len(lezione["capitoli"])
