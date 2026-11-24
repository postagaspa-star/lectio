"""Il modulo delle verifiche nella scheda del prof (contratto 5.1, richiesta di Andrea del 24/11/2026) e i testi da
leggere della verifica di prova (21.2)."""

import io
import json

import pytest
from pypdf import PdfReader

from aiuti import aggiungi_testo, crea_materia, sfondo_tutto
from app import pdf, prompt, prompt_studio, simulata
from app import scheda as schede

pagamento = pytest.mark.pagamento

VERIFICA = """Verifica di Fisica
1) Completa la seguente tabella: [6 punti]
0,0036 cm = ... m
2) ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici. Quanti cubetti da 2 cm servono per riempire una scatola da 10 cm? [4 punti]
0,5 con imprecisione
TOT 22 / 30 pt. (sufficienza 18 pt.)"""

INTERROGAZIONE = """Prof: Allora, la scuola pitagorica?
Studente: Era una comunità religiosa.
Prof: Ti ricordi perché per i pitagorici il numero è il principio?"""

MODULO_AI = {
    "domande": [{"doc": "V1", "testo": "Completa la seguente tabella:", "tipo": "esercizio", "argomento": "Equivalenze"}],
    "verifiche": {
        "struttura": {"frase": "Una tabella di equivalenze, poi un problema da ragionare.", "prova": "Completa la seguente tabella"},
        "consegne": ["ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici.", "Rispondi in dieci righe."],
        "punti": {"frase": "Punti accanto a ogni esercizio.", "totale": 30, "sufficienza": 18, "prova": "TOT 22 / 30 pt."},
        "regole_punti": [{"frase": "Mezzo punto in meno per un'imprecisione", "prova": "0,5 con imprecisione"},
                         {"frase": "Toglie un punto per l'unità", "prova": "manca l'unità di misura"}],
        "difficolta": {"frase": "Molto difficile", "prova": "un esercizio che non c'è"},
        "approfondimento": {"frase": "Vuole il ragionamento.", "prova": "motiva i passaggi logici"},
        "cosa_da": None,
        "richieste": [{"frase": "Motivare i passaggi", "prova": "ILLUSTRA IL RAGIONAMENTO"}],
    },
    "interrogazioni": {
        "inizio": {"frase": "Parte con una domanda larga.", "prova": "Allora, la scuola pitagorica?"},
        "andamento": {"frase": "Dà le tabelle da completare.", "prova": "Completa la seguente tabella"},
        "esempi": ["Ti ricordi perché per i pitagorici il numero è il principio?", "Che cos'è l'archè?"],
    },
}


def scheda(client, auth, materia_id):
    return client.get(f"/api/materie/{materia_id}/scheda", headers=auth).json()


def test_scheda_vuota_ha_il_modulo_vuoto(client, auth):
    m = crea_materia(client, auth)
    s = scheda(client, auth, m["id"])
    assert s["verifiche"] == {"struttura": "", "contesto": "", "consegne": [], "punti": "", "punti_totali": None,
                              "sufficienza": None, "regole_punti": [], "difficolta": "", "approfondimento": "",
                              "cosa_da": "", "richieste": []}
    assert s["interrogazioni"] == {"inizio": "", "andamento": "", "durata": "", "profondita": "", "cosa_vuole": [], "esempi": []}


