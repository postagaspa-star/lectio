"""Il generatore finto della modalità prova: stessa interfaccia di TestoMistral, nessuna
chiamata esterna, costo 0, sempre lo stesso risultato a parità di materiale.

Non è un'AI: prende davvero le frasi del materiale e le rimette nella forma che la
lezione si aspetta (piano, blocchi, domande, scheda), così si prova tutto il percorso
(lettura, piano, scrittura, voce, montaggio, domande e tempi) prima di avere le chiavi.
Usa `dati` (passati dal server insieme ai messaggi) invece di leggere i prompt."""

import json
import re

from .testo_ai import RispostaTesto

PAROLE_MASSIME_FRASE = 20
# In prova i blocchi restano corti: la voce di prova è robotica e serve solo a provare.
PAROLE_MASSIME_BLOCCO = 140

_ETICHETTE = re.compile(
    r"^\s*(?:#+\s*)?(?:domanda\s*\d*.*?:?|testo|mia risposta|correzioni del prof|punti presi|commento del prof"
    r"|materia|prof|data|tipo|voto)\s*[:)]\s*", re.IGNORECASE)


def _righe_pulite(testo: str) -> list[str]:
    righe = []
    for riga in (testo or "").split("\n"):
        if re.match(r"^\s*MATERIA\s*:", riga, re.IGNORECASE):
            continue  # la testata dei testi di Gemini
        riga = _ETICHETTE.sub("", riga).strip(" -*#\t")
        if riga and riga != "[illeggibile]":
            righe.append(riga)
    return righe


def _accorcia(frase: str) -> str:
    parole = frase.split()
    if len(parole) <= PAROLE_MASSIME_FRASE:
        return frase
    return " ".join(parole[:PAROLE_MASSIME_FRASE]).rstrip(",;:") + "."


def frasi(testo: str) -> list[str]:
    uscita = []
    for riga in _righe_pulite(testo):
        for pezzo in re.split(r"(?<=[.!?…])\s+", riga):
            pezzo = pezzo.strip()
            if len(pezzo.split()) < 3:
                continue
            if not re.search(r"[.!?…]$", pezzo):
                pezzo += "."
            uscita.append(_accorcia(pezzo))
    return uscita


def _domande_vere(brani: list[dict]) -> list[str]:
    """Le domande scritte nelle verifiche trascritte con Gemini (riga TESTO: …)."""
    domande = []
    for b in brani:
        for riga in (b.get("testo") or "").split("\n"):
            m = re.match(r"^\s*TESTO\s*:\s*(.+)$", riga, re.IGNORECASE)
            if m and len(m.group(1).split()) >= 3:
                d = m.group(1).strip()
                domande.append(d if d.endswith("?") else d.rstrip(".") + "?")
    return domande


def _parole(testo: str) -> int:
    return len(testo.split())


_VUOTE = {"perché", "quando", "quale", "quali", "questo", "questa", "questi", "queste", "quello", "quella",
          "anche", "molto", "sono", "erano", "aveva", "avevano", "della", "delle", "degli", "nella", "nelle",
          "dalla", "dalle", "sulla", "sulle", "come", "cosa", "dove", "però", "quindi", "allora", "invece"}


def _parole_importanti(testo: str) -> list[str]:
    return [p for p in re.findall(r"[a-zàèéìòù]{5,}", (testo or "").lower()) if p not in _VUOTE]


def _titolo_da(frase: str, riserva: str) -> str:
    parole = re.sub(r"[^\w\sàèéìòù']", "", frase).split()
    return " ".join(parole[:6]).capitalize() if parole else riserva


