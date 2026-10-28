"""Il lavoro in sottofondo, in due giri indipendenti:

- lezioni: UNA lezione alla volta, nell'ordine di arrivo; le altre aspettano in coda
  (stato salvato nel database, quindi la coda sopravvive a un riavvio);
- sfondo: le letture a pagamento (foto e PDF scansionati) e gli aggiornamenti della
  scheda del prof, uno alla volta.

Ogni passo è una funzione normale che fa una cosa e dice com'è andata: "fatto" (c'è
altro, continua) o "vuoto" (niente da fare, aspetta). I test chiamano i passi a mano.
Niente resta appeso: ogni lavoro finisce in uno stato con una frase leggibile."""

import asyncio
import logging
import shutil
from pathlib import Path

from . import clock, db, lettura
from . import scheda as schede
from .ai import RispostaNonUsabile
from .audio import ErroreMontaggio
from .config import MASSIMO_CARATTERI
from .lezione import Annullata, Contesto, ErroreLezione, Preparazione
from .rete import ErroreFornitore
from .spesa import TettoRaggiunto, VoceGratisFinita

log = logging.getLogger("uvicorn.error")

FATTO, VUOTO = "fatto", "vuoto"
FALLIMENTI_MASSIMI = 3
ERRORE_INTERNO_LEZIONE = "Lectio ha avuto un problema interno preparando la lezione. Tocca Riprova."


def _minuscola(frase: str) -> str:
    """"Hai raggiunto…" dopo i due punti diventa "hai raggiunto…" (ma "Mistral" resta com'è)."""
    if len(frase) > 1 and frase[0].isupper() and frase[1].islower() and not frase.startswith(("Mistral", "Azure", "Lectio")):
        return frase[0].lower() + frase[1:]
    return frase


def frase_errore(e: BaseException) -> str | None:
    """La frase per Andrea di un errore previsto; None se è un errore inatteso."""
    if isinstance(e, TettoRaggiunto):
        return e.frase + " Poi tocca Riprova."
    if isinstance(e, VoceGratisFinita):
        return e.frase
    if isinstance(e, (ErroreFornitore, ErroreLezione, ErroreMontaggio, RispostaNonUsabile)):
        return e.frase
    return None


