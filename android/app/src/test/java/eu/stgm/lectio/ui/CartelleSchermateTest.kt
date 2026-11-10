package eu.stgm.lectio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.fotografo.AppDiProva
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.ui.lezione.NuovaLezioneScreen
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.FoglioAggiungi
import eu.stgm.lectio.ui.materiale.MaterialeScreen
import eu.stgm.lectio.ui.materiale.MaterialeViewModel
import eu.stgm.lectio.ui.materiale.PassoAggiunta
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materie.AzioniCartella
import eu.stgm.lectio.ui.materie.AzioniMateria
import eu.stgm.lectio.ui.materie.CartellaScreen
import eu.stgm.lectio.ui.materie.CartellaViewModel
import eu.stgm.lectio.ui.materie.MateriaScreen
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.tema.LectioTema
import eu.stgm.lectio.visita.LocalVisita
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Le cartelle sulle schermate vere (Robolectric, aspetto.md §42), coi dati di esempio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w390dp-h844dp-xxhdpi")
class CartelleSchermateTest {
    @get:Rule
    val compose = createComposeRule()

    private val oggi = LocalDate.of(2026, 10, 28)
    private val esempio = SorgenteEsempio(oggi)
    private val storia = SorgenteEsempio.MATERIA_STORIA
    private val imperatori = SorgenteEsempio.CARTELLA_STORIA

    /** Come nell'app: la pagina e, sopra, i fogli ospitati. */
    private fun mostra(contenuto: @Composable () -> Unit) {
        val ospite = OspiteFogli()
        compose.setContent {
            LectioTema(scuro = false, movimenti = false) {
                CompositionLocalProvider(LocalOspiteFogli provides ospite, LocalVisita provides null) {
                    Box(Modifier.fillMaxSize()) {
                        contenuto()
                        FogliOspitati(ospite)
                    }
                }
            }
        }
    }

