"""Il linguaggio (contratto 17.1): schemi e lezioni devono sembrare un normale ripasso fatto
da una persona. Il prompt lo chiede, ma non basta: qui si controlla con delle regole fisse.

- `problemi(testo)` dice cosa suona finto: serve per far riscrivere il pezzo una volta;
- `ripulisci(testo)` sistema da solo quello che resta (e non costa niente): toglie le frasi a
  effetto, gli attacchi inutili, il trattino lungo e le emoji."""

import re

ELENCO_PER_PROMPT = """Lingua: deve sembrare scritto da una persona normale, mai da un programma. Non usare mai:
- "è importante notare", "vale la pena", "va sottolineato", "in conclusione", "in sintesi", "per riassumere";
- "cruciale", "un vero e proprio", "a tutti gli effetti", "gioca un ruolo", "svolge un ruolo chiave", "affonda le \
radici", "getta le basi", "nel cuore di";
- la formula "non è X, è Y", "non si tratta di X ma di Y", "non solo X ma anche Y";
- "ecco perché", "ed è qui che", "il punto è", "la chiave è", "la verità è";
- domande a effetto e aperture del tipo "ti sei mai chiesto", "immagina", "scopriamo insieme", "facciamo un viaggio";
- aggettivi da pubblicità: "affascinante", "straordinario", "incredibile", "sorprendente", "rivoluzionario", "iconico";
- gruppi di tre parole messi lì per fare effetto;
- frasi finali di incoraggiamento o offerte di aiuto: "buono studio", "in bocca al lupo", "se vuoi", "fammi sapere", \
"spero";
- ogni riferimento a chi ha scritto il testo o a "i documenti", "il materiale", "il testo fornito": parla delle cose, \
e se serve di' "sul libro", "nei tuoi appunti", "nella verifica";
- il trattino lungo, le emoji, i punti esclamativi."""

# Frasi intere da togliere: aperture a effetto, offerte finali, saluti.
_FRASI_DA_TOGLIERE = [re.compile(p, re.I) for p in (
    r"\bti sei mai chiest[oa]\b", r"\bhai mai (?:pensato|notato|visto|sentito|provato)\b", r"\bimmagin(?:a|iamo|ate)\b",
    r"\bprova a immaginare\b", r"\bfacciamo un (?:viaggio|salto|tuffo)\b", r"\bscopriamo insieme\b",
    r"\bentriamo nel vivo\b", r"\btuffiamoci\b", r"\bsei pront[oa]\b", r"\bsiete pronti\b", r"\bbuono studio\b",
    r"\bbuon ripasso\b", r"\bbuon ascolto\b", r"\bin bocca al lupo\b", r"\bce la (?:puoi fare|farai)\b",
    r"\bfammi sapere\b", r"\bse (?:vuoi|hai bisogno|desideri|preferisci)\b.{0,60}\b(?:posso|possiamo|chiedi|dimmi|fammi)\b",
    r"\bposso (?:aiutarti|anche|prepararti)\b", r"\bspero (?:che|di)\b", r"\bnon esitare\b", r"\bsono qui per\b",
    r"\bbenvenut[oaie]\b", r"\bin questa puntata\b", r"\bintelligenza artificiale\b", r"\bmodello linguistico\b",
    r"\bcome ai?\b.{0,20}\bnon posso\b",
)]

# Attacchi inutili: si tolgono e la frase resta.
_ATTACCHI = re.compile(
    r"\b(?:è (?:importante|fondamentale|essenziale|bene) (?:notare|ricordare|sottolineare|capire|sapere|evidenziare) che"
    r"|vale la pena (?:di )?(?:notare|ricordare|sottolineare) che"
    r"|occorre (?:notare|sottolineare) che|bisogna (?:notare|sottolineare) che"
    r"|va (?:notato|sottolineato|ricordato|detto) che"
    r"|in (?:conclusione|sintesi|definitiva|ultima analisi|poche parole)"
    r"|per (?:riassumere|concludere)"
    r"|secondo (?:i documenti|il materiale)(?: forniti?)?"
    r"|come (?:emerge|si evince|risulta) dai? (?:documenti|materiale)"
    r"|in base (?:ai documenti|al materiale)(?: forniti?)?)\s*[,:]?\s*", re.I)

