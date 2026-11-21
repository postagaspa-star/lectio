"""File aggiunti a una modifica: restano nella materia, legati alle parti che hanno cambiato (contratto 28.5)."""

import json

from aiuti import aggiungi_testo, crea_materia, lezione_pronta, prepara_tutto, sfondo_tutto
from app import db, modifiche, schema_ripasso, simulata
from app.cartella import Cartella
from test_mappe import mappa_pronta
from test_modifiche import riassunto_pronto
from test_studio import _simulata, materia_pronta, schema_pronto, studio_tutto

APPUNTI_NUOVI = ("Augusto ricevette dal senato il titolo di princeps nel 27 avanti Cristo. "
                 "Il principato teneva insieme le forme della repubblica e il potere di un uomo solo. "
                 "Augusto riorganizzò le province e creò la guardia pretoriana.")


def con_file(client, auth, tipo, ident, materiali, richiesta="Aggiungi quello che c'è nei file", parti=None, atteso=201):
    r = client.post("/api/modifiche", json={"tipo": tipo, "id": ident, "richiesta": richiesta, "parti": parti or [],
                                            "materiali": materiali}, headers=auth)
    assert r.status_code == atteso, r.text
    return r.json()


def file_nuovo(client, auth, materia_id, titolo="Foto pag. 52", testo=APPUNTI_NUOVI):
    return aggiungi_testo(client, auth, materia_id, testo, titolo=titolo)


def test_schema_con_file_aspetta_la_lettura_e_lega_il_file_alla_parte(client, auth, servizi):
    m, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert d["file_delle_modifiche"] == []
    f = file_nuovo(client, auth, m["id"])
    assert f["usato_per"] == []

    senza = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Di più", "parti": [2]},
                        headers=auth).json()
    con = client.post("/api/modifiche/stima", json={"tipo": "schema", "id": d["id"], "richiesta": "Di più", "parti": [2],
                                                    "materiali": [f["id"]]}, headers=auth).json()
    assert senza["consentita"] and con["consentita"]

    mod = con_file(client, auth, "schema", d["id"], [f["id"]], parti=[2])
    assert mod["stato"] == "in_coda"
    studio_tutto(servizi)  # il file non è ancora diviso in pezzi: la modifica aspetta
    assert client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()["stato"] == "in_coda"
    sfondo_tutto(servizi)
    studio_tutto(servizi)
    mod = client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()
    assert mod["stato"] == "pronta" and mod["cambiate"] == [2]

    dopo = client.get(f"/api/schemi/{d['id']}", headers=auth).json()
    assert dopo["contenuto"]["sezioni"][0] == sezioni[0]
    assert dopo["file_delle_modifiche"] == [{"materiale_id": f["id"], "titolo": "Foto pag. 52", "modifica_id": mod["id"],
                                             "stato": "pronta", "parti": [{"n": 2, "titolo": sezioni[1]["titolo"]}]}]
    # Il file è diventato un documento dello schema: la parte nuova lo cita.
    lettura = Cartella(schema_ripasso.cartella(servizi.settings.dati_dir, d["id"])).leggi("lettura.json")
    nuovi = [b["id"] for b in lettura["brani"] if b["materiale_id"] == f["id"]]
    assert nuovi and len({b["id"] for b in lettura["brani"]}) == len(lettura["brani"])
    fonti = [i for b in dopo["contenuto"]["sezioni"][1]["blocchi"] for i in b.get("fonti") or []]
    assert set(nuovi) & set(fonti)
    assert all(i in dopo["contenuto"]["fonti"] for i in fonti)

    x = client.get(f"/api/materiale/{f['id']}", headers=auth).json()
    assert x["usato_per"] == [{"tipo": "schema", "id": d["id"], "titolo": d["titolo"], "modifica_id": mod["id"],
                               "stato": "pronta", "parti": [{"n": 2, "titolo": sezioni[1]["titolo"]}]}]
    elenco = client.get(f"/api/materie/{m['id']}/materiale", headers=auth).json()
    assert next(e for e in elenco if e["id"] == f["id"])["usato_per"][0]["id"] == d["id"]

    # Cancellato il file, il legame se ne va; lo schema resta com'è.
    assert client.delete(f"/api/materiale/{f['id']}", headers=auth).status_code == 200
    assert client.get(f"/api/schemi/{d['id']}", headers=auth).json()["file_delle_modifiche"] == []


