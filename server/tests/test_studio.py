"""Memoria sui documenti, controllo, schemi, riassunti, ripasso lampo, verifica simulata, firma (contratto 17-26)."""

import io
from pathlib import Path

import pytest
from pypdf import PdfReader

from aiuti import APPUNTI_STORIA, VERIFICA_GEMINI, aggiungi_testo, carica_file, crea_materia, nuova_lezione, prepara_tutto, sfondo_tutto
from app import db, finto, memoria, schema_ripasso, stile
from app.firma import FIRMA

pagamento = pytest.mark.pagamento


def studio_tutto(servizi, massimo=40):
    for _ in range(massimo):
        if servizi.lavoro.passo_studio() != "fatto":
            return
    raise AssertionError("la coda non finisce")


def materia_pronta(client, auth, servizi):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    sfondo_tutto(servizi)
    return m


def crea_schema(client, auth, materia_id, **altro):
    corpo = {"materia_id": materia_id, "argomento": "Augusto e il principato", "forma": "capitoli", "livello": "completo", **altro}
    r = client.post("/api/schemi", json=corpo, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


def schema_pronto(client, auth, servizi, **altro):
    m = materia_pronta(client, auth, servizi)
    s = crea_schema(client, auth, m["id"], **altro)
    studio_tutto(servizi)
    d = client.get(f"/api/schemi/{s['id']}", headers=auth).json()
    assert d["stato"] == "pronto", d
    return m, d


def firmato_su_ogni_pagina(contenuto: bytes) -> None:
    lettore = PdfReader(io.BytesIO(contenuto))
    assert lettore.pages
    for pagina in lettore.pages:
        assert FIRMA in pagina.extract_text()
        flusso = pagina.get_contents().get_data().decode("latin-1")
        assert "1 1 1 rg" in flusso  # scritta in bianco


# --------------------------------------------------------------------------- memoria


def test_priorita_libro_verifica_interrogazione_appunti(client, auth, servizi):
    m = crea_materia(client, auth)
    testo = "Augusto governò Roma e il senato restò di facciata. " * 6
    for tipo in ("appunti", "interrogazione", "verifica", "libro"):
        aggiungi_testo(client, auth, m["id"], testo + tipo, tipo=tipo)
    sfondo_tutto(servizi)
    with db.aperta(servizi.settings.db_path) as conn:
        memoria.assicura(conn, m["id"], servizi.settings.dati_dir)
        brani, _ = memoria.brani_di(conn, m["id"])
    assert [b["tipo"] for b in brani] == ["libro", "verifica", "interrogazione", "appunti"]
    scelti = memoria.scegli(servizi.ctx, "Augusto e il senato", brani, 100_000, materia_id=m["id"])
    assert scelti[0]["tipo"] == "libro" and {b["tipo"] for b in scelti} == {"libro", "verifica", "interrogazione", "appunti"}
    assert [b["rango"] for b in scelti] == sorted(b["rango"] for b in scelti)


def test_nelle_verifiche_non_si_usa_la_risposta_dello_studente():
    fatti = memoria.testo_per_fatti(VERIFICA_GEMINI, "verifica")
    assert "Che cos'è il principato?" in fatti and "Il governo di Augusto" not in fatti


@pagamento
def test_ricerca_con_i_vettori_e_senza(client, auth, servizi, server):
    m = materia_pronta(client, auth, servizi)
    with db.aperta(servizi.settings.db_path) as conn:
        brani, _ = memoria.brani_di(conn, m["id"])
    assert memoria.scegli(servizi.ctx, "Augusto", brani, 5000, materia_id=m["id"])
    assert server.conta("/v1/embeddings") >= 1
    server.vettori_rotti = True  # senza vettori resta la ricerca per parole
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE pezzi SET vettore = NULL, modello = NULL")
    assert memoria.scegli(servizi.ctx, "Augusto", brani, 5000, materia_id=m["id"])


def test_lingua_che_non_sembra_automatica():
    assert stile.problemi("È importante sottolineare che non è solo una data, ma una vera svolta.")
    assert not stile.problemi("Augusto prese il potere nel 27 avanti Cristo.")
    assert stile.formule_in_testo(r"\frac{x+3}{x-1}") == "(x+3)/(x-1)"
    assert "—" not in stile.ripulisci("Augusto — il primo imperatore — governò.")
    assert stile.formule_in_testo("$x^2-2x+1$") == "x²-2x+1" and stile.formule_in_testo("x^{-1}") == "x⁻¹"


# --------------------------------------------------------------------------- scheda del prof


@pagamento
def test_i_numeri_della_scheda_li_conta_lectio(client, auth, servizi, server):
    m = crea_materia(client, auth, "Matematica", "Bianchi")
    verifica = "TESTO: Risolvi l'equazione 3x + 2 = 8\nTESTO: Semplifica l'espressione (x+1)(x-1)"
    server.scheda_forzata = {"domande": [
        {"doc": "V1", "testo": "Risolvi l'equazione 3x + 2 = 8", "tipo": "definizione", "argomento": "Equazioni"},
        {"doc": "V1", "testo": "Semplifica l'espressione (x+1)(x-1)", "tipo": "esercizio", "argomento": "Equazioni"},
        {"doc": "V1", "testo": "Definisci il teorema di Pitagora", "tipo": "definizione", "argomento": "Inventato"}],
        "cosa_corregge": [{"frase": "Inventata", "prova": "parole che non ci sono da nessuna parte"}], "cosa_premia": [], "lunghezza_risposte": None, "consigli": []}
    aggiungi_testo(client, auth, m["id"], verifica, tipo="verifica")
    sfondo_tutto(servizi)
    s = client.get(f"/api/materie/{m['id']}/scheda", headers=auth).json()
    assert s["tipi_domanda"] == [{"tipo": "esercizio", "quota": 1.0}]
    assert [(a["nome"], a["volte"]) for a in s["argomenti"]] == [("Equazioni", 1)]
    assert s["cosa_corregge"] == []


# --------------------------------------------------------------------------- schemi


def test_doppioni_e_lettere_legate():
    pagina = ("Nel 1122 il concordato di Worms chiuse la lotta per le investiture tra il papa e l'imperatore: "
              "al papa la nomina spirituale dei vescovi, all'imperatore quella temporale, con i beni e i poteri. ") * 2
    brani = [{"id": "B1", "testo": pagina}, {"id": "B2", "testo": pagina.replace("Nel 1122", "Nell'anno 1122")},
             {"id": "B3", "testo": "Breve nota."}, {"id": "B4", "testo": "La prima crociata partì nel 1096 dopo il "
              "concilio di Clermont, dove Urbano II promise l'indulgenza a chi partiva per Gerusalemme con la croce."}]
    assert [b["id"] for b in memoria.senza_doppioni(brani)] == ["B1", "B3", "B4"]
    assert memoria.testo_pulito("Veriﬁca: la ﬁne") == "Verifica: la fine"


def test_piano_mette_ogni_punto_in_una_parte():
    punti = [{"testo": "Concordato di Worms (1122)", "fonti": ["B1"]}, {"testo": "Dictatus papae (1075)", "fonti": ["B1"]},
             {"testo": "Prima crociata (1096)", "fonti": ["B2"]}, {"testo": "Presa di Gerusalemme (1099)", "fonti": ["B2"]},
             {"testo": "Urbano II a Clermont", "fonti": ["B2"]}]
    lettura = {"argomento": "Medioevo", "brani": [{"id": "B1", "testo": "concordato worms dictatus papae"},
                                                  {"id": "B2", "testo": "crociata gerusalemme urbano clermont"}]}
    o = {"titolo": "Medioevo", "sezioni": [
        {"titolo": "Papato e impero", "punti": ["P2", "P1", "P1"]},
        {"titolo": "Vuota", "punti": ["P99"]},
        {"titolo": "Le crociate", "punti": ["P3", "4"]}],
        "immagini": [{"sezione": 2, "soggetto": "Gerusalemme", "ricerca": "Gerusalemme"}]}
    piano = schema_ripasso.normalizza_piano(o, lettura, punti, "capitoli", 10)
    assert [s["titolo"] for s in piano["sezioni"]] == ["Papato e impero", "Le crociate"]
    assert piano["sezioni"][0]["concetti"] == ["Dictatus papae (1075)", "Concordato di Worms (1122)"]
    # P5 dimenticato dal piano: va nella parte che usa lo stesso documento.
    assert piano["sezioni"][1]["concetti"] == ["Prima crociata (1096)", "Presa di Gerusalemme (1099)", "Urbano II a Clermont"]
    assert piano["sezioni"][1]["fonti"] == ["B2"] and piano["immagini"][0]["sezione"] == 2


def test_argomenti_dallo_stesso_soggetto():
    s = schema_ripasso.soggetto
    assert s("Canossa (1077): Enrico IV si umilia") == s("Canossa: tre giorni scalzo") == "canossa"
    assert s("L'eresia: negazione delle verità") == "eresia"
    assert s("Causa: la fame") == "" and s("1077: Canossa") == "" and s("Senza due punti") == ""
    punti = [{"testo": "Canossa (1077): Enrico IV si umilia", "fonti": ["B1"]}, {"testo": "Worms (1122): accordo", "fonti": ["B1"]},
             {"testo": "Canossa: tre giorni scalzo", "fonti": ["B2"]}]
    a = schema_ripasso.raggruppa(punti)
    assert [x["indici"] for x in a] == [[0, 2], [1]]
    assert a[0]["testo"] == "Canossa (1077): Enrico IV si umilia; tre giorni scalzo" and a[0]["fonti"] == ["B1", "B2"]


def test_piano_tiene_insieme_gli_argomenti_e_toglie_le_parti_minuscole():
    punti = [{"testo": "Dictatus papae (1075): primato del papa", "fonti": ["B1"]},
             {"testo": "Canossa (1077): Enrico IV si umilia", "fonti": ["B1"]},
             {"testo": "Dictatus papae: superiorità sul potere imperiale", "fonti": ["B2"]}]
    punti += [{"testo": f"Papato {j}: fatto numero {j}", "fonti": ["B1"]} for j in range(6)]
    punti += [{"testo": f"Crociata {j}: fatto numero {j}", "fonti": ["B3"]} for j in range(8)]
    punti += [{"testo": "Ordini cavallereschi: templari e ospedalieri", "fonti": ["B3"]}]
    lettura = {"argomento": "Medioevo", "brani": [{"id": "B1", "testo": "papato"}, {"id": "B2", "testo": "dictatus"},
                                                  {"id": "B3", "testo": "crociate"}]}
    o = {"titolo": "Medioevo", "sezioni": [
        {"titolo": "Papato e impero", "punti": [f"P{i}" for i in range(1, 9)]},
        {"titolo": "Le crociate", "punti": [f"P{i}" for i in range(9, 17)]},
        {"titolo": "Ordini cavallereschi", "punti": ["P17"]}],
        "immagini": [{"sezione": 3, "soggetto": "Templari", "ricerca": "templari"}]}
    piano = schema_ripasso.normalizza_piano(o, lettura, punti, "capitoli", 10)
    # I due punti sul Dictatus papae sono un argomento solo: stessa parte, uno dopo l'altro.
    assert [s["titolo"] for s in piano["sezioni"]] == ["Papato e impero", "Le crociate"]
    assert piano["sezioni"][0]["concetti"][:3] == ["Dictatus papae (1075): primato del papa",
                                                   "Dictatus papae: superiorità sul potere imperiale",
                                                   "Canossa (1077): Enrico IV si umilia"]
    assert set(piano["sezioni"][0]["fonti"]) == {"B1", "B2"}
    # La parte da un punto solo in fondo va dove ci sono gli stessi documenti, con la sua foto.
    assert piano["sezioni"][1]["concetti"][-1] == "Ordini cavallereschi: templari e ospedalieri"
    assert piano["immagini"][0]["sezione"] == 2
    assert sum(len(s["concetti"]) for s in piano["sezioni"]) == len(punti)
    # Chi rilegge il piano vede le parti già sistemate, con i numeri degli argomenti.
    testo = schema_ripasso.piano_in_chiaro(o, punti, "capitoli", 10)
    assert testo.splitlines()[:3] == ["S1. Papato e impero", "   P1. Dictatus papae (1075): primato del papa; superiorità sul potere imperiale",
                                      "   P2. Canossa (1077): Enrico IV si umilia"]
    assert "S3." not in testo and "P17. Ordini cavallereschi" in testo.split("S2.")[1]
    assert schema_ripasso._numero_punto("P12. Dictatus papae", 20) == 11


def test_piano_corretto_da_chi_lo_rilegge():
    punti = [{"testo": f"{tema} {j}: fatto", "fonti": [fonte]} for tema, fonte, quanti in
             (("Economia", "B1", 5), ("Papato", "B2", 5), ("Crociate", "B3", 5), ("Chiesa", "B2", 3)) for j in range(quanti)]
    lettura = {"argomento": "Medioevo", "brani": [{"id": b, "testo": "x"} for b in ("B1", "B2", "B3")]}
    o = {"titolo": "Medioevo", "sezioni": [
        {"titolo": "Economia", "punti": ["P1", "P2", "P3", "P4", "P5", "P11"]},
        {"titolo": "Crociate", "punti": ["P12", "P13", "P14", "P15"]},
        {"titolo": "Papato", "punti": [f"P{i}" for i in range(6, 11)]},
        {"titolo": "Chiesa e società", "punti": ["P16", "P17", "P18"]}],
        "immagini": [{"sezione": 4, "soggetto": "Chiesa", "ricerca": "chiesa"}]}
    correzioni = {"sposta": [{"punto": "P11", "sezione": "S2"}, {"punto": "P99", "sezione": "S1"}],
                  "unisci": [{"sezione": "S4", "in": "S3"}], "metti_dopo": [{"sezione": "S3", "dopo": "S1"}, {"sezione": "S9", "dopo": "S1"}]}
    piano = schema_ripasso.normalizza_piano(o, lettura, punti, "capitoli", 10, correzioni)
    assert [s["titolo"] for s in piano["sezioni"]] == ["Economia", "Papato", "Crociate"]
    assert piano["sezioni"][1]["concetti"][-3:] == ["Chiesa 0: fatto", "Chiesa 1: fatto", "Chiesa 2: fatto"]
    assert piano["sezioni"][2]["concetti"][-1] == "Crociate 0: fatto" and len(piano["sezioni"][0]["concetti"]) == 5
    assert piano["immagini"][0]["sezione"] == 2  # la foto segue la sua parte unita all'altra
    assert sum(len(s["concetti"]) for s in piano["sezioni"]) == len(punti)
    assert schema_ripasso.valida_correzioni(correzioni) == [] and schema_ripasso.valida_correzioni({"sposta": "P3"})


def test_copertura_senza_spendere():
    testo = schema_ripasso.radici("Nel 1122 il Concordato di Worms chiuse la lotta tra papato e impero.")
    assert schema_ripasso.coperto("Concordato di Worms (1122)", testo)
    assert not schema_ripasso.coperto("Concordato di Worms (1123)", testo)  # la data deve esserci
    assert not schema_ripasso.coperto("Dictatus papae di Gregorio VII", testo)
    assert not schema_ripasso.coperto("Worms", testo)  # troppo poco per dirlo: decide il modello


def test_punti_senza_immagini_e_uniti():
    o = {"punti": [{"testo": "Immagine del papa in trono", "fonti": ["B1"]}, {"testo": "Il papato teocratico", "fonti": ["B1", "X9"]},
                   "Fotosintesi clorofilliana"]}
    assert schema_ripasso.normalizza_mappa(o, {"B1"}) == [{"testo": "Il papato teocratico", "fonti": ["B1"]},
                                                         {"testo": "Fotosintesi clorofilliana", "fonti": []}]
    uniti = schema_ripasso.unisci_punti([[{"testo": "Il papato teocratico", "fonti": ["B1"]}],
                                         [{"testo": "il Papato, teocratico.", "fonti": ["B7"]}]])
    assert uniti == [{"testo": "Il papato teocratico", "fonti": ["B1", "B7"]}]


def test_schema_usa_il_piano_riletto(client, auth, servizi, monkeypatch):
    visti = {}

    def rivisto(self, d):
        visti["titoli"] = [stile.ripulisci(schema_ripasso._corto(s["titolo"])) for s in d["piano"]["sezioni"]]
        return {"sposta": [], "unisci": [], "metti_dopo": [{"sezione": "S1", "dopo": "S2"}]}

    monkeypatch.setattr(finto.TestoFinto, "_schema_piano_rivisto", rivisto)
    m = materia_pronta(client, auth, servizi)
    s = crea_schema(client, auth, m["id"])
    studio_tutto(servizi)
    d = client.get(f"/api/schemi/{s['id']}", headers=auth).json()
    titoli = [x["titolo"] for x in d["contenuto"]["sezioni"]]
    attesi = visti["titoli"]
    assert len(attesi) > 1 and titoli[:2] == [attesi[1], attesi[0]]


def test_quante_immagini():
    q = schema_ripasso.quante_immagini
    assert (q("essenziale", ""), q("completo", None), q("approfondito", "")) == (2, 3, 5)
    assert q("completo", "ne voglio 12 immagini") == 12 and q("completo", "metti 100 foto") == 40
    assert q("completo", "tante immagini per favore") == 15 and q("completo", "senza foto") == 0


def test_schema_in_prova(client, auth, servizi):
    m = materia_pronta(client, auth, servizi)
    stima = client.post("/api/schemi/stima", json={"materia_id": m["id"], "argomento": "Augusto"}, headers=auth)
    assert stima.status_code == 200 and stima.json()["costo_eur"] == 0 and stima.json()["immagini"] == 3
    s = crea_schema(client, auth, m["id"])
    assert s["stato"] == "in_coda"
    studio_tutto(servizi)
    d = client.get(f"/api/schemi/{s['id']}", headers=auth).json()
    assert d["stato"] == "pronto" and d["contenuto"]["sezioni"] and d["contenuto"]["avvisi"] == ["In modalità prova non cerco le foto."]
    assert "contenuto" in d and client.get("/api/schemi", params={"materia_id": m["id"]}, headers=auth).json()[0]["contenuto"] is None
    assert any(n["tipo"] == "schema_pronto" for n in client.get("/api/novita", headers=auth).json())
    r = client.get(f"/api/schemi/{s['id']}/pdf", headers=auth)
    assert r.status_code == 200 and r.headers["content-type"] == "application/pdf"
    firmato_su_ogni_pagina(r.content)


def test_schema_senza_materiale_e_annulla_e_cancella(client, auth, servizi):
    m = crea_materia(client, auth)
    r = client.post("/api/schemi", json={"materia_id": m["id"], "argomento": "Niente"}, headers=auth)
    assert r.status_code == 409
    aggiungi_testo(client, auth, m["id"])
    s = crea_schema(client, auth, m["id"])
    assert client.post(f"/api/schemi/{s['id']}/annulla", headers=auth).json()["stato"] == "annullato"
    assert client.post(f"/api/schemi/{s['id']}/annulla", headers=auth).status_code == 409
    studio_tutto(servizi)
    assert client.delete(f"/api/schemi/{s['id']}", headers=auth).json() == {"ok": True}
    assert client.get(f"/api/schemi/{s['id']}", headers=auth).status_code == 404


@pagamento
def test_schema_vero_con_foto_controllate(client, auth, servizi, server):
    m, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert [s["titolo"] for s in sezioni][:2] == ["Il principato", "Il senato"] and sezioni[-1]["titolo"] == "Quadro d'insieme"
    tipi = [b["tipo"] for s in sezioni for b in s["blocchi"]]
    assert "sconosciuto" not in tipi and tipi.count("immagine") == 1  # solo il soggetto nominato nei documenti
    assert d["contenuto"]["avvisi"] == ["Ho trovato solo 1 foto sicura su 3."]
    foto = next(b for s in sezioni for b in s["blocchi"] if b["tipo"] == "immagine")
    assert "CC BY-SA 4.0" in foto["credito"] and d["carte"] == 2
    assert foto["didascalia"] == "Augusto"  # solo il nome: una data qui non passerebbe dal controllo
    r = client.get(f"/api/schemi/{d['id']}/immagini/1", headers=auth)
    assert r.status_code == 200 and r.headers["content-type"] == "image/jpeg"
    assert client.post(f"/api/schemi/{d['id']}/immagini/1/cambia", headers=auth).status_code == 200
    firmato_su_ogni_pagina(client.get(f"/api/schemi/{d['id']}/pdf", headers=auth).content)
    dopo = client.delete(f"/api/schemi/{d['id']}/immagini/1", headers=auth).json()
    assert "immagine" not in [b["tipo"] for s in dopo["contenuto"]["sezioni"] for b in s["blocchi"]]
    usate = [x["model"] for p, x in server.chiamate if p == "/v1/chat/completions"]
    assert "mistral-medium-latest" in usate and "mistral-small-latest" in usate


def test_ricerca_foto_troppo_lunga_si_accorcia():
    from app.immagini import ricerche

    assert ricerche("Discobolo Mirone statua marmo bronzo", "Discobolo di Mirone") == [
        "Discobolo Mirone statua marmo bronzo", "Discobolo di Mirone", "Discobolo Mirone statua", "Discobolo Mirone"]
    assert ricerche("Augusto", "Augusto") == ["Augusto"]


def test_foto_trasparente_ha_il_fondo_bianco():
    import io

    from PIL import Image

    from app.immagini import in_jpeg

    grezza = io.BytesIO()
    Image.new("RGBA", (40, 40), (0, 0, 0, 0)).save(grezza, "PNG")
    with Image.open(io.BytesIO(in_jpeg(grezza.getvalue(), 40))) as im:
        assert min(im.getpixel((20, 20))) > 240


@pagamento
def test_foto_che_non_mostra_il_soggetto_non_entra(client, auth, servizi, server):
    server.foto_buona = False
    _, d = schema_pronto(client, auth, servizi)
    assert "immagine" not in [b["tipo"] for s in d["contenuto"]["sezioni"] for b in s["blocchi"]]
    assert d["contenuto"]["avvisi"] == ["Non ho trovato foto sicure per questo schema."]


@pagamento
def test_piano_senza_foto_si_chiede_apposta(client, auth, servizi, server):
    server.piano_senza_foto = True
    _, d = schema_pronto(client, auth, servizi)
    assert "immagine" in [b["tipo"] for s in d["contenuto"]["sezioni"] for b in s["blocchi"]]
    assert any("Elenca le immagini" in c["messages"][1]["content"] for p, c in server.chiamate if p == "/v1/chat/completions" and isinstance(c["messages"][1]["content"], str))


@pagamento
def test_frase_fuori_dai_documenti_si_toglie(client, auth, servizi, server):
    fuori = [{"n": 1, "motivo": "non c'è"}]
    server.fuori_coda = [fuori, fuori]  # segnalata, riscritta, segnalata di nuovo: si toglie
    _, d = schema_pronto(client, auth, servizi)
    prima = d["contenuto"]["sezioni"][0]["blocchi"]
    assert all("tenne il potere vero" not in str(b.get("testo")) for b in prima)
    assert "tenne il potere vero" in str(d["contenuto"]["sezioni"][1]["blocchi"])


@pagamento
def test_punto_mancante_si_aggiunge_nella_sua_parte(client, auth, servizi, server):
    server.mancanti = ["Le legioni a Teutoburgo"]  # il piano l'ha dimenticato: va nella parte dei punti vicini
    _, d = schema_pronto(client, auth, servizi)
    sezioni = d["contenuto"]["sezioni"]
    assert "Altri punti da sapere" not in [s["titolo"] for s in sezioni]
    assert "Le legioni furono sconfitte a Teutoburgo." in str(sezioni[1]["blocchi"])  # in fondo alla sua parte
    assert "Teutoburgo" not in str(sezioni[0]["blocchi"])
    assert d["contenuto"]["copertura"] == {"concetti": 3, "coperti": 3, "mancanti": []}
    assert d["carte"] == 3  # una per parte e una per l'aggiunta
    chat = [c["messages"][1]["content"] for p, c in server.chiamate if p == "/v1/chat/completions"
            and isinstance(c["messages"][1]["content"], str)]
    copertura = next(c for c in chat if "Qui sotto ci sono i concetti" in c)
    assert "C1. Le legioni a Teutoburgo" in copertura
    assert "C2." not in copertura  # gli altri due sono coperti dalle parole: per loro non si paga il controllo


@pagamento
def test_schema_legge_tutto_il_materiale_e_ogni_punto_va_in_una_parte(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    chat = [c for p, c in server.chiamate if p == "/v1/chat/completions" and isinstance(c["messages"][1]["content"], str)]
    mappa = [c for c in chat if "segna TUTTI i punti da sapere" in c["messages"][1]["content"]]
    piano = next(c for c in chat if "Prepara il PIANO di uno schema di ripasso" in c["messages"][1]["content"])
    assert mappa and all(c["model"] == "mistral-small-latest" for c in mappa)
    assert "P1. Il principato di Augusto" in piano["messages"][1]["content"]
    assert "<materiale id=" not in piano["messages"][1]["content"]  # il piano lavora sui punti, non su un pezzo del materiale


@pagamento
def test_riassunto_tagliato_si_richiede_con_piu_spazio(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    n = server.conta("/v1/chat/completions")
    r = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "misto"}, headers=auth).json()
    server.tronca = 1
    studio_tutto(servizi)
    x = client.get(f"/api/riassunti/{r['id']}", headers=auth).json()
    assert x["stato"] == "pronto" and x["contenuto"]["parti"]
    scritti = [c for p, c in server.chiamate[n:] if p == "/v1/chat/completions"
               and "Scrivi il riassunto di questo schema" in c["messages"][1]["content"]]
    assert len(scritti) == 2 and scritti[1]["max_tokens"] == 2 * scritti[0]["max_tokens"]
    assert len(scritti[1]["messages"]) == len(scritti[0]["messages"])  # la stessa richiesta, non "correggiti"


@pagamento
def test_schema_fermato_riprende_senza_ripagare(client, auth, servizi, server):
    m = materia_pronta(client, auth, servizi)
    s = crea_schema(client, auth, m["id"])
    server.guasti["/v1/chat/completions"] = [401]
    studio_tutto(servizi)
    d = client.get(f"/api/schemi/{s['id']}", headers=auth).json()
    assert d["stato"] == "errore" and d["errore"]
    assert any(n["tipo"] == "schema_errore" for n in client.get("/api/novita", headers=auth).json())
    assert client.post(f"/api/schemi/{s['id']}/riprova", headers=auth).json()["stato"] == "in_coda"
    studio_tutto(servizi)
    assert client.get(f"/api/schemi/{s['id']}", headers=auth).json()["stato"] == "pronto"


# --------------------------------------------------------------------------- riassunti


@pytest.mark.parametrize("forma", ["punti", "discorsivo", "misto"])
def test_riassunto_nelle_tre_forme(client, auth, servizi, forma):
    _, d = schema_pronto(client, auth, servizi)
    assert d["riassunti"]["fatti"] == [] and d["riassunti"]["stima_eur"] == 0
    r = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": forma}, headers=auth)
    assert r.status_code == 201 and r.json()["stato"] == "in_coda" and r.json()["frase"] == "Scrivo il riassunto"
    studio_tutto(servizi)
    x = client.get(f"/api/riassunti/{r.json()['id']}", headers=auth).json()
    assert x["stato"] == "pronto" and x["parole"] > 0 and x["nota"]
    parti = x["contenuto"]["parti"]
    assert parti
    if forma == "punti":
        assert all(p["punti"] and not p["paragrafi"] for p in parti)
    elif forma == "discorsivo":
        assert all(p["paragrafi"] and not p["punti"] for p in parti)
    else:
        assert any(p["paragrafi"] for p in parti)
    p = client.get(f"/api/riassunti/{x['id']}/pdf", headers=auth)
    assert p.status_code == 200 and "attachment" in p.headers["content-disposition"]
    firmato_su_ogni_pagina(p.content)
    fatti = client.get(f"/api/schemi/{d['id']}", headers=auth).json()["riassunti"]["fatti"]
    assert fatti == [{"id": x["id"], "forma": forma, "stato": "pronto"}]
    assert any(n["tipo"] == "riassunto_pronto" for n in client.get("/api/novita", headers=auth).json())


def test_riassunto_uno_per_forma_e_rifarlo(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    a = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "misto"}, headers=auth).json()
    b = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "misto"}, headers=auth).json()
    assert a["id"] == b["id"]
    assert len(client.get(f"/api/schemi/{d['id']}/riassunti", headers=auth).json()) == 1
    assert client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "altra"}, headers=auth).status_code == 422
    assert client.delete(f"/api/riassunti/{a['id']}", headers=auth).json() == {"ok": True}
    assert client.get(f"/api/riassunti/{a['id']}", headers=auth).status_code == 404
    assert client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "misto"}, headers=auth).status_code == 201


