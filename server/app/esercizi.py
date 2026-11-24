"""Gli esercizi con i numeri della verifica di prova (contratto 21.1).

Richiesta di Andrea del 23/11/2026: per matematica e fisica gli esercizi hanno SEMPRE numeri nuovi ("altrimenti diventa
troppo facile") e la soluzione la calcola un programma, non l'AI. Il modello scrive il testo con i numeri nuovi, il metodo
e il "calcolo" (dati e formula, oppure l'equazione, la disequazione, il sistema o l'espressione); `calcolo.py` trova il
risultato, e lo stesso programma alla correzione confronta i risultati scritti dallo studente."""

import logging
import re
from fractions import Fraction

from . import calcolo

log = logging.getLogger("uvicorn.error")

MATERIE_CON_I_NUMERI = re.compile(r"matemat|fisic|chimic|algebr|geometr|statistic|aritmet|trigonometr", re.I)
TIPI_CHIUSI = ("vero_falso", "scelta_multipla", "collegamento", "traduzione", "date")
_CHIEDE_CONTI = re.compile(r"\b(?:calcol|arrotond|risolv|determin|trova|convert|semplific|scompon|ricava|esprimi|"
                           r"quanto (?:vale|misura|è|pesa|dura))", re.I)
_ARROTONDA = re.compile(r"arrotond|approssim|cifr[ae] significativ", re.I)
_NOME = re.compile(r"[A-Za-zΔα-ωΑ-Ω][A-Za-z0-9_]{0,15}")
TIPI_CALCOLO = ("valore", "equazione", "disequazione", "sistema", "espressione")
RICHIESTE_MASSIME = 4
PUNTI_FUNZIONE = 61


class EsercizioRotto(ValueError):
    """Un esercizio che il programma non sa risolvere o che non torna con il suo testo: la frase va al modello."""


def con_i_numeri(nome_materia: str | None) -> bool:
    return bool(MATERIE_CON_I_NUMERI.search(str(nome_materia or "")))


def _pulito(x, massimo: int) -> str:
    return " ".join(str(x or "").split())[:massimo]


def chiede_conti(d: dict, numeri: bool) -> bool:
    """Una domanda che va risolta con un conto: senza "calcolo" non può entrare."""
    if isinstance(d.get("calcolo"), dict):
        return True
    tipo = str(d.get("tipo") or "").strip().lower().replace(" ", "_")
    testo = str(d.get("testo") or "")
    if tipo in TIPI_CHIUSI:
        return False
    cifre = bool(re.search(r"\d", testo))
    if tipo == "esercizio":
        return cifre or numeri
    return numeri and cifre and bool(_CHIEDE_CONTI.search(testo))


# --------------------------------------------------------------------------- tabella e grafico

def _cella(x) -> str:
    if isinstance(x, (int, float)) and not isinstance(x, bool):
        try:
            return calcolo.scrivi(calcolo.numero(x))
        except calcolo.ErroreCalcolo:
            return ""
    return _pulito(x, 40)


def tabella(o) -> dict | None:
    if not isinstance(o, dict):
        return None
    colonne = [_pulito(c, 40) for c in (o.get("colonne") or o.get("intestazioni") or []) if isinstance(c, (str, int, float))]
    righe = o.get("valori") if isinstance(o.get("valori"), list) else o.get("righe")
    if not 1 <= len(colonne) <= 8 or not isinstance(righe, list) or not 1 <= len(righe) <= 30:
        return None
    valori = []
    for r in righe:
        if not isinstance(r, list) or len(r) != len(colonne):
            return None
        valori.append([_cella(c) for c in r])
    return {"titolo": _pulito(o.get("titolo"), 80) or None, "colonne": colonne, "valori": valori}


def _num(x) -> float | int:
    v = calcolo.numero(x)
    return int(v) if v.denominator == 1 and abs(v) < 10 ** 15 else float(v)