@pagamento
def test_modulo_dalle_verifiche_e_dalle_interrogazioni_con_le_prove(client, auth, servizi, server):
    m = crea_materia(client, auth, "Fisica", "Bianchi")
    aggiungi_testo(client, auth, m["id"], VERIFICA, tipo="verifica")
    aggiungi_testo(client, auth, m["id"], INTERROGAZIONE, tipo="interrogazione")
    server.scheda_forzata = MODULO_AI
    sfondo_tutto(servizi)
    s = scheda(client, auth, m["id"])
    v, i = s["verifiche"], s["interrogazioni"]
    assert s["stato"] == "pronta"
    assert v["struttura"] == "Una tabella di equivalenze, poi un problema da ragionare."
    assert v["consegne"] == ["ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici."]  # quella inventata non c'è
    assert v["punti_totali"] == 30 and v["sufficienza"] == 18 and v["punti"] == "Punti accanto a ogni esercizio."
    assert v["regole_punti"] == ["Mezzo punto in meno per un'imprecisione"]  # senza prova non passa
    assert v["difficolta"] == "" and v["approfondimento"] == "Vuole il ragionamento." and v["cosa_da"] == ""
    assert v["richieste"] == ["Motivare i passaggi"]
    assert i["inizio"] == "Parte con una domanda larga."
    assert i["andamento"] == ""  # la prova viene da una verifica, non da un'interrogazione
    assert i["esempi"] == ["Ti ricordi perché per i pitagorici il numero è il principio?"]
    # Il modulo ha la sua chiamata, dopo la scheda; tutte e due sanno che le verifiche possono non essere corrette.
    chat = [d["messages"] for p, d in server.chiamate if p == "/v1/chat/completions"]
    assert "Studia come interroga" in chat[0][1]["content"] and "corrette o no" in chat[0][0]["content"]
    assert "Descrivi come fa le verifiche" in chat[1][1]["content"] and "corrette o no" in chat[1][0]["content"]
    assert '"contesto"' in chat[1][1]["content"] and '"verifiche"' not in chat[0][1]["content"]


@pagamento
def test_numeri_che_non_sono_nei_documenti_non_passano(client, auth, servizi, server):
    m = crea_materia(client, auth, "Fisica")
    aggiungi_testo(client, auth, m["id"], VERIFICA, tipo="verifica")
    server.scheda_forzata = {**MODULO_AI, "verifiche": {**MODULO_AI["verifiche"], "punti": {
        "frase": "Punti accanto", "totale": 40, "sufficienza": 18, "prova": "TOT 22 / 30 pt."}}}
    sfondo_tutto(servizi)
    v = scheda(client, auth, m["id"])["verifiche"]
    assert v["punti_totali"] is None and v["sufficienza"] is None  # 40 non c'è; senza totale niente sufficienza
    # Senza interrogazioni la parte orale resta vuota anche se l'AI la scrive.
    assert not schede.modulo_pieno(scheda(client, auth, m["id"])["interrogazioni"])


@pagamento
def test_le_modifiche_di_andrea_al_modulo_restano(client, auth, servizi, server):
    m = crea_materia(client, auth, "Fisica")
    aggiungi_testo(client, auth, m["id"], VERIFICA, tipo="verifica")
    server.scheda_forzata = MODULO_AI
    sfondo_tutto(servizi)
    v = scheda(client, auth, m["id"])["verifiche"]
    # Il computer manda l'oggetto intero: cambiano solo struttura, richieste e i numeri.
    nuovo = {**v, "struttura": "Sempre tre esercizi.", "richieste": v["richieste"] + ["Scrivere le unità di misura"],
             "punti_totali": 100, "sufficienza": 60}
    r = client.put(f"/api/materie/{m['id']}/scheda", json={"verifiche": nuovo}, headers=auth)
    assert r.status_code == 200, r.text
    assert r.json()["verifiche"]["struttura"] == "Sempre tre esercizi."
    # L'AI rifà la scheda con un approfondimento diverso: quello cambia, le parti di Andrea no.
    server.scheda_forzata = {**MODULO_AI, "verifiche": {**MODULO_AI["verifiche"], "approfondimento": {
        "frase": "Vuole i passaggi spiegati.", "prova": "motiva i passaggi logici"}}}
    client.post(f"/api/materie/{m['id']}/scheda/aggiorna", headers=auth)
    sfondo_tutto(servizi)
    v = scheda(client, auth, m["id"])["verifiche"]
    assert v["struttura"] == "Sempre tre esercizi." and v["punti_totali"] == 100 and v["sufficienza"] == 60
    assert v["richieste"] == ["Motivare i passaggi", "Scrivere le unità di misura"]
    assert v["approfondimento"] == "Vuole i passaggi spiegati."
    # Un testo svuotato resta vuoto; una voce tolta resta tolta.
    client.put(f"/api/materie/{m['id']}/scheda", json={"verifiche": {"approfondimento": "", "consegne": []}}, headers=auth)
    client.post(f"/api/materie/{m['id']}/scheda/aggiorna", headers=auth)
    sfondo_tutto(servizi)
    v = scheda(client, auth, m["id"])["verifiche"]
    assert v["approfondimento"] == "" and v["consegne"] == []