def test_riassunto_solo_da_schema_pronto(client, auth, servizi):
    m = materia_pronta(client, auth, servizi)
    s = crea_schema(client, auth, m["id"])
    assert client.post(f"/api/schemi/{s['id']}/riassunti", json={"forma": "punti"}, headers=auth).status_code == 409
    assert client.get(f"/api/schemi/{s['id']}", headers=auth).json()["riassunti"] is None


@pagamento
def test_riassunto_vero_controllato_sullo_schema(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    assert d["riassunti"]["stima_eur"] > 0
    n = server.conta("/v1/chat/completions")
    r = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "punti"}, headers=auth).json()
    server.fuori_coda = [[{"n": 1, "motivo": "non c'è"}], [{"n": 1, "motivo": "non c'è"}]]
    studio_tutto(servizi)
    x = client.get(f"/api/riassunti/{r['id']}", headers=auth).json()
    assert x["stato"] == "pronto" and x["nota"] is None
    assert x["contenuto"]["parti"][0]["punti"] == ["Il senato restò di facciata."]
    assert server.conta("/v1/chat/completions") == n + 4  # scritto, controllato, riscritto, ricontrollato
    chat = [d for p, d in server.chiamate if p == "/v1/chat/completions"]
    assert any("Scrivi il riassunto di questo schema" in c["messages"][1]["content"] and "Forma: PER PUNTI" in c["messages"][1]["content"] for c in chat[n:])
    with db.aperta(servizi.settings.db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM spese WHERE riferimento = ?", (f"riassunto:{r['id']}",)).fetchone()[0] == 4


@pagamento
def test_riassunto_fermato_si_riprova_e_tetto(client, auth, servizi, server):
    _, d = schema_pronto(client, auth, servizi)
    r = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "discorsivo"}, headers=auth).json()
    server.guasti["/v1/chat/completions"] = [401]
    studio_tutto(servizi)
    x = client.get(f"/api/riassunti/{r['id']}", headers=auth).json()
    assert x["stato"] == "errore" and x["errore"]
    assert client.post(f"/api/riassunti/{r['id']}/riprova", headers=auth).json()["stato"] == "in_coda"
    studio_tutto(servizi)
    assert client.get(f"/api/riassunti/{r['id']}", headers=auth).json()["stato"] == "pronto"
    with db.aperta(servizi.settings.db_path) as conn:
        db.salva_impostazione(conn, "tetto_eur", 0.0)
    t = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "punti"}, headers=auth)
    assert t.status_code == 402 and "tetto" in t.json()["errore"]