def _campiona(formula: str, da, a) -> list[list]:
    albero = calcolo.leggi(formula, {"x"})
    inizio, fine = calcolo.numero(da), calcolo.numero(a)
    if not inizio < fine or fine - inizio > 10 ** 6:
        raise calcolo.ErroreCalcolo("intervallo del grafico sbagliato")
    punti = []
    for i in range(PUNTI_FUNZIONE):
        x = inizio + (fine - inizio) * Fraction(i, PUNTI_FUNZIONE - 1)
        try:
            y = calcolo.calcola(albero, {"x": x})
        except calcolo.ErroreCalcolo:
            continue
        if abs(float(y)) <= 10 ** 6:
            punti.append([_num(str(float(x))) if x.denominator != 1 else int(x), round(float(y), 6) if not float(y).is_integer() else int(y)])
    if len(punti) < 2:
        raise calcolo.ErroreCalcolo("la funzione del grafico non si disegna")
    return punti


def grafico(o) -> dict | None:
    """Il grafico come lo disegnano le app (contratto 21.1); le funzioni diventano linee di punti."""
    if not isinstance(o, dict):
        return None
    tipo = str(o.get("tipo") or "").strip().lower()
    base = {"titolo": _pulito(o.get("titolo"), 80) or None, "asse_x": _pulito(o.get("asse_x"), 40), "asse_y": _pulito(o.get("asse_y"), 40)}
    try:
        if tipo == "barre":
            categorie = [_pulito(c, 30) for c in o.get("categorie") or []]
            serie = []
            for s in (o.get("serie") or [])[:4]:
                valori = [_num(v) for v in (s.get("valori") or [])] if isinstance(s, dict) else []
                if not categorie or len(valori) != len(categorie):
                    return None
                serie.append({"nome": _pulito(s.get("nome"), 40) or None, "valori": valori})
            return {"tipo": "barre", **base, "categorie": categorie, "serie": serie} if serie and len(categorie) <= 12 else None
        serie = []
        for s in (o.get("serie") or [])[:5]:
            if not isinstance(s, dict) or not isinstance(s.get("punti"), list):
                return None
            punti = [[_num(p[0]), _num(p[1])] for p in s["punti"][:200] if isinstance(p, (list, tuple)) and len(p) == 2]
            if len(punti) != len(s["punti"][:200]) or not punti:
                return None
            serie.append({"nome": _pulito(s.get("nome"), 40) or None, "punti": punti})
        funzioni = [f for f in (o.get("funzioni") or [])[:4] if isinstance(f, dict)]
        for f in funzioni:
            serie.append({"nome": _pulito(f.get("nome"), 40) or None, "punti": _campiona(str(f.get("formula") or ""), f.get("da"), f.get("a"))})
    except (calcolo.ErroreCalcolo, TypeError, ValueError, AttributeError):
        return None
    if not serie:
        return None
    if funzioni:
        tipo = "linee"
    return {"tipo": tipo if tipo in ("punti", "linee") else "punti", **base, "serie": serie}


def numeri_visibili(testo: str, tab: dict | None, graf: dict | None) -> set[Fraction]:
    pezzi = [testo]
    if tab:
        pezzi += [" ".join(r) for r in tab["valori"]] + tab["colonne"]
    numeri = calcolo.numeri_del_testo(" ; ".join(pezzi))
    if graf:
        for s in graf["serie"]:
            for v in (s.get("valori") or []) + [c for p in s.get("punti") or [] for c in p]:
                try:
                    numeri.add(calcolo.numero(v))
                    numeri.add(abs(calcolo.numero(v)))
                except calcolo.ErroreCalcolo:
                    pass
    return numeri


# --------------------------------------------------------------------------- il calcolo

def _regola(o) -> dict | None:
    if not isinstance(o, dict):
        return None
    for chiave, (minimo, massimo) in (("decimali", (0, 8)), ("cifre", (1, 8))):
        if o.get(chiave) is not None:
            try:
                n = int(o[chiave])
            except (TypeError, ValueError):
                raise EsercizioRotto('in "arrotonda" il numero non va') from None
            if not minimo <= n <= massimo:
                raise EsercizioRotto('in "arrotonda" il numero non va')
            return {chiave: n}
    return None


def _testo_numero(v: Fraction) -> str:
    """Un dato da tenere: "1.73" (anche per rimandarlo al modello in una modifica), "1/3" se non ha fine."""
    if calcolo._finisce(v):
        return calcolo._testo_decimale(calcolo._decimale(v)).replace(",", ".")
    return f"{v.numerator}/{v.denominator}"


