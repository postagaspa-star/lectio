"""Le cartelle della materia (contratto, sezione 27): un livello solo, solo per il materiale.

Lectio mette da solo un file nuovo in una cartella, dopo averlo letto, quando parla chiaramente di
quella cartella sola:
- le parole del nome della cartella ci sono tutte (nel titolo, o tante volte nel testo), oppure
- assomiglia ai file che la cartella ha già: stesse parole, contando di più quelle rare nella materia
  (una parola che sta in tutto il materiale, come "scultura" in Storia dell'Arte, non distingue niente).
Niente AI: è gratis e immediato. Se due cartelle vanno bene allo stesso modo, o nessuna, il file resta
fuori e lo sposta Andrea."""

import math
import sqlite3
from collections import Counter
from datetime import timedelta

from . import clock, db, titoli

NOME_MASSIMO = 40
ORE_NUOVI = 24  # "messi da Lectio" nella riga della cartella: quelli delle ultime 24 ore
CARATTERI_LETTI = 60_000  # per confrontare basta l'inizio: i libri lunghi non rallentano il server
SOGLIA_SIMILE = 0.12  # somiglianza minima con i file che la cartella ha già (0-1)
MARGINE = 2.0  # la cartella scelta deve valere almeno il doppio della seconda
SOGLIA_PROPOSTA = 0.06  # per "Metti dei file qui" basta meno: decide Andrea
DA_UTENTE = "utente"
DA_LECTIO = "lectio"
IN_ATTESA = "attesa"
NESSUNA = "nessuna"


def nome_pulito(nome) -> str:
    return " ".join(str(nome or "").split())


def forma(conn: sqlite3.Connection, r: sqlite3.Row) -> dict:
    dal = clock.iso(clock.now() - timedelta(hours=ORE_NUOVI))
    c = conn.execute(
        "SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN cartella_da = 'lectio' AND cartella_il >= ? THEN 1 ELSE 0 END), 0)"
        " AS nuovi FROM materiali WHERE cartella_id = ?", (dal, r["id"])).fetchone()
    return {
        "id": r["id"],
        "materia_id": r["materia_id"],
        "nome": r["nome"],
        "file": c["n"],
        "messi_da_lectio": c["nuovi"],
        "creata_il": clock.per_utente(r["creata_il"]),
    }


def elenco(conn: sqlite3.Connection, materia_id: int) -> list[dict]:
    righe = conn.execute("SELECT * FROM cartelle WHERE materia_id = ?", (materia_id,)).fetchall()
    return [forma(conn, r) for r in sorted(righe, key=lambda r: r["nome"].casefold())]


# --------------------------------------------------------------------------- il confronto


def _radici(testo: str) -> list[str]:
    return [titoli.radice(p) for p in titoli.parole(testo)]


