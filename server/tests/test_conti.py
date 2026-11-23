"""Esercizi con i numeri nella verifica di prova (contratto 21.1, richiesta di Andrea del 23/11/2026): numeri sempre nuovi,
soluzione calcolata dal programma, tabelle e grafici nelle domande, risultati nei campi, foto e file aggiunti."""

import io
from fractions import Fraction

import pytest
from PIL import Image
from pypdf import PdfReader

from aiuti import aggiungi_testo, crea_materia, sfondo_tutto
from app import calcolo, esercizi, simulata

pagamento = pytest.mark.pagamento

APPUNTI_FISICA = """Misure ed errori sperimentali.
L'errore assoluto medio si trova in due modi: semidispersione massima e deviazione standard.
Semidispersione massima: la misura più grande meno la misura più piccola, diviso 2.
Esempio: misure 1,63; 1,65; 1,64; 1,63; 1,66; 1,61 cm.
Δx = (1,66 - 1,61) / 2 cm = 0,05 / 2 = 0,025 cm (0,03).
Equazione di esempio: 3x + 5 = 2(x - 1)."""


def studio_tutto(servizi, massimo=40):
    for _ in range(massimo):
        if servizi.lavoro.passo_studio() != "fatto":
            return
    raise AssertionError("la coda non finisce")


# --------------------------------------------------------------------------- il programma che fa i conti

def test_la_semidispersione_della_verifica_di_andrea():
    """23/11/2026: 0/3 per "0,25" con la risposta attesa arrotondata a 0,03 che il testo non chiedeva."""
    v = calcolo.calcola(calcolo.leggi("(a - b) / 2", {"a", "b"}), {"a": calcolo.numero("1,66"), "b": calcolo.numero(1.61)})
    assert v == Fraction(1, 40) and calcolo.scrivi(v) == "0,025" and calcolo.scrivi(v, {"decimali": 2}) == "0,03"
    assert calcolo.controlla_valore("Semidispersione massima = 0,25", v, "cm", None) == "sbagliato"
    for giusto in ("0,025", "0,03 cm", "0,025 cm", "2,5·10^-2 cm", "0,25 mm", "1/40"):
        assert calcolo.controlla_valore(giusto, v, "cm", None) == "giusto", giusto
    # Arrotondamento chiesto: 0,03 è giusto, 0,025 torna ma non è arrotondato come chiesto.
    assert calcolo.controlla_valore("0,03 cm", v, "cm", {"decimali": 2}) == "giusto"
    assert calcolo.controlla_valore("0,025", v, "cm", {"decimali": 2}) == "non_arrotondato"
    assert calcolo.controlla_valore("", v, "cm", None) == "mancante"
    assert calcolo.controlla_valore("non lo so", v, "cm", None) == "da_leggere"


def test_equazioni_disequazioni_sistemi_espressioni():
    assert calcolo.risolvi_equazione("3x + 5 = 2(x - 1)").testo == "x = -7"
    assert calcolo.risolvi_equazione("x^2 - 5x + 6 = 0").testo == "x = 2 oppure x = 3"
    assert calcolo.risolvi_equazione("x² - 4x - 4 = 0").testo.startswith("x = 2 ± 2√2")
    assert "nessuna soluzione" in calcolo.risolvi_equazione("x^2 + 1 = 0").testo
    assert "indeterminata" in calcolo.risolvi_equazione("2(x + 1) = 2x + 2").testo
    with pytest.raises(calcolo.NonRisolvibile):
        calcolo.risolvi_equazione("x^3 = 8")
    with pytest.raises(calcolo.NonRisolvibile):
        calcolo.risolvi_equazione("1/x = 2")
    assert calcolo.risolvi_disequazione("-2x + 4 ≥ 0").testo == "x ≤ 2"
    assert calcolo.risolvi_disequazione("x^2 - 4 < 0").testo == "-2 < x < 2"
    assert calcolo.risolvi_sistema(["2x + y = 7", "x - y = 2"], ["x", "y"]) == ([3, 1], "x = 3; y = 1")
    assert calcolo.risolvi_sistema(["x + y = 2", "2x + 2y = 5"], ["x", "y"])[1] == "il sistema è impossibile"
    assert calcolo.equivalenti("x^2 - 9", "(x - 3)(x + 3)") and not calcolo.equivalenti("x^2 - 9", "(x - 3)^2")
    g = calcolo.risolvi_equazione("x^2 - 5x + 6 = 0")
    assert [calcolo.controlla_equazione(s, g) for s in ("x₁ = 2, x₂ = 3", "3 e 2", "x = 2", "impossibile")] == \
        ["giusto", "giusto", "sbagliato", "sbagliato"]
    d = calcolo.risolvi_disequazione("x^2 - 4 >= 0")
    assert [calcolo.controlla_disequazione(s, d) for s in ("x ≤ -2 oppure x ≥ 2", "x < -2 o x > 2", "-2 ≤ x ≤ 2")] == \
        ["giusto", "sbagliato", "sbagliato"]
    assert calcolo.controlla_espressione("(x+3)(x-3)", "x^2-9", "Scomposizione", "(x-3)(x+3)") == "giusto"
    assert calcolo.controlla_espressione("x^2-9", "x^2-9", "Scomposizione", "(x-3)(x+3)") == "sbagliato"