_SOSTITUZIONI = [
    (re.compile(r"\b(un|una) ver[oa] e propri[oa]\s+", re.I), r"\1 "),
    (re.compile(r"\bver[ei] e propri[ei]\s+", re.I), ""),
    (re.compile(r",?\s*a tutti gli effetti\b", re.I), ""),
    (re.compile(r"\bcruciale\b", re.I), "importante"),
    (re.compile(r"\bcruciali\b", re.I), "importanti"),
    (re.compile(r"\b(giocano|svolgono|ricoprono|rivestono)\s+un\s+ruolo\s+(?:\w+\s+)?"
                r"(?:fondamentale|chiave|centrale|importante|decisivo)\b", re.I), "sono importanti"),
    (re.compile(r"\b(gioca|svolge|ricopre|riveste)\s+un\s+ruolo\s+(?:\w+\s+)?"
                r"(?:fondamentale|chiave|centrale|importante|decisivo)\b", re.I), "è importante"),
    (re.compile(r"\bnon solo\s+([^,.;:]{1,70}?),?\s+ma anche\s+", re.I), r"\1 e anche "),
]

_EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\uFE0F\u200D]")
_TRATTINO_LUNGO = re.compile(r"\s*[—―]\s*|\s+–\s+")

# Da far riscrivere (qui non c'è un rimedio automatico senza rovinare la frase).
_STRUTTURE = [
    (re.compile(r"\bnon (?:è|sono|era|erano)\b[^.;:?]{1,60}[,;:]\s*(?:è|sono|era|erano)\b", re.I),
     'la formula "non è X, è Y"'),
    (re.compile(r"\bnon si tratta (?:solo |soltanto |semplicemente )?di\b", re.I), '"non si tratta di"'),
    (re.compile(r"\bnon solo\b", re.I), '"non solo... ma anche"'),
    (re.compile(r"\becco perché\b", re.I), '"ecco perché"'),
    (re.compile(r"\b(?:ed )?è (?:proprio )?qui che\b", re.I), '"è qui che"'),
    (re.compile(r"\b(?:il punto|la chiave|la verità|il segreto) è\b", re.I), '"il punto è" e simili'),
    (re.compile(r"\baffonda le (?:sue )?radici\b|\bgetta le basi\b|\bnel cuore d[ie]l?\b|\bpanorama\b", re.I),
     "frasi fatte da articolo"),
    (re.compile(r"\?\s+(?:semplice|la risposta)\b", re.I), "una domanda seguita subito dalla risposta a effetto"),
    (re.compile(r"\b(?:i documenti|il materiale|il testo fornito|nei documenti|nel materiale)\b", re.I),
     'le parole "documenti" e "materiale" (scrivi "sul libro", "nei tuoi appunti", "nella verifica")'),
]
_AGGETTIVI = re.compile(
    r"\b(?:affascinant[ei]|straordinari[oaie]|incredibil[ei]|sorprendent[ei]|rivoluzionari[oaie]|iconic[oaih]e?"
    r"|magistral[ei]|sapientemente|fondamental[ei]|essenzial[ei]|significativ[oaie]|emblematic[oaih]e?)\b", re.I)
_TRIADE = re.compile(r"\b[a-zàèéìòù]{4,}, [a-zàèéìòù]{4,} e [a-zàèéìòù]{4,}\b", re.I)
_INIZI_IN_SERIE = re.compile(r"(?:^|[.!?]\s+)(Inoltre|Tuttavia|Pertanto|Infatti|Quindi|Dunque)\b")


def dividi_frasi(testo: str) -> list[str]:
    """Le frasi di un testo (senza spezzare "a.C.", "pag. 3", "S. Pietro" e simili)."""
    testo = " ".join((testo or "").split())
    if not testo:
        return []
    uscita: list[str] = []
    for pezzo in re.split(r"(?<=[.!?…])\s+", testo):
        if uscita and re.search(r"(?:\b(?:a\.C|d\.C|sec|pag|pagg|ecc|es|cfr|art|prof|dott|sig|vol|cap|fig|n|p)\.|\b[A-Z]\.)$",
                                uscita[-1]):
            uscita[-1] += " " + pezzo
        else:
            uscita.append(pezzo)
    return uscita


def problemi(testo: str, fonti: str = "") -> list[str]:
    """Cosa suona finto in questo testo. Un aggettivo usato anche nei documenti va bene."""
    testo = testo or ""
    trovati: list[str] = []
    for regola, nome in _STRUTTURE:
        if regola.search(testo):
            trovati.append(nome)
    if any(r.search(f) for f in dividi_frasi(testo) for r in _FRASI_DA_TOGLIERE):
        trovati.append("aperture a effetto o frasi finali di incoraggiamento")
    if _ATTACCHI.search(testo):
        trovati.append('attacchi inutili come "è importante notare" o "in sintesi"')
    fonti_minuscole = (fonti or "").lower()
    usati = sorted({m.group(0).lower() for m in _AGGETTIVI.finditer(testo) if m.group(0).lower() not in fonti_minuscole})
    if usati:
        trovati.append("aggettivi gonfiati: " + ", ".join(usati[:6]))
    if len(_TRIADE.findall(testo)) >= 3:
        trovati.append("troppi gruppi di tre parole")
    if len(_INIZI_IN_SERIE.findall(testo)) >= 3:
        trovati.append('troppe frasi che iniziano con "Inoltre", "Tuttavia", "Infatti"')
    return trovati