    /**
     * Il tocco come azione di accessibilità: qui conta cosa succede, non dove cade il dito.
     * In Robolectric un tocco simulato nella parte bassa dello schermo (i fogli dal basso, le
     * righe sotto il pulsante fisso) non arriva, anche sui fogli che c'erano già.
     */
    private fun SemanticsNodeInteraction.tocca() {
        performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun esiste(testo: String, parte: Boolean = false) =
        compose.onAllNodesWithText(testo, substring = parte).fetchSemanticsNodes().isNotEmpty()

    // --- Pagina della materia (§42.1) ----------------------------------------------------------------

    @Test
    fun laMateriaMostraPrimaLeCartellePoiNuovaCartellaPoiIFileFuori() {
        var aperta: Long? = null
        val vm = MateriaViewModel(esempio, storia, oggi = { oggi })
        mostra { MateriaScreen(vm, AzioniMateria(apriCartella = { aperta = it }), esempio = false, senzaRete = false, scaricate = emptySet()) }
        compose.waitUntil(10_000) { vm.stato.value.cartelle.isNotEmpty() && vm.stato.value.materiale.isNotEmpty() }
        compose.onNodeWithText("2 file · 1 messo ora da Lectio").performScrollTo().assertExists()
        compose.onNodeWithText("Nuova cartella").assertIsEnabled()
        compose.onNodeWithText("Augusto e il principato").assertExists()
        // Quelli dentro la cartella qui non compaiono.
        compose.onNodeWithText("Interrogazione su Tiberio").assertDoesNotExist()
        compose.onNodeWithText("L'Impero romano dopo Augusto").assertDoesNotExist()
        compose.onNodeWithText("Imperatori").tocca()
        assertEquals(imperatori, aperta)
    }

    @Test
    fun anchePerUnaMateriaVuotaCeNuovaCartella() {
        val vm = MateriaViewModel(esempio, 5, oggi = { oggi })
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet()) }
        compose.waitUntil(10_000) { vm.stato.value.materia != null }
        compose.onNodeWithText("Nuova cartella").performScrollTo().assertIsEnabled()
    }

    @Test
    fun nuovaCartellaCreaEApreONomeDoppioDiceLErrore() {
        var aperta: Long? = null
        val vm = MateriaViewModel(esempio, storia, oggi = { oggi })
        mostra { MateriaScreen(vm, AzioniMateria(apriCartella = { aperta = it }), esempio = false, senzaRete = false, scaricate = emptySet()) }
        compose.waitUntil(10_000) { vm.stato.value.cartelle.isNotEmpty() }
        compose.onNodeWithText("Nuova cartella").performScrollTo().tocca()
        compose.waitForIdle()
        compose.onNodeWithText("Crea cartella").assertIsNotEnabled()
        // Un nome che c'è già (senza badare alle maiuscole): la frase sotto il campo.
        compose.onNodeWithContentDescription("Nome della cartella").performTextInput("imperatori")
        compose.onNodeWithText("Crea cartella").assertIsEnabled().tocca()
        compose.waitUntil(5_000) { esiste("C'è già una cartella con questo nome in questa materia.") }
        assertNull(aperta)
        compose.onNodeWithContentDescription("Nome della cartella").performTextInput(" romani")
        compose.onNodeWithText("Crea cartella").tocca()
        compose.waitUntil(5_000) { aperta != null }
        val nuova = runBlocking { esempio.cartelle(storia).dato.orEmpty() }.single { it.id == aperta }
        assertEquals("imperatori romani", nuova.nome)
    }

    // --- Pagina della cartella (§42.2) ---------------------------------------------------------------

    @Test
    fun laPaginaDellaCartella() {
        val vm = CartellaViewModel(esempio, imperatori, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.caricata && vm.stato.value.materia != null }
        compose.onNodeWithText("Imperatori").assertExists()
        compose.onNodeWithText("Storia · 2 file").assertExists()
        compose.onNodeWithText("Interrogazione su Tiberio").assertExists()
        compose.onNodeWithText("L'Impero romano dopo Augusto").assertExists()
        // Solo quello messo lì da Lectio lo dice.
        assertEquals(1, compose.onAllNodesWithText("messo qui da Lectio", substring = true).fetchSemanticsNodes().size)
        compose.onNode(hasText("Metti dei file qui") and hasClickAction()).assertIsEnabled()
        compose.onNodeWithText("Aggiungi").assertIsEnabled()
    }

    @Test
    fun senzaReteLaCartellaHaTuttoSpento() {
        val vm = CartellaViewModel(esempio, imperatori, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(), esempio = false, senzaRete = true) }
        compose.waitUntil(10_000) { vm.stato.value.caricata }
        compose.onNode(hasText("Metti dei file qui") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("Aggiungi").assertIsNotEnabled()
    }

    @Test
    fun mettiDeiFileQuiPartedaiPropostiGiaSpuntati() {
        val conProposti = object : Sorgente by esempio {
            override suspend fun proposti(cartellaId: Long): Esito<List<Long>> = Esito.Ok(listOf(23L))
        }
        val vm = CartellaViewModel(conProposti, imperatori, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.caricata }
        compose.onNode(hasText("Metti dei file qui") and hasClickAction()).tocca()
        compose.waitUntil(5_000) { vm.stato.value.metti?.inAttesa == false }
        compose.onNodeWithText("Metti in Imperatori").assertExists()
        compose.onNodeWithText("1 scelto").assertExists()
        compose.onNodeWithText("FUORI DALLE CARTELLE").assertExists()
        compose.onNodeWithText("Augusto e il principato").assertIsOn()
        compose.onNodeWithText("Le guerre puniche").assertIsOff()
        compose.onNodeWithText("Le guerre puniche").tocca()
        compose.onNodeWithText("2 scelti").assertExists()
        compose.onNodeWithText("Metti qui 2 file").assertIsEnabled().tocca()
        compose.waitUntil(5_000) { vm.stato.value.metti == null && vm.stato.value.dentro.size == 4 }
        compose.onNodeWithText("Metti in Imperatori").assertDoesNotExist()
    }

    @Test
    fun eliminareConIFileDaLeDueScelte() {
        var frase: String? = null
        val vm = CartellaViewModel(esempio, imperatori, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(eliminata = { frase = it }), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.caricata }
        compose.onNodeWithContentDescription("Altre azioni").tocca()
        compose.onNodeWithText("Elimina cartella").tocca()
        compose.waitForIdle()
        compose.onNodeWithText("Eliminare la cartella «Imperatori»?").assertExists()
        compose.onNodeWithText("Dentro ci sono 2 file.").assertExists()
        compose.onNodeWithText("I file restano nella materia").assertExists()
        compose.onNodeWithText("Elimina anche i 2 file").assertIsEnabled()
        compose.onNodeWithText("Annulla").assertIsEnabled()
        compose.onNodeWithText("Elimina solo la cartella").tocca()
        compose.waitUntil(5_000) { frase != null }
        assertEquals("Cartella eliminata", frase)
        // I due file restano nella materia, fuori.
        val materiale = runBlocking { esempio.materiale(storia).dato.orEmpty() }
        assertTrue(materiale.filter { it.id == 22L || it.id == 24L }.all { it.cartellaId == null })
    }

    @Test
    fun eliminareAncheIFile() {
        var frase: String? = null
        val vm = CartellaViewModel(esempio, imperatori, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(eliminata = { frase = it }), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.caricata }
        compose.onNodeWithContentDescription("Altre azioni").tocca()
        compose.onNodeWithText("Elimina cartella").tocca()
        compose.waitForIdle()
        compose.onNodeWithText("Elimina anche i 2 file").tocca()
        compose.waitUntil(5_000) { frase != null }
        assertEquals("Cartella e 2 file eliminati", frase)
    }

    @Test
    fun unaCartellaVuotaSiEliminaConUnaConfermaSemplice() {
        val vuota = runBlocking { (esempio.creaCartella(storia, "Arte romana") as Esito.Ok).dato }
        var frase: String? = null
        val vm = CartellaViewModel(esempio, vuota.id, storia) { oggi }
        mostra { CartellaScreen(vm, AzioniCartella(eliminata = { frase = it }), esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.caricata }
        compose.onNodeWithText("Ancora nessun file in questa cartella.").assertExists()
        compose.onNodeWithText("Storia · Vuota").assertExists()
        compose.onNodeWithContentDescription("Altre azioni").tocca()
        compose.onNodeWithText("Elimina cartella").tocca()
        compose.waitForIdle()
        compose.onNodeWithText("È vuota.").assertExists()
        compose.onNodeWithText("Elimina solo la cartella").assertDoesNotExist()
        compose.onNodeWithText("Elimina").tocca()
        compose.waitUntil(5_000) { frase != null }
        assertEquals("Cartella eliminata", frase)
    }

    // --- Scelta del materiale: CARTELLE (§42.7) ------------------------------------------------------

    @Test
    fun laPillolaCartellaNellaNuovaLezione() {
        val vm = NuovaLezioneViewModel(esempio, storia, 3, oggi = { oggi }, attesaSuggeritiMs = 0)
        mostra { NuovaLezioneScreen(vm, {}, {}, esempio = true, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.stimaEspressiva != null && vm.stato.value.cartelle.isNotEmpty() }
        compose.onNodeWithText("CARTELLE").performScrollTo().assertExists()
        // Il nome della cartella nelle righe, dopo il tipo.
        compose.onNodeWithText("Appunti · Imperatori · foto", substring = true).assertExists()
        val pillola = compose.onNode(hasText("Imperatori") and hasText(" · 2"))
        pillola.performScrollTo().assertIsOff()
        pillola.tocca()
        compose.waitForIdle()
        assertTrue(vm.stato.value.scelti.containsAll(listOf(22L, 24L)))
        pillola.assertIsOn()
        pillola.tocca()
        compose.waitForIdle()
        assertTrue(vm.stato.value.scelti.none { it == 22L || it == 24L })
        // Cerca trova anche per nome della cartella.
        compose.runOnIdle { vm.cerca("imperat") }
        compose.waitForIdle()
        compose.onNodeWithText("Interrogazione su Tiberio").assertExists()
        compose.onNodeWithText("Le guerre puniche").assertDoesNotExist()
    }

    // --- Dove si sceglie la cartella di un file (§42.6) ---------------------------------------------

    @Test
    fun aggiungiDallaCartellaHaGiaLaSuaPillola() {
        val vm = AggiuntaViewModel(esempio, null).apply { apri(RichiestaAggiunta(storia, PassoAggiunta.APPUNTI, cartellaId = imperatori)) }
        mostra { FoglioAggiungi(vm, null, {}, {}, {}, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.cartelle.isNotEmpty() }
        compose.onNodeWithText("CARTELLA").assertExists()
        compose.onNodeWithText("Imperatori").assertIsSelected()
        compose.onNodeWithText("Decide Lectio").assertIsNotSelected().tocca()
        compose.waitForIdle()
        assertNull(vm.stato.value.richiesta.cartellaId)
        compose.onNodeWithText("Decide Lectio").assertIsSelected()
    }

    @Test
    fun aggiungiSenzaCartelleNonMostraLaRiga() {
        val vm = AggiuntaViewModel(esempio, null).apply { apri(RichiestaAggiunta(5, PassoAggiunta.APPUNTI)) }
        mostra { FoglioAggiungi(vm, null, {}, {}, {}, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.materie.isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("CARTELLA").assertDoesNotExist()
        compose.onNodeWithText("Decide Lectio").assertDoesNotExist()
    }

    @Test
    fun unMaterialeMessoDaLectioLoDice() {
        val vm = MaterialeViewModel(esempio, 24, durataSalvato = 0)
        mostra { MaterialeScreen(vm, {}, esempio = false, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.materiale != null && vm.stato.value.cartelle.isNotEmpty() }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Messo qui da Lectio"))
        compose.onNodeWithText("Messo qui da Lectio").assertExists()
        compose.onNodeWithText("Imperatori").assertIsSelected()
        compose.onNodeWithText("Fuori").assertIsNotSelected().tocca()
        compose.waitForIdle()
        assertNull(vm.stato.value.cartellaId)
        compose.onNodeWithText("Messo qui da Lectio").assertDoesNotExist()
    }
}