class Confronto:
    """Il materiale pronto di una materia e le sue cartelle, pronti per essere confrontati.

    `materiali`: [{"id", "titolo", "testo", "cartella_id"}]; `cartelle`: [{"id", "nome"}]."""

    def __init__(self, materiali: list[dict], cartelle: list[dict]):
        self.materiali = {m["id"]: m for m in materiali}
        self.cartelle = {c["id"]: c for c in cartelle}
        self.nel_titolo: dict[int, set[str]] = {}
        self.nel_testo: dict[int, Counter] = {}
        for m in materiali:
            self.nel_titolo[m["id"]] = set(_radici(m.get("titolo") or ""))
            self.nel_testo[m["id"]] = Counter(_radici((m.get("testo") or "")[:CARATTERI_LETTI]))
        n = len(materiali)
        presenza = Counter()
        for ident in self.materiali:
            presenza.update(self.nel_titolo[ident] | set(self.nel_testo[ident]))
        self._presenza = presenza
        # Peso di una parola: più è rara nella materia, più conta (0 se sta in tutto il materiale).
        self._peso = {r: math.log((n + 1) / (d + 1)) for r, d in presenza.items()}
        self._vettori = {ident: self._vettore(ident) for ident in self.materiali}

    def _vettore(self, ident: int) -> dict[str, float]:
        conti = Counter(self.nel_testo[ident])
        for r in self.nel_titolo[ident]:
            conti[r] += 3  # il titolo dice di cosa parla
        v = {r: (1 + math.log(c)) * self._peso.get(r, 0.0) for r, c in conti.items()}
        v = {r: x for r, x in v.items() if x > 0}
        norma = math.sqrt(sum(x * x for x in v.values()))
        return {r: x / norma for r, x in v.items()} if norma else {}

    def parole_del_nome(self, cartella_id: int) -> list[str]:
        """Le radici del nome che distinguono qualcosa: quelle presenti in più del 60% del materiale
        (con almeno 4 materiali) non contano, a meno che lo siano tutte."""
        radici = list(dict.fromkeys(_radici(self.cartelle[cartella_id]["nome"])))
        n = len(self.materiali)
        if n >= 4:
            rare = [r for r in radici if self._presenza.get(r, 0) / n <= titoli.SOGLIA_COMUNE]
            if rare:
                return rare
        return radici

    def nome(self, materiale_id: int, cartella_id: int) -> float:
        """Quanto il file parla del nome della cartella (0-1): 1 = ogni parola nel titolo o almeno tre
        volte nel testo, metà punto se c'è due volte. Per le proposte di "Metti dei file qui"."""
        radici = self.parole_del_nome(cartella_id)
        if not radici:
            return 0.0
        titolo, testo = self.nel_titolo[materiale_id], self.nel_testo[materiale_id]
        punti = sum(1.0 if r in titolo or testo[r] >= 3 else 0.5 if testo[r] == 2 else 0.0 for r in radici)
        return punti / len(radici)

    def nel_titolo_del_file(self, materiale_id: int, cartella_id: int) -> bool:
        """Tutte le parole del nome della cartella stanno nel titolo del file. Per mettere un file da solo
        conta solo il titolo: nel testo di una pagina di libro "chiesa" o "papa" ci sono quasi sempre."""
        radici = self.parole_del_nome(cartella_id)
        return bool(radici) and all(r in self.nel_titolo[materiale_id] for r in radici)

    def simile(self, materiale_id: int, cartella_id: int | None) -> float:
        """Quanto il file assomiglia ai file che la cartella ha già (escluso sé stesso), 0-1.
        Con None: ai file che stanno fuori dalle cartelle."""
        dentro = [i for i, m in self.materiali.items() if m.get("cartella_id") == cartella_id and i != materiale_id]
        if not dentro:
            return 0.0
        centro: Counter = Counter()
        for i in dentro:
            for r, x in self._vettori[i].items():
                centro[r] += x / len(dentro)
        norma = math.sqrt(sum(x * x for x in centro.values()))
        if not norma:
            return 0.0
        v = self._vettori[materiale_id]
        return sum(x * centro.get(r, 0.0) for r, x in v.items()) / norma

    def scegli(self, materiale_id: int) -> int | None:
        """La cartella giusta per un file, solo se è chiaramente una.

        1. Il nome della cartella sta tutto nel titolo del file (e in nessun'altra cartella): va lì, a
           meno che il file assomigli molto di più ai file di un'altra cartella.
        2. Altrimenti il file deve assomigliare ai file di una cartella almeno il doppio che a quelli di
           ogni altra cartella E a quelli che stanno fuori: chi parla di tutto non finisce da nessuna parte."""
        if materiale_id not in self.materiali or not self.cartelle:
            return None
        simili = {c: self.simile(materiale_id, c) for c in self.cartelle}
        per_nome = [c for c in self.cartelle if self.nel_titolo_del_file(materiale_id, c)]
        if len(per_nome) == 1:
            scelta = per_nome[0]
            altra = max((x for c, x in simili.items() if c != scelta), default=0.0)
            if altra >= SOGLIA_SIMILE and altra >= MARGINE * max(simili[scelta], 0.01):
                return None
            return scelta
        if len(per_nome) > 1:
            return _chiaramente_prima(per_nome, simili)
        fuori = self.simile(materiale_id, None)
        prima = _chiaramente_prima(list(self.cartelle), {**simili, None: fuori}, con=[None])
        if prima is not None and simili[prima] >= SOGLIA_SIMILE:
            return prima
        return None

    def proposti(self, cartella_id: int) -> list[int]:
        """Per "Metti dei file qui": il materiale fuori dalle cartelle che parla di questa. Basta meno che
        per metterlo da solo (decide Andrea): il nome della cartella almeno a metà, oppure una somiglianza
        con i suoi file più alta che con ogni altra cartella e con il resto del materiale."""
        scelti = []
        for ident, m in self.materiali.items():
            if m.get("cartella_id") is not None:
                continue
            if self.nome(ident, cartella_id) >= 0.5:
                scelti.append(ident)
                continue
            qui = self.simile(ident, cartella_id)
            if qui < SOGLIA_PROPOSTA:
                continue
            altrove = max([self.simile(ident, c) for c in self.cartelle if c != cartella_id] + [self.simile(ident, None)])
            if qui >= 1.2 * altrove:
                scelti.append(ident)
        return scelti


