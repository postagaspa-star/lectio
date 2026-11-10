"""SQLite: una connessione per richiesta (o per passo del lavoro in sottofondo),
schema creato e migrato all'avvio. Come Sol: connessioni in autocommit, e dove servono
più istruzioni insieme si usa `transazione(conn)`.

Le spese non hanno legami con lezioni e materiali: cancellare una lezione non cancella
i soldi già spesi (il conto del mese deve restare vero)."""

import hashlib
import json
import secrets
import sqlite3
from contextlib import contextmanager

from fastapi import Request

from . import clock

COLORI = ["ambra", "rosa", "petrolio", "viola", "blu", "verde", "arancio", "oliva", "giada", "senape", "orchidea",
          "prugna", "cacao", "lavagna", "salvia", "lavanda"]

IMPOSTAZIONI_INIZIALI = {
    "tetto_eur": 2.00,
    "voce_predefinita": "mistral:voice_482",
    "durata_predefinita": 20,
    "fermati_alle_domande": True,
    "velocita": 1.0,
    "nome": "",  # come salutare lo studente: lo sceglie lui nelle impostazioni
    "risposte_scritte": True,  # v1.1 (contratto 15.2)
    "domande_saltabili": False,  # v1.2 (contratto 15.4)
}

SCHEMA_1 = """
CREATE TABLE dispositivi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('android', 'pc')),
    token_hash TEXT NOT NULL UNIQUE,
    abbinato_il TEXT NOT NULL,
    ultimo_accesso TEXT
);
CREATE TABLE codici_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    codice_hash TEXT NOT NULL,
    creato_il TEXT NOT NULL,
    scade_il TEXT NOT NULL,
    usato_il TEXT,
    annullato_il TEXT
);
CREATE TABLE tentativi_abbinamento (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ip TEXT NOT NULL,
    il TEXT NOT NULL
);
CREATE TABLE impostazioni (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);
CREATE TABLE interno (
    chiave TEXT PRIMARY KEY,
    valore TEXT NOT NULL
);
CREATE TABLE materie (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nome TEXT NOT NULL UNIQUE COLLATE NOCASE,
    prof TEXT NOT NULL DEFAULT '',
    colore TEXT NOT NULL,
    creata_il TEXT NOT NULL
);
CREATE TABLE schede (
    materia_id INTEGER PRIMARY KEY REFERENCES materie(id) ON DELETE CASCADE,
    -- argomenti, tipi_domanda, cosa_corregge, cosa_premia, lunghezza_risposte, consigli
    dati TEXT NOT NULL DEFAULT '{}',
    -- le note di Andrea: l'AI non le scrive mai
    note_mie TEXT NOT NULL DEFAULT '',
    -- quello che Andrea ha scritto o corretto a mano: un aggiornamento AI non lo cancella
    di_andrea TEXT NOT NULL DEFAULT '{}',
    stato TEXT NOT NULL DEFAULT 'vuota' CHECK (stato IN ('vuota', 'in_aggiornamento', 'pronta', 'errore')),
    errore TEXT,
    aggiornata_il TEXT,
    basata_su TEXT NOT NULL DEFAULT '{"verifiche": 0, "interrogazioni": 0, "esiti": 0}',
    -- quante richieste di aggiornamento sono arrivate (0 = nessuna in attesa)
    da_aggiornare INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE materiali (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL CHECK (tipo IN ('verifica', 'interrogazione', 'appunti', 'libro', 'altro')),
    titolo TEXT NOT NULL,
    data TEXT NOT NULL,
    origine TEXT NOT NULL CHECK (origine IN ('file', 'foto', 'scritto')),
    nome_file TEXT,
    percorso TEXT,
    formato TEXT,
    pagine INTEGER,
    testo TEXT NOT NULL DEFAULT '',
    stato TEXT NOT NULL CHECK (stato IN ('in_lettura', 'pronto', 'errore')),
    errore TEXT,
    creato_il TEXT NOT NULL
);
CREATE INDEX materiali_materia ON materiali (materia_id, creato_il DESC);
CREATE TABLE eventi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL CHECK (tipo IN ('scritta', 'orale')),
    argomento TEXT NOT NULL DEFAULT '',
    data TEXT NOT NULL,
    esito TEXT,
    creato_il TEXT NOT NULL
);
CREATE INDEX eventi_data ON eventi (data, id);
CREATE TABLE lezioni (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    evento_id INTEGER REFERENCES eventi(id) ON DELETE SET NULL,
    argomento TEXT NOT NULL,
    materiali TEXT NOT NULL DEFAULT '[]',
    durata_min INTEGER NOT NULL,
    voce TEXT NOT NULL,
    domande_saltabili INTEGER NOT NULL DEFAULT 0,
    stato TEXT NOT NULL CHECK (stato IN ('in_coda', 'in_preparazione', 'pronta', 'errore', 'annullata')),
    fase TEXT,
    fatto INTEGER NOT NULL DEFAULT 0,
    totale INTEGER NOT NULL DEFAULT 0,
    durata_s INTEGER,
    errore TEXT,
    nota TEXT,
    creata_il TEXT NOT NULL,
    in_coda_dal TEXT NOT NULL,
    pronta_il TEXT,
    audio_byte INTEGER,
    audio_sha256 TEXT,
    capitoli TEXT,
    testo TEXT,
    ascolto_posizione REAL NOT NULL DEFAULT 0,
    ascolto_finita INTEGER NOT NULL DEFAULT 0,
    ascolto_il TEXT
);
CREATE INDEX lezioni_coda ON lezioni (stato, in_coda_dal, id);
CREATE TABLE domande (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    lezione_id INTEGER NOT NULL REFERENCES lezioni(id) ON DELETE CASCADE,
    tipo TEXT NOT NULL CHECK (tipo IN ('blocco', 'interrogazione')),
    capitolo INTEGER NOT NULL,
    n INTEGER NOT NULL,
    di INTEGER NOT NULL,
    testo TEXT NOT NULL,
    risposta TEXT NOT NULL,
    inizio_s REAL NOT NULL,
    fine_domanda_s REAL NOT NULL,
    inizio_risposta_s REAL NOT NULL,
    fine_s REAL NOT NULL
);
CREATE INDEX domande_lezione ON domande (lezione_id, n);
CREATE TABLE risposte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    domanda_id INTEGER NOT NULL REFERENCES domande(id) ON DELETE CASCADE,
    lezione_id INTEGER NOT NULL REFERENCES lezioni(id) ON DELETE CASCADE,
    testo TEXT NOT NULL DEFAULT '',
    esito TEXT NOT NULL CHECK (esito IN ('giusta', 'parziale', 'sbagliata', 'saltata', 'non_corretta')),
    punteggio REAL,
    commento TEXT NOT NULL DEFAULT '',
    mancava TEXT NOT NULL DEFAULT '[]',
    costo_eur REAL NOT NULL DEFAULT 0,
    quando TEXT NOT NULL
);
CREATE INDEX risposte_domanda ON risposte (domanda_id, id DESC);
CREATE TABLE spese (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    quando TEXT NOT NULL,
    mese TEXT NOT NULL,
    cosa TEXT NOT NULL,
    fornitore TEXT NOT NULL,
    quantita REAL NOT NULL,
    unita TEXT NOT NULL,
    costo_eur REAL NOT NULL,
    lezione_id INTEGER,
    materiale_id INTEGER,
    materia_id INTEGER
);
CREATE INDEX spese_mese ON spese (mese, fornitore);
CREATE INDEX spese_lezione ON spese (lezione_id);
CREATE INDEX spese_materiale ON spese (materiale_id);
CREATE TABLE novita (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tipo TEXT NOT NULL,
    testo TEXT NOT NULL,
    lezione_id INTEGER,
    materia_id INTEGER,
    materiale_id INTEGER,
    quando TEXT NOT NULL
)
"""


