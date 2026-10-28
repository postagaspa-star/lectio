"""La preparazione delle lezioni (contratto 9 e 9.1) in modalità prova: generatore finto,
voce finta e montaggio con il modulo wave al posto di ffmpeg."""

import json
from pathlib import Path

import pytest

from aiuti import (APPUNTI_STORIA, VERIFICA_GEMINI, aggiungi_testo, crea_materia, lezione_pronta, nuova_lezione,
                   prepara_tutto)
from app import db, misure
from app.audio import PAUSA_CAPITOLI, PAUSA_DOPO_DOMANDA


def test_lezione_intera_in_prova(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    aggiungi_testo(client, auth, m["id"], VERIFICA_GEMINI, tipo="verifica")
    creata = nuova_lezione(client, auth, m["id"], durata_min=20)
    assert creata["stato"] == "in_coda"
    assert creata["voce"] == "prova:espeak"
    assert creata["costo_eur"] == 0
    prepara_tutto(servizi)
    l = client.get(f"/api/lezioni/{creata['id']}", headers=auth).json()
    assert l["stato"] == "pronta", l
    assert l["avanzamento"]["fase"] is None
    assert l["errore"] is None
    assert "Lezione di prova" in l["nota"]
    # 20 minuti: 4 blocchi + il capitolo finale; 4 domande di blocco + 5 di interrogazione.
    assert len(l["capitoli"]) == misure.n_blocchi(20) + 1
    assert l["capitoli"][-1]["titolo"] == "Ripasso e interrogazione"
    domande = l["domande"]
    assert [d["tipo"] for d in domande].count("blocco") == 4
    assert [d["tipo"] for d in domande].count("interrogazione") == 5
    assert [d["n"] for d in domande] == list(range(1, 10))
    assert all(d["di"] == 9 for d in domande)
    assert all(d["mia_risposta"] is None for d in domande)
    # Tempi coerenti: dopo ogni domanda 2,5 s di silenzio, tutto dentro la durata.
    for d in domande:
        assert d["inizio_s"] < d["fine_domanda_s"] < d["inizio_risposta_s"] < d["fine_s"] <= l["durata_s"] + 1
        assert abs(d["inizio_risposta_s"] - d["fine_domanda_s"] - PAUSA_DOPO_DOMANDA) < 0.15
    capitoli = l["capitoli"]
    assert capitoli[0]["inizio_s"] == 0
    for a, b in zip(capitoli, capitoli[1:]):
        assert abs(b["inizio_s"] - a["fine_s"] - PAUSA_CAPITOLI) < 0.15
    # La domanda di blocco chiude il suo capitolo.
    blocco1 = [d for d in domande if d["capitolo"] == 1]
    assert blocco1 and abs(blocco1[-1]["fine_s"] - capitoli[0]["fine_s"]) < 0.11
    assert l["testo"][0]["capitolo"] == 1 and l["testo"][0]["paragrafi"]
    assert any(p.startswith("Domanda: ") for p in l["testo"][0]["paragrafi"])
    assert l["audio"]["byte"] > 0 and len(l["audio"]["sha256"]) == 64
    # Il testo finto usa davvero il materiale.
    tutto = " ".join(p for c in l["testo"] for p in c["paragrafi"])
    assert "senato" in tutto
    # I pezzi di voce si cancellano dopo il montaggio.
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])
    assert (cartella / "lezione.mp3").is_file()
    assert not (cartella / "pezzi").exists()
    novita = client.get("/api/novita?dopo_id=0", headers=auth).json()
    assert any(n["tipo"] == "lezione_pronta" and n["lezione_id"] == l["id"] for n in novita)


