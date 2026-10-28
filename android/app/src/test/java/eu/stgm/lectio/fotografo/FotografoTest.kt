package eu.stgm.lectio.fotografo

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.Lettura
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.RichiestaStima
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.logica.GenereFile
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.altre.TestoMaterialeScreen
import eu.stgm.lectio.ui.altre.TestoMaterialeViewModel
import eu.stgm.lectio.ui.materiale.FileScelto
import eu.stgm.lectio.ui.materiale.PassoAggiunta
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.lettore.LettoreEsempio
import eu.stgm.lectio.ui.FogliOspitati
import eu.stgm.lectio.ui.LocalOspiteFogli
import eu.stgm.lectio.ui.OspiteFogli
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.abbinamento.AbbinamentoScreen
import eu.stgm.lectio.ui.abbinamento.AbbinamentoViewModel
import eu.stgm.lectio.ui.altre.ComeAndataScreen
import eu.stgm.lectio.ui.altre.ComeAndataViewModel
import eu.stgm.lectio.ui.comuni.BarraSchede
import eu.stgm.lectio.ui.gemini.GeminiScreen
import eu.stgm.lectio.ui.gemini.GeminiViewModel
import eu.stgm.lectio.ui.impostazioni.ImpostazioniScreen
import eu.stgm.lectio.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.lectio.ui.impostazioni.AzioniImpostazioni
import eu.stgm.lectio.ui.lettore.AzioniLettore
import eu.stgm.lectio.ui.lettore.LettoreScreen
import eu.stgm.lectio.ui.lettore.LettoreViewModel
import eu.stgm.lectio.ui.lettore.MiniLettore
import eu.stgm.lectio.ui.lezione.NuovaLezioneScreen
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.lezione.PreparazioneScreen
import eu.stgm.lectio.ui.lezione.PreparazioneViewModel
import eu.stgm.lectio.ui.lezioni.AzioniLezioni
import eu.stgm.lectio.ui.lezioni.LezioniScreen
import eu.stgm.lectio.ui.lezioni.LezioniViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.FoglioAggiungi
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materie.AzioniMateria
import eu.stgm.lectio.ui.materie.MateriaScreen
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.materie.MaterieScreen
import eu.stgm.lectio.ui.materie.MaterieViewModel
import eu.stgm.lectio.ui.oggi.AzioniOggi
import eu.stgm.lectio.ui.oggi.OggiScreen
import eu.stgm.lectio.ui.oggi.OggiViewModel
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LectioTema
import eu.stgm.lectio.ui.visita.StratoVisita
import eu.stgm.lectio.dati.TemaScelto
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.Visita
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Senza LectioApp: niente WorkManager né notifiche quando si disegnano le schermate. */
class AppDiProva : Application()

