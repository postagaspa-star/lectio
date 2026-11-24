"""La verifica simulata (contratto 21): nel formato delle verifiche vere del prof, solo su quello
che c'è nei documenti. Gli esercizi non si inventano: si ripropongono solo quelli scritti nei
documenti, con gli stessi numeri. Dove manca la soluzione, si confronta da soli."""

import json
import logging
import math
import re
import shutil
import sqlite3
from datetime import date
from pathlib import Path

from . import ai, allegati, calcolo, clock, controllo, db, esercizi, lampo, memoria, prompt, prompt_studio, stile
from . import scheda as schede
from .cartella import Cartella
from .lezione import Annullata, ErroreLezione
from .rete import ErroreFornitore
from .spesa import TettoRaggiunto

log = logging.getLogger("uvicorn.error")

CARATTERI_SIMULATA = 45_000
SOGLIA_SUFFICIENZA = 0.6
ESITI = ("giusta", "parziale", "sbagliata")
FRASE_NON_CORRETTA = "Di questo esercizio non ho la soluzione: confronta tu con il libro o con la verifica."
_CALCOLO = re.compile(r"\d\s*[=+^/×·*]|[=+^]\s*\d|\bx\s*[=+^²]", re.I)


def domande_circa(minuti: int) -> int:
    return max(3, min(25, round(minuti / 6)))


def _lista(v) -> list:
    return v if isinstance(v, list) else ([v] if isinstance(v, str) else [])


def _normale(testo: str) -> str:
    return " ".join(re.findall(r"[a-zàèéìòù0-9]+", str(testo or "").lower()))


def e_esercizio(tipo: str, testo: str) -> bool:
    return tipo == "esercizio" or bool(_CALCOLO.search(testo))


def valida(o: dict) -> list[str]:
    domande = o.get("domande")
    if not isinstance(domande, list) or not domande:
        return ['servono le "domande"']
    if any(not isinstance(d, dict) or not str(d.get("testo") or "").strip() for d in domande):
        return ['ogni domanda deve avere il suo "testo"']
    if o.get("testi_da_leggere") is not None and not isinstance(o["testi_da_leggere"], list):
        return ['"testi_da_leggere" deve essere una lista']
    return []


# --------------------------------------------------------------------------- testi da leggere (contratto 21.2)

TESTO_DA_LEGGERE_MASSIMO = 1500


def _brano_pulito(testo) -> str:
    righe = [" ".join(r.split()) for r in str(testo or "").splitlines()]
    testo = "\n".join(r for r in righe if r)
    if len(testo) > TESTO_DA_LEGGERE_MASSIMO:
        testo = testo[:TESTO_DA_LEGGERE_MASSIMO].rsplit(" ", 1)[0]
    return testo


def copiato(testo: str, tutto_normale: str) -> bool:
    """Il testo da leggere è copiato dai materiali? A pezzi di 8 parole, quasi tutti devono esserci (la lettura delle
    foto può sbagliare qualche lettera)."""
    parole = _normale(testo).split()
    if len(parole) < 5:
        return False
    pezzi = [" ".join(parole[i:i + 8]) for i in range(0, len(parole), 8)]
    pezzi = [x for x in pezzi if len(x.split()) >= 4] or pezzi
    return sum(1 for x in pezzi if x in tutto_normale) / len(pezzi) >= 0.85


def _testi_da_leggere(o: dict, brani: list[dict], tutto_normale: str) -> dict:
    per_id = {b["id"]: b for b in brani}
    uscita = {}
    for x in _lista(o.get("testi_da_leggere")):
        if not isinstance(x, dict):
            continue
        tid, testo = str(x.get("id") or "").strip(), _brano_pulito(x.get("testo"))
        if tid and testo:
            b = per_id.get(str(x.get("da") or "").strip())
            uscita[tid] = {"testo": testo, "fonte": memoria.etichetta(b) if b else None, "ok": copiato(testo, tutto_normale)}
    return uscita


def brano_di(d: dict, testi: dict, tutto_normale: str) -> tuple[dict | None, str | None]:
    """Il testo da leggere di una domanda e, se c'è, il problema. Arriva come id di `testi_da_leggere` o, dalle
    modifiche, già scritto nella domanda."""
    rif = d.get("da_leggere")
    if rif not in (None, "", "null"):
        t = testi.get(str(rif).strip())
        if t is None:
            return None, f"parla del testo da leggere «{rif}», che non c'è in \"testi_da_leggere\""
        if not t["ok"]:
            return None, (f'ha un testo da leggere ("{rif}") che non è copiato parola per parola dai materiali: copialo '
                          "uguale o togli il testo e la domanda")
        return {"testo": t["testo"], "fonte": t["fonte"]}, None
    b = d.get("brano")
    if isinstance(b, str):
        b = {"testo": b}
    if isinstance(b, dict) and str(b.get("testo") or "").strip():
        testo = _brano_pulito(b["testo"])
        if not copiato(testo, tutto_normale):
            return None, "ha un testo da leggere che non è copiato parola per parola dai materiali"
        return {"testo": testo, "fonte": " ".join(str(b.get("fonte") or "").split())[:120] or None}, None
    return None, None


