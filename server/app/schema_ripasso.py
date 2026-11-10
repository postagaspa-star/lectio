"""Gli schemi di ripasso (contratto 18): lettura → piano → scrittura → copertura → foto → chiusura.

Ogni fase salva quello che produce in dati/schemi/<id>/: un Riprova riparte da lì. Ogni parte
passa dal controllo sui documenti (controllo.py). Le foto sono poche, a meno che le indicazioni
ne chiedano tante, e ognuna viene guardata prima di metterla."""

import json
import logging
import re
import shutil
import sqlite3
from datetime import date
from pathlib import Path

from . import ai, clock, controllo, db, immagini, lampo, memoria, prompt_studio, stile
from . import scheda as schede
from .cartella import Cartella
from .lezione import Annullata, ErroreLezione
from .spesa import CARATTERI_PIANO_SCHEMA, CARATTERI_SEZIONE, PAROLE_SEZIONE

log = logging.getLogger("uvicorn.error")

FASI = ("lettura", "piano", "scrittura", "copertura", "immagini", "chiusura")
FRASI = {"lettura": "Leggo i documenti", "piano": "Preparo lo schema", "scrittura": "Scrivo lo schema",
         "copertura": "Controllo che ci sia tutto", "immagini": "Cerco le foto", "chiusura": "Finisco"}
TIPI_BLOCCO = ("paragrafo", "punti", "passaggi", "definizione", "tabella", "prof")
IMMAGINI_PER_LIVELLO = {"essenziale": 2, "completo": 3, "approfondito": 5}
IMMAGINI_MASSIME = 40
IMMAGINI_TANTE = 15
DIVISORE_SEZIONI = {"essenziale": 9000, "completo": 5000, "approfondito": 3500}
TOKEN_PIANO = 8000  # la lunghezza del piano dipende dai concetti dei documenti: con meno viene tagliato a metà
TOKEN_TABELLA = 3000


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