def _da_testo(s) -> Fraction:
    return Fraction(str(s))


def _deve_esserci(pezzo: str, testo: str, cosa: str) -> None:
    if calcolo.compatta(pezzo) not in calcolo.compatta(testo):
        raise EsercizioRotto(f'{cosa} di "calcolo" ({pezzo}) non è scritta uguale nel "testo"')


def risolvi(d: dict, testo_visibile: str, numeri: set[Fraction]) -> dict:
    """Il calcolo controllato e risolto: {"calcolo", "risultati", "soluzione"}. EsercizioRotto se non va."""
    c = d.get("calcolo")
    if not isinstance(c, dict):
        raise EsercizioRotto('è un esercizio con i numeri ma non ha "calcolo"')
    tipo = str(c.get("tipo") or "").strip().lower()
    if tipo not in TIPI_CALCOLO:
        raise EsercizioRotto(f'in "calcolo" il "tipo" deve essere uno di: {", ".join(TIPI_CALCOLO)}')
    try:
        if tipo == "valore":
            return _valore(c, testo_visibile, numeri)
        if tipo in ("equazione", "disequazione"):
            testo = _pulito(c.get(tipo), 200)
            x = _pulito(c.get("incognita"), 10) or "x"
            if not testo or not _NOME.fullmatch(x):
                raise EsercizioRotto(f'manca "{tipo}" o "incognita" in "calcolo"')
            _deve_esserci(testo, testo_visibile, "l'" + tipo)
            sol = calcolo.risolvi_equazione(testo, x) if tipo == "equazione" else calcolo.risolvi_disequazione(testo, x)
            return {"calcolo": {"tipo": tipo, tipo: testo, "incognita": x}, "risultati": ["Soluzione"],
                    "soluzione": f"Soluzione: {sol.testo}.", "atteso": [sol.testo]}
        if tipo == "sistema":
            equazioni = [_pulito(e, 200) for e in c.get("equazioni") or []]
            incognite = [_pulito(x, 10) for x in c.get("incognite") or []]
            if not equazioni or any(not _NOME.fullmatch(x) for x in incognite):
                raise EsercizioRotto('nel sistema servono "equazioni" e "incognite"')
            for e in equazioni:
                _deve_esserci(e, testo_visibile, "l'equazione")
            valori, testo = calcolo.risolvi_sistema(equazioni, incognite)
            uscita = {"calcolo": {"tipo": "sistema", "equazioni": equazioni, "incognite": incognite}, "soluzione": f"Soluzione: {testo}."}
            if valori is None:
                return {**uscita, "risultati": ["Soluzione"], "atteso": [testo]}
            return {**uscita, "risultati": incognite, "atteso": [calcolo.scrivi(v) for v in valori]}
        espressione, risultato = _pulito(c.get("espressione"), 200), _pulito(c.get("risultato"), 200)
        cosa = _pulito(c.get("cosa"), 40) or "Risultato"
        if not espressione or not risultato:
            raise EsercizioRotto('nell\'espressione servono "espressione" e "risultato"')
        _deve_esserci(espressione, testo_visibile, "l'espressione")
        if not calcolo.equivalenti(espressione, risultato):
            raise EsercizioRotto(f'il "risultato" {risultato} non è uguale all\'"espressione" {espressione}: ricontrollalo')
        return {"calcolo": {"tipo": "espressione", "espressione": espressione, "risultato": risultato, "cosa": cosa},
                "risultati": [cosa], "soluzione": f"{cosa}: {espressione} = {risultato}.", "atteso": [risultato]}
    except calcolo.NonRisolvibile as e:
        raise EsercizioRotto(f"il programma non lo sa risolvere ({e}): scegli un esercizio dello stesso tipo che stia nelle forme di \"calcolo\"") from None
    except calcolo.ErroreCalcolo as e:
        raise EsercizioRotto(f'in "calcolo" c\'è un errore: {e}') from None