def _maiuscola(frase: str) -> str:
    for i, c in enumerate(frase):
        if c.isalpha():
            return frase[:i] + c.upper() + frase[i + 1:]
    return frase


def ripulisci(testo: str, parlato: bool = False) -> str:
    """Il rimedio che non costa niente, frase per frase. Una frase a effetto si toglie, se resta
    almeno qualcosa: un testo fatto solo di quella frase resta com'è."""
    testo = _EMOJI.sub("", testo or "")
    testo = _TRATTINO_LUNGO.sub(", ", testo).replace("!", ".")
    testo = re.sub(r"\.{2,}", ".", testo.replace("…", "."))
    frasi = dividi_frasi(testo)
    uscita = []
    for frase in frasi:
        if any(r.search(frase) for r in _FRASI_DA_TOGLIERE):
            continue
        nuova = _ATTACCHI.sub("", frase)
        for regola, sostituto in _SOSTITUZIONI:
            nuova = regola.sub(sostituto, nuova)
        nuova = re.sub(r"\s+([,.;:?])", r"\1", " ".join(nuova.split())).strip(" ,;")
        if nuova:
            uscita.append(_maiuscola(nuova) if nuova != frase else nuova)
    if not uscita and frasi:
        return " ".join(frasi)
    testo = " ".join(uscita)
    if parlato:
        testo = re.sub(r"[;:]\s+", ", ", testo) if testo.count(":") > 3 else testo
    return testo


# --------------------------------------------------------------------------- formule

_GRECHE = {"alpha": "α", "beta": "β", "gamma": "γ", "delta": "δ", "theta": "θ", "lambda": "λ", "mu": "μ", "pi": "π",
           "rho": "ρ", "sigma": "σ", "phi": "φ", "omega": "ω", "Delta": "Δ", "Omega": "Ω", "Sigma": "Σ"}
_SIMBOLI = {"cdot": "·", "times": "×", "div": "÷", "pm": "±", "leq": "≤", "le": "≤", "geq": "≥", "ge": "≥", "neq": "≠",
            "ne": "≠", "approx": "≈", "infty": "∞", "rightarrow": "→", "to": "→", "Rightarrow": "⇒", "in": "∈",
            "sum": "Σ", "int": "∫", "circ": "°", "degree": "°", "ldots": "...", "dots": "...", "%": "%"}
_APICI = str.maketrans("0123456789+-n", "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻ⁿ")
_PEDICI = str.maketrans("0123456789", "₀₁₂₃₄₅₆₇₈₉")


def _intero(x: str) -> str:
    return x if re.fullmatch(r"[\w.,²³√]+", x) else f"({x})"


def formule_in_testo(testo: str) -> str:
    """Il LaTeX che arriva dalla lettura delle foto diventa testo normale: x², √2, (x+3)/(x−1)."""
    t = testo or ""
    if "\\" not in t and "$" not in t and "^" not in t and "_{" not in t:
        return t
    t = re.sub(r"\\\[|\\\]|\\\(|\\\)|\$+", "", t)
    t = t.replace("\\left", "").replace("\\right", "").replace("\\,", " ").replace("\\;", " ").replace("\\ ", " ")
    frazione = re.compile(r"\\[dt]?frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}")
    radice = re.compile(r"\\sqrt\s*\{([^{}]*)\}")
    for _ in range(4):  # frazioni e radici una dentro l'altra
        t = frazione.sub(lambda m: f"{_intero(m.group(1).strip())}/{_intero(m.group(2).strip())}", t)
        t = radice.sub(lambda m: f"√{_intero(m.group(1).strip())}", t)
    t = re.sub(r"\\sqrt\s*(\w)", r"√\1", t)
    t = re.sub(r"\\(?:text|mathrm|mathbf)\s*\{([^{}]*)\}", r"\1", t)

    def simbolo(m: re.Match) -> str:
        nome = m.group(1)
        return _GRECHE.get(nome) or _SIMBOLI.get(nome) or nome

    t = re.sub(r"\\([A-Za-z]+)", simbolo, t)
    t = re.sub(r"\^\{([0-9+\-n]+)\}", lambda m: m.group(1).translate(_APICI), t)
    t = re.sub(r"\^(-?\d+|n)", lambda m: m.group(1).translate(_APICI), t)
    t = re.sub(r"\^\{([^{}]*)\}", r"^(\1)", t)
    t = re.sub(r"_\{?([0-9]+)\}?", lambda m: m.group(1).translate(_PEDICI), t)
    t = t.replace("{", "").replace("}", "")
    return " ".join(t.split(" ")).strip()
