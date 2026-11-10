"""Cartelle dentro la materia (contratto 27): un livello solo, spostare, eliminare con o senza i file,
Lectio che mette da solo i file nuovi nella cartella giusta, proposte e suggeriti."""

from pathlib import Path

import pytest

from aiuti import VERIFICA_GEMINI, aggiungi_testo, carica_file, crea_materia, sfondo_tutto
from app import cartelle, db

DISCOBOLO = ("Mirone scolpì il Discobolo in bronzo verso il 450 avanti Cristo. L'atleta è colto nel momento di "
             "massima torsione prima del lancio del disco. Lo stile severo greco cerca l'equilibrio tra movimento e "
             "calma. Dell'originale in bronzo restano copie in marmo. Mirone studia il corpo dell'atleta in tensione.")
DORIFORO = ("Policleto scrisse il Canone, un trattato sulle proporzioni del corpo umano. Il Doriforo, il portatore "
            "di lancia, applica il canone: la testa è un settimo dell'altezza. Il chiasmo bilancia la gamba tesa e "
            "il braccio rilassato. È l'opera simbolo della scultura greca classica, in bronzo come il Discobolo.")
COLOSSEO = ("L'Anfiteatro Flavio, detto Colosseo, fu costruito sotto Vespasiano e Tito. Usa archi e volte in "
            "calcestruzzo e travertino. Ospitava i giochi dei gladiatori. Gli ordini architettonici si sovrappongono: "
            "tuscanico, ionico e corinzio. Archi e volte permettono di coprire spazi enormi.")
ARA_PACIS = ("L'Ara Pacis fu voluta da Augusto per celebrare la pace. Il fregio mostra la processione della famiglia "
             "imperiale. Il rilievo racconta fatti storici veri. Il marmo di Carrara arriva nell'Urbe in questi "
             "anni, insieme a nuove tecniche per archi e volte nei grandi edifici imperiali.")
FIDIA = ("Fidia diresse i lavori del Partenone sull'Acropoli di Atene. Le metope e il fregio raccontano miti. "
         "La statua di Atena era d'oro e d'avorio. Fidia segue le proporzioni del canone di Policleto: il corpo "
         "umano in equilibrio, lo stile classico. Come nel Doriforo e nel Discobolo, il bronzo e il marmo "
         "mostrano il corpo dell'atleta e degli dei.")
PANTHEON = ("Il Pantheon fu ricostruito da Adriano. La cupola in calcestruzzo ha un oculo al centro. Archi e volte "
            "permettono spazi enormi, come nel Colosseo. Il pronao ha colonne corinzie in granito. Il calcestruzzo "
            "e il travertino sono i materiali dei grandi edifici imperiali.")


@pytest.fixture
def materia(client, auth):
    return crea_materia(client, auth, nome="Storia dell'Arte", prof="Verdi")


