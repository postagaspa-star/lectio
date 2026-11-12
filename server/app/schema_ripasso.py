"""Gli schemi di ripasso (contratto 18): lettura → punti → piano → scrittura → copertura → foto → chiusura.

Ogni fase salva quello che produce in dati/schemi/<id>/: un Riprova riparte da lì. Ogni parte
passa dal controllo sui documenti (controllo.py). Le foto sono poche, a meno che le indicazioni
ne chiedano tante, e ognuna viene guardata prima di metterla.

Dalla 1.3.1 il piano non parte più da un pezzo del materiale scelto per somiglianza col titolo:
il modello piccolo legge TUTTO il materiale a gruppi e segna i punti da sapere, il piano li
distribuisce tutti nelle parti, e quello che manca si aggiunge nella sua parte, non in fondo."""

import json
import logging
import re
import shutil
import sqlite3
import unicodedata
from collections import Counter
from datetime import date
from pathlib import Path

from . import ai, clock, controllo, db, immagini, lampo, memoria, prompt_studio, stile
from . import scheda as schede
from .cartella import Cartella
from .lezione import Annullata, ErroreLezione
from .ai import RispostaNonUsabile
from .spesa import CARATTERI_MAPPA_SCHEMA, CARATTERI_SEZIONE

log = logging.getLogger("uvicorn.error")

FASI = ("lettura", "mappa", "piano", "scrittura", "copertura", "immagini", "chiusura")
FRASI = {"lettura": "Leggo i documenti", "mappa": "Segno i punti da sapere", "piano": "Preparo lo schema",
         "scrittura": "Scrivo lo schema", "copertura": "Controllo che ci sia tutto", "immagini": "Cerco le foto",
         "chiusura": "Finisco"}
TIPI_BLOCCO = ("paragrafo", "punti", "passaggi", "definizione", "tabella", "prof")
IMMAGINI_PER_LIVELLO = {"essenziale": 2, "completo": 3, "approfondito": 5}
IMMAGINI_MASSIME = 40
IMMAGINI_TANTE = 15
DIVISORE_SEZIONI = {"essenziale": 9000, "completo": 5000, "approfondito": 3500}
TOKEN_PIANO = 8000  # la lunghezza del piano dipende dai concetti dei documenti: con meno viene tagliato a metà
TOKEN_TABELLA = 3000
TOKEN_MAPPA = 6000
TOKEN_CORREZIONI = 3000
PUNTI_MASSIMI = 500
FONTI_PER_SEZIONE = 14
# Le parole di una parte crescono con i punti che deve spiegare, dentro dei limiti per livello.
PAROLE_PER_PUNTO = {"essenziale": 14, "completo": 32, "approfondito": 50}
PAROLE_LIMITI = {"essenziale": (80, 380), "completo": (150, 1000), "approfondito": (250, 1400)}
# Un argomento: i punti sulla stessa cosa (lo stesso soggetto prima dei due punti), al massimo tanti.
ARGOMENTO_MASSIMO = 8
_ARTICOLI = {"il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una"}
_SOGGETTI_GENERICI = {"causa", "cause", "conseguenza", "conseguenze", "definizione", "esempio", "esempi", "significato",
                      "nota", "data", "date", "periodo", "contesto", "origine", "origini", "obiettivo", "ruolo"}
# Quello che non è un punto da studiare: le immagini e le didascalie delle pagine.
NON_PUNTI = re.compile(r"^(?:immagin[ei]|foto|fotografi[ae]|illustrazion[ei]|didascali[ae])\b", re.IGNORECASE)
COPERTURA_SOGLIA = 0.75
_PAROLE_VUOTE = {"della", "delle", "dello", "degli", "nella", "nelle", "negli", "nello", "dalla", "dalle", "dagli",
                 "sulla", "sulle", "sugli", "alla", "alle", "agli", "come", "cosa", "quale", "quali", "quest", "quell",
                 "sono", "erano", "aveva", "anche", "perch", "quand", "dove", "molto", "tutti", "tutto", "tutta",
                 "tutte", "loro", "esser", "stato", "stata", "fino", "verso", "senza", "oltre", "ogni", "parte"}


def quante_immagini(livello: str, indicazioni: str | None) -> int:
    """Poche di base; un numero o "tante foto" nelle indicazioni cambia le cose."""
    testo = (indicazioni or "").lower()
    if re.search(r"\b(?:senza|niente|nessuna)\s+(?:immagin\w+|foto)\b", testo):
        return 0
    m = re.search(r"(\d{1,3})\s+(?:immagin\w+|foto|illustrazion\w+)\b", testo)
    if m:
        return min(IMMAGINI_MASSIME, int(m.group(1)))
    if re.search(r"\b(?:tante|molte|parecchie|numerose)\s+(?:immagin\w+|foto|illustrazion\w+)\b|più (?:immagin\w+|foto) possibil", testo):
        return IMMAGINI_TANTE
    return IMMAGINI_PER_LIVELLO[livello]


def numero_sezioni(caratteri: int, livello: str) -> tuple[int, int]:
    centro = min(30, max(3, round(min(caratteri, 150_000) / DIVISORE_SEZIONI[livello])))
    return max(2, centro - 2), min(30, centro + 2)


def parole_sezione(livello: str, punti: int) -> int:
    basso, alto = PAROLE_LIMITI[livello]
    return max(basso, min(alto, punti * PAROLE_PER_PUNTO[livello]))


def token_per(parole: int) -> int:
    """Lo spazio per la risposta: largo, perché una risposta tagliata a metà non si può usare."""
    return int(parole * 4) + 1200


# --------------------------------------------------------------------------- i punti da sapere


def gruppi_per_mappa(brani: list[dict], massimo: int = CARATTERI_MAPPA_SCHEMA) -> list[list[dict]]:
    """Il materiale a gruppi, nell'ordine, ognuno entro `massimo` caratteri."""
    gruppi: list[list[dict]] = []
    totale = 0
    for b in brani:
        if not gruppi or (gruppi[-1] and totale + len(b["testo"]) > massimo):
            gruppi.append([])
            totale = 0
        gruppi[-1].append(b)
        totale += len(b["testo"])
    return gruppi


def valida_mappa(o: dict) -> list[str]:
    return [] if isinstance(o.get("punti"), list) else ['serve "punti"']


def normalizza_mappa(o: dict, validi: set[str]) -> list[dict]:
    punti = []
    for p in _lista(o.get("punti")):
        if isinstance(p, str):
            testo, fonti = p, []
        elif isinstance(p, dict):
            testo, fonti = p.get("testo"), _lista(p.get("fonti"))
        else:
            continue
        testo = _corto(testo, 220)
        if not testo or NON_PUNTI.match(testo):
            continue
        punti.append({"testo": testo, "fonti": [str(f) for f in fonti if str(f) in validi][:8]})
    return punti