def _valore(c: dict, testo_visibile: str, numeri: set[Fraction]) -> dict:
    dati = c.get("dati") or {}
    if not isinstance(dati, dict) or len(dati) > 20:
        raise EsercizioRotto('in "calcolo" i "dati" devono essere un oggetto {"nome": numero}')
    valori: dict[str, Fraction] = {}
    for nome, v in dati.items():
        nome = str(nome).strip()
        if not _NOME.fullmatch(nome):
            raise EsercizioRotto(f'il dato «{nome}» ha un nome che non va: usa lettere e numeri')
        valori[nome] = calcolo.numero(v)
        if valori[nome] not in numeri and abs(valori[nome]) not in numeri:
            raise EsercizioRotto(f'il dato {nome} = {calcolo.scrivi(valori[nome])} non è scritto nel "testo" né nella tabella')
    richieste = c.get("richieste") or []
    if not isinstance(richieste, list) or not 1 <= len(richieste) <= RICHIESTE_MASSIME:
        raise EsercizioRotto(f'in "calcolo" servono da 1 a {RICHIESTE_MASSIME} "richieste"')
    pulite, frasi, attesi, lunghi = [], [], [], []
    arrotonda_testo = bool(_ARROTONDA.search(testo_visibile))
    for r in richieste:
        if not isinstance(r, dict):
            raise EsercizioRotto('ogni richiesta è un oggetto con "cosa" e "formula"')
        cosa, formula, unita = _pulito(r.get("cosa"), 60), _pulito(r.get("formula"), 300), _pulito(r.get("unita"), 20)
        if not cosa or not formula:
            raise EsercizioRotto('ogni richiesta ha "cosa" e "formula"')
        regola = _regola(r.get("arrotonda"))
        albero = calcolo.leggi(formula, set(valori))
        v = calcolo.calcola(albero, valori)
        esatto, u = calcolo.scrivi(v), f" {unita}" if unita else ""
        if not regola and not _corto(v, esatto):
            lunghi.append(cosa)
        conto = "" if albero[0] in ("var", "num") else f"{calcolo.stampa(albero, valori)} = "
        if len(conto) > 140:  # una deviazione standard scritta per intero non si legge: basta il risultato
            conto = ""
        if regola and calcolo.scrivi(v, regola) != esatto:
            arrotondato = calcolo.scrivi(v, regola)
            frasi.append(f"{cosa}: {conto}{esatto}{u}, che arrotondato fa {arrotondato}{u}.")
            attesi.append(f"{arrotondato}{u}")
        else:
            frasi.append(f"{cosa}: {conto}{esatto}{u}.")
            attesi.append(f"{calcolo.scrivi(v, regola) if regola else esatto}{u}")
        pulite.append({"cosa": cosa, "formula": formula, "unita": unita, "arrotonda": regola})
    if arrotonda_testo and not any(p["arrotonda"] for p in pulite):
        raise EsercizioRotto('il "testo" chiede di arrotondare ma nessuna richiesta ha "arrotonda"')
    return {"calcolo": {"tipo": "valore", "dati": {k: _testo_numero(v) for k, v in valori.items()}, "richieste": pulite},
            "risultati": [p["cosa"] for p in pulite], "soluzione": " ".join(frasi), "atteso": attesi, "lunghi": lunghi,
            "arrotonda_da_dire": None if arrotonda_testo else frase_arrotonda(pulite)}


_IN_PAROLE = ["zero", "una", "due", "tre", "quattro", "cinque", "sei", "sette", "otto"]


def _a_quanto(regola: dict) -> str:
    if "decimali" in regola:
        n = regola["decimali"]
        return "all'unità" if n == 0 else ("a una cifra decimale" if n == 1 else f"a {_IN_PAROLE[n]} cifre decimali")
    n = regola["cifre"]
    return "a una cifra significativa" if n == 1 else f"a {_IN_PAROLE[n]} cifre significative"


def frase_arrotonda(pulite: list[dict]) -> str | None:
    """La frase che chiede di arrotondare, quando il modello ha messo "arrotonda" nel calcolo ma non l'ha chiesto nel
    testo (24/11/2026, prova di fisica: due esercizi su sei buttati per questo). Prima si scartava l'esercizio; ora la
    aggiunge il programma, con le cifre giuste: «Arrotonda i risultati a due cifre significative.»"""
    gruppi: dict[str, list[str]] = {}
    for p in pulite:
        if p["arrotonda"]:
            gruppi.setdefault(_a_quanto(p["arrotonda"]), []).append(p["cosa"])
    if not gruppi:
        return None
    frasi = []
    for quanto, cose in gruppi.items():
        if len(cose) == len(pulite):
            frasi.append(f"Arrotonda {'il risultato' if len(cose) == 1 else 'i risultati'} {quanto}.")
        else:
            nomi = " e ".join(f"«{c}»" for c in cose) if len(cose) <= 2 else ", ".join(f"«{c}»" for c in cose[:-1]) + f" e «{cose[-1]}»"
            frasi.append(f"Arrotonda {quanto} {'il risultato' if len(cose) == 1 else 'i risultati'} {nomi}.")
    return " ".join(frasi)