def test_riavvio_ferma_i_riassunti_a_meta(client, auth, servizi):
    _, d = schema_pronto(client, auth, servizi)
    r = client.post(f"/api/schemi/{d['id']}/riassunti", json={"forma": "punti"}, headers=auth).json()
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("UPDATE riassunti SET stato = 'in_preparazione' WHERE id = ?", (r["id"],))
    db.ripristina_dopo_riavvio(servizi.settings.db_path)
    assert client.get(f"/api/riassunti/{r['id']}", headers=auth).json()["stato"] == "errore"


# --------------------------------------------------------------------------- ripasso lampo


@pagamento
def test_ripasso_lampo(client, auth, servizi):
    m, d = schema_pronto(client, auth, servizi)
    o = client.get("/api/lampo", params={"materia_id": m["id"]}, headers=auth).json()
    assert o["da_fare"] == 2 and len(o["carte"]) == 2 and o["carte"][0]["scatola"] == 1
    carta = o["carte"][0]
    assert client.post(f"/api/lampo/{carta['id']}", json={"esito": "boh"}, headers=auth).status_code == 422
    r = client.post(f"/api/lampo/{carta['id']}", json={"esito": "sapevo"}, headers=auth).json()
    assert r["scatola"] == 2 and r["da_fare"] == 1
    assert client.get("/api/oggi", headers=auth).json()["lampo"]["da_fare"] == 1
    assert client.get(f"/api/materie/{m['id']}", headers=auth).json()["conteggi"]["lampo"] == 1
    assert client.delete(f"/api/lampo/{carta['id']}", headers=auth).json() == {"ok": True}
    assert client.post(f"/api/lampo/{carta['id']}", json={"esito": "sapevo"}, headers=auth).status_code == 404