def domande_rotte(o: dict, brani: list[dict] | None = None, numeri: bool = False, scheda: dict | None = None) -> list[str]:
    """Le domande da sistemare. Non stanno in `valida`: due risposte con una crocetta rotta farebbero fallire
    tutta la verifica; così si chiede una correzione e quello che resta rotto si toglie (`normalizza`).
    Dal 23/11/2026 anche gli esercizi con i numeri che il programma non sa risolvere o che hanno gli stessi
    numeri dei materiali (contratto 21.1); dal 24/11/2026 i tipi di domanda più frequenti che nelle verifiche del
    prof (`fuori_misura`)."""
    materiali = _materiali(brani) if brani else None
    tutto = _normale(" ".join(b["testo"] for b in brani)) if brani else ""
    testi = _testi_da_leggere(o, brani, tutto) if brani else {}
    problemi, voci = {}, []
    for i, d in enumerate(o.get("domande") or [], start=1):
        if not isinstance(d, dict):
            continue
        conti = None
        problema = domanda_chiusa_rotta(d) or (brano_di(d, testi, tutto)[1] if brani else None)
        if not problema:
            try:
                conti = prepara_conti(d, numeri, materiali)
            except esercizi.EsercizioRotto as e:
                problema, conti = f"ha questo problema: {e}", {}
            else:
                problema = (esercizio_incompleto(d) if conti is None else None) or manca_figura(d, conti) or procedimento_dato(d, conti)
        voci.append((i, d, conti))
        if problema:
            problemi[i] = problema
    misura = fuori_misura([(d, conti) for _, d, conti in voci], brani, scheda, numeri)
    for k, (i, _, _) in enumerate(voci):
        if k in misura and i not in problemi:
            problemi[i] = misura[k]
    return [f"la domanda {i} {p}" for i, p in sorted(problemi.items())][:8]


_TEORIA = re.compile(
    r"\b(?:che\s+)?cos['’]\s*è(?!\w)|\bche\s+cosa\s+è(?!\w)|\b(?:che\s+)?cosa\s+(?:si\s+intende|significa|vuol\s+dire)\b"
    r"|\bdefinisci\b|\b(?:dai|dare|scrivi|fornisci)\s+la\s+definizione\b|\benuncia\b"
    r"|\bdescrivi\s+(?:i\s+passaggi|il\s+procedimento|il\s+metodo|come)\b"
    r"|\bspiega\s+(?:come\s+si|che\s+cosa|cosa|il\s+significato)\b"
    r"|\bcome\s+si\s+(?:rappresenta|calcola|definisce|esprime|scrive|ottiene|chiama)\b", re.I)
_TEORIA_DESCRITTA = re.compile(r"teori|definizion|domand[ae]\s+apert", re.I)


def teoria_esclusa(brani: list[dict] | None, scheda: dict | None) -> bool:
    """24/11/2026, fisica: la verifica vera del prof aveva solo esercizi e problemi, quella di prova quattro domande di
    teoria prese dagli appunti. Vero solo quando si sa com'è una sua verifica (verifiche tra i materiali o le consegne
    del modulo, 5.1) e lì non c'è nessuna domanda di teoria."""
    scheda = scheda or {}
    m = scheda.get("verifiche") or {}
    suoi = [b["testo"] for b in brani or [] if b.get("tipo") == "verifica"] + [str(c) for c in m.get("consegne") or []]
    if not suoi:
        return False
    if any(isinstance(t, dict) and t.get("tipo") == "definizione" for t in scheda.get("tipi_domanda") or []):
        return False
    descritto = " ".join([str(m.get("struttura") or ""), str(m.get("cosa_da") or "")] + [str(r) for r in m.get("richieste") or []])
    if _TEORIA_DESCRITTA.search(descritto):
        return False
    return not any(_TEORIA.search(t) for t in suoi)


def tipo_della_prova(d: dict, conti: dict | None, definizioni: bool) -> str:
    """Il tipo di una domanda della verifica di prova, contato come la scheda conta quelle del prof (scheda.tipo_vero).
    Con i conti è un esercizio; una domanda aperta che chiede una definizione è una definizione se il prof le conta
    a parte."""
    if conti is not None:
        return "esercizio"
    testo = str(d.get("testo") or "")
    tipo = schede.tipo_vero(d.get("tipo"), testo)
    if definizioni and tipo in ("aperta", "altro") and schede._DEFINIZIONE.search(testo):
        return "definizione"
    if not definizioni and tipo == "definizione":
        return "aperta"
    return tipo


