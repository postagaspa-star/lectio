package eu.stgm.lectio.ui

import eu.stgm.lectio.RegolaMain
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.aspetta
import eu.stgm.lectio.dati.CacheMemoria
import eu.stgm.lectio.dati.CopiaSimulata
import eu.stgm.lectio.dati.DepositoStudio
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.logica.FormaRiassunto
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.studio.GenereStudio
import eu.stgm.lectio.ui.studio.LampoViewModel
import eu.stgm.lectio.ui.studio.NonHoCapitoViewModel
import eu.stgm.lectio.ui.studio.NuovoStudioViewModel
import eu.stgm.lectio.ui.studio.RiassuntoViewModel
import eu.stgm.lectio.ui.studio.SchemaViewModel
import eu.stgm.lectio.ui.studio.SimulataViewModel
import eu.stgm.lectio.ui.studio.StatoSalva
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.oggi.OggiViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Le schermate nuove della 1.2 girano sui ViewModel veri, contro un server finto. */
class StudioSchermateTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val deposito = DepositoStudio()
    private val sorgente = SorgenteServer(server.client(), CacheMemoria(), deposito = deposito)
    private val oggi = LocalDate.of(2026, 11, 10)

    @After
    fun chiudi() = server.close()

    private fun corpo(percorso: String) =
        JsonLectio.parseToJsonElement(server.richiesteA(percorso).last().body.clone().readUtf8()).jsonObject

    // --- Non ho capito ------------------------------------------------------------------------

    @Test
    fun nonCeNeiDocumentiEUnaRispostaNonUnErrore() {
        server.rispondi("POST /api/chiarimenti", """{"id": 1, "risposta": "Nei tuoi documenti non c'è.", "trovato": false, "fonti": []}""")
        val vm = NonHoCapitoViewModel(sorgente)
        vm.apriDaSchema(7, 3, 2, "Il Discobolo")
        vm.spunto("Spiegamelo più facile")
        vm.invia()
        aspetta { vm.stato.value.risposte.isNotEmpty() }
        val s = vm.stato.value
        assertNull(s.errore)
        assertFalse(s.ultima!!.trovato)
        assertEquals("", s.domanda)
        val b = corpo("POST /api/chiarimenti")
        assertEquals("3", b["schema_id"]!!.jsonPrimitive.content)
        assertEquals("Il Discobolo", b["passaggio"]!!.jsonPrimitive.content)
    }

    @Test
    fun unErroreVeroTieneLaDomandaScritta() {
        server.rispondi("POST /api/chiarimenti", """{"errore": "Il tetto del mese è finito", "codice": "tetto"}""", 402)
        val vm = NonHoCapitoViewModel(sorgente)
        vm.apriDaLezione(7, 12, 312.0, "Al minuto 5:12 · Augusto")
        vm.domanda("Perché?")
        vm.invia()
        aspetta { vm.stato.value.errore != null }
        assertEquals("Perché?", vm.stato.value.domanda)
        assertEquals("Il tetto del mese è finito", vm.stato.value.errore)
    }

    // --- Nuovo schema e nuova simulata ---------------------------------------------------------

    private fun prepara(genere: GenereStudio): NuovoStudioViewModel {
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi("GET /api/materie/1/materiale", """[{"id": 5, "materia_id": 1, "tipo": "verifica", "titolo": "Verifica", "data": "2026-10-12", "origine": "file", "caratteri": 100, "stato": "pronto"}]""")
        server.rispondi("GET /api/spesa", """{"mese": "2026-11", "speso_eur": 0.2, "tetto_eur": 2, "percentuale": 10, "si_azzera_il": "2026-12-01"}""")
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.2.0", "modalita_prova": false}""")
        return NuovoStudioViewModel(sorgente, genere, 1, null, null, { oggi }, 0, 0).apply { carica() }
    }

    @Test
    fun ilPulsanteDelloSchemaRestaSpentoFinoAllaStima() {
        val vm = prepara(GenereStudio.SCHEMA)
        aspetta { vm.stato.value.pronto }
        assertFalse(vm.stato.value.puoPreparare)
        assertEquals("Prepara lo schema", vm.stato.value.etichettaPulsante)
        vm.argomento("Augusto")
        vm.tutti()
        // La stima non è ancora arrivata (404 dal server finto).
        Thread.sleep(150)
        assertNull(vm.stato.value.stima)
        assertFalse(vm.stato.value.puoPreparare)
        assertEquals("Prepara lo schema", vm.stato.value.etichettaPulsante)

        server.rispondi("POST /api/schemi/stima", """{"costo_eur": 0.08, "spesa_mese_eur": 0.2, "tetto_eur": 2, "dopo_eur": 0.28, "consentita": true, "immagini": 3}""")
        vm.livello("essenziale")
        aspetta { vm.stato.value.stima != null }
        assertTrue(vm.stato.value.puoPreparare)
        assertEquals("Prepara lo schema · 0,08 €", vm.stato.value.etichettaPulsante)
        assertEquals("essenziale", corpo("POST /api/schemi/stima")["livello"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/schemi", """{"id": 3, "materia_id": 1, "argomento": "Augusto", "stato": "in_coda"}""", 201)
        vm.prepara()
        aspetta { vm.stato.value.schemaCreato != null }
        assertEquals(setOf(3L), deposito.seguiti().schemi)
    }

    @Test
    fun unaStimaNonConsentitaSpegneIlPulsante() {
        val vm = prepara(GenereStudio.SIMULATA)
        aspetta { vm.stato.value.pronto }
        server.rispondi("POST /api/simulate/stima", """{"costo_eur": 0.03, "tetto_eur": 2, "dopo_eur": 2.1, "consentita": false, "motivo": "Sforerebbe il tetto"}""")
        vm.argomento("Augusto")
        vm.tutti()
        aspetta { vm.stato.value.stima != null }
        assertFalse(vm.stato.value.puoPreparare)
        assertEquals("Sforerebbe il tetto", vm.stato.value.stima?.motivo)
    }

    // --- Lettura dello schema e riassunti --------------------------------------------------------

    private val schemaPronto = """{"id": 3, "materia_id": 1, "argomento": "x", "titolo": "Augusto", "stato": "pronto",
 "riassunti": {"stima_eur": 0.01, "fatti": [{"id": 4, "forma": "misto", "stato": "pronto"}]},
 "contenuto": {"forma": "capitoli", "sezioni": [{"n": 1, "titolo": "Uno", "blocchi": [{"tipo": "paragrafo", "testo": "Testo"}]}], "fonti": {}, "avvisi": []}}"""

    private fun schemaVm(): SchemaViewModel {
        server.rispondi("GET /api/schemi/3", schemaPronto)
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada"}]""")
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.2.0", "modalita_prova": false}""")
        return SchemaViewModel(sorgente, 3).apply { carica() }
    }

    @Test
    fun unaFormaGiaFattaSiApreSenzaSpendere() {
        val vm = schemaVm()
        aspetta { vm.stato.value.schema != null }
        vm.scegliForma(FormaRiassunto.MISTO)
        aspetta { vm.stato.value.apriRiassunto == "misto" }
        assertTrue(server.richiesteA("POST /api/schemi/3/riassunti").isEmpty())
    }

    @Test
    fun unaFormaNuovaSiChiedeESiSegue() {
        val vm = schemaVm()
        aspetta { vm.stato.value.schema != null }
        server.rispondi("POST /api/schemi/3/riassunti", """{"id": 8, "schema_id": 3, "materia_id": 1, "forma": "punti", "stato": "in_coda"}""", 201)
        vm.scegliForma(FormaRiassunto.PUNTI)
        aspetta { vm.stato.value.apriRiassunto == "punti" }
        assertEquals("punti", corpo("POST /api/schemi/3/riassunti")["forma"]!!.jsonPrimitive.content)
        assertEquals(setOf(8L), deposito.seguiti().riassunti)
    }

    @Test
    fun colTettoFinitoIlFoglioRestaEDiceIlPerche() {
        val vm = schemaVm()
        aspetta { vm.stato.value.schema != null }
        server.rispondi("POST /api/schemi/3/riassunti", """{"errore": "Il tetto del mese è finito", "codice": "tetto"}""", 402)
        vm.scegliForma(FormaRiassunto.PUNTI)
        aspetta { vm.stato.value.erroreRiassunto != null }
        assertNull(vm.stato.value.apriRiassunto)
        assertEquals("Il tetto del mese è finito", vm.stato.value.erroreRiassunto)
    }

    @Test
    fun cambiaFotoSenzaAltreLoDiceCalmo() {
        val vm = schemaVm()
        aspetta { vm.stato.value.schema != null }
        server.rispondi("POST /api/schemi/3/immagini/1/cambia", """{"errore": "Finite", "codice": "non_trovato"}""", 404)
        vm.cambiaFoto(1)
        aspetta { vm.stato.value.avviso != null }
        assertEquals("Non ne ho trovate altre", vm.stato.value.avviso)
    }

    @Test
    fun iRiassuntiFormaPerForma() {
        val pronto = """{"id": 4, "schema_id": 3, "materia_id": 1, "forma": "misto", "titolo": "Augusto", "stato": "pronto", "parole": 480, "contenuto": {"parti": [{"titolo": "Uno", "paragrafi": ["Testo"], "punti": []}]}}"""
        server.rispondi("GET /api/schemi/3/riassunti", "[$pronto]")
        server.rispondi("GET /api/riassunti/4", pronto)
        server.rispondi("GET /api/schemi/3", schemaPronto)
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada"}]""")
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.2.0", "modalita_prova": false}""")
        val vm = RiassuntoViewModel(sorgente, 3, "misto").apply { carica() }
        aspetta { vm.stato.value.corrente != null }
        assertEquals(FormaRiassunto.MISTO, vm.stato.value.forma)
        assertEquals(1, vm.stato.value.corrente?.contenuto?.parti?.size)

        // Una forma che non c'è ancora: niente, finché non si tocca Scrivilo.
        vm.scegli(FormaRiassunto.PUNTI)
        assertNull(vm.stato.value.corrente)
        server.rispondi("POST /api/schemi/3/riassunti", """{"id": 8, "schema_id": 3, "materia_id": 1, "forma": "punti", "stato": "in_coda"}""", 201)
        vm.scrivi()
        aspetta { server.richiesteA("POST /api/schemi/3/riassunti").isNotEmpty() && deposito.seguiti().riassunti.isNotEmpty() }
        assertEquals("punti", corpo("POST /api/schemi/3/riassunti")["forma"]!!.jsonPrimitive.content)
    }

    @Test
    fun rifareVuolDireCancellareERiscrivere() {
        val pronto = """{"id": 4, "schema_id": 3, "materia_id": 1, "forma": "misto", "stato": "pronto", "contenuto": {"parti": []}}"""
        server.rispondi("GET /api/schemi/3/riassunti", "[$pronto]")
        server.rispondi("GET /api/riassunti/4", pronto)
        server.rispondi("DELETE /api/riassunti/4", """{"ok": true}""")
        server.rispondi("POST /api/schemi/3/riassunti", """{"id": 9, "schema_id": 3, "materia_id": 1, "forma": "misto", "stato": "in_coda"}""", 201)
        val vm = RiassuntoViewModel(sorgente, 3, "misto").apply { carica() }
        aspetta { vm.stato.value.corrente != null }
        vm.rifai()
        aspetta { server.richiesteA("POST /api/schemi/3/riassunti").isNotEmpty() }
        assertEquals(1, server.richiesteA("DELETE /api/riassunti/4").size)
        val ordine = server.richieste.map { "${it.method} ${it.path}" }
        assertTrue(ordine.indexOf("DELETE /api/riassunti/4") < ordine.indexOf("POST /api/schemi/3/riassunti"))
    }

    // --- Ripasso lampo --------------------------------------------------------------------------

    private val dueCarte = """{"carte": [
 {"id": 1, "materia_id": 1, "materia": "Storia", "colore": "giada", "domanda": "Chi?", "risposta": "Augusto.", "fonti": [], "scatola": 1},
 {"id": 2, "materia_id": 1, "materia": "Storia", "colore": "giada", "domanda": "Quando?", "risposta": "14 d.C.", "fonti": [], "scatola": 1}
 ], "da_fare": 2, "domani": 3}"""

    @Test
    fun ilRipassoMandaGliEsitiEFinisce() {
        var ora = 1_000L
        server.rispondi("GET /api/lampo?limite=12", dueCarte)
        server.rispondi("POST /api/lampo/1", """{"scatola": 2, "prossima": "2026-11-13", "da_fare": 1}""")
        server.rispondi("POST /api/lampo/2", """{"scatola": 2, "prossima": "2026-11-13", "da_fare": 0}""")
        val vm = LampoViewModel(sorgente, null) { ora }.apply { carica() }
        aspetta { vm.stato.value.sessione != null }

        vm.mostra()
        assertFalse("doppio tocco su Mostra", vm.esito("sapevo"))
        ora += 400
        assertTrue(vm.esito("sapevo"))
        // Il primo esito resta in sospeso: Annulla non deve dire niente al server.
        assertTrue(server.richiesteA("POST /api/lampo/1").isEmpty())

        vm.mostra()
        ora += 400
        assertTrue(vm.esito("quasi"))
        aspetta { server.richiesteA("POST /api/lampo/2").isNotEmpty() && deposito.codaLampo().isEmpty() }
        assertEquals(1, server.richiesteA("POST /api/lampo/1").size)
        assertTrue(vm.stato.value.sessione!!.finita)
        assertEquals(3, vm.stato.value.domani)
    }

    @Test
    fun ilRipassoSenzaReteUsaLeSchedeSalvateETieneGliEsiti() {
        var ora = 1_000L
        server.rispondi("GET /api/lampo?limite=12", dueCarte)
        // La prima volta le schede arrivano e restano sul telefono.
        val prima = LampoViewModel(sorgente, null) { ora }.apply { carica() }
        aspetta { prima.stato.value.sessione != null }
        server.spegni()

        val vm = LampoViewModel(sorgente, null) { ora }.apply { carica() }
        aspetta { vm.stato.value.sessione != null }
        assertEquals(2, vm.stato.value.sessione!!.carte.size)
        vm.mostra()
        ora += 400
        assertTrue(vm.esito("quasi"))
        vm.esci()
        aspetta { deposito.codaLampo().size == 1 }
        assertEquals("quasi", deposito.codaLampo().single().esito)
    }

    @Test
    fun senzaCopiaSenzaReteLoDice() {
        server.spegni()
        val vm = LampoViewModel(sorgente, null).apply { carica() }
        // Su Windows una porta chiusa risponde dopo qualche secondo.
        aspetta(massimoMs = 30_000) { vm.stato.value.caricato }
        assertNull(vm.stato.value.sessione)
        assertTrue(vm.stato.value.senzaCopia)
    }

    // --- Verifica simulata ----------------------------------------------------------------------

    private val inCorso = """{"id": 9, "materia_id": 1, "argomento": "Augusto", "titolo": "Verifica", "minuti": 50, "stato": "in_corso",
 "domande": [{"n": 1, "tipo": "aperta", "testo": "Perché?", "punti": 3}, {"n": 2, "tipo": "aperta", "testo": "Chi?", "punti": 7}], "risposte": {"2": "dal server"}}"""

    private fun simulataVm(attesa: Long = 30): SimulataViewModel {
        server.rispondi("GET /api/simulate/9", inCorso)
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada"}]""")
        return SimulataViewModel(sorgente, 9, attesa).apply { carica() }
    }

    @Test
    fun ogniRispostaVaSubitoSulTelefonoEPoiAlServerUnaVolta() {
        server.rispondi("PATCH /api/simulate/9", inCorso)
        val vm = simulataVm(attesa = 60)
        aspetta { vm.stato.value.simulata != null }
        assertEquals("dal server", vm.stato.value.risposte["2"])

        vm.scrivi(1, "P")
        vm.scrivi(1, "Pe")
        vm.scrivi(1, "Per")
        assertEquals("Per", deposito.copia(9)?.risposte?.get("1"))
        assertTrue(deposito.copia(9)?.daInviare == true)
        assertEquals(StatoSalva.SALVO, vm.stato.value.salva)

        aspetta { vm.stato.value.salva == StatoSalva.SALVATA }
        assertEquals(1, server.richiesteA("PATCH /api/simulate/9").size)
        val b = corpo("PATCH /api/simulate/9")
        // Si manda sempre tutta la mappa.
        assertEquals("Per", b["risposte"]!!.jsonObject["1"]!!.jsonPrimitive.content)
        assertEquals("dal server", b["risposte"]!!.jsonObject["2"]!!.jsonPrimitive.content)
        assertEquals("true", b["iniziata"]!!.jsonPrimitive.content)
        assertTrue(deposito.copia(9)?.daInviare == false)
    }

    @Test
    fun senzaReteLaRispostaRestaSulTelefono() {
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        server.spegni()
        vm.scrivi(1, "scritta senza rete")
        aspetta { vm.stato.value.salva == StatoSalva.SUL_TELEFONO }
        assertTrue(deposito.copia(9)?.daInviare == true)
        assertEquals(1, deposito.copieDaInviare().size)
    }

    @Test
    fun unRifiutoDelServerSiVede() {
        server.rispondi("PATCH /api/simulate/9", """{"errore": "Già consegnata", "codice": "non_valido"}""", 409)
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        vm.scrivi(1, "x")
        aspetta { vm.stato.value.salva == StatoSalva.ERRORE }
    }

    @Test
    fun riaprendoLeRisposteDelTelefonoVincono() {
        deposito.scriviCopia(9, CopiaSimulata(mapOf("2" to "dal telefono"), secondi = 600, daInviare = true))
        server.rispondi("PATCH /api/simulate/9", inCorso)
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        assertEquals("dal telefono", vm.stato.value.risposte["2"])
        assertEquals(600, vm.stato.value.secondi)
    }

    @Test
    fun ilTempoContaEAvvisaUnaVolta() {
        deposito.scriviCopia(9, CopiaSimulata(secondi = 50 * 60 - 301))
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        assertFalse(vm.stato.value.avvisoCinque)
        vm.passaUnSecondo()
        vm.passaUnSecondo()
        assertTrue(vm.stato.value.avvisoCinque)
        assertTrue(deposito.copia(9)?.avvisato5 == true)
        repeat(5) { vm.passaUnSecondo() }
        assertEquals(50 * 60 - 301 + 7, vm.stato.value.secondi)
    }

    @Test
    fun colTempoNascostoNienteAvvisoDeiCinqueMinuti() {
        deposito.scriviCopia(9, CopiaSimulata(secondi = 50 * 60 - 301))
        deposito.tempoNascosto = true
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        vm.passaUnSecondo()
        vm.passaUnSecondo()
        assertFalse(vm.stato.value.avvisoCinque)
    }

    @Test
    fun ilTempoFinitoSiDiceAncheNascostoENonFerma() {
        deposito.scriviCopia(9, CopiaSimulata(secondi = 50 * 60 - 1))
        deposito.tempoNascosto = true
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        vm.passaUnSecondo()
        assertTrue(vm.stato.value.avvisoFine)
        vm.scrivi(1, "continuo")
        assertEquals("continuo", vm.stato.value.risposte["1"])
        vm.chiudiAvvisoFine()
        assertFalse(vm.stato.value.avvisoFine)
        assertTrue(deposito.copia(9)?.fineChiuso == true)
    }

    @Test
    fun laConsegnaMandaPrimaLeRisposte() {
        server.rispondi("PATCH /api/simulate/9", inCorso)
        server.rispondi("POST /api/simulate/9/consegna", """{"id": 9, "materia_id": 1, "stato": "in_correzione", "minuti": 50}""")
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        vm.scrivi(1, "x")
        var fatta = false
        vm.consegna { fatta = true }
        aspetta { fatta }
        val ordine = server.richieste.map { "${it.method} ${it.path}" }
        assertTrue(ordine.indexOf("PATCH /api/simulate/9") < ordine.indexOf("POST /api/simulate/9/consegna"))
        assertEquals("in_correzione", vm.stato.value.simulata?.stato)
        assertEquals(setOf(9L), deposito.seguiti().simulate)
    }

    @Test
    fun unaConsegnaNonRiuscitaTieneLeRisposte() {
        server.rispondi("PATCH /api/simulate/9", inCorso)
        server.rispondi("POST /api/simulate/9/consegna", MockResponse().setResponseCode(500).setBody("""{"errore": "No"}"""))
        val vm = simulataVm()
        aspetta { vm.stato.value.simulata != null }
        vm.scrivi(1, "resta")
        var fatta = false
        vm.consegna { fatta = true }
        aspetta { vm.stato.value.erroreConsegna != null }
        assertFalse(fatta)
        assertEquals("resta", vm.stato.value.risposte["1"])
    }

    // --- Dove si entra --------------------------------------------------------------------------

    @Test
    fun laMateriaCaricaSchemiESimulateESegueISegmenti() {
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada", "conteggi": {"lampo": 2, "simulate": 1, "schemi": 1}}]""")
        server.rispondi("GET /api/schemi?materia_id=1", """[{"id": 3, "materia_id": 1, "titolo": "Augusto", "stato": "in_preparazione"}]""")
        server.rispondi("GET /api/simulate?materia_id=1", """[{"id": 9, "materia_id": 1, "titolo": "Verifica", "stato": "in_corso"}]""")
        server.rispondi("GET /api/lampo?materia_id=1&limite=12", dueCarte)
        val vm = MateriaViewModel(sorgente, 1, { oggi }).apply { carica() }
        aspetta { vm.stato.value.schemi.isNotEmpty() && vm.stato.value.lampoDisponibile }
        assertEquals(1, vm.stato.value.simulate.size)
        assertTrue("uno schema in preparazione fa ricontrollare", vm.inAttesa)
        assertEquals(0, vm.stato.value.segmento)
        vm.segmento(2)
        assertEquals(2, vm.stato.value.segmento)
        vm.segmento(9)
        assertEquals(2, vm.stato.value.segmento)
    }

    @Test
    fun oggiTrovaLaVerificaLasciataAMeta() {
        server.rispondi("GET /api/oggi", """{"oggi": "2026-11-10", "lampo": {"da_fare": 2, "minuti": 1}}""")
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada", "conteggi": {"simulate": 1}}, {"id": 2, "nome": "Inglese", "prof": "Bianchi", "colore": "blu"}]""")
        server.rispondi("GET /api/simulate?materia_id=1", """[{"id": 9, "materia_id": 1, "titolo": "Verifica", "stato": "in_corso"}]""")
        server.rispondi("GET /api/lampo?limite=12", dueCarte)
        val vm = OggiViewModel(sorgente).apply { carica() }
        aspetta { vm.stato.value.primaLettura }
        assertEquals(9L, vm.stato.value.simulataInCorso?.id)
        assertTrue(vm.stato.value.lampoSalvato)
        // Solo le materie che hanno simulate.
        assertTrue(server.richiesteA("GET /api/simulate?materia_id=2").isEmpty())
    }

    @Test
    fun ascoltaQuestaParteRiempieNuovaLezione() {
        server.rispondi("GET /api/materie", """[{"id": 1, "nome": "Storia", "prof": "Rossi", "colore": "giada"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi("GET /api/materie/1/materiale", """[
 {"id": 5, "materia_id": 1, "tipo": "appunti", "titolo": "Uno", "data": "2026-10-12", "origine": "scritto", "caratteri": 100, "stato": "pronto"},
 {"id": 6, "materia_id": 1, "tipo": "appunti", "titolo": "Due", "data": "2026-10-13", "origine": "scritto", "caratteri": 100, "stato": "pronto"}]""")
        val vm = NuovaLezioneViewModel(sorgente, 1, null, { oggi }, 0, argomentoIniziale = "Il Discobolo", materialiIniziali = listOf(6L)).apply { carica() }
        aspetta { vm.stato.value.pronto }
        assertEquals("Il Discobolo", vm.stato.value.argomento)
        assertEquals(setOf(6L), vm.stato.value.scelti)
    }

    // --- Modalità esempio -----------------------------------------------------------------------

    @Test
    fun laVisitaGiraSenzaToccareLaRete() {
        val esempio = SorgenteEsempio(oggi)
        val schema = SchemaViewModel(esempio, SorgenteEsempio.SCHEMA_STORIA).apply { carica() }
        aspetta { schema.stato.value.schema != null }
        val sc = schema.stato.value.schema!!
        assertEquals(setOf("paragrafo", "definizione", "passaggi", "prof", "tabella", "immagine", "punti"), sc.contenuto!!.sezioni.flatMap { it.blocchi }.map { it.tipo }.toSet())
        assertEquals(1, sc.contenuto!!.copertura?.mancanti?.size)
        assertEquals("misto", sc.riassunti?.fatti?.single()?.forma)

        val capito = NonHoCapitoViewModel(esempio)
        capito.apriDaSchema(1, sc.id, 1, "Princeps")
        capito.invia()
        aspetta { capito.stato.value.risposte.isNotEmpty() }
        assertTrue(capito.stato.value.ultima!!.fonti.isNotEmpty())

        val riassunto = RiassuntoViewModel(esempio, sc.id, "misto").apply { carica() }
        aspetta { riassunto.stato.value.corrente != null }
        riassunto.scegli(FormaRiassunto.PUNTI)
        riassunto.scrivi()
        aspetta { riassunto.stato.value.corrente?.pronto == true }

        val lampo = LampoViewModel(esempio, null).apply { carica() }
        aspetta { lampo.stato.value.sessione != null }
        assertEquals(4, lampo.stato.value.sessione!!.carte.size)

        val inCorso = SimulataViewModel(esempio, SorgenteEsempio.SIMULATA_IN_CORSO, 0).apply { carica() }
        aspetta { inCorso.stato.value.simulata != null }
        assertTrue(inCorso.stato.value.simulata!!.inCorso)
        inCorso.scrivi(2, "Una risposta")
        aspetta { inCorso.stato.value.salva == StatoSalva.SALVATA }

        val corretta = SimulataViewModel(esempio, SorgenteEsempio.SIMULATA_CORRETTA).apply { carica() }
        aspetta { corretta.stato.value.simulata != null }
        assertNotNull(corretta.stato.value.simulata!!.esito?.votoStimato)
        // La domanda non corretta non conta nei punti.
        assertEquals(10.0, corretta.stato.value.simulata!!.esito!!.puntiTotali, 0.0)

        val nuovo = NuovoStudioViewModel(esempio, GenereStudio.SCHEMA, 1, 3, null, { oggi }, 0, 0).apply { carica() }
        aspetta { nuovo.stato.value.stima != null }
        assertEquals("Prepara lo schema · 0,08 €", nuovo.stato.value.etichettaPulsante)

        val oggiVm = OggiViewModel(esempio).apply { carica() }
        aspetta { oggiVm.stato.value.primaLettura }
        assertEquals(4, oggiVm.stato.value.oggi?.lampo?.daFare)
        assertEquals(SorgenteEsempio.SIMULATA_IN_CORSO, oggiVm.stato.value.simulataInCorso?.id)

        val foto = runBlocking { esempio.immagineSchema(sc.id, 1) }
        assertTrue(foto is eu.stgm.lectio.rete.Esito.Ok)
        assertTrue(server.richieste.isEmpty())
    }
}
