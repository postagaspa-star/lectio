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
    # "arrotonda" senza chiederlo nel testo: la frase la aggiunge il programma (prima l'esercizio si buttava).
    r = esercizi.risolvi({"calcolo": {**valore, "richieste": [{**valore["richieste"][0], "arrotonda": {"decimali": 2}}]}}, testo, visibili)
    assert r["arrotonda_da_dire"] == "Arrotonda il risultato a due cifre decimali."
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
        {"n": 1, "esito": "parziale", "punti": 2, "commento": "Mancano i passaggi.", "mancava": ["Passaggi"]},
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
    # Il risultato giusto nel campo vale tutti i punti anche se la correzione voleva più passaggi (24/11/2026).
    assert e["domande"][0]["esito"] == "giusta" and e["domande"][0]["commento"] == "Tutti i risultati sono giusti."
    assert e["domande"][0]["mancava"] == []
    assert "0,075 cm, che arrotondato fa 0,08 cm" in e["domande"][0]["risposta_attesa"]
    correzione = next(c["messages"][-1]["content"] for p, c in server.chiamate[n:]
                      if p == "/v1/chat/completions" and "Correggi ogni risposta confrontandola" in c["messages"][-1]["content"])
    assert "Controllo del programma sul risultato (sicuro)" in correzione and "giusto 0,08 cm" in correzione
    assert "Tabella: x (cm)" in correzione and "Testo letto dal file «foglio.png»" in correzione
    assert "Risultati scritti nei campi: Soluzione: x = 9" in correzione
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


def test_la_consegna_non_da_il_procedimento():
    """Segnalato da Andrea il 24/11/2026: «Calcola l'errore relativo Δx/x» dava già la formula."""
    assert esercizi.formule_nel_testo("Calcola l'errore relativo Δx/x.") == ["Δx/x"]
    assert esercizi.formule_nel_testo("Calcola la semidispersione con (xmax − xmin)/2.") == ["(xmax − xmin)/2"]
    assert esercizi.formule_nel_testo("Calcola la densità ρ = m/V del corpo.") == ["m/V"]
    # Le unità di misura non sono formule.
    assert esercizi.formule_nel_testo("Massa 4,8 kg. Esprimi il risultato in kg/m³. Una macchina va a 90 km/h.") == []
    assert esercizi.senza_formule("Un carrello percorre 12 m in 4 s. Calcola la velocità media v = Δs/Δt in m/s.") == \
        "Un carrello percorre 12 m in 4 s. Calcola la velocità media in m/s."
    assert esercizi.senza_formule("Calcola ρ = m/V.") is None  # senza la formula non resta niente da chiedere
    brani = [{"id": "B1", "materiale_id": 1, "tipo": "appunti", "titolo": "Errori", "pagina": None, "rango": 4,
              "testo": "L'errore relativo è il rapporto tra errore assoluto e misura."}]
    esercizio = {"tipo": "esercizio", "punti": 3, "risposta_attesa": "Si divide l'errore assoluto per la misura.", "fonti": ["B1"],
                 "testo": "Una misura è x = 2,40 cm con errore assoluto Δx = 0,06 cm. Calcola l'errore relativo Δx/x.",
                 "calcolo": {"tipo": "valore", "dati": {"x": 2.40, "dx": 0.06},
                             "richieste": [{"cosa": "Errore relativo", "formula": "dx / x", "unita": None, "arrotonda": None}]}}
    rotte = simulata.domande_rotte({"domande": [esercizio]}, brani, numeri=True)
    assert len(rotte) == 1 and "dà il procedimento" in rotte[0] and "«Δx/x»" in rotte[0]
    d = simulata.normalizza({"domande": [esercizio]}, brani, numeri=True)[0]
    assert d["testo"] == "Una misura è x = 2,40 cm con errore assoluto Δx = 0,06 cm. Calcola l'errore relativo."
    assert d["forma"] == "conti" and "0,025" in d["risposta_attesa"]


def test_formula_detta_con_le_parole_o_con_la_radice():
    """Le altre forme dello stesso errore: «usando la formula …» e la radice scritta per intero."""
    testo = ("Le misure di una massa sono in tabella. Calcola la media e la deviazione standard, usando la formula "
             "Δm = sqrt(somma degli scarti al quadrato / numero delle misure).")
    assert esercizi.formule_nel_testo(testo)
    assert esercizi.senza_formule(testo) == "Le misure di una massa sono in tabella. Calcola la media e la deviazione standard."


