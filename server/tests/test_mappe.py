"""Le mappe di ripasso: linea del tempo e mappa dei concetti, da uno schema pronto (contratto 29)."""

import json

import pytest

from aiuti import aggiungi_testo, crea_materia
from app import db, mappe
from test_studio import crea_schema, schema_pronto, sfondo_tutto, studio_tutto

pagamento = pytest.mark.pagamento

APPUNTI_DATE = """L'età moderna

Nel 1492 Colombo arrivò in America, nel 1517 Lutero scrisse le tesi e nel 1545 cominciò il concilio di Trento. \
Roma era stata fondata nel 753 a.C.

L'impero romano d'Occidente cadde nel 476 d.C., quando Odoacre depose Romolo Augustolo."""

SCHEMA_CON_DATE = {"sezioni": [
    {"n": 1, "titolo": "Augusto", "campi": [], "blocchi": [
        {"tipo": "paragrafo", "titolo": None, "testo": "Nel 27 avanti Cristo Ottaviano diventa Augusto. Augusto tiene il potere vero."}]},
    {"n": 2, "titolo": "Le scoperte", "campi": [], "blocchi": [
        {"tipo": "punti", "titolo": None, "voci": ["Nel 1492 Colombo arriva in America.", "Nel XV secolo cresce il commercio."]}]},
]}


