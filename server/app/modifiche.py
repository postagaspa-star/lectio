"""Modifiche dopo (contratto 28): una lezione, uno schema, un riassunto o una verifica simulata già fatti si cambiano
senza rifarli da capo.

Come lavora una modifica con l'AI:
1. le parti da cambiare: quelle scelte da Andrea, oppure (con "Tutto") le sceglie il modello di scrittura con una
   richiesta breve (riassunti e verifiche no: lì il modello risponde direttamente solo con le parti che cambia);
2. ogni parte si riscrive con il modello di scrittura partendo dalla STESSA richiesta della prima volta (stessi
   documenti, stesse regole), poi la versione di adesso come risposta del modello, poi la richiesta di Andrea;
3. il solito controllo sui documenti (controllo.rivedi);
4. la versione di adesso si mette da parte (versioni) e si scrive quella nuova, tutto in una transazione.
Ogni parte riscritta si salva subito in /data/modifiche/<id>/: Riprova non la ripaga.

Lezioni: la voce si rifà solo per i capitoli cambiati; quelli uguali si tagliano dall'audio di prima. Le domande uguali
tengono il loro id (e le risposte date); quelle che non ci sono più restano nel database con `tolta = 1`."""

import hashlib
import json
import logging
import os
import shutil
import sqlite3
from pathlib import Path

from . import ai, clock, controllo, db, lampo, memoria, misure, prompt, prompt_studio, riassunti, schema_ripasso, simulata
from .audio import PAUSA_CAPITOLI
from .cartella import Cartella
from .lezione import (CAPITOLO_FINALE, CAPITOLO_FINALE_INTERROGAZIONE, CAPITOLO_FINALE_RIPASSO, Annullata,
                      ErroreLezione, Preparazione, componi, copione)
from .spesa import arrotonda_su

log = logging.getLogger("uvicorn.error")

TIPI = ("lezione", "schema", "riassunto", "simulata")
TABELLE = {"lezione": "lezioni", "schema": "schemi", "riassunto": "riassunti", "simulata": "simulate"}
NOMI = {"lezione": "la lezione", "schema": "lo schema", "riassunto": "il riassunto", "simulata": "la verifica di prova"}
ATTIVE = ("in_coda", "in_preparazione")
RICHIESTA_MASSIMA = 600
VERSIONI_MASSIME = {"lezione": 1, "schema": 3, "riassunto": 3, "simulata": 3}
MODIFICABILI = {"lezione": ("pronta",), "schema": ("pronto",), "riassunto": ("pronto",),
                "simulata": ("pronta", "in_corso", "corretta")}
FINALI = (CAPITOLO_FINALE, CAPITOLO_FINALE_RIPASSO, CAPITOLO_FINALE_INTERROGAZIONE)
FILE_LEZIONE = ("capitoli.json", "copione.json", "lezione.mp3")
FRASE_VECCHIA = "Questa cosa è stata fatta con una versione vecchia di Lectio e non si può modificare: fanne una nuova."
FRASE_CAMBIATA = "Nel frattempo è cambiata: la modifica non si può più mettere. Tocca Lascia stare e riprova."
CARATTERI_SEZIONE = 16_000
MARGINE_TAGLIO = 0.05


def cartella(dati_dir: str, modifica_id: int) -> Path:
    return Path(dati_dir) / "modifiche" / str(modifica_id)


def cartella_lezione(dati_dir: str, lezione_id: int) -> Path:
    return Path(dati_dir) / "lezioni" / str(lezione_id)


def cartella_versione(dati_dir: str, lezione_id: int, versione_id: int) -> Path:
    return cartella_lezione(dati_dir, lezione_id) / "versioni" / str(versione_id)


# --------------------------------------------------------------------------- letture

def trova_oggetto(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> sqlite3.Row | None:
    return conn.execute(f"SELECT * FROM {TABELLE[tipo]} WHERE id = ?", (oggetto_id,)).fetchone()


def titolo_di(tipo: str, r) -> str:
    if tipo == "lezione":
        return r["argomento"]
    return (r["titolo"] or "").strip() or (r["argomento"] if "argomento" in r.keys() else "") or "Riassunto"


def attiva(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM modifiche WHERE tipo = ? AND oggetto_id = ? AND stato IN ('in_coda', 'in_preparazione')"
                        " ORDER BY id DESC LIMIT 1", (tipo, oggetto_id)).fetchone()


def ultima(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM modifiche WHERE tipo = ? AND oggetto_id = ? ORDER BY id DESC LIMIT 1",
                        (tipo, oggetto_id)).fetchone()


def perche_no(conn: sqlite3.Connection, tipo: str, r) -> str | None:
    """Perché adesso non si può modificare (None = si può)."""
    if r["stato"] not in MODIFICABILI[tipo]:
        if tipo == "simulata" and r["stato"] == "in_correzione":
            return "La sto correggendo: aspetta il voto, poi la puoi modificare."
        return f"{NOMI[tipo].capitalize()} non è ancora pronta da modificare." if tipo in ("lezione", "simulata") else \
            f"{NOMI[tipo].capitalize()} non è ancora pronto da modificare."
    if attiva(conn, tipo, r["id"]):
        return "C'è già una modifica in corso: aspetta che finisca."
    return None


def per_api(m) -> dict:
    return {"id": m["id"], "tipo": m["tipo"], "oggetto_id": m["oggetto_id"], "materia_id": m["materia_id"],
            "richiesta": m["richiesta"], "parti": json.loads(m["parti"] or "[]"), "stato": m["stato"], "fase": m["fase"],
            "fatto": m["fatto"], "totale": m["totale"], "frase": m["frase"],
            "errore": m["errore"] if m["stato"] == "errore" else None, "nota": m["nota"],
            "cambiate": json.loads(m["cambiate"] or "[]"), "stima_eur": m["stima_eur"],
            "creata_il": clock.per_utente(m["creata_il"]),
            "pronta_il": clock.per_utente(m["pronta_il"]) if m["pronta_il"] else None}


def _inizio(testo: str, quanto: int = 160) -> str:
    testo = " ".join(str(testo or "").replace("**", "").split())
    return testo if len(testo) <= quanto else testo[:quanto].rsplit(" ", 1)[0] + "…"


def parti_di(tipo: str, r) -> list[dict]:
    """Le parti che si possono scegliere: numero, titolo e come comincia."""
    if tipo == "schema":
        contenuto = json.loads(r["contenuto"] or "{}")
        uscita = []
        for s in contenuto.get("sezioni") or []:
            testo = schema_ripasso.testo_di([s])
            uscita.append({"n": s["n"], "titolo": s["titolo"], "inizio": _inizio(testo[len(s["titolo"]):])})
        return uscita
    if tipo == "riassunto":
        parti = (json.loads(r["contenuto"] or "{}") or {}).get("parti") or []
        return [{"n": i, "titolo": p.get("titolo") or f"Parte {i}",
                 "inizio": _inizio(" ".join([*(p.get("paragrafi") or []), *(p.get("punti") or [])]))}
                for i, p in enumerate(parti, start=1)]
    if tipo == "simulata":
        return [{"n": d["n"], "titolo": _inizio(d["testo"], 80), "inizio": _inizio(d["testo"])}
                for d in json.loads(r["domande"] or "[]")]
    testo = {t["capitolo"]: " ".join(t.get("paragrafi") or []) for t in json.loads(r["testo"] or "[]")}
    return [{"n": c["n"], "titolo": c["titolo"], "inizio": _inizio(testo.get(c["n"], ""))}
            for c in json.loads(r["capitoli"] or "[]")]


def versione_da_parte(conn: sqlite3.Connection, tipo: str, oggetto_id: int) -> dict | None:
    v = conn.execute("SELECT * FROM versioni WHERE tipo = ? AND oggetto_id = ? ORDER BY id DESC LIMIT 1",
                     (tipo, oggetto_id)).fetchone()
    if v is None:
        return None
    return {"ruolo": v["ruolo"], "perche": v["perche"], "richiesta": v["richiesta"], "quando": clock.per_utente(v["quando"])}


def campi_in_piu(conn: sqlite3.Connection, tipo: str, r) -> dict:
    """Contratto 28.3: quello che le app mostrano per Modifica, Torna a prima e Correggi a mano."""
    m = ultima(conn, tipo, r["id"])
    parti = [{"n": p["n"], "titolo": p["titolo"]} for p in parti_di(tipo, r)] if r["stato"] in MODIFICABILI[tipo] else []
    return {"modifica": per_api(m) if m else None, "versione_da_parte": versione_da_parte(conn, tipo, r["id"]),
            "parti_modificabili": parti}


# --------------------------------------------------------------------------- versioni

def _dati_di(conn: sqlite3.Connection, tipo: str, r) -> dict:
    if tipo == "schema":
        carte = [dict(c) for c in conn.execute(
            "SELECT origine, chiave, domanda, risposta, fonti, scatola, prossima, creata_il FROM carte WHERE chiave LIKE ?",
            (f"schema:{r['id']}:%",))]
        return {"titolo": r["titolo"], "contenuto": r["contenuto"], "cambiato_il": r["cambiato_il"], "carte": carte}
    if tipo == "riassunto":
        return {k: r[k] for k in ("titolo", "contenuto", "parole", "schema_del")}
    if tipo == "simulata":
        return {k: r[k] for k in ("titolo", "domande", "risposte", "esito", "stato", "iniziata_il", "consegnata_il", "nota")}
    domande = [dict(d) for d in conn.execute(
        "SELECT id, tipo, capitolo, testo, risposta, inizio_s, fine_domanda_s, inizio_risposta_s, fine_s FROM domande"
        " WHERE lezione_id = ? AND tolta = 0 ORDER BY n", (r["id"],))]
    return {**{k: r[k] for k in ("capitoli", "testo", "durata_s", "audio_byte", "audio_sha256")}, "domande": domande}


def _rimetti(conn: sqlite3.Connection, tipo: str, r, dati: dict) -> None:
    oggetto_id = r["id"]
    if tipo == "schema":
        conn.execute("DELETE FROM carte WHERE chiave LIKE ?", (f"schema:{oggetto_id}:%",))
        for c in dati["carte"]:
            conn.execute("INSERT OR IGNORE INTO carte (materia_id, origine, chiave, domanda, risposta, fonti, scatola, prossima,"
                         " creata_il) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                         (r["materia_id"], c["origine"], c["chiave"], c["domanda"], c["risposta"], c["fonti"], c["scatola"],
                          c["prossima"], c["creata_il"]))
        # cambiato_il torna quello della versione: un riassunto scritto da quella non risulta più vecchio.
        conn.execute("UPDATE schemi SET titolo = ?, contenuto = ?, carte = ?, cambiato_il = ? WHERE id = ?",
                     (dati["titolo"], dati["contenuto"], len(dati["carte"]), dati.get("cambiato_il"), oggetto_id))
    elif tipo == "riassunto":
        conn.execute("UPDATE riassunti SET titolo = ?, contenuto = ?, parole = ?, schema_del = ? WHERE id = ?",
                     (dati["titolo"], dati["contenuto"], dati["parole"], dati["schema_del"], oggetto_id))
    elif tipo == "simulata":
        conn.execute("UPDATE simulate SET titolo = ?, domande = ?, risposte = ?, esito = ?, stato = ?, iniziata_il = ?,"
                     " consegnata_il = ?, nota = ? WHERE id = ?",
                     (dati["titolo"], dati["domande"], dati["risposte"], dati["esito"], dati["stato"], dati["iniziata_il"],
                      dati["consegnata_il"], dati["nota"], oggetto_id))
    else:
        # L'ascolto resta dov'era (dentro la durata della versione rimessa).
        conn.execute("UPDATE lezioni SET capitoli = ?, testo = ?, durata_s = ?, audio_byte = ?, audio_sha256 = ?,"
                     " ascolto_posizione = MIN(ascolto_posizione, ?) WHERE id = ?",
                     (dati["capitoli"], dati["testo"], dati["durata_s"], dati["audio_byte"], dati["audio_sha256"],
                      float(dati["durata_s"] or 0), oggetto_id))
        imposta_domande(conn, oggetto_id, dati["domande"])