def test_l_esercizio_deve_tornare_con_il_suo_testo():
    testo = "Calcola la semidispersione massima delle misure 1,73 cm e 1,58 cm."
    visibili = esercizi.numeri_visibili(testo, None, None)
    valore = {"tipo": "valore", "dati": {"a": 1.73, "b": 1.58},
              "richieste": [{"cosa": "Semidispersione massima", "formula": "(a - b) / 2", "unita": "cm", "arrotonda": None}]}
    r = esercizi.risolvi({"calcolo": valore}, testo, visibili)
    assert r["risultati"] == ["Semidispersione massima"]
    assert r["soluzione"] == "Semidispersione massima: (1,73 - 1,58) / 2 = 0,075 cm."
    with pytest.raises(esercizi.EsercizioRotto, match="non è scritto"):
        esercizi.risolvi({"calcolo": {**valore, "dati": {"a": 1.74, "b": 1.58}}}, testo, visibili)
    with pytest.raises(esercizi.EsercizioRotto, match="non chiede di arrotondare"):
        esercizi.risolvi({"calcolo": {**valore, "richieste": [{**valore["richieste"][0], "arrotonda": {"decimali": 2}}]}}, testo, visibili)
    with pytest.raises(esercizi.EsercizioRotto, match="non è un dato"):
        esercizi.risolvi({"calcolo": {**valore, "richieste": [{**valore["richieste"][0], "formula": "(a - c) / 2"}]}}, testo, visibili)
    with pytest.raises(esercizi.EsercizioRotto, match="non è scritta uguale"):
        esercizi.risolvi({"calcolo": {"tipo": "equazione", "equazione": "2x = 6", "incognita": "x"}}, "Risolvi 2x = 8.", set())
    assert esercizi.stessi_numeri({"tipo": "valore", "dati": {"a": "1.66", "b": "1.61"}}, "", {Fraction(166, 100), Fraction(161, 100)})
    assert not esercizi.stessi_numeri({"tipo": "valore", "dati": {"a": "1.73", "b": "1.58"}}, "", {Fraction(166, 100)})
    assert esercizi.stessi_numeri({"tipo": "equazione", "equazione": "3x + 5 = 2(x - 1)"}, calcolo.compatta(APPUNTI_FISICA), set())


def test_tabella_e_grafico():
    assert esercizi.tabella({"colonne": ["x (cm)"], "valori": [[1.72], ["1,58"]]}) == \
        {"titolo": None, "colonne": ["x (cm)"], "valori": [["1,72"], ["1,58"]]}
    assert esercizi.tabella({"colonne": ["a", "b"], "valori": [["1"]]}) is None
    g = esercizi.grafico({"tipo": "punti", "asse_x": "t (s)", "asse_y": "s (m)", "funzioni": [{"formula": "x^2", "da": -2, "a": 2}]})
    assert g["tipo"] == "linee" and len(g["serie"][0]["punti"]) == esercizi.PUNTI_FUNZIONE and g["serie"][0]["punti"][0] == [-2, 4]
    b = esercizi.grafico({"tipo": "barre", "categorie": ["A", "B"], "serie": [{"nome": "voti", "valori": [6, 7.5]}]})
    assert b["serie"] == [{"nome": "voti", "valori": [6, 7.5]}]
    assert esercizi.grafico({"tipo": "barre", "categorie": ["A"], "serie": [{"valori": [1, 2]}]}) is None


# --------------------------------------------------------------------------- la verifica di prova di fisica

ESERCIZIO_COPIATO = {
    "tipo": "esercizio", "testo": "Calcola la semidispersione massima delle misure 1,66 cm e 1,61 cm.", "punti": 3,
    "risposta_attesa": "Si fa la differenza e si divide per 2.", "fonti": [],
    "calcolo": {"tipo": "valore", "dati": {"a": 1.66, "b": 1.61},
                "richieste": [{"cosa": "Semidispersione massima", "formula": "(a - b) / 2", "unita": "cm", "arrotonda": None}]}}
