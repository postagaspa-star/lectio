"""Eventi, esiti, centratura (contratto 7), pagina Oggi (10) e novità (11)."""

from aiuti import aggiungi_testo, crea_materia, lezione_pronta, nuova_lezione, prepara_tutto, sfondo_tutto


def evento(client, auth, materia_id, data, tipo="scritta", argomento="Impero romano"):
    r = client.post(f"/api/materie/{materia_id}/eventi", json={"tipo": tipo, "argomento": argomento, "data": data},
                    headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def test_eventi_crea_elenca_cambia_cancella(client, auth):
    m = crea_materia(client, auth)
    vecchio = evento(client, auth, m["id"], "2026-09-01")
    recente = evento(client, auth, m["id"], "2026-10-10")
    futuro = evento(client, auth, m["id"], "2026-10-30", tipo="orale")
    assert futuro == {"id": futuro["id"], "materia_id": m["id"], "tipo": "orale", "argomento": "Impero romano",
                      "data": "2026-10-30", "esito": None, "lezione_id": None}
    # Di base: da 30 giorni fa in poi.
    assert [e["id"] for e in client.get("/api/eventi", headers=auth).json()] == [recente["id"], futuro["id"]]
    tutti = client.get("/api/eventi?da=2026-01-01", headers=auth).json()
    assert [e["id"] for e in tutti] == [vecchio["id"], recente["id"], futuro["id"]]
    assert client.get("/api/eventi?da=ieri", headers=auth).status_code == 422
    r = client.patch(f"/api/eventi/{futuro['id']}", json={"data": "2026-11-02", "argomento": "Augusto"}, headers=auth)
    assert r.json()["data"] == "2026-11-02" and r.json()["argomento"] == "Augusto" and r.json()["tipo"] == "orale"
    r = client.patch(f"/api/eventi/{futuro['id']}", json={"data": "2026-13-01"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "data"
    r = client.post(f"/api/materie/{m['id']}/eventi", json={"tipo": "test", "data": "2026-11-01"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "tipo"
    assert client.delete(f"/api/eventi/{vecchio['id']}", headers=auth).status_code == 200
    assert client.delete(f"/api/eventi/{vecchio['id']}", headers=auth).status_code == 404


def test_esito_e_centratura(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    e = evento(client, auth, m["id"], "2026-10-28")
    l = lezione_pronta(client, auth, servizi, m["id"], evento_id=e["id"])
    ids = [d["id"] for d in l["domande"]]
    assert client.get(f"/api/materie/{m['id']}", headers=auth).json()["centratura"] is None
    r = client.post(f"/api/eventi/{e['id']}/esito", headers=auth, json={
        "domande_uscite": ids[:3], "altre_domande": "Chi era Seiano?\n\nCos'era il pretorio?\n", "voto": "7"})
    assert r.status_code == 200, r.text
    assert r.json() == {"previste_uscite": 3, "totale_vere": 5, "percentuale": 60}
    dopo = client.get("/api/eventi", headers=auth).json()[0]
    assert dopo["esito"]["voto"] == "7" and dopo["esito"]["domande_uscite"] == ids[:3]
    assert dopo["lezione_id"] == l["id"]
    materia = client.get(f"/api/materie/{m['id']}", headers=auth).json()
    assert materia["centratura"] == {"previste_uscite": 3, "uscite_totali": 5, "percentuale": 60}
    # L'esito fa aggiornare la scheda (e la scheda lo conta).
    assert client.get(f"/api/materie/{m['id']}/scheda", headers=auth).json()["stato"] == "in_aggiornamento"
    sfondo_tutto(servizi)
    s = client.get(f"/api/materie/{m['id']}/scheda", headers=auth).json()
    assert s["basata_su"]["esiti"] == 1
    # Un secondo esito sulla stessa verifica sostituisce il primo; una seconda verifica si somma.
    client.post(f"/api/eventi/{e['id']}/esito", headers=auth, json={"domande_uscite": ids[:1]})
    e2 = evento(client, auth, m["id"], "2026-10-29")
    client.post(f"/api/eventi/{e2['id']}/esito", headers=auth, json={"altre_domande": "Una\nDue\nTre"})
    materia = client.get(f"/api/materie/{m['id']}", headers=auth).json()
    assert materia["centratura"] == {"previste_uscite": 1, "uscite_totali": 4, "percentuale": 25}


def test_esito_con_domande_di_altre_lezioni(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    altra = lezione_pronta(client, auth, servizi, m["id"])
    e = evento(client, auth, m["id"], "2026-10-28")
    r = client.post(f"/api/eventi/{e['id']}/esito", json={"domande_uscite": [altra["domande"][0]["id"]]}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "domande_uscite"
    r = client.post(f"/api/eventi/{e['id']}/esito", json={"domande_uscite": ["31"]}, headers=auth)
    assert r.status_code == 422
    r = client.post(f"/api/eventi/{e['id']}/esito", json={}, headers=auth)
    assert r.json() == {"previste_uscite": 0, "totale_vere": 0, "percentuale": 0}


def test_oggi(client, auth, servizi, orologio):
    vuoto = client.get("/api/oggi", headers=auth).json()
    assert vuoto["oggi"] == "2026-10-27" and vuoto["prossima"] is None and vuoto["riprendi"] is None
    assert vuoto["da_raccontare"] == [] and vuoto["piu_avanti"] == [] and vuoto["modalita_prova"] is True
    assert vuoto["spesa"] == {"speso_eur": 0.0, "tetto_eur": 2.0, "percentuale": 0, "si_azzera_il": "2026-11-01",
                              "avviso": False}
    storia = crea_materia(client, auth)
    aggiungi_testo(client, auth, storia["id"])
    latino = crea_materia(client, auth, "Latino", "Bianchi", colore="viola")
    passato = evento(client, auth, storia["id"], "2026-10-20")
    lezione_pronta(client, auth, servizi, storia["id"], evento_id=passato["id"])
    evento(client, auth, latino["id"], "2026-10-15")  # passato ma senza lezione: non si racconta
    prossimo = evento(client, auth, storia["id"], "2026-10-29")
    in_prep = nuova_lezione(client, auth, storia["id"], evento_id=prossimo["id"])
    for giorno in ("2026-11-03", "2026-11-05", "2026-11-10", "2026-11-12", "2026-11-20"):
        evento(client, auth, latino["id"], giorno, argomento="Declinazioni")
    o = client.get("/api/oggi", headers=auth).json()
    assert o["prossima"]["evento"]["id"] == prossimo["id"]
    assert o["prossima"]["materia"] == {"id": storia["id"], "nome": "Storia", "colore": "ambra"}
    assert o["prossima"]["tra_giorni"] == 2
    assert o["prossima"]["lezione"]["id"] == in_prep["id"] and o["prossima"]["lezione"]["stato"] == "in_coda"
    assert len(o["piu_avanti"]) == 4 and o["piu_avanti"][0]["tra_giorni"] == 7
    assert o["piu_avanti"][0]["materia"]["colore"] == "viola"
    assert [x["evento"]["id"] for x in o["da_raccontare"]] == [passato["id"]]
    # Riprendi: l'ultima lezione ascoltata e non finita.
    prepara_tutto(servizi)
    client.put(f"/api/lezioni/{in_prep['id']}/ascolto", json={"posizione_s": 42}, headers=auth)
    o = client.get("/api/oggi", headers=auth).json()
    assert o["riprendi"]["id"] == in_prep["id"] and o["riprendi"]["ascolto"]["posizione_s"] == 42
    client.put(f"/api/lezioni/{in_prep['id']}/ascolto", json={"posizione_s": 50, "finita": True}, headers=auth)
    assert client.get("/api/oggi", headers=auth).json()["riprendi"] is None
    # Passati più di 30 giorni, non si racconta più.
    orologio.avanza(days=31)
    assert all(x["evento"]["id"] != passato["id"] for x in client.get("/api/oggi", headers=auth).json()["da_raccontare"])


def test_novita_dopo_id(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    nuova_lezione(client, auth, m["id"])
    nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    tutte = client.get("/api/novita?dopo_id=0", headers=auth).json()
    assert [n["tipo"] for n in tutte] == ["lezione_pronta", "lezione_pronta"]
    assert tutte[0]["testo"] == "La lezione di Storia è pronta"
    assert tutte[0]["quando"].endswith("+01:00")
    dopo = client.get(f"/api/novita?dopo_id={tutte[0]['id']}", headers=auth).json()
    assert [n["id"] for n in dopo] == [tutte[1]["id"]]
    assert client.get(f"/api/novita?dopo_id={tutte[1]['id']}", headers=auth).json() == []
    assert client.get("/api/novita?dopo_id=-1", headers=auth).status_code == 422
    assert client.get("/api/novita?dopo_id=abc", headers=auth).status_code == 422