def schema_con_date(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"], APPUNTI_DATE)
    sfondo_tutto(servizi)
    s = crea_schema(client, auth, m["id"], argomento="Roma e l'impero")
    studio_tutto(servizi)
    d = client.get(f"/api/schemi/{s['id']}", headers=auth).json()
    assert d["stato"] == "pronto"
    return d


def mappa_pronta(client, auth, servizi, schema_id, tipo):
    r = client.post(f"/api/schemi/{schema_id}/mappe", json={"tipo": tipo}, headers=auth)
    assert r.status_code == 201, r.text
    assert r.json()["stato"] == "in_coda" and r.json()["frase"]
    studio_tutto(servizi)
    x = client.get(f"/api/mappe/{r.json()['id']}", headers=auth).json()
    assert x["stato"] == "pronta", x
    return x


# --------------------------------------------------------------------------- regole fisse

def test_date_cercate_nello_schema():
    testo = mappe.testo_numerato(SCHEMA_CON_DATE)
    assert testo.startswith("[Sezione 1] Augusto")
    assert mappe.date_nel_testo(testo) == 3 and mappe.date_possibili(SCHEMA_CON_DATE)
    o = {"eventi": [{"data": "27 a.C.", "anno": 27, "ac": True, "cosa": "Augusto"},
                    {"data": "1492", "anno": 1492, "cosa": "Colombo"},
                    {"data": "XV secolo", "anno": 1500, "cosa": "Commercio"},
                    {"data": "1789", "anno": 1789, "cosa": "Inventata"},
                    {"data": "1492", "anno": 1493, "cosa": "Anno che non torna"}]}
    assert mappe.date_fuori(o, testo) == ["1789: Inventata", "1492: Anno che non torna"]
    n = mappe.normalizza_date(mappe.togli_date_fuori(o, testo), {1, 2})["eventi"]
    assert [(e["data"], e["x"], e["x_fine"]) for e in n] == [("27 a.C.", -26, None), ("XV secolo", 1401, 1500), ("1492", 1492, None)]
    assert [e["id"] for e in n] == [1, 2, 3]


def test_secoli_e_anni_avanti_cristo():
    assert mappe.secolo_di("XV secolo") == 15 and mappe.secolo_di("sec. III") == 3 and mappe.secolo_di("il Quattrocento") == 15
    assert mappe.astronomico(753, True) == -752 and mappe.astronomico(1, True) == 0
    e = mappe.normalizza_date({"eventi": [{"data": "V secolo a.C.", "anno": 400, "cosa": "Atene"},
                                          {"data": "753 a.C.", "anno": -753, "cosa": "Roma"},
                                          {"data": "1914-1918", "anno": 1914, "fine": 1918, "cosa": "Guerra"},
                                          {"data": "1914-1918", "anno": 1914, "fine": 1918, "cosa": "Guerra"}]}, set())["eventi"]
    assert [(x["data"], x["anno"], x["ac"], x["fine"]) for x in e] == [("753 a.C.", 753, True, None), ("V secolo a.C.", 500, True, 401),
                                                                       ("1914-1918", 1914, False, 1918)]


def test_concetti_un_albero_solo():
    assert mappe.valida_concetti({"concetti": [{"id": 1, "testo": "A", "genitore": 2}, {"id": 2, "testo": "B", "genitore": 1}]})
    assert "frase" in " ".join(mappe.valida_concetti({"concetti": [
        {"id": 1, "testo": "Roma", "genitore": None}, {"id": 2, "testo": "uno due tre quattro cinque sei sette otto nove", "genitore": 1},
        {"id": 3, "testo": "C", "genitore": 1}, {"id": 4, "testo": "D", "genitore": 1}]}))
    o = {"forma": "nuvola", "concetti": [{"id": "r", "testo": "Roma", "genitore": None}, {"id": "b", "testo": "Impero.", "genitore": "r", "verbo": "diventa"},
                                         {"id": "c", "testo": "Caduta", "genitore": "b", "verbo": "subisce"}, {"id": "d", "testo": "Senato", "genitore": "r", "verbo": "ha"},
                                         {"id": "x", "testo": "Isolato", "genitore": "z"}],
         "collegamenti": [{"da": "c", "a": "d", "verbo": "indebolisce"}, {"da": "r", "a": "b", "verbo": "doppione"}, {"da": "c", "a": "c"}]}
    n = mappe.normalizza_concetti(o, set())
    assert n["forma"] == "albero"
    assert [(c["id"], c["testo"], c["genitore"], c["verbo"]) for c in n["concetti"]] == [
        (1, "Roma", None, None), (2, "Impero", 1, "diventa"), (3, "Senato", 1, "ha"), (4, "Caduta", 2, "subisce")]
    assert n["collegamenti"] == [{"da": 4, "a": 3, "verbo": "indebolisce", "spiegazione": ""}]
    # Il controllo trova fuori "Impero subisce Caduta" e "Roma diventa Impero": Caduta sale a Roma senza verbo.
    oggetto, da_dove = mappe.frasi_da_controllare("concetti", n)
    assert oggetto["voci"] == ["Roma diventa Impero.", "Roma ha Senato.", "Impero subisce Caduta.", "Caduta indebolisce Senato."]
    tolto = mappe.togli_fuori("concetti", n, [(oggetto["voci"], 0, 0, "")], da_dove)
    assert [(c["id"], c["genitore"], c["verbo"]) for c in tolto["concetti"]] == [(1, None, None), (3, 1, "ha"), (4, 1, None)]


def test_correggi_a_mano_sposta_le_date():
    attuale = mappe.normalizza_date({"eventi": [{"data": "1492", "anno": 1492, "cosa": "Colombo"}, {"data": "753 a.C.", "anno": 753, "ac": True, "cosa": "Roma"},
                                               {"data": "1914", "anno": 1914, "cosa": "Guerra"}]}, set())
    nuovo = mappe.pulisci("date", attuale, {"eventi": [{"data": "753 a.C.", "cosa": "Roma fondata", "spiegazione": "Secondo la leggenda."},
                                                       {"data": "12 ottobre 1492", "cosa": "Colombo", "spiegazione": ""},
                                                       {"data": "1914-1918", "cosa": "", "spiegazione": ""}]})
    assert [(e["data"], e["cosa"], e["x"]) for e in nuovo["eventi"]] == [("753 a.C.", "Roma fondata", -752), ("12 ottobre 1492", "Colombo", 1492.75)]
    with pytest.raises(mappe.FormaSbagliata):
        mappe.pulisci("date", attuale, {"eventi": []})


# --------------------------------------------------------------------------- in prova

def test_mappe_in_prova_dalla_scheda_dello_schema(client, auth, servizi):
    d = schema_con_date(client, auth, servizi)
    assert d["mappe"]["date_possibili"] is True, mappe.testo_numerato(d["contenuto"])
    assert d["mappe"]["stima_eur"] == 0 and d["mappe"]["fatte"] == []
    concetti = mappa_pronta(client, auth, servizi, d["id"], "concetti")
    c = concetti["contenuto"]
    assert c["concetti"][0]["genitore"] is None and all(x["genitore"] for x in c["concetti"][1:])
    assert concetti["elementi"] == len(c["concetti"]) and concetti["nota"] and concetti["schema_cambiato"] is False
    assert concetti["parti_modificabili"] == [{"n": 1, "titolo": concetti["titolo"]}]
    date = mappa_pronta(client, auth, servizi, d["id"], "date")
    eventi = date["contenuto"]["eventi"]
    assert len(eventi) >= 2 and [e["x"] for e in eventi] == sorted(e["x"] for e in eventi)
    assert all(e["ac"] for e in eventi if "a.C." in e["data"])
    # Già fatta: la stessa richiesta non ne fa un'altra.
    assert client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "date"}, headers=auth).json()["id"] == date["id"]
    fatte = client.get(f"/api/schemi/{d['id']}", headers=auth).json()["mappe"]["fatte"]
    assert {(x["tipo"], x["stato"]) for x in fatte} == {("concetti", "pronta"), ("date", "pronta")}
    assert [x["id"] for x in client.get(f"/api/schemi/{d['id']}/mappe", headers=auth).json()] == [concetti["id"], date["id"]]
    assert client.delete(f"/api/mappe/{date['id']}", headers=auth).json() == {"ok": True}
    assert client.get(f"/api/mappe/{date['id']}", headers=auth).status_code == 404
    # Lo schema si cancella: anche le sue mappe.
    assert client.delete(f"/api/schemi/{d['id']}", headers=auth).status_code == 200
    assert client.get(f"/api/mappe/{concetti['id']}", headers=auth).status_code == 404