BRANI_FISICA = [
    {"id": "B1", "materiale_id": 1, "tipo": "verifica", "titolo": "Verifica", "pagina": None, "rango": 2,
     "testo": "1) Completa la seguente tabella. 2) Determina il risultato in notazione scientifica. 3) Un cubo ha "
              "massa 120 g e lato 4 cm: qual è la sua densità? Illustra il ragionamento."},
    {"id": "B2", "materiale_id": 2, "tipo": "appunti", "titolo": "Misure", "pagina": None, "rango": 4,
     "testo": "Una misura si scrive x ± Δx. Due misure sono compatibili se i loro intervalli hanno una parte in comune. "
              "L'errore relativo è il rapporto tra errore assoluto e misura."},
]


def _errore_relativo(x: str, dx: str) -> dict:
    return {"tipo": "esercizio", "punti": 3, "risposta_attesa": "Si divide l'errore assoluto per la misura.", "fonti": ["B2"],
            "testo": f"Una misura è x = {x} cm con errore assoluto Δx = {dx} cm. Calcola l'errore relativo.",
            "calcolo": {"tipo": "valore", "dati": {"x": float(x.replace(",", ".")), "dx": float(dx.replace(",", "."))},
                        "richieste": [{"cosa": "Errore relativo", "formula": "dx / x", "unita": None, "arrotonda": None}]}}


def _aperta(testo: str, tipo: str = "aperta") -> dict:
    return {"tipo": tipo, "punti": 3, "testo": testo, "risposta_attesa": "Come negli appunti.", "fonti": ["B2"]}


TEORIA = [_aperta("Come si rappresenta una misura con il suo errore assoluto?"),
          _aperta("Che cosa si intende per compatibilità tra misure?"),
          _aperta("Descrivi i passaggi per calcolare la deviazione standard."),
          _aperta("Spiega come si calcola la semidispersione massima di una serie di misure.")]
ESERCIZI = [_errore_relativo("3,20", "0,08"), _errore_relativo("5,60", "0,07"),
            _errore_relativo("6,40", "0,16"), _errore_relativo("2,50", "0,05")]


def test_niente_teoria_se_il_prof_non_la_fa():
    """24/11/2026, fisica: la verifica vera aveva solo esercizi e problemi, la prova quattro domande di teoria su otto."""
    scheda = {"tipi_domanda": [{"tipo": "esercizio", "quota": 0.67}, {"tipo": "aperta", "quota": 0.33}],
              "verifiche": {"struttura": "Una tabella da completare, esercizi di calcolo e problemi.",
                            "consegne": ["Completa la seguente tabella:"], "richieste": []}}
    assert simulata.teoria_esclusa(BRANI_FISICA, scheda)
    o = {"domande": [ESERCIZI[0], TEORIA[0], ESERCIZI[1], TEORIA[1], ESERCIZI[2], TEORIA[2], ESERCIZI[3], TEORIA[3]]}
    rotte = simulata.domande_rotte(o, BRANI_FISICA, numeri=True, scheda=scheda)
    assert [r.split()[2] for r in rotte] == ["2", "4", "6", "8"] and all("teoria" in r for r in rotte)
    domande = simulata.normalizza(o, BRANI_FISICA, numeri=True, scheda=scheda)
    assert [d["forma"] for d in domande] == ["conti"] * 4 and [d["n"] for d in domande] == [1, 2, 3, 4]
    # Senza sapere com'è una sua verifica non si toglie niente.
    assert not simulata.domande_rotte(o, BRANI_FISICA[1:], numeri=True, scheda=None)
    assert len(simulata.normalizza(o, BRANI_FISICA[1:], numeri=True)) == 8
    # Se nelle sue verifiche c'è teoria, la teoria resta.
    con_teoria = [dict(BRANI_FISICA[0], testo=BRANI_FISICA[0]["testo"] + " 4) Che cos'è la densità?")] + BRANI_FISICA[1:]
    assert not simulata.teoria_esclusa(con_teoria, scheda)