# «arrotonda a due cifre»: significative o decimali? (24/11/2026, prova di fisica). Il programma lo sa dal calcolo.
_CIFRE_VAGHE = re.compile(r"(\b(?:una|due|tre|quattro|cinque|[1-5])\s+cifr[ae])(?!\s+(?:significativ|decimal|dopo))", re.I)


def arrotonda_chiaro(testo: str, conti: dict | None) -> str:
    """Il testo con «a due cifre» precisato in «a due cifre significative» o «decimali», se tutte le richieste
    arrotondano nello stesso modo; se no il testo com'è."""
    if not conti or not _ARROTONDA.search(testo) or not _CIFRE_VAGHE.search(testo):
        return testo
    modi = {next(iter(r["arrotonda"])) for r in conti["calcolo"].get("richieste") or [] if r.get("arrotonda")}
    if len(modi) != 1:
        return testo
    plurale, singolare = ("significative", "significativa") if modi == {"cifre"} else ("decimali", "decimale")
    return _CIFRE_VAGHE.sub(lambda m: f"{m.group(1)} {plurale if m.group(1).lower().endswith('e') else singolare}", testo)


def _corto(v, scritto: str) -> bool:
    """Un risultato come quelli del prof: esatto e con al massimo 4 decimali (0,035 sì; 1/49 o una radice no).
    24/11/2026, fisica: "2 / 98 = 1/49 (circa 0,02041)" senza chiedere di arrotondare."""
    if not isinstance(v, Fraction) or "circa" in scritto:
        return False
    return len(scritto.partition(",")[2]) <= 4


def stessi_numeri(calcolo_pulito: dict, compatti_materiali: str, numeri_materiali: set[Fraction]) -> bool:
    """Lo stesso esercizio dei materiali, con gli stessi numeri: Andrea vuole sempre numeri nuovi."""
    tipo = calcolo_pulito["tipo"]
    if tipo == "valore":
        dati = [abs(_da_testo(v)) for v in calcolo_pulito["dati"].values()]
        importanti = {v for v in dati if not (v.denominator == 1 and v <= 10) and v not in (100, 1000)}
        return bool(importanti) and all(v in numeri_materiali for v in importanti)
    pezzi = {"equazione": lambda c: [c["equazione"]], "disequazione": lambda c: [c["disequazione"]],
             "sistema": lambda c: c["equazioni"], "espressione": lambda c: [c["espressione"]]}[tipo](calcolo_pulito)
    return all(calcolo.compatta(p) in compatti_materiali for p in pezzi)


# --------------------------------------------------------------------------- il procedimento nella consegna

# Segnalato da Andrea il 24/11/2026: «Calcola l'errore relativo Δx/x» dà già il procedimento, che è proprio quello che la
# verifica deve controllare. Una formula nel testo di un esercizio con un valore da calcolare si fa togliere al modello e,
# se resta, la toglie il programma.
_SIMBOLO = r"[A-Za-zΔΣρλμσθαβγδεωπ][A-Za-z0-9_ₐ-ₜ₀-₉]*[²³]?"
_TERMINE = rf"(?:\(\s*{_SIMBOLO}(?:\s*[-−+]\s*{_SIMBOLO})+\s*\)|{_SIMBOLO})"
_FORMULA = re.compile(rf"(?<![\w,.])({_TERMINE}(?:\s*[/·×*]\s*(?:{_TERMINE}|\d+(?:[.,]\d+)?))+)")
_SOLO_UNITA = {"m", "cm", "mm", "km", "dm", "hm", "g", "kg", "mg", "hg", "t", "s", "h", "min", "ms", "l", "L", "ml", "mL",
               "N", "J", "W", "Pa", "K", "V", "A", "mol", "m²", "m³", "cm²", "cm³", "dm³", "mm²", "mm³", "km²", "°C"}