def imposta_domande(conn: sqlite3.Connection, lezione_id: int, nuove: list[dict]) -> None:
    """Le domande della lezione diventano `nuove`. Una domanda già vista (stesso id, o stesso testo) tiene la sua riga,
    quindi le risposte date; le altre righe restano con `tolta = 1` (le risposte non si perdono)."""
    tutte = {r["id"]: dict(r) for r in conn.execute("SELECT * FROM domande WHERE lezione_id = ?", (lezione_id,))}
    usati: set[int] = set()
    righe: list[int | None] = []
    for d in nuove:  # prima chi ha già il suo id...
        rid = d.get("id") if d.get("id") in tutte and d.get("id") not in usati else None
        righe.append(rid)
        if rid is not None:
            usati.add(rid)
    for i, d in enumerate(nuove):  # ...poi le altre, con lo stesso testo
        if righe[i] is None:
            uguali = [x for x, r in sorted(tutte.items(), key=lambda kv: (kv[1]["tolta"], kv[0]))
                      if x not in usati and r["testo"] == d["testo"]]
            if uguali:
                righe[i] = uguali[0]
                usati.add(uguali[0])
    di = len(nuove)
    for i, d in enumerate(nuove, start=1):
        rid = righe[i - 1]
        valori = (d["tipo"], d["capitolo"], i, di, d["testo"], d["risposta"], d["inizio_s"], d["fine_domanda_s"],
                  d["inizio_risposta_s"], d["fine_s"])
        if rid is None:
            rid = conn.execute(
                "INSERT INTO domande (tipo, capitolo, n, di, testo, risposta, inizio_s, fine_domanda_s, inizio_risposta_s,"
                " fine_s, lezione_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", (*valori, lezione_id)).lastrowid
        else:
            conn.execute("UPDATE domande SET tipo = ?, capitolo = ?, n = ?, di = ?, testo = ?, risposta = ?, inizio_s = ?,"
                         " fine_domanda_s = ?, inizio_risposta_s = ?, fine_s = ?, tolta = 0 WHERE id = ?", (*valori, rid))
        usati.add(rid)
    segni = ",".join("?" * len(usati)) or "NULL"
    conn.execute(f"UPDATE domande SET tolta = 1 WHERE lezione_id = ? AND id NOT IN ({segni})", (lezione_id, *usati))


def _copia_file_lezione(da: Path, a: Path) -> None:
    a.mkdir(parents=True, exist_ok=True)
    if (da / "scrittura").is_dir():
        shutil.copytree(da / "scrittura", a / "scrittura", dirs_exist_ok=True)
    for nome in FILE_LEZIONE:
        if (da / nome).is_file():
            shutil.copy2(da / nome, a / nome)


def metti_da_parte(conn: sqlite3.Connection, dati_dir: str, tipo: str, r, ruolo: str, perche: str,
                   richiesta: str | None) -> int:
    """La versione di adesso va da parte (per le lezioni anche i file). Si tengono le ultime VERSIONI_MASSIME."""
    vid = conn.execute("INSERT INTO versioni (tipo, oggetto_id, dati, ruolo, perche, richiesta, quando) VALUES (?, ?, ?, ?, ?, ?, ?)",
                       (tipo, r["id"], json.dumps(_dati_di(conn, tipo, r), ensure_ascii=False), ruolo, perche, richiesta,
                        clock.adesso())).lastrowid
    if tipo == "lezione":
        _copia_file_lezione(cartella_lezione(dati_dir, r["id"]), cartella_versione(dati_dir, r["id"], vid))
    vecchie = [v["id"] for v in conn.execute("SELECT id FROM versioni WHERE tipo = ? AND oggetto_id = ? ORDER BY id DESC",
                                             (tipo, r["id"]))][VERSIONI_MASSIME[tipo]:]
    for v in vecchie:
        conn.execute("DELETE FROM versioni WHERE id = ?", (v,))
        if tipo == "lezione":
            shutil.rmtree(cartella_versione(dati_dir, r["id"], v), ignore_errors=True)
    return vid


def scambia(conn: sqlite3.Connection, dati_dir: str, tipo: str, r) -> None:
    """Contratto 28.2: rimette la versione messa da parte e mette da parte quella di adesso. Si chiama dentro una
    transazione; le app controllano prima stato e modifiche in corso."""
    v = conn.execute("SELECT * FROM versioni WHERE tipo = ? AND oggetto_id = ? ORDER BY id DESC LIMIT 1",
                     (tipo, r["id"])).fetchone()
    if v is None:
        raise ErroreLezione("Non c'è una versione messa da parte.")
    lezione_dir = cartella_lezione(dati_dir, r["id"])
    in_attesa = None
    if tipo == "lezione":
        # I file della versione da rimettere vanno al sicuro prima che la potatura (1 versione) li tolga.
        in_attesa = lezione_dir / f"rimetti-{v['id']}"
        shutil.rmtree(in_attesa, ignore_errors=True)
        sorgente = cartella_versione(dati_dir, r["id"], v["id"])
        if not (sorgente / "lezione.mp3").is_file():
            raise ErroreLezione("L'audio della versione di prima non c'è più: non posso tornare indietro.")
        os.replace(sorgente, in_attesa)
    conn.execute("DELETE FROM versioni WHERE id = ?", (v["id"],))
    try:
        metti_da_parte(conn, dati_dir, tipo, r, "dopo" if v["ruolo"] == "prima" else "prima", v["perche"], v["richiesta"])
        _rimetti(conn, tipo, r, json.loads(v["dati"]))
        if in_attesa is not None:
            if (in_attesa / "scrittura").is_dir():
                shutil.rmtree(lezione_dir / "scrittura", ignore_errors=True)
            for nome in ("scrittura", *FILE_LEZIONE):
                if (in_attesa / nome).exists():
                    os.replace(in_attesa / nome, lezione_dir / nome)
            shutil.rmtree(in_attesa, ignore_errors=True)
    except BaseException:
        if in_attesa is not None and in_attesa.is_dir():
            os.replace(in_attesa, cartella_versione(dati_dir, r["id"], v["id"]))
        raise