def test_put_del_modulo_controlla_i_campi(client, auth):
    m = crea_materia(client, auth)
    url = f"/api/materie/{m['id']}/scheda"
    assert client.put(url, json={"verifiche": {"punti_totali": 0}}, headers=auth).status_code == 422
    assert client.put(url, json={"verifiche": {"consegne": ["x"] * 7}}, headers=auth).status_code == 422
    assert client.put(url, json={"interrogazioni": {"esempi": ["x" * 201]}}, headers=auth).status_code == 422
    r = client.put(url, json={"verifiche": {"sufficienza": 18}}, headers=auth)
    assert r.status_code == 200 and r.json()["verifiche"]["sufficienza"] is None  # senza i punti in tutto non vale
    r = client.put(url, json={"interrogazioni": {"inizio": "Chiama alla cattedra e chiede una definizione."}}, headers=auth)
    assert r.json()["stato"] == "pronta" and r.json()["interrogazioni"]["inizio"].startswith("Chiama")


def _scheda_con_modulo() -> dict:
    v = {**schede.modulo_vuoto("verifiche"), "struttura": "Due testi da leggere e cinque domande.",
         "consegne": ["Leggi il testo e rispondi."], "punti_totali": 30, "sufficienza": 18,
         "regole_punti": ["0,5 con imprecisione"], "cosa_da": "Due testi da leggere."}
    i = {**schede.modulo_vuoto("interrogazioni"), "inizio": "Parte con una domanda larga.", "esempi": ["Chi era Tylor?"]}
    return {"argomenti": [], "tipi_domanda": [], "cosa_corregge": [], "cosa_premia": [], "lunghezza_risposte": "",
            "consigli": [], "note_mie": "", "verifiche": v, "interrogazioni": i}


def test_il_modulo_entra_solo_dove_serve():
    s = _scheda_con_modulo()
    assert "Come fa le verifiche" not in prompt.scheda_in_parole(s)  # le lezioni normali non lo vedono
    testo = prompt.scheda_in_parole(s, verifiche=True)
    assert "Come fa le verifiche:" in testo and "- Punti: 30 in tutto, sufficienza 18" in testo
    assert "«Leggi il testo e rispondi.»" in testo and "Come interroga" not in testo
    assert "Come interroga:" in prompt.scheda_in_parole(s, interrogazioni=True)
    assert "Come interroga" in prompt.stile_domande(s)
    lettura = {"argomento": "Boas", "indicazioni": "", "materia": {"nome": "Scienze umane", "prof": ""}, "evento": None,
               "scheda": s, "brani": [], "durata_min": 10, "da_ripassare": None}
    sim = prompt_studio.messaggi_simulata(lettura, 30, 5)[1]["content"]
    assert "Questo prof lo conosciamo" in sim and "in tutto 30" in sim and '"testi_da_leggere"' in sim
    assert "Come fa le verifiche:" in sim
    piano = prompt.messaggi_piano(lettura, 3, 4, 3.0)[1]["content"]
    assert "Come interroga:" in piano and "Come fa le verifiche" not in piano
    corr = prompt_studio.messaggi_correzione_simulata([], s)[1]["content"]
    assert "Regole dei punti" in corr
    # Senza modulo le regole di sempre.
    vuota = {**s, "verifiche": schede.modulo_vuoto("verifiche"), "interrogazioni": schede.modulo_vuoto("interrogazioni")}
    sim = prompt_studio.messaggi_simulata({**lettura, "scheda": vuota}, 30, 5)[1]["content"]
    assert "Questo prof lo conosciamo" not in sim and "in tutto 100" in sim


BRANI = [{"id": "B1", "materiale_id": 7, "tipo": "libro", "titolo": "Antropologia", "pagina": 45, "rango": 1,
          "testo": "Tylor definisce la cultura come quell'insieme complesso che include le conoscenze, le credenze, l'arte, "
                   "la morale, il diritto, il costume e qualsiasi altra capacità acquisita dall'uomo come membro della società."}]