def test_linea_del_tempo_solo_con_abbastanza_date(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)  # gli appunti hanno una data sola
    assert d["mappe"]["date_possibili"] is False
    r = client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "date"}, headers=auth)
    assert r.status_code == 409 and r.json()["errore"] == mappe.FRASE_POCHE_DATE
    assert client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "altro"}, headers=auth).status_code == 422


def test_mappa_solo_da_schema_pronto_e_riavvio(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"], APPUNTI_DATE)
    sfondo_tutto(servizi)
    s = crea_schema(client, auth, m["id"])
    assert client.post(f"/api/schemi/{s['id']}/mappe", json={"tipo": "concetti"}, headers=auth).status_code == 409
    studio_tutto(servizi)
    r = client.post(f"/api/schemi/{s['id']}/mappe", json={"tipo": "concetti"}, headers=auth).json()
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE mappe SET stato = 'in_preparazione' WHERE id = ?", (r["id"],))
    db.ripristina_dopo_riavvio(servizi.settings.db_path)
    x = client.get(f"/api/mappe/{r['id']}", headers=auth).json()
    assert x["stato"] == "errore" and x["errore"]
    assert client.post(f"/api/mappe/{r['id']}/riprova", headers=auth).json()["stato"] == "in_coda"
    assert client.post(f"/api/mappe/{r['id']}/riprova", headers=auth).status_code == 409
    studio_tutto(servizi)
    assert client.get(f"/api/mappe/{r['id']}", headers=auth).json()["stato"] == "pronta"


def test_modifica_correggi_e_torna_a_prima(client, auth, servizi):
    d = schema_con_date(client, auth, servizi)
    x = mappa_pronta(client, auth, servizi, d["id"], "concetti")
    st = client.post("/api/modifiche/stima", json={"tipo": "mappa", "id": x["id"], "richiesta": "Più semplice"}, headers=auth).json()
    assert st["consentita"] and st["fino_a"] is False
    m = client.post("/api/modifiche", json={"tipo": "mappa", "id": x["id"], "richiesta": "Più semplice"}, headers=auth)
    assert m.status_code == 201, m.text
    assert client.post("/api/modifiche", json={"tipo": "mappa", "id": x["id"], "richiesta": "Altro"}, headers=auth).status_code == 409
    studio_tutto(servizi)
    y = client.get(f"/api/mappe/{x['id']}", headers=auth).json()
    assert y["modifica"]["stato"] == "pronta" and y["modifica"]["cambiate"] == [1]
    assert "cambiata in prova: Più semplice" in y["titolo"]
    assert y["versione_da_parte"]["ruolo"] == "prima"
    z = client.post(f"/api/mappe/{x['id']}/scambia-versione", headers=auth).json()
    assert z["titolo"] == x["titolo"] and z["versione_da_parte"]["ruolo"] == "dopo"

    corretto = json.loads(json.dumps(z["contenuto"]))
    corretto["concetti"][1]["testo"] = "Concetto mio"
    corretto["concetti"][1]["verbo"] = "porta a"
    corretto["concetti"][0]["testo"] = ""  # vuoto: resta quello di prima
    w = client.put(f"/api/mappe/{x['id']}/testo", json={"titolo": "La mia mappa", "contenuto": corretto}, headers=auth).json()
    assert w["titolo"] == "La mia mappa" and w["contenuto"]["concetti"][1]["testo"] == "Concetto mio"
    assert w["contenuto"]["concetti"][1]["verbo"] == "porta a" and w["contenuto"]["concetti"][0]["testo"] == z["contenuto"]["concetti"][0]["testo"]
    assert w["versione_da_parte"]["perche"] == "a_mano"
    corretto["concetti"] = corretto["concetti"][:1]
    assert client.put(f"/api/mappe/{x['id']}/testo", json={"contenuto": corretto}, headers=auth).status_code == 422

    # Lo schema cambia: la mappa lo dice.
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE schemi SET cambiato_il = '2099-01-01T00:00:00' WHERE id = ?", (d["id"],))
    assert client.get(f"/api/mappe/{x['id']}", headers=auth).json()["schema_cambiato"] is True


# --------------------------------------------------------------------------- con l'AI (finta) a pagamento

def _metti_schema_con_date(servizi, schema_id):
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE schemi SET contenuto = ? WHERE id = ?", (json.dumps(SCHEMA_CON_DATE, ensure_ascii=False), schema_id))


@pagamento
def test_linea_del_tempo_vera_senza_date_inventate(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    _metti_schema_con_date(servizi, d["id"])
    d = client.get(f"/api/schemi/{d['id']}", headers=auth).json()
    assert d["mappe"]["date_possibili"] and d["mappe"]["stima_eur"] > 0
    n = server.conta("/v1/chat/completions")
    r = client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "date"}, headers=auth).json()
    server.fuori_coda = [[{"n": 2, "motivo": "non c'è"}], [{"n": 2, "motivo": "non c'è"}]]
    studio_tutto(servizi)
    x = client.get(f"/api/mappe/{r['id']}", headers=auth).json()
    assert x["stato"] == "pronta" and x["nota"] is None and x["titolo"] == "Le date dell'impero"
    eventi = x["contenuto"]["eventi"]
    assert [(e["data"], e["anno"], e["fine"]) for e in eventi] == [("27 a.C.", 27, None), ("XV secolo", 1401, 1500), ("1492", 1492, None)]
    assert eventi[0]["spiegazione"] == ""  # la frase che il controllo trova fuori si toglie
    assert eventi[2]["mese"] == 10 and eventi[2]["x"] == 1492.75
    # scritta, date corrette, controllata, riscritta, ricontrollata
    assert server.conta("/v1/chat/completions") == n + 5
    chat = [c for p, c in server.chiamate if p == "/v1/chat/completions"][n:]
    assert any("Queste date non le trovo" in c["messages"][-1]["content"] and "1789" in c["messages"][-1]["content"] for c in chat)
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM spese WHERE riferimento = ?", (f"mappa:{r['id']}",)).fetchone()[0] == 5