def _per_cento(q: float) -> str:
    return f"{int(round(100 * q))}%"


def fuori_misura(voci: list[tuple[dict, dict | None]], brani: list[dict] | None, scheda: dict | None,
                 numeri: bool) -> dict[int, str]:
    """Le domande di troppo rispetto alle verifiche del prof (Andrea, 24/11/2026: se il prof fa il 20% di
    definizioni e la prova il 40%, è lo stesso errore delle quattro domande di teoria in fisica). Per ogni tipo la
    prova ne può avere al massimo quante ne farebbe il prof con le sue quote in «Tipi di domanda» (arrotondato per
    eccesso); le altre, dalla fine, sono di troppo. Nelle materie con i numeri si contano a parte anche le domande
    di teoria travestite da aperte («spiega come si calcola», «descrivi i passaggi»): zero se le sue verifiche non ne
    hanno (`teoria_esclusa`), se no al massimo la sua quota di definizioni. Gli esercizi con i conti non sono mai
    di troppo: il prof può chiamare «domande aperte» i suoi problemi. Ritorna indice nella lista → problema."""
    n = len(voci)
    if n < 3:
        return {}
    scheda = scheda or {}
    quote = {}
    for t in scheda.get("tipi_domanda") or []:
        if isinstance(t, dict) and t.get("tipo"):
            try:
                quote[str(t["tipo"])] = float(t.get("quota") or 0)
            except (TypeError, ValueError):
                continue
    problemi: dict[int, str] = {}
    if quote:
        suoi = ", ".join(f"{prompt.TIPI_DOMANDA_IN_PAROLE.get(t, t)} {_per_cento(q)}" for t, q in quote.items() if q > 0)
        gruppi: dict[str, list[int]] = {}
        for k, (d, conti) in enumerate(voci):
            gruppi.setdefault(tipo_della_prova(d, conti, "definizione" in quote), []).append(k)
        for tipo, indici in gruppi.items():
            if numeri and tipo == "esercizio":
                continue
            q = quote.get(tipo, 0.0)
            massimo = math.ceil(q * n - 1e-9)
            nome = prompt.TIPI_DOMANDA_IN_PAROLE.get(tipo, tipo)
            quanto = f"il prof ne fa circa il {_per_cento(q)}" if q > 0 else "il prof non ne fa"
            for k in indici[massimo:]:
                problemi[k] = (f"è di troppo: è tra le {nome}, ma {quanto} e qui sono {len(indici)} su {n}. Sostituiscila con "
                               f"una domanda dei tipi che usa lui ({suoi}), sugli stessi argomenti")
    if numeri:
        q = 0.0 if teoria_esclusa(brani, scheda) else quote.get("definizione")
        if q is not None:
            teoria = [k for k, (d, conti) in enumerate(voci) if conti is None and (
                str(d.get("tipo") or "").strip().lower() == "definizione" or _TEORIA.search(str(d.get("testo") or "")))]
            quanto = ("nelle verifiche del prof non ce ne sono" if q == 0 else
                      f"il prof ne fa circa il {_per_cento(q)} e qui sono {len(teoria)} su {n}")
            for k in teoria[math.ceil(q * n - 1e-9):]:
                problemi[k] = (f"è una domanda di teoria, ma {quanto}: sostituiscila con un esercizio o un problema "
                               "come i suoi, sugli stessi argomenti")
    return problemi


def _materiali(brani: list[dict]) -> tuple[str, set]:
    tutto = " ".join(b["testo"] for b in brani)
    return calcolo.compatta(tutto), calcolo.numeri_del_testo(tutto)


def prepara_conti(d: dict, numeri: bool, materiali: tuple | None) -> dict | None:
    """None se la domanda non chiede conti; se no il calcolo risolto, la tabella e il grafico.
    EsercizioRotto se il programma non lo sa risolvere o se ha gli stessi numeri dei materiali."""
    if not esercizi.chiede_conti(d, numeri):
        return None
    testo = stile.formule_in_testo(" ".join(str(d.get("testo") or "").split()))
    tab, graf = esercizi.tabella(d.get("tabella")), esercizi.grafico(d.get("grafico"))
    visibile = esercizi.testo_visibile({"testo": testo, "tabella": tab, "grafico": graf})
    r = esercizi.risolvi(d, visibile, esercizi.numeri_visibili(testo, tab, graf))
    if materiali and esercizi.stessi_numeri(r["calcolo"], *materiali):
        raise esercizi.EsercizioRotto("ha gli stessi numeri dell'esercizio dei materiali: riscrivilo con numeri nuovi")
    return {**r, "tabella": tab, "grafico": graf}