def _chiaramente_prima(candidate: list, punteggi: dict, con: list | None = None) -> int | None:
    """La candidata con il punteggio più alto, se vale almeno MARGINE volte la seconda (contando anche le
    rivali in `con`, che non vincono mai) e la distacca di almeno 0,05."""
    ordinate = sorted(candidate, key=lambda c: punteggi[c], reverse=True)
    if not ordinate or punteggi[ordinate[0]] <= 0:
        return None
    prima = ordinate[0]
    rivali = [punteggi[c] for c in ordinate[1:]] + [punteggi[c] for c in (con or [])]
    seconda = max(rivali, default=0.0)
    if punteggi[prima] >= MARGINE * seconda and punteggi[prima] - seconda >= 0.05:
        return prima
    return None


def confronto(conn: sqlite3.Connection, materia_id: int) -> Confronto:
    materiali = [dict(r) for r in conn.execute(
        "SELECT id, titolo, testo, cartella_id FROM materiali WHERE materia_id = ? AND stato = 'pronto'", (materia_id,))]
    cartelle = [dict(r) for r in conn.execute("SELECT id, nome FROM cartelle WHERE materia_id = ?", (materia_id,))]
    return Confronto(materiali, cartelle)


def sistema(db_path: str, materiale_id: int) -> int | None:
    """Lavoro in sottofondo: un file nuovo appena letto va nella sua cartella, se è chiara. Una volta
    sola: dopo resta dove l'ha messo Lectio o dove lo sposta Andrea."""
    with db.aperta(db_path) as conn:
        m = conn.execute("SELECT id, materia_id, titolo FROM materiali WHERE id = ? AND stato = 'pronto'"
                         " AND cartella_da = ?", (materiale_id, IN_ATTESA)).fetchone()
        if m is None:
            return None
        scelta = confronto(conn, m["materia_id"]).scegli(m["id"])
        with db.transazione(conn):
            if scelta is None:
                conn.execute("UPDATE materiali SET cartella_da = ? WHERE id = ? AND cartella_da = ?",
                             (NESSUNA, m["id"], IN_ATTESA))
                return None
            # Se nel frattempo Andrea l'ha spostato, o la cartella non c'è più, vince lui.
            cur = conn.execute(
                "UPDATE materiali SET cartella_id = ?, cartella_da = ?, cartella_il = ? WHERE id = ? AND cartella_da = ?"
                " AND EXISTS (SELECT 1 FROM cartelle WHERE id = ?)",
                (scelta, DA_LECTIO, clock.adesso(), m["id"], IN_ATTESA, scelta))
            if not cur.rowcount:
                conn.execute("UPDATE materiali SET cartella_da = ? WHERE id = ? AND cartella_da = ?",
                             (NESSUNA, m["id"], IN_ATTESA))
                return None
            nome = conn.execute("SELECT nome FROM cartelle WHERE id = ?", (scelta,)).fetchone()["nome"]
            db.aggiungi_novita(conn, "materiale_in_cartella", f"Ho messo «{m['titolo']}» nella cartella {nome}",
                               materia_id=m["materia_id"], materiale_id=m["id"])
        return scelta