def _semina_1(conn: sqlite3.Connection) -> None:
    for chiave, valore in IMPOSTAZIONI_INIZIALI.items():
        if chiave == "voce_predefinita":
            continue  # finché Andrea non la sceglie, segue LECTIO_VOCE_MISTRAL (vedi impostazioni_complete)
        conn.execute("INSERT INTO impostazioni (chiave, valore) VALUES (?, ?)", (chiave, json.dumps(valore)))


# 1.0.1 (dopo la revisione): prenotazioni nel conto, stime delle lezioni in coda, dati fatti in
# modalità prova, pagine da leggere con l'OCR, avvisi sul materiale, abbinamento da casa o da internet.
SCHEMA_2 = """
ALTER TABLE spese ADD COLUMN stato TEXT NOT NULL DEFAULT 'fatta';
ALTER TABLE lezioni ADD COLUMN stima_eur REAL NOT NULL DEFAULT 0;
ALTER TABLE lezioni ADD COLUMN stima_caratteri INTEGER NOT NULL DEFAULT 0;
ALTER TABLE materiali ADD COLUMN da_prova INTEGER NOT NULL DEFAULT 0;
ALTER TABLE materiali ADD COLUMN pagine_ocr TEXT;
ALTER TABLE materiali ADD COLUMN avviso TEXT;
ALTER TABLE schede ADD COLUMN da_prova INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tentativi_abbinamento ADD COLUMN gruppo TEXT NOT NULL DEFAULT 'casa'
"""