def test_file_sbagliati_e_modifica_annullata(client, auth, servizi):
    m, d = schema_pronto(client, auth, servizi)
    altra = crea_materia(client, auth, nome="Latino")
    fuori = aggiungi_testo(client, auth, altra["id"], APPUNTI_NUOVI)
    r = con_file(client, auth, "schema", d["id"], [fuori["id"]], atteso=422)
    assert r["campo"] == "materiali"
    assert con_file(client, auth, "schema", d["id"], [999_999], atteso=422)["campo"] == "materiali"
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM modifiche").fetchone()[0] == 0

    f = file_nuovo(client, auth, m["id"])
    with db.aperta(servizi.settings.db_path) as conn:  # come una foto ancora da leggere
        conn.execute("UPDATE materiali SET stato = 'in_lettura' WHERE id = ?", (f["id"],))
    mod = con_file(client, auth, "schema", d["id"], [f["id"]], parti=[1])
    assert mod["frase"] == modifiche.FRASE_ASPETTA_FILE
    assert client.get(f"/api/materiale/{f['id']}", headers=auth).json()["usato_per"][0]["parti"][0]["n"] == 1
    client.post(f"/api/modifiche/{mod['id']}/annulla", headers=auth)
    assert client.get(f"/api/materiale/{f['id']}", headers=auth).json()["usato_per"] == []
    assert client.get(f"/api/schemi/{d['id']}", headers=auth).json()["file_delle_modifiche"] == []

    with db.aperta(servizi.settings.db_path) as conn:  # una foto che non si è potuta leggere non si può aggiungere
        conn.execute("UPDATE materiali SET stato = 'errore', errore = 'Foto vuota' WHERE id = ?", (f["id"],))
    assert con_file(client, auth, "schema", d["id"], [f["id"]], atteso=422)["campo"] == "materiali"


def test_file_in_errore_durante_l_attesa_resta_fuori_con_una_nota(client, auth, servizi):
    m, d = schema_pronto(client, auth, servizi)
    f = file_nuovo(client, auth, m["id"])
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE materiali SET stato = 'in_lettura' WHERE id = ?", (f["id"],))
    mod = con_file(client, auth, "schema", d["id"], [f["id"]], parti=[1])
    studio_tutto(servizi)
    assert client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()["stato"] == "in_coda"
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE materiali SET stato = 'errore', errore = 'Foto vuota' WHERE id = ?", (f["id"],))
    studio_tutto(servizi)
    mod = client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()
    assert mod["stato"] == "pronta" and "Foto pag. 52" in (mod["nota"] or "")


def test_con_tutto_e_file_e_gli_altri_tipi(client, auth, servizi):
    m, d = schema_pronto(client, auth, servizi)
    f1 = file_nuovo(client, auth, m["id"], "Appunti del 21")
    sfondo_tutto(servizi)
    mod = con_file(client, auth, "schema", d["id"], [f1["id"]])  # "Tutto": il modello sceglie le parti
    studio_tutto(servizi)
    mod = client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()
    assert mod["stato"] == "pronta" and mod["cambiate"]
    legati = client.get(f"/api/schemi/{d['id']}", headers=auth).json()["file_delle_modifiche"]
    assert [p["n"] for p in legati[0]["parti"]] == mod["cambiate"]

    # Riassunto, mappa e verifica: i file vanno nella richiesta e nel controllo, e restano legati.
    rias = riassunto_pronto(client, auth, servizi, d["id"])
    mp = mappa_pronta(client, auth, servizi, d["id"], "concetti")
    sim = _simulata(client, auth, m["id"])
    studio_tutto(servizi)
    for tipo, ident, parti in (("riassunto", rias["id"], [1]), ("mappa", mp["id"], []), ("simulata", sim["id"], [1])):
        f = file_nuovo(client, auth, m["id"], f"File per {tipo}")
        sfondo_tutto(servizi)
        x = con_file(client, auth, tipo, ident, [f["id"]], parti=parti)
        studio_tutto(servizi)
        x = client.get(f"/api/modifiche/{x['id']}", headers=auth).json()
        assert x["stato"] == "pronta", (tipo, x)
        percorso = {"riassunto": "riassunti", "mappa": "mappe", "simulata": "simulate"}[tipo]
        oggetto = client.get(f"/api/{percorso}/{ident}", headers=auth).json()
        assert [y["materiale_id"] for y in oggetto["file_delle_modifiche"]] == [f["id"]]
        assert client.get(f"/api/materiale/{f['id']}", headers=auth).json()["usato_per"][0]["tipo"] == tipo
    lettura = Cartella(simulata.cartella(servizi.settings.dati_dir, sim["id"])).leggi("lettura.json")
    assert any(b["titolo"] == "File per simulata" for b in lettura["brani"])


def test_lezione_con_file(client, auth, servizi):
    lezione = lezione_pronta(client, auth, servizi)
    f = file_nuovo(client, auth, lezione["materia_id"])
    sfondo_tutto(servizi)
    mod = con_file(client, auth, "lezione", lezione["id"], [f["id"]], parti=[1])
    prepara_tutto(servizi)
    mod = client.get(f"/api/modifiche/{mod['id']}", headers=auth).json()
    assert mod["stato"] == "pronta", mod
    dopo = client.get(f"/api/lezioni/{lezione['id']}", headers=auth).json()
    assert dopo["file_delle_modifiche"][0]["materiale_id"] == f["id"]
    lettura = json.loads((modifiche.cartella_lezione(servizi.settings.dati_dir, lezione["id"]) / "lettura.json").read_text("utf-8"))
    assert any(b["materiale_id"] == f["id"] for b in lettura["brani"])