def test_testi_da_leggere():
    copiato = "Tylor definisce la cultura come quell'insieme complesso che include le conoscenze, le credenze, l'arte, la morale."
    o = {"titolo": "Prova", "testi_da_leggere": [{"id": "T1", "testo": copiato, "da": "B1"},
                                                  {"id": "T2", "testo": "Boas inventò il lavoro sul campo nel 1920 a Parigi con tutti.", "da": "B1"}],
         "domande": [{"tipo": "aperta", "testo": "Leggi il testo: come definisce la cultura Tylor?", "punti": 5, "da_leggere": "T1",
                      "risposta_attesa": "Come un insieme complesso.", "fonti": ["B1"]},
                     {"tipo": "aperta", "testo": "Leggi il testo: cosa elenca Tylor?", "punti": 5, "da_leggere": "T1",
                      "risposta_attesa": "Conoscenze, credenze, arte, morale.", "fonti": ["B1"]},
                     {"tipo": "aperta", "testo": "Leggi il testo: dove lavorò Boas?", "punti": 5, "da_leggere": "T2",
                      "risposta_attesa": "A Parigi.", "fonti": ["B1"]},
                     {"tipo": "aperta", "testo": "Spiega il testo T9.", "punti": 5, "da_leggere": "T9", "risposta_attesa": "x"}]}
    rotte = simulata.domande_rotte(o, BRANI)
    assert any("domanda 3" in x and "non è copiato parola per parola" in x for x in rotte)
    assert any("domanda 4" in x and "T9" in x for x in rotte)
    domande = simulata.normalizza(o, BRANI)
    assert len(domande) == 2  # le domande con il testo inventato o che non c'è non passano
    assert domande[0]["brano"] == {"testo": copiato, "fonte": "Libro, pag. 45"} and domande[1]["brano"] == domande[0]["brano"]
    assert simulata._domanda_api(domande[0])["brano"]["fonte"] == "Libro, pag. 45"
    # Dalle modifiche il testo arriva già scritto nella domanda.
    dopo = simulata.normalizza({"domande": [{**domande[0], "da_leggere": None}]}, BRANI)
    assert dopo[0]["brano"]["testo"] == copiato
    # Nel PDF il testo da leggere c'è una volta sola.
    contenuto = pdf.simulata_pdf({"titolo": "Prova", "minuti": 30}, domande, {"nome": "Scienze umane"})
    testo = "".join(p.extract_text() for p in PdfReader(io.BytesIO(contenuto)).pages)
    assert testo.count("Testo da leggere") == 1 and "Tylor definisce la cultura" in testo


def test_voto_con_la_sufficienza_del_prof():
    assert schede.sufficienza_del_prof(_scheda_con_modulo()) == 0.6
    assert schede.sufficienza_del_prof({"verifiche": {"punti_totali": 30, "sufficienza": None}}) is None
    assert simulata.voto_stimato(18, 30, 0.6) == 6 and simulata.voto_stimato(30, 30, 0.6) == 10
    assert simulata.voto_stimato(58, 100, 0.58) == 6 and simulata.voto_stimato(0, 100, 0.58) == 1
    assert simulata.voto_stimato(29, 100, 0.58) == 3.5
    assert simulata.voto_stimato(22, 30) == 7.5 and simulata.voto_stimato(1, 0) is None


def _studio_tutto(servizi, massimo=40):
    for _ in range(massimo):
        if servizi.lavoro.passo_studio() != "fatto":
            return
    raise AssertionError("la coda non finisce")