def test_segni_di_voce_e_pause_nel_copione(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])
    copione = json.loads((cartella / "copione.json").read_text(encoding="utf-8"))
    assert copione[0]["emozione"] == "warm"  # apertura
    assert all(len(p["testo"]) <= 1500 for p in copione)
    dopo_domanda = [b for a, b in zip(copione, copione[1:]) if a["ruolo"] == "domanda"]
    assert dopo_domanda and all(p["pausa_prima"] == PAUSA_DOPO_DOMANDA and p["ruolo"] == "risposta"
                                for p in dopo_domanda)
    assert any(p["emozione"] == "determined" for p in copione)  # interrogazione
    # Le domande sono pezzi a sé.
    assert all(p["testo"].startswith(("Una domanda per te.", "Prima", "Seconda", "Terza", "Ultima"))
               for p in copione if p["ruolo"] == "domanda")


def test_una_lezione_alla_volta_le_altre_in_coda(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    prima = nuova_lezione(client, auth, m["id"])
    seconda = nuova_lezione(client, auth, m["id"])
    stati = {}

    vero_esegui = servizi.lavoro.passo_lezione.__func__

    # Durante la preparazione della prima, la seconda resta in coda.
    from app import lezione as modulo

    originale = modulo.Preparazione._piano

    def spia(self):
        with db.aperta(servizi.settings.db_path) as conn:
            stati.update({r["id"]: r["stato"] for r in conn.execute("SELECT id, stato FROM lezioni")})
        avanzamento = client.get(f"/api/lezioni/{seconda['id']}", headers=auth).json()["avanzamento"]
        stati["frase"] = avanzamento["frase"]
        originale(self)

    modulo.Preparazione._piano = spia
    try:
        assert vero_esegui(servizi.lavoro) == "fatto"
    finally:
        modulo.Preparazione._piano = originale
    assert stati[prima["id"]] == "in_preparazione" and stati[seconda["id"]] == "in_coda"
    assert stati["frase"] == "In attesa: prima finisco l'altra lezione"
    assert client.get(f"/api/lezioni/{prima['id']}", headers=auth).json()["stato"] == "pronta"
    assert client.get(f"/api/lezioni/{seconda['id']}", headers=auth).json()["stato"] == "in_coda"
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{seconda['id']}", headers=auth).json()["stato"] == "pronta"
    salute = client.get("/api/salute", headers=auth).json()
    assert salute["coda"]["lezioni_in_preparazione"] == 0


def test_annulla_in_coda_e_durante(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    r = client.post(f"/api/lezioni/{l['id']}/annulla", headers=auth)
    assert r.status_code == 200 and r.json()["stato"] == "annullata"
    assert client.post(f"/api/lezioni/{l['id']}/annulla", headers=auth).status_code == 409
    assert servizi.lavoro.passo_lezione() == "vuoto"

    # Annullata a metà: la preparazione si ferma prima della chiamata successiva.
    l2 = nuova_lezione(client, auth, m["id"])
    from app import lezione as modulo

    originale = modulo.Preparazione._scrittura

    def annulla_e_scrivi(self):
        client.post(f"/api/lezioni/{l2['id']}/annulla", headers=auth)
        originale(self)

    modulo.Preparazione._scrittura = annulla_e_scrivi
    try:
        servizi.lavoro.passo_lezione()
    finally:
        modulo.Preparazione._scrittura = originale
    dopo = client.get(f"/api/lezioni/{l2['id']}", headers=auth).json()
    assert dopo["stato"] == "annullata" and dopo["errore"] is None


def test_riavvio_mette_in_errore_e_riprova_riparte(client, auth, servizi, app):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    from app import lezione as modulo

    originale = modulo.Preparazione._montaggio

    def si_spegne(self):
        raise KeyboardInterrupt  # come un riavvio: il processo muore a metà

    modulo.Preparazione._montaggio = si_spegne
    try:
        with pytest.raises(KeyboardInterrupt):
            servizi.lavoro.passo_lezione()
    finally:
        modulo.Preparazione._montaggio = originale
    pezzi_fatti = len(servizi.catalogo.voce_prova.chiamate)
    assert pezzi_fatti > 0
    db.ripristina_dopo_riavvio(servizi.settings.db_path)
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore"
    assert dopo["errore"] == "Interrotta da un riavvio: tocca Riprova"
    assert dopo["avanzamento"]["fase"] == "montaggio"
    novita = client.get("/api/novita", headers=auth).json()
    assert any(n["tipo"] == "lezione_errore" for n in novita)
    r = client.post(f"/api/lezioni/{l['id']}/riprova", headers=auth)
    assert r.status_code == 200 and r.json()["stato"] == "in_coda"
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"] == "pronta"
    # Nessun pezzo di voce rifatto: si è ripartiti dal montaggio.
    assert len(servizi.catalogo.voce_prova.chiamate) == pezzi_fatti
    assert client.post(f"/api/lezioni/{l['id']}/riprova", headers=auth).status_code == 409


def test_errore_imprevisto_lascia_la_lezione_in_errore(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    from app import lezione as modulo

    originale = modulo.Preparazione._piano
    modulo.Preparazione._piano = lambda self: 1 / 0
    try:
        servizi.lavoro.passo_lezione()
    finally:
        modulo.Preparazione._piano = originale
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore"
    assert "Tocca Riprova" in dopo["errore"]
    assert dopo["avanzamento"]["fase"] == "piano"


def test_cancella_lezione(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])
    assert cartella.exists()
    assert client.delete(f"/api/lezioni/{l['id']}", headers=auth).status_code == 200
    assert not cartella.exists()
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).status_code == 404


def test_elenco_e_filtro(client, auth, servizi):
    storia = crea_materia(client, auth)
    aggiungi_testo(client, auth, storia["id"])
    latino = crea_materia(client, auth, "Latino", "Bianchi")
    aggiungi_testo(client, auth, latino["id"], "Rosa rosae: la prima declinazione. Si studiano i casi.")
    a = nuova_lezione(client, auth, storia["id"])
    b = nuova_lezione(client, auth, latino["id"], argomento="La prima declinazione")
    tutte = client.get("/api/lezioni", headers=auth).json()
    assert [x["id"] for x in tutte] == [b["id"], a["id"]]
    solo = client.get(f"/api/lezioni?materia_id={latino['id']}", headers=auth).json()
    assert [x["id"] for x in solo] == [b["id"]]


def test_validazioni_creazione(client, auth):
    m = crea_materia(client, auth)
    # Senza materiale non si parte.
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "Augusto"}, headers=auth)
    assert r.status_code == 409 and r.json()["codice"] == "conflitto"
    aggiungi_testo(client, auth, m["id"])
    for durata in (10, 61):
        r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "A", "durata_min": durata},
                        headers=auth)
        assert r.status_code == 422 and r.json()["campo"] == "durata_min"
        assert "15 e 60" in r.json()["errore"]
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": ""}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "argomento"
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "A", "voce": "mistral:voice_482"},
                    headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "voce"  # in prova la voce vera non c'è
    r = client.post("/api/lezioni", json={"materia_id": 999, "argomento": "A"}, headers=auth)
    assert r.status_code == 404
    altra = crea_materia(client, auth, "Latino")
    e = client.post(f"/api/materie/{altra['id']}/eventi", json={"tipo": "scritta", "data": "2026-11-02"},
                    headers=auth).json()
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "A", "evento_id": e["id"]},
                    headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "evento_id"


