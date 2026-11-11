"""La verifica simulata (contratto 21): nel formato delle verifiche vere del prof, solo su quello
che c'è nei documenti. Gli esercizi non si inventano: si ripropongono solo quelli scritti nei
documenti, con gli stessi numeri. Dove manca la soluzione, si confronta da soli."""

import json
import logging
import re
import shutil
import sqlite3
from datetime import date
from pathlib import Path

from . import ai, clock, controllo, db, lampo, memoria, prompt, prompt_studio, stile
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
    return []


def domande_rotte(o: dict) -> list[str]:
    """Le domande chiuse da sistemare. Non stanno in `valida`: due risposte con una crocetta rotta
    farebbero fallire tutta la verifica; così si chiede una correzione e quello che resta rotto si toglie."""
    return [f"la domanda {i} {problema}" for i, d in enumerate(o.get("domande") or [], start=1)
            if isinstance(d, dict) and (problema := domanda_chiusa_rotta(d))][:6]


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


def normalizza(o: dict, brani: list[dict]) -> list[dict]:
    """Le domande utilizzabili: un esercizio che non è scritto nei documenti non passa."""
    per_id = {b["id"]: b for b in brani}
    tutto = _normale(" ".join(b["testo"] for b in brani))
    parole = set(tutto.split())
    uscita = []
    for d in o["domande"]:
        testo = stile.formule_in_testo(" ".join(str(d["testo"]).split()))
        tipo = str(d.get("tipo") or "").strip().lower().replace(" ", "_")
        tipo = tipo if tipo in prompt.TIPI_DOMANDA else "aperta"
        if not testo:
            continue
        if domanda_chiusa_rotta(d):
            log.info("simulata: domanda chiusa scartata perché senza opzioni o senza soluzione")
            continue
        if e_esercizio(tipo, testo) and not schede._trovata(testo, tutto, parole):
            log.info("simulata: esercizio scartato perché non è nei documenti")
            continue
        try:
            punti = max(1, int(float(d.get("punti") or 0)))
        except (TypeError, ValueError):
            punti = 10
        ids = [str(i) for i in _lista(d.get("fonti")) if str(i) in per_id]
        uscita.append({"n": len(uscita) + 1, "tipo": tipo, "testo": testo, "punti": punti,
                       "sezione": " ".join(str(d.get("sezione")).split())[:80] if d.get("sezione") else None,
                       "risposta_attesa": stile.formule_in_testo(" ".join(str(d.get("risposta_attesa") or "").split())),
                       "fonti": memoria.fonti_di(ids, brani)})
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
        messaggi = prompt_studio.messaggi_simulata(lettura, minuti, n)
        dati = {"lettura": lettura, "domande_circa": n}
        max_token = min(8000, 800 + 340 * n)
        comuni = {"cassa": self.ctx.cassa, "fornitore": self.ctx.testo, "materia_id": self.riga["materia_id"],
                  "prima_di_ogni_chiamata": self.controlla, "riferimento": self.riferimento}
        grezzo = self.c.leggi("grezzo.json")
        if grezzo is None:
            grezzo = ai.chiedi_json(compito="simulata", messaggi=messaggi, modello=modello, valida=valida, max_token=max_token,
                                    cosa="Verifica simulata", dati=dati, **comuni)
            rotte = domande_rotte(grezzo)
            if rotte:
                try:
                    nuovo = ai.chiedi_json(compito="simulata", modello=modello, valida=valida, max_token=max_token, dati=dati,
                                           messaggi=messaggi + [{"role": "assistant", "content": json.dumps(grezzo, ensure_ascii=False)},
                                                                {"role": "user", "content": prompt.correzione(rotte)}],
                                           cosa="Verifica simulata (domande da sistemare)", **comuni)
                except ai.RispostaNonUsabile:
                    nuovo = None
                if nuovo and len(domande_rotte(nuovo)) < len(rotte):
                    grezzo = nuovo
            self.c.scrivi("grezzo.json", grezzo)
        self._avanza("controllo", "Controllo le domande")
        riveduto = controllo.rivedi(self.ctx, grezzo, lettura["brani"], compito="simulata", messaggi=messaggi, modello=modello, valida=valida,
                                    max_token=max_token, cosa="Verifica simulata", altro=prompt.scheda_in_parole(lettura.get("scheda")),
                                    materia_id=self.riga["materia_id"], prima=self.controlla, dati=dati, parlato=False,
                                    riferimento=self.riferimento)
        domande = normalizza(riveduto, lettura["brani"])
        if not domande:
            raise ErroreLezione("Nei tuoi documenti non ho trovato abbastanza per farci una verifica: aggiungi il libro o una verifica vera.")
        ci_sono_verifiche = any(b["tipo"] == "verifica" for b in lettura["brani"])
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
    voci = [{"n": d["n"], "punti": d["punti"], "testo": d["testo"], "risposta_attesa": d["risposta_attesa"],
             "risposta": str(risposte.get(str(d["n"])) or "").strip()}
            for d in domande if d["risposta_attesa"] and str(risposte.get(str(d["n"])) or "").strip()]
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
        risposta = str(risposte.get(str(d["n"])) or "").strip()
        base = {"n": d["n"], "risposta_attesa": d["risposta_attesa"], "fonti": d.get("fonti") or []}
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
                presi = max(0, min(d["punti"], int(round(float(c.get("punti") or 0)))))
            except (TypeError, ValueError):
                presi = 0
            commento = stile.ripulisci(" ".join(str(c.get("commento") or "").split()))[:400]
            mancava = [" ".join(str(m).split())[:80] for m in _lista(c.get("mancava")) if str(m).strip()][:3]
        punti += presi
        da_rifare += int(esito != "giusta")
        uscita.append({**base, "esito": esito, "punti": presi, "commento": commento, "mancava": mancava})
    voto = max(1.0, round(20 * punti / totali) / 2) if totali else None
    esito_finale = {"punti": punti, "punti_totali": totali, "voto_stimato": voto, "soglia": round(SOGLIA_SUFFICIENZA * totali),
                    "domande": uscita, "da_rifare": da_rifare}
    per_n = {d["n"]: d for d in domande}
    with db.aperta(ctx.settings.db_path) as conn, db.transazione(conn):
        cur = conn.execute("UPDATE simulate SET stato = 'corretta', frase = NULL, errore = NULL, esito = ? WHERE id = ? AND stato = 'in_correzione'",
                           (json.dumps(esito_finale, ensure_ascii=False), simulata_id))
        if cur.rowcount == 0:
            return
        for u in uscita:
            if u["esito"] in ("parziale", "sbagliata") and u["risposta_attesa"]:
                lampo.aggiungi(conn, r["materia_id"], "simulata", f"simulata:{simulata_id}:{u['n']}", per_n[u["n"]]["testo"], u["risposta_attesa"], u["fonti"])
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
    return {k: d.get(k) for k in ("n", "tipo", "testo", "punti", "sezione")}


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
        uscita.update(domande=[_domanda_api(d) for d in json.loads(r["domande"] or "[]")], risposte=json.loads(r["risposte"] or "{}"), esito=esito)
    else:
        uscita.update(esito={k: esito[k] for k in ("punti", "punti_totali", "voto_stimato", "soglia", "da_rifare")} if esito else None)
    return uscita


def cancella_file(dati_dir: str, simulata_id: int) -> None:
    shutil.rmtree(cartella(dati_dir, simulata_id), ignore_errors=True)


__all__ = ["PreparazioneSimulata", "correggi", "in_errore", "per_api", "cancella_file", "TettoRaggiunto", "ErroreFornitore"]
