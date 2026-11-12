"""I riassunti scritti (contratto 26): si fanno da uno schema pronto, per punti, discorsivi o una
via di mezzo. La fonte è solo lo schema, che è già stato controllato sui documenti; il riassunto
ripassa dallo stesso controllo, confrontato con lo schema."""

import json
import re
import sqlite3

from . import ai, clock, controllo, db, prompt_studio, schema_ripasso, stile
from .lezione import Annullata, ErroreLezione

FORME = ("punti", "discorsivo", "misto")
NOME_FORMA = {"punti": "Per punti", "discorsivo": "Discorsivo", "misto": "Via di mezzo"}
PROPORZIONE = {"punti": 0.4, "misto": 0.5, "discorsivo": 0.6}
PAROLE_MINIME = 120
PAROLE_MASSIME = 1500
CARATTERI_SCHEMA = 90_000


def testo_schema(contenuto: dict) -> str:
    return schema_ripasso.testo_di(contenuto.get("sezioni") or [])[:CARATTERI_SCHEMA]


def parole_volute(testo: str, forma: str) -> int:
    return max(PAROLE_MINIME, min(PAROLE_MASSIME, round(len(testo.split()) * PROPORZIONE[forma])))


def _lista(v) -> list:
    return v if isinstance(v, list) else ([v] if isinstance(v, str) else [])


def _pulito(v) -> str:
    return stile.formule_in_testo(" ".join(str(v or "").split()))


def valida(o: dict) -> list[str]:
    parti = o.get("parti")
    if not isinstance(parti, list) or not parti or any(not isinstance(p, dict) for p in parti):
        return ['servono le "parti"']
    if not any(_lista(p.get("paragrafi")) or _lista(p.get("punti")) for p in parti):
        return ['le "parti" sono vuote']
    return []


def sezioni_usate(contenuto: dict) -> int:
    """Quante sezioni dello schema arrivano al modello (il testo si ferma a CARATTERI_SCHEMA)."""
    n, totale = 0, 0
    for s in contenuto.get("sezioni") or []:
        lunghezza = len(schema_ripasso.testo_di([s]))
        if not lunghezza:
            continue
        if totale >= CARATTERI_SCHEMA:
            break
        n, totale = n + 1, totale + lunghezza + 2
    return n