class Lavoro:
    def __init__(self, ctx: Contesto):
        self.ctx = ctx
        self._loop: asyncio.AbstractEventLoop | None = None
        self._sveglie: dict[str, asyncio.Event] = {}
        self._fallimenti: dict[tuple[str, int], int] = {}

    @property
    def db_path(self) -> str:
        return self.ctx.settings.db_path

    # ------------------------------------------------------------------ lezioni

    def passo_lezione(self) -> str:
        with db.aperta(self.db_path) as conn, db.transazione(conn):
            riga = conn.execute(
                "SELECT id FROM lezioni WHERE stato = 'in_coda' ORDER BY in_coda_dal, id LIMIT 1"
            ).fetchone()
            if riga is None:
                return VUOTO
            if conn.execute("SELECT 1 FROM lezioni WHERE stato = 'in_preparazione' LIMIT 1").fetchone():
                return VUOTO  # una alla volta
            conn.execute("UPDATE lezioni SET stato = 'in_preparazione', errore = NULL WHERE id = ?", (riga["id"],))
        lezione_id = riga["id"]
        try:
            Preparazione(self.ctx, lezione_id).esegui()
        except Annullata:
            log.info("lezione %s annullata durante la preparazione", lezione_id)
            # Annullata o cancellata mentre si lavorava: i file a metà non servono più.
            with db.aperta(self.db_path) as conn:
                r = conn.execute("SELECT stato FROM lezioni WHERE id = ?", (lezione_id,)).fetchone()
            if r is None or r["stato"] == "annullata":
                shutil.rmtree(Path(self.ctx.settings.dati_dir) / "lezioni" / str(lezione_id), ignore_errors=True)
        except Exception as e:
            frase = frase_errore(e)
            if frase is None:
                log.exception("lezione %s: errore inatteso", lezione_id)
                frase = ERRORE_INTERNO_LEZIONE
            self._lezione_in_errore(lezione_id, frase)
        return FATTO

    def _lezione_in_errore(self, lezione_id: int, frase: str) -> None:
        with db.aperta(self.db_path) as conn, db.transazione(conn):
            r = conn.execute(
                "SELECT l.materia_id, m.nome FROM lezioni l JOIN materie m ON m.id = l.materia_id"
                " WHERE l.id = ? AND l.stato = 'in_preparazione'", (lezione_id,)
            ).fetchone()
            if r is None:
                return
            # La fase resta quella in cui si è fermata: Riprova riparte da lì.
            conn.execute("UPDATE lezioni SET stato = 'errore', errore = ? WHERE id = ?", (frase, lezione_id))
            db.aggiungi_novita(conn, "lezione_errore", f"La lezione di {r['nome']} si è fermata: {_minuscola(frase)}",
                               lezione_id=lezione_id, materia_id=r["materia_id"])

    # ------------------------------------------------------------------ materiale e schede

    def passo_sfondo(self) -> str:
        """Una foto da leggere o una scheda da aggiornare. Un elemento che fallisce con un
        errore inatteso (per esempio il database che non scrive) si riprova al giro dopo; dopo
        FALLIMENTI_MASSIMI volte di fila ci si ferma su quello (fino a Riprova o a un riavvio),
        così niente si ripete all'infinito."""
        with db.aperta(self.db_path) as conn:
            lavori = [(("materiale", r["id"]), self.leggi_materiale, r["id"]) for r in conn.execute(
                "SELECT id FROM materiali WHERE stato = 'in_lettura' ORDER BY id")]
            lavori += [(("scheda", r["materia_id"]), lambda mid: schede.aggiorna(self.ctx, mid), r["materia_id"])
                       for r in conn.execute("SELECT materia_id FROM schede WHERE da_aggiornare > 0 ORDER BY materia_id")]
        for chiave, fare, argomento in lavori:
            if self._fallimenti.get(chiave, 0) >= FALLIMENTI_MASSIMI:
                continue
            try:
                fare(argomento)
            except Exception:
                n = self._fallimenti.get(chiave, 0) + 1
                self._fallimenti[chiave] = n
                log.exception("sottofondo %s %s: errore inatteso (%s di fila)", chiave[0], chiave[1], n)
                if n >= FALLIMENTI_MASSIMI:
                    log.error("sottofondo %s %s: mi fermo, si riprova con Riprova o al riavvio", *chiave)
                return VUOTO  # si aspetta prima di riprovare
            self._fallimenti.pop(chiave, None)
            return FATTO
        return VUOTO

    def libera(self, tipo: str, ident: int) -> None:
        """Riprova a mano: l'elemento torna a essere provato dal giro in sottofondo."""
        self._fallimenti.pop((tipo, ident), None)

    def leggi_materiale(self, materiale_id: int) -> None:
        """Lettura di un materiale in sottofondo. Prima si rilegge il file sul NAS (gratis): se
        il testo c'è già (per esempio dopo la modalità prova) l'OCR non serve. Altrimenti
        all'OCR vanno solo le pagine vuote, con stima e tetto controllati prima."""
        with db.aperta(self.db_path) as conn:
            m = conn.execute("SELECT * FROM materiali WHERE id = ? AND stato = 'in_lettura'", (materiale_id,)).fetchone()
        if m is None:
            return
        ocr = self.ctx.ocr
        modello = self.ctx.settings.modello_ocr
        prezzi = self.ctx.cassa.prezzi
        try:
            contenuto = (Path(self.ctx.settings.dati_dir) / m["percorso"]).read_bytes()
        except (OSError, TypeError):
            self._materiale_in_errore(m, "Il file non si trova più sul server: caricalo di nuovo.")
            return
        try:
            letto = lettura.leggi(contenuto, m["formato"] or "")
            avviso = letto.avviso
            da_prova = 0
            if not letto.pagine_ocr:
                testo = letto.testo or ""
            else:
                pagine = letto.pagine_ocr
                if m["formato"] == "pdf" and len(pagine) < letto.pagine:
                    contenuto = lettura.sotto_pdf(contenuto, pagine)
                mime = lettura.IMMAGINI.get(m["formato"], "application/pdf")

                def registra(ricevuta, risposta):
                    if ocr.a_pagamento:
                        ricevuta.registra(risposta.pagine, "pagine", prezzi.costo_ocr(modello, risposta.pagine))

                risposta = self.ctx.cassa.chiama(
                    cosa=f"Lettura di «{m['titolo']}»", fornitore=ocr.nome,
                    stima_eur=prezzi.costo_ocr(modello, len(pagine)) if ocr.a_pagamento else 0.0,
                    quantita_stimata=len(pagine), unita="pagine", materiale_id=m["id"], materia_id=m["materia_id"],
                    fare=lambda: ocr.leggi(contenuto, mime, modello, pagine=len(pagine)), registra=registra,
                )
                da_prova = 0 if ocr.a_pagamento else 1
                if letto.testi_pagine is not None:
                    testo = lettura.unisci_pagine(letto.testi_pagine, pagine,
                                                  risposta.testi_pagine or [risposta.testo])
                else:
                    testo = risposta.testo
            testo = lettura.pulisci(testo)
            if not testo:
                raise ErroreLezione("Non ho trovato testo da leggere: prova con una foto più nitida e dritta.")
            if len(testo) > MASSIMO_CARATTERI:
                testo = testo[:MASSIMO_CARATTERI]
                avviso = "Ho tenuto solo i primi 200.000 caratteri: per il resto carica un file a parte."
        except lettura.FileIlleggibile as e:
            self._materiale_in_errore(m, e.frase)
            return
        except Exception as e:
            frase = frase_errore(e)
            if frase is None:
                log.exception("materiale %s: errore inatteso", materiale_id)
                frase = "Lectio ha avuto un problema leggendo questo file. Tocca Riprova."
            # Se anche questo non si scrive (database rotto), l'errore sale e lo conta passo_sfondo.
            self._materiale_in_errore(m, frase)
            return
        with db.aperta(self.db_path) as conn, db.transazione(conn):
            cur = conn.execute(
                "UPDATE materiali SET stato = 'pronto', errore = NULL, testo = ?, da_prova = ?, avviso = ?,"
                " pagine = ? WHERE id = ? AND stato = 'in_lettura'",
                (testo, da_prova, avviso, letto.pagine, m["id"]))
            if cur.rowcount == 0:
                return  # cancellato nel frattempo
            db.aggiungi_novita(conn, "materiale_letto", f"Ho letto «{m['titolo']}»", materia_id=m["materia_id"],
                               materiale_id=m["id"])
            if m["tipo"] in ("verifica", "interrogazione"):
                schede.segna_da_aggiornare(conn, m["materia_id"])

    def _materiale_in_errore(self, m, frase: str) -> None:
        with db.aperta(self.db_path) as conn, db.transazione(conn):
            cur = conn.execute("UPDATE materiali SET stato = 'errore', errore = ? WHERE id = ? AND stato = 'in_lettura'",
                               (frase, m["id"]))
            if cur.rowcount:
                db.aggiungi_novita(conn, "materiale_errore",
                                   f"Non sono riuscito a leggere «{m['titolo']}»: {_minuscola(frase)}",
                                   materia_id=m["materia_id"], materiale_id=m["id"])

    # ------------------------------------------------------------------ i giri

    def sveglia(self, nome: str) -> None:
        if self._loop is None or nome not in self._sveglie:
            return
        try:
            self._loop.call_soon_threadsafe(self._sveglie[nome].set)
        except RuntimeError:
            pass  # il server si sta spegnendo

    async def _ciclo(self, nome: str, passo, attesa_vuoto: float, ferma: asyncio.Event) -> None:
        sveglia = self._sveglie[nome]
        while not ferma.is_set():
            sveglia.clear()
            try:
                esito = await asyncio.to_thread(passo)
            except Exception:
                log.exception("lavoro %s: errore inatteso", nome)
                esito = VUOTO
            if esito == FATTO:
                await asyncio.sleep(0)
                continue
            try:
                await asyncio.wait_for(sveglia.wait(), timeout=attesa_vuoto)
            except asyncio.TimeoutError:
                pass

    async def gira(self, ferma: asyncio.Event) -> None:
        self._loop = asyncio.get_running_loop()
        self._sveglie = {n: asyncio.Event() for n in ("lezioni", "sfondo")}
        compiti = [
            asyncio.create_task(self._ciclo("lezioni", self.passo_lezione, 30.0, ferma)),
            asyncio.create_task(self._ciclo("sfondo", self.passo_sfondo, 30.0, ferma)),
        ]
        await ferma.wait()
        for c in compiti:
            c.cancel()
        await asyncio.gather(*compiti, return_exceptions=True)
        self._loop = None