def test_risposta_sbagliata_in_lezione_diventa_carta(client, auth, servizi):
    m = materia_pronta(client, auth, servizi)
    l = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    domanda = client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["domande"][0]
    r = client.post(f"/api/lezioni/{l['id']}/domande/{domanda['id']}/risposta", json={"testo": "non lo so proprio"}, headers=auth)
    assert r.status_code == 201, r.text
    if r.json()["esito"] in ("parziale", "sbagliata"):
        assert client.get("/api/lampo", params={"materia_id": m["id"]}, headers=auth).json()["da_fare"] == 1


# --------------------------------------------------------------------------- verifica simulata


def _simulata(client, auth, materia_id, **altro):
    r = client.post("/api/simulate", json={"materia_id": materia_id, "argomento": "Augusto", "minuti": 30, **altro}, headers=auth)
    assert r.status_code == 201, r.text
    return r.json()


@pagamento
def test_verifica_simulata_completa(client, auth, servizi, server):
    m = materia_pronta(client, auth, servizi)
    assert client.post("/api/simulate/stima", json={"materia_id": m["id"], "argomento": "Augusto", "minuti": 30}, headers=auth).json()["costo_eur"] > 0
    s = _simulata(client, auth, m["id"])
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert d["stato"] == "pronta" and d["nota"]
    assert [q["n"] for q in d["domande"]] == [1, 2]  # l'esercizio inventato non passa
    assert "risposta_attesa" not in str(d["domande"]) and "Il governo di Augusto" not in str(d)
    firmato_su_ogni_pagina(client.get(f"/api/simulate/{s['id']}/pdf", headers=auth).content)
    client.patch(f"/api/simulate/{s['id']}", json={"risposte": {"1": "Il governo"}, "iniziata": True}, headers=auth)
    assert client.get(f"/api/simulate/{s['id']}", headers=auth).json()["stato"] == "in_corso"
    assert client.post(f"/api/simulate/{s['id']}/consegna", headers=auth).json()["stato"] == "in_correzione"
    assert client.post(f"/api/simulate/{s['id']}/consegna", headers=auth).status_code == 409
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    e = d["esito"]
    assert d["stato"] == "corretta" and (e["punti"], e["punti_totali"], e["soglia"]) == (30, 100, 60) and e["voto_stimato"] == 3.0
    assert [x["esito"] for x in e["domande"]] == ["parziale", "sbagliata"] and e["da_rifare"] == 2
    assert e["domande"][1]["commento"] == "Qui non hai risposto."
    elenco = client.get("/api/simulate", params={"materia_id": m["id"]}, headers=auth).json()[0]
    assert "domande" not in elenco and set(elenco["esito"]) == {"punti", "punti_totali", "voto_stimato", "soglia", "da_rifare"}
    assert client.get("/api/lampo", params={"materia_id": m["id"]}, headers=auth).json()["da_fare"] == 2