@pagamento
def test_mappa_dei_concetti_vera_e_modifica(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    r = client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "concetti"}, headers=auth).json()
    server.fuori_coda = [[{"n": 2, "motivo": "non c'è"}], [{"n": 2, "motivo": "non c'è"}]]  # "Augusto tiene Potere vero."
    studio_tutto(servizi)
    x = client.get(f"/api/mappe/{r['id']}", headers=auth).json()
    assert x["stato"] == "pronta" and x["contenuto"]["domanda"] == "Come governò Augusto?"
    concetti = x["contenuto"]["concetti"]
    # "Potere vero" è tolto: le Legioni salgono ad Augusto, senza verbo; il collegamento resta.
    assert [(c["testo"], c["genitore"], c["verbo"]) for c in concetti] == [
        ("Augusto", None, None), ("Senato", 1, "lascia in piedi"), ("Legioni", 1, None), ("Facciata", 3, "resta di")]
    assert x["contenuto"]["collegamenti"] == [{"da": 4, "a": 3, "verbo": "pesano più del", "spiegazione": ""}]
    m = client.post("/api/modifiche", json={"tipo": "mappa", "id": x["id"], "richiesta": "Aggiungi le legioni"}, headers=auth).json()
    studio_tutto(servizi)
    assert client.get(f"/api/modifiche/{m['id']}", headers=auth).json()["stato"] == "pronta"
    assert any("Aggiungi le legioni" in t for t in server.richieste_modifica)


@pagamento
def test_mappa_fermata_e_tetto(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    r = client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "concetti"}, headers=auth).json()
    server.guasti["/v1/chat/completions"] = [401]
    studio_tutto(servizi)
    assert client.get(f"/api/mappe/{r['id']}", headers=auth).json()["stato"] == "errore"
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT tipo FROM novita ORDER BY id DESC LIMIT 1").fetchone()[0] == "mappa_errore"
        db.salva_impostazione(conn, "tetto_eur", 0.0)
    t = client.post(f"/api/schemi/{d['id']}/mappe", json={"tipo": "concetti"}, headers=auth)
    assert t.status_code == 402 and "tetto" in t.json()["errore"]