_ASSEGNATA = re.compile(rf"{_SIMBOLO}\s*=\s*$")
_GIRO = r"(?:(?:con|usando|secondo) la (?:formula|relazione)|cioè|ovvero|dat[oa] da|come)"
# "…, usando la formula Δm = sqrt(somma degli scarti al quadrato / numero delle misure)": tutta la frase dà il procedimento.
_CLAUSOLA = re.compile(r",?\s*\(?\b(?:usando|applicando|utilizzando|con|secondo)\s+(?:la\s+|le\s+)?(?:formula|formule|relazione)\b"
                       r"[^.?!;]*\)?", re.I)
_RADICE = re.compile(rf"{_SIMBOLO}\s*=\s*(?:sqrt|√)\s*\((?:[^()]|\([^()]*\))*\)")


def formule_nel_testo(testo: str) -> list[str]:
    """Le formule che danno il procedimento ("Δx/x", "(xmax − xmin)/2", "v = Δs/Δt", "usando la formula …"), non le
    unità di misura ("4,8 kg/m³", "in m/s")."""
    trovate = [m.group(0).strip(" ,()") for m in _CLAUSOLA.finditer(testo or "")]
    trovate += [m.group(0) for m in _RADICE.finditer(testo or "") if not any(m.group(0).rstrip(")") in t for t in trovate)]
    for m in _FORMULA.finditer(testo or ""):
        if any(m.group(1).strip() in t for t in trovate):
            continue
        pezzo, prima = m.group(1).strip(), (testo or "")[:m.start()].rstrip()
        if pezzo.lower() == "e/o" or (prima and prima[-1].isdigit()):
            continue
        if all(s in _SOLO_UNITA for s in re.findall(_SIMBOLO, pezzo)) and not _ASSEGNATA.search(prima):
            continue
        trovate.append(pezzo)
    return trovate


def senza_formule(testo: str) -> str | None:
    """Il testo senza le formule del procedimento, oppure None se senza di loro la richiesta non si capisce più."""
    nuovo = _CLAUSOLA.sub("", testo)
    nuovo = _RADICE.sub("", nuovo)
    for f in formule_nel_testo(nuovo):
        nucleo = rf"(?:{_SIMBOLO}\s*=\s*)?\(?{re.escape(f)}\)?(?:\s*[·×*]\s*\d+)?"  # anche "(Δx/x)·100"
        nuovo = re.sub(rf"\s*(?:(?:{_GIRO}\s+)?{nucleo}|\(\s*(?:{_GIRO}\s*)?{nucleo}\s*\))", "", nuovo, count=1)
    nuovo = re.sub(r"\s+([.,;:?!])", r"\1", re.sub(r"\(\s*\)", "", nuovo))
    nuovo = re.sub(r"[ \t]{2,}", " ", nuovo).strip()
    ultima = re.split(r"(?<=[.?!:])\s+", nuovo.rstrip(".?!: "))[-1]
    return nuovo if len(re.findall(r"\w+", ultima)) >= 3 else None