def test_argomento_dall_evento_e_durata_predefinita(client, auth):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    e = client.post(f"/api/materie/{m['id']}/eventi",
                    json={"tipo": "scritta", "argomento": "Impero romano", "data": "2026-10-30"}, headers=auth).json()
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "evento_id": e["id"]}, headers=auth)
    assert r.status_code == 201
    assert r.json()["argomento"] == "Impero romano"
    assert r.json()["durata_min"] == 20
    assert r.json()["evento_id"] == e["id"]
    evento = client.get("/api/eventi", headers=auth).json()[0]
    assert evento["lezione_id"] == r.json()["id"]


def test_domande_saltabili(client, auth):
    """Contratto 15.4: per ogni lezione, dalle impostazioni se manca, modificabile dopo."""
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    assert client.get("/api/impostazioni", headers=auth).json()["domande_saltabili"] is False
    a = nuova_lezione(client, auth, m["id"])
    assert a["domande_saltabili"] is False
    client.patch("/api/impostazioni", json={"domande_saltabili": True}, headers=auth)
    b = nuova_lezione(client, auth, m["id"])
    assert b["domande_saltabili"] is True
    c = nuova_lezione(client, auth, m["id"], domande_saltabili=False)
    assert c["domande_saltabili"] is False
    r = client.patch(f"/api/lezioni/{c['id']}", json={"domande_saltabili": True}, headers=auth)
    assert r.status_code == 200 and r.json()["domande_saltabili"] is True
    r = client.patch(f"/api/lezioni/{c['id']}", json={"domande_saltabili": "sì"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "domande_saltabili"


def test_materiale_della_materia_e_restrizione(client, auth, servizi):
    """Contratto 15.1: di base tutti gli appunti e tutte le verifiche; con "materiali" solo quelli scelti."""
    m = crea_materia(client, auth)
    a1 = aggiungi_testo(client, auth, m["id"], APPUNTI_STORIA, titolo="Appunti Augusto")
    aggiungi_testo(client, auth, m["id"], "Carlo Magno fu incoronato nella notte di Natale dell'anno 800.",
                   titolo="Appunti Carlo Magno")
    v = aggiungi_testo(client, auth, m["id"], VERIFICA_GEMINI, tipo="verifica")
    l = nuova_lezione(client, auth, m["id"], materiali=[a1["id"], v["id"]])
    tutto = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)

    def usati(lezione_id):
        lettura = json.loads((Path(servizi.settings.dati_dir) / "lezioni" / str(lezione_id) / "lettura.json")
                             .read_text(encoding="utf-8"))
        return {b["materiale_id"] for b in lettura["brani"]}, lettura

    scelti, lettura = usati(l["id"])
    assert scelti == {a1["id"], v["id"]}  # l'altro appunto no
    assert any(b["id"].startswith("V") and b["tipo"] == "verifica" for b in lettura["brani"])
    assert len(usati(tutto["id"])[0]) == 3  # senza il campo: tutto il materiale
    altra = crea_materia(client, auth, "Latino")
    estraneo = aggiungi_testo(client, auth, altra["id"], "Rosa rosae.")
    r = client.post("/api/lezioni", json={"materia_id": m["id"], "argomento": "A", "materiali": [estraneo["id"]]},
                    headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "materiali"


def test_appunti_lunghi_scelti_sull_argomento(client, auth, servizi):
    """Contratto 15.1: oltre 60.000 caratteri di appunti il piano sceglie prima i pezzi sull'argomento."""
    m = crea_materia(client, auth)
    riempitivo = ("Le piante fanno la fotosintesi con la luce del sole e producono ossigeno. " * 14 + "\n\n") * 70
    aggiungi_testo(client, auth, m["id"], riempitivo, titolo="Scienze")
    aggiungi_testo(client, auth, m["id"], APPUNTI_STORIA, titolo="Augusto")
    l = nuova_lezione(client, auth, m["id"], argomento="Augusto e il senato nel principato")
    prepara_tutto(servizi)
    cartella = Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])
    lettura = json.loads((cartella / "lettura.json").read_text(encoding="utf-8"))
    assert lettura["caratteri_appunti"] > 60_000
    scelti = set(json.loads((cartella / "selezione.json").read_text(encoding="utf-8"))["scelti"])
    per_id = {b["id"]: b for b in lettura["brani"]}
    assert sum(len(per_id[i]["testo"]) for i in scelti) <= 72_000
    augusto = {b["id"] for b in lettura["brani"] if b["titolo"] == "Augusto"}
    assert augusto <= scelti  # i pezzi sull'argomento ci sono
    dettaglio = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dettaglio["stato"] == "pronta"
    assert "parti che riguardano l'argomento" in dettaglio["nota"]