ESERCIZIO_NUOVO = {
    "tipo": "esercizio", "testo": "Calcola la semidispersione massima delle misure in tabella e arrotondala a due cifre decimali.",
    "punti": 3, "risposta_attesa": "Si sottrae la misura più piccola dalla più grande e si divide per 2.", "fonti": [],
    "tabella": {"titolo": None, "colonne": ["x (cm)"], "valori": [["1,73"], ["1,64"], ["1,58"]]},
    "calcolo": {"tipo": "valore", "dati": {"xmax": 1.73, "xmin": 1.58},
                "richieste": [{"cosa": "Semidispersione massima", "formula": "(xmax - xmin) / 2", "unita": "cm",
                               "arrotonda": {"decimali": 2}}]}}
EQUAZIONE = {"tipo": "esercizio", "testo": "Risolvi l'equazione 4x - 7 = 2x + 9.", "punti": 2,
             "risposta_attesa": "Si portano le x a sinistra e i numeri a destra.", "fonti": [],
             "calcolo": {"tipo": "equazione", "equazione": "4x - 7 = 2x + 9", "incognita": "x"}}
GRAFICO = {"tipo": "aperta", "testo": "Che cosa mostra il grafico?", "punti": 2, "fonti": [],
           "risposta_attesa": "Lo spazio cresce in modo proporzionale al tempo.",
           "grafico": {"tipo": "linee", "asse_x": "t (s)", "asse_y": "s (m)", "funzioni": [{"formula": "2x", "da": 0, "a": 5}]}}


def _foto() -> bytes:
    uscita = io.BytesIO()
    Image.new("RGB", (40, 30), "white").save(uscita, format="PNG")
    return uscita.getvalue()