def procedimento_dato(d: dict, conti: dict | None) -> str | None:
    """Un esercizio con un valore da calcolare non deve dare la formula nel testo (segnalato da Andrea il 24/11/2026)."""
    if not conti or conti["calcolo"].get("tipo") != "valore":
        return None
    formule = esercizi.formule_nel_testo(stile.formule_in_testo(str(d.get("testo") or "")))
    if not formule:
        return None
    return (f"dà il procedimento nella consegna: nel testo c'è «{formule[0]}». Togli la formula e ogni indicazione su come "
            "si calcola: il testo dice solo i dati e cosa calcolare (per esempio «Calcola l'errore relativo»)")


def manca_figura(d: dict, conti: dict | None) -> str | None:
    testo = str(d.get("testo") or "")
    tab = conti["tabella"] if conti else esercizi.tabella(d.get("tabella"))
    graf = conti["grafico"] if conti else esercizi.grafico(d.get("grafico"))
    if re.search(r"\btabell", testo, re.I) and not tab:
        return 'parla di una tabella ma "tabella" manca o è scritta male'
    if re.search(r"\bgrafic", testo, re.I) and not graf:
        return 'parla di un grafico ma "grafico" manca o è scritto male'
    return None


_ARROTONDA = re.compile(r"arrotond|approssim|cifre significative", re.I)
_UNITA = re.compile(r"\d\)?\s*(?:mm|cm|dm|km|mg|kg|g|ms|min|s|h|kN|N|kJ|J|kW|W|kPa|Pa|K|°C|mL|ml|L|m)(?:\^?[23²³]|/s²?)?(?![A-Za-zà-ù])")
_UNITA_A_PAROLE = re.compile(r"\b(?:milli|centi|deci|chilo)?(?:metr|gramm|litr|second)[io]\b|\bnewton\b|\bjoule\b|\bwatt\b|\bkelvin\b|\bgradi\b", re.I)


def esercizio_incompleto(d: dict) -> str | None:
    """23/11/2026, fisica: "Calcola la semidispersione massima per le misure: 1,63; 1,65…" senza i centimetri e senza
    chiedere di arrotondare, ma la risposta attesa era "0,025 cm, che si arrotonda a 0,03 cm". Si chiede di sistemarlo;
    se resta così, l'esercizio non si toglie: ci pensa la correzione a non chiedere quello che il testo non chiede."""
    testo, attesa = str(d.get("testo") or ""), str(d.get("risposta_attesa") or "")
    if not attesa or not e_esercizio(str(d.get("tipo") or ""), testo):
        return None
    if _ARROTONDA.search(attesa) and not _ARROTONDA.search(testo):
        return 'ha la risposta attesa arrotondata ma il "testo" non chiede di arrotondare'
    if re.search(r"\d", testo) and _UNITA.search(attesa) and not (_UNITA.search(testo) or _UNITA_A_PAROLE.search(testo)):
        return 'ha l\'unità di misura nella risposta attesa ma non nei dati del "testo"'
    return None


def domanda_chiusa_rotta(d: dict) -> str | None:
    """1.3.5: Small 4 scriveva crocette senza le opzioni, vero/falso senza dire se è vero, collegamenti senza
    le due liste e crocette con la risposta "non è specificato" (quando il materiale si leggeva male)."""
    tipo = str(d.get("tipo") or "").strip().lower().replace(" ", "_")
    testo = str(d.get("testo") or "")
    attesa = d.get("risposta_attesa")
    if attesa not in (None, "") and not isinstance(attesa, str):
        return 'ha "risposta_attesa" che non è un testo'
    if tipo == "scelta_multipla" and not (re.search(r"(?:^|\s)a\)", testo) and re.search(r"(?:^|\s)b\)", testo)):
        return 'è a scelta multipla ma nel "testo" mancano le opzioni "a) … b) … c) …"'
    if tipo == "vero_falso" and not re.match(r"\s*(?:vero|falso)\b", str(attesa or ""), re.I):
        return 'è vero o falso ma "risposta_attesa" non comincia con "Vero" o "Falso"'
    if tipo == "collegamento" and not (re.search(r"(?:^|\s)1[.)]", testo) and re.search(r"(?:^|\s)a\)", testo)):
        return 'è un collegamento ma nel "testo" mancano le due liste "1. … 2. …" e "a) … b) …"'
    if tipo in ("scelta_multipla", "vero_falso") and _NON_SPECIFICATO.search(f"{testo} {attesa or ''}"):
        return 'ha un\'opzione "non è specificato": chiedi una cosa scritta chiaramente nei materiali'
    return None


_NON_SPECIFICATO = re.compile(r"\bnon (?:è |viene )?(?:specificat|indicat)[oa]\b|\bnon è detto nel\b", re.I)