TESTO_OCR_FINTO = "Testo di prova. In modalità prova Lectio non legge davvero"


def _semina_2(conn: sqlite3.Connection) -> None:
    # Le foto lette in modalità prova hanno il testo segnaposto: si rileggono quando arriva la chiave.
    conn.execute("UPDATE materiali SET da_prova = 1 WHERE testo LIKE ?", (TESTO_OCR_FINTO + "%",))
    # Se non c'è mai stata una spesa vera di testo, le schede aggiornate finora le ha scritte il
    # generatore finto: si rifanno da zero con la chiave.
    if conn.execute("SELECT 1 FROM spese WHERE fornitore = 'mistral' LIMIT 1").fetchone() is None:
        conn.execute("UPDATE schede SET da_prova = 1 WHERE aggiornata_il IS NOT NULL")


# 1.1.0 (4/11): com'è fatta la lezione e le indicazioni di Andrea; chi ha scritto il titolo del materiale.
SCHEMA_3 = """
ALTER TABLE lezioni ADD COLUMN tipo TEXT NOT NULL DEFAULT 'spiegazione';
ALTER TABLE lezioni ADD COLUMN indicazioni TEXT NOT NULL DEFAULT '';
ALTER TABLE materiali ADD COLUMN titolo_da TEXT NOT NULL DEFAULT 'utente'
"""


def _semina_3(conn: sqlite3.Connection) -> None:
    # I titoli presi dal nome del file (o quelli predefiniti) li riscrive Lectio leggendo il materiale.
    from .titoli import titolo_automatico

    for r in conn.execute("SELECT id, titolo, nome_file, origine, tipo, data FROM materiali").fetchall():
        if titolo_automatico(r["titolo"], r["nome_file"]):
            conn.execute("UPDATE materiali SET titolo_da = 'file' WHERE id = ?", (r["id"],))