@pagamento
def test_verifica_di_fisica_con_numeri_nuovi(client, auth, servizi, server):
    m = crea_materia(client, auth, "Fisica", "Bianchi")
    aggiungi_testo(client, auth, m["id"], APPUNTI_FISICA)
    sfondo_tutto(servizi)
    server.simulata = {"titolo": "Verifica di fisica", "domande": [ESERCIZIO_COPIATO, ESERCIZIO_NUOVO, EQUAZIONE, GRAFICO]}
    n = server.conta("/v1/chat/completions")
    r = client.post("/api/simulate", json={"materia_id": m["id"], "argomento": "Errori", "minuti": 30}, headers=auth)
    assert r.status_code == 201, r.text
    sid = r.json()["id"]
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{sid}", headers=auth).json()
    assert d["stato"] == "pronta", d
    chieste = [c["messages"][-1]["content"] for p, c in server.chiamate[n:] if p == "/v1/chat/completions"]
    assert any("stessi numeri dell'esercizio dei materiali" in x for x in chieste)
    # L'esercizio con gli stessi numeri dei materiali non c'è; gli altri hanno la loro forma.
    assert [q["forma"] for q in d["domande"]] == ["conti", "conti", "formule"]
    uno, due, tre = d["domande"]
    assert uno["risultati"] == ["Semidispersione massima"] and uno["tabella"]["valori"] == [["1,73"], ["1,64"], ["1,58"]]
    assert due["risultati"] == ["Soluzione"] and due["tipo"] == "esercizio"
    assert tre["grafico"]["tipo"] == "linee" and tre["grafico"]["serie"][0]["punti"][-1] == [5, 10] and "risultati" not in tre
    assert "calcolo" not in str(d["domande"]) and "risposta_attesa" not in str(d["domande"])
    assert d["risultati"] == {} and d["allegati"] == {}

    # Il foglio di carta: tabella, grafico e le righe dei risultati.
    testo_pdf = "".join(p.extract_text() for p in PdfReader(io.BytesIO(client.get(f"/api/simulate/{sid}/pdf", headers=auth).content)).pages)
    assert "Semidispersione massima:" in testo_pdf and "1,73" in testo_pdf and "Soluzione:" in testo_pdf

    # Risposte, risultati e file.
    r = client.patch(f"/api/simulate/{sid}", json={"risposte": {"1": "(1,73 - 1,58) / 2 = 0,075"},
                                                  "risultati": {"1": ["0,08 cm"], "2": ["x = 9"]}, "iniziata": True}, headers=auth)
    assert r.status_code == 200 and r.json()["risultati"] == {"1": ["0,08 cm"], "2": ["x = 9"]}
    assert client.patch(f"/api/simulate/{sid}", json={"risultati": {"1": ["x"] * 7}}, headers=auth).status_code == 422
    foto = _foto()
    r = client.post(f"/api/simulate/{sid}/allegati", data={"n": "2"}, files={"file": ("foglio.png", foto, "image/png")}, headers=auth)
    assert r.status_code == 201, r.text
    primo = r.json()
    assert primo["nome"] == "foglio.png" and primo["tipo"] == "foto"
    assert client.post(f"/api/simulate/{sid}/allegati", data={"n": "2"}, files={"file": ("a.txt", b"ciao", "text/plain")},
                       headers=auth).status_code == 422
    assert client.post(f"/api/simulate/{sid}/allegati", data={"n": "9"}, files={"file": ("b.png", foto, "image/png")},
                       headers=auth).status_code == 422
    altri = [client.post(f"/api/simulate/{sid}/allegati", data={"n": "2"}, files={"file": (f"f{i}.png", foto, "image/png")},
                         headers=auth) for i in range(4)]
    assert [x.status_code for x in altri] == [201, 201, 201, 409]
    for x in altri[:3]:
        assert client.delete(f"/api/simulate/{sid}/allegati/{x.json()['id']}", headers=auth).json() == {"ok": True}
    assert client.delete(f"/api/simulate/{sid}/allegati/nessuno", headers=auth).status_code == 404
    assert client.get(f"/api/simulate/{sid}", headers=auth).json()["allegati"] == {"2": [primo]}

    # La correzione: il programma controlla i risultati, il modello giudica il metodo, anche con il mezzo punto.
    server.correzione = {"domande": [
        {"n": 1, "esito": "giusta", "punti": 3, "commento": "Conto e arrotondamento giusti.", "mancava": []},
        {"n": 2, "esito": "parziale", "punti": 1.5, "commento": "Il metodo è giusto, ma 16 / 2 fa 8.", "mancava": []}]}
    n = server.conta("/v1/chat/completions")
    assert client.post(f"/api/simulate/{sid}/consegna", headers=auth).json()["stato"] == "in_correzione"
    assert client.delete(f"/api/simulate/{sid}/allegati/{primo['id']}", headers=auth).status_code == 409
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{sid}", headers=auth).json()
    e = d["esito"]
    assert d["stato"] == "corretta"
    assert [x["punti"] for x in e["domande"]] == [3, 1.5, 0] and e["punti"] == 4.5 and e["punti_totali"] == 7
    assert e["domande"][0]["controllo"] == [{"cosa": "Semidispersione massima", "scritto": "0,08 cm", "atteso": "0,08 cm", "esito": "giusto"}]
    assert e["domande"][1]["controllo"][0]["esito"] == "sbagliato" and e["domande"][1]["controllo"][0]["atteso"] == "x = 8"
    assert "controllo" not in e["domande"][2]
    assert "0,075 cm, che arrotondato fa 0,08 cm" in e["domande"][0]["risposta_attesa"]
    correzione = next(c["messages"][-1]["content"] for p, c in server.chiamate[n:]
                      if p == "/v1/chat/completions" and "Correggi ogni risposta confrontandola" in c["messages"][-1]["content"])
    assert "Controllo del programma sul risultato (sicuro)" in correzione and "giusto 0,08 cm" in correzione
    assert "Tabella: x (cm)" in correzione and "Testo letto dal file «foglio.png»" in correzione
    assert "Risultati scritti: Soluzione: x = 9" in correzione
    # Nel Ripasso lampo l'esercizio sbagliato arriva con i suoi dati.
    carte = client.get("/api/lampo", params={"materia_id": m["id"]}, headers=auth).json()
    assert carte["da_fare"] >= 1


@pagamento
def test_esercizio_senza_numeri_senza_soluzione_si_confronta_da_soli(client, auth, servizi, server):
    m = crea_materia(client, auth, "Latino")
    aggiungi_testo(client, auth, m["id"], "Esercizio: analizza la frase Puer rosam amat e riconosci i casi.", tipo="verifica")
    sfondo_tutto(servizi)
    server.simulata = {"titolo": "Prova", "domande": [{"tipo": "esercizio", "testo": "Analizza la frase Puer rosam amat",
                                                        "punti": 10, "risposta_attesa": "", "fonti": []}]}
    s = client.post("/api/simulate", json={"materia_id": m["id"], "argomento": "Casi", "minuti": 30}, headers=auth).json()
    studio_tutto(servizi)
    d = client.get(f"/api/simulate/{s['id']}", headers=auth).json()
    assert len(d["domande"]) == 1 and d["domande"][0]["forma"] == "testo"
    client.post(f"/api/simulate/{s['id']}/consegna", headers=auth)
    studio_tutto(servizi)
    e = client.get(f"/api/simulate/{s['id']}", headers=auth).json()["esito"]
    assert e["domande"][0]["esito"] == "non_corretta" and e["punti_totali"] == 0 and e["voto_stimato"] is None


def test_mezzo_punto():
    assert simulata._mezzi(1.4) == 1.5 and simulata._mezzi(2.0) == 2 and isinstance(simulata._mezzi(2.0), int)
    assert simulata._mezzi(0.2) == 0