def normalizza(o: dict, brani: list[dict], numeri: bool = False, scheda: dict | None = None) -> list[dict]:
    """Le domande utilizzabili. Un esercizio senza numeri che non è scritto nei documenti non passa; uno con i numeri
    passa solo con numeri nuovi e un calcolo che il programma sa risolvere (contratto 21.1). La teoria che il prof non
    chiede e i tipi di troppo (`fuori_misura`) si tolgono se resta almeno metà della verifica."""
    per_id = {b["id"]: b for b in brani}
    tutto = _normale(" ".join(b["testo"] for b in brani))
    parole = set(tutto.split())
    materiali = _materiali(brani)
    testi = _testi_da_leggere(o, brani, tutto)
    contate = []
    uscita = []
    for d in o["domande"]:
        if not isinstance(d, dict):
            continue
        testo = stile.formule_in_testo(" ".join(str(d.get("testo") or "").split()))
        tipo = str(d.get("tipo") or "").strip().lower().replace(" ", "_")
        tipo = tipo if tipo in prompt.TIPI_DOMANDA else "aperta"
        if not testo:
            continue
        if domanda_chiusa_rotta(d):
            log.info("simulata: domanda chiusa scartata perché senza opzioni o senza soluzione")
            continue
        try:
            conti = prepara_conti(d, numeri, materiali)
        except esercizi.EsercizioRotto as e:
            log.info("simulata: esercizio con i numeri scartato (%s)", e)
            continue
        if conti is None and e_esercizio(tipo, testo) and not schede._trovata(testo, tutto, parole):
            log.info("simulata: esercizio scartato perché non è nei documenti")
            continue
        if manca_figura(d, conti):
            log.info("simulata: domanda scartata perché parla di una tabella o di un grafico che non c'è")
            continue
        brano, problema = brano_di(d, testi, tutto)
        if problema:
            log.info("simulata: domanda scartata perché il testo da leggere non va (%s)", problema)
            continue
        if procedimento_dato({**d, "testo": testo}, conti):
            senza = esercizi.senza_formule(testo)
            if senza is None:
                log.info("simulata: esercizio scartato perché senza la formula del procedimento non si capisce cosa chiede")
                continue
            log.info("simulata: tolta dal testo la formula del procedimento")
            testo = senza
        try:
            punti = max(1, int(float(d.get("punti") or 0)))
        except (TypeError, ValueError):
            punti = 10
        ids = [str(i) for i in _lista(d.get("fonti")) if str(i) in per_id]
        metodo = stile.formule_in_testo(" ".join(str(d.get("risposta_attesa") or "").split()))
        voce = {"n": len(uscita) + 1, "tipo": tipo, "testo": testo, "punti": punti,
                "sezione": " ".join(str(d.get("sezione")).split())[:80] if d.get("sezione") else None, "brano": brano,
                "risposta_attesa": metodo, "fonti": memoria.fonti_di(ids, brani),
                "forma": "formule" if numeri and tipo not in esercizi.TIPI_CHIUSI else "testo",
                "tabella": conti["tabella"] if conti else esercizi.tabella(d.get("tabella")),
                "grafico": conti["grafico"] if conti else esercizi.grafico(d.get("grafico"))}
        if conti:
            voce.update(tipo="esercizio", forma="conti", risultati=conti["risultati"], calcolo=conti["calcolo"], metodo=metodo,
                        risposta_attesa=f"{metodo} {conti['soluzione']}".strip())
        contate.append(({**d, "tipo": tipo, "testo": testo}, conti))
        uscita.append(voce)
    via = sorted(fuori_misura(contate, brani, scheda, numeri))
    resta = max(2, math.ceil(len(uscita) / 2))
    via = via[len(via) - max(0, len(uscita) - resta):] if len(uscita) - len(via) < resta else via
    if via:
        log.info("simulata: tolte %d domande di tipi che il prof fa meno spesso", len(via))
        uscita = [v for k, v in enumerate(uscita) if k not in set(via)]
        for k, v in enumerate(uscita, start=1):
            v["n"] = k
    return uscita


