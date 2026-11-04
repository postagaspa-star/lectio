package eu.stgm.lectio.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.fotografo.AppDiProva
import eu.stgm.lectio.ui.lezione.NuovaLezioneScreen
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.materie.AzioniMateria
import eu.stgm.lectio.ui.materie.MateriaScreen
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.tema.LectioTema
import eu.stgm.lectio.visita.LocalVisita
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Le schermate vere (Robolectric, senza disegnare PNG): pulsanti accesi e spenti. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w390dp-h844dp-xxhdpi")
class SchermateTest {
    @get:Rule
    val compose = createComposeRule()

    private val oggi = LocalDate.of(2026, 10, 28)
    private val esempio = SorgenteEsempio(oggi)

    private fun mostra(contenuto: @Composable () -> Unit) {
        val ospite = OspiteFogli()
        compose.setContent {
            LectioTema(scuro = false, movimenti = false) {
                CompositionLocalProvider(LocalOspiteFogli provides ospite, LocalVisita provides null) {
                    contenuto()
                    FogliOspitati(ospite)
                }
            }
        }
    }

    /** "Cosa chiede il prof" vuota: Modifica c'è lo stesso, per scriverla a mano. */
    @Test
    fun laSchedaVuotaSiPuoModificare() {
        var aperta = false
        val vm = MateriaViewModel(esempio, 5) { oggi }
        mostra { MateriaScreen(vm, AzioniMateria(modificaScheda = { aperta = true }), esempio = false, senzaRete = false, scaricate = emptySet()) }
        compose.waitUntil(10_000) { vm.stato.value.scheda?.stato == "vuota" && vm.stato.value.materia != null }
        compose.onNodeWithText("Ancora nessuna verifica letta.").performScrollTo()
        compose.onNodeWithText("Modifica").assertIsEnabled().performClick()
        assertTrue(aperta)
    }

    /** Prepara spento senza materiale o senza argomento, con il perché nella barra. */
    @Test
    fun preparaSiAccendeSoloConArgomentoEMateriale() {
        val vm = NuovaLezioneViewModel(esempio, SorgenteEsempio.MATERIA_STORIA, 3, oggi = { oggi }, attesaSuggeritiMs = 0)
        mostra { NuovaLezioneScreen(vm, {}, {}, esempio = true, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.stimaEspressiva != null }
        val prepara = hasText("Prepara la lezione", substring = true) and hasClickAction()
        compose.onNode(prepara).assertIsEnabled()
        compose.onNodeWithText("Spiegazione · 20 min · 3 materiali").assertExists()

        compose.onNodeWithText("Nessuno").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(prepara).assertIsNotEnabled()
        compose.onAllNodesWithText("Scegli almeno un materiale").onFirst().assertExists()

        compose.onNodeWithText("Sull'argomento").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(prepara).assertIsEnabled()

        compose.runOnIdle { vm.argomento("") }
        compose.waitUntil(5_000) { vm.stato.value.scelti.isEmpty() }
        compose.onNode(prepara).assertIsNotEnabled()
        compose.onAllNodesWithText("Manca l'argomento").onFirst().assertExists()
    }
}