def test_nessun_tipo_piu_spesso_che_nelle_verifiche_del_prof():
    """Andrea, 24/11/2026: se il prof fa il 20% di definizioni e la prova il 40%, è lo stesso errore in un'altra forma."""
    scheda = {"tipi_domanda": [{"tipo": "aperta", "quota": 0.6}, {"tipo": "definizione", "quota": 0.2},
                               {"tipo": "date", "quota": 0.2}]}
    brani = [{"id": "B1", "materiale_id": 1, "tipo": "libro", "titolo": "Storia", "pagina": 1, "rango": 1,
              "testo": "Il feudalesimo è un sistema di rapporti tra signori e vassalli. Carlo Magno fu incoronato nell'800."}]
    definizioni = [_aperta("Che cos'è il feudalesimo?"), _aperta("Cosa si intende per vassallo?", "definizione"),
                   _aperta("Definisci il beneficio."), _aperta("Cosa significa immunità?")]
    aperte = [_aperta("Spiega perché i vassalli giuravano fedeltà."), _aperta("Racconta l'incoronazione di Carlo Magno."),
              _aperta("Quali erano i doveri del signore verso il vassallo?"), _aperta("Perché l'impero si divise?"),
              _aperta("Come vivevano i servi della gleba?"), _aperta("Che ruolo aveva la Chiesa?")]
    o = {"domande": definizioni + aperte}  # 4 definizioni su 10 = 40%: ne può avere al massimo 2
    rotte = simulata.domande_rotte(o, brani, scheda=scheda)
    assert [r.split()[2] for r in rotte] == ["3", "4"] and "il prof ne fa circa il 20%" in rotte[0]
    assert "4 su 10" in rotte[0] and "date 20%" in rotte[0]
    domande = simulata.normalizza(o, brani, scheda=scheda)
    assert len(domande) == 8 and [d["testo"] for d in domande[:2]] == ["Che cos'è il feudalesimo?", "Cosa si intende per vassallo?"]
    # Un tipo che il prof non usa non c'è.
    vf = {"tipo": "vero_falso", "punti": 1, "testo": "Carlo Magno fu incoronato nell'800.", "risposta_attesa": "Vero, nell'800."}
    rotte = simulata.domande_rotte({"domande": aperte[:3] + definizioni[:1] + [vf]}, brani, scheda=scheda)
    assert len(rotte) == 1 and rotte[0].startswith("la domanda 5 è di troppo") and "il prof non ne fa" in rotte[0]
    # Nelle proporzioni giuste non si tocca niente; senza «Tipi di domanda» nemmeno.
    date = [_aperta("In che anno fu incoronato Carlo Magno?", "date"), _aperta("Quando morì Carlo Magno?", "date")]
    assert not simulata.domande_rotte({"domande": definizioni[:2] + aperte + date}, brani, scheda=scheda)
    assert not simulata.domande_rotte(o, brani, scheda={})
    # Non si toglie mai più di metà della verifica.
    troppe = {"domande": definizioni + aperte[:1]}  # 3 definizioni di troppo su 5: se ne tolgono solo 2
    assert [d["testo"] for d in simulata.normalizza(troppe, brani, scheda=scheda)] == \
        ["Che cos'è il feudalesimo?", "Cosa si intende per vassallo?", "Spiega perché i vassalli giuravano fedeltà."]


def test_esercizi_con_i_conti_mai_di_troppo():
    """Il prof può chiamare «domande aperte» i suoi problemi: gli esercizi con i conti non si tolgono."""
    scheda = {"tipi_domanda": [{"tipo": "aperta", "quota": 0.7}, {"tipo": "esercizio", "quota": 0.3}]}
    assert not simulata.domande_rotte({"domande": ESERCIZI}, BRANI_FISICA[1:], numeri=True, scheda=scheda)


def test_risposta_attesa_lunga_solo_col_risultato():
    """Simulata 3 di fisica: la deviazione standard scritta per intero era illeggibile."""
    valori = ["8,24", "8,29", "8,27", "8,22", "8,28"]
    nomi = ["a", "b", "c", "d", "e"]
    media = "(a + b + c + d + e) / 5"
    formula = "sqrt((" + " + ".join(f"({n} - {media})^2" for n in nomi) + ") / 5)"
    d = {"tipo": "esercizio", "punti": 5, "risposta_attesa": "Scarti, quadrati, somma, divisione e radice.", "fonti": [],
         "testo": "Le misure di una massa sono nella tabella. Calcola la deviazione standard.",
         "tabella": {"titolo": None, "colonne": ["m (g)"], "valori": [[v] for v in valori]},
         "calcolo": {"tipo": "valore", "dati": {n: float(v.replace(",", ".")) for n, v in zip(nomi, valori)},
                     "richieste": [{"cosa": "Deviazione standard", "formula": formula, "unita": "g", "arrotonda": None}]}}
    voce = simulata.normalizza({"domande": [d]}, BRANI_FISICA, numeri=True)[0]
    assert voce["risposta_attesa"].startswith("Scarti, quadrati, somma, divisione e radice. Deviazione standard: 0,02")
    assert "√" not in voce["risposta_attesa"] and len(voce["risposta_attesa"]) < 120