# 1.2.0 (10/11): la memoria sui documenti, gli schemi, "Non ho capito", il ripasso lampo, la verifica simulata.
SCHEMA_4 = """
ALTER TABLE spese ADD COLUMN riferimento TEXT;
CREATE INDEX spese_riferimento ON spese (riferimento);
CREATE TABLE pezzi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    materiale_id INTEGER NOT NULL REFERENCES materiali(id) ON DELETE CASCADE,
    n INTEGER NOT NULL,
    pagina INTEGER,
    rango INTEGER NOT NULL,
    testo TEXT NOT NULL,
    vettore BLOB,
    modello TEXT
);
CREATE INDEX pezzi_materiale ON pezzi (materiale_id, n);
CREATE INDEX pezzi_materia ON pezzi (materia_id, rango);
CREATE TABLE schemi (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    evento_id INTEGER REFERENCES eventi(id) ON DELETE SET NULL,
    argomento TEXT NOT NULL,
    titolo TEXT NOT NULL DEFAULT '',
    forma TEXT NOT NULL,
    livello TEXT NOT NULL,
    indicazioni TEXT NOT NULL DEFAULT '',
    materiali TEXT NOT NULL DEFAULT '[]',
    stato TEXT NOT NULL CHECK (stato IN ('in_coda', 'in_preparazione', 'pronto', 'errore', 'annullato')),
    fase TEXT,
    fatto INTEGER NOT NULL DEFAULT 0,
    totale INTEGER NOT NULL DEFAULT 0,
    frase TEXT,
    errore TEXT,
    nota TEXT,
    stima_eur REAL NOT NULL DEFAULT 0,
    creato_il TEXT NOT NULL,
    in_coda_dal TEXT NOT NULL,
    pronto_il TEXT,
    contenuto TEXT,
    carte INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX schemi_coda ON schemi (stato, in_coda_dal, id);
CREATE TABLE riassunti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    schema_id INTEGER NOT NULL REFERENCES schemi(id) ON DELETE CASCADE,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    forma TEXT NOT NULL CHECK (forma IN ('punti', 'discorsivo', 'misto')),
    titolo TEXT NOT NULL DEFAULT '',
    stato TEXT NOT NULL CHECK (stato IN ('in_coda', 'in_preparazione', 'pronto', 'errore')),
    frase TEXT,
    errore TEXT,
    nota TEXT,
    stima_eur REAL NOT NULL DEFAULT 0,
    creato_il TEXT NOT NULL,
    in_coda_dal TEXT NOT NULL,
    pronto_il TEXT,
    parole INTEGER NOT NULL DEFAULT 0,
    contenuto TEXT,
    UNIQUE (schema_id, forma)
);
CREATE INDEX riassunti_coda ON riassunti (stato, in_coda_dal, id);
CREATE TABLE simulate (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    evento_id INTEGER REFERENCES eventi(id) ON DELETE SET NULL,
    argomento TEXT NOT NULL,
    titolo TEXT NOT NULL DEFAULT '',
    minuti INTEGER NOT NULL,
    materiali TEXT NOT NULL DEFAULT '[]',
    indicazioni TEXT NOT NULL DEFAULT '',
    stato TEXT NOT NULL CHECK (stato IN ('in_coda', 'in_preparazione', 'pronta', 'in_corso', 'in_correzione', 'corretta',
                                         'errore', 'annullata')),
    fase TEXT,
    fatto INTEGER NOT NULL DEFAULT 0,
    totale INTEGER NOT NULL DEFAULT 0,
    frase TEXT,
    errore TEXT,
    nota TEXT,
    stima_eur REAL NOT NULL DEFAULT 0,
    creata_il TEXT NOT NULL,
    in_coda_dal TEXT NOT NULL,
    iniziata_il TEXT,
    consegnata_il TEXT,
    domande TEXT NOT NULL DEFAULT '[]',
    risposte TEXT NOT NULL DEFAULT '{}',
    esito TEXT
);
CREATE INDEX simulate_coda ON simulate (stato, in_coda_dal, id);
CREATE TABLE carte (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    origine TEXT NOT NULL,
    chiave TEXT NOT NULL UNIQUE,
    domanda TEXT NOT NULL,
    risposta TEXT NOT NULL,
    fonti TEXT NOT NULL DEFAULT '[]',
    scatola INTEGER NOT NULL DEFAULT 1,
    prossima TEXT NOT NULL,
    creata_il TEXT NOT NULL
);
CREATE INDEX carte_prossima ON carte (prossima, scatola);
CREATE INDEX carte_materia ON carte (materia_id, prossima);
CREATE TABLE chiarimenti (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    lezione_id INTEGER REFERENCES lezioni(id) ON DELETE SET NULL,
    schema_id INTEGER REFERENCES schemi(id) ON DELETE SET NULL,
    passaggio TEXT NOT NULL DEFAULT '',
    domanda TEXT NOT NULL DEFAULT '',
    risposta TEXT NOT NULL,
    trovato INTEGER NOT NULL,
    fonti TEXT NOT NULL DEFAULT '[]',
    costo_eur REAL NOT NULL DEFAULT 0,
    quando TEXT NOT NULL
);
CREATE INDEX chiarimenti_materia ON chiarimenti (materia_id, id DESC)
"""