class TestoFinto:
    nome = "prova"
    a_pagamento = False

    def genera(self, *, compito: str, messaggi: list[dict], modello: str, json_atteso: bool = True,
               max_token: int = 4000, temperatura: float = 0.4, dati: dict | None = None) -> RispostaTesto:
        dati = dati or {}
        fare = getattr(self, f"_{compito}", None)
        oggetto = fare(dati) if fare else {}
        testo = json.dumps(oggetto, ensure_ascii=False)
        entrata = sum(len(m.get("content", "")) for m in messaggi) // 4
        return RispostaTesto(testo=testo, token_entrata=entrata, token_uscita=len(testo) // 4, modello="prova")

    # ------------------------------------------------------------------ lezione

    def _piano(self, d: dict) -> dict:
        lettura = d["lettura"]
        brani = lettura["brani"]
        n = int(d["n_blocchi"])
        argomento = lettura["argomento"]
        gruppi: list[list[dict]] = [[] for _ in range(n)]
        for i, b in enumerate(brani):
            gruppi[min(n - 1, i * n // max(1, len(brani)))].append(b)
        blocchi = []
        for k, gruppo in enumerate(gruppi, start=1):
            if not gruppo and brani:
                gruppo = [brani[(k - 1) % len(brani)]]
            tutte = [f for b in gruppo for f in frasi(b["testo"])]
            titolo = _titolo_da(tutte[0], f"{argomento}, parte {k}") if tutte else f"{argomento}, parte {k}"
            spunto = tutte[0].rstrip(".") if tutte else argomento
            blocchi.append({
                "titolo": titolo,
                "punti": tutte[:4] or [f"Cosa sappiamo di {argomento}."],
                "fonti": [b["id"] for b in gruppo],
                "domanda": {"testo": f"Che cosa ricordi di questo: {spunto[:90].lower()}?", "tipo": "aperta"},
            })
        vere = _domande_vere(brani)
        tutte_frasi = [f for b in brani for f in frasi(b["testo"])] or [f"{argomento} è l'argomento di oggi."]
        tipo = d.get("tipo") or "spiegazione"
        if tipo == "interrogazione":
            # Le domande divise nei gruppi chiesti: prima quelle vere delle verifiche, poi dalle frasi.
            contatore = 0
            for k, quante in enumerate(d.get("gruppi") or [], start=1):
                domande = []
                for _ in range(int(quante)):
                    if contatore < len(vere):
                        testo = vere[contatore]
                    else:
                        frase = tutte_frasi[contatore % len(tutte_frasi)].rstrip(".")
                        testo = f"Spiega questo punto: {frase[:100].lower()}."
                    domande.append({"testo": testo, "tipo": "aperta", "fonti": []})
                    contatore += 1
                if k <= len(blocchi):
                    blocchi[k - 1]["domande"] = domande
        elif tipo == "ripasso":
            for b in blocchi:
                b.pop("domanda", None)
        interrogazione = []
        for i in range(0 if tipo == "interrogazione" else int(d["n_interrogazione"])):
            if i < len(vere):
                interrogazione.append({"testo": vere[i], "tipo": "aperta", "fonti": []})
            else:
                frase = tutte_frasi[i % len(tutte_frasi)].rstrip(".")
                interrogazione.append({"testo": f"Spiega questo punto: {frase[:100].lower()}.", "tipo": "aperta",
                                       "fonti": []})
        # I punti da ripassare che c'entrano con l'argomento diventano le prime domande.
        sull_argomento = set(_parole_importanti(argomento))
        ripresi = [v for v in lettura.get("da_ripassare") or []
                   if sull_argomento & set(_parole_importanti(v["testo"]))]
        for i, v in enumerate(ripresi[: len(interrogazione)]):
            interrogazione[i] = {"testo": f"Torniamo su un punto da ripassare. {v['testo']}", "tipo": "aperta",
                                 "fonti": []}
        parole_materiale = sum(_parole(b["testo"]) for b in brani)
        corregge = (lettura.get("scheda") or {}).get("cosa_corregge") or []
        errori = (corregge + [
            "Rispondere senza partire dalla definizione.",
            "Dimenticare le date e i nomi precisi.",
            "Fermarsi ai fatti senza dire le cause e le conseguenze.",
        ])[:3]
        return {
            "titolo": argomento,
            "minuti_di_materiale": round(min(float(lettura["durata_min"]), parole_materiale / 145), 1),
            "blocchi": blocchi,
            "interrogazione": interrogazione,
            "errori_da_evitare": errori,
        }

    def _apertura(self, d: dict) -> dict:
        piano = d["piano"]
        lettura = d["lettura"]
        prof = (lettura["materia"].get("prof") or "").strip()
        titoli = ", poi ".join(b["titolo"].lower() for b in piano["blocchi"][:3])
        tipo = piano.get("tipo") or "spiegazione"
        come = (f" come la farebbe il prof {prof}." if prof else ".")
        if tipo == "ripasso":
            seguito = "Prima i punti chiave, poi tante domande alla fine" + come
        elif tipo == "interrogazione":
            seguito = "Oggi niente spiegazione: ti interrogo" + come
        else:
            seguito = "Dopo ogni parte c'è una domanda, alla fine un'interrogazione" + come
        return {"paragrafi": [{"testo": (
            f"Questa è una lezione di prova, con un testo finto. Oggi vediamo {lettura['argomento']}. "
            f"Partiamo da {titoli}. {seguito}"
        )}]}

    def _blocco(self, d: dict) -> dict:
        piano = d["piano"]
        k = int(d["k"])
        blocco = piano["blocchi"][k - 1]
        tutte = [f for b in d.get("brani") or [] for f in frasi(b["testo"])] or list(blocco.get("punti") or [])
        limite = min(int(d.get("parole") or PAROLE_MASSIME_BLOCCO), PAROLE_MASSIME_BLOCCO)
        paragrafi: list[dict] = []
        usate, attuale = 0, []
        for frase in tutte:
            if usate >= limite:
                break
            attuale.append(frase)
            usate += _parole(frase)
            if len(attuale) == 3:
                tipo = "definizione" if re.search(r"\b\d{3,4}\b| è | sono ", " ".join(attuale)) and len(paragrafi) % 2 else "spiegazione"
                paragrafi.append({"tipo": tipo, "testo": " ".join(attuale)})
                attuale = []
        if attuale:
            paragrafi.append({"tipo": "esempio", "testo": " ".join(attuale)})
        risposta = tutte[0] if tutte else f"La risposta sta in {blocco['titolo'].lower()}."
        domanda = (blocco.get("domanda") or {}).get("testo") or f"Che cosa ricordi di {blocco['titolo'].lower()}?"
        return {"paragrafi": paragrafi, "domanda": domanda, "risposta": risposta}

    def _parte(self, d: dict) -> dict:
        """Ripasso: solo i punti chiave, senza domanda."""
        uscita = self._blocco(d)
        return {"paragrafi": uscita["paragrafi"] or [{"tipo": "spiegazione", "testo": "Niente da aggiungere su questa parte."}]}

    def _gruppo(self, d: dict) -> dict:
        """Interrogazione: le domande del gruppo, con la risposta presa dal materiale."""
        piano = d["piano"]
        blocco = piano["blocchi"][int(d["k"]) - 1]
        tutte = [f for b in d.get("brani") or [] for f in frasi(b["testo"])] or list(blocco.get("punti") or [])
        domande = []
        for i, q in enumerate(blocco.get("domande") or []):
            risposta = " ".join(tutte[i * 2:i * 2 + 2]) if tutte else f"La risposta sta in {blocco['titolo'].lower()}."
            domande.append({"testo": q["testo"], "risposta": risposta or tutte[0]})
        return {"introduzione": f"Adesso qualche domanda su {blocco['titolo'].lower()}.", "domande": domande}

    def _titolo(self, d: dict) -> dict:
        prima = next(iter(frasi(d.get("testo") or "")), "Materiale")
        return {"titolo": _titolo_da(prima, "Materiale")}

    def _finale(self, d: dict) -> dict:
        piano = d["piano"]
        if (piano.get("tipo") or "spiegazione") == "interrogazione":
            errori = [f"Errore da evitare: {e.rstrip('.').lower()}. Fai il contrario e sei a posto."
                      for e in piano.get("errori_da_evitare") or []][:3]
            return {"errori": errori or ["Rispondi sempre partendo dalla definizione."],
                    "chiusura": "Per oggi è tutto. Rifai le domande che hai sbagliato prima della verifica."}
        tutte = [f for b in d.get("brani") or [] for f in frasi(b["testo"])]
        riepilogo = "Ricapitoliamo. " + " ".join(f"Abbiamo visto {b['titolo'].lower()}." for b in piano["blocchi"])
        domande = []
        for i, q in enumerate(piano["interrogazione"]):
            risposta = tutte[i % len(tutte)] if tutte else "La risposta è nel materiale di questa lezione."
            domande.append({"testo": q["testo"], "risposta": risposta})
        errori = [f"Errore da evitare: {e.rstrip('.').lower()}. Fai il contrario e sei a posto."
                  for e in piano.get("errori_da_evitare") or []][:3]
        return {"riepilogo": [riepilogo], "domande": domande, "errori": errori,
                "chiusura": "Per oggi è tutto. Ripeti a voce le risposte prima della verifica."}

    # ------------------------------------------------------------------ controllo, schemi, simulate

    def _verifica(self, d: dict) -> dict:
        return {"fuori": []}

    def _copertura(self, d: dict) -> dict:
        return {"mancanti": []}

    def _foto(self, d: dict) -> dict:
        return {"mostra": False, "cosa_si_vede": ""}

    def _schema_piano(self, d: dict) -> dict:
        lettura = d["lettura"]
        brani = lettura["brani"]
        n = max(1, min(int(d["massimo"]), len(brani)))
        gruppi: list[list[dict]] = [[] for _ in range(n)]
        for i, b in enumerate(brani):
            gruppi[min(n - 1, i * n // max(1, len(brani)))].append(b)
        sezioni, concetti = [], []
        for k, gruppo in enumerate(gruppi, start=1):
            tutte = [f for b in gruppo for f in frasi(b["testo"])]
            titolo = _titolo_da(tutte[0], f"Parte {k}") if tutte else f"Parte {k}"
            punti = [f.rstrip(".") for f in tutte[:3]] or [titolo]
            concetti += punti
            sezioni.append({"titolo": titolo, "concetti": punti, "fonti": [b["id"] for b in gruppo][:6]})
        forma = d["forma"] if d["forma"] != "auto" else "capitoli"
        return {"titolo": lettura["argomento"], "forma": forma, "concetti": concetti, "sezioni": sezioni, "immagini": []}

    def _schema_sezione(self, d: dict) -> dict:
        sezione = d["sezione"]
        brani = d.get("brani") or []
        tutte = [f for b in brani for f in frasi(b["testo"])] or [f"{sezione['titolo']}."]
        fonti = [b["id"] for b in brani][:3]
        blocchi = [{"tipo": "paragrafo", "titolo": None, "testo": " ".join(tutte[:3]), "fonti": fonti},
                   {"tipo": "punti", "titolo": "Da sapere", "voci": tutte[:4], "fonti": fonti}]
        uscita = {"blocchi": blocchi,
                  "carte": [{"domanda": f"Che cosa ricordi di {sezione['titolo'].lower()}?", "risposta": tutte[0]}]}
        if d.get("forma") == "schede":
            uscita["campi"] = [{"nome": "Dal testo", "valore": tutte[0][:80]}]
        return uscita

    def _schema_tabella(self, d: dict) -> dict:
        return {"titolo": "Quadro d'insieme", "intestazione": ["Parte", "Da ricordare"],
                "righe": [[s["titolo"], (s.get("primo") or "")[:90]] for s in d["sezioni"]]}

    def _schema_immagini(self, d: dict) -> dict:
        return {"immagini": []}

    def _riassunto(self, d: dict) -> dict:
        parti = []
        for sezione in d["sezioni"]:
            righe = [r for r in sezione["testo"].splitlines()[1:] if r.strip()][:5]
            if not righe:
                continue
            if d["forma"] == "punti":
                parti.append({"titolo": sezione["titolo"], "paragrafi": [], "punti": righe})
            elif d["forma"] == "discorsivo":
                parti.append({"titolo": sezione["titolo"], "paragrafi": [" ".join(righe)], "punti": []})
            else:
                parti.append({"titolo": sezione["titolo"], "paragrafi": [" ".join(righe[:2])], "punti": righe[2:]})
        return {"titolo": "Riassunto", "parti": parti}

    def _chiarimento(self, d: dict) -> dict:
        tutte = [f for b in d.get("brani") or [] for f in frasi(b["testo"])]
        if not tutte:
            return {"trovato": False, "risposta": None, "fonti": [], "cosa_manca": "quello che chiedi"}
        return {"trovato": True, "risposta": "Vuol dire questo. " + " ".join(tutte[:3]),
                "fonti": [b["id"] for b in d["brani"]][:2], "cosa_manca": None}

    def _simulata(self, d: dict) -> dict:
        lettura = d["lettura"]
        brani = lettura["brani"]
        tutte = [(f, b["id"]) for b in brani for f in frasi(b["testo"])] or [("Il materiale è vuoto.", None)]
        n = int(d["domande_circa"])
        domande = []
        for i, testo in enumerate(_domande_vere(brani)[:n]):
            domande.append({"tipo": "aperta", "testo": testo, "punti": 10, "sezione": None,
                            "risposta_attesa": tutte[i % len(tutte)][0], "fonti": [tutte[i % len(tutte)][1]],
                            "dal_documento": None})
        for i in range(len(domande), n):
            frase, fonte = tutte[i % len(tutte)]
            domande.append({"tipo": "aperta", "testo": f"Spiega questo punto: {frase[:100].lower()}", "punti": 10,
                            "sezione": None, "risposta_attesa": frase, "fonti": [fonte], "dal_documento": None})
        return {"titolo": f"Verifica di prova: {lettura['argomento']}"[:100], "domande": domande}

    def _correzione_simulata(self, d: dict) -> dict:
        uscita = []
        for v in d["voci"]:
            esito = self._correttore({"attesa": v["risposta_attesa"], "risposta": v["risposta"]})
            uscita.append({"n": v["n"], "esito": esito["esito"], "punti": round(v["punti"] * esito["punteggio"]),
                           "commento": esito["commento"], "mancava": esito["mancava"]})
        return {"domande": uscita}

    def _correttore(self, d: dict) -> dict:
        """Confronta le parole importanti della risposta attesa con quelle scritte."""
        attese = list(dict.fromkeys(_parole_importanti(d.get("attesa") or "")))
        scritte = set(_parole_importanti(d.get("risposta") or ""))
        trovate = [p for p in attese if p in scritte]
        mancanti = [p for p in attese if p not in scritte]
        quota = len(trovate) / len(attese) if attese else 1.0
        if quota >= 0.6:
            return {"esito": "giusta", "punteggio": 1.0, "commento": "Giusto: hai detto le cose che contano.",
                    "mancava": []}
        if quota >= 0.25:
            return {"esito": "parziale", "punteggio": 0.5,
                    "commento": f"In parte giusto: hai detto {', '.join(trovate[:2])}. Manca qualcosa di importante.",
                    "mancava": mancanti[:3]}
        return {"esito": "sbagliata", "punteggio": 0.0,
                "commento": "Non ci siamo ancora: manca l'idea centrale della risposta.", "mancava": mancanti[:3]}

    # ------------------------------------------------------------------ scheda del prof

    def _scheda(self, d: dict) -> dict:
        documenti = d.get("documenti") or []
        domande = []
        for doc in documenti:
            for q in _domande_vere([doc]):
                t = q.lower()
                if re.search(r"\b(quando|in che anno|data)\b", t):
                    tipo = "date"
                elif re.search(r"\b(che cos|cos'è|definisci|definizione)\b", t):
                    tipo = "definizione"
                elif "vero o falso" in t:
                    tipo = "vero_falso"
                elif re.search(r"\b(traduci|traduzione)\b", t):
                    tipo = "traduzione"
                elif re.search(r"\b(calcola|risolvi)\b", t):
                    tipo = "esercizio"
                else:
                    tipo = "aperta"
                domande.append({"doc": doc["id"], "testo": q.rstrip("?"), "tipo": tipo,
                                "argomento": (doc.get("titolo") or "Argomento").strip()[:60]})
        corregge, premia = [], []
        for doc in documenti:
            for riga in (doc.get("testo") or "").split("\n"):
                m = re.match(r"^\s*CORREZIONI DEL PROF\s*:\s*(.+)$", riga, re.IGNORECASE)
                if m and m.group(1).strip() and len(corregge) < 5:
                    corregge.append({"frase": m.group(1).strip()[:150], "prova": m.group(1).strip()})
                m = re.match(r"^\s*COMMENTO DEL PROF\s*:\s*(.+)$", riga, re.IGNORECASE)
                if m and m.group(1).strip() and len(premia) < 3:
                    premia.append({"frase": m.group(1).strip()[:150], "prova": m.group(1).strip()})
        return {"domande": domande, "cosa_corregge": corregge, "cosa_premia": premia, "lunghezza_risposte": None,
                "consigli": []}