@pagamento
def test_esercizio_senza_soluzione_si_confronta_da_soli(client, auth, servizi, server):
    m = crea_materia(client, auth, "Matematica")
    aggiungi_testo(client, auth, m["id"], "Esercizio: Risolvi 7x + 3 = 24 e controlla il risultato.", tipo="verifica")
    sfondo_tutto(servizi)
    server.simulata = {"titolo": "Prova", "domande": [{"tipo": "esercizio", "testo": "Risolvi 7x + 3 = 24", "punti": 10, "risposta_attesa": "", "fonti": []}]}
    s = _simulata(client, auth, m["id"])
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert d["nota"] is None and len(d["domande"]) == 1
    client.post(f"/api/simulate/{s['id']}/consegna", headers=auth)
    studio_tutto(servizi)
    e = client.get(f"/api/simulate/{s['id']}", headers=auth).json()["esito"]
    assert e["domande"][0]["esito"] == "non_corretta" and e["punti_totali"] == 0 and e["voto_stimato"] is None


@pagamento
def test_correzione_fermata_riparte_dalla_correzione(client, auth, servizi, server):
    m = materia_pronta(client, auth, servizi)
    s = _simulata(client, auth, m["id"])
    studio_tutto(servizi)
    client.patch(f"/api/simulate/{s['id']}", json={"risposte": {"1": "Il governo"}}, headers=auth)
    client.post(f"/api/simulate/{s['id']}/consegna", headers=auth)
    server.guasti["/v1/chat/completions"] = [401]
    studio_tutto(servizi)
    assert client.get(f"/api/simulate/{s['id']}", headers=auth).json()["stato"] == "errore"
    assert client.post(f"/api/simulate/{s['id']}/riprova", headers=auth).json()["stato"] == "in_correzione"
    studio_tutto(servizi)
    assert client.get(f"/api/simulate/{s['id']}", headers=auth).json()["stato"] == "corretta"