def _chiave(testo: str) -> str:
    t = unicodedata.normalize("NFD", testo.lower())
    return " ".join(re.findall(r"[a-z0-9]+", "".join(c for c in t if not unicodedata.combining(c))))


def unisci_punti(liste: list[list[dict]]) -> list[dict]:
    """I punti di tutti i gruppi: quelli scritti uguali diventano uno, con le fonti di tutti."""
    per_chiave: dict[str, dict] = {}
    uscita = []
    for lista in liste:
        for p in lista:
            k = _chiave(p["testo"])
            if not k:
                continue
            if k in per_chiave:
                q = per_chiave[k]
                q["fonti"] = list(dict.fromkeys(q["fonti"] + p["fonti"]))[:8]
            else:
                q = {"testo": p["testo"], "fonti": list(p["fonti"])}
                per_chiave[k] = q
                uscita.append(q)
    return uscita[:PUNTI_MASSIMI]


def soggetto(testo: str) -> str:
    """Di chi o di che cosa parla un punto: quello che sta prima dei due punti, senza date e articoli.
    "Canossa (1077): Enrico IV si umilia" e "Canossa: tre giorni scalzo" hanno lo stesso soggetto."""
    if ":" not in testo:
        return ""
    parole = _chiave(re.sub(r"\([^)]*\)", " ", testo.split(":", 1)[0])).split()
    while parole and parole[0] in _ARTICOLI:
        parole.pop(0)
    if not parole or len(parole) > 8 or not any(len(p) >= 3 and not p.isdigit() for p in parole):
        return ""
    chiave = " ".join(parole)
    return "" if chiave in _SOGGETTI_GENERICI else chiave


def raggruppa(punti: list[dict]) -> list[dict]:
    """I punti sulla stessa cosa, anche se vengono da documenti diversi, diventano un argomento solo: il piano li
    vede come uno e non li può dividere fra due parti. Ogni argomento sa quali punti contiene ("indici")."""
    aperti: dict[str, dict] = {}
    argomenti: list[dict] = []
    for i, p in enumerate(punti):
        s = soggetto(p["testo"])
        a = aperti.get(s) if s else None
        if a is None or len(a["indici"]) >= ARGOMENTO_MASSIMO:
            a = {"indici": []}
            argomenti.append(a)
            if s:
                aperti[s] = a
        a["indici"].append(i)
    for a in argomenti:
        testi = [punti[i]["testo"] for i in a["indici"]]
        if len(testi) == 1:
            a["testo"] = testi[0]
        else:
            testa = testi[0].split(":", 1)[0].strip()
            a["testo"] = _corto(testa + ": " + "; ".join(t.split(":", 1)[1].strip() for t in testi), 600)
        a["fonti"] = list(dict.fromkeys(f for i in a["indici"] for f in punti[i]["fonti"]))
    return argomenti