def parti_che_mancano(o: dict, sezioni: int) -> str | None:
    """1.3.5: una parte per sezione (una in meno va bene: due sezioni vicine si possono unire)."""
    if sezioni > 2 and len(o.get("parti") or []) < sezioni - max(1, sezioni // 10):
        return (f"hai scritto {len(o['parti'])} parti ma lo schema ha {sezioni} sezioni: serve una parte per ogni "
                f"sezione, nello stesso ordine, senza saltarne")
    return None


def normalizza(o: dict, forma: str) -> list[dict]:
    """Ogni parte nella forma chiesta, anche se il modello ha mischiato paragrafi e punti."""
    uscita = []
    for p in o.get("parti") or []:
        if not isinstance(p, dict):
            continue
        paragrafi = [x for x in (_pulito(v) for v in _lista(p.get("paragrafi"))) if x]
        punti = [x for x in (_pulito(v) for v in _lista(p.get("punti"))) if x]
        if forma == "punti":
            paragrafi, punti = [], punti or paragrafi
        elif forma == "discorsivo":
            paragrafi, punti = paragrafi or ([" ".join(punti)] if punti else []), []
        if not paragrafi and not punti:
            continue
        titolo = stile.ripulisci(_pulito(p.get("titolo"))[:100]) if p.get("titolo") else None
        uscita.append({"titolo": titolo or None, "paragrafi": paragrafi, "punti": punti})
    return uscita


def conta_parole(parti: list[dict]) -> int:
    return sum(len(re.findall(r"\S+", t)) for p in parti for t in [*p["paragrafi"], *p["punti"]])


class PreparazioneRiassunto:
    def __init__(self, ctx, riassunto_id: int):
        self.ctx = ctx
        self.id = riassunto_id
        with db.aperta(ctx.settings.db_path) as conn:
            r = conn.execute(
                "SELECT r.*, s.titolo AS schema_titolo, s.argomento, s.contenuto AS schema_contenuto, s.cambiato_il AS schema_cambiato_il,"
                " m.nome AS materia_nome, m.prof AS materia_prof"
                " FROM riassunti r JOIN schemi s ON s.id = r.schema_id JOIN materie m ON m.id = r.materia_id WHERE r.id = ?",
                (riassunto_id,)).fetchone()
        if r is None:
            raise Annullata()
        self.riga = dict(r)

    def controlla(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM riassunti WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def esegui(self) -> None:
        self.controlla()
        contenuto = json.loads(self.riga["schema_contenuto"] or "{}")
        testo = testo_schema(contenuto)
        if not testo.strip():
            raise ErroreLezione("Questo schema è vuoto: non c'è niente da riassumere.")
        forma = self.riga["forma"]
        parole = parole_volute(testo, forma)
        materia = {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]}
        titolo_schema = self.riga["schema_titolo"] or self.riga["argomento"]
        sezioni = sezioni_usate(contenuto)
        messaggi = prompt_studio.messaggi_riassunto(materia, titolo_schema, testo, forma, parole, sezioni)
        modello = self.ctx.settings.modello_scrittura
        # Largo: una risposta tagliata a metà non si può usare (la 1.3.0 si fermava qui con gli schemi lunghi), e il
        # modello scrive spesso più parole di quelle chieste. Lo spazio in più non costa: si pagano i token scritti.
        max_token = min(14_000, int(parole * 6) + 2500)
        cosa = f"Riassunto · {NOME_FORMA[forma].lower()}"
        riferimento = f"riassunto:{self.id}"
        dati = {"forma": forma, "sezioni": [{"titolo": s["titolo"], "testo": schema_ripasso.testo_di([s])} for s in contenuto.get("sezioni") or []]}
        grezzo = ai.chiedi_json(cassa=self.ctx.cassa, fornitore=self.ctx.testo, compito="riassunto", messaggi=messaggi, modello=modello, valida=valida,
                                max_token=max_token, cosa=cosa, dati=dati, materia_id=self.riga["materia_id"], prima_di_ogni_chiamata=self.controlla,
                                riferimento=riferimento)
        mancano = parti_che_mancano(grezzo, sezioni)
        if mancano:
            # Una richiesta in più, ma il riassunto che c'è non si butta: se la seconda non è meglio, resta il primo.
            try:
                nuovo = ai.chiedi_json(cassa=self.ctx.cassa, fornitore=self.ctx.testo, compito="riassunto", modello=modello, valida=valida,
                                       messaggi=messaggi + [{"role": "assistant", "content": json.dumps(grezzo, ensure_ascii=False)},
                                                            {"role": "user", "content": prompt_studio.correzione_riassunto(mancano)}],
                                       max_token=max_token, cosa=f"{cosa} (parti mancanti)", dati=dati, materia_id=self.riga["materia_id"],
                                       prima_di_ogni_chiamata=self.controlla, riferimento=riferimento)
            except ai.RispostaNonUsabile:
                nuovo = None
            if nuovo and len(nuovo["parti"]) > len(grezzo["parti"]):
                grezzo = nuovo
        riveduto = controllo.rivedi(self.ctx, grezzo, [], compito="riassunto", messaggi=messaggi, modello=modello, valida=valida, max_token=max_token,
                                    cosa=cosa, altro=testo, materia_id=self.riga["materia_id"], prima=self.controlla, dati=dati, parlato=False,
                                    riferimento=riferimento)
        parti = normalizza(riveduto, forma)
        if not parti:
            raise ErroreLezione("Non sono riuscito a scrivere il riassunto: tocca Riprova.")
        nota = "Riassunto di prova: testo preso pari pari dallo schema. Serve la chiave Mistral per quello vero." if not self.ctx.testo.a_pagamento else None
        titolo = stile.ripulisci(_pulito(riveduto.get("titolo"))[:120]) or f"Riassunto: {titolo_schema}"
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            # schema_del: da quale versione dello schema è scritto (contratto 28.3, "schema_cambiato").
            cur = conn.execute("UPDATE riassunti SET stato = 'pronto', frase = NULL, errore = NULL, nota = ?, titolo = ?, parole = ?, contenuto = ?, pronto_il = ?,"
                               " schema_del = ? WHERE id = ? AND stato = 'in_preparazione'",
                               (nota, titolo, conta_parole(parti), json.dumps({"parti": parti}, ensure_ascii=False), clock.adesso(),
                                self.riga["schema_cambiato_il"], self.id))
            if cur.rowcount == 0:
                raise Annullata()
            db.aggiungi_novita(conn, "riassunto_pronto", f"Il riassunto di {self.riga['materia_nome']} è pronto", materia_id=self.riga["materia_id"])


def in_errore(db_path: str, riassunto_id: int, frase: str) -> None:
    with db.aperta(db_path) as conn, db.transazione(conn):
        r = conn.execute("SELECT r.materia_id, m.nome FROM riassunti r JOIN materie m ON m.id = r.materia_id WHERE r.id = ? AND r.stato = 'in_preparazione'",
                         (riassunto_id,)).fetchone()
        if r is None:
            return
        conn.execute("UPDATE riassunti SET stato = 'errore', errore = ?, frase = NULL WHERE id = ?", (frase, riassunto_id))
        db.aggiungi_novita(conn, "riassunto_errore", f"Il riassunto di {r['nome']} si è fermato: tocca Riprova", materia_id=r["materia_id"])


def per_api(r: sqlite3.Row) -> dict:
    return {
        "id": r["id"], "schema_id": r["schema_id"], "materia_id": r["materia_id"], "forma": r["forma"], "titolo": r["titolo"], "stato": r["stato"],
        "frase": r["frase"] or ("Scrivo il riassunto" if r["stato"] in ("in_coda", "in_preparazione") else None), "errore": r["errore"], "nota": r["nota"],
        "stima_eur": r["stima_eur"], "creato_il": clock.per_utente(r["creato_il"]), "pronto_il": clock.per_utente(r["pronto_il"]) if r["pronto_il"] else None,
        "parole": r["parole"], "contenuto": json.loads(r["contenuto"]) if r["contenuto"] else None,
    }