# --------------------------------------------------------------------------- non ho capito


@pagamento
def test_non_ho_capito(client, auth, servizi, server):
    m = materia_pronta(client, auth, servizi)
    r = client.post("/api/chiarimenti", json={"materia_id": m["id"], "passaggio": "Augusto e il senato", "domanda": "cosa vuol dire?"}, headers=auth)
    assert r.status_code == 200, r.text
    o = r.json()
    assert o["trovato"] is True and o["risposta"].startswith("Vuol dire") and o["fonti"] and o["costo_eur"] > 0
    server.chiarimento = {"trovato": False, "risposta": None, "fonti": [], "cosa_manca": "la data della morte"}
    o = client.post("/api/chiarimenti", json={"materia_id": m["id"], "domanda": "quando morì?"}, headers=auth).json()
    assert o["trovato"] is False and "Nei tuoi documenti questo non c'è. Manca: la data della morte." in o["risposta"]
    assert len(client.get("/api/chiarimenti", params={"materia_id": m["id"]}, headers=auth).json()) == 2
    assert client.post("/api/chiarimenti", json={"materia_id": m["id"]}, headers=auth).status_code == 422
    with db.aperta(servizi.settings.db_path) as conn:
        db.salva_impostazione(conn, "tetto_eur", 0.0)
    assert client.post("/api/chiarimenti", json={"materia_id": m["id"], "domanda": "e poi?"}, headers=auth).status_code == 402