def test_ascolto_vince_il_piu_recente(client, auth, servizi, orologio):
    l = lezione_pronta(client, auth, servizi)
    url = f"/api/lezioni/{l['id']}/ascolto"
    r = client.put(url, json={"posizione_s": 100, "finita": False, "aggiornato_il": "2026-10-27T09:59:00+01:00"},
                   headers=auth)
    assert r.status_code == 200 and r.json()["posizione_s"] == 100
    # Un dispositivo rimasto senza rete manda una posizione più vecchia: si ignora.
    r = client.put(url, json={"posizione_s": 30, "finita": False, "aggiornato_il": "2026-10-27T09:50:00+01:00"},
                   headers=auth)
    assert r.json()["posizione_s"] == 100
    r = client.put(url, json={"posizione_s": 140.25, "finita": False, "aggiornato_il": "2026-10-27T08:59:30Z"},
                   headers=auth)
    assert r.json()["posizione_s"] == 140.2 or r.json()["posizione_s"] == 140.3
    # Senza ora del dispositivo vale l'ora del server; un'ora nel futuro si conta come adesso.
    orologio.avanza(minutes=5)
    r = client.put(url, json={"posizione_s": 200, "finita": True, "aggiornato_il": "2030-01-01T00:00:00+01:00"},
                   headers=auth)
    assert r.json()["finita"] is True
    r = client.put(url, json={"posizione_s": 210, "finita": False}, headers=auth)
    assert r.json()["posizione_s"] == 210
    r = client.put(url, json={"posizione_s": 1, "aggiornato_il": "ieri"}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "aggiornato_il"
    r = client.put(url, json={"posizione_s": -1}, headers=auth)
    assert r.status_code == 422 and r.json()["campo"] == "posizione_s"
    dettaglio = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dettaglio["ascolto"]["posizione_s"] == 210
    assert dettaglio["ascolto"]["aggiornato_il"].endswith("+01:00")


def test_audio_con_range_ed_etag(client, auth, servizi):
    l = lezione_pronta(client, auth, servizi)
    url = f"/api/lezioni/{l['id']}/audio"
    r = client.get(url, headers=auth)
    assert r.status_code == 200
    assert r.headers["content-type"] == "audio/mpeg"
    assert r.headers["etag"] == f'"{l["audio"]["sha256"]}"'
    assert r.headers["accept-ranges"] == "bytes"
    assert len(r.content) == l["audio"]["byte"]
    parte = client.get(url, headers={**auth, "Range": "bytes=0-99"})
    assert parte.status_code == 206 and len(parte.content) == 100
    assert parte.headers["content-range"].startswith("bytes 0-99/")
    uguale = client.get(url, headers={**auth, "If-None-Match": r.headers["etag"]})
    assert uguale.status_code == 304
    assert client.get(url).status_code == 401
    m = crea_materia(client, auth, "Latino")
    aggiungi_testo(client, auth, m["id"], "Rosa rosae: la prima declinazione.")
    in_coda = nuova_lezione(client, auth, m["id"], argomento="Declinazioni")
    r = client.get(f"/api/lezioni/{in_coda['id']}/audio", headers=auth)
    assert r.status_code == 404 and r.json()["codice"] == "non_trovato"


def test_errore_di_voce_mancante_e_chiaro(client, auth, servizi):
    """Senza espeak-ng (per esempio sul PC) la voce di prova si ferma con una frase chiara."""
    from app.voci import VoceProva

    servizi.catalogo.voce_prova = VoceProva(programma="programma-che-non-esiste")
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    dopo = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()
    assert dopo["stato"] == "errore"
    assert "espeak-ng" in dopo["errore"]
    assert dopo["avanzamento"]["fase"] == "voce"


def test_cancellata_durante_la_preparazione_non_lascia_file(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"])
    from app import lezione as modulo

    originale = modulo.Preparazione._voce

    def cancella_e_continua(self):
        client.delete(f"/api/lezioni/{l['id']}", headers=auth)
        originale(self)

    modulo.Preparazione._voce = cancella_e_continua
    try:
        assert servizi.lavoro.passo_lezione() == "fatto"
    finally:
        modulo.Preparazione._voce = originale
    assert not (Path(servizi.settings.dati_dir) / "lezioni" / str(l["id"])).exists()
    assert servizi.catalogo.voce_prova.chiamate == []  # nessun pezzo fatto dopo la cancellazione
