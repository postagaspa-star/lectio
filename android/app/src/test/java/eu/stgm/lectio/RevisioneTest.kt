package eu.stgm.lectio

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import eu.stgm.lectio.dati.Ascolto
import eu.stgm.lectio.dati.ArchivioAudio
import eu.stgm.lectio.dati.CacheMemoria
import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.DepositoConfigurazione
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MateriaBreve
import eu.stgm.lectio.dati.MemoriaNovita
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.dati.SchedaProf
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.dati.deveSvuotare
import eu.stgm.lectio.dati.fuoriCasaScelto
import eu.stgm.lectio.lavori.Avvisi
import eu.stgm.lectio.lavori.GiroPreparazione
import eu.stgm.lectio.lavori.novitaDaMostrare
import eu.stgm.lectio.lettore.AzioneSulLettore
import eu.stgm.lectio.lettore.Lettore
import eu.stgm.lectio.lettore.LettoreEsempio
import eu.stgm.lectio.lettore.StatoLettore
import eu.stgm.lectio.lettore.azioneSuLezioneInAscolto
import eu.stgm.lectio.logica.CodaRisposte
import eu.stgm.lectio.logica.Comando
import eu.stgm.lectio.logica.ControlloreDomande
import eu.stgm.lectio.logica.EsitoRisposta
import eu.stgm.lectio.logica.FaseDomanda
import eu.stgm.lectio.logica.MemoriaPosizioniInMemoria
import eu.stgm.lectio.logica.MemoriaRisposteInMemoria
import eu.stgm.lectio.logica.ModoDomande
import eu.stgm.lectio.logica.Preparazione
import eu.stgm.lectio.logica.SincronizzatorePosizioni
import eu.stgm.lectio.logica.StatoFermate
import eu.stgm.lectio.logica.StatoPasso
import eu.stgm.lectio.logica.GenereFile
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.rete.SceltaIndirizzo
import eu.stgm.lectio.ui.abbinamento.AbbinamentoViewModel
import eu.stgm.lectio.ui.altre.modificaDaiCampi
import eu.stgm.lectio.ui.altre.paragrafiLeggibili
import eu.stgm.lectio.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.lectio.ui.lettore.LettoreViewModel
import eu.stgm.lectio.ui.lettore.OsservatoreVisibilita
import eu.stgm.lectio.ui.lettore.playDelMiniLettoreApre
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.FileScelto
import eu.stgm.lectio.ui.materiale.PassoAggiunta
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materiale.dataIniziale
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.oggi.OggiViewModel
import eu.stgm.lectio.ui.oggi.fraseAvvisoTetto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Un lettore finto che registra i caricamenti (per i test del ViewModel del lettore). */
class LettoreFinto(iniziale: StatoLettore = StatoLettore()) : Lettore {
    val _stato = MutableStateFlow(iniziale)
    override val stato: StateFlow<StatoLettore> = _stato
    data class Carico(val id: Long, val da: Double, val avvia: Boolean, val allaDomanda: Long?)
    val caricate = mutableListOf<Carico>()
    val visibilita = mutableListOf<Boolean>()
    override fun carica(lezione: Lezione, materia: MateriaBreve?, daSecondi: Double, avvia: Boolean, allaDomanda: Long?) {
        caricate += Carico(lezione.id, daSecondi, avvia, allaDomanda)
        _stato.value = StatoLettore(lezione, materia, daSecondi, lezione.durataSecondi)
    }
    override fun playPausa() = Unit
    override fun vaiA(secondi: Double) = Unit
    override fun sposta(deltaSecondi: Double) = Unit
    override fun cambiaVelocita(v: Float) = Unit
    override fun rispondi(testo: String) = Unit
    override fun salta() = Unit
    override fun cambiaSaltabili(si: Boolean) = Unit
    override fun sentiRisposta() = Unit
    override fun ripetiDomanda() = Unit
    override fun avanti() = Unit
    override fun ferma() = Unit
    override suspend fun liberaFile(lezioneId: Long, elimina: Boolean) = Unit
    override fun visibile(si: Boolean) {
        visibilita += si
    }
}

/**
 * Un test per ogni difetto trovato dal revisore (punti 1-24 dove si può provare
 * sul PC). I nomi dicono il punto.
 */
class RevisioneTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val esempio = SorgenteEsempio(LocalDate.of(2026, 10, 28))

    @After
    fun chiudi() = server.close()

    private fun lezioneStoria() = esempio.lezioneCompleta()!!

    // --- 1. Errore di rete: aprire di nuovo ricarica ---------------------------------------------

    @Test
    fun p01_lettoreInErroreSiRicarica() {
        val lettore = LettoreFinto(StatoLettore(lezione = lezioneStoria(), errore = "Non riesco a far partire l'audio."))
        val vm = LettoreViewModel(esempio, lettore)
        vm.apri(SorgenteEsempio.LEZIONE_STORIA)
        aspetta { lettore.caricate.isNotEmpty() }
        assertEquals(SorgenteEsempio.LEZIONE_STORIA, lettore.caricate.single().id)
    }

    @Test
    fun p01_senzaErroreNonRicarica() {
        val lettore = LettoreFinto(StatoLettore(lezione = lezioneStoria()))
        LettoreViewModel(esempio, lettore).apri(SorgenteEsempio.LEZIONE_STORIA)
        Thread.sleep(50)
        assertTrue(lettore.caricate.isEmpty())
    }

    // --- 2. Ripresa da una lezione ferma alla domanda --------------------------------------------

    @Test
    fun p02_riprendereDentroUnaDomandaTornaAllaDomanda() {
        val d = domanda(id = 32, n = 2, inizio = 561.2, fineDomanda = 567.8, inizioRisposta = 570.3, fine = 589.0)
        val c = ControlloreDomande(listOf(domanda(), d))
        assertEquals(32L, c.domandaDaRiprendere(567.9)?.id) // fermo nel silenzio dopo la domanda
        assertEquals(32L, c.domandaDaRiprendere(562.0)?.id) // a metà domanda
        assertNull(c.domandaDaRiprendere(575.0)) // già nella risposta: si prosegue
        assertNull(c.domandaDaRiprendere(400.0))
        assertNull(ControlloreDomande(listOf(d), attivo = false).domandaDaRiprendere(567.9))
    }

    @Test
    fun p02_ilLettoreRiprendeFermoAllaDomanda() {
        val l = LettoreEsempio()
        l.carica(lezioneStoria(), null, 567.9, avvia = true)
        assertEquals(FaseDomanda.ALLA_DOMANDA, l.stato.value.fermate.fase)
        assertEquals(32L, l.stato.value.fermate.domanda?.id)
        assertFalse(l.stato.value.suona)
        l.ferma()
    }

    // --- 3. Pagina ricreata: niente da capo, niente avvio da solo ---------------------------------

    @Test
    fun p03_paginaRicreataNonRiparteENonSiAvvia() {
        val salvato = SavedStateHandle()
        val lettore = LettoreFinto()
        val vm = LettoreViewModel(esempio, lettore, salvato = salvato)
        vm.apriPagina(SorgenteEsempio.LEZIONE_STORIA, allaDomanda = 33, idPagina = "a")
        aspetta { lettore.caricate.size == 1 }
        assertEquals(LettoreFinto.Carico(SorgenteEsempio.LEZIONE_STORIA, 462.0, false, 33), lettore.caricate[0].copy(avvia = false))
        // Rotazione: stessa pagina, stessa lezione caricata → niente.
        vm.apriPagina(SorgenteEsempio.LEZIONE_STORIA, allaDomanda = 33, idPagina = "a")
        Thread.sleep(50)
        assertEquals(1, lettore.caricate.size)
        // Android chiude il processo: ViewModel nuovo, lettore vuoto, stato salvato uguale.
        val lettore2 = LettoreFinto()
        LettoreViewModel(esempio, lettore2, salvato = salvato).apriPagina(SorgenteEsempio.LEZIONE_STORIA, 33, "a")
        aspetta { lettore2.caricate.size == 1 }
        assertFalse("una lezione ripristinata non parte da sola", lettore2.caricate[0].avvia)
        assertNull("e non torna alla domanda vecchia", lettore2.caricate[0].allaDomanda)
        // Una pagina aperta di nuovo (id diverso) è un'apertura vera.
        val lettore3 = LettoreFinto()
        LettoreViewModel(esempio, lettore3, salvato = salvato).apriPagina(SorgenteEsempio.LEZIONE_STORIA, null, "b")
        aspetta { lettore3.caricate.size == 1 }
        assertTrue(lettore3.caricate[0].avvia)
    }

    // --- 4. Senza rete si riprende dalla posizione appena salvata ---------------------------------

    @Test
    fun p04_laCopiaLocalePrendeLaPosizioneSalvata() = runBlocking {
        val cartella = File.createTempFile("audio", "").apply { delete(); mkdirs() }
        val audio = ArchivioAudio(cartella)
        val dettaglio = Esempi.DETTAGLIO
        server.rispondi("GET /api/lezioni/12", dettaglio)
        server.rispondi("PUT /api/lezioni/12/ascolto", "{}")
        val client = server.client()
        val sorgente = SorgenteServer(client, CacheMemoria(), audio)
        assertTrue(sorgente.lezione(12).dato != null)
        audio.salvaDettaglio(sorgente.lezione(12).dato!!)
        val sinc = SincronizzatorePosizioni(MemoriaPosizioniInMemoria(), client::salvaAscolto, dopoInvio = sorgente::aggiornaAscoltoLocale)
        assertTrue(sinc.registra(12, 900.0, false))
        server.spegni()
        val offline = sorgente.lezione(12)
        assertTrue(offline.senzaRete)
        assertEquals(900.0, offline.dato!!.ascolto!!.posizioneS, 0.0)
        assertEquals(900.0, audio.dettaglio(12)!!.ascolto!!.posizioneS, 0.0)
        cartella.deleteRecursively()
        Unit
    }

    // --- 5. Novità: niente valanga al primo giro, e lette all'apertura ----------------------------

    private class Memoria(var c: Long = 0) : MemoriaNovita {
        val a = mutableSetOf<Long>()
        override suspend fun cursore() = c
        override suspend fun ricordaCursore(id: Long) { if (id > c) c = id }
        override suspend fun avvisate() = a.toSet()
        override suspend fun segnaAvvisata(lezioneId: Long) { a += lezioneId }
    }

    private val vecchie = listOf(
        Novita(1, "lezione_pronta", "La lezione di Storia è pronta", 3),
        Novita(2, "lezione_pronta", "La lezione di Inglese è pronta", 4),
        Novita(7, "scheda_aggiornata", "Ho aggiornato la scheda del prof Rossi"),
    )

    @Test
    fun p05_primoGiroFissaSoloIlCursore() = runBlocking {
        val avvisi = mutableListOf<String>()
        val memoria = Memoria(0)
        val giro = GiroPreparazione({ Esito.Ok(emptyList()) }, { Esito.Ok(vecchie) }, { "Storia" }, memoria, object : Avvisi {
            override fun avanzamento(titolo: String, frase: String, frazione: Float?, lezioneId: Long) = Unit
            override fun pronta(testo: String, lezioneId: Long) { avvisi += testo }
            override fun nonRiuscita(testo: String, lezioneId: Long) { avvisi += testo }
            override fun altro(id: Long, testo: String) { avvisi += testo }
        })
        giro.giro()
        assertTrue("nessuna notifica (e nessuno scaricamento) per le novità vecchie: $avvisi", avvisi.isEmpty())
        assertEquals(7L, memoria.c)
    }

    @Test
    fun p05_novitaAllApertura() = runBlocking {
        val memoria = Memoria(0)
        assertTrue(novitaDaMostrare({ Esito.Ok(vecchie) }, memoria).isEmpty())
        assertEquals(7L, memoria.c)
        val nuove = listOf(Novita(8, "lezione_pronta", "La lezione di Storia è pronta", 12), Novita(9, "tetto_vicino", "Hai speso l'80% del tetto"))
        val da = novitaDaMostrare({ dopo -> Esito.Ok(nuove.filter { it.id > dopo }) }, memoria)
        assertEquals(listOf(8L, 9L), da.map { it.id })
        assertEquals(9L, memoria.c)
        assertTrue(12L in memoria.a)
        assertTrue(novitaDaMostrare({ dopo -> Esito.Ok(nuove.filter { it.id > dopo }) }, memoria).isEmpty())
    }

    // --- 6. Token in http solo al server riconosciuto ----------------------------------------------

    private fun clientVerificato(serverId: String?, fuori: String? = null, scollegato: (() -> Unit)? = null) =
        LectioClient({ Configurazione(server.url, "token-segreto", 3, serverId, fuori) }, { scollegato?.invoke() }, verificaAncheLocale = true)

    @Test
    fun p06_conLoStessoServerIlTokenParte() = runBlocking {
        server.rispondi("GET /api/salute", """{"ok":true,"versione":"1.0.0","server_id":"casa-123"}""")
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2}""")
        val e = clientVerificato("casa-123").impostazioni()
        assertTrue(e is Esito.Ok)
        val col = server.richiesteA("GET /api/impostazioni").single()
        assertEquals("Bearer token-segreto", col.getHeader("Authorization"))
        assertNull("la verifica si fa senza token", server.richiesteA("GET /api/salute").first().getHeader("Authorization"))
    }

    @Test
    fun p06_unAltroApparecchioNonRiceveIlToken() = runBlocking {
        server.rispondi("GET /api/salute", """{"ok":true,"versione":"1.0.0","server_id":"impostore"}""")
        server.rispondi("GET /api/impostazioni", """{"errore":"no","codice":"non_autorizzato"}""", 401)
        var scollegato = false
        val e = clientVerificato("casa-123") { scollegato = true }.impostazioni()
        assertTrue("come senza rete: $e", e is Esito.Irraggiungibile)
        assertFalse("mai un 401, mai l'abbinamento cancellato", scollegato)
        assertTrue(server.richieste.none { it.getHeader("Authorization") != null })
    }

    @Test
    fun p06_unServerSenzaIdentitaNonRiceveIlToken() = runBlocking {
        server.rispondi("GET /api/salute", """{"ok":true,"versione":"1.0.0"}""")
        assertTrue(clientVerificato("casa-123").oggi() is Esito.Irraggiungibile)
        assertTrue(clientVerificato(null).oggi() is Esito.Irraggiungibile)
        assertTrue(server.richieste.none { it.getHeader("Authorization") != null })
    }

    @Test
    fun p06_fuoriCasaSiUsaLHttpsDiTailscale() = runBlocking {
        val richiede = { b: String -> b.startsWith("http://") }
        assertEquals("https://lectio.esempio.ts.net",
            SceltaIndirizzo.scegli("http://192.168.0.20:8500", "https://lectio.esempio.ts.net", richiede) { false })
        assertEquals("http://192.168.0.20:8500",
            SceltaIndirizzo.scegli("http://192.168.0.20:8500", "https://lectio.esempio.ts.net", richiede) { true })
        assertNull(SceltaIndirizzo.scegli("http://192.168.0.20:8500", "http://192.168.1.60", richiede) { false })
        assertEquals("https://lectio.esempio.ts.net",
            SceltaIndirizzo.scegli("https://lectio.esempio.ts.net", null, richiede) { error("in https non si verifica") })
    }

    @Test
    fun p06_ricollegarsiAlloStessoServerNonCancellaLeLezioni() {
        assertFalse(deveSvuotare("http://192.168.0.20:8500", "casa-123", "https://lectio.esempio.ts.net", "casa-123"))
        assertTrue(deveSvuotare("http://192.168.0.20:8500", "casa-123", "http://192.168.0.20:8500", "altro"))
        assertFalse(deveSvuotare("https://a.ts.net", null, "https://a.ts.net/", null))
        assertTrue(deveSvuotare("https://a.ts.net", null, "https://b.ts.net", null))
        assertFalse(deveSvuotare(null, null, "https://a.ts.net", "x"))
    }

    @Test
    fun p06_lAbbinamentoSalvaIdentitaEIndirizzoDaFuori() {
        var salvata: Configurazione? = null
        var prima: Pair<String, String?>? = null
        val deposito = object : DepositoConfigurazione {
            override val flusso: Flow<Configurazione?> = MutableStateFlow(null)
            override suspend fun leggi(): Configurazione? = salvata
            override suspend fun salva(configurazione: Configurazione) { salvata = configurazione }
            override suspend fun dimentica() { salvata = null }
        }
        val vm = AbbinamentoViewModel(
            deposito,
            abbina = { _, _, _ -> Esito.Ok(eu.stgm.lectio.dati.Abbinamento("t".repeat(64), 1, indirizzoFuoriCasa = "https://lectio.esempio.ts.net/")) },
            prima = { s, id -> prima = s to id },
            nome = { "Telefono di prova" },
            serverId = { "casa-123" },
        )
        vm.cambiaIndirizzo("192.168.0.20:8500")
        vm.cambiaCodice("48213377")
        vm.collega()
        aspetta { salvata != null }
        assertEquals("casa-123", salvata!!.serverId)
        assertEquals("l'indirizzo da fuori casa arriva dal server", "https://lectio.esempio.ts.net", salvata!!.fuoriCasa)
        assertEquals("http://192.168.0.20:8500" to "casa-123", prima)
    }

    // --- Repo pubblico: nessun indirizzo di rete nel codice ---------------------------------------

    @Test
    fun pubblico_ilCampoDellAbbinamentoPartevuoto() {
        assertEquals("", eu.stgm.lectio.ui.abbinamento.StatoAbbinamento().indirizzo)
        assertEquals("http://192.168.x.x:8500", eu.stgm.lectio.rete.IndirizzoServer.ESEMPIO)
        assertEquals("Telefono (moto g84)", eu.stgm.lectio.ui.abbinamento.nomeTelefono("moto g84"))
        assertEquals("Telefono", eu.stgm.lectio.ui.abbinamento.nomeTelefono(null))
    }

    @Test
    fun pubblico_senzaIndirizzoDalServerNonCeNessunFuoriCasa() {
        var salvata: Configurazione? = null
        val deposito = object : DepositoConfigurazione {
            override val flusso: Flow<Configurazione?> = MutableStateFlow(null)
            override suspend fun leggi(): Configurazione? = salvata
            override suspend fun salva(configurazione: Configurazione) { salvata = configurazione }
            override suspend fun dimentica() { salvata = null }
        }
        val vm = AbbinamentoViewModel(deposito, abbina = { _, _, _ -> Esito.Ok(eu.stgm.lectio.dati.Abbinamento("t".repeat(64), 1, "casa-123")) }, nome = { "Telefono" })
        vm.cambiaIndirizzo("192.168.0.20:8500")
        vm.cambiaCodice("48213377")
        vm.collega()
        aspetta { salvata != null }
        assertNull(salvata!!.fuoriCasa)
    }

    @Test
    fun pubblico_scrittoAManoVinceSulServer() {
        // Non toccato da Andrea: segue il server, anche quando cambia o sparisce.
        assertEquals("https://lectio.esempio.ts.net", fuoriCasaScelto(null, false, "https://lectio.esempio.ts.net/"))
        assertEquals("https://nuovo.esempio.ts.net", fuoriCasaScelto("https://lectio.esempio.ts.net", false, "https://nuovo.esempio.ts.net"))
        assertNull(fuoriCasaScelto("https://lectio.esempio.ts.net", false, null))
        // Solo https: un http dal server non vale per fuori casa.
        assertNull(fuoriCasaScelto(null, false, "http://192.168.0.20:8500"))
        // Scritto a mano: il server non lo cambia.
        assertEquals("https://mio.esempio.ts.net", fuoriCasaScelto("https://mio.esempio.ts.net", true, "https://lectio.esempio.ts.net"))
        assertEquals("https://mio.esempio.ts.net", fuoriCasaScelto("https://mio.esempio.ts.net", true, null))
    }

    @Test
    fun pubblico_ogniSaluteConsegnaLIndirizzoDaFuoriCasa() = runBlocking {
        server.rispondi("GET /api/salute", """{"ok":true,"versione":"1.0.1","server_id":"casa-123","indirizzo_fuori_casa":"https://lectio.esempio.ts.net"}""")
        val arrivati = mutableListOf<String?>()
        val sorgente = SorgenteServer(server.client(), CacheMemoria(), alSalute = { arrivati += it.indirizzoFuoriCasa })
        assertTrue(sorgente.salute() is Esito.Ok)
        assertEquals(listOf<String?>("https://lectio.esempio.ts.net"), arrivati)
        server.rispondi("GET /api/salute", """{"ok":true,"versione":"1.0.1","server_id":"casa-123","indirizzo_fuori_casa":null}""")
        sorgente.salute()
        assertEquals(listOf("https://lectio.esempio.ts.net", null), arrivati)
    }

    // --- 7. Il foglio Aggiungi sopravvive alla ricreazione -----------------------------------------

    @Test
    fun p07_ilFoglioTornaComEra() {
        val salvato = SavedStateHandle()
        val vm = AggiuntaViewModel(esempio, null, salvato)
        vm.apri(RichiestaAggiunta(null, PassoAggiunta.FILE, file = listOf(FileScelto("content://x/1", "verifica.pdf", "application/pdf", GenereFile.PDF)), testo = "MATERIA: Storia", condivisa = true))
        vm.campi { it.copy(titolo = "Verifica di ottobre", fotoInCorso = "content://eu.stgm.lectio.file/foto/1.jpg") }
        // Android chiude l'app (per esempio mentre la fotocamera è aperta): ViewModel nuovo.
        val ripreso = AggiuntaViewModel(esempio, null, salvato)
        val s = ripreso.stato.value
        assertTrue(s.aperto)
        assertEquals("verifica.pdf", s.richiesta.file.single().nome)
        assertEquals("MATERIA: Storia", s.richiesta.testo)
        assertEquals("Verifica di ottobre", s.campi.titolo)
        assertEquals("content://eu.stgm.lectio.file/foto/1.jpg", s.campi.fotoInCorso)
        ripreso.chiudi()
        assertFalse(AggiuntaViewModel(esempio, null, salvato).stato.value.aperto)
    }

    // --- 8. A schermo spento la risposta non si ferma alla fine ------------------------------------

    @Test
    fun p08_aSchermoSpentoDopoLaRispostaSiProsegue() {
        val c = ControlloreDomande(listOf(domanda()))
        c.salto(285.0)
        c.posizione(285.0, false)
        c.posizione(287.0, true)
        c.riproduci()
        c.posizione(300.9, true, siVede = false)
        assertTrue(c.posizione(301.05, true, siVede = false).isEmpty())
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
        // Con la schermata davanti invece si aspetta "Avanti".
        val v = ControlloreDomande(listOf(domanda()))
        v.salto(285.0); v.posizione(285.0, false); v.posizione(287.0, true); v.riproduci(); v.posizione(300.9, true)
        assertEquals(listOf(Comando.Pausa), v.posizione(301.05, true, siVede = true))
    }

    // --- 10. 502/503 del tunnel: si tiene la posizione ---------------------------------------------

    @Test
    fun p10_503SenzaJsonESenzaRete() = runBlocking {
        server.rispondi("GET /api/oggi", "<html>Service Unavailable</html>", 503)
        assertTrue(server.client().oggi() is Esito.Irraggiungibile)
    }

    @Test
    fun p10_conErroriDelServerLaPosizioneResta() = runBlocking {
        for (stato in listOf(500, 502, 503)) {
            val memoria = MemoriaPosizioniInMemoria()
            val s = SincronizzatorePosizioni(memoria, { _, _ -> if (stato == 500) Esito.Errore("Il server ha avuto un problema", "errore_interno", 500) else Esito.Irraggiungibile })
            assertFalse(s.registra(12, 300.0, false))
            assertEquals("stato $stato", 1, memoria.posizioni.size)
        }
        val memoria = MemoriaPosizioniInMemoria()
        SincronizzatorePosizioni(memoria, { _, _ -> Esito.Errore("Non trovo più questo elemento", "non_trovato", 404) }).registra(12, 1.0, false)
        assertTrue("un 404 non si ritenta", memoria.posizioni.isEmpty())
        val risposte = MemoriaRisposteInMemoria()
        val coda = CodaRisposte(risposte, { _, _, _ -> Esito.Errore("Il server ha avuto un problema", "errore_interno", 500) })
        assertEquals(EsitoRisposta.InCoda, coda.rispondi(12, 32, "Perché sì", false))
        assertEquals(1, risposte.risposte.size)
    }

    // --- 11. Togli/Elimina sulla lezione che suona ---------------------------------------------------

    @Test
    fun p11_primaDiTogliereIlFileIlLettoreLoLascia() {
        assertEquals(AzioneSulLettore.STREAMING, azioneSuLezioneInAscolto(12, 12, elimina = false, dalFile = true))
        assertEquals(AzioneSulLettore.FERMA, azioneSuLezioneInAscolto(12, 12, elimina = true, dalFile = true))
        assertEquals(AzioneSulLettore.FERMA, azioneSuLezioneInAscolto(12, 12, elimina = true, dalFile = false))
        assertEquals(AzioneSulLettore.NIENTE, azioneSuLezioneInAscolto(9, 12, elimina = true, dalFile = true))
        assertEquals(AzioneSulLettore.NIENTE, azioneSuLezioneInAscolto(12, 12, elimina = false, dalFile = false))
    }

    // --- 12. Visibile solo con l'app davanti ----------------------------------------------------------

    @Test
    fun p12_conUnAltraAppDavantiNonEVisibile() {
        val lettore = LettoreFinto()
        val proprietario = object : LifecycleOwner {
            val registro = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registro
        }
        proprietario.registro.addObserver(OsservatoreVisibilita(lettore))
        proprietario.registro.currentState = Lifecycle.State.RESUMED
        proprietario.registro.currentState = Lifecycle.State.CREATED // un'altra app davanti
        assertEquals(listOf(true, false), lettore.visibilita)
    }

    // --- 13. +30 alla domanda --------------------------------------------------------------------------

    @Test
    fun p13_piu30AllaDomandaNonLaSaltaSenzaTraccia() {
        val obbl = ControlloreDomande(listOf(domanda()), modo = ModoDomande.SCRITTE_OBBLIGATORIE)
        obbl.fermaA(domanda())
        val e = obbl.premiAvanti()!!
        assertFalse(e.saltata)
        assertEquals(listOf(Comando.Vai(280.4), Comando.Riproduci), e.comandi) // ripete la domanda
        val salt = ControlloreDomande(listOf(domanda()), modo = ModoDomande.SCRITTE_SALTABILI)
        salt.fermaA(domanda())
        assertTrue("con le saltabili vale Salta e si manda saltata", salt.premiAvanti()!!.saltata)
        assertNull("fuori dalla domanda è un +30 normale", ControlloreDomande(listOf(domanda())).premiAvanti())
    }

    // --- 14. Play del mini-lettore alla domanda ---------------------------------------------------

    @Test
    fun p14_ilPlayDelMiniLettoreAllaDomandaApreIlLettore() {
        val alla = StatoLettore(lezione = lezioneStoria(), fermate = StatoFermate(FaseDomanda.ALLA_DOMANDA, domanda()), risposteScritte = true)
        assertTrue(playDelMiniLettoreApre(alla))
        assertFalse(playDelMiniLettoreApre(alla.copy(risposteScritte = false)))
        assertFalse(playDelMiniLettoreApre(alla.copy(fermate = StatoFermate())))
    }

    // --- 15. "Quanto lunghe le risposte" si svuota -------------------------------------------------

    @Test
    fun p15_unCampoSvuotatoSiMandaVuoto() {
        val m = modificaDaiCampi(SchedaProf(lunghezzaRisposte = "5-8 righe"), "", "", "", "   ", "", "")
        val json = JsonLectio.encodeToString(eu.stgm.lectio.dati.ModificaScheda.serializer(), m)
        assertTrue(json, json.contains("\"lunghezza_risposte\":\"\""))
    }

    // --- 16. La data di un file condiviso non è oggi per forza ------------------------------------

    @Test
    fun p16_perIFileLaDataLaPrendeIlServerDalFile() {
        assertNull(dataIniziale(PassoAggiunta.FILE))
        assertNull(dataIniziale(PassoAggiunta.FOTO))
        assertEquals("2026-10-28", dataIniziale(PassoAggiunta.APPUNTI, LocalDate.of(2026, 10, 28)))
        val f = File.createTempFile("verifica", ".txt").apply { writeText("MATERIA: Storia | DATA: 12/10") }
        server.rispondi("POST /api/materie/1/materiale", """{"id":8,"materia_id":1,"stato":"in_lettura"}""", 201)
        val vm = AggiuntaViewModel(SorgenteServer(server.client(), CacheMemoria()), null)
        vm.apri(RichiestaAggiunta(1, PassoAggiunta.FILE, file = listOf(FileScelto("content://x/1", f.absolutePath, "text/plain", GenereFile.TESTO))))
        vm.inviaFile()
        aspetta { vm.stato.value.fatto }
        val corpo = server.richiesteA("POST /api/materie/1/materiale").single().body.readUtf8()
        assertFalse("niente data: il server la prende dalla riga DATA", corpo.contains("name=\"data\""))
        f.delete()
    }

    // --- 17. "Cambia": la stima coi materiali e il caso vuoto ---------------------------------------

    @Test
    fun p17_stimaConIMaterialiENienteListaVuota() {
        server.rispondi("GET /api/materie", """[{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi("GET /api/materie/1/materiale", """[{"id":23,"materia_id":1,"tipo":"appunti","titolo":"Augusto","stato":"pronto"}]""")
        server.rispondi("GET /api/voci", """[{"id":"mistral:voice_482","nome":"Lorenzo","tipo":"espressiva","disponibile":true}]""")
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2}""")
        server.rispondi("POST /api/lezioni/stima", Esempi.STIMA)
        val vm = NuovaLezioneViewModel(SorgenteServer(server.client(), CacheMemoria()), 1, null)
        vm.carica()
        aspetta { vm.stato.value.stimaEspressiva != null }
        assertFalse(JsonLectio.parseToJsonElement(server.richiesteA("POST /api/lezioni/stima").last().body.readUtf8()).toString().contains("materiali"))
        vm.argomento("L'Impero romano")
        vm.appunto(23) // tolto l'unico appunto, e non ci sono verifiche
        assertTrue(vm.stato.value.senzaMateriale)
        assertTrue(vm.stato.value.fraseSenzaMateriale!!.startsWith("Scegli almeno un appunto"))
        vm.prepara()
        Thread.sleep(100)
        assertTrue("con materiali [] il server userebbe tutto: non si manda", server.richiesteA("POST /api/lezioni").isEmpty())
        vm.appunto(23)
        assertFalse(vm.stato.value.senzaMateriale)
    }

    @Test
    fun p17_laStimaMandaIMaterialiScelti() {
        server.rispondi("GET /api/materie", """[{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi("GET /api/materie/1/materiale", """[{"id":21,"materia_id":1,"tipo":"verifica","titolo":"Verifica","stato":"pronto"},
            {"id":22,"materia_id":1,"tipo":"interrogazione","titolo":"Orale","stato":"pronto"},
            {"id":25,"materia_id":1,"tipo":"verifica","titolo":"Verifica nuova","stato":"in_lettura"},
            {"id":26,"materia_id":1,"tipo":"appunti","titolo":"Rovinati","stato":"errore"},
            {"id":23,"materia_id":1,"tipo":"appunti","titolo":"Augusto","stato":"pronto"},{"id":24,"materia_id":1,"tipo":"libro","titolo":"Libro","stato":"pronto"}]""")
        server.rispondi("GET /api/voci", """[{"id":"mistral:voice_482","nome":"Lorenzo","tipo":"espressiva","disponibile":true}]""")
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2}""")
        server.rispondi("POST /api/lezioni/stima", Esempi.STIMA)
        val vm = NuovaLezioneViewModel(SorgenteServer(server.client(), CacheMemoria()), 1, null)
        vm.carica()
        aspetta { vm.stato.value.stimaEspressiva != null }
        val prima = server.richiesteA("POST /api/lezioni/stima").size
        vm.appunto(24)
        aspetta { server.richiesteA("POST /api/lezioni/stima").size > prima }
        val corpo = server.richiesteA("POST /api/lezioni/stima").last().body.readUtf8()
        // v1.3: l'elenco COMPLETO, solo materiale pronto: verifiche, interrogazioni e appunti accesi.
        assertTrue(corpo, corpo.contains("\"materiali\":[21,22,23]"))
        // E la lezione parte con lo stesso elenco.
        server.rispondi("POST /api/lezioni", Esempi.LEZIONE, 201)
        vm.argomento("L'Impero romano")
        vm.prepara()
        aspetta { vm.stato.value.creata != null }
        val crea = server.richiesteA("POST /api/lezioni").single().body.readUtf8()
        assertTrue(crea, crea.contains("\"materiali\":[21,22,23]"))
    }

    // --- Contratto v1.3 (server 1.0.1) ---------------------------------------------------------------

    @Test
    fun v13_unModelloSenzaPrezzoMostraLaFraseDelServer() {
        server.rispondi("GET /api/materie", """[{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi("GET /api/materie/1/materiale", "[]")
        server.rispondi("GET /api/voci", """[{"id":"mistral:voice_482","nome":"Lorenzo","tipo":"espressiva","disponibile":true}]""")
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2}""")
        server.rispondi("POST /api/lezioni/stima", """{"errore":"Il modello scelto non ha un prezzo nel listino: avvisa chi cura il server","codice":"fornitore_non_disponibile"}""", 503)
        val vm = NuovaLezioneViewModel(SorgenteServer(server.client(), CacheMemoria()), 1, null)
        vm.carica()
        aspetta { vm.stato.value.errore != null }
        assertEquals("Il modello scelto non ha un prezzo nel listino: avvisa chi cura il server", vm.stato.value.errore)
    }

    @Test
    fun v13_lIdentitaArrivaConIlToken() {
        var salvata: Configurazione? = null
        val deposito = object : DepositoConfigurazione {
            override val flusso: Flow<Configurazione?> = MutableStateFlow(null)
            override suspend fun leggi(): Configurazione? = salvata
            override suspend fun salva(configurazione: Configurazione) { salvata = configurazione }
            override suspend fun dimentica() { salvata = null }
        }
        val vm = AbbinamentoViewModel(
            deposito,
            abbina = { _, _, _ -> Esito.Ok(JsonLectio.decodeFromString(eu.stgm.lectio.dati.Abbinamento.serializer(), """{"token":"${"t".repeat(64)}","dispositivo_id":4,"server_id":"0123456789abcdef0123456789abcdef"}""")) },
            prima = { _, _ -> },
            nome = { "Telefono di prova" },
            serverId = { error("con server_id nella risposta non serve chiederlo") },
        )
        vm.cambiaIndirizzo("192.168.0.20:8500")
        vm.cambiaCodice("48213377")
        vm.collega()
        aspetta { salvata != null }
        assertEquals("0123456789abcdef0123456789abcdef", salvata!!.serverId)
    }

    @Test
    fun v13_rigaDelMeseConIlLavoroInCorso() {
        val stima = JsonLectio.decodeFromString(eu.stgm.lectio.dati.Stima.serializer(), Esempi.STIMA.replace("\"spesa_mese_eur\": 0.80", "\"spesa_mese_eur\": 1.05, \"impegnato_eur\": 0.25"))
        val v = eu.stgm.lectio.logica.VistaCosto.calcola(stima, null, null, eu.stgm.lectio.logica.TipoVoce.ESPRESSIVA)
        assertEquals("Questo mese 0,80 €", v.rigaMese)
        assertEquals("+ 0,25 € in preparazione", v.rigaImpegnato)
        val senza = eu.stgm.lectio.logica.VistaCosto.calcola(JsonLectio.decodeFromString(eu.stgm.lectio.dati.Stima.serializer(), Esempi.STIMA), null, null, eu.stgm.lectio.logica.TipoVoce.ESPRESSIVA)
        assertEquals("Questo mese 0,80 €", senza.rigaMese)
        assertEquals(null, senza.rigaImpegnato)
    }

    @Test
    fun v13_lAvvisoDelMaterialeSiLegge() {
        val m = JsonLectio.decodeFromString(eu.stgm.lectio.dati.Materiale.serializer(),
            """{"id":7,"materia_id":1,"tipo":"libro","titolo":"Libro di storia","stato":"pronto","avviso":"Ho letto solo le prime 70 pagine: il resto supera il limite"}""")
        assertEquals("Ho letto solo le prime 70 pagine: il resto supera il limite", m.avviso)
    }

    // --- 18. Lezione annullata e frase della coda ------------------------------------------------

    @Test
    fun p18_annullataENonInPreparazione() {
        val annullata = Lezione(13, stato = "annullata", avanzamento = eu.stgm.lectio.dati.Avanzamento("voce", 3, 7, "Registro la voce · 3 di 7"))
        assertEquals("Annullata", Preparazione.frase(annullata))
        assertEquals(0f, Preparazione.frazione(annullata), 0f)
        assertTrue(Preparazione.passi(annullata).none { it.stato == StatoPasso.IN_CORSO })
        assertFalse(annullata.inLavoro)
        val inCoda = Lezione(14, stato = "in_coda", avanzamento = eu.stgm.lectio.dati.Avanzamento(null, 0, 0, "In coda: prima c'è la lezione di Inglese"))
        assertEquals("In coda: prima c'è la lezione di Inglese", Preparazione.frase(inCoda))
    }

    // --- 19. Testo lungo a pezzi ---------------------------------------------------------------------

    @Test
    fun p19_unLibroInteroSiMostraAPezzi() {
        val libro = (1..40_000).joinToString(" ") { "parola" } // ~280.000 caratteri, senza a capo
        val pezzi = paragrafiLeggibili(libro)
        assertTrue(pezzi.size > 100)
        assertTrue(pezzi.all { it.length <= 1500 })
        assertEquals(libro.replace(" ", ""), pezzi.joinToString("").replace(" ", ""))
        assertEquals(listOf("Primo paragrafo.", "Secondo."), paragrafiLeggibili("Primo paragrafo.\n\n  \nSecondo.\n"))
        assertTrue(paragrafiLeggibili(null).isEmpty())
    }

    // --- 20. Velocità predefinita anche sul telefono ---------------------------------------------

    @Test
    fun p20_laVelocitaDelleImpostazioniValeAncheSulTelefono() {
        val locali = mutableListOf<Float>()
        val vm = ImpostazioniViewModel(esempio, cambiaVelocitaLocale = { locali += it })
        vm.cambia(buildJsonObject { put("velocita", 1.4) })
        aspetta { locali.isNotEmpty() }
        assertEquals(1.4f, locali.single(), 0.001f)
        vm.cambia(buildJsonObject { put("durata_predefinita", 30) })
        Thread.sleep(50)
        assertEquals(1, locali.size)
    }

    // --- 21. Elimina materia: via anche le sue lezioni dal telefono ------------------------------

    @Test
    fun p21_eliminaMateriaTogliLeSueLezioniDalTelefono() {
        val tolte = mutableListOf<Long>()
        val vm = MateriaViewModel(esempio, SorgenteEsempio.MATERIA_STORIA, togliLezioniLocali = { tolte += it })
        vm.carica()
        aspetta { vm.stato.value.materia != null }
        var fatto = false
        vm.eliminaMateria { fatto = true }
        aspetta { fatto }
        assertEquals(listOf(SorgenteEsempio.LEZIONE_STORIA), tolte)
    }

    // --- 24. "Voce gratis" nell'avviso solo se c'è ---------------------------------------------------

    @Test
    fun p24_laVoceGratisSiNominaSoloSeCe() {
        assertFalse(fraseAvvisoTetto(false).contains("gratis"))
        assertTrue(fraseAvvisoTetto(true).contains("voce gratis"))
        server.rispondi("GET /api/oggi", Esempi.OGGI)
        server.rispondi("GET /api/materie", "[${Esempi.MATERIA}]")
        server.rispondi("GET /api/lezioni/12", Esempi.DETTAGLIO)
        server.rispondi("GET /api/voci", """[{"id":"azure:x","nome":"Isabella","tipo":"gratis","disponibile":false}]""")
        val vm = OggiViewModel(SorgenteServer(server.client(), CacheMemoria()))
        vm.carica()
        aspetta { vm.stato.value.primaLettura }
        assertFalse(vm.stato.value.voceGratis)
        server.rispondi("GET /api/voci", """[{"id":"azure:x","nome":"Isabella","tipo":"gratis","disponibile":true}]""")
        vm.carica()
        aspetta { vm.stato.value.voceGratis }
    }
}