def _semina_4(conn: sqlite3.Connection) -> None:
    # La scheda del prof ora si conta dalle domande vere: quelle scritte prima si rifanno.
    conn.execute(
        "UPDATE schede SET da_aggiornare = da_aggiornare + 1, stato = 'in_aggiornamento', errore = NULL WHERE materia_id IN"
        " (SELECT materia_id FROM materiali WHERE stato = 'pronto' AND tipo IN ('verifica', 'interrogazione'))")
    # Le risposte sbagliate che c'erano già diventano le prime carte del ripasso lampo.
    giorno = clock.oggi().isoformat()
    for r in conn.execute(
        "SELECT d.id, d.testo, d.risposta, l.materia_id FROM risposte r JOIN domande d ON d.id = r.domanda_id"
        " JOIN lezioni l ON l.id = r.lezione_id WHERE r.esito IN ('parziale', 'sbagliata')"
        " AND r.id = (SELECT MAX(r2.id) FROM risposte r2 WHERE r2.domanda_id = r.domanda_id)"
    ).fetchall():
        conn.execute(
            "INSERT OR IGNORE INTO carte (materia_id, origine, chiave, domanda, risposta, prossima, creata_il)"
            " VALUES (?, 'lezione', ?, ?, ?, ?, ?)",
            (r["materia_id"], f"lezione:{r['id']}", r["testo"], r["risposta"], giorno, clock.adesso()))


# 1.3.0 (10/11): le cartelle dentro la materia (un livello solo) per raggruppare il materiale.
# cartella_da: 'utente' (l'ha messo Andrea), 'lectio' (l'ha messo Lectio), 'attesa' (Lectio deciderà dopo
# averlo letto), 'nessuna' (Lectio non ha trovato una cartella chiara), NULL (materiale di prima: si lascia stare).
SCHEMA_5 = """
CREATE TABLE cartelle (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    materia_id INTEGER NOT NULL REFERENCES materie(id) ON DELETE CASCADE,
    nome TEXT NOT NULL COLLATE NOCASE,
    creata_il TEXT NOT NULL,
    UNIQUE (materia_id, nome)
);
ALTER TABLE materiali ADD COLUMN cartella_id INTEGER REFERENCES cartelle(id) ON DELETE SET NULL;
ALTER TABLE materiali ADD COLUMN cartella_da TEXT;
ALTER TABLE materiali ADD COLUMN cartella_il TEXT;
CREATE INDEX materiali_cartella ON materiali (cartella_id)
"""


# Migrazioni: (versione, sql, semina). Si aggiunge in fondo, mai si modifica una già rilasciata.
MIGRAZIONI = [
    (1, SCHEMA_1, _semina_1),
    (2, SCHEMA_2, _semina_2),
    (3, SCHEMA_3, _semina_3),
    (4, SCHEMA_4, _semina_4),
    (5, SCHEMA_5, None),
]