class PreparazioneSimulata:
    def __init__(self, ctx, simulata_id: int):
        self.ctx = ctx
        self.id = simulata_id
        self.c = Cartella(cartella(ctx.settings.dati_dir, simulata_id))
        self.riferimento = f"simulata:{simulata_id}"
        with db.aperta(ctx.settings.db_path) as conn:
            r = conn.execute("SELECT s.*, m.nome AS materia_nome, m.prof AS materia_prof FROM simulate s JOIN materie m"
                             " ON m.id = s.materia_id WHERE s.id = ?", (simulata_id,)).fetchone()
        if r is None:
            raise Annullata()
        self.riga = dict(r)

    def controlla(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM simulate WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def _avanza(self, fase: str, frase: str) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            conn.execute("UPDATE simulate SET fase = ?, frase = ? WHERE id = ? AND stato = 'in_preparazione'", (fase, frase, self.id))

    def esegui(self) -> None:
        self.controlla()
        self._avanza("lettura", "Leggo i documenti")
        lettura = self._lettura()
        self.controlla()
        self._avanza("scrittura", "Preparo le domande")
        minuti = self.riga["minuti"]
        modello = self.ctx.settings.modello_scrittura
        n = domande_circa(minuti)
        numeri = esercizi.con_i_numeri(self.riga["materia_nome"])
        messaggi = prompt_studio.messaggi_simulata(lettura, minuti, n)
        dati = {"lettura": lettura, "domande_circa": n}
        max_token = min(10000, 900 + 450 * n)
        comuni = {"cassa": self.ctx.cassa, "fornitore": self.ctx.testo, "materia_id": self.riga["materia_id"],
                  "prima_di_ogni_chiamata": self.controlla, "riferimento": self.riferimento}
        grezzo = self.c.leggi("grezzo.json")
        if grezzo is None:
            grezzo = ai.chiedi_json(compito="simulata", messaggi=messaggi, modello=modello, valida=valida, max_token=max_token,
                                    cosa="Verifica simulata", dati=dati, **comuni)
            rotte = domande_rotte(grezzo, lettura["brani"], numeri, lettura.get("scheda"))
            if rotte:
                try:
                    nuovo = ai.chiedi_json(compito="simulata", modello=modello, valida=valida, max_token=max_token, dati=dati,
                                           messaggi=messaggi + [{"role": "assistant", "content": json.dumps(grezzo, ensure_ascii=False)},
                                                                {"role": "user", "content": prompt.correzione(rotte)}],
                                           cosa="Verifica simulata (domande da sistemare)", **comuni)
                except ai.RispostaNonUsabile:
                    nuovo = None
                if nuovo and len(domande_rotte(nuovo, lettura["brani"], numeri, lettura.get("scheda"))) < len(rotte):
                    grezzo = nuovo
            self.c.scrivi("grezzo.json", grezzo)
        self._avanza("controllo", "Controllo le domande")
        riveduto = controllo.rivedi(self.ctx, grezzo, lettura["brani"], compito="simulata", messaggi=messaggi, modello=modello, valida=valida,
                                    max_token=max_token, cosa="Verifica simulata", altro=prompt.scheda_in_parole(lettura.get("scheda")),
                                    materia_id=self.riga["materia_id"], prima=self.controlla, dati=dati, parlato=False,
                                    riferimento=self.riferimento)
        domande = normalizza(riveduto, lettura["brani"], numeri, lettura.get("scheda"))
        if not domande:
            raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza per farci una verifica: aggiungi il libro o una verifica vera.")
        ci_sono_verifiche = any(b["tipo"] == "verifica" for b in lettura["brani"]) or prompt.modulo_verifiche(lettura.get("scheda"))
        nota = None if ci_sono_verifiche else "Non hai ancora caricato verifiche di questa materia: il formato non è per forza quello del prof."
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            r = conn.execute("SELECT stato FROM simulate WHERE id = ?", (self.id,)).fetchone()
            if r is None or r["stato"] != "in_preparazione":
                raise Annullata()
            titolo = " ".join(str(riveduto.get("titolo") or "").split())[:120] or f"Verifica su {self.riga['argomento']}"
            conn.execute("UPDATE simulate SET stato = 'pronta', fase = NULL, frase = NULL, errore = NULL, nota = ?, titolo = ?, domande = ?"
                         " WHERE id = ?", (nota, stile.ripulisci(titolo), json.dumps(domande, ensure_ascii=False), self.id))
            db.aggiungi_novita(conn, "simulata_pronta", f"La verifica di prova di {self.riga['materia_nome']} è pronta", materia_id=self.riga["materia_id"])

    def _lettura(self) -> dict:
        salvata = self.c.leggi("lettura.json")
        if salvata:
            return salvata
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
        cerca = " ".join(x for x in (self.riga["argomento"], self.riga["indicazioni"]) if x)
        if sum(len(b["testo"]) for b in brani) > CARATTERI_SIMULATA:
            brani = memoria.scegli(self.ctx, cerca, brani, CARATTERI_SIMULATA, materia_id=materia_id, prima=self.controlla)
        lettura = {"argomento": self.riga["argomento"], "indicazioni": self.riga["indicazioni"],
                   "materia": {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]},
                   "evento": evento, "scheda": scheda, "brani": brani}
        self.c.scrivi("lettura.json", lettura)
        return lettura


def voto_stimato(punti: float, totali: float, quota: float | None = None) -> float | None:
    """Il voto in decimi. Con la sufficienza del prof (5.1) quella soglia vale 6: sopra si sale fino a 10, sotto si
    scende fino a 1; senza, il voto è in proporzione ai punti."""
    if not totali:
        return None
    if not quota:
        return max(1.0, round(20 * punti / totali) / 2)
    soglia = quota * totali
    voto = 6 + 4 * (punti - soglia) / (totali - soglia) if punti >= soglia else 1 + 5 * punti / soglia
    return max(1.0, min(10.0, round(voto * 2) / 2))


