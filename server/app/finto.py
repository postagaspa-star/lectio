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
        interrogazione = []
        for i in range(int(d["n_interrogazione"])):
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
        return {"paragrafi": [{"testo": (
            f"Questa è una lezione di prova, con un testo finto. Oggi vediamo {lettura['argomento']}. "
            f"Partiamo da {titoli}. Dopo ogni parte c'è una domanda, alla fine un'interrogazione"
            + (f" come la farebbe il prof {prof}." if prof else ".")
        )}]}

    def _blocco(self, d: dict) -> dict:
        piano = d["piano"]
        k = int(d["k"])
        blocco = piano["blocchi"][k - 1]
        tutte = [f for b in d.get("brani") or [] for f in frasi(b["testo"])] or list(blocco.get("punti") or [])
        limite = min(int(d.get("parole") or PAROLE_MASSIME_BLOCCO), PAROLE_MASSIME_BLOCCO)
        paragrafi = [{"tipo": "aggancio", "testo": f"Partiamo da una domanda. Che cosa sappiamo di {blocco['titolo'].lower()}?"}]
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
        return {"paragrafi": paragrafi, "domanda": blocco["domanda"]["testo"], "risposta": risposta}

    def _finale(self, d: dict) -> dict:
        piano = d["piano"]
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

    def _selezione(self, d: dict) -> dict:
        """Sceglie i pezzi che hanno più parole in comune con l'argomento."""
        parole = set(_parole_importanti(d.get("argomento") or ""))
        brani = d.get("brani") or []
        massimo = int(d.get("massimo") or 60_000)
        ordinati = sorted(brani, key=lambda b: -len(parole & set(_parole_importanti(b["testo"]))))
        scelti, totale = [], 0
        for b in ordinati:
            if scelti and totale + len(b["testo"]) > massimo:
                continue
            scelti.append(b["id"])
            totale += len(b["testo"])
        return {"scelti": scelti}

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
        domande = _domande_vere(documenti)
        conteggio: dict[str, int] = {}
        for q in domande:
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
            conteggio[tipo] = conteggio.get(tipo, 0) + 1
        totale = sum(conteggio.values())
        tipi = [{"tipo": t, "quota": round(n / totale, 2)} for t, n in sorted(conteggio.items(), key=lambda x: -x[1])]
        argomenti: dict[str, dict] = {}
        for doc in documenti:
            nome = (doc.get("titolo") or "Argomento").strip()[:60]
            voce = argomenti.setdefault(nome, {"nome": nome, "volte": 0, "esempio": ""})
            voce["volte"] += 1
            prime = _domande_vere([doc])
            if prime and not voce["esempio"]:
                voce["esempio"] = prime[0][:150]
        corregge, premia = [], []
        for doc in documenti:
            for riga in (doc.get("testo") or "").split("\n"):
                m = re.match(r"^\s*CORREZIONI DEL PROF\s*:\s*(.+)$", riga, re.IGNORECASE)
                if m and m.group(1).strip() and len(corregge) < 5:
                    corregge.append(m.group(1).strip()[:150])
                m = re.match(r"^\s*COMMENTO DEL PROF\s*:\s*(.+)$", riga, re.IGNORECASE)
                if m and m.group(1).strip() and len(premia) < 3:
                    premia.append(m.group(1).strip()[:150])
        return {
            "argomenti": sorted(argomenti.values(), key=lambda a: -a["volte"])[:10],
            "tipi_domanda": tipi,
            "cosa_corregge": corregge,
            "cosa_premia": premia,
            "lunghezza_risposte": "",
            "consigli": ["Rileggi le correzioni delle verifiche passate prima della prossima."],
        }