/**
 * Il fotografo: disegna le schermate vere coi dati d'esempio (Prof. Rossi,
 * Bianchi) in PNG, chiare e scure, larghe 390 dp come il prototipo.
 *   gradlew :app:testDebugUnitTest -Pfotografo [-Pfotografo.cartella=C:/percorso]
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w390dp-h844dp-xxhdpi")
class FotografoTest {
    @get:Rule
    val compose = createComposeRule()

    private val cartella = File(System.getProperty("fotografo.cartella") ?: "build/fotografo").also { it.mkdirs() }
    private var scuro by mutableStateOf(false)
    private val oggi = LocalDate.of(2026, 10, 28)

    /** Come nell'app: tema, fogli ospitati, visita; [scheda] = sotto c'è la barra a 4 voci. */
    private fun mostra(scheda: Scheda? = null, visita: Visita? = null, mini: LettoreEsempio? = null, contenuto: @Composable () -> Unit) {
        val ospite = OspiteFogli()
        compose.setContent {
            LectioTema(scuro = scuro, movimenti = false) {
                CompositionLocalProvider(LocalOspiteFogli provides ospite, LocalVisita provides visita) {
                    Box(Modifier.fillMaxSize().background(L.colori.fondo)) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { contenuto() }
                            if (scheda != null) {
                                if (mini != null) MiniLettore(mini.stato.value, {}, {})
                                BarraSchede(scheda) {}
                            }
                        }
                        FogliOspitati(ospite)
                        if (visita != null) StratoVisita(visita)
                    }
                }
            }
        }
    }

    private fun scatta(nome: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(cartella, "$nome.png").path)
    }

    private fun chiaroEScuro(nome: String) {
        scuro = false
        scatta("${nome}_chiaro")
        scuro = true
        scatta("${nome}_scuro")
        scuro = false
        compose.waitForIdle()
    }

    private val esempio = SorgenteEsempio(oggi)

    @Test
    fun oggi() {
        val vm = OggiViewModel(esempio)
        mostra(Scheda.OGGI) { OggiScreen(vm, AzioniOggi(), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.dettaglioProssima != null }
        chiaroEScuro("01_oggi")
    }

    @Test
    fun oggiSenzaReteConMiniLettore() {
        val vm = OggiViewModel(esempio)
        val lettore = LettoreEsempio()
        lettore.carica(esempio.lezioneCompleta()!!, esempio.materiaBreve(1), 462.0, false)
        mostra(Scheda.OGGI, mini = lettore) { OggiScreen(vm, AzioniOggi(), esempio = false, senzaRete = true) }
        compose.waitUntil(10_000) { vm.stato.value.dettaglioProssima != null }
        chiaroEScuro("02_oggi_senza_rete_mini_lettore")
    }

    @Test
    fun materie() {
        val vm = MaterieViewModel(esempio)
        mostra(Scheda.MATERIE) { MaterieScreen(vm, {}, esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.letta }
        chiaroEScuro("03_materie")
    }

    @Test
    fun nuovaMateria() {
        val vm = MaterieViewModel(esempio)
        mostra(Scheda.MATERIE) { MaterieScreen(vm, {}, esempio = false, senzaRete = false, apriNuova = true) }
        compose.waitUntil(10_000) { vm.stato.value.letta }
        chiaroEScuro("04_materie_nuova")
    }

    @Test
    fun materia() {
        val vm = MateriaViewModel(esempio, 1) { oggi }
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = setOf(12L)) }
        compose.waitUntil(10_000) { vm.stato.value.scheda != null && vm.stato.value.materia != null }
        chiaroEScuro("05_materia")
    }

    @Test
    fun aggiungi() {
        val vmMateria = MateriaViewModel(esempio, 1) { oggi }
        val vm = AggiuntaViewModel(esempio, null).apply { apri(RichiestaAggiunta(1)) }
        mostra {
            MateriaScreen(vmMateria, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet())
            FoglioAggiungi(vm, null, {}, {}, {}, senzaRete = false)
        }
        compose.waitUntil(10_000) { vm.stato.value.materie.isNotEmpty() }
        chiaroEScuro("06_aggiungi")
    }

    @Test
    fun nuovaLezione() {
        val vm = NuovaLezioneViewModel(esempio, 1, 3)
        mostra { NuovaLezioneScreen(vm, {}, {}, esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.stimaEspressiva != null }
        chiaroEScuro("07_nuova_lezione")
    }

    @Test
    fun inPreparazione() {
        val vm = PreparazioneViewModel(esempio, 13)
        mostra { PreparazioneScreen(vm, {}, {}, {}, controlla = false) }
        compose.waitUntil(10_000) { vm.stato.value.lezione != null && vm.stato.value.materia != null }
        chiaroEScuro("08_in_preparazione")
    }

    private fun lettore(preparazione: (LettoreEsempio) -> Unit): LettoreEsempio {
        val l = LettoreEsempio(correttore = esempio::rispondi)
        l.carica(esempio.lezioneCompleta()!!, esempio.materiaBreve(1), 462.0, false)
        preparazione(l)
        val vm = LettoreViewModel(esempio, l)
        mostra { LettoreScreen(vm, AzioniLettore(), scaricata = true, scaricamento = null, esempio = false, senzaRete = false) }
        return l
    }

    @Test
    fun lettoreInAscolto() {
        lettore { }
        chiaroEScuro("09_lettore")
    }

    @Test
    fun lettoreAllaDomanda() {
        lettore { it.fermaAllaDomanda(2) }
        chiaroEScuro("10_lettore_domanda")
    }

    @Test
    fun lettoreCorrezione() {
        val d = esempio.lezioneCompleta()!!.domande[1]
        lettore {
            it.mostraCorrezione(2, "Non si fidava del senato", Correzione(88, d.id, "parziale", 0.5,
                "Giusto: non si fidava del senato. Manca chi governava Roma al posto suo.", listOf("il ruolo di Seiano"), d.risposta))
        }
        chiaroEScuro("11_lettore_correzione")
    }

    @Test
    fun lezioni() {
        val vm = LezioniViewModel(esempio)
        mostra(Scheda.LEZIONI) { LezioniScreen(vm, AzioniLezioni(), setOf(12L), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.letta }
        chiaroEScuro("12_lezioni")
    }

    @Test
    fun gemini() {
        val vm = GeminiViewModel(esempio)
        mostra(Scheda.GEMINI) { GeminiScreen(vm, {}, esempio = false, senzaRete = false, copiaTesto = { true }) }
        compose.waitUntil(10_000) { vm.testo.value != null }
        chiaroEScuro("13_gemini")
    }

    @Test
    fun comeAndata() {
        val vm = ComeAndataViewModel(esempio, 3)
        mostra { ComeAndataScreen(vm, {}, {}, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.lezione != null }
        chiaroEScuro("14_come_andata")
    }

    @Test
    fun comeAndataRisultato() {
        val vm = ComeAndataViewModel(esempio, 3)
        mostra { ComeAndataScreen(vm, {}, {}, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.lezione != null }
        vm.uscita(31, true)
        vm.uscita(32, true)
        vm.uscita(34, true)
        vm.uscita(36, true)
        vm.altre("Chi era Seiano?\nCos'era il pretorio?")
        vm.salva()
        compose.waitUntil(10_000) { vm.stato.value.risultato != null }
        chiaroEScuro("15_come_andata_risultato")
    }

    @Test
    fun impostazioni() {
        val vm = ImpostazioniViewModel(esempio)
        mostra { ImpostazioniScreen(vm, AzioniImpostazioni(), "http://192.168.0.20:8500", "https://lectio.esempio.ts.net", TemaScelto.SISTEMA, false, "1.0.0", false) }
        compose.waitUntil(10_000) { vm.stato.value.impostazioni != null }
        chiaroEScuro("16_impostazioni")
    }

    /** Il server non ha ancora mandato l'indirizzo da fuori casa: la riga resta, vuota. */
    @Test
    fun impostazioniSenzaFuoriCasa() {
        val vm = ImpostazioniViewModel(esempio)
        mostra { ImpostazioniScreen(vm, AzioniImpostazioni(), "http://192.168.0.20:8500", null, TemaScelto.SISTEMA, false, "1.0.0", false) }
        compose.waitUntil(10_000) { vm.stato.value.impostazioni != null }
        chiaroEScuro("25_impostazioni_senza_fuori_casa")
    }

    @Test
    fun abbinamento() {
        val vm = AbbinamentoViewModel(object : eu.stgm.lectio.dati.DepositoConfigurazione {
            override val flusso = kotlinx.coroutines.flow.MutableStateFlow<eu.stgm.lectio.rete.Configurazione?>(null)
            override suspend fun leggi() = null
            override suspend fun salva(configurazione: eu.stgm.lectio.rete.Configurazione) {}
            override suspend fun dimentica() {}
        }, nome = { "Telefono" })
        mostra { AbbinamentoScreen(vm, perso = false) }
        chiaroEScuro("17_abbinamento")
    }

    @Test
    fun visitaPrimoPasso() {
        val visita = Visita()
        val vm = OggiViewModel(esempio)
        mostra(Scheda.OGGI, visita = visita) { OggiScreen(vm, AzioniOggi(), esempio = true, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.dettaglioProssima != null }
        compose.runOnIdle { visita.inizia() }
        compose.waitUntil(10_000) { visita.rettangolo() != null }
        chiaroEScuro("18_visita_passo1")
    }

    @Test
    fun visitaDomanda() {
        val visita = Visita()
        val l = LettoreEsempio(correttore = esempio::rispondi)
        l.carica(esempio.lezioneCompleta()!!, esempio.materiaBreve(1), 0.0, false)
        l.fermaAllaDomanda(2)
        val vm = LettoreViewModel(esempio, l)
        mostra(visita = visita) { LettoreScreen(vm, AzioniLettore(), scaricata = false, scaricamento = null, esempio = true, senzaRete = false) }
        compose.runOnIdle {
            visita.inizia()
            repeat(7) { if (visita.corrente.pratico) visita.azione(visita.corrente.bersaglio!!) else visita.avanti() }
        }
        compose.waitUntil(10_000) { visita.rettangolo() != null }
        chiaroEScuro("19_visita_passo8_domanda")
    }

    // --- Dopo la revisione: le schermate cambiate ---------------------------------------------

    /** Un file condiviso: la data resta vuota ("La prendo dal file"), punto 16. */
    @Test
    fun aggiungiFile() {
        val vmMateria = MateriaViewModel(esempio, 1) { oggi }
        val vm = AggiuntaViewModel(esempio, null).apply {
            apri(RichiestaAggiunta(1, PassoAggiunta.FILE, file = listOf(FileScelto("content://prova/1", "verifica_12_ottobre.pdf", "application/pdf", GenereFile.PDF))))
        }
        mostra {
            MateriaScreen(vmMateria, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet())
            FoglioAggiungi(vm, null, {}, {}, {}, senzaRete = false)
        }
        compose.waitUntil(10_000) { vm.stato.value.materie.isNotEmpty() }
        chiaroEScuro("20_aggiungi_file")
    }

    /** Le conferme di "Elimina" senza rosso, punto 22. */
    @Test
    fun confermaElimina() {
        val vm = MateriaViewModel(esempio, 1) { oggi }
        mostra {
            MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet())
            Conferma("Eliminare Storia?", "Spariscono anche il suo materiale e le sue lezioni, dal server e da questo telefono.", "Elimina", {}, {})
        }
        compose.waitUntil(10_000) { vm.stato.value.materia != null }
        chiaroEScuro("21_conferma_elimina")
    }

    /** Una lezione annullata non sembra in preparazione, punto 18. */
    @Test
    fun preparazioneAnnullata() {
        val sorgente = object : Sorgente by esempio {
            override suspend fun lezione(id: Long): Lettura<Lezione> {
                val l = this@FotografoTest.esempio.lezione(id)
                return l.copy(dato = l.dato?.copy(stato = "annullata"))
            }
        }
        val vm = PreparazioneViewModel(sorgente, 13)
        mostra { PreparazioneScreen(vm, {}, {}, {}, controlla = false) }
        compose.waitUntil(10_000) { vm.stato.value.lezione != null && vm.stato.value.materia != null }
        chiaroEScuro("22_preparazione_annullata")
    }

    /** Testo letto a paragrafi (punto 19) e avviso del server sul materiale (contratto v1.3). */
    @Test
    fun testoMateriale() {
        val testo = (1..12).joinToString("\n\n") { n ->
            "Paragrafo $n. Augusto governò come princeps, primo tra i cittadini, lasciando in piedi le forme della repubblica: " +
                "il senato si riuniva ancora e i consoli venivano eletti ogni anno, ma le decisioni importanti passavano da lui."
        }
        val sorgente = object : Sorgente by esempio {
            override suspend fun materialeDettaglio(id: Long): Esito<Materiale> {
                val m = (this@FotografoTest.esempio.materialeDettaglio(id) as Esito.Ok<Materiale>).dato
                return Esito.Ok(m.copy(tipo = "libro", titolo = "Libro di storia, capitoli 3 e 4", testo = testo, avviso = "Ho letto solo le prime 70 pagine: il resto supera il limite"))
            }
        }
        val vm = TestoMaterialeViewModel(sorgente, 23)
        mostra { TestoMaterialeScreen(vm, {}, senzaRete = false) }
        compose.waitUntil(10_000) { vm.materiale.value != null }
        chiaroEScuro("23_testo_materiale")
    }

    /** La riga del mese con il lavoro in corso (contratto v1.3, impegnato_eur). */
    @Test
    fun nuovaLezioneConLavoroInCorso() {
        val sorgente = object : Sorgente by esempio {
            override suspend fun stima(richiesta: RichiestaStima): Esito<Stima> {
                val s = (this@FotografoTest.esempio.stima(richiesta) as Esito.Ok<Stima>).dato
                return Esito.Ok(s.copy(spesaMeseEur = s.spesaMeseEur + 0.25, impegnatoEur = 0.25, dopoEur = s.dopoEur + 0.25))
            }
        }
        val vm = NuovaLezioneViewModel(sorgente, 1, 3)
        mostra { NuovaLezioneScreen(vm, {}, {}, esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.stimaEspressiva != null }
        // La riga del mese sta sotto il pulsante fisso: si scorre fino in fondo.
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        chiaroEScuro("24_nuova_lezione_lavoro_in_corso")
    }
}