def nuova_cartella(client, auth, materia_id, nome) -> dict:
    r = client.post(f"/api/materie/{materia_id}/cartelle", json={"nome": nome}, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def materiale(client, auth, materiale_id) -> dict:
    return client.get(f"/api/materiale/{materiale_id}", headers=auth).json()


def cartelle_di(client, auth, materia_id) -> list[dict]:
    return client.get(f"/api/materie/{materia_id}/cartelle", headers=auth).json()


def test_crea_rinomina_elenco(client, auth, materia):
    b = nuova_cartella(client, auth, materia["id"], "  Arte   romana ")
    a = nuova_cartella(client, auth, materia["id"], "arte greca")
    assert b["nome"] == "Arte romana" and b["file"] == 0 and b["messi_da_lectio"] == 0
    assert [c["nome"] for c in cartelle_di(client, auth, materia["id"])] == ["arte greca", "Arte romana"]
    # Nome unico nella materia, senza distinguere maiuscole.
    r = client.post(f"/api/materie/{materia['id']}/cartelle", json={"nome": "ARTE GRECA"}, headers=auth)
    assert r.status_code == 409 and r.json()["codice"] == "conflitto"
    # In un'altra materia lo stesso nome va bene.
    altra = crea_materia(client, auth, nome="Storia")
    nuova_cartella(client, auth, altra["id"], "Arte greca")
    for nome in ("", "   ", "x" * 41):
        r = client.post(f"/api/materie/{materia['id']}/cartelle", json={"nome": nome}, headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == "nome", nome
    r = client.patch(f"/api/cartelle/{a['id']}", json={"nome": "Arte Greca"}, headers=auth)
    assert r.status_code == 200 and r.json()["nome"] == "Arte Greca"  # la stessa, con le maiuscole cambiate
    assert client.patch(f"/api/cartelle/{a['id']}", json={"nome": "arte romana"}, headers=auth).status_code == 409
    assert client.patch("/api/cartelle/999", json={"nome": "X"}, headers=auth).status_code == 404
    assert client.get("/api/materie/999/cartelle", headers=auth).status_code == 404
    assert client.post("/api/materie/999/cartelle", json={"nome": "X"}, headers=auth).status_code == 404


def test_caricare_dentro_una_cartella(client, auth, materia, servizi):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    m = aggiungi_testo(client, auth, materia["id"], DISCOBOLO, cartella_id=greca["id"])
    assert m["cartella_id"] == greca["id"] and m["cartella_da"] == "utente"
    r = carica_file(client, auth, materia["id"], "doriforo.txt", DORIFORO.encode(), cartella_id=str(greca["id"]))
    assert r.status_code == 201 and r.json()["cartella_id"] == greca["id"]
    # Una cartella di un'altra materia (o che non c'è) non va.
    altra = crea_materia(client, auth, nome="Storia")
    sua = nuova_cartella(client, auth, altra["id"], "Roma")
    r = client.post(f"/api/materie/{materia['id']}/materiale", json={"testo": "x" * 20, "cartella_id": sua["id"]},
                    headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "cartella_id"
    r = carica_file(client, auth, materia["id"], "a.txt", b"Testo qualsiasi di prova.", cartella_id="abc")
    assert r.status_code == 422 and r.json()["campo"] == "cartella_id"
    assert len(client.get(f"/api/materie/{materia['id']}/materiale", headers=auth).json()) == 2
    sfondo_tutto(servizi)
    # Scelti da Andrea: Lectio non li sposta.
    assert {x["cartella_id"] for x in client.get(f"/api/materie/{materia['id']}/materiale", headers=auth).json()} == {greca["id"]}
    assert cartelle_di(client, auth, materia["id"])[0]["file"] == 2


def test_spostare_uno_o_tanti(client, auth, materia):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    romana = nuova_cartella(client, auth, materia["id"], "Arte romana")
    a = aggiungi_testo(client, auth, materia["id"], DISCOBOLO)
    b = aggiungi_testo(client, auth, materia["id"], DORIFORO)
    c = aggiungi_testo(client, auth, materia["id"], COLOSSEO)
    r = client.post(f"/api/materie/{materia['id']}/sposta", json={"materiali": [a["id"], b["id"]], "cartella_id": greca["id"]},
                    headers=auth)
    assert r.status_code == 200 and r.json() == {"spostati": 2}
    assert materiale(client, auth, a["id"])["cartella_da"] == "utente"
    # Uno solo dalla scheda del materiale; senza cartella_id non cambia niente.
    assert client.patch(f"/api/materiale/{c['id']}", json={"cartella_id": romana["id"]}, headers=auth).json()["cartella_id"] == romana["id"]
    assert client.patch(f"/api/materiale/{c['id']}", json={"titolo": "Il Colosseo"}, headers=auth).json()["cartella_id"] == romana["id"]
    fuori = client.patch(f"/api/materiale/{c['id']}", json={"cartella_id": None}, headers=auth).json()
    assert fuori["cartella_id"] is None and fuori["cartella_da"] is None
    # Fuori per tutti e due.
    assert client.post(f"/api/materie/{materia['id']}/sposta", json={"materiali": [a["id"], b["id"]], "cartella_id": None},
                       headers=auth).json() == {"spostati": 2}
    assert all(x["cartella_id"] is None for x in client.get(f"/api/materie/{materia['id']}/materiale", headers=auth).json())
    # Materiale di un'altra materia o che non c'è, elenco vuoto, cartella sbagliata.
    altra = crea_materia(client, auth, nome="Storia")
    suo = aggiungi_testo(client, auth, altra["id"])
    for corpo in ({"materiali": [a["id"], suo["id"]], "cartella_id": greca["id"]},
                  {"materiali": [999], "cartella_id": greca["id"]},
                  {"materiali": [], "cartella_id": greca["id"]},
                  {"materiali": [a["id"]], "cartella_id": 999}):
        assert client.post(f"/api/materie/{materia['id']}/sposta", json=corpo, headers=auth).status_code == 422, corpo
    assert client.patch(f"/api/materiale/{a['id']}", json={"cartella_id": 999}, headers=auth).status_code == 422
    assert materiale(client, auth, a["id"])["cartella_id"] is None


def test_eliminare_solo_la_cartella(client, auth, materia, servizi):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    a = aggiungi_testo(client, auth, materia["id"], DISCOBOLO, cartella_id=greca["id"])
    r = client.request("DELETE", f"/api/cartelle/{greca['id']}", headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "conferma"
    r = client.request("DELETE", f"/api/cartelle/{greca['id']}", json={"conferma": True}, headers=auth)
    assert r.status_code == 200 and r.json() == {"ok": True, "file_eliminati": 0}
    dopo = materiale(client, auth, a["id"])
    assert dopo["cartella_id"] is None and dopo["testo"] == DISCOBOLO
    assert cartelle_di(client, auth, materia["id"]) == []
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT cartella_da FROM materiali WHERE id = ?", (a["id"],)).fetchone()[0] == "utente"
    assert client.request("DELETE", f"/api/cartelle/{greca['id']}", json={"conferma": True}, headers=auth).status_code == 404


def test_eliminare_la_cartella_con_i_file(client, auth, materia, servizi):
    greca = nuova_cartella(client, auth, materia["id"], "Verifiche")
    fuori = aggiungi_testo(client, auth, materia["id"], COLOSSEO)
    v = aggiungi_testo(client, auth, materia["id"], VERIFICA_GEMINI, tipo="verifica", cartella_id=greca["id"])
    r = carica_file(client, auth, materia["id"], "doriforo.txt", DORIFORO.encode(), cartella_id=str(greca["id"]))
    f = r.json()
    percorso = Path(servizi.settings.dati_dir) / "materiale" / str(materia["id"]) / f"{f['id']}.txt"
    assert percorso.is_file()
    sfondo_tutto(servizi)
    assert client.get(f"/api/materie/{materia['id']}/scheda", headers=auth).json()["basata_su"]["verifiche"] == 1
    r = client.request("DELETE", f"/api/cartelle/{greca['id']}", json={"conferma": True, "con_i_file": True}, headers=auth)
    assert r.status_code == 200 and r.json() == {"ok": True, "file_eliminati": 2}
    assert client.get(f"/api/materiale/{v['id']}", headers=auth).status_code == 404
    assert not percorso.exists()
    assert [x["id"] for x in client.get(f"/api/materie/{materia['id']}/materiale", headers=auth).json()] == [fuori["id"]]
    # Una verifica in meno: la scheda del prof si rifà.
    assert client.get(f"/api/materie/{materia['id']}/scheda", headers=auth).json()["stato"] in ("in_aggiornamento", "vuota")


def test_lectio_mette_i_file_nuovi_nella_cartella_giusta(client, auth, materia, servizi, orologio):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    romana = nuova_cartella(client, auth, materia["id"], "Arte romana")
    for testo in (DISCOBOLO, DORIFORO):
        aggiungi_testo(client, auth, materia["id"], testo, cartella_id=greca["id"])
    for testo in (COLOSSEO, ARA_PACIS):
        aggiungi_testo(client, auth, materia["id"], testo, cartella_id=romana["id"])
    fidia = aggiungi_testo(client, auth, materia["id"], FIDIA, titolo="Fidia e il Partenone")
    pantheon = carica_file(client, auth, materia["id"], "pantheon.txt", PANTHEON.encode()).json()
    assert fidia["cartella_id"] is None and fidia["cartella_da"] is None
    sfondo_tutto(servizi)
    assert materiale(client, auth, fidia["id"])["cartella_id"] == greca["id"]
    assert materiale(client, auth, fidia["id"])["cartella_da"] == "lectio"
    assert materiale(client, auth, pantheon["id"])["cartella_id"] == romana["id"]
    righe = {c["nome"]: c for c in cartelle_di(client, auth, materia["id"])}
    assert righe["Arte greca"]["file"] == 3 and righe["Arte greca"]["messi_da_lectio"] == 1
    novita = [n for n in client.get("/api/novita", headers=auth).json() if n["tipo"] == "materiale_in_cartella"]
    assert {n["materiale_id"] for n in novita} == {fidia["id"], pantheon["id"]}
    assert any(n["testo"] == "Ho messo «Fidia e il Partenone» nella cartella Arte greca" for n in novita)
    # Dopo un giorno non sono più "messi ora da Lectio", ma restano lì.
    orologio.avanza(hours=25)
    righe = {c["nome"]: c for c in cartelle_di(client, auth, materia["id"])}
    assert righe["Arte greca"]["messi_da_lectio"] == 0 and righe["Arte greca"]["file"] == 3
    # Spostato da Andrea: Lectio non lo rimette.
    client.patch(f"/api/materiale/{fidia['id']}", json={"cartella_id": None}, headers=auth)
    sfondo_tutto(servizi)
    assert materiale(client, auth, fidia["id"])["cartella_id"] is None


def test_quando_non_e_chiaro_resta_fuori(client, auth, materia, servizi):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    romana = nuova_cartella(client, auth, materia["id"], "Arte romana")
    aggiungi_testo(client, auth, materia["id"], DISCOBOLO, cartella_id=greca["id"])
    aggiungi_testo(client, auth, materia["id"], COLOSSEO, cartella_id=romana["id"])
    misto = aggiungi_testo(client, auth, materia["id"], "Appunti sparsi: compiti per lunedì, ricordarsi il libro e la "
                                                         "gita di primavera. Portare la giustificazione firmata.")
    sfondo_tutto(servizi)
    assert materiale(client, auth, misto["id"])["cartella_id"] is None
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT cartella_da FROM materiali WHERE id = ?", (misto["id"],)).fetchone()[0] == "nessuna"


def test_materia_senza_cartelle_e_materiale_di_prima(client, auth, materia, servizi):
    a = aggiungi_testo(client, auth, materia["id"], DISCOBOLO)
    sfondo_tutto(servizi)
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT cartella_da FROM materiali WHERE id = ?", (a["id"],)).fetchone()[0] == "nessuna"
        # Il materiale di prima della 1.3.0 (cartella_da NULL) non si tocca mai.
        conn.execute("UPDATE materiali SET cartella_da = NULL WHERE id = ?", (a["id"],))
    nuova_cartella(client, auth, materia["id"], "Mirone e il Discobolo")
    sfondo_tutto(servizi)
    assert materiale(client, auth, a["id"])["cartella_id"] is None


def test_il_nome_della_cartella_basta_anche_se_e_vuota(client, auth, materia, servizi):
    nuova_cartella(client, auth, materia["id"], "Policleto")
    nuova_cartella(client, auth, materia["id"], "Colosseo")
    d = aggiungi_testo(client, auth, materia["id"], DORIFORO, titolo="Policleto e il Doriforo")
    sfondo_tutto(servizi)
    dopo = materiale(client, auth, d["id"])
    assert dopo["cartella_da"] == "lectio"
    assert [c["nome"] for c in cartelle_di(client, auth, materia["id"]) if c["id"] == dopo["cartella_id"]] == ["Policleto"]


def test_proposti_per_metti_dei_file_qui(client, auth, materia):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    canone = nuova_cartella(client, auth, materia["id"], "Il canone")
    a = aggiungi_testo(client, auth, materia["id"], DISCOBOLO, cartella_id=greca["id"])
    b = aggiungi_testo(client, auth, materia["id"], DORIFORO)
    c = aggiungi_testo(client, auth, materia["id"], COLOSSEO)
    f = aggiungi_testo(client, auth, materia["id"], FIDIA)
    r = client.get(f"/api/cartelle/{canone['id']}/proposti", headers=auth)
    assert r.status_code == 200
    proposti = r.json()["proposti"]
    # "canone" sta due volte nel Doriforo; Fidia lo nomina una volta sola (non basta); il Discobolo è già in
    # un'altra cartella; il Colosseo non c'entra.
    assert proposti == [b["id"]]
    assert f["id"] and a["id"] and c["id"]
    # Con un file dentro conta anche la somiglianza: Fidia assomiglia al Doriforo.
    client.post(f"/api/materie/{materia['id']}/sposta", json={"materiali": [b["id"]], "cartella_id": canone["id"]}, headers=auth)
    assert client.get(f"/api/cartelle/{canone['id']}/proposti", headers=auth).json()["proposti"] == [f["id"]]
    assert client.get("/api/cartelle/999/proposti", headers=auth).status_code == 404


def test_suggeriti_col_nome_della_cartella(client, auth, materia):
    greca = nuova_cartella(client, auth, materia["id"], "Scultura severa")
    a = aggiungi_testo(client, auth, materia["id"], DISCOBOLO, titolo="Mirone", cartella_id=greca["id"])
    aggiungi_testo(client, auth, materia["id"], COLOSSEO, titolo="Il Colosseo")
    r = client.get(f"/api/materie/{materia['id']}/suggeriti", params={"argomento": "La scultura severa"}, headers=auth).json()
    assert r["suggeriti"] == [a["id"]]


def test_cancellare_la_materia_toglie_le_cartelle(client, auth, materia, servizi):
    greca = nuova_cartella(client, auth, materia["id"], "Arte greca")
    aggiungi_testo(client, auth, materia["id"], DISCOBOLO, cartella_id=greca["id"])
    assert client.request("DELETE", f"/api/materie/{materia['id']}", json={"conferma": True}, headers=auth).status_code == 200
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM cartelle").fetchone()[0] == 0


def test_confronto_senza_cartelle_o_senza_parole():
    c = cartelle.Confronto([{"id": 1, "titolo": "", "testo": "", "cartella_id": None}], [])
    assert c.scegli(1) is None and c.scegli(2) is None
    c = cartelle.Confronto([{"id": 1, "titolo": "Augusto", "testo": "Augusto e il senato", "cartella_id": None}],
                           [{"id": 5, "nome": "Età"}])  # parole troppo corte: il nome non dice niente
    assert c.nome(1, 5) == 0 and c.scegli(1) is None