def _con_brano(d: dict) -> str:
    testo = esercizi.testo_visibile(d)
    brano = d.get("brano")
    return f"Testo da leggere: {brano['testo']}\n{testo}" if isinstance(brano, dict) and brano.get("testo") else testo


def _mezzi(x: float) -> int | float:
    """I punti al mezzo punto, come nelle verifiche vere: 1.5 resta 1.5, 3.0 diventa 3."""
    x = round(float(x) * 2) / 2
    return int(x) if x.is_integer() else x


def _valida_correzione(o: dict) -> list[str]:
    domande = o.get("domande")
    if not isinstance(domande, list) or any(not isinstance(d, dict) for d in domande):
        return ['servono le "domande" corrette']
    return []


def correggi(ctx, simulata_id: int) -> None:
    """La correzione: domande senza soluzione "confronta tu", risposte vuote sbagliate senza chiedere a nessuno."""
    with db.aperta(ctx.settings.db_path) as conn:
        r = conn.execute("SELECT * FROM simulate WHERE id = ? AND stato = 'in_correzione'", (simulata_id,)).fetchone()
        if r is None:
            return
        scheda = schede.per_lezione(conn, r["materia_id"])
    domande = json.loads(r["domande"])
    risposte = json.loads(r["risposte"] or "{}")
    risultati = json.loads(r["risultati"] or "{}")
    file_di = allegati.elenco(r)
    scritto: dict[int, str] = {}
    controlli: dict[int, list[dict]] = {}
    for d in domande:
        n = str(d["n"])
        passaggi = str(risposte.get(n) or "").strip()
        miei = [str(x or "").strip() for x in risultati.get(n) or []]
        letti = [(a["nome"], allegati.testo(ctx, simulata_id, r["materia_id"], a)) for a in file_di.get(n) or []] if d["risposta_attesa"] else []
        if d.get("calcolo") and (passaggi or any(miei) or letti):
            controlli[d["n"]] = esercizi.controlla(d, passaggi + "\n" + "\n".join(t for _, t in letti), miei)
        parti = [passaggi] if passaggi else []
        nomi = d.get("risultati") or []
        if any(miei):
            parti.append("Risultati scritti: " + "; ".join(f"{nomi[i] if i < len(nomi) else 'Risultato'}: {x}" for i, x in enumerate(miei) if x))
        parti += [f"Testo letto dal file «{nome}»:\n{t}" if t else f"(il file «{nome}» non si è riuscito a leggere)" for nome, t in letti]
        if parti:
            scritto[d["n"]] = "\n".join(parti)
    voci = [{"n": d["n"], "punti": d["punti"], "testo": _con_brano(d), "risposta_attesa": d["risposta_attesa"],
             "risposta": scritto[d["n"]], "controllo": esercizi.controllo_in_parole(controlli[d["n"]]) if d["n"] in controlli else None}
            for d in domande if d["risposta_attesa"] and d["n"] in scritto]
    corrette: dict[int, dict] = {}
    if voci:
        o = ai.chiedi_json(cassa=ctx.cassa, fornitore=ctx.testo, compito="correzione_simulata",
                           messaggi=prompt_studio.messaggi_correzione_simulata(voci, scheda), modello=ctx.settings.modello_piano,
                           valida=_valida_correzione, max_token=min(6000, 300 + 180 * len(voci)), cosa="Correzione della verifica simulata",
                           dati={"voci": voci}, materia_id=r["materia_id"], riferimento=f"simulata:{simulata_id}")
        for x in o["domande"]:
            try:
                corrette[int(x.get("n"))] = x
            except (TypeError, ValueError):
                continue
    uscita = []
    punti, totali, da_rifare = 0, 0, 0
    for d in domande:
        risposta = scritto.get(d["n"], "")
        base = {"n": d["n"], "risposta_attesa": d["risposta_attesa"], "fonti": d.get("fonti") or []}
        if d.get("calcolo"):
            base["controllo"] = controlli.get(d["n"]) or esercizi.controlla(d, "", [])
        if not d["risposta_attesa"]:
            uscita.append({**base, "esito": "non_corretta", "punti": 0, "commento": FRASE_NON_CORRETTA, "mancava": []})
            continue
        totali += d["punti"]
        c = corrette.get(d["n"]) if risposta else None
        if c is None or str(c.get("esito")).strip().lower() not in ESITI:
            esito, presi, commento, mancava = "sbagliata", 0, "Qui non hai risposto." if not risposta else "Non sono riuscito a correggerla: confronta con la risposta.", []
        else:
            esito = str(c["esito"]).strip().lower()
            try:
                presi = _mezzi(max(0.0, min(float(d["punti"]), float(c.get("punti") or 0))))
            except (TypeError, ValueError):
                presi = 0
            commento = stile.ripulisci(" ".join(str(c.get("commento") or "").split()))[:400]
            mancava = [" ".join(str(m).split())[:80] for m in _lista(c.get("mancava")) if str(m).strip()][:3]
        punti += presi
        da_rifare += int(esito != "giusta")
        uscita.append({**base, "esito": esito, "punti": presi, "commento": commento, "mancava": mancava})
    punti = _mezzi(punti)
    quota = schede.sufficienza_del_prof(scheda)  # la sufficienza del prof, se il modulo delle verifiche la sa (5.1)
    soglia = _mezzi(quota * totali) if quota else round(SOGLIA_SUFFICIENZA * totali)
    esito_finale = {"punti": punti, "punti_totali": totali, "voto_stimato": voto_stimato(punti, totali, quota), "soglia": soglia,
                    "domande": uscita, "da_rifare": da_rifare}
    per_n = {d["n"]: d for d in domande}
    with db.aperta(ctx.settings.db_path) as conn, db.transazione(conn):
        cur = conn.execute("UPDATE simulate SET stato = 'corretta', frase = NULL, errore = NULL, esito = ? WHERE id = ? AND stato = 'in_correzione'",
                           (json.dumps(esito_finale, ensure_ascii=False), simulata_id))
        if cur.rowcount == 0:
            return
        for u in uscita:
            if u["esito"] in ("parziale", "sbagliata") and u["risposta_attesa"]:
                lampo.aggiungi(conn, r["materia_id"], "simulata", f"simulata:{simulata_id}:{u['n']}", esercizi.testo_visibile(per_n[u["n"]]),
                               u["risposta_attesa"], u["fonti"])
        db.aggiungi_novita(conn, "simulata_corretta", "Ho corretto la verifica di prova", materia_id=r["materia_id"])