def testo_visibile(d: dict) -> str:
    """Il testo della domanda con la tabella e il grafico scritti a parole: per la correzione e per il Ripasso lampo."""
    righe = [str(d.get("testo") or "")]
    tab = d.get("tabella")
    if tab:
        righe.append(("Tabella " + tab["titolo"] + ": " if tab.get("titolo") else "Tabella: ") + " | ".join(tab["colonne"]))
        righe += [" | ".join(r) for r in tab["valori"]]
    graf = d.get("grafico")
    if graf:
        assi = f" (asse x: {graf.get('asse_x') or 'x'}, asse y: {graf.get('asse_y') or 'y'})"
        if graf["tipo"] == "barre":
            for s in graf["serie"]:
                righe.append("Grafico a barre" + assi + ": " + "; ".join(f"{c} {calcolo.scrivi(calcolo.numero(v))}"
                                                                           for c, v in zip(graf["categorie"], s["valori"])))
        else:
            for s in graf["serie"]:
                punti = s["punti"] if len(s["punti"]) <= 15 else s["punti"][:: max(1, len(s["punti"]) // 12)]
                righe.append(("Grafico" if not s.get("nome") else f"Grafico «{s['nome']}»") + assi + ", punti: " +
                             "; ".join(f"({calcolo.scrivi(calcolo.numero(p[0]))}; {calcolo.scrivi(calcolo.numero(p[1]))})" for p in punti))
    return "\n".join(righe)


# --------------------------------------------------------------------------- correzione

def _dall_ultimo_uguale(passaggi: str) -> str:
    righe = [r for r in str(passaggi or "").splitlines() if "=" in r]
    return righe[-1].rsplit("=", 1)[-1].strip() if righe else ""


def controlla(d: dict, passaggi: str, scritti: list[str]) -> list[dict]:
    """Il confronto del programma tra i risultati scritti e quelli giusti (contratto 21.1, `controllo`)."""
    c = d.get("calcolo") or {}
    nomi = d.get("risultati") or []
    scritti = [str(s or "").strip() for s in (scritti or [])] + [""] * len(nomi)
    if len(nomi) == 1 and not scritti[0]:
        scritti[0] = _dall_ultimo_uguale(passaggi)
    uscita = []
    for i, cosa in enumerate(nomi):
        scritto, atteso, esito = scritti[i], "", "da_leggere"
        try:
            if c["tipo"] == "valore":
                r = c["richieste"][i]
                valori = {k: _da_testo(v) for k, v in c["dati"].items()}
                v = calcolo.calcola(calcolo.leggi(r["formula"], set(valori)), valori)
                atteso = (calcolo.scrivi(v, r["arrotonda"]) if r["arrotonda"] else calcolo.scrivi(v)) + (f" {r['unita']}" if r["unita"] else "")
                esito = calcolo.controlla_valore(scritto, v, r["unita"], r["arrotonda"])
            elif c["tipo"] == "equazione":
                sol = calcolo.risolvi_equazione(c["equazione"], c["incognita"])
                atteso, esito = sol.testo, calcolo.controlla_equazione(scritto, sol)
            elif c["tipo"] == "disequazione":
                sol = calcolo.risolvi_disequazione(c["disequazione"], c["incognita"])
                atteso, esito = sol.testo, calcolo.controlla_disequazione(scritto, sol)
            elif c["tipo"] == "sistema":
                valori, testo = calcolo.risolvi_sistema(c["equazioni"], c["incognite"])
                if valori is None:
                    atteso = testo
                    nessuna = calcolo.Soluzione("x", insieme="tutti" if "indeterminato" in testo else "nessuno")
                    esito = calcolo.controlla_equazione(scritto, nessuna) if scritto else "mancante"
                else:
                    atteso = calcolo.scrivi(valori[i])
                    esito = calcolo.controlla_valore(scritto, valori[i], "", None)
            else:
                atteso = c["risultato"]
                esito = calcolo.controlla_espressione(scritto, c["espressione"], c.get("cosa") or "", c["risultato"])
        except (calcolo.ErroreCalcolo, KeyError, IndexError, TypeError, ValueError):
            log.warning("controllo del risultato non riuscito (domanda %s)", d.get("n"), exc_info=True)
            esito = "da_leggere" if scritto else "mancante"
        uscita.append({"cosa": cosa, "scritto": scritto[:200], "atteso": atteso[:200], "esito": esito})
    return uscita


FRASI_CONTROLLO = {"giusto": "giusto", "non_arrotondato": "il numero torna ma non è arrotondato come chiesto",
                   "sbagliato": "sbagliato", "mancante": "non scritto", "da_leggere": "il programma non riesce a leggerlo: decidi tu"}


def controllo_in_parole(controllo: list[dict]) -> str:
    return "; ".join(f"{c['cosa']}: scritto «{c['scritto'] or '(niente)'}», giusto {c['atteso']} → {FRASI_CONTROLLO.get(c['esito'], c['esito'])}"
                     for c in controllo)


__all__ = ["con_i_numeri", "chiede_conti", "tabella", "grafico", "risolvi", "stessi_numeri", "testo_visibile", "controlla",
           "controllo_in_parole", "EsercizioRotto", "numeri_visibili"]