# --------------------------------------------------------------------------- stima

def stima(s, conn: sqlite3.Connection, tipo: str, r, parti: list[int]) -> dict:
    """Quanto costa, circa. Con "Tutto" è il massimo (tutte le parti)."""
    st = s.settings
    tutte = parti_di(tipo, r)
    scelte = [p for p in tutte if p["n"] in parti] if parti else tutte
    t = misure.CARATTERI_PER_TOKEN
    prezzi = s.cassa.prezzi
    medium, small = st.modello_scrittura, st.modello_piano
    costo = 0.0
    minuti = 1.0
    if tipo in ("schema", "lezione"):
        for p in scelte:
            lunga = 2_500 if tipo == "schema" else 3_500
            costo += prezzi.costo_testo(medium, int((CARATTERI_SEZIONE + 7_000 + lunga) / t), int(lunga * 1.3 / t) + 300)
            costo += prezzi.costo_testo(small, int((CARATTERI_SEZIONE + lunga + 3_000) / t), 400)
            minuti += 0.5
        if not parti and len(tutte) > 1:
            costo += prezzi.costo_testo(medium, 3_000, 200)
        costo *= 1.3  # una riscrittura dopo il controllo, a volte
    elif tipo == "riassunto":
        contenuto = json.loads(r["contenuto"] or "{}")
        tutto = sum(len(x["inizio"]) for x in tutte) or 1
        lunghezza = len(json.dumps(contenuto, ensure_ascii=False))
        quota = lunghezza if not parti else max(800, lunghezza * len(scelte) // max(1, len(tutte)))
        costo += prezzi.costo_testo(medium, int((min(lunghezza, 60_000) + 12_000 + lunghezza) / t), int(quota * 1.2 / t) + 300)
        costo += prezzi.costo_testo(small, int((min(lunghezza, 60_000) + quota) / t), 400)
        costo *= 1.3
        minuti += 1 + tutto // 20_000
    else:
        domande = json.loads(r["domande"] or "[]")
        lunghezza = len(json.dumps(domande, ensure_ascii=False))
        quota = lunghezza if not parti else max(600, lunghezza * len(scelte) // max(1, len(domande)))
        costo += prezzi.costo_testo(medium, int((45_000 + 8_000 + lunghezza) / t), int(quota * 1.2 / t) + 300)
        costo += prezzi.costo_testo(small, int((45_000 + quota) / t), 600)
        costo *= 1.3
        minuti += 1
    if tipo == "lezione":
        voce = s.catalogo.risolvi(r["voce"])
        capitoli = {c["n"]: c for c in json.loads(r["capitoli"] or "[]")}
        secondi = sum(max(0.0, float(capitoli[p["n"]]["fine_s"]) - float(capitoli[p["n"]]["inizio_s"]))
                      for p in scelte if p["n"] in capitoli)
        if voce is not None:
            minuti += secondi / 60 * float(getattr(voce, "minuti_per_minuto", 1.0) or 1.0)
            if voce.fornitore.fornitore == "mistral":
                costo += prezzi.costo_voce(st.modello_voce, int(secondi / 60 * misure.PAROLE_AL_MINUTO * misure.CARATTERI_PER_PAROLA))
    decisione = s.cassa.decisione(conn, 0.0 if st.modalita_prova else arrotonda_su(costo), prova=st.modalita_prova,
                                  modello_ocr=st.modello_ocr, modello_piano=st.modello_piano, quale="Questa modifica")
    decisione["minuti_preparazione"] = max(1, int(round(minuti)))
    decisione["fino_a"] = not parti and len(tutte) > 1
    return decisione


# --------------------------------------------------------------------------- la preparazione

class _LezioneDaCambiare(Preparazione):
    """La preparazione della lezione, usata solo per rifare le richieste di scrittura: si ferma se si ferma la modifica."""

    def __init__(self, ctx, lezione_id: int, mod: "PreparazioneModifica"):
        super().__init__(ctx, lezione_id)
        self._mod = mod

    def controlla(self) -> None:
        self._mod.controlla()


class PreparazioneModifica:
    def __init__(self, ctx, modifica_id: int):
        self.ctx = ctx
        self.id = modifica_id
        with db.aperta(ctx.settings.db_path) as conn:
            m = conn.execute("SELECT mo.*, ma.nome AS materia_nome, ma.prof AS materia_prof FROM modifiche mo"
                             " JOIN materie ma ON ma.id = mo.materia_id WHERE mo.id = ?", (modifica_id,)).fetchone()
        if m is None:
            raise Annullata()
        self.riga = dict(m)
        self.tipo = m["tipo"]
        self.richiesta = m["richiesta"]
        self.parti = json.loads(m["parti"] or "[]")
        self.c = Cartella(cartella(ctx.settings.dati_dir, modifica_id))
        self.riferimento = f"modifica:{modifica_id}"
        self.modello = ctx.settings.modello_scrittura

    # --- piccoli aiuti

    def controlla(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = conn.execute("SELECT stato FROM modifiche WHERE id = ?", (self.id,)).fetchone()
        if r is None or r["stato"] != "in_preparazione":
            raise Annullata()

    def avanza(self, fase: str, fatto: int = 0, totale: int = 0, frase: str | None = None) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            conn.execute("UPDATE modifiche SET fase = ?, fatto = ?, totale = ?, frase = ? WHERE id = ? AND stato = 'in_preparazione'",
                         (fase, fatto, totale, frase, self.id))

    def _chiedi(self, compito: str, messaggi: list[dict], valida, max_token: int, cosa: str, dati: dict,
                lezione_id: int | None = None, modello: str | None = None) -> dict:
        return ai.chiedi_json(cassa=self.ctx.cassa, fornitore=self.ctx.testo, compito=compito, messaggi=messaggi,
                              modello=modello or self.modello, valida=valida, max_token=max_token, cosa=cosa,
                              dati={**dati, "modifica": self.richiesta}, materia_id=self.riga["materia_id"],
                              lezione_id=lezione_id, prima_di_ogni_chiamata=self.controlla, riferimento=self.riferimento)

    def _rivedi(self, grezzo: dict, brani: list[dict], *, compito: str, messaggi: list[dict], valida, max_token: int,
                cosa: str, altro: str = "", dati: dict | None = None, parlato: bool = False, lezione_id: int | None = None) -> dict:
        nota = grezzo.get("nota")
        riveduto = controllo.rivedi(self.ctx, grezzo, brani, compito=compito, messaggi=messaggi, modello=self.modello,
                                    valida=valida, max_token=max_token, cosa=cosa, altro=altro,
                                    materia_id=self.riga["materia_id"], lezione_id=lezione_id, prima=self.controlla,
                                    dati={**(dati or {}), "modifica": self.richiesta}, parlato=parlato,
                                    riferimento=self.riferimento)
        if not riveduto.get("nota") and nota:
            riveduto["nota"] = nota
        return riveduto

    def _segna_nota(self, nota) -> None:
        nota = " ".join(str(nota or "").split())
        if not nota or nota.lower() in ("null", "none", "nessuna"):
            return
        note = self.c.leggi("note.json") or []
        if nota not in note:
            note.append(nota[:300])
            self.c.scrivi("note.json", note)

    def _nota_finale(self) -> str | None:
        note = self.c.leggi("note.json") or []
        return " ".join(note[:3]) or None

    def _scegli(self, disponibili: list[dict], cosa: str) -> list[int]:
        numeri = [p["n"] for p in disponibili]
        if self.parti:
            scelte = [n for n in numeri if n in self.parti]
            if not scelte:
                raise ErroreLezione("Le parti scelte non ci sono più: riapri e scegli di nuovo.")
            return scelte
        if len(numeri) <= 1:
            return numeri
        fatto = self.c.leggi("scelta.json")
        if fatto is None:
            self.avanza("scelta", frase="Scelgo le parti da cambiare")
            elenco = [(p["n"], p["titolo"], p["inizio"]) for p in disponibili]

            def valida(o: dict) -> list[str]:
                v = o.get("parti")
                return [] if isinstance(v, list) else ['serve "parti", la lista dei numeri']

            try:
                o = self._chiedi("modifica_scelta", prompt_studio.messaggi_scelta_parti(cosa, elenco, self.richiesta),
                                 valida, 400, "Modifica · scelta delle parti", {"numeri": numeri})
                scelte = []
                for x in o["parti"]:
                    try:
                        n = int(x)
                    except (TypeError, ValueError):
                        continue
                    if n in numeri and n not in scelte:
                        scelte.append(n)
            except ai.RispostaNonUsabile:
                scelte = []
            fatto = {"parti": sorted(scelte) or numeri}  # se non ha saputo scegliere, tutte
            self.c.scrivi("scelta.json", fatto)
        return fatto["parti"]

    # --- il giro

    def esegui(self) -> None:
        self.controlla()
        getattr(self, f"_{self.tipo}")()

    def _oggetto(self, conn: sqlite3.Connection):
        r = trova_oggetto(conn, self.tipo, self.riga["oggetto_id"])
        if r is None or r["stato"] not in MODIFICABILI[self.tipo]:
            raise ErroreLezione(FRASE_CAMBIATA)
        return r

    def _chiudi(self, conn: sqlite3.Connection, cambiate: list[int], titolo: str) -> None:
        cur = conn.execute("UPDATE modifiche SET stato = 'pronta', fase = NULL, frase = NULL, errore = NULL, nota = ?,"
                           " cambiate = ?, pronta_il = ? WHERE id = ? AND stato = 'in_preparazione'",
                           (self._nota_finale(), json.dumps(sorted(set(cambiate))), clock.adesso(), self.id))
        if cur.rowcount == 0:
            raise Annullata()
        db.aggiungi_novita(conn, "modifica_pronta", f"Ho modificato {NOMI[self.tipo]} «{titolo[:60]}»",
                           lezione_id=self.riga["oggetto_id"] if self.tipo == "lezione" else None,
                           materia_id=self.riga["materia_id"])

    # --- schema

    def _schema(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = dict(self._oggetto(conn))
        contenuto = json.loads(r["contenuto"] or "{}")
        sc = Cartella(schema_ripasso.cartella(self.ctx.settings.dati_dir, r["id"]))
        lettura, piano = sc.leggi("lettura.json"), sc.leggi("piano.json")
        if not lettura:
            raise ErroreLezione(FRASE_VECCHIA)
        sezioni = {s["n"]: s for s in contenuto.get("sezioni") or []}
        scelte = self._scegli(parti_di("schema", r), "uno schema di ripasso")
        nuove = {}
        for j, n in enumerate(scelte):
            self.avanza("scrittura", j, len(scelte), f"Riscrivo la parte {n}")
            fatto = self.c.leggi(f"parti/{n:02d}.json")
            if fatto is None:
                fatto = self._schema_parte(r, lettura, piano, sezioni[n], contenuto)
                self.c.scrivi(f"parti/{n:02d}.json", fatto)
            self._segna_nota(fatto.get("nota"))
            nuove[n] = fatto
        self.avanza("chiusura", len(scelte), len(scelte), "Metto insieme lo schema")
        per_id = {b["id"]: b for b in lettura["brani"]}
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            self.controlla()
            attuale = self._oggetto(conn)
            if attuale["contenuto"] != r["contenuto"]:
                raise ErroreLezione(FRASE_CAMBIATA)
            metti_da_parte(conn, self.ctx.settings.dati_dir, "schema", attuale, "prima", "modifica", self.richiesta)
            fonti = dict(contenuto.get("fonti") or {})
            for s in contenuto["sezioni"]:
                if s["n"] not in nuove:
                    continue
                nuova = nuove[s["n"]]["sezione"]
                s.update(titolo=nuova["titolo"], campi=nuova["campi"], blocchi=nuova["blocchi"])
                for b in nuova["blocchi"]:
                    for i in b.get("fonti") or []:
                        if i in per_id:
                            fonti[i] = memoria.fonte(per_id[i])
                conn.execute("DELETE FROM carte WHERE chiave LIKE ?", (f"schema:{r['id']}:{s['n']}:%",))
                ids = [i for b in nuova["blocchi"] for i in b.get("fonti") or []]
                for i, c in enumerate(nuove[s["n"]]["carte"], start=1):
                    lampo.aggiungi(conn, r["materia_id"], "schema", f"schema:{r['id']}:{s['n']}:{i}", c["domanda"], c["risposta"],
                                   memoria.fonti_di(ids, lettura["brani"]))
            contenuto["fonti"] = fonti
            carte = conn.execute("SELECT COUNT(*) FROM carte WHERE chiave LIKE ?", (f"schema:{r['id']}:%",)).fetchone()[0]
            conn.execute("UPDATE schemi SET contenuto = ?, carte = ?, cambiato_il = ? WHERE id = ?",
                         (json.dumps(contenuto, ensure_ascii=False), carte, clock.adesso(), r["id"]))
            self._chiudi(conn, list(nuove), titolo_di("schema", r))

    def _schema_parte(self, r: dict, lettura: dict, piano: dict | None, s: dict, contenuto: dict) -> dict:
        n = s["n"]
        forma = contenuto.get("forma") or (piano or {}).get("forma") or "capitoli"
        immagini = [(i, b) for i, b in enumerate(s["blocchi"]) if b.get("tipo") == "immagine"]
        blocchi = [b for b in s["blocchi"] if b.get("tipo") != "immagine"]
        with db.aperta(self.ctx.settings.db_path) as conn:
            carte = [{"domanda": c["domanda"], "risposta": c["risposta"]} for c in conn.execute(
                "SELECT domanda, risposta FROM carte WHERE chiave LIKE ? ORDER BY id", (f"schema:{r['id']}:{n}:%",))]
        ultima_n = max(x["n"] for x in contenuto["sezioni"])
        quadro = n == ultima_n and len(blocchi) == 1 and blocchi[0].get("tipo") == "tabella" and not any(
            x["titolo"] == s["titolo"] for x in (piano or {}).get("sezioni") or [])
        cosa = f"Modifica · schema · parte {n}"
        if quadro:
            altre = [x for x in contenuto["sezioni"] if x["n"] != n]
            testo = schema_ripasso.testo_di(altre)[:40_000]
            b = blocchi[0]
            attuale = {"titolo": s["titolo"], "intestazione": b.get("intestazione") or [], "righe": b.get("righe") or []}

            def valida(o: dict) -> list[str]:
                return [] if isinstance(o.get("righe"), list) and o["righe"] else ['servono le "righe"']

            messaggi = prompt_studio.messaggi_schema_tabella(lettura, testo) + [
                {"role": "assistant", "content": json.dumps(attuale, ensure_ascii=False)},
                {"role": "user", "content": prompt_studio.richiesta_modifica(self.richiesta, "il quadro d'insieme")}]
            dati = {"sezioni": [{"titolo": x["titolo"], "primo": next((y.get("testo") for y in x["blocchi"] if y.get("testo")), "")}
                                for x in altre]}
            grezzo = self._chiedi("schema_tabella", messaggi, valida, 3000, cosa, dati)
            riveduto = self._rivedi(grezzo, [], compito="schema_tabella", messaggi=messaggi, valida=valida, max_token=3000,
                                    cosa=cosa, altro=testo, dati=dati)
            righe = [[schema_ripasso._formula(c) for c in x] for x in riveduto.get("righe") or [] if isinstance(x, list)]
            if not righe:
                raise ErroreLezione("Il quadro d'insieme nuovo è venuto vuoto: tocca Riprova o scrivi la richiesta in un altro modo.")
            sezione = {"titolo": schema_ripasso._corto(riveduto.get("titolo")) or s["titolo"], "campi": [],
                       "blocchi": [{"tipo": "tabella", "fonti": [], "titolo": None,
                                    "intestazione": [schema_ripasso._corto(c, 60) for c in schema_ripasso._lista(riveduto.get("intestazione"))],
                                    "righe": righe}]}
            return {"sezione": sezione, "carte": [], "nota": riveduto.get("nota")}
        sp, k = None, n
        for i, x in enumerate((piano or {}).get("sezioni") or [], start=1):
            if x["titolo"] == s["titolo"]:
                sp, k = x, i
                break
        if sp is None:
            sp = {"titolo": s["titolo"], "concetti": [], "fonti": []}
        cerca = " ".join([sp["titolo"], *sp.get("concetti", []), self.richiesta])
        brani = memoria.scegli(self.ctx, cerca, lettura["brani"], CARATTERI_SEZIONE, list(sp.get("fonti") or []),
                               materia_id=r["materia_id"], prima=self.controlla)
        parole = schema_ripasso.parole_sezione(r["livello"], max(1, len(sp.get("concetti") or [])))
        piano_per_prompt = piano or {"titolo": r["titolo"], "forma": forma, "sezioni": [sp], "concetti": [], "immagini": []}
        attuale = {"campi": s.get("campi") or [], "blocchi": blocchi, "carte": carte}
        messaggi = prompt_studio.messaggi_schema_sezione(lettura, piano_per_prompt, k, sp, brani, parole, forma) + [
            {"role": "assistant", "content": json.dumps(attuale, ensure_ascii=False)},
            {"role": "user", "content": prompt_studio.richiesta_modifica(self.richiesta, "questa parte dello schema")}]
        max_token = min(12_000, int(schema_ripasso.token_per(parole) * 1.6))
        dati = {"lettura": lettura, "sezione": sp, "brani": brani, "forma": forma}
        grezzo = self._chiedi("schema_sezione", messaggi, schema_ripasso.valida_sezione, max_token, cosa, dati)
        riveduto = self._rivedi(grezzo, brani, compito="schema_sezione", messaggi=messaggi, valida=schema_ripasso.valida_sezione,
                                max_token=max_token, cosa=cosa, dati=dati)
        norm = schema_ripasso.normalizza_sezione(riveduto, {b["id"] for b in brani}, forma)
        if not norm["blocchi"]:
            raise ErroreLezione("La parte nuova è venuta vuota: tocca Riprova o scrivi la richiesta in un altro modo.")
        nuovi = list(norm["blocchi"])
        for posto, foto in immagini:  # le foto restano dov'erano (o in fondo, se la parte è diventata più corta)
            nuovi.insert(min(posto, len(nuovi)), foto)
        return {"sezione": {"titolo": s["titolo"], "campi": norm["campi"], "blocchi": nuovi}, "carte": norm["carte"],
                "nota": riveduto.get("nota")}

    # --- riassunto

    def _riassunto(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = dict(self._oggetto(conn))
            schema = conn.execute("SELECT * FROM schemi WHERE id = ?", (r["schema_id"],)).fetchone()
        if schema is None or not schema["contenuto"]:
            raise ErroreLezione("Lo schema di questo riassunto non c'è più: non posso modificarlo.")
        contenuto_schema = json.loads(schema["contenuto"])
        testo = riassunti.testo_schema(contenuto_schema)
        parti = (json.loads(r["contenuto"] or "{}") or {}).get("parti") or []
        if not parti:
            raise ErroreLezione(FRASE_VECCHIA)
        numeri = list(range(1, len(parti) + 1))
        scelte = [n for n in numeri if n in self.parti] if self.parti else []
        if self.parti and not scelte:
            raise ErroreLezione("Le parti scelte non ci sono più: riapri e scegli di nuovo.")
        forma = r["forma"]
        fatto = self.c.leggi("riassunto.json")
        if fatto is None:
            self.avanza("scrittura", 0, 1, "Riscrivo il riassunto")
            materia = {"nome": self.riga["materia_nome"], "prof": self.riga["materia_prof"]}
            titolo_schema = schema["titolo"] or schema["argomento"]
            base = prompt_studio.messaggi_riassunto(materia, titolo_schema, testo, forma,
                                                    riassunti.parole_volute(testo, forma), riassunti.sezioni_usate(contenuto_schema))
            numerate = [{"n": i, **p} for i, p in enumerate(parti, start=1)]
            attuale = {"titolo": r["titolo"], "parti": numerate}
            messaggi = base + [{"role": "assistant", "content": json.dumps(attuale, ensure_ascii=False)},
                               {"role": "user", "content": prompt_studio.richiesta_modifica(
                                   self.richiesta, "il riassunto", prompt_studio.risposta_parti_riassunto(scelte))}]
            ammesse = set(scelte or numeri)

            def valida(o: dict) -> list[str]:
                v = o.get("parti")
                if not isinstance(v, list) or not v:
                    return ['servono le "parti" cambiate, ognuna con il suo "n"']
                errori = []
                for p in v:
                    try:
                        n = int(p.get("n")) if isinstance(p, dict) else None
                    except (TypeError, ValueError):
                        n = None
                    if n not in ammesse:
                        errori.append(f'la parte con "n" {p.get("n") if isinstance(p, dict) else p} non si può cambiare')
                    elif not (riassunti._lista(p.get("paragrafi")) or riassunti._lista(p.get("punti"))):
                        errori.append(f"la parte {n} è vuota")
                return errori[:5]

            lunghezza = len(json.dumps([numerate[n - 1] for n in sorted(ammesse)], ensure_ascii=False))
            max_token = min(14_000, int(lunghezza / misure.CARATTERI_PER_TOKEN * 1.8) + 1500)
            cosa = "Modifica · riassunto"
            dati = {"forma": forma, "parti": numerate, "scelte": scelte}
            grezzo = self._chiedi("riassunto_modifica", messaggi, valida, max_token, cosa, dati)
            riveduto = self._rivedi(grezzo, [], compito="riassunto_modifica", messaggi=messaggi, valida=valida,
                                    max_token=max_token, cosa=cosa, altro=testo, dati=dati)
            nuove = {}
            for p in riveduto.get("parti") or []:
                try:
                    n = int(p.get("n"))
                except (TypeError, ValueError, AttributeError):
                    continue
                norm = riassunti.normalizza({"parti": [p]}, forma)
                if n in ammesse and norm:
                    nuove[str(n)] = norm[0]
            fatto = {"parti": nuove, "nota": riveduto.get("nota")}
            self.c.scrivi("riassunto.json", fatto)
        self._segna_nota(fatto.get("nota"))
        if not fatto["parti"]:
            raise ErroreLezione("Il riassunto nuovo è venuto vuoto: tocca Riprova o scrivi la richiesta in un altro modo.")
        self.avanza("chiusura", 1, 1, "Metto insieme il riassunto")
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            self.controlla()
            attuale = self._oggetto(conn)
            if attuale["contenuto"] != r["contenuto"]:
                raise ErroreLezione(FRASE_CAMBIATA)
            metti_da_parte(conn, self.ctx.settings.dati_dir, "riassunto", attuale, "prima", "modifica", self.richiesta)
            nuove_parti = [fatto["parti"].get(str(i), p) for i, p in enumerate(parti, start=1)]
            conn.execute("UPDATE riassunti SET contenuto = ?, parole = ? WHERE id = ?",
                         (json.dumps({"parti": nuove_parti}, ensure_ascii=False), riassunti.conta_parole(nuove_parti), r["id"]))
            self._chiudi(conn, [int(n) for n in fatto["parti"]], titolo_di("riassunto", r))

    # --- verifica simulata

    def _simulata(self) -> None:
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = dict(self._oggetto(conn))
        lettura = Cartella(simulata.cartella(self.ctx.settings.dati_dir, r["id"])).leggi("lettura.json")
        if not lettura:
            raise ErroreLezione(FRASE_VECCHIA)
        domande = json.loads(r["domande"] or "[]")
        numeri = [d["n"] for d in domande]
        scelte = [n for n in numeri if n in self.parti] if self.parti else []
        if self.parti and not scelte:
            raise ErroreLezione("Le domande scelte non ci sono più: riapri e scegli di nuovo.")
        fatto = self.c.leggi("domande.json")
        if fatto is None:
            self.avanza("scrittura", 0, 1, "Riscrivo le domande")
            minuti = r["minuti"]
            base = prompt_studio.messaggi_simulata(lettura, minuti, simulata.domande_circa(minuti))
            attuale = {"titolo": r["titolo"], "domande": [{k: d.get(k) for k in ("n", "tipo", "testo", "punti", "sezione", "risposta_attesa")}
                                                         for d in domande]}
            ultima_n = max(numeri or [0])
            messaggi = base + [{"role": "assistant", "content": json.dumps(attuale, ensure_ascii=False)},
                               {"role": "user", "content": prompt_studio.richiesta_modifica(
                                   self.richiesta, "la verifica", prompt_studio.risposta_domande_simulata(scelte, ultima_n))}]

            def valida(o: dict) -> list[str]:
                v, togli = o.get("domande"), o.get("togli", [])
                if not isinstance(v, list) or not isinstance(togli or [], list):
                    return ['servono "domande" (quelle che cambi o aggiungi) e "togli" (una lista, anche vuota)']
                if not v and not togli:
                    return ["non hai cambiato niente: scrivi in \"domande\" quelle che cambi"]
                errori = []
                for d in v:
                    if not isinstance(d, dict) or not str(d.get("testo") or "").strip():
                        errori.append('ogni domanda deve avere il suo "testo"')
                        continue
                    try:
                        n = int(d.get("n"))
                    except (TypeError, ValueError):
                        errori.append('ogni domanda deve avere il suo numero "n"')
                        continue
                    if scelte and n <= ultima_n and n not in scelte:
                        errori.append(f"la domanda {n} non era tra quelle da cambiare")
                return errori[:5]

            lunghezza = len(json.dumps(attuale, ensure_ascii=False))
            max_token = min(8000, int(lunghezza / misure.CARATTERI_PER_TOKEN * 1.6) + 1200)
            cosa = "Modifica · verifica simulata"
            dati = {"domande": attuale["domande"], "scelte": scelte, "fonti": [b["id"] for b in lettura["brani"]][:3]}
            grezzo = self._chiedi("simulata_modifica", messaggi, valida, max_token, cosa, dati)
            riveduto = self._rivedi(grezzo, lettura["brani"], compito="simulata_modifica", messaggi=messaggi, valida=valida,
                                    max_token=max_token, cosa=cosa, altro=prompt.scheda_in_parole(lettura.get("scheda")), dati=dati)
            nuove = {}
            for d in riveduto.get("domande") or []:
                try:
                    n = int(d.get("n"))
                except (TypeError, ValueError, AttributeError):
                    continue
                norm = simulata.normalizza({"domande": [d]}, lettura["brani"])
                if norm and (not scelte or n in scelte or n > ultima_n):
                    nuove[str(n)] = norm[0]
            togli = []
            for x in riveduto.get("togli") or []:
                try:
                    togli.append(int(x))
                except (TypeError, ValueError):
                    continue
            fatto = {"domande": nuove, "togli": [n for n in togli if n in numeri and (not scelte or n in scelte)],
                     "nota": riveduto.get("nota")}
            self.c.scrivi("domande.json", fatto)
        self._segna_nota(fatto.get("nota"))
        if not fatto["domande"] and not fatto["togli"]:
            raise ErroreLezione("Non sono riuscito a cambiare le domande: tocca Riprova o scrivi la richiesta in un altro modo.")
        self.avanza("chiusura", 1, 1, "Metto insieme la verifica")
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            self.controlla()
            attuale = self._oggetto(conn)
            if attuale["domande"] != r["domande"]:
                raise ErroreLezione(FRASE_CAMBIATA)
            metti_da_parte(conn, self.ctx.settings.dati_dir, "simulata", attuale, "prima", "modifica", self.richiesta)
            nuove_domande, cambiate, da_vecchio = [], [], {}
            for d in domande:
                if d["n"] in fatto["togli"]:
                    continue
                nuova = fatto["domande"].get(str(d["n"]))
                numero = len(nuove_domande) + 1
                if nuova:
                    cambiate.append(numero)
                    nuove_domande.append({**nuova, "n": numero})
                else:
                    da_vecchio[str(d["n"])] = str(numero)
                    nuove_domande.append({**d, "n": numero})
            for n in sorted(int(x) for x in fatto["domande"] if int(x) > max(numeri or [0])):
                numero = len(nuove_domande) + 1
                cambiate.append(numero)
                nuove_domande.append({**fatto["domande"][str(n)], "n": numero})
            if not nuove_domande:
                raise ErroreLezione("Con questa modifica la verifica resterebbe senza domande: scrivi la richiesta in un altro modo.")
            stato = attuale["stato"]
            if stato == "in_corso":
                vecchie = json.loads(attuale["risposte"] or "{}")
                risposte = {da_vecchio[k]: v for k, v in vecchie.items() if k in da_vecchio}
                conn.execute("UPDATE simulate SET domande = ?, risposte = ? WHERE id = ?",
                             (json.dumps(nuove_domande, ensure_ascii=False), json.dumps(risposte, ensure_ascii=False), r["id"]))
            else:  # pronta, oppure corretta: diventa una prova nuova (quella di prima resta da parte con il suo voto)
                conn.execute("UPDATE simulate SET domande = ?, risposte = '{}', esito = NULL, stato = 'pronta', iniziata_il = NULL,"
                             " consegnata_il = NULL WHERE id = ?", (json.dumps(nuove_domande, ensure_ascii=False), r["id"]))
            self._chiudi(conn, cambiate, titolo_di("simulata", r))

    # --- lezione

    def _lezione(self) -> None:
        dati_dir = self.ctx.settings.dati_dir
        with db.aperta(self.ctx.settings.db_path) as conn:
            r = dict(self._oggetto(conn))
            vecchie_domande = [dict(d) for d in conn.execute(
                "SELECT * FROM domande WHERE lezione_id = ? AND tolta = 0 ORDER BY n", (r["id"],))]
        L = cartella_lezione(dati_dir, r["id"])
        lettura, piano = _leggi(L / "lettura.json"), _leggi(L / "piano.json")
        capitoli_vecchi = _leggi(L / "capitoli.json")
        if not lettura or not piano or not capitoli_vecchi or not (L / "lezione.mp3").is_file():
            raise ErroreLezione(FRASE_VECCHIA)
        n_blocchi = len(piano["blocchi"])
        nomi_tutti = ["apertura"] + [f"blocco_{k:02d}" for k in range(1, n_blocchi + 1)] + ["finale"]
        scritti_vecchi = {nome: _leggi(L / "scrittura" / f"{nome}.json") for nome in nomi_tutti}
        if any(v is None for v in scritti_vecchi.values()):
            raise ErroreLezione(FRASE_VECCHIA)
        tempi = {c["n"]: c for c in json.loads(r["capitoli"] or "[]")}
        scelte = self._scegli(parti_di("lezione", r), "una lezione da ascoltare")
        mappa = _capitoli_in_parti(capitoli_vecchi, piano)
        nomi = []
        for c in capitoli_vecchi:
            if c["n"] in scelte:
                nomi += [x for x in mappa[c["n"]] if x not in nomi]
        prep = _LezioneDaCambiare(self.ctx, r["id"], self)
        for j, nome in enumerate(nomi):
            self.avanza("scrittura", j, len(nomi), f"Riscrivo la parte {j + 1} di {len(nomi)}")
            if self.c.leggi(f"scrittura/{nome}.json") is not None:
                continue
            s = prep._richiesta(nome, lettura, piano)
            messaggi = s["messaggi"] + [
                {"role": "assistant", "content": json.dumps(scritti_vecchi[nome], ensure_ascii=False)},
                {"role": "user", "content": prompt_studio.richiesta_modifica(self.richiesta, "questa parte della lezione")}]
            cosa = f"Modifica · {s['cosa']}"
            grezzo = self._chiedi(s["compito"], messaggi, s["struttura"], s["max_token"], cosa, s["dati"], lezione_id=r["id"])
            riveduto = self._rivedi(grezzo, s["brani"], compito=s["compito"], messaggi=messaggi, valida=s["struttura"],
                                    max_token=s["max_token"], cosa=cosa, altro=s["altro"], dati=s["dati"], parlato=True,
                                    lezione_id=r["id"])
            self._segna_nota(riveduto.pop("nota", None))
            self.c.scrivi(f"scrittura/{nome}.json", riveduto)
        scritti = {nome: (self.c.leggi(f"scrittura/{nome}.json") if nome in nomi else scritti_vecchi[nome]) for nome in nomi_tutti}
        capitoli = componi(lettura, piano, scritti)
        self._voce_e_montaggio(r, L, capitoli, capitoli_vecchi, tempi, vecchie_domande, scritti, nomi)

    def _voce_e_montaggio(self, r: dict, L: Path, capitoli: list[dict], capitoli_vecchi: list[dict], tempi: dict,
                          vecchie_domande: list[dict], scritti: dict, nomi: list[str]) -> None:
        def firma(c: dict) -> tuple:
            return tuple((s["voce"], s["emozione"], bool(s["lento"])) for s in c["segmenti"])

        liberi = {}
        for c in capitoli_vecchi:
            if c["n"] in tempi:
                liberi.setdefault(firma(c), []).append(c)
        riuso = {}
        for c in capitoli:
            uguali = liberi.get(firma(c))
            if uguali:  # a parità di contenuto, quello con lo stesso numero (l'ascolto resta al suo posto)
                stesso = next((x for x in uguali if x["n"] == c["n"]), uguali[0])
                uguali.remove(stesso)
                riuso[c["n"]] = stesso
        pezzi = copione(capitoli)
        da_registrare = [p for p in pezzi if p["capitolo"] not in riuso]
        lavoro = self.c.file("voce")
        lavoro.mkdir(parents=True, exist_ok=True)
        scelta = self.ctx.catalogo.risolvi(r["voce"])
        if scelta is None and da_registrare:
            raise ErroreLezione("La voce di questa lezione adesso non si può usare: riprova tra poco.")
        fatti = {int(f.stem.split("-")[1]): f for f in lavoro.glob("pezzo-*.*") if f.stat().st_size > 0 and f.suffix in (".mp3", ".wav")}
        prezzi, modello_voce = self.ctx.cassa.prezzi, self.ctx.settings.modello_voce
        for j, p in enumerate(da_registrare):
            self.avanza("voce", j, len(da_registrare), f"Registro la voce, {j + 1} di {len(da_registrare)}")
            if p["n"] in fatti:
                continue
            voce = scelta.fornitore
            caratteri = voce.caratteri(p["testo"], p["emozione"], p["lento"])
            stima, gratis = (prezzi.costo_voce(modello_voce, caratteri), 0) if voce.fornitore == "mistral" else \
                ((prezzi.costo_azure(caratteri), caratteri) if voce.fornitore == "azure" else (0.0, 0))

            def registra(ricevuta, audio, voce=voce):
                if voce.fornitore == "mistral":
                    ricevuta.registra(audio.caratteri, "caratteri", prezzi.costo_voce(modello_voce, audio.caratteri))
                elif voce.fornitore == "azure":
                    ricevuta.registra(audio.caratteri, "caratteri", prezzi.costo_azure(audio.caratteri))

            audio = self.ctx.cassa.chiama(
                cosa=f"Modifica · voce · pezzo {j + 1} di {len(da_registrare)}", fornitore=voce.fornitore, stima_eur=stima,
                caratteri_gratis=gratis, quantita_stimata=caratteri, unita="caratteri", lezione_id=r["id"],
                materia_id=r["materia_id"], prima=self.controlla, registra=registra, riferimento=self.riferimento,
                fare=lambda p=p, voce=voce: voce.sintetizza(p["testo"], emozione=p["emozione"], lento=p["lento"], voce=scelta.nome_voce))
            percorso = lavoro / f"pezzo-{p['n']:04d}.{audio.estensione}"
            provvisorio = percorso.with_suffix(".tmp")
            provvisorio.write_bytes(audio.contenuto)
            os.replace(provvisorio, percorso)
            fatti[p["n"]] = percorso
        self.controlla()
        self.avanza("montaggio", 0, 1, "Monto l'audio")
        montatore = self.ctx.montatore
        elenco, voci = [], []  # voci: (capitolo nuovo, None) per un capitolo riusato, oppure il pezzo
        for c in capitoli:
            if c["n"] in riuso:
                vecchio = riuso[c["n"]]
                t = tempi[vecchio["n"]]
                wav = lavoro / f"capitolo-{c['n']:02d}.wav"
                montatore.taglia(L / "lezione.mp3", _da(t), float(t["fine_s"]) + MARGINE_TAGLIO, wav)
                elenco.append((wav, 0.0 if not elenco else PAUSA_CAPITOLI))
                voci.append(("capitolo", c, vecchio))
            else:
                for p in pezzi:
                    if p["capitolo"] == c["n"]:
                        elenco.append((fatti[p["n"]], 0.0 if not elenco else p["pausa_prima"]))
                        voci.append(("pezzo", c, p))
        mp3 = lavoro / "lezione.mp3"
        risultato = montatore.monta(elenco, mp3, lavoro / "montaggio")
        cap_tempi: dict[int, list[float]] = {}
        seg_tempi: dict[tuple[int, int], list[float]] = {}
        domande = []
        per_capitolo_vecchio: dict[int, list[dict]] = {}
        for d in vecchie_domande:
            per_capitolo_vecchio.setdefault(d["capitolo"], []).append(d)
        for (genere, c, x), (inizio, fine) in zip(voci, risultato.tempi):
            ct = cap_tempi.setdefault(c["n"], [inizio, fine])
            ct[1] = fine
            if genere == "pezzo":
                st = seg_tempi.setdefault((c["n"], x["segmento"]), [inizio, fine])
                st[1] = fine
            else:
                spostamento = inizio - _da(tempi[x["n"]])
                for d in per_capitolo_vecchio.get(x["n"], []):
                    domande.append({"id": d["id"], "tipo": d["tipo"], "capitolo": c["n"], "testo": d["testo"], "risposta": d["risposta"],
                                    "inizio_s": round(d["inizio_s"] + spostamento, 1), "fine_domanda_s": round(d["fine_domanda_s"] + spostamento, 1),
                                    "inizio_risposta_s": round(d["inizio_risposta_s"] + spostamento, 1),
                                    "fine_s": round(d["fine_s"] + spostamento, 1), "_ordine": (c["n"], 0, d["n"])})
        for c in capitoli:
            if c["n"] in riuso:
                continue
            for s_i, s in enumerate(c["segmenti"]):
                if s["ruolo"] != "domanda":
                    continue
                q, a = seg_tempi[(c["n"], s_i)], seg_tempi[(c["n"], s_i + 1)]
                domande.append({"tipo": s["domanda"], "capitolo": c["n"], "testo": s["testo"].removeprefix("Domanda: "),
                                "risposta": c["segmenti"][s_i + 1]["testo"].removeprefix("Risposta: "),
                                "inizio_s": round(q[0], 1), "fine_domanda_s": round(q[1], 1),
                                "inizio_risposta_s": round(a[0], 1), "fine_s": round(a[1], 1), "_ordine": (c["n"], 1, s_i)})
        domande.sort(key=lambda d: d.pop("_ordine"))
        uscita_capitoli = [{"n": c["n"], "titolo": c["titolo"], "inizio_s": round(cap_tempi[c["n"]][0], 1),
                            "fine_s": round(cap_tempi[c["n"]][1], 1)} for c in capitoli]
        testo = [{"capitolo": c["n"], "paragrafi": [s["testo"] for s in c["segmenti"]]} for c in capitoli]
        cambiate = [c["n"] for c in capitoli if c["n"] not in riuso]
        h = hashlib.sha256()
        with open(mp3, "rb") as f:
            for pezzo in iter(lambda: f.read(1024 * 1024), b""):
                h.update(pezzo)
        self.avanza("chiusura", 1, 1, "Metto insieme la lezione")
        dati_dir = self.ctx.settings.dati_dir
        with db.aperta(self.ctx.settings.db_path) as conn, db.transazione(conn):
            self.controlla()
            attuale = self._oggetto(conn)
            if attuale["audio_sha256"] != r["audio_sha256"]:
                raise ErroreLezione(FRASE_CAMBIATA)
            metti_da_parte(conn, dati_dir, "lezione", attuale, "prima", "modifica", self.richiesta)
            posizione = _nuova_posizione(float(attuale["ascolto_posizione"] or 0), tempi, riuso, cap_tempi, capitoli)
            conn.execute("UPDATE lezioni SET capitoli = ?, testo = ?, durata_s = ?, audio_byte = ?, audio_sha256 = ?,"
                         " ascolto_posizione = ?, ascolto_finita = 0 WHERE id = ?",
                         (json.dumps(uscita_capitoli, ensure_ascii=False), json.dumps(testo, ensure_ascii=False),
                          int(round(risultato.durata)), mp3.stat().st_size, h.hexdigest(), posizione, r["id"]))
            imposta_domande(conn, r["id"], domande)
            self._chiudi(conn, cambiate, titolo_di("lezione", r))
            # I file nuovi al loro posto (dentro la transazione: se non si spostano, il database non cambia).
            for nome in nomi:
                Cartella(L).scrivi(f"scrittura/{nome}.json", scritti[nome])
            Cartella(L).scrivi("capitoli.json", capitoli)
            Cartella(L).scrivi("copione.json", copione(capitoli))
            os.replace(mp3, L / "lezione.mp3")
        shutil.rmtree(lavoro, ignore_errors=True)


def _capitoli_in_parti(capitoli: list[dict], piano: dict) -> dict[int, list[str]]:
    """Da quale parte scritta viene ogni capitolo. L'ultimo capitolo è sempre la parte finale; gli altri seguono i
    blocchi del piano in ordine (un blocco rimasto vuoto non fa capitolo, e due blocchi possono avere lo stesso
    titolo). Il primo capitolo contiene anche l'apertura."""
    blocchi = piano["blocchi"]
    mappa: dict[int, list[str]] = {}
    k = 0
    for i, c in enumerate(capitoli):
        if i == len(capitoli) - 1:
            mappa[c["n"]] = ["finale"]
            continue
        j = next((x for x in range(k + 1, len(blocchi) + 1) if blocchi[x - 1]["titolo"] == c["titolo"]), None)
        k = j if j is not None else min(k + 1, len(blocchi))
        mappa[c["n"]] = (["apertura"] if i == 0 else []) + [f"blocco_{k:02d}"]
    return mappa


def _da(t: dict) -> float:
    """Da dove si taglia un capitolo riusato: un filo prima (i tempi sono arrotondati al decimo, prima c'è silenzio)."""
    return max(0.0, float(t["inizio_s"]) - MARGINE_TAGLIO)


def _leggi(percorso: Path):
    try:
        with open(percorso, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _nuova_posizione(posizione: float, tempi: dict, riuso: dict, cap_tempi: dict, capitoli: list[dict]) -> float:
    """Contratto 28.1: allo stesso punto se quel capitolo non è cambiato, altrimenti all'inizio del capitolo cambiato."""
    if posizione <= 0:
        return 0.0
    for nuovo_n, vecchio in riuso.items():
        t = tempi.get(vecchio["n"])
        if t and float(t["inizio_s"]) <= posizione <= float(t["fine_s"]):
            return round(cap_tempi[nuovo_n][0] + posizione - float(t["inizio_s"]), 1)
    for n, t in sorted(tempi.items()):
        if float(t["inizio_s"]) <= posizione <= float(t["fine_s"]) + 1.5:
            stesso = next((c for c in capitoli if c["n"] == n), None)
            if stesso is not None and n in cap_tempi:
                return round(cap_tempi[n][0], 1)
    return 0.0


# --------------------------------------------------------------------------- errori

def in_errore(db_path: str, modifica_id: int, frase: str) -> None:
    with db.aperta(db_path) as conn, db.transazione(conn):
        m = conn.execute("SELECT * FROM modifiche WHERE id = ? AND stato = 'in_preparazione'", (modifica_id,)).fetchone()
        if m is None:
            return
        conn.execute("UPDATE modifiche SET stato = 'errore', errore = ? WHERE id = ?", (frase, modifica_id))
        r = trova_oggetto(conn, m["tipo"], m["oggetto_id"])
        titolo = titolo_di(m["tipo"], r) if r is not None else ""
        db.aggiungi_novita(conn, "modifica_errore", f"La modifica del{_di(m['tipo'])} «{titolo[:60]}» si è fermata: tocca Riprova",
                           lezione_id=m["oggetto_id"] if m["tipo"] == "lezione" else None, materia_id=m["materia_id"])


def _di(tipo: str) -> str:
    return {"lezione": "la lezione", "schema": "lo schema", "riassunto": " riassunto", "simulata": "la verifica di prova"}[tipo]


def cancella_file(dati_dir: str, modifica_id: int) -> None:
    shutil.rmtree(cartella(dati_dir, modifica_id), ignore_errors=True)


def togli_di(conn: sqlite3.Connection, dati_dir: str, tipo: str, oggetto_id: int) -> None:
    """Quando si cancella l'oggetto: via le sue modifiche, le versioni e i loro file."""
    for m in conn.execute("SELECT id FROM modifiche WHERE tipo = ? AND oggetto_id = ?", (tipo, oggetto_id)).fetchall():
        cancella_file(dati_dir, m["id"])
    conn.execute("DELETE FROM modifiche WHERE tipo = ? AND oggetto_id = ?", (tipo, oggetto_id))
    conn.execute("DELETE FROM versioni WHERE tipo = ? AND oggetto_id = ?", (tipo, oggetto_id))


# --------------------------------------------------------------------------- correzione a mano (contratto 28.4)

TESTO_MASSIMO = 20_000
TITOLO_MASSIMO = 120


class FormaSbagliata(ValueError):
    pass


def _testo(v, massimo: int = TESTO_MASSIMO) -> str:
    if v is None:
        return ""
    if not isinstance(v, str):
        raise FormaSbagliata("un testo non è un testo")
    testo = " ".join(v.split())
    if len(testo) > massimo:
        raise FormaSbagliata("un testo è troppo lungo")
    return testo


def _testi(v) -> list[str]:
    if v is None:
        return []
    if not isinstance(v, list):
        raise FormaSbagliata("una lista non è una lista")
    return [t for t in (_testo(x) for x in v) if t]


def pulisci_schema(attuale: dict, nuovo: dict) -> dict:
    """Il contenuto corretto a mano: dal nuovo si prendono solo i testi, il resto (tipi, ordine, foto, fonti) resta
    quello di adesso. Le voci vuote si tolgono; un blocco rimasto senza testo sparisce."""
    sezioni_nuove = nuovo.get("sezioni") if isinstance(nuovo, dict) else None
    sezioni = attuale.get("sezioni") or []
    if not isinstance(sezioni_nuove, list) or len(sezioni_nuove) != len(sezioni):
        raise FormaSbagliata("le parti non sono le stesse")
    uscita = json.loads(json.dumps(attuale))
    for s, n in zip(uscita["sezioni"], sezioni_nuove):
        if not isinstance(n, dict):
            raise FormaSbagliata("una parte non va bene")
        s["titolo"] = _testo(n.get("titolo"), TITOLO_MASSIMO) or s["titolo"]
        campi_nuovi = n.get("campi") or []
        if not isinstance(campi_nuovi, list) or len(campi_nuovi) != len(s.get("campi") or []):
            raise FormaSbagliata("i campi non sono gli stessi")
        campi = []
        for c, cn in zip(s.get("campi") or [], campi_nuovi):
            valore = _testo(cn.get("valore") if isinstance(cn, dict) else None)
            if valore:
                campi.append({**c, "valore": valore})
        s["campi"] = campi
        blocchi_nuovi = n.get("blocchi")
        if not isinstance(blocchi_nuovi, list) or len(blocchi_nuovi) != len(s.get("blocchi") or []):
            raise FormaSbagliata("i blocchi non sono gli stessi")
        blocchi = []
        for b, bn in zip(s["blocchi"], blocchi_nuovi):
            if not isinstance(bn, dict) or bn.get("tipo") != b.get("tipo"):
                raise FormaSbagliata("un blocco ha cambiato tipo")
            tipo = b["tipo"]
            b = dict(b)
            if tipo == "immagine":
                b["didascalia"] = _testo(bn.get("didascalia"), 300)
                blocchi.append(b)
                continue
            if "titolo" in b:
                b["titolo"] = _testo(bn.get("titolo"), TITOLO_MASSIMO) or None
            if tipo in ("paragrafo", "prof", "definizione"):
                b["testo"] = _testo(bn.get("testo"))
                if tipo == "definizione":
                    b["termine"] = _testo(bn.get("termine"), 100) or b.get("termine")
                if not b["testo"]:
                    continue
            elif tipo in ("punti", "passaggi"):
                b["voci"] = _testi(bn.get("voci"))
                if not b["voci"]:
                    continue
            elif tipo == "tabella":
                b["intestazione"] = [_testo(c, 200) for c in (bn.get("intestazione") or [])] if isinstance(
                    bn.get("intestazione") or [], list) else []
                righe_nuove = bn.get("righe") or []
                if not isinstance(righe_nuove, list) or any(not isinstance(r, list) for r in righe_nuove):
                    raise FormaSbagliata("le righe della tabella non vanno bene")
                b["righe"] = [riga for riga in ([_testo(c) for c in r] for r in righe_nuove) if any(riga)]
                if not b["righe"]:
                    continue
            blocchi.append(b)
        s["blocchi"] = blocchi
    return uscita


def pulisci_riassunto(attuale: dict, nuovo: dict) -> list[dict]:
    parti_nuove = nuovo.get("parti") if isinstance(nuovo, dict) else None
    parti = (attuale or {}).get("parti") or []
    if not isinstance(parti_nuove, list) or len(parti_nuove) != len(parti):
        raise FormaSbagliata("le parti non sono le stesse")
    uscita = []
    for p, n in zip(parti, parti_nuove):
        if not isinstance(n, dict):
            raise FormaSbagliata("una parte non va bene")
        uscita.append({**p, "titolo": _testo(n.get("titolo"), TITOLO_MASSIMO) or None,
                       "paragrafi": _testi(n.get("paragrafi")), "punti": _testi(n.get("punti"))})
    return uscita


def chiudi_ferme(conn: sqlite3.Connection, dati_dir: str, tipo: str, oggetto_id: int) -> None:
    """Le modifiche ferme per un errore non si possono più riprovare quando l'oggetto cambia per altre vie (le parti già
    riscritte erano fatte sulla versione di prima)."""
    for m in conn.execute("SELECT id FROM modifiche WHERE tipo = ? AND oggetto_id = ? AND stato = 'errore'",
                          (tipo, oggetto_id)).fetchall():
        conn.execute("UPDATE modifiche SET stato = 'annullata' WHERE id = ?", (m["id"],))
        cancella_file(dati_dir, m["id"])