def normalizza_piano(o: dict, lettura: dict, forma: str, massimo: int) -> dict:
    validi = {b["id"] for b in lettura["brani"]}
    sezioni = []
    for s in o["sezioni"][:massimo]:
        sezioni.append({"titolo": stile.ripulisci(_corto(s["titolo"])), "concetti": [_corto(c, 160) for c in _lista(s.get("concetti")) if _corto(c)][:40],
                        "fonti": [str(f) for f in _lista(s.get("fonti")) if str(f) in validi][:12]})
    scelta = str(o.get("forma") or "").strip().lower()
    forma = forma if forma != "auto" else (scelta if scelta in ("capitoli", "schede") else "capitoli")
    concetti = [_corto(c, 160) for c in _lista(o.get("concetti")) if _corto(c)][:250] or [c for s in sezioni for c in s["concetti"]]
    titoli = tuple(x["titolo"] for x in sezioni) + tuple(concetti)
    foto = foto_valide(o.get("immagini"), len(sezioni), _vocabolario(lettura, titoli))
    return {"titolo": stile.ripulisci(_corto(o["titolo"], 120)), "forma": forma, "concetti": concetti, "sezioni": sezioni, "immagini": foto}


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
        self.c.scrivi("lettura.json", {"argomento": self.riga["argomento"], "indicazioni": self.riga["indicazioni"],
                                       "materia": {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]},
                                       "evento": evento, "scheda": scheda, "brani": brani})

    def _piano(self) -> None:
        if self.c.leggi("piano.json"):
            return
        lettura = self.c.leggi("lettura.json")
        totale = sum(len(b["testo"]) for b in lettura["brani"])
        cerca = " ".join(x for x in (lettura["argomento"], lettura.get("indicazioni")) if x)
        per_piano = {**lettura, "brani": self._cerca(cerca, lettura["brani"], CARATTERI_PIANO_SCHEMA)} if totale > CARATTERI_PIANO_SCHEMA else lettura
        livello, forma = self.riga["livello"], self.riga["forma"]
        minimo, massimo = numero_sezioni(sum(len(b["testo"]) for b in per_piano["brani"]), livello)
        oggetto = self._chiedi("schema_piano", prompt_studio.messaggi_schema_piano(
            per_piano, forma, livello, minimo, massimo, quante_immagini(livello, lettura.get("indicazioni"))),
            self.ctx.settings.modello_scrittura, valida_piano, TOKEN_PIANO, "Schema · piano",
            {"lettura": per_piano, "forma": forma, "massimo": massimo})
        self.c.scrivi("piano.json", normalizza_piano(oggetto, per_piano, forma, massimo))

    def _scrivi_sezione(self, k: int, sezione: dict, lettura: dict, piano: dict) -> dict:
        """Una parte scritta, controllata e messa in forma."""
        cerca = " ".join([sezione["titolo"], *sezione["concetti"]])
        brani = self._cerca(cerca, lettura["brani"], CARATTERI_SEZIONE, sezione["fonti"])
        parole = PAROLE_SEZIONE[self.riga["livello"]]
        messaggi = prompt_studio.messaggi_schema_sezione(lettura, piano, k, sezione, brani, parole, piano["forma"])
        modello = self.ctx.settings.modello_scrittura
        max_token = int(parole * 3.2) + 900
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
        n = len(piano["sezioni"]) + (1 if extra else 0)
        trovate = [self.c.leggi(f"sezioni/{k:02d}.json") for k in range(1, n + 1)]
        return [s for s in trovate if s and s.get("blocchi")]

    def _copertura(self) -> None:
        if self.c.leggi("copertura.json") is not None:
            return
        lettura, piano = self.c.leggi("lettura.json"), self.c.leggi("piano.json")
        sezioni = self._scritte(piano)
        if not sezioni:
            raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza su questo argomento per farci uno schema.")
        mancanti: list[str] = []
        if piano["concetti"]:
            o = self._chiedi("copertura", prompt_studio.messaggi_copertura(piano["concetti"], testo_di(sezioni)[:40_000]),
                             self.ctx.settings.modello_piano, lambda x: [] if isinstance(x.get("mancanti"), list) else ['serve "mancanti"'],
                             1200, "Schema · controllo di quello che manca", {})
            noti = set(piano["concetti"])
            mancanti = [str(c) for c in o["mancanti"] if str(c) in noti]
        rimasti = mancanti
        extra = False
        if mancanti:
            sezione = {"titolo": "Altri punti da sapere", "concetti": mancanti[:30], "fonti": []}
            con_extra = {**piano, "sezioni": piano["sezioni"] + [sezione]}
            scritta = self._scrivi_sezione(len(piano["sezioni"]) + 1, sezione, lettura, con_extra)
            extra = bool(scritta.get("blocchi"))
            testo = testo_di([scritta]).lower() if extra else ""
            rimasti = [c for c in mancanti if not all(p in testo for p in re.findall(r"[a-zàèéìòù]{5,}", c.lower())[:3])]
        tabella = None
        if piano["forma"] == "capitoli" and self.riga["livello"] != "essenziale":
            tabella = self._tabella(lettura, self._scritte(piano, extra))
        self.c.scrivi("copertura.json", {"concetti": len(piano["concetti"]), "coperti": len(piano["concetti"]) - len(rimasti),
                                         "mancanti": rimasti[:40], "extra": extra, "tabella": tabella})

    def _tabella(self, lettura: dict, sezioni: list[dict]) -> dict | None:
        testo = testo_di(sezioni)[:40_000]
        modello = self.ctx.settings.modello_scrittura
        def valida(o: dict) -> list[str]:
            return [] if isinstance(o.get("righe"), list) and o["righe"] else ['servono le "righe"']
        messaggi = prompt_studio.messaggi_schema_tabella(lettura, testo)
        dati = {"sezioni": [{"titolo": s["titolo"], "primo": next((b.get("testo") for b in s["blocchi"] if b.get("testo")), "")} for s in sezioni]}
        grezzo = self._chiedi("schema_tabella", messaggi, modello, valida, TOKEN_TABELLA, "Schema · quadro d'insieme", dati)
        riveduto = controllo.rivedi(self.ctx, grezzo, [], compito="schema_tabella", messaggi=messaggi, modello=modello, valida=valida,
                                    max_token=TOKEN_TABELLA, cosa="Schema · quadro d'insieme", altro=testo, materia_id=self.riga["materia_id"],
                                    prima=self.controlla, dati=dati, parlato=False, riferimento=self.riferimento)
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
