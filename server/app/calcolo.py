"""I conti degli esercizi con i numeri (contratto 21.1): la soluzione la calcola questo programma, non l'AI.

Le formule si scrivono come a scuola: 1,63 oppure 1.63, 2x, 3(x + 1), x^2, x², √(x), 10^-3, ·, ×, ÷, :, −.
Si calcola con le frazioni esatte finché si può (0,05 / 2 fa esattamente 0,025); radici e seni passano ai numeri
con la virgola. Si risolvono equazioni e disequazioni fino al secondo grado e sistemi lineari fino a tre incognite.
Lo stesso lettore confronta i risultati scritti dallo studente con quelli giusti (`controlla`)."""

import math
import random
import re
from decimal import ROUND_HALF_UP, Decimal, localcontext
from fractions import Fraction


class ErroreCalcolo(ValueError):
    """Una formula che non si legge o non si calcola (divisione per zero, radice di un negativo, …)."""


Numero = Fraction | float

FUNZIONI = {"sqrt", "abs", "sin", "cos", "tan", "log", "ln"}
_SINONIMI = {"radq": "sqrt", "rad": "sqrt", "sen": "sin", "tg": "tan"}
_APICI = str.maketrans("⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺", "0123456789-+")
_PEDICI = str.maketrans("₀₁₂₃₄₅₆₇₈₉", "0123456789")
_LETTERA = r"A-Za-zÀ-ÖØ-öø-ÿΑ-Ωα-ω"


# --------------------------------------------------------------------------- lettura

def _pulisci(testo: str) -> str:
    t = str(testo or "")
    t = re.sub(r"[⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺]+", lambda m: "^(" + m.group(0).translate(_APICI) + ")", t)
    t = re.sub(r"[₀₁₂₃₄₅₆₇₈₉]+", lambda m: "_" + m.group(0).translate(_PEDICI), t)
    for a, b in (("−", "-"), ("–", "-"), ("—", "-"), ("·", "*"), ("×", "*"), ("∙", "*"), ("⋅", "*"), ("÷", "/"),
                 (":", "/"), ("[", "("), ("]", ")"), ("{", "("), ("}", ")"), ("π", " pi "), ("**", "^"), ("√", " sqrt ")):
        t = t.replace(a, b)
    return t


_TOKEN = re.compile(rf"(?P<num>\d+(?:[.,]\d+)?)|(?P<id>[{_LETTERA}Δ°µμ%][{_LETTERA}Δ0-9_'°µμ]*)|(?P<op>[-+*/^()|])")


def _token_pos(t: str) -> list[tuple[str, str, int]]:
    """(tipo, testo, posizione) di ogni pezzo del testo già pulito."""
    uscita, i = [], 0
    while i < len(t):
        if t[i].isspace():
            i += 1
            continue
        m = _TOKEN.match(t, i)
        if not m:
            raise ErroreCalcolo(f"non capisco «{t[i]}»")
        tipo = m.lastgroup
        uscita.append((tipo, m.group(tipo), i))
        i = m.end()
    return uscita


def _token(testo: str) -> list[tuple[str, str]]:
    return [(tipo, v) for tipo, v, _ in _token_pos(_pulisci(testo))]


