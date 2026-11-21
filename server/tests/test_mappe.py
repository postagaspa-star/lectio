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
    o = {"forma": "nuvola", "concetti": [{"id": "r", "testo": "Roma", "genitore": None},
                                         {"id": "b", "testo": "Impero.", "genitore": "r", "verbo": "diventa", "frase": "Augusto lo fonda nel 27 a.C."},
                                         {"id": "c", "testo": "Crisi", "genitore": "b", "verbo": "subisce", "frase": "Tasse e guerre lo indeboliscono."},
                                         {"id": "e", "testo": "Caduta", "genitore": "c", "verbo": "porta alla", "frase": "Arriva nel 476 d.C."},
                                         {"id": "d", "testo": "Senato", "genitore": "r", "verbo": "ha"},
                                         {"id": "x", "testo": "Isolato", "genitore": "z"}],
         "collegamenti": [{"da": "c", "a": "d", "verbo": "indebolisce"}]}
    n = mappe.normalizza_concetti(o, set())
    # 1.4.1: a riquadri, niente frecce tra rami; il verbo solo tra due concetti (mai dall'argomento o da una parte).
    assert n["forma"] == "riquadri" and n["collegamenti"] == []
    assert [(c["id"], c["testo"], c["genitore"], c["verbo"], c["frase"]) for c in n["concetti"]] == [
        (1, "Roma", None, None, None), (2, "Impero", 1, None, "Augusto lo fonda nel 27 a.C."), (3, "Senato", 1, None, None),
        (4, "Crisi", 2, None, "Tasse e guerre lo indeboliscono."), (5, "Caduta", 4, "porta alla", "Arriva nel 476 d.C.")]
    assert mappe.normalizza_concetti({**o, "forma": "raggiera"}, set())["forma"] == "raggiera"
    # Le mappe di prima date al modello per una Modifica: i verbi dei gruppi non si ricopiano.
    vecchia = {"forma": "albero", "concetti": [dict(c, verbo="ha") if c["genitore"] else c for c in n["concetti"]]}
    assert [c["verbo"] for c in mappe.per_modello("concetti", "T", vecchia)["concetti"]] == [None, None, None, None, "ha"]
    oggetto, da_dove = mappe.frasi_da_controllare("concetti", n)
    voci = oggetto["voci"]
    assert voci == ["Roma: Impero.", "Impero: Augusto lo fonda nel 27 a.C.", "Roma: Senato.", "Impero: Crisi.",
                    "Crisi: Tasse e guerre lo indeboliscono.", "Crisi: Caduta.", "Crisi porta alla Caduta.", "Caduta: Arriva nel 476 d.C."]
    # Un verbo sbagliato toglie solo la freccia; una frase sbagliata solo la frase.
    tolto = mappe.togli_fuori("concetti", n, [(voci, 6, 0, ""), (voci, 7, 0, "")], da_dove)
    assert [(c["id"], c["genitore"], c["verbo"], c["frase"]) for c in tolto["concetti"]] == [
        (1, None, None, None), (2, 1, None, "Augusto lo fonda nel 27 a.C."), (3, 1, None, None),
        (4, 2, None, "Tasse e guerre lo indeboliscono."), (5, 4, None, None)]
    # Un concetto che non c'è si toglie: chi stava sotto sale, senza verbo.
    tolto = mappe.togli_fuori("concetti", n, [(voci, 3, 0, "")], da_dove)
    assert [(c["id"], c["genitore"], c["verbo"]) for c in tolto["concetti"]] == [(1, None, None), (2, 1, None), (3, 1, None), (5, 2, None)]


def test_concetti_senza_frasi_o_con_frasi_lunghe():
    radice = {"id": 1, "testo": "Roma", "genitore": None}
    senza = [radice] + [{"id": k, "testo": f"Parte {k}", "genitore": 1} for k in range(2, 6)]
    assert any("manca la" in e for e in mappe.valida_concetti({"concetti": senza}))
    con = [radice] + [{"id": k, "testo": f"Parte {k}", "genitore": 1, "frase": "Una frase giusta."} for k in range(2, 6)]
    assert mappe.valida_concetti({"concetti": con}) == []
    con[1] = {**con[1], "frase": " ".join(["parola"] * 40)}
    assert any("troppo lunga" in e for e in mappe.valida_concetti({"concetti": con}))
    # Qualche frase mancante si accetta.
    con[1] = {"id": 2, "testo": "Parte 2", "genitore": 1}
    assert mappe.valida_concetti({"concetti": con}) == []


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
    corretto["concetti"][1]["frase"] = "La mia frase."
    corretto["concetti"][1]["verbo"] = "porta a"  # sotto l'argomento la freccia non c'è: si ignora
    corretto["concetti"][0]["testo"] = ""  # vuoto: resta quello di prima
    w = client.put(f"/api/mappe/{x['id']}/testo", json={"titolo": "La mia mappa", "contenuto": corretto}, headers=auth).json()
    assert w["titolo"] == "La mia mappa" and w["contenuto"]["concetti"][1]["testo"] == "Concetto mio"
    assert w["contenuto"]["concetti"][1]["frase"] == "La mia frase." and w["contenuto"]["concetti"][1]["verbo"] is None
    assert w["contenuto"]["concetti"][0]["testo"] == z["contenuto"]["concetti"][0]["testo"]
    sotto = next((k for k, c in enumerate(w["contenuto"]["concetti"]) if c["genitore"] not in (None, 1)), None)
    if sotto is not None:  # nemmeno sotto una parte
        corretto = json.loads(json.dumps(w["contenuto"]))
        corretto["concetti"][sotto]["verbo"] = "porta a"
        v = client.put(f"/api/mappe/{x['id']}/testo", json={"contenuto": corretto}, headers=auth).json()
        assert v["contenuto"]["concetti"][sotto]["verbo"] is None
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
    server.fuori_coda = [[{"n": 2, "motivo": "non c'è"}], [{"n": 2, "motivo": "non c'è"}]]  # "Augusto: Potere vero."
    studio_tutto(servizi)
    x = client.get(f"/api/mappe/{r['id']}", headers=auth).json()
    assert x["stato"] == "pronta" and x["contenuto"]["domanda"] == "Come governò Augusto?"
    assert x["contenuto"]["forma"] == "riquadri"
    concetti = x["contenuto"]["concetti"]
    # "Potere vero" è tolto: le Legioni salgono ad Augusto, senza verbo. Il verbo resta solo tra due concetti (Facciata e
    # Consoli); le frecce tra rami non ci sono.
    assert [(c["testo"], c["genitore"], c["verbo"]) for c in concetti] == [
        ("Augusto", None, None), ("Senato", 1, None), ("Legioni", 1, None), ("Facciata", 3, None), ("Consoli", 5, "tiene i")]
    assert concetti[1]["frase"] == "Si riunisce ancora ma conta poco."
    assert x["contenuto"]["collegamenti"] == []
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