@pagamento
def test_verifica_di_prova_con_il_modulo_e_la_sufficienza_del_prof(client, auth, servizi, server):
    m = crea_materia(client, auth, "Scienze umane", "Verdi")
    aggiungi_testo(client, auth, m["id"], BRANI[0]["testo"], tipo="libro")
    sfondo_tutto(servizi)
    # Il modulo scritto a mano (nessuna verifica caricata): punti in tutto 30, sufficienza 18.
    r = client.put(f"/api/materie/{m['id']}/scheda", json={"verifiche": {"struttura": "Un testo da leggere e due domande.",
                                                                         "punti_totali": 30, "sufficienza": 18}}, headers=auth)
    assert r.status_code == 200, r.text
    copiato = "Tylor definisce la cultura come quell'insieme complesso che include le conoscenze, le credenze, l'arte, la morale."
    server.simulata = {"titolo": "Prova su Tylor", "testi_da_leggere": [{"id": "T1", "testo": copiato, "da": "B1"}],
                       "domande": [{"tipo": "aperta", "testo": "Leggi il testo: come definisce la cultura Tylor?", "punti": 10,
                                    "da_leggere": "T1", "risposta_attesa": "Come un insieme complesso.", "fonti": ["B1"]},
                                   {"tipo": "aperta", "testo": "Leggi il testo: cosa include la cultura?", "punti": 20,
                                    "da_leggere": "T1", "risposta_attesa": "Conoscenze, credenze, arte, morale.", "fonti": ["B1"]}]}
    n = server.conta("/v1/chat/completions")
    sid = client.post("/api/simulate", json={"materia_id": m["id"], "argomento": "Tylor", "minuti": 30}, headers=auth).json()["id"]
    _studio_tutto(servizi)
    d = client.get(f"/api/simulate/{sid}", headers=auth).json()
    assert d["stato"] == "pronta" and d["nota"] is None  # il formato del prof lo sappiamo dal modulo
    assert [q["brano"]["testo"] for q in d["domande"]] == [copiato, copiato]
    chiesta = next(c["messages"][-1]["content"] for p, c in server.chiamate[n:] if p == "/v1/chat/completions"
                   and "verifica di prova da 30 minuti" in c["messages"][-1]["content"])
    assert "Questo prof lo conosciamo" in chiesta and "in tutto 30" in chiesta
    client.patch(f"/api/simulate/{sid}", json={"risposte": {"1": "Un insieme complesso", "2": "Le conoscenze"}, "iniziata": True},
                 headers=auth)
    server.correzione = {"domande": [{"n": 1, "esito": "giusta", "punti": 10, "commento": "Giusto.", "mancava": []},
                                     {"n": 2, "esito": "parziale", "punti": 8, "commento": "Ne manca qualcuna.", "mancava": []}]}
    client.post(f"/api/simulate/{sid}/consegna", headers=auth)
    _studio_tutto(servizi)
    e = client.get(f"/api/simulate/{sid}", headers=auth).json()["esito"]
    assert e["punti"] == 18 and e["punti_totali"] == 30 and e["soglia"] == 18 and e["voto_stimato"] == 6
    correzione = next(c["messages"][-1]["content"] for p, c in server.chiamate if p == "/v1/chat/completions"
                      and "Correggi ogni risposta confrontandola" in c["messages"][-1]["content"])
    assert "Testo da leggere: Tylor definisce" in correzione and "Come fa le verifiche:" in correzione


def test_consegne_solo_dalle_verifiche_e_leggibili():
    """Schede rifatte il 24/11/2026: una consegna presa dal racconto di un esito («che non ricordo»), il LaTeX grezzo
    della lettura delle foto e il numero davanti."""
    documenti = [{"id": "V1", "tipo": "verifica", "titolo": "Verifica", "testo":
                  "1) Completa la seguente tabella con le equivalenze. 2) Determinare il risultato dell'operazione "
                  r"$$\frac{1,2 \cdot 10^{-18}}{2,0 \cdot 10^{-8}}$$ in notazione scientifica. TOT 30 pt"}]
    esiti = [{"quando": "ieri", "tipo": "scritta", "voto": None, "altre": [],
              "uscite": ["introdurre Tylor e Morgan, spiegando il potlatch e un'altra cosa che non ricordo"]}]
    o = {"domande": [], "verifiche": {
        "struttura": {"frase": "Una tabella e un esercizio di calcolo.", "prova": "Completa la seguente tabella"},
        "consegne": ["1) Completa la seguente tabella con le equivalenze.",
                     r"2) Determinare il risultato dell'operazione $$\frac{1,2 \cdot 10^{-18}}{2,0 \cdot 10^{-8}}$$ in notazione scientifica",
                     "introdurre Tylor e Morgan, spiegando il potlatch e un'altra cosa che non ricordo"]}}
    m = schede.conta(o, documenti, esiti)["verifiche"]
    assert m["struttura"] == "Una tabella e un esercizio di calcolo."
    assert m["consegne"] == ["Completa la seguente tabella con le equivalenze.",
                             "Determinare il risultato dell'operazione (1,2 · 10⁻¹⁸)/(2,0 · 10⁻⁸) in notazione scientifica"]
    # Le schede salvate prima si leggono già pulite.
    vecchia = {"dati": json.dumps({"verifiche": {"consegne": [r"Esprimi il risultato in $$\frac{kg}{m^3}$$"]}})}
    assert schede.dati_di(vecchia)["verifiche"]["consegne"] == ["Esprimi il risultato in kg/m³"]
    vecchia = {"dati": json.dumps({"argomenti": [{"nome": "Densità", "volte": 1, "esempio": r"3) Esprimi in $$\frac{kg}{m^3}$$"}]})}
    assert schede.dati_di(vecchia)["argomenti"][0]["esempio"] == "Esprimi in kg/m³"
    testo = prompt.messaggi_scheda({"nome": "Fisica", "prof": None}, documenti, esiti)[1]["content"]
    assert "è una voce sola" in testo and "Al massimo 60 voci" in testo
    assert "niente id dei documenti" in prompt.messaggi_modulo({"nome": "Fisica", "prof": None}, documenti, esiti)[1]["content"]