def test_regole_nuove_nei_prompt():
    from app import prompt_studio
    voci = [{"n": 1, "punti": 3, "testo": "Come si scrive una misura?", "risposta_attesa": "x ± Δx", "risposta": "m ± Δm"}]
    correzione = prompt_studio.messaggi_correzione_simulata(voci, None)[1]["content"]
    assert "Giudica il significato, non le parole" in correzione and "m e Δm al posto di x e Δx" in correzione
    lettura = {"argomento": "Errori", "indicazioni": "", "materia": {"nome": "Fisica", "prof": None}, "evento": None,
               "scheda": None, "brani": BRANI_FISICA}
    verifica = prompt_studio.messaggi_simulata(lettura, 50, 8)[1]["content"]
    assert "Stesse proporzioni tra i tipi di domanda" in verifica and "solo se il prof le fa" in verifica


def test_esercizi_che_si_capiscono():
    """24/11/2026, simulata 4 di fisica (Andrea: "che esercizio è questo? la consegna non si capisce")."""
    tabella = {"titolo": None, "colonne": ["Misura", "x (cm)"], "valori": [["x1", "3,21"], ["x2", "3,25"], ["x3", "3,18"]]}
    d = {"tipo": "esercizio", "testo": "Le misure riportate in tabella sono espresse in cm. Calcola la media.", "tabella": tabella}
    assert "x1" in simulata.tabella_confusa(d, None)
    doppia = {**d, "testo": "Calcola la media delle misure 3,21 cm; 3,25 cm; 3,18 cm.",
              "tabella": {**tabella, "colonne": ["Prova", "x (cm)"], "valori": [["1", "3,21"], ["2", "3,25"], ["3", "3,18"]]}}
    assert "nel testo e nella tabella" in simulata.tabella_confusa(doppia, None)
    buona = {**doppia, "testo": "Con un righello misuri tre volte una matita. Calcola la media."}
    assert simulata.tabella_confusa(buona, None) is None
    completa = {**buona, "testo": "Completa la seguente tabella: calcola l'errore relativo."}
    assert "nei campi" in simulata.tabella_confusa(completa, {"tabella": None})
    # 2 / 98 = 1/49 senza chiedere di arrotondare: si chiede di cambiare i dati o di arrotondare.
    lungo = _errore_relativo("98", "2")
    rotte = simulata.domande_rotte({"domande": [lungo]}, BRANI_FISICA, numeri=True)
    assert len(rotte) == 1 and "risultato lungo" in rotte[0]
    assert not simulata.domande_rotte({"domande": [_errore_relativo("3,20", "0,08")]}, BRANI_FISICA, numeri=True)


def test_il_controllo_non_tocca_le_consegne():
    """24/11/2026: il controllo dei fatti aveva tolto «Stabilisci se le misure sono compatibili.» (un esercizio nuovo
    non è nei documenti) e la domanda 8 era rimasta solo «ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici.»."""
    from app import controllo
    o = {"domande": [{"tipo": "aperta", "testo": "ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici. Due gruppi misurano "
                      "un banco. Stabilisci se le misure sono compatibili.", "risposta_attesa": "Gli intervalli si toccano."}]}
    frasi = [f[3] for f in controllo._elenco_frasi(o)]
    assert frasi == ["Gli intervalli si toccano."]


def test_regole_per_esercizi_chiari_nel_prompt():
    from app import prompt_studio
    lettura = {"argomento": "Errori", "indicazioni": "", "materia": {"nome": "Fisica", "prof": None}, "evento": None,
               "scheda": {"verifiche": {"struttura": "Una tabella e tre problemi.", "contesto": "Problemi su un terreno."}},
               "brani": BRANI_FISICA}
    testo = prompt_studio.messaggi_simulata(lettura, 50, 8)[1]["content"]
    assert "Mai dati senza storia" in testo and "mai x1, x2" in testo and "non frazioni come" in testo
    assert "domande nello stesso contesto" in testo and "Contesto delle domande: Problemi su un terreno" in testo


def _con_arrotonda(d: dict, testo: str, regola: dict) -> dict:
    c = d["calcolo"]
    return {**d, "testo": testo, "calcolo": {**c, "richieste": [{**c["richieste"][0], "arrotonda": regola}]}}