def test_non_ho_capito_da_una_lezione(client, auth, servizi):
    m = materia_pronta(client, auth, servizi)
    l = nuova_lezione(client, auth, m["id"])
    prepara_tutto(servizi)
    r = client.post("/api/chiarimenti", json={"materia_id": m["id"], "lezione_id": l["id"], "posizione_s": 5.0}, headers=auth)
    assert r.status_code == 200, r.text
    assert r.json()["passaggio"]


# --------------------------------------------------------------------------- piccole regole


def test_pdf_finto_che_dentro_e_testo(client, auth, servizi):
    m = crea_materia(client, auth)
    r = carica_file(client, auth, m["id"], "storia.pdf", ("# Augusto\n\n" + APPUNTI_STORIA).encode("utf-8"), tipo="appunti")
    assert r.status_code == 201, r.text
    sfondo_tutto(servizi)
    d = client.get(f"/api/materiale/{r.json()['id']}", headers=auth).json()
    assert d["stato"] == "pronto" and "Augusto" in d["testo"]


def test_sedici_colori(client, auth):
    for i, colore in enumerate(("giada", "senape", "orchidea", "prugna", "cacao", "lavagna", "salvia", "lavanda")):
        assert crea_materia(client, auth, f"Materia {i}", colore=colore)["colore"] == colore
    assert client.post("/api/materie", json={"nome": "X", "colore": "fucsia"}, headers=auth).status_code == 422


def test_audio_firmato(monkeypatch, tmp_path: Path):
    from app import audio

    visti: list = []

    class Fatto:
        returncode, stdout, stderr = 0, b"", b""

    monkeypatch.setattr(audio.subprocess, "run", lambda args, **k: (visti.extend(args), Fatto())[1])
    audio.Montatore().codifica(tmp_path / "a.wav", tmp_path / "a.mp3")
    assert f"artist={FIRMA}" in visti and f"comment={FIRMA}" in visti