def radici(testo: str) -> set[str]:
    """Le parole di un testo ridotte alle prime cinque lettere, senza accenti: "papato" e "papale" sono vicine."""
    t = unicodedata.normalize("NFD", (testo or "").lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return {p[:5] for p in re.findall(r"[a-z0-9]+", t) if len(p) >= 4 or (p.isdigit() and len(p) >= 3)}


def coperto(punto: str, parole_testo: set[str]) -> bool:
    """Senza spendere: un punto è coperto se il testo ha le sue date e quasi tutte le sue parole.
    Quelli con meno di due parole utili li giudica il modello."""
    mie = radici(punto) - _PAROLE_VUOTE
    if len(mie) < 2:
        return False
    if not {p for p in mie if p.isdigit()} <= parole_testo:
        return False
    return len(mie & parole_testo) / len(mie) >= COPERTURA_SOGLIA


def _corto(v, massimo: int = 100) -> str:
    return " ".join(str(v).split())[:massimo] if isinstance(v, (str, int, float)) else ""


def _lista(v) -> list:
    return v if isinstance(v, list) else ([v] if isinstance(v, str) else [])


def _formula(v) -> str:
    return stile.formule_in_testo(" ".join(str(v or "").split()))


def _vocabolario(lettura: dict, altro: tuple = ()) -> set[str]:
    """Le parole che si possono usare per una foto: i documenti e quello che ha scritto lo studente."""
    testo = " ".join([b["testo"] for b in lettura["brani"]] + [lettura.get("argomento") or "", lettura.get("indicazioni") or "", *altro])
    return set(re.findall(r"[a-zàèéìòù]{4,}", testo.lower()))


def _soggetto_nominato(soggetto: str, parole_documenti: set[str]) -> bool:
    """Una foto si cerca solo per cose nominate davvero."""
    importanti = [p for p in re.findall(r"[a-zàèéìòù]{4,}", soggetto.lower()) if p not in ("della", "delle", "dello", "degli", "nella")]
    if not importanti:
        return False
    mancanti = sum(1 for p in importanti if p not in parole_documenti)
    return mancanti == 0 or (len(importanti) > 2 and mancanti == 1)


def foto_valide(lista, n_sezioni: int, parole: set[str]) -> list[dict]:
    foto = []
    for i in _lista(lista):
        if not isinstance(i, dict):
            continue
        soggetto = _corto(i.get("soggetto"), 120)
        if not soggetto or not _soggetto_nominato(soggetto, parole):
            continue
        try:
            sezione = max(1, min(n_sezioni, int(i.get("sezione") or 1)))
        except (TypeError, ValueError):
            sezione = 1
        foto.append({"sezione": sezione, "soggetto": soggetto, "ricerca": _corto(i.get("ricerca"), 120) or soggetto,
                     "didascalia": soggetto})  # solo il nome dell'opera: date e dettagli stanno nel testo, che viene controllato
    return foto


def valida_piano(o: dict) -> list[str]:
    errori = []
    if not isinstance(o.get("titolo"), str) or not o["titolo"].strip():
        errori.append('manca "titolo"')
    sezioni = o.get("sezioni")
    if not isinstance(sezioni, list) or not sezioni:
        errori.append('servono le "sezioni"')
    elif any(not isinstance(s, dict) or not _corto(s.get("titolo")) for s in sezioni):
        errori.append('ogni sezione deve avere il suo "titolo"')
    return errori


def _numero_punto(x, n: int) -> int | None:
    """"P12", "12" e anche "P12. Dictatus papae..." (a volte il modello copia il testo dopo il numero)."""
    m = re.match(r"\s*[Pp]?\s*(\d+)", str(x))
    if m and 1 <= int(m.group(1)) <= n:
        return int(m.group(1)) - 1
    return None


def _forma(o: dict, forma: str) -> str:
    scelta = str(o.get("forma") or "").strip().lower()
    return forma if forma != "auto" else (scelta if scelta in ("capitoli", "schede") else "capitoli")


def assegna(o: dict, punti: list[dict], argomenti: list[dict], forma: str, massimo: int) -> list[dict]:
    """Ogni argomento in una parte sola (i numeri P del piano sono gli argomenti di `raggruppa`). Quelli che il
    piano ha dimenticato, e quelli delle parti da uno o due punti in uno schema lungo, vanno nella parte che usa
    gli stessi documenti (a pari merito, in quella dei punti vicini). Le parti restano nell'ordine del piano,
    anche quelle rimaste vuote, così i numeri delle foto valgono ancora."""
    n = len(argomenti)
    di_chi: dict[int, int] = {}
    grezze: list[dict] = []
    for s in o["sezioni"][:massimo]:
        indici = []
        for x in _lista(s.get("punti")):
            a = _numero_punto(x, n)
            if a is not None and a not in di_chi:
                di_chi[a] = len(grezze)
                indici.append(a)
        grezze.append({"titolo": stile.ripulisci(_corto(s["titolo"])), "argomenti": indici})

    def quanti(g: dict) -> int:
        return sum(len(argomenti[a]["indici"]) for a in g["argomenti"])

    # Una parte con uno o due punti in uno schema dove le altre ne hanno tanti è un avanzo messo in fondo:
    # i suoi punti vanno nella parte dove stanno meglio (in forma schede no: lì una scheda può essere corta).
    piene = [g for g in grezze if g["argomenti"]]
    media = len(punti) / max(1, len(piene))
    if forma != "schede":
        for g in piene:
            if quanti(g) <= 2 and quanti(g) < media / 4:
                for a in g["argomenti"]:
                    del di_chi[a]
                g["argomenti"], g["tolta"] = [], True
    fonti_parti = [{f for a in g["argomenti"] for f in argomenti[a]["fonti"]} for g in grezze]
    possibili = [k for k, g in enumerate(grezze) if not g.get("tolta")]

    def posto(a: int) -> int:
        return argomenti[a]["indici"][0]

    def distanza(a: int, k: int) -> int:
        return min((abs(posto(a) - posto(b)) for b in grezze[k]["argomenti"]), default=10 ** 6)

    for a in range(n):
        if a in di_chi:
            continue
        mie = set(argomenti[a]["fonti"])
        k = max(possibili, key=lambda k: (len(mie & fonti_parti[k]), -distanza(a, k)))
        grezze[k]["argomenti"].append(a)
        di_chi[a] = k
        fonti_parti[k] |= mie
    return grezze


def _visibili(grezze: list[dict]) -> list[int]:
    """Le parti che hanno dei punti, nell'ordine: la S1 di chi rilegge il piano è grezze[_visibili(grezze)[0]]."""
    return [k for k, g in enumerate(grezze) if g["argomenti"]]


def piano_in_chiaro(o: dict, punti: list[dict], forma: str, massimo: int) -> str:
    """Il piano come lo legge chi lo controlla: le parti (S1, S2...) con i loro argomenti (P...)."""
    argomenti = raggruppa(punti)
    grezze = assegna(o, punti, argomenti, _forma(o, forma), massimo)
    righe = []
    for s, k in enumerate(_visibili(grezze), start=1):
        righe.append(f"S{s}. {grezze[k]['titolo']}")
        righe += [f"   P{a + 1}. {argomenti[a]['testo']}" for a in grezze[k]["argomenti"]]
    return "\n".join(righe)


def valida_correzioni(o: dict) -> list[str]:
    if all(isinstance(o.get(c, []), list) for c in ("sposta", "unisci", "metti_dopo")):
        return []
    return ['"sposta", "unisci" e "metti_dopo" sono liste, anche vuote']


def correggi(grezze: list[dict], correzioni: dict, n: int) -> tuple[list[int], dict[int, int]]:
    """Le correzioni di chi ha riletto il piano, fatte dal codice: si spostano punti e parti, nessun punto si può
    perdere. Torna l'ordine delle parti (indici di `grezze`) e, per le parti unite, dove sono finite."""
    visibili = _visibili(grezze)

    def parte(x) -> int | None:
        m = re.match(r"\s*[Ss]?\s*(\d+)", str(x))
        return visibili[int(m.group(1)) - 1] if m and 1 <= int(m.group(1)) <= len(visibili) else None

    unita: dict[int, int] = {}

    def viva(k: int) -> int:
        visti = set()
        while k in unita and k not in visti:
            visti.add(k)
            k = unita[k]
        return k

    for c in _lista(correzioni.get("unisci")):
        da, verso = (parte(c.get("sezione")), parte(c.get("in"))) if isinstance(c, dict) else (None, None)
        if da is None or verso is None or viva(da) == viva(verso):
            continue
        da, verso = viva(da), viva(verso)
        grezze[verso]["argomenti"] += grezze[da]["argomenti"]
        grezze[da]["argomenti"] = []
        unita[da] = verso
    di_chi = {a: k for k, g in enumerate(grezze) for a in g["argomenti"]}
    for c in _lista(correzioni.get("sposta")):
        a, verso = (_numero_punto(c.get("punto"), n), parte(c.get("sezione"))) if isinstance(c, dict) else (None, None)
        if a is None or verso is None or a not in di_chi or di_chi[a] == viva(verso):
            continue
        grezze[di_chi[a]]["argomenti"].remove(a)
        di_chi[a] = viva(verso)
        grezze[di_chi[a]]["argomenti"].append(a)
    ordine = list(range(len(grezze)))
    for c in _lista(correzioni.get("metti_dopo")):
        k, dopo = (parte(c.get("sezione")), parte(c.get("dopo"))) if isinstance(c, dict) else (None, None)
        if k is None or dopo is None or viva(k) == viva(dopo):
            continue
        k, dopo = viva(k), viva(dopo)
        ordine.remove(k)
        ordine.insert(ordine.index(dopo) + 1, k)
    return ordine, unita


def normalizza_piano(o: dict, lettura: dict, punti: list[dict], forma: str, massimo: int,
                     correzioni: dict | None = None) -> dict:
    """Il piano pronto da scrivere: ogni punto in una parte sola (vedi `assegna`), le correzioni di chi l'ha
    riletto, le parti vuote tolte."""
    forma = _forma(o, forma)
    argomenti = raggruppa(punti)
    grezze = assegna(o, punti, argomenti, forma, massimo)
    ordine_parti, unita = correggi(grezze, correzioni, len(argomenti)) if correzioni else (list(range(len(grezze))), {})
    ordine = {b["id"]: j for j, b in enumerate(lettura["brani"])}
    sezioni, nuovo_numero = [], {}
    for k in ordine_parti:
        g = grezze[k]
        indici = [i for a in g["argomenti"] for i in argomenti[a]["indici"]]
        if not indici:
            continue
        nuovo_numero[k] = len(sezioni) + 1
        conta = Counter(f for i in indici for f in punti[i]["fonti"])
        sezioni.append({"titolo": g["titolo"], "concetti": [punti[i]["testo"] for i in indici],
                        "fonti_punti": [punti[i]["fonti"] for i in indici],
                        "fonti": sorted(conta, key=lambda f: (-conta[f], ordine.get(f, 0)))[:FONTI_PER_SEZIONE]})
    immagini = []
    for f in _lista(o.get("immagini")):
        if not isinstance(f, dict):
            continue
        try:
            k = max(1, int(f.get("sezione") or 1)) - 1
        except (TypeError, ValueError):
            k = 0
        visti = set()
        while k in unita and k not in visti:  # la foto segue la sua parte quando viene unita a un'altra
            visti.add(k)
            k = unita[k]
        # Una foto di una parte tolta va nella prima parte dopo (nell'ordine del piano) che c'è ancora.
        dopo = nuovo_numero.get(k) or next((nuovo_numero[j] for j in range(k, len(grezze)) if j in nuovo_numero), len(sezioni))
        immagini.append({**f, "sezione": dopo})
    concetti = [p["testo"] for p in punti]
    titoli = tuple(x["titolo"] for x in sezioni) + tuple(concetti)
    foto = foto_valide(immagini, len(sezioni), _vocabolario(lettura, titoli))
    return {"versione": 2, "titolo": stile.ripulisci(_corto(o["titolo"], 120)), "forma": forma, "concetti": concetti,
            "sezioni": sezioni, "immagini": foto}


def valida_sezione(o: dict) -> list[str]:
    blocchi = o.get("blocchi")
    if not isinstance(blocchi, list) or not blocchi or any(not isinstance(b, dict) for b in blocchi):
        return ['servono i "blocchi", ognuno con il suo "tipo"']
    return []


def normalizza_sezione(o: dict, validi: set[str], forma: str) -> dict:
    blocchi = []
    for b in o.get("blocchi") or []:
        if not isinstance(b, dict) or b.get("tipo") not in TIPI_BLOCCO:
            continue
        tipo, fonti = b["tipo"], [str(f) for f in _lista(b.get("fonti")) if str(f) in validi][:8]
        comuni = {"tipo": tipo, "fonti": fonti}
        titolo = stile.ripulisci(_corto(b.get("titolo"))) if tipo in ("paragrafo", "punti", "passaggi", "tabella") and b.get("titolo") else None
        if tipo in ("paragrafo", "prof") and _formula(b.get("testo")):
            blocchi.append({**comuni, **({"titolo": titolo} if tipo == "paragrafo" else {}), "testo": _formula(b["testo"])})
        elif tipo == "definizione" and _formula(b.get("testo")) and _corto(b.get("termine")):
            blocchi.append({**comuni, "termine": _formula(b["termine"]).replace("**", "")[:100], "testo": _formula(b["testo"])})
        elif tipo in ("punti", "passaggi"):
            voci = [_formula(v) for v in _lista(b.get("voci")) if _formula(v)]
            if voci:
                blocchi.append({**comuni, "titolo": titolo, "voci": voci})
        elif tipo == "tabella":
            righe = [[_formula(c) for c in r] for r in _lista(b.get("righe")) if isinstance(r, list) and any(_formula(c) for c in r)]
            if righe:
                blocchi.append({**comuni, "titolo": titolo, "intestazione": [_corto(c, 60) for c in _lista(b.get("intestazione"))], "righe": righe})
    campi = [{"nome": _corto(c.get("nome"), 40), "valore": _formula(c.get("valore"))} for c in _lista(o.get("campi"))
             if isinstance(c, dict) and _corto(c.get("nome")) and _formula(c.get("valore"))] if forma == "schede" else []
    carte = [{"domanda": _formula(c.get("domanda")), "risposta": _formula(c.get("risposta"))} for c in _lista(o.get("carte"))
             if isinstance(c, dict) and _formula(c.get("domanda")) and _formula(c.get("risposta"))][:6]
    return {"campi": campi, "blocchi": blocchi, "carte": carte}


def testo_di(sezioni: list[dict]) -> str:
    parti = []
    for s in sezioni:
        parti.append(s["titolo"])
        parti += [c["valore"] for c in s.get("campi") or []]
        for b in s.get("blocchi") or []:
            parti += [str(x) for x in (b.get("termine"), b.get("testo")) if x] + list(b.get("voci") or [])
            parti += [" | ".join(r) for r in b.get("righe") or []]
    return "\n".join(parti)


class PreparazioneSchema:
    def __init__(self, ctx, schema_id: int):
        self.ctx = ctx
        self.id = schema_id
        self.c = Cartella(cartella(ctx.settings.dati_dir, schema_id))
        self.riferimento = f"schema:{schema_id}"
        with db.aperta(ctx.settings.db_path) as conn:
            r = conn.execute("SELECT s.*, m.nome AS materia_nome, m.prof AS materia_prof FROM schemi s JOIN materie m"
                             " ON m.id = s.materia_id WHERE s.id = ?", (schema_id,)).fetchone()
        if r is None:
            raise Annullata()
        self.riga = dict(r)

    def controlla(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM schemi WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def avanza(self, fase: str, fatto: int = 0, totale: int = 0, frase: str | None = None) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            conn.execute("UPDATE schemi SET fase = ?, fatto = ?, totale = ?, frase = ? WHERE id = ? AND stato = 'in_preparazione'",
                         (fase, fatto, totale, frase or FRASI[fase], self.id))

    def _chiedi(self, compito: str, messaggi: list[dict], modello: str, valida, max_token: int, cosa: str, dati: dict) -> dict:
        return ai.chiedi_json(cassa=self.ctx.cassa, fornitore=self.ctx.testo, compito=compito, messaggi=messaggi, modello=modello,
                              valida=valida, max_token=max_token, cosa=cosa, dati=dati, materia_id=self.riga["materia_id"],
                              prima_di_ogni_chiamata=self.controlla, riferimento=self.riferimento)

    def _cerca(self, domanda: str, brani: list[dict], massimo: int, gia: list[str] | None = None) -> list[dict]:
        return memoria.scegli(self.ctx, domanda, brani, massimo, gia, materia_id=self.riga["materia_id"], prima=self.controlla)

    def esegui(self) -> None:
        for fase in FASI:
            self.controlla()
            self.avanza(fase)
            getattr(self, f"_{fase}")()

    def _lettura(self) -> None:
        if self.c.leggi("lettura.json"):
            return
        ristretti = json.loads(self.riga["materiali"] or "[]")
        materia_id = self.riga["materia_id"]
        with db.aperta(self.ctx.settings.db_path) as conn:
            memoria.assicura(conn, materia_id, self.ctx.settings.dati_dir)
            brani, _ = memoria.brani_di(conn, materia_id, ristretti or None)
            scheda = schede.per_lezione(conn, materia_id)
            evento = None
            if self.riga["evento_id"]:
                e = conn.execute("SELECT * FROM eventi WHERE id = ?", (self.riga["evento_id"],)).fetchone()
                if e:
                    giorno = date.fromisoformat(e["data"])
                    evento = {"tipo": e["tipo"], "data": e["data"], "argomento": e["argomento"],
                              "data_in_parole": f"{giorno.day} {clock.MESI[giorno.month - 1]}"}
        if not brani:
            raise ErroreLezione("Nel materiale scelto non ho trovato testo da usare: aggiungi il libro, gli appunti o le verifiche, poi riprova.")
        brani = memoria.senza_doppioni(brani)  # la stessa pagina fotografata due volte si legge una volta
        self.c.scrivi("lettura.json", {"argomento": self.riga["argomento"], "indicazioni": self.riga["indicazioni"],
                                       "materia": {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]},
                                       "evento": evento, "scheda": scheda, "brani": brani})

    def _mappa(self) -> None:
        """Il modello piccolo legge tutto il materiale, un gruppo alla volta, e segna i punti da sapere."""
        if self.c.leggi("mappa.json") is not None or self.c.leggi("piano.json") is not None:
            return  # un piano fatto prima della 1.3.1 (Riprova di uno schema vecchio) resta quello
        lettura = self.c.leggi("lettura.json")
        gruppi = gruppi_per_mappa(lettura["brani"])
        liste = []
        for k, gruppo in enumerate(gruppi, start=1):
            fatto = self.c.leggi(f"mappa/{k:02d}.json")
            if fatto is None:
                tanti = len(gruppi) > 1
                if tanti:
                    self.avanza("mappa", k - 1, len(gruppi), f"Segno i punti da sapere, {k} di {len(gruppi)}")
                o = self._chiedi("schema_mappa", prompt_studio.messaggi_schema_mappa(lettura, gruppo), self.ctx.settings.modello_piano,
                                 valida_mappa, TOKEN_MAPPA, f"Schema · punti da sapere {k} di {len(gruppi)}" if tanti else "Schema · punti da sapere",
                                 {"brani": gruppo})
                fatto = normalizza_mappa(o, {b["id"] for b in gruppo})
                self.c.scrivi(f"mappa/{k:02d}.json", fatto)
            liste.append(fatto)
        punti = unisci_punti(liste)
        if not punti:
            raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza su questo argomento per farci uno schema.")
        self.c.scrivi("mappa.json", {"punti": punti})

    def _piano(self) -> None:
        if self.c.leggi("piano.json"):
            return
        lettura, punti = self.c.leggi("lettura.json"), self.c.leggi("mappa.json")["punti"]
        argomenti = raggruppa(punti)  # il piano vede un numero per argomento: i punti sulla stessa cosa restano insieme
        livello, forma = self.riga["livello"], self.riga["forma"]
        minimo, massimo = numero_sezioni(sum(len(b["testo"]) for b in lettura["brani"]), livello)
        minimo, massimo = max(1, min(minimo, len(argomenti))), max(1, min(massimo, len(argomenti)))
        immagini = quante_immagini(livello, lettura.get("indicazioni"))
        primo = self.c.leggi("piano-primo.json")
        if primo is None:
            primo = self._chiedi("schema_piano", prompt_studio.messaggi_schema_piano(
                lettura, [a["testo"] for a in argomenti], forma, livello, minimo, massimo, immagini),
                self.ctx.settings.modello_scrittura, valida_piano, TOKEN_PIANO, "Schema · piano",
                {"lettura": lettura, "punti": argomenti, "forma": forma, "massimo": massimo})
            self.c.scrivi("piano-primo.json", primo)
        # Un piano lungo fatto in una volta lascia punti fuori tema e parti di avanzi in fondo: lo rilegge il
        # modello stesso e scrive solo le correzioni (sposta, unisci, metti dopo), che fa il codice: riscritto
        # per intero, un piano lungo perde dei punti. Se il controllo va male resta il primo piano.
        self.avanza("piano", frase="Controllo l'ordine dello schema")
        correzioni = self.c.leggi("piano-correzioni.json")
        if correzioni is None:
            try:
                correzioni = self._chiedi("schema_piano_rivisto", prompt_studio.messaggi_schema_piano_rivisto(
                    lettura, piano_in_chiaro(primo, punti, forma, massimo), len(argomenti), livello),
                    self.ctx.settings.modello_scrittura, valida_correzioni, TOKEN_CORREZIONI, "Schema · controllo del piano",
                    {"piano": primo})
            except RispostaNonUsabile:
                correzioni = {}
            self.c.scrivi("piano-correzioni.json", correzioni)
        self.c.scrivi("piano.json", normalizza_piano(primo, lettura, punti, forma, massimo, correzioni))

    def _scrivi_sezione(self, k: int, sezione: dict, lettura: dict, piano: dict) -> dict:
        """Una parte scritta, controllata e messa in forma."""
        cerca = " ".join([sezione["titolo"], *sezione["concetti"]])
        brani = self._cerca(cerca, lettura["brani"], CARATTERI_SEZIONE, sezione["fonti"])
        parole = parole_sezione(self.riga["livello"], len(sezione["concetti"]))
        messaggi = prompt_studio.messaggi_schema_sezione(lettura, piano, k, sezione, brani, parole, piano["forma"])
        modello = self.ctx.settings.modello_parti
        max_token = token_per(parole)
        cosa = f"Schema · parte {k}"
        dati = {"lettura": lettura, "sezione": sezione, "brani": brani, "forma": piano["forma"]}
        grezzo = self.c.leggi(f"sezioni/{k:02d}.grezzo.json")
        if grezzo is None:
            grezzo = self._chiedi("schema_sezione", messaggi, modello, valida_sezione, max_token, cosa, dati)
            self.c.scrivi(f"sezioni/{k:02d}.grezzo.json", grezzo)
        riveduto = controllo.rivedi(self.ctx, grezzo, brani, compito="schema_sezione", messaggi=messaggi, modello=modello,
                                    valida=valida_sezione, max_token=max_token, cosa=cosa, materia_id=self.riga["materia_id"],
                                    prima=self.controlla, dati=dati, parlato=False, riferimento=self.riferimento)
        uscita = {"titolo": sezione["titolo"], **normalizza_sezione(riveduto, {b["id"] for b in brani}, piano["forma"])}
        self.c.scrivi(f"sezioni/{k:02d}.json", uscita)
        return uscita

    def _scrittura(self) -> None:
        lettura, piano = self.c.leggi("lettura.json"), self.c.leggi("piano.json")
        n = len(piano["sezioni"])
        for k, sezione in enumerate(piano["sezioni"], start=1):
            self.avanza("scrittura", k - 1, n, f"Scrivo la parte {k} di {n}")
            if self.c.leggi(f"sezioni/{k:02d}.json") is None:
                self._scrivi_sezione(k, sezione, lettura, piano)
        self.avanza("scrittura", n, n)

    def _scritte(self, piano: dict, extra: bool = False) -> list[dict]:
        """Le parti scritte, ognuna con in fondo quello che le è stato aggiunto dopo il controllo.
        `extra`: la parte "Altri punti da sapere" degli schemi fatti prima della 1.3.1."""
        n = len(piano["sezioni"]) + (1 if extra else 0)
        uscita = []
        for k in range(1, n + 1):
            s = self.c.leggi(f"sezioni/{k:02d}.json")
            if not s:
                continue
            aggiunta = self.c.leggi(f"sezioni/{k:02d}.aggiunta.json")
            if aggiunta and aggiunta.get("blocchi"):
                s = {**s, "blocchi": s["blocchi"] + aggiunta["blocchi"], "carte": (s.get("carte") or []) + (aggiunta.get("carte") or [])}
            if s.get("blocchi"):
                uscita.append(s)
        return uscita

    def _copertura(self) -> None:
        """Ogni punto segnato nei documenti deve stare nello schema. Prima un confronto di parole che non costa
        niente; i dubbi li guarda il modello piccolo; quelli che mancano davvero si aggiungono nella loro parte."""
        if self.c.leggi("copertura.json") is not None:
            return
        lettura, piano = self.c.leggi("lettura.json"), self.c.leggi("piano.json")
        sezioni = self._scritte(piano)
        if not sezioni:
            raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza su questo argomento per farci uno schema.")
        testo = testo_di(sezioni)
        parole_testo = radici(testo)
        dubbi = [(k, c) for k, s in enumerate(piano["sezioni"], start=1) for c in s.get("concetti") or [] if not coperto(c, parole_testo)]
        esame = self.c.leggi("copertura-esame.json")
        if esame is None:
            detti: list[str] = []
            elenco = list(dict.fromkeys(c for _, c in dubbi))[:200]
            if elenco:
                def valida(x: dict) -> list[str]:
                    return [] if isinstance(x.get("mancanti"), list) else ['serve "mancanti"']
                o = self._chiedi("copertura", prompt_studio.messaggi_copertura(elenco, testo[:90_000]), self.ctx.settings.modello_piano,
                                 valida, 3000, "Schema · controllo di quello che manca", {"concetti": elenco})
                detti = [elenco[i] for i in (_numero_punto(str(x).lstrip("Cc"), len(elenco)) for x in o["mancanti"]) if i is not None]
            esame = {"mancanti": detti}
            self.c.scrivi("copertura-esame.json", esame)
        detti_set = set(esame["mancanti"])
        per_parte: dict[int, list[str]] = {}
        for k, c in dubbi:
            if c in detti_set and c not in per_parte.get(k, []):
                per_parte.setdefault(k, []).append(c)
        rimasti: list[str] = []
        for j, (k, concetti) in enumerate(sorted(per_parte.items()), start=1):
            self.avanza("copertura", j - 1, len(per_parte), "Aggiungo quello che mancava")
            if not self._aggiungi(k, concetti, lettura, piano).get("blocchi"):
                rimasti += concetti
        tabella = None
        if piano["forma"] == "capitoli" and self.riga["livello"] != "essenziale":
            tabella = self._tabella(lettura, self._scritte(piano))
        self.c.scrivi("copertura.json", {"concetti": len(piano["concetti"]), "coperti": len(piano["concetti"]) - len(rimasti),
                                         "mancanti": rimasti[:40], "extra": False, "tabella": tabella})

    def _aggiungi(self, k: int, concetti: list[str], lettura: dict, piano: dict) -> dict:
        """I blocchi che mancavano alla parte k, scritti dai documenti di quei punti e messi in fondo alla parte."""
        fatto = self.c.leggi(f"sezioni/{k:02d}.aggiunta.json")
        if fatto is not None:
            return fatto
        sezione = piano["sezioni"][k - 1]
        da_punto = dict(zip(sezione.get("concetti") or [], sezione.get("fonti_punti") or []))
        fonti = list(dict.fromkeys([f for c in concetti for f in da_punto.get(c, [])] + list(sezione.get("fonti") or [])))
        brani = self._cerca(" ".join(concetti), lettura["brani"], CARATTERI_SEZIONE, fonti)
        scritta = self.c.leggi(f"sezioni/{k:02d}.json") or {"blocchi": []}
        parole = parole_sezione(self.riga["livello"], len(concetti))
        messaggi = prompt_studio.messaggi_schema_aggiunta(lettura, piano, k, sezione, concetti,
                                                          testo_di([{"titolo": sezione["titolo"], **scritta}]), brani, parole)
        modello = self.ctx.settings.modello_parti
        max_token = token_per(parole)
        cosa = f"Schema · aggiunta alla parte {k}"
        dati = {"lettura": lettura, "sezione": {**sezione, "concetti": concetti}, "brani": brani, "forma": piano["forma"]}

        def valida(o: dict) -> list[str]:  # anche niente va bene: vuol dire che i documenti non dicono di più
            blocchi = o.get("blocchi")
            return [] if isinstance(blocchi, list) and all(isinstance(b, dict) for b in blocchi) else ['servono i "blocchi"']

        try:
            grezzo = self.c.leggi(f"sezioni/{k:02d}.aggiunta.grezzo.json")
            if grezzo is None:
                grezzo = self._chiedi("schema_sezione", messaggi, modello, valida, max_token, cosa, dati)
                self.c.scrivi(f"sezioni/{k:02d}.aggiunta.grezzo.json", grezzo)
            riveduto = grezzo if not grezzo.get("blocchi") else controllo.rivedi(
                self.ctx, grezzo, brani, compito="schema_sezione", messaggi=messaggi, modello=modello, valida=valida,
                max_token=max_token, cosa=cosa, materia_id=self.riga["materia_id"], prima=self.controlla, dati=dati,
                parlato=False, riferimento=self.riferimento)
        except RispostaNonUsabile:
            riveduto = {"blocchi": []}  # un'aggiunta andata male non ferma lo schema: il punto resta tra i mancanti
        uscita = normalizza_sezione(riveduto, {b["id"] for b in brani}, piano["forma"])
        uscita = {"blocchi": uscita["blocchi"], "carte": uscita["carte"][:2]}
        self.c.scrivi(f"sezioni/{k:02d}.aggiunta.json", uscita)
        return uscita

    def _tabella(self, lettura: dict, sezioni: list[dict]) -> dict | None:
        testo = testo_di(sezioni)[:40_000]
        modello = self.ctx.settings.modello_parti
        def valida(o: dict) -> list[str]:
            return [] if isinstance(o.get("righe"), list) and o["righe"] else ['servono le "righe"']
        messaggi = prompt_studio.messaggi_schema_tabella(lettura, testo)
        dati = {"sezioni": [{"titolo": s["titolo"], "primo": next((b.get("testo") for b in s["blocchi"] if b.get("testo")), "")} for s in sezioni]}
        try:
            grezzo = self._chiedi("schema_tabella", messaggi, modello, valida, TOKEN_TABELLA, "Schema · quadro d'insieme", dati)
            riveduto = controllo.rivedi(self.ctx, grezzo, [], compito="schema_tabella", messaggi=messaggi, modello=modello, valida=valida,
                                        max_token=TOKEN_TABELLA, cosa="Schema · quadro d'insieme", altro=testo, materia_id=self.riga["materia_id"],
                                        prima=self.controlla, dati=dati, parlato=False, riferimento=self.riferimento)
        except RispostaNonUsabile:
            # Il quadro è un di più: se non viene, lo schema esce lo stesso, senza (prima finiva tutto in errore).
            log.warning("Schema %s: quadro d'insieme non riuscito, esce senza", self.id)
            return None
        righe = [[_formula(c) for c in r] for r in riveduto.get("righe") or [] if isinstance(r, list)]
        if not righe:
            return None
        return {"titolo": _corto(riveduto.get("titolo")) or "Quadro d'insieme", "campi": [], "carte": [],
                "blocchi": [{"tipo": "tabella", "fonti": [], "titolo": None, "intestazione": [_corto(c, 60) for c in _lista(riveduto.get("intestazione"))],
                             "righe": righe}]}

    def _altri_soggetti(self, piano: dict) -> list[dict]:
        """Il piano non ne ha indicate: le foto sono obbligatorie, quindi si chiede apposta."""
        lettura = self.c.leggi("lettura.json")
        voluti = quante_immagini(self.riga["livello"], self.riga["indicazioni"])
        o = self._chiedi("schema_immagini", prompt_studio.messaggi_schema_immagini(piano, lettura["argomento"], voluti),
                         self.ctx.settings.modello_piano, lambda x: [] if isinstance(x.get("immagini"), list) else ['serve "immagini"'],
                         700, "Schema · foto da cercare", {})
        altro = tuple(x["titolo"] for x in piano["sezioni"]) + tuple(piano["concetti"])
        return foto_valide(o["immagini"], len(piano["sezioni"]), _vocabolario(lettura, altro))

    def _immagini(self) -> None:
        stato = self.c.leggi("immagini.json") or {"foto": [], "candidati": None, "avvisi": []}
        piano = self.c.leggi("piano.json")
        voluti = quante_immagini(self.riga["livello"], self.riga["indicazioni"])
        if stato["candidati"] is None:
            stato["candidati"] = list(piano["immagini"])
            if not stato["candidati"] and voluti and self.ctx.foto is not None:
                stato["candidati"], stato["altri"] = self._altri_soggetti(piano), True
        if voluti == 0:
            stato["candidati"] = []
        if self.ctx.foto is None:
            if voluti:
                stato["avvisi"] = ["In modalità prova non cerco le foto."]
            stato["candidati"] = []
        cartella_foto = self.c.file("immagini")
        cartella_foto.mkdir(parents=True, exist_ok=True)
        cercati = 0
        while len(stato["foto"]) < voluti:
            if not stato["candidati"]:
                # Le foto sono obbligatorie: se di quelle del piano non se n'è trovata nessuna, si chiede apposta, una volta.
                if stato["foto"] or stato.get("altri") or not stato.get("provate"):
                    break
                stato["candidati"], stato["altri"] = self._altri_soggetti(piano), True
                self.c.scrivi("immagini.json", stato)
                continue
            self.avanza("immagini", len(stato["foto"]), voluti, f"Cerco le foto, {len(stato['foto']) + 1} di {voluti}")
            voce = stato["candidati"].pop(0)
            cercati += 1
            stato["provate"] = True
            escluse = {f["url"] for f in stato["foto"]}  # la stessa foto non entra due volte
            trovata = immagini.trova_foto(self.ctx, voce["soggetto"], voce["ricerca"], escluse, materia_id=self.riga["materia_id"],
                                          riferimento=self.riferimento, prima=self.controlla)
            if trovata is not None:
                n = len(stato["foto"]) + 1
                (cartella_foto / f"{n}.jpg").write_bytes(trovata["jpeg"])
                stato["foto"].append({"n": n, **voce, "credito": trovata["credito"], "url": trovata["url"], "provate": sorted(escluse)})
            self.c.scrivi("immagini.json", stato)
        if self.ctx.foto is not None and len(stato["foto"]) < voluti:
            if not stato["foto"] and not cercati and not stato.get("provate"):
                stato["avvisi"] = ["Su questo argomento non c'è niente da fotografare: lo schema non ha foto."]
            else:
                quante = len(stato["foto"])
                stato["avvisi"] = ["Non ho trovato foto sicure per questo schema." if quante == 0
                                   else f"Ho trovato solo {quante} {'foto sicura' if quante == 1 else 'foto sicure'} su {voluti}."]
        self.c.scrivi("immagini.json", stato)

    def _chiusura(self) -> None:
        lettura, piano = self.c.leggi("lettura.json"), self.c.leggi("piano.json")
        copertura, foto = self.c.leggi("copertura.json"), self.c.leggi("immagini.json")
        sezioni = self._scritte(piano, bool(copertura.get("extra")))
        if copertura.get("tabella"):
            sezioni.append(copertura["tabella"])
        gia_messe: dict[int, int] = {}
        for f in foto["foto"]:
            indice = max(0, min(len(sezioni) - 1, f["sezione"] - 1))
            blocchi = sezioni[indice]["blocchi"]
            prima = gia_messe.get(indice, 0)
            gia_messe[indice] = prima + 1
            # In ordine, con il testo in mezzo quando ce n'è: non tutte ammucchiate nello stesso punto.
            blocchi.insert(min(1 + 2 * prima, len(blocchi)), {"tipo": "immagine", "n": f["n"], "didascalia": f["didascalia"], "credito": f["credito"]})
        usate: dict[str, dict] = {}
        per_id = {b["id"]: b for b in lettura["brani"]}
        for s in sezioni:
            for b in s["blocchi"]:
                for i in b.get("fonti") or []:
                    if i in per_id:
                        usate[i] = memoria.fonte(per_id[i])
        contenuto = {"forma": piano["forma"],
                     "sezioni": [{"n": n, "titolo": s["titolo"], "campi": s.get("campi") or [], "blocchi": s["blocchi"]}
                                 for n, s in enumerate(sezioni, start=1)],
                     "fonti": usate,
                     "copertura": {k: copertura[k] for k in ("concetti", "coperti", "mancanti")},
                     "avvisi": foto.get("avvisi") or []}
        nota = "Schema di prova: testo preso pari pari dai documenti. Serve la chiave Mistral per quello vero." if not self.ctx.testo.a_pagamento else None
        materia_id = self.riga["materia_id"]
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            r = conn.execute("SELECT stato FROM schemi WHERE id = ?", (self.id,)).fetchone()
            if r is None or r["stato"] != "in_preparazione":
                raise Annullata()
            carte = 0
            for n, s in enumerate(sezioni, start=1):
                ids = [i for b in s["blocchi"] for i in b.get("fonti") or []]
                fonti = memoria.fonti_di(ids, lettura["brani"])
                for i, c in enumerate(s.get("carte") or [], start=1):
                    carte += int(lampo.aggiungi(conn, materia_id, "schema", f"schema:{self.id}:{n}:{i}", c["domanda"], c["risposta"], fonti))
            conn.execute("UPDATE schemi SET stato = 'pronto', fase = NULL, fatto = 0, totale = 0, frase = NULL, errore = NULL, nota = ?, titolo = ?,"
                         " contenuto = ?, carte = ?, pronto_il = ? WHERE id = ?",
                         (nota, piano["titolo"], json.dumps(contenuto, ensure_ascii=False), carte, clock.adesso(), self.id))
            db.aggiungi_novita(conn, "schema_pronto", f"Lo schema di {self.riga['materia_nome']} è pronto", materia_id=materia_id)


def cartella(dati_dir: str, schema_id: int) -> Path:
    return Path(dati_dir) / "schemi" / str(schema_id)


def in_errore(db_path: str, schema_id: int, frase: str) -> None:
    with db.aperta(db_path) as conn, db.transazione(conn):
        r = conn.execute("SELECT s.materia_id, m.nome FROM schemi s JOIN materie m ON m.id = s.materia_id WHERE s.id = ? AND s.stato = 'in_preparazione'",
                         (schema_id,)).fetchone()
        if r is None:
            return
        conn.execute("UPDATE schemi SET stato = 'errore', errore = ? WHERE id = ?", (frase, schema_id))
        db.aggiungi_novita(conn, "schema_errore", f"Lo schema di {r['nome']} si è fermato: tocca Riprova", materia_id=r["materia_id"])


def per_api(r: sqlite3.Row, completo: bool = False) -> dict:
    return {
        "id": r["id"], "materia_id": r["materia_id"], "evento_id": r["evento_id"], "argomento": r["argomento"],
        "titolo": r["titolo"] or r["argomento"], "forma": r["forma"], "livello": r["livello"], "indicazioni": r["indicazioni"],
        "materiali": json.loads(r["materiali"] or "[]"), "stato": r["stato"], "fase": r["fase"], "fatto": r["fatto"], "totale": r["totale"],
        "frase": r["frase"], "errore": r["errore"], "nota": r["nota"], "stima_eur": r["stima_eur"], "creato_il": clock.per_utente(r["creato_il"]),
        "pronto_il": clock.per_utente(r["pronto_il"]) if r["pronto_il"] else None, "carte": r["carte"],
        "contenuto": json.loads(r["contenuto"]) if completo and r["contenuto"] else None,
    }


# --------------------------------------------------------------------------- le foto, dopo

def _salva_contenuto(conn: sqlite3.Connection, schema_id: int, contenuto: dict) -> None:
    conn.execute("UPDATE schemi SET contenuto = ? WHERE id = ?", (json.dumps(contenuto, ensure_ascii=False), schema_id))


def cambia_foto(ctx, riga: sqlite3.Row, n: int) -> bool:
    """Un'altra foto coerente per lo stesso soggetto. False se non ce ne sono altre sicure."""
    c = Cartella(cartella(ctx.settings.dati_dir, riga["id"]))
    stato = c.leggi("immagini.json") or {"foto": []}
    voce = next((f for f in stato["foto"] if f["n"] == n), None)
    if voce is None:
        return False
    escluse = set(voce.get("provate") or [])
    trovata = immagini.trova_foto(ctx, voce["soggetto"], voce["ricerca"], escluse, materia_id=riga["materia_id"], riferimento=f"schema:{riga['id']}")
    voce["provate"] = sorted(escluse)
    if trovata is None:
        c.scrivi("immagini.json", stato)
        return False
    c.file(f"immagini/{n}.jpg").write_bytes(trovata["jpeg"])
    voce.update(url=trovata["url"], credito=trovata["credito"])
    c.scrivi("immagini.json", stato)
    contenuto = json.loads(riga["contenuto"])
    for s in contenuto["sezioni"]:
        for b in s["blocchi"]:
            if b.get("tipo") == "immagine" and b.get("n") == n:
                b["credito"] = trovata["credito"]
    with db.aperta(ctx.settings.db_path) as conn:
        _salva_contenuto(conn, riga["id"], contenuto)
    return True


def togli_foto(ctx, conn: sqlite3.Connection, riga: sqlite3.Row, n: int) -> bool:
    contenuto = json.loads(riga["contenuto"] or "{}")
    trovata = False
    for s in contenuto.get("sezioni") or []:
        rimasti = [b for b in s["blocchi"] if not (b.get("tipo") == "immagine" and b.get("n") == n)]
        trovata = trovata or len(rimasti) != len(s["blocchi"])
        s["blocchi"] = rimasti
    if not trovata:
        return False
    _salva_contenuto(conn, riga["id"], contenuto)
    c = Cartella(cartella(ctx.settings.dati_dir, riga["id"]))
    try:
        c.file(f"immagini/{n}.jpg").unlink()
    except OSError:
        pass
    return True


def cancella_file(dati_dir: str, schema_id: int) -> None:
    shutil.rmtree(cartella(dati_dir, schema_id), ignore_errors=True)