def cartella(dati_dir: str, simulata_id: int) -> Path:
    return Path(dati_dir) / "simulate" / str(simulata_id)


def in_errore(db_path: str, simulata_id: int, frase: str) -> None:
    with db.aperta(db_path) as conn, db.transazione(conn):
        r = conn.execute("SELECT s.materia_id, m.nome FROM simulate s JOIN materie m ON m.id = s.materia_id"
                         " WHERE s.id = ? AND s.stato IN ('in_preparazione', 'in_correzione')", (simulata_id,)).fetchone()
        if r is None:
            return
        conn.execute("UPDATE simulate SET stato = 'errore', errore = ? WHERE id = ?", (frase, simulata_id))
        db.aggiungi_novita(conn, "simulata_errore", f"La verifica di prova di {r['nome']} si è fermata: tocca Riprova", materia_id=r["materia_id"])


def _domanda_api(d: dict) -> dict:
    uscita = {k: d.get(k) for k in ("n", "tipo", "testo", "punti", "sezione", "brano", "tabella", "grafico")}
    uscita["forma"] = d.get("forma") or "testo"
    if uscita["forma"] == "conti":
        uscita["risultati"] = d.get("risultati") or []
    return uscita


def per_api(r: sqlite3.Row, completa: bool = True) -> dict:
    uscita = {
        "id": r["id"], "materia_id": r["materia_id"], "evento_id": r["evento_id"], "argomento": r["argomento"], "titolo": r["titolo"] or r["argomento"],
        "minuti": r["minuti"], "materiali": json.loads(r["materiali"] or "[]"), "indicazioni": r["indicazioni"], "stato": r["stato"], "fase": r["fase"],
        "fatto": r["fatto"], "totale": r["totale"], "frase": r["frase"], "errore": r["errore"], "nota": r["nota"], "stima_eur": r["stima_eur"],
        "creata_il": clock.per_utente(r["creata_il"]), "iniziata_il": clock.per_utente(r["iniziata_il"]) if r["iniziata_il"] else None,
        "consegnata_il": clock.per_utente(r["consegnata_il"]) if r["consegnata_il"] else None,
    }
    esito = json.loads(r["esito"]) if r["esito"] else None
    if completa:
        # Le risposte attese stanno solo dentro `esito`: prima della correzione non escono.
        uscita.update(domande=[_domanda_api(d) for d in json.loads(r["domande"] or "[]")], risposte=json.loads(r["risposte"] or "{}"),
                      risultati=json.loads(r["risultati"] or "{}"), allegati=allegati.per_api(allegati.elenco(r)), esito=esito)
    else:
        uscita.update(esito={k: esito[k] for k in ("punti", "punti_totali", "voto_stimato", "soglia", "da_rifare")} if esito else None)
    return uscita


def cancella_file(dati_dir: str, simulata_id: int) -> None:
    shutil.rmtree(cartella(dati_dir, simulata_id), ignore_errors=True)


__all__ = ["PreparazioneSimulata", "correggi", "in_errore", "per_api", "cancella_file", "TettoRaggiunto", "ErroreFornitore"]