def test_modulo_a_punti_con_il_contesto():
    """24/11/2026 sera (Andrea: "alcune parti non le mette, mette le cose nei posti sbagliati e certe cose non le dice
    tutte", e manca il contesto): ogni campo è una lista di punti con la sua prova; si perde solo il punto senza prova."""
    documenti = [{"id": "V1", "tipo": "verifica", "titolo": "Verifica",
                  "testo": VERIFICA + "\n3) Esprimi il risultato in $$\frac{kg}{m^3}$$"}]
    m = {"verifiche": {
        "struttura": [{"frase": "1) Tabella di equivalenze da completare", "prova": "Completa la seguente tabella"},
                      {"frase": "2) Un problema da ragionare", "prova": "Quanti cubetti da 2 cm servono"},
                      {"frase": "Tre esercizi sulle forze", "prova": "una cosa che non c'è"}],
        "contesto": [{"frase": "Problemi con oggetti di tutti i giorni: cubetti e una scatola.",
                      "prova": "per riempire una scatola da 10 cm"}],
        "richieste": [{"frase": "Il risultato in kg/m³", "prova": "Esprimi il risultato in kg/m³"}],  # il documento ha il LaTeX
        "totale": 30, "sufficienza": 18}, "interrogazioni": None}
    v = schede.modulo_da(m, documenti, [])["verifiche"]
    assert v["struttura"] == "1) Tabella di equivalenze da completare.\n2) Un problema da ragionare."
    assert v["contesto"] == "Problemi con oggetti di tutti i giorni: cubetti e una scatola."
    assert v["richieste"] == ["Il risultato in kg/m³"]
    assert v["punti_totali"] == 30 and v["sufficienza"] == 18
    # Nel prompt della verifica di prova ogni riga diventa un punto, e c'è il contesto.
    righe = prompt.scheda_in_parole({**schede.vuota(), "verifiche": v}, verifiche=True)
    assert "Com'è fatta: 1) Tabella di equivalenze da completare; 2) Un problema da ragionare" in righe
    assert "Contesto delle domande: Problemi con oggetti" in righe


def test_put_del_modulo_a_righe(client, auth):
    m = crea_materia(client, auth)
    url = f"/api/materie/{m['id']}/scheda"
    righe = "Prima riga.\n\n  Seconda   riga.  \nTerza riga."
    r = client.put(url, json={"verifiche": {"contesto": righe, "struttura": "x" * 800}}, headers=auth)
    assert r.status_code == 200, r.text
    assert r.json()["verifiche"]["contesto"] == "Prima riga.\nSeconda riga.\nTerza riga."
    assert client.put(url, json={"verifiche": {"difficolta": "x" * 801}}, headers=auth).status_code == 422
    # Un computer 1.5.0 manda l'oggetto senza "contesto": il contesto resta.
    r = client.put(url, json={"verifiche": {"struttura": "Due esercizi."}}, headers=auth)
    assert r.json()["verifiche"]["contesto"] == "Prima riga.\nSeconda riga.\nTerza riga."