def test_arrotondare_detto_nel_testo():
    """24/11/2026, prova di fisica: due esercizi su sei buttati perché "arrotonda" era nel calcolo ma il testo non lo
    chiedeva; e «arrotonda a due cifre» non diceva se significative o decimali."""
    base = _errore_relativo("2,36", "0,04")
    muto = _con_arrotonda(base, base["testo"], {"cifre": 2})
    assert not simulata.domande_rotte({"domande": [muto]}, BRANI_FISICA, numeri=True)
    d = simulata.normalizza({"domande": [muto]}, BRANI_FISICA, numeri=True)[0]
    assert d["testo"].endswith("Calcola l'errore relativo. Arrotonda il risultato a due cifre significative.")
    assert "0,017" in d["risposta_attesa"]
    vago = _con_arrotonda(base, base["testo"][:-1] + " e arrotonda il risultato a due cifre.", {"cifre": 2})
    d = simulata.normalizza({"domande": [vago]}, BRANI_FISICA, numeri=True)[0]
    assert d["testo"].endswith("arrotonda il risultato a due cifre significative.") and "Arrotonda il" not in d["testo"]
    vago = _con_arrotonda(base, base["testo"][:-1] + " e arrotonda a una cifra.", {"decimali": 1})
    assert simulata.normalizza({"domande": [vago]}, BRANI_FISICA, numeri=True)[0]["testo"].endswith("a una cifra decimale.")
    chiaro = _con_arrotonda(base, base["testo"][:-1] + " e arrotonda a tre cifre decimali.", {"decimali": 3})
    assert simulata.normalizza({"domande": [chiaro]}, BRANI_FISICA, numeri=True)[0]["testo"] == chiaro["testo"]
    # Più richieste: si dice quali arrotondare.
    pulite = [{"cosa": "Media", "arrotonda": None}, {"cosa": "Deviazione standard", "arrotonda": {"cifre": 2}}]
    assert esercizi.frase_arrotonda(pulite) == "Arrotonda a due cifre significative il risultato «Deviazione standard»."
    pulite = [{"cosa": "Errore relativo 1", "arrotonda": {"cifre": 2}}, {"cosa": "Errore relativo 2", "arrotonda": {"cifre": 2}}]
    assert esercizi.frase_arrotonda(pulite) == "Arrotonda i risultati a due cifre significative."
    assert esercizi.frase_arrotonda([{"cosa": "Media", "arrotonda": {"decimali": 0}}]) == "Arrotonda il risultato all'unità."


def test_altre_forme_di_teoria():
    """24/11/2026, prova di fisica: «Spiega quando si può dire che le due misure sono compatibili» era passata."""
    for testo in ["Spiega quando si può dire che le due misure sono compatibili.",
                  "Quando si dice che due misure sono compatibili?", "In che cosa consiste la semidispersione massima?"]:
        assert simulata._TEORIA.search(testo), testo
    assert not simulata._TEORIA.search("Spiega che rapporto c'è tra le densità dei due oggetti.")
    scheda = {"tipi_domanda": [{"tipo": "esercizio", "quota": 1.0}],
              "verifiche": {"struttura": "Esercizi e problemi.", "consegne": ["Completa la seguente tabella:"]}}
    o = {"domande": ESERCIZI[:3] + [_aperta("Due misure hanno i loro intervalli. Spiega quando si può dire che sono compatibili.")]}
    rotte = simulata.domande_rotte(o, BRANI_FISICA, numeri=True, scheda=scheda)
    assert len(rotte) == 1 and rotte[0].startswith("la domanda 4") and "teoria" in rotte[0]



def test_i_passaggi_non_scritti_non_tolgono_punti():
    """Andrea, 24/11/2026: «se nei passaggi non li scrivo tutti mi dà errore e mi toglie punti»."""
    tutti = [{"cosa": "Errore relativo", "scritto": "0,025", "atteso": "0,025", "esito": "giusto"}]
    solo_numeri = _errore_relativo("3,20", "0,08")
    assert simulata.tutti_i_punti(solo_numeri, tutti)
    assert not simulata.tutti_i_punti(solo_numeri, [{**tutti[0], "esito": "sbagliato"}])
    assert not simulata.tutti_i_punti(solo_numeri, [{**tutti[0], "esito": "non_arrotondato"}])
    assert not simulata.tutti_i_punti(solo_numeri, [])
    for altro in ["Determina gli estremi degli intervalli e stabilisci se le misure sono compatibili.",
                  "Calcola la densità e spiega quale oggetto galleggia.", "ILLUSTRA IL RAGIONAMENTO: calcola la distanza."]:
        assert not simulata.tutti_i_punti({**solo_numeri, "testo": altro}, tutti), altro
    from app import prompt_studio
    testo = prompt_studio.messaggi_correzione_simulata([], None)[1]["content"]
    assert "Se tutti i risultati sono giusti, il metodo è giusto" in testo and "non va mai in «mancava»" in testo