class _Lettore:
    """Discesa ricorsiva: somme, prodotti (anche sottintesi: 2x, 3(x+1)), meno davanti, potenze, funzioni."""

    def __init__(self, token: list[tuple[str, str]], nomi: set[str]):
        self.t = token
        self.i = 0
        self.nomi = nomi

    def guarda(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def prendi(self):
        x = self.guarda()
        self.i += 1
        return x

    def aspetta(self, op: str):
        tipo, v = self.prendi()
        if v != op:
            raise ErroreCalcolo(f"manca «{op}»")

    def espressione(self):
        a = self.termine()
        while self.guarda()[1] in ("+", "-"):
            op = self.prendi()[1]
            a = ("op", op, a, self.termine())
        return a

    def _inizia_fattore(self) -> bool:
        tipo, v = self.guarda()
        return tipo in ("num", "id") or v in ("(",)

    def termine(self):
        a = self.unario()
        while True:
            tipo, v = self.guarda()
            if v in ("*", "/"):
                self.prendi()
                a = ("op", v, a, self.unario())
            elif self._inizia_fattore():
                a = ("op", "*", a, self.potenza())
            else:
                return a

    def unario(self):
        v = self.guarda()[1]
        if v in ("+", "-"):
            self.prendi()
            x = self.unario()
            return ("neg", x) if v == "-" else x
        return self.potenza()

    def potenza(self):
        base = self.atomo()
        if self.guarda()[1] == "^":
            self.prendi()
            return ("pow", base, self.unario())
        return base

    def atomo(self):
        tipo, v = self.prendi()
        if tipo == "num":
            return ("num", Fraction(v.replace(",", ".")))
        if v == "(":
            x = self.espressione()
            self.aspetta(")")
            return x
        if v == "|":
            x = self.espressione()
            self.aspetta("|")
            return ("fun", "abs", x)
        if tipo == "id":
            return self._nome(v)
        raise ErroreCalcolo("la formula finisce a metà" if tipo is None else f"non mi aspettavo «{v}»")

    def _nome(self, v: str):
        nome = _SINONIMI.get(v.lower(), v.lower() if v.lower() in FUNZIONI or v.lower() == "pi" else v)
        if nome in FUNZIONI:
            if self.guarda()[1] == "(":
                self.prendi()
                x = self.espressione()
                self.aspetta(")")
            else:
                x = self.potenza()
            return ("fun", nome, x)
        if nome == "pi" and "pi" not in self.nomi:
            return ("num", math.pi)
        if nome in self.nomi:
            return ("var", nome)
        # "xy" o "ab" quando x, y, a, b sono nomi conosciuti: è un prodotto, come a scuola.
        pezzi = re.findall(rf"[{_LETTERA}Δ][0-9_']*", nome)
        if len(pezzi) > 1 and "".join(pezzi) == nome and all(p in self.nomi for p in pezzi):
            nodo = ("var", pezzi[0])
            for p in pezzi[1:]:
                nodo = ("op", "*", nodo, ("var", p))
            return nodo
        raise ErroreCalcolo(f"«{v}» non è un dato dell'esercizio")


def leggi(testo: str, nomi: set[str] | None = None):
    """La formula letta (un albero); ErroreCalcolo se non si legge."""
    token = _token(testo)
    if not token:
        raise ErroreCalcolo("la formula è vuota")
    l = _Lettore(token, set(nomi or ()))
    albero = l.espressione()
    if l.i != len(token):
        raise ErroreCalcolo(f"non capisco «{token[l.i][1]}»")
    return albero


def nomi_in(albero) -> set[str]:
    if albero[0] == "var":
        return {albero[1]}
    if albero[0] == "num":
        return set()
    return set().union(*(nomi_in(x) for x in albero[1:] if isinstance(x, tuple)))


# --------------------------------------------------------------------------- calcolo

def _finito(x: Numero) -> Numero:
    if isinstance(x, float) and (math.isnan(x) or math.isinf(x)):
        raise ErroreCalcolo("il conto non dà un numero")
    if isinstance(x, Fraction) and (abs(x.numerator) > 10 ** 60 or x.denominator > 10 ** 60):
        raise ErroreCalcolo("numeri troppo grandi")
    return x


def _radice(x: Numero) -> Numero:
    if x < 0:
        raise ErroreCalcolo("radice di un numero negativo")
    if isinstance(x, Fraction):
        n, d = math.isqrt(x.numerator), math.isqrt(x.denominator)
        if n * n == x.numerator and d * d == x.denominator:
            return Fraction(n, d)
    return math.sqrt(float(x))


def calcola(albero, valori: dict[str, Numero] | None = None) -> Numero:
    valori = valori or {}
    tipo = albero[0]
    if tipo == "num":
        return albero[1]
    if tipo == "var":
        if albero[1] not in valori:
            raise ErroreCalcolo(f"manca il valore di {albero[1]}")
        return valori[albero[1]]
    if tipo == "neg":
        return _finito(-calcola(albero[1], valori))
    if tipo == "op":
        a, b = calcola(albero[2], valori), calcola(albero[3], valori)
        if isinstance(a, float) or isinstance(b, float):
            a, b = float(a), float(b)
        if albero[1] == "+":
            return _finito(a + b)
        if albero[1] == "-":
            return _finito(a - b)
        if albero[1] == "*":
            return _finito(a * b)
        if b == 0:
            raise ErroreCalcolo("divisione per zero")
        return _finito(a / b)
    if tipo == "pow":
        a, b = calcola(albero[1], valori), calcola(albero[2], valori)
        if a == 0 and b < 0:
            raise ErroreCalcolo("divisione per zero")
        if isinstance(a, Fraction) and isinstance(b, Fraction):
            if b.denominator == 1:
                if abs(b) > 200:
                    raise ErroreCalcolo("esponente troppo grande")
                return _finito(a ** int(b))
            if b.denominator == 2 and a >= 0:
                r = _radice(a)
                if isinstance(r, Fraction):
                    return _finito(r ** b.numerator)
        if a < 0 and not float(b).is_integer():
            raise ErroreCalcolo("potenza di un numero negativo con esponente non intero")
        try:
            return _finito(math.pow(float(a), float(b)))
        except (OverflowError, ValueError):
            raise ErroreCalcolo("numeri troppo grandi") from None
    if tipo == "fun":
        x = calcola(albero[2], valori)
        nome = albero[1]
        if nome == "sqrt":
            return _radice(x)
        if nome == "abs":
            return abs(x)
        if nome in ("sin", "cos", "tan"):
            gradi = float(x)
            # Gli angoli notevoli escono giusti (sin 30° = 0,5, non 0,49999999…).
            r = {"sin": math.sin, "cos": math.cos, "tan": math.tan}[nome](math.radians(gradi))
            if nome == "tan" and abs(math.cos(math.radians(gradi))) < 1e-12:
                raise ErroreCalcolo("la tangente di 90° non esiste")
            return _finito(round(r, 12) + 0.0)
        if x <= 0:
            raise ErroreCalcolo("logaritmo di un numero che non è positivo")
        return _finito(math.log10(float(x)) if nome == "log" else math.log(float(x)))
    raise ErroreCalcolo("formula strana")


def uguali(a: Numero, b: Numero) -> bool:
    if isinstance(a, Fraction) and isinstance(b, Fraction):
        return a == b
    return math.isclose(float(a), float(b), rel_tol=1e-9, abs_tol=1e-12)


def numero(testo) -> Fraction:
    """Un dato dell'esercizio: 1.72, "1,72", "-3", 2.5e3 → frazione esatta."""
    if isinstance(testo, bool):
        raise ErroreCalcolo("un dato non è un numero")
    if isinstance(testo, int):
        return Fraction(testo)
    if isinstance(testo, float):
        if math.isnan(testo) or math.isinf(testo):
            raise ErroreCalcolo("un dato non è un numero")
        return Fraction(Decimal(repr(testo)))
    t = _pulisci(str(testo)).replace(" ", "")
    frazione = re.fullmatch(r"([-+]?\d+)/(\d+)", t)
    if frazione and int(frazione.group(2)):
        return Fraction(int(frazione.group(1)), int(frazione.group(2)))
    m = re.fullmatch(r"([-+]?\d+(?:[.,]\d+)?)(?:\*10\^\(?([-+]?\d+)\)?)?", t)
    if not m:
        raise ErroreCalcolo(f"«{testo}» non è un numero")
    v = Fraction(m.group(1).replace(",", "."))
    return v * Fraction(10) ** int(m.group(2)) if m.group(2) else v


# --------------------------------------------------------------------------- numeri scritti all'italiana

def _decimale(x: Numero) -> Decimal:
    with localcontext() as c:
        c.prec = 60
        if isinstance(x, Fraction):
            return Decimal(x.numerator) / Decimal(x.denominator)
        return Decimal(repr(float(x)))


def _testo_decimale(d: Decimal) -> str:
    t = format(d, "f")
    if "." in t:
        t = t.rstrip("0").rstrip(".")
    if t in ("-0", ""):
        t = "0"
    return t.replace(".", ",")


def _finisce(x: Fraction) -> bool:
    d = x.denominator
    for p in (2, 5):
        while d % p == 0:
            d //= p
    return d == 1


def arrotonda(x: Numero, regola: dict | None) -> Decimal:
    """Arrotondamento di scuola (5 va in su): {"decimali": 2} o {"cifre": 1} (cifre significative)."""
    d = _decimale(x)
    if not regola:
        return d
    if "cifre" in regola:
        if d == 0:
            return Decimal(0)
        cifre = int(regola["cifre"])
        esponente = d.copy_abs().adjusted()
        quanti = cifre - 1 - esponente
    else:
        quanti = int(regola.get("decimali", 0))
    passo = Decimal(1).scaleb(-quanti)
    with localcontext() as c:
        c.prec = 60
        return d.quantize(passo, rounding=ROUND_HALF_UP)


def scrivi(x: Numero, regola: dict | None = None) -> str:
    """0.025 → "0,025"; 1/3 → "1/3 (circa 0,3333)"; con la regola, il numero arrotondato con i suoi zeri."""
    if regola:
        d = arrotonda(x, regola)
        t = format(d, "f")
        return (t.lstrip("-") if d == 0 else t).replace(".", ",")
    if isinstance(x, Fraction):
        if x.denominator == 1 or _finisce(x) and len(_testo_decimale(_decimale(x))) <= 14:
            return _testo_decimale(_decimale(x))
        circa = _testo_decimale(arrotonda(x, {"cifre": 4}))
        return f"{x.numerator}/{x.denominator} (circa {circa})"
    if float(x).is_integer() and abs(x) < 1e15:
        return str(int(x))
    return _testo_decimale(arrotonda(x, {"cifre": 4}))


_PRIORITA = {"+": 1, "-": 1, "*": 2, "/": 2}


def stampa(albero, valori: dict[str, Numero] | None = None, padre: int = 0) -> str:
    """La formula riscritta (con i dati al posto dei nomi, se ci sono): (1,72 - 1,58) / 2."""
    valori = valori or {}
    tipo = albero[0]
    if tipo == "num":
        return "π" if albero[1] == math.pi else scrivi(albero[1])
    if tipo == "var":
        if albero[1] in valori:
            v = valori[albero[1]]
            t = scrivi(v) if not (isinstance(v, Fraction) and v.denominator != 1 and not _finisce(v)) else f"{v.numerator}/{v.denominator}"
            return f"({t})" if v < 0 else t
        return albero[1]
    if tipo == "neg":
        t = "-" + stampa(albero[1], valori, 3)
        return f"({t})" if padre >= 2 else t
    if tipo == "op":
        p = _PRIORITA[albero[1]]
        simbolo = {"+": " + ", "-": " - ", "*": " · ", "/": " / "}[albero[1]]
        destra = p + 1 if albero[1] in ("-", "/") else p
        t = stampa(albero[2], valori, p) + simbolo + stampa(albero[3], valori, destra)
        return f"({t})" if p < padre else t
    if tipo == "pow":
        t = stampa(albero[1], valori, 4) + "^" + stampa(albero[2], valori, 4)
        return f"({t})" if padre >= 4 else t
    if tipo == "fun":
        nome = {"sqrt": "√", "abs": "|"}.get(albero[1], albero[1])
        dentro = stampa(albero[2], valori, 0)
        return f"|{dentro}|" if albero[1] == "abs" else f"{nome}({dentro})"
    return "?"


# --------------------------------------------------------------------------- equazioni, disequazioni, sistemi

class NonRisolvibile(ErroreCalcolo):
    pass


_PROVE = (Fraction(3), Fraction(-1), Fraction(1, 2), Fraction(7, 3), Fraction(-5, 2))


def _coefficienti(f, x: str, altri: dict | None = None) -> tuple[Numero, Numero, Numero]:
    """a, b, c di a·x² + b·x + c; NonRisolvibile se f non è un polinomio di grado al massimo 2 in x."""
    altri = altri or {}
    try:
        f0, f1, f2 = (calcola(f, {**altri, x: Fraction(k)}) for k in (0, 1, 2))
    except ErroreCalcolo:
        raise NonRisolvibile("non è un polinomio (c'è l'incognita al denominatore o sotto radice)") from None
    a = (f2 - 2 * f1 + f0) / 2
    b = f1 - f0 - a
    c = f0
    for t in _PROVE:
        try:
            atteso = a * t * t + b * t + c if not isinstance(a, float) else float(a) * float(t) ** 2 + float(b) * float(t) + float(c)
            if not uguali(calcola(f, {**altri, x: t}), atteso):
                raise NonRisolvibile("è di grado più alto del secondo o non è un polinomio")
        except ErroreCalcolo as e:
            raise NonRisolvibile(str(e)) from None
    return a, b, c


def _lati(testo: str, nomi: set[str]):
    t = _pulisci(testo)
    if t.count("=") != 1 or re.search(r"[<>≤≥]", t):
        raise ErroreCalcolo("un'equazione ha un solo segno =")
    sinistra, destra = t.split("=")
    return ("op", "-", leggi(sinistra, nomi), leggi(destra, nomi))


_SEGNI = {"<": "<", ">": ">", "<=": "≤", ">=": "≥", "≤": "≤", "≥": "≥", "=<": "≤", "=>": "≥"}


def _lati_dis(testo: str, nomi: set[str]):
    t = _pulisci(testo)
    m = re.findall(r"<=|>=|=<|=>|≤|≥|<|>", t)
    if len(m) != 1 or "=" in re.sub(r"<=|>=|=<|=>", "", t):
        raise ErroreCalcolo("una disequazione ha un solo segno (<, >, ≤, ≥)")
    sinistra, destra = re.split(r"<=|>=|=<|=>|≤|≥|<|>", t)
    return ("op", "-", leggi(sinistra, nomi), leggi(destra, nomi)), _SEGNI[m[0]]


def _intero(a: Numero, b: Numero, c: Numero):
    """Coefficienti interi (per scrivere le radici come a scuola), se sono frazioni."""
    if not all(isinstance(v, Fraction) for v in (a, b, c)):
        return None
    m = math.lcm(a.denominator, b.denominator, c.denominator)
    return int(a * m), int(b * m), int(c * m)


def _radicale(n: int) -> tuple[int, int]:
    """√n = k·√m con m senza quadrati."""
    k, m = 1, n
    p = 2
    while p * p <= m:
        while m % (p * p) == 0:
            m //= p * p
            k *= p
        p += 1
    return k, m


def _radici_scritte(a: int, b: int, d: int, x: str) -> str:
    """(−b ± k√m)/(2a), semplificata."""
    k, m = _radicale(d)
    den = 2 * a
    g = math.gcd(math.gcd(abs(b), k), abs(den))
    b2, k2, den2 = -b // g, k // g, den // g
    if den2 < 0:
        b2, k2, den2 = -b2, k2, -den2
    rad = ("" if k2 == 1 else str(k2)) + f"√{m}"
    sopra = (f"{b2} ± {rad}" if b2 else f"± {rad}")
    return f"{x} = ({sopra})/{den2}" if den2 != 1 else f"{x} = {sopra}"


class Soluzione:
    """Le soluzioni di un'equazione o di una disequazione: per scriverle e per confrontarle."""

    def __init__(self, x: str, valori: list[Numero] | None = None, insieme: str | None = None,
                 intervalli: list[tuple] | None = None, testo: str = ""):
        self.x = x
        self.valori = valori  # equazione: i valori (anche vuoto: nessuna soluzione)
        self.insieme = insieme  # "tutti", "nessuno"
        self.intervalli = intervalli  # disequazione: (da, da_compreso, a, a_compreso), None = infinito
        self.testo = testo

    def contiene(self, t: float) -> bool:
        if self.insieme == "tutti":
            return True
        if self.insieme == "nessuno":
            return False
        if self.valori is not None:
            return any(math.isclose(float(v), t, rel_tol=1e-9, abs_tol=1e-9) for v in self.valori)
        for da, da_c, a, a_c in self.intervalli or []:
            sopra = da is None or t > float(da) + 1e-12 or (da_c and math.isclose(t, float(da), abs_tol=1e-9))
            sotto = a is None or t < float(a) - 1e-12 or (a_c and math.isclose(t, float(a), abs_tol=1e-9))
            if sopra and sotto:
                return True
        return False

    def bordi(self) -> list[float]:
        b = [float(v) for v in self.valori or []]
        for da, _, a, _ in self.intervalli or []:
            b += [float(v) for v in (da, a) if v is not None]
        return b


def _ordina(vs: list[Numero]) -> list[Numero]:
    return sorted(vs, key=float)


def risolvi_equazione(testo: str, x: str = "x") -> Soluzione:
    f = _lati(testo, {x})
    a, b, c = _coefficienti(f, x)
    if a == 0:
        if b == 0:
            return Soluzione(x, insieme="tutti" if c == 0 else "nessuno",
                             testo="indeterminata: va bene ogni numero" if c == 0 else "impossibile: nessuna soluzione")
        v = -c / b
        return Soluzione(x, valori=[v], testo=f"{x} = {scrivi(v)}")
    delta = b * b - 4 * a * c
    if delta < 0 and not uguali(delta, 0):
        return Soluzione(x, valori=[], testo="nessuna soluzione reale (il delta è negativo)")
    if uguali(delta, 0):
        v = -b / (2 * a)
        return Soluzione(x, valori=[v], testo=f"{x} = {scrivi(v)} (due soluzioni uguali)")
    r = _radice(delta)
    v1, v2 = _ordina([(-b - r) / (2 * a), (-b + r) / (2 * a)])
    if isinstance(r, Fraction):
        testo_v = f"{x} = {scrivi(v1)} oppure {x} = {scrivi(v2)}"
    else:
        interi = _intero(a, b, c)
        scritta = _radici_scritte(interi[0], interi[1], interi[1] ** 2 - 4 * interi[0] * interi[2], x) if interi else None
        testo_v = (f"{scritta}, cioè circa {x} = {scrivi(v1)} oppure {x} = {scrivi(v2)}" if scritta
                   else f"{x} = {scrivi(v1)} oppure {x} = {scrivi(v2)}")
    return Soluzione(x, valori=[v1, v2], testo=testo_v)


def _gira(segno: str) -> str:
    return {"<": ">", ">": "<", "≤": "≥", "≥": "≤"}[segno]


def _vale(v: Numero, segno: str) -> bool:
    return {"<": v < 0, ">": v > 0, "≤": v <= 0, "≥": v >= 0}[segno]


def risolvi_disequazione(testo: str, x: str = "x") -> Soluzione:
    f, segno = _lati_dis(testo, {x})
    a, b, c = _coefficienti(f, x)
    stretto = segno in ("<", ">")
    if a == 0:
        if b == 0:
            tutti = _vale(c, segno)
            return Soluzione(x, insieme="tutti" if tutti else "nessuno", testo="ogni numero" if tutti else "nessun numero")
        r = -c / b
        s = segno if b > 0 else _gira(segno)
        intervallo = (None, False, r, not stretto) if s in ("<", "≤") else (r, not stretto, None, False)
        return Soluzione(x, intervalli=[intervallo], testo=f"{x} {s} {scrivi(r)}")
    if a < 0:
        a, b, c, segno = -a, -b, -c, _gira(segno)
    delta = b * b - 4 * a * c
    positivo = segno in (">", "≥")
    if delta < 0 and not uguali(delta, 0):
        return Soluzione(x, insieme="tutti" if positivo else "nessuno", testo="ogni numero" if positivo else "nessun numero")
    if uguali(delta, 0):
        r = -b / (2 * a)
        if segno == ">":
            return Soluzione(x, intervalli=[(None, False, r, False), (r, False, None, False)], testo=f"ogni numero tranne {x} = {scrivi(r)}")
        if segno == "≥":
            return Soluzione(x, insieme="tutti", testo="ogni numero")
        if segno == "<":
            return Soluzione(x, insieme="nessuno", testo="nessun numero")
        return Soluzione(x, valori=[r], testo=f"{x} = {scrivi(r)}")
    rq = _radice(delta)
    r1, r2 = _ordina([(-b - rq) / (2 * a), (-b + rq) / (2 * a)])
    s1, s2 = scrivi(r1), scrivi(r2)
    if positivo:
        lt, gt = ("<", ">") if stretto else ("≤", "≥")
        return Soluzione(x, intervalli=[(None, False, r1, not stretto), (r2, not stretto, None, False)],
                         testo=f"{x} {lt} {s1} oppure {x} {gt} {s2}")
    lt = "<" if stretto else "≤"
    return Soluzione(x, intervalli=[(r1, not stretto, r2, not stretto)], testo=f"{s1} {lt} {x} {lt} {s2}")


def risolvi_sistema(equazioni: list[str], incognite: list[str]) -> tuple[list[Numero] | None, str]:
    """Sistema lineare: (valori, testo); valori None se impossibile o indeterminato."""
    n = len(incognite)
    if not 2 <= n <= 3 or len(equazioni) != n:
        raise NonRisolvibile("il sistema deve avere 2 o 3 equazioni e altrettante incognite")
    nomi = set(incognite)
    righe = []
    for e in equazioni:
        f = _lati(e, nomi)
        zero = {v: Fraction(0) for v in incognite}
        try:
            c = calcola(f, zero)
            coeff = [calcola(f, {**zero, v: Fraction(1)}) - c for v in incognite]
            prova = {v: Fraction(k + 2, k + 3) for k, v in enumerate(incognite)}
            if not uguali(calcola(f, prova), c + sum(a * prova[v] for a, v in zip(coeff, incognite))):
                raise NonRisolvibile("il sistema non è lineare")
        except NonRisolvibile:
            raise
        except ErroreCalcolo as e:
            raise NonRisolvibile(str(e)) from None
        righe.append([*coeff, -c])
    m = [r[:] for r in righe]
    for col in range(n):
        perno = next((r for r in range(col, n) if m[r][col] != 0), None)
        if perno is None:
            return None, _impossibile_o_indeterminato(righe, n)
        m[col], m[perno] = m[perno], m[col]
        for r in range(n):
            if r != col and m[r][col] != 0:
                k = m[r][col] / m[col][col]
                m[r] = [a - k * b for a, b in zip(m[r], m[col])]
    valori = [m[i][n] / m[i][i] for i in range(n)]
    return valori, "; ".join(f"{v} = {scrivi(x)}" for v, x in zip(incognite, valori))


def _impossibile_o_indeterminato(righe: list[list[Numero]], n: int) -> str:
    def rango(mat):
        mat = [r[:] for r in mat]
        rg, col = 0, 0
        while rg < len(mat) and col < len(mat[0]):
            perno = next((r for r in range(rg, len(mat)) if mat[r][col] != 0), None)
            if perno is None:
                col += 1
                continue
            mat[rg], mat[perno] = mat[perno], mat[rg]
            for r in range(len(mat)):
                if r != rg and mat[r][col] != 0:
                    k = mat[r][col] / mat[rg][col]
                    mat[r] = [a - k * b for a, b in zip(mat[r], mat[rg])]
            rg += 1
            col += 1
        return rg
    return "il sistema è indeterminato" if rango([r[:n] for r in righe]) == rango(righe) else "il sistema è impossibile"


def equivalenti(a: str, b: str, nomi: set[str] | None = None) -> bool:
    """Due espressioni uguali per ogni valore delle lettere (provate in punti a caso, con le frazioni esatte)."""
    ta, tb = leggi(a, nomi or _lettere(a, b)), leggi(b, nomi or _lettere(a, b))
    lettere = sorted(nomi_in(ta) | nomi_in(tb))
    caso = random.Random(f"{a}|{b}")
    provate = 0
    for _ in range(30):
        valori = {v: Fraction(caso.randint(-40, 40), caso.choice((1, 2, 3, 5, 7))) for v in lettere}
        try:
            va, vb = calcola(ta, valori), calcola(tb, valori)
        except ErroreCalcolo:
            continue
        if not uguali(va, vb):
            return False
        provate += 1
        if provate >= 6:
            return True
    return provate >= 3


def _lettere(*testi: str) -> set[str]:
    """Le lettere singole delle espressioni di algebra (a, b, x, y): ogni lettera è una variabile."""
    lettere = set()
    for t in testi:
        for parola in re.findall(rf"[{_LETTERA}]+", _pulisci(t)):
            if parola.lower() in FUNZIONI or parola.lower() in _SINONIMI or parola.lower() == "pi":
                continue
            lettere |= set(parola)
    return lettere


# --------------------------------------------------------------------------- numeri nel testo

_SCIENTIFICO = re.compile(r"(\d+(?:[.,]\d+)?)\s*[·×x*]\s*10\s*(?:\^\s*\(?\s*([-−+]?\d+)\s*\)?|([⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+))")
_NUMERO = re.compile(r"(?<![A-Za-z\d_,.])[-−]?\d+(?:[.,]\d+)?(?![\d])")


def numeri_del_testo(testo: str) -> set[Fraction]:
    t = str(testo or "")
    trovati = set()
    for m in _SCIENTIFICO.finditer(t):
        esponente = m.group(2) or m.group(3).translate(_APICI)
        try:
            trovati.add(Fraction(m.group(1).replace(",", ".")) * Fraction(10) ** int(esponente.replace("−", "-")))
        except ValueError:
            pass
    for m in _NUMERO.finditer(t):
        try:
            v = Fraction(m.group(0).replace("−", "-").replace(",", "."))
        except ValueError:
            continue
        trovati.add(v)
        trovati.add(abs(v))
    return trovati


def compatta(testo: str) -> str:
    """Per riconoscere la stessa equazione scritta in due modi: niente spazi, niente segni di per, simboli uguali."""
    t = _pulisci(testo).lower().replace(" sqrt ", "√")
    t = t.replace("^(2)", "^2").replace("^(3)", "^3")
    return re.sub(r"[\s*]", "", t)


# --------------------------------------------------------------------------- unità di misura

_UNITA = {
    "lunghezza": {"km": Fraction(1000), "hm": Fraction(100), "dam": Fraction(10), "m": Fraction(1), "dm": Fraction(1, 10),
                  "cm": Fraction(1, 100), "mm": Fraction(1, 1000), "µm": Fraction(1, 10 ** 6), "μm": Fraction(1, 10 ** 6)},
    "massa": {"t": Fraction(10 ** 6), "kg": Fraction(1000), "hg": Fraction(100), "dag": Fraction(10), "g": Fraction(1),
              "dg": Fraction(1, 10), "cg": Fraction(1, 100), "mg": Fraction(1, 1000)},
    "tempo": {"h": Fraction(3600), "min": Fraction(60), "s": Fraction(1), "ms": Fraction(1, 1000)},
    "volume": {"m³": Fraction(1000), "dm³": Fraction(1), "l": Fraction(1), "dl": Fraction(1, 10), "cl": Fraction(1, 100),
               "ml": Fraction(1, 1000), "cm³": Fraction(1, 1000), "mm³": Fraction(1, 10 ** 6)},
    "area": {"km²": Fraction(10 ** 6), "m²": Fraction(1), "dm²": Fraction(1, 100), "cm²": Fraction(1, 10 ** 4), "mm²": Fraction(1, 10 ** 6)},
    "velocità": {"m/s": Fraction(1), "km/h": Fraction(5, 18)},
    "densità": {"kg/m³": Fraction(1), "g/cm³": Fraction(1000), "g/ml": Fraction(1000), "kg/dm³": Fraction(1000), "kg/l": Fraction(1000), "g/l": Fraction(1)},
}


def _unita(testo: str) -> str:
    t = str(testo or "").strip().replace(" ", "").replace("^2", "²").replace("^3", "³").replace("^(2)", "²").replace("^(3)", "³")
    t = t.replace("sec", "s").replace("mL", "ml").replace("L", "l").replace("dL", "dl").replace("cL", "cl")
    return t.rstrip(".")


def fattore(unita: str) -> tuple[str, Fraction] | None:
    u = _unita(unita)
    for famiglia, unita_f in _UNITA.items():
        for nome, f in unita_f.items():
            if u == _unita(nome):
                return famiglia, f
    return None


# --------------------------------------------------------------------------- il controllo dei risultati scritti

def _dopo_uguale(scritto: str) -> str:
    t = str(scritto or "").strip()
    return t.rsplit("=", 1)[-1].strip() if "=" in t else t


def leggi_valore(scritto: str) -> tuple[Numero, int | None, str] | None:
    """"Δx = 0,03 cm" → (0.03, 2 decimali scritti, "cm"); None se non c'è un numero da leggere."""
    t = _dopo_uguale(scritto)
    if not re.search(r"\d", t):
        return None
    pulito = _pulisci(t)
    try:
        token = _token_pos(pulito)
    except ErroreCalcolo:
        return None
    # Il numero finisce al primo nome che non è una funzione: da lì comincia l'unità di misura.
    fine = len(token)
    for i, (tipo, v, _) in enumerate(token):
        if tipo == "id" and v.lower() not in FUNZIONI and v.lower() not in _SINONIMI and v.lower() != "pi":
            fine = i
            break
    while fine > 0 and token[fine - 1][1] in ("*", "/", "^", "+", "-", "("):
        fine -= 1
    if fine == 0:
        return None
    try:
        l = _Lettore([(a, b) for a, b, _ in token[:fine]], set())
        albero = l.espressione()
        if l.i != fine:
            return None
        v = calcola(albero)
    except ErroreCalcolo:
        return None
    unita = pulito[token[fine][2]:].strip() if fine < len(token) else ""
    decimali = None
    if albero[0] == "num" or (albero[0] == "neg" and albero[1][0] == "num"):
        m = re.search(r"\d+(?:[.,](\d+))?", t)
        decimali = len(m.group(1)) if m and m.group(1) else 0
    return v, decimali, unita


def _confronta_numero(v: Numero, atteso: Numero, decimali: int | None, regola: dict | None) -> str:
    """giusto | non_arrotondato (giusto ma arrotondato diversamente da come chiesto) | sbagliato."""
    if regola and uguali(v, Fraction(arrotonda(atteso, regola))):
        return "giusto"
    if not regola and uguali(v, atteso):
        return "giusto"
    if decimali is not None and (v != 0 or uguali(atteso, 0)):
        if not regola and uguali(v, Fraction(arrotonda(atteso, {"decimali": decimali}))):
            return "giusto"
        if abs(float(v) - float(atteso)) <= 0.5 * 10 ** -decimali * (1 + 1e-9) + 1e-12:
            return "non_arrotondato"
    if uguali(v, atteso) or math.isclose(float(v), float(atteso), rel_tol=5e-3):
        return "non_arrotondato"
    return "sbagliato"


def controlla_valore(scritto: str, atteso: Numero, unita: str, regola: dict | None) -> str:
    if not str(scritto or "").strip():
        return "mancante"
    letto = leggi_valore(scritto)
    if letto is None:
        return "da_leggere"
    v, decimali, unita_scritta = letto
    if unita and unita_scritta and _unita(unita_scritta) != _unita(unita):
        a, b = fattore(unita_scritta), fattore(unita)
        if a and b and a[0] == b[0]:
            # Stessa grandezza in un'altra unità (0,25 mm invece di 0,025 cm): si confronta nell'unità dello studente.
            atteso = atteso * b[1] / a[1] if isinstance(atteso, Fraction) else float(atteso) * float(b[1] / a[1])
            return _confronta_numero(v, atteso, decimali, None)
        # Un'unità che il programma non sa convertire: se il numero torna decide la correzione.
        return "da_leggere" if _confronta_numero(v, atteso, decimali, regola) != "sbagliato" else "sbagliato"
    return _confronta_numero(v, atteso, decimali, regola)


_PAROLE_NESSUNA = re.compile(r"impossibil|nessun|non ha soluzion|∅|insieme vuoto", re.I)
_PAROLE_TUTTI = re.compile(r"indeterminat|ogni (?:numero|x|valore)|per ogni|qualsiasi|tutti i numeri|∀|\bℝ\b|\bR\b", re.I)
_SEPARATORI = re.compile(r";|\s+oppure\s+|\s+o\s+|\s+e\s+|∨|∪|,\s+(?=[-−+]?\s*[A-Za-z(√\d])")


def _numeri_soluzione(testo: str, x: str) -> list[Numero] | None:
    valori = []
    for pezzo in _SEPARATORI.split(_pulisci(testo)):
        pezzo = pezzo.strip()
        if not pezzo:
            continue
        pezzo = pezzo.rsplit("=", 1)[-1] if "=" in pezzo else pezzo
        try:
            valori.append(calcola(leggi(pezzo, set())))
        except ErroreCalcolo:
            return None
    return valori


def controlla_equazione(scritto: str, giusta: Soluzione) -> str:
    t = str(scritto or "").strip()
    if not t:
        return "mancante"
    if _PAROLE_TUTTI.search(t) or _PAROLE_NESSUNA.search(t):
        nessuna = bool(_PAROLE_NESSUNA.search(t)) and not _PAROLE_TUTTI.search(t)
        if giusta.insieme == "tutti":
            return "giusto" if not nessuna else "sbagliato"
        if giusta.valori == [] or giusta.insieme == "nessuno":
            return "giusto" if nessuna else "sbagliato"
        return "sbagliato"
    valori = _numeri_soluzione(t, giusta.x)
    if valori is None:
        return "da_leggere"
    attesi = list(giusta.valori or [])
    if not attesi:
        return "sbagliato"
    if len({round(float(v), 9) for v in valori}) != len({round(float(v), 9) for v in attesi}):
        return "sbagliato"
    esiti = []
    for a in attesi:
        migliori = [_confronta_numero(v, a, _decimali_di(v), None) for v in valori]
        esiti.append("giusto" if "giusto" in migliori else "non_arrotondato" if "non_arrotondato" in migliori else "sbagliato")
    return "sbagliato" if "sbagliato" in esiti else "non_arrotondato" if "non_arrotondato" in esiti else "giusto"


def _decimali_di(v: Numero) -> int | None:
    if isinstance(v, Fraction) and _finisce(v):
        t = _testo_decimale(_decimale(v))
        return len(t.split(",")[1]) if "," in t else 0
    return None


_CONDIZIONE = re.compile(r"<=|>=|=<|=>|≤|≥|<|>|≠|!=")


def _leggi_disequazione(testo: str, x: str) -> Soluzione | None:
    t = _pulisci(testo).replace("≠", "!=")
    if _PAROLE_TUTTI.search(t):
        return Soluzione(x, insieme="tutti")
    if _PAROLE_NESSUNA.search(t):
        return Soluzione(x, insieme="nessuno")
    intervalli, valori, esclusi = [], [], []
    for pezzo in re.split(r";|\s+oppure\s+|\s+o\s+|∨|∪|,\s+(?=[-−+]?\s*[A-Za-z\d])", t):
        pezzo = pezzo.strip()
        if not pezzo:
            continue
        parti = re.split(r"(<=|>=|=<|=>|≤|≥|<|>|!=|=)", pezzo)
        if len(parti) not in (3, 5):
            return None
        termini, segni = parti[0::2], [_SEGNI.get(s, s) for s in parti[1::2]]
        try:
            numeri = [None if compatta(p) == compatta(x) else calcola(leggi(p, set())) for p in termini]
        except ErroreCalcolo:
            return None
        if numeri.count(None) != 1:
            return None
        if len(numeri) == 3:
            if numeri[1] is not None or segni[0] not in ("<", "≤") or segni[1] not in ("<", "≤"):
                return None
            intervalli.append((numeri[0], segni[0] == "≤", numeri[2], segni[1] == "≤"))
            continue
        segno, n = (segni[0], numeri[1]) if numeri[0] is None else (_gira(segni[0]) if segni[0] in "<>≤≥" else segni[0], numeri[0])
        if segno == "=":
            valori.append(n)
        elif segno == "!=":
            esclusi.append(n)
        elif segno in ("<", "≤"):
            intervalli.append((None, False, n, segno == "≤"))
        else:
            intervalli.append((n, segno == "≥", None, False))
    if esclusi:
        if intervalli or valori or len(esclusi) != 1:
            return None
        r = esclusi[0]
        return Soluzione(x, intervalli=[(None, False, r, False), (r, False, None, False)])
    if valori and not intervalli:
        return Soluzione(x, valori=valori)
    if valori:
        return None
    return Soluzione(x, intervalli=intervalli)


def controlla_disequazione(scritto: str, giusta: Soluzione) -> str:
    t = str(scritto or "").strip()
    if not t:
        return "mancante"
    letta = _leggi_disequazione(t, giusta.x)
    if letta is None:
        return "da_leggere"
    bordi = sorted(set(giusta.bordi() + letta.bordi()))
    prove = [b for b in bordi]
    prove += [(a + b) / 2 for a, b in zip(bordi, bordi[1:])]
    prove += [bordi[0] - 1, bordi[-1] + 1] if bordi else [0.0]
    for p in prove:
        if giusta.contiene(p) != letta.contiene(p):
            return "sbagliato"
    return "giusto"


def controlla_espressione(scritto: str, originale: str, cosa: str, attesa: str) -> str:
    t = _dopo_uguale(scritto)
    if not t:
        return "mancante"
    try:
        if not equivalenti(originale, t):
            return "sbagliato"
        albero = leggi(t, _lettere(originale, t))
    except ErroreCalcolo:
        return "da_leggere"
    if re.search(r"scompo|fattor", cosa or "", re.I):
        # Scomposta vuol dire un prodotto (o una potenza), con un numero davanti al massimo.
        return "giusto" if albero[0] in ("pow",) or (albero[0] == "op" and albero[1] == "*") or \
            (albero[0] == "neg" and albero[1][0] in ("op", "pow")) else "sbagliato"
    return "giusto" if len(_token(t)) <= len(_token(attesa)) + 2 else "da_leggere"