def connetti(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, check_same_thread=False, isolation_level=None, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 15000")
    return conn


@contextmanager
def transazione(conn: sqlite3.Connection):
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    else:
        conn.execute("COMMIT")


@contextmanager
def aperta(db_path: str):
    conn = connetti(db_path)
    try:
        yield conn
    finally:
        conn.close()


def init_db(db_path: str) -> None:
    conn = connetti(db_path)
    try:
        # WAL: il lavoro in sottofondo scrive mentre le app leggono, senza bloccarsi.
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("CREATE TABLE IF NOT EXISTS versione_schema (versione INTEGER NOT NULL)")
        attuale = conn.execute("SELECT MAX(versione) AS v FROM versione_schema").fetchone()["v"] or 0
        for versione, sql, semina in MIGRAZIONI:
            if versione <= attuale:
                continue
            with transazione(conn):
                for istruzione in sql.split(";"):
                    if istruzione.strip():
                        conn.execute(istruzione)
                if semina:
                    semina(conn)
                conn.execute("INSERT INTO versione_schema (versione) VALUES (?)", (versione,))
    finally:
        conn.close()


def get_conn(request: Request):
    conn = connetti(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


def hash_segreto(segreto: str) -> str:
    """Nel database finisce solo l'hash di token e codici, mai il segreto."""
    return hashlib.sha256(segreto.encode("utf-8")).hexdigest()


def impostazioni(conn: sqlite3.Connection, voce_mistral: str = "voice_482") -> dict:
    valori = dict(IMPOSTAZIONI_INIZIALI)
    valori["voce_predefinita"] = f"mistral:{voce_mistral}"
    for riga in conn.execute("SELECT chiave, valore FROM impostazioni"):
        valori[riga["chiave"]] = json.loads(riga["valore"])
    return valori


def salva_impostazione(conn: sqlite3.Connection, chiave: str, valore) -> None:
    conn.execute(
        "INSERT INTO impostazioni (chiave, valore) VALUES (?, ?)"
        " ON CONFLICT (chiave) DO UPDATE SET valore = excluded.valore",
        (chiave, json.dumps(valore)),
    )


def leggi_interno(conn: sqlite3.Connection, chiave: str, base=None):
    riga = conn.execute("SELECT valore FROM interno WHERE chiave = ?", (chiave,)).fetchone()
    return json.loads(riga["valore"]) if riga else base


def salva_interno(conn: sqlite3.Connection, chiave: str, valore) -> None:
    conn.execute(
        "INSERT INTO interno (chiave, valore) VALUES (?, ?) ON CONFLICT (chiave) DO UPDATE SET valore = excluded.valore",
        (chiave, json.dumps(valore)),
    )


def server_id(conn: sqlite3.Connection) -> str:
    """Un identificativo casuale (32 caratteri esadecimali) creato una volta sola e salvato nel
    database sul volume: resta uguale per questo server, anche dopo riavvii e aggiornamenti."""
    valore = leggi_interno(conn, "server_id")
    if valore is None:
        conn.execute("INSERT OR IGNORE INTO interno (chiave, valore) VALUES ('server_id', ?)",
                     (json.dumps(secrets.token_hex(16)),))
        valore = leggi_interno(conn, "server_id")
    return valore


def aggiungi_novita(conn: sqlite3.Connection, tipo: str, testo: str, lezione_id: int | None = None,
                    materia_id: int | None = None, materiale_id: int | None = None) -> None:
    conn.execute(
        "INSERT INTO novita (tipo, testo, lezione_id, materia_id, materiale_id, quando) VALUES (?, ?, ?, ?, ?, ?)",
        (tipo, testo, lezione_id, materia_id, materiale_id, clock.adesso()),
    )


def colore_libero(conn: sqlite3.Connection) -> str:
    """Il primo colore dell'elenco non ancora usato; se sono tutti usati, il primo tra i meno usati."""
    usati = [r[0] for r in conn.execute("SELECT colore FROM materie")]
    return min(COLORI, key=lambda c: (usati.count(c), COLORI.index(c)))


INTERROTTA_RIAVVIO = "Interrotta da un riavvio: tocca Riprova"


def ripristina_dopo_riavvio(db_path: str) -> None:
    """All'avvio nessuna lezione è in preparazione: quella che lo risulta si era fermata a
    metà. Va in errore con la sua frase; Riprova riparte dalla fase in cui era, senza
    ripagare i pezzi già fatti. Materiale da leggere e schede da aggiornare restano in
    coda: il lavoro in sottofondo li riprende da solo."""
    conn = connetti(db_path)
    try:
        with transazione(conn):
            for riga in conn.execute(
                "SELECT l.id, l.materia_id, m.nome FROM lezioni l JOIN materie m ON m.id = l.materia_id"
                " WHERE l.stato = 'in_preparazione'"
            ).fetchall():
                conn.execute(
                    "UPDATE lezioni SET stato = 'errore', errore = ? WHERE id = ?", (INTERROTTA_RIAVVIO, riga["id"])
                )
                aggiungi_novita(conn, "lezione_errore", f"La lezione di {riga['nome']} si è fermata: tocca Riprova",
                                lezione_id=riga["id"], materia_id=riga["materia_id"])
            # Schemi e verifiche simulate: stessa regola. Le correzioni restano in coda e ripartono da sole.
            conn.execute("UPDATE schemi SET stato = 'errore', errore = ? WHERE stato = 'in_preparazione'",
                         (INTERROTTA_RIAVVIO,))
            conn.execute("UPDATE simulate SET stato = 'errore', errore = ? WHERE stato = 'in_preparazione'",
                         (INTERROTTA_RIAVVIO,))
            conn.execute("UPDATE riassunti SET stato = 'errore', errore = ? WHERE stato = 'in_preparazione'",
                         (INTERROTTA_RIAVVIO,))
    finally:
        conn.close()
