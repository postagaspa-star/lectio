package eu.stgm.lectio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import eu.stgm.lectio.dati.Lampo
import eu.stgm.lectio.dati.Lettura
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.fotografo.AppDiProva
import eu.stgm.lectio.ui.materie.AzioniMateria
import eu.stgm.lectio.ui.materie.MateriaScreen
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.studio.AzioniSchema
import eu.stgm.lectio.ui.studio.LetturaSchemaScreen
import eu.stgm.lectio.ui.studio.NonHoCapitoViewModel
import eu.stgm.lectio.ui.studio.SchemaViewModel
import eu.stgm.lectio.ui.tema.LectioTema
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.Visita
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Il pulsante Nuovo, il foglio "Cosa vuoi fare?" e l'ordine della materia (aspetto.md §43). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AppDiProva::class, qualifiers = "it-rIT-w390dp-h844dp-xxhdpi")
class NuovoSchermateTest {
    @get:Rule
    val compose = createComposeRule()

    private val oggi = LocalDate.of(2026, 10, 28)
    private val esempio = SorgenteEsempio(oggi)
    private val storia = SorgenteEsempio.MATERIA_STORIA
    private val matematica = 5L

    /** Come nell'app: la pagina e, sopra, i fogli ospitati. */
    private fun mostra(visita: Visita? = null, contenuto: @Composable () -> Unit) {
        val ospite = OspiteFogli()
        compose.setContent {
            LectioTema(scuro = false, movimenti = false) {
                CompositionLocalProvider(LocalOspiteFogli provides ospite, LocalVisita provides visita) {
                    Box(Modifier.fillMaxSize()) {
                        contenuto()
                        FogliOspitati(ospite)
                    }
                }
            }
        }
    }

    /** Il tocco come azione di accessibilità (in Robolectric i tocchi bassi sullo schermo non arrivano). */
    private fun SemanticsNodeInteraction.tocca() {
        performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun esiste(testo: String) = compose.onAllNodesWithText(testo).fetchSemanticsNodes().isNotEmpty()

    /** Dove comincia (senza ritaglio: vale anche per quello fuori schermo, più in basso). */
    private fun alto(testo: String) = compose.onNodeWithText(testo).fetchSemanticsNode().positionInRoot.y

    /** La riga del foglio (la scheda di Da ripassare, sulla pagina, ha lo stesso nome e viene prima). */
    private fun rigaLampo() = compose.onAllNodes(hasText("Ripasso lampo") and hasClickAction()).onLast()

    private fun materia(id: Long = storia, sorgente: Sorgente = esempio): MateriaViewModel =
        MateriaViewModel(sorgente, id, oggi = { oggi })

    private fun aspettaMateria(vm: MateriaViewModel) =
        compose.waitUntil(10_000) { vm.stato.value.materia != null && vm.stato.value.scheda != null }

    // --- §43.1 Ordine delle sezioni -------------------------------------------------------------

    @Test
    fun ilMaterialeStaInFondoSottoLezioniSchemiESimulate() {
        val vm = materia()
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.waitForIdle()
        val ordine = listOf("Cosa chiede il prof", "Prossime", "Da ripassare", "Lezioni", "Materiale").map(::alto)
        assertTrue(ordine.toString(), ordine.zipWithNext().all { (a, b) -> a < b })
        // Anche le righe del materiale vengono dopo l'elenco.
        assertTrue(alto("Imperatori") > alto("Lezioni"))
    }

    // --- §43.2 Il pulsante --------------------------------------------------------------------------

    @Test
    fun ilPulsanteENuovoQualunqueSegmentoSiaAperto() {
        val vm = materia()
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        listOf(0, 1, 2, 0).forEach { i ->
            compose.runOnIdle { vm.segmento(i) }
            compose.waitForIdle()
            compose.onNodeWithText("Nuovo").assertIsEnabled()
            assertFalse(esiste("Nuova lezione"))
            assertFalse(esiste("Nuovo schema"))
            assertFalse(esiste("Nuova verifica simulata"))
        }
    }

    @Test
    fun senzaReteESenzaSchedeSalvateNuovoESpento() {
        val senzaSchede = object : Sorgente by esempio {
            override suspend fun lampo(materiaId: Long?, limite: Int): Lettura<Lampo> = Lettura(null, "Serve la rete")
        }
        val vm = materia(sorgente = senzaSchede)
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = true, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.waitForIdle()
        compose.onNodeWithText("Nuovo").assertIsNotEnabled()
    }

    // --- §43.3 Il foglio --------------------------------------------------------------------------

    @Test
    fun nuovoApreCosaVuoiFareConCinqueRigheAccese() {
        val vm = materia()
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.waitUntil(5_000) { vm.stato.value.schemi.isNotEmpty() }
        assertFalse(esiste("Cosa vuoi fare?"))
        compose.onNodeWithText("Nuovo").tocca()
        compose.onNodeWithText("Cosa vuoi fare?").assertExists()
        listOf("Lezione da ascoltare", "Schema", "Riassunto", "Verifica simulata").forEach { compose.onNodeWithText(it).assertIsEnabled() }
        rigaLampo().assertIsEnabled()
        compose.onNodeWithText("Te la spiego a voce, con le domande lungo la strada").assertExists()
        compose.onNodeWithText("Da leggere: i punti chiave, con le immagini").assertExists()
        compose.onNodeWithText("Da uno schema già pronto: per punti o discorsivo").assertExists()
        compose.onNodeWithText("Come quella del prof, poi la correggo").assertExists()
        // Storia ha 3 schede: la frase come la scheda di Da ripassare.
        assertEquals(2, compose.onAllNodesWithText("3 schede · circa 1 min").fetchSemanticsNodes().size)
        // Le cinque righe una sotto l'altra, nell'ordine della guida.
        val ordine = listOf("Lezione da ascoltare", "Schema", "Riassunto", "Verifica simulata").map(::alto) +
            rigaLampo().fetchSemanticsNode().positionInRoot.y
        assertTrue(ordine.toString(), ordine.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun ogniRigaApreLaSuaPaginaEIlFoglioSiChiude() {
        val aperte = mutableListOf<String>()
        val vm = materia()
        mostra {
            MateriaScreen(
                vm,
                AzioniMateria(
                    nuovaLezione = { aperte += "lezione $it" },
                    nuovoSchema = { aperte += "schema $it" },
                    nuovaSimulata = { aperte += "simulata $it" },
                    lampo = { aperte += "lampo" },
                    apriRiassunti = { aperte += "riassunti ${it.id}" },
                ),
                esempio = false, senzaRete = false, scaricate = emptySet(),
            )
        }
        aspettaMateria(vm)
        compose.waitUntil(5_000) { vm.stato.value.schemi.isNotEmpty() && vm.stato.value.prossime.isNotEmpty() }
        val data = vm.stato.value.prossime.first().id
        listOf("Lezione da ascoltare", "Schema", "Verifica simulata", "Riassunto").forEach { riga ->
            compose.onNodeWithText("Nuovo").tocca()
            compose.onNodeWithText(riga).tocca()
            assertFalse("dopo $riga il foglio è chiuso", esiste("Cosa vuoi fare?"))
            assertFalse(esiste("Da quale schema?"))
        }
        compose.onNodeWithText("Nuovo").tocca()
        rigaLampo().tocca()
        assertFalse(esiste("Cosa vuoi fare?"))
        // Lezione, Schema e Verifica simulata partono con la prossima data; con un solo schema
        // pronto Riassunto apre subito quello (niente passo 2).
        assertEquals(
            listOf("lezione $data", "schema $data", "simulata $data", "riassunti ${SorgenteEsempio.SCHEMA_STORIA}", "lampo"),
            aperte,
        )
    }

    @Test
    fun riassuntoConPiuSchemiChiedeDaQuale() {
        val conPiuSchemi = object : Sorgente by esempio {
            override suspend fun schemi(materiaId: Long): Lettura<List<Schema>> = Lettura(
                listOf(
                    Schema(41, materiaId, titolo = "Augusto", forma = "capitoli", stato = "pronto", prontoIl = "2026-10-20T10:00:00Z"),
                    Schema(42, materiaId, titolo = "Nerone", forma = "schede", livello = "essenziale", stato = "pronto", prontoIl = "2026-10-26T10:00:00Z"),
                    Schema(43, materiaId, titolo = "Tiberio", stato = "in_preparazione"),
                ),
            )
        }
        var scelto: Schema? = null
        val vm = materia(sorgente = conPiuSchemi)
        mostra { MateriaScreen(vm, AzioniMateria(apriRiassunti = { scelto = it }), esempio = false, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.waitUntil(5_000) { vm.stato.value.schemi.size == 3 }
        compose.onNodeWithText("Nuovo").tocca()
        compose.onNodeWithText("Riassunto").tocca()
        // Passo 2 nello stesso foglio: titolo nuovo, freccia indietro, solo gli schemi pronti.
        compose.onNodeWithText("Da quale schema?").assertExists()
        assertFalse(esiste("Cosa vuoi fare?"))
        assertNull(scelto)
        compose.onNodeWithText("A capitoli · Completo").assertExists()
        compose.onNodeWithText("Schede per opera · Essenziale").assertExists()
        assertFalse(esiste("Tiberio"))
        assertTrue("il più recente prima", alto("Nerone") < alto("Augusto"))
        // La freccia torna alle cinque righe (è l'ultima: c'è anche quella della pagina).
        compose.onAllNodesWithContentDescription("Indietro").onLast().tocca()
        compose.onNodeWithText("Cosa vuoi fare?").assertExists()
        assertFalse(esiste("Da quale schema?"))
        compose.onNodeWithText("Riassunto").tocca()
        compose.onNodeWithText("Augusto").tocca()
        assertEquals(41L, scelto?.id)
        assertFalse(esiste("Da quale schema?"))
        assertFalse(esiste("Cosa vuoi fare?"))
    }

    @Test
    fun senzaReteLeRigheSpenteDiconoPercheERipassoLampoResta() {
        var lampo = false
        val vm = materia()
        mostra { MateriaScreen(vm, AzioniMateria(lampo = { lampo = true }), esempio = false, senzaRete = true, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.waitUntil(5_000) { vm.stato.value.lampoDisponibile }
        compose.onNodeWithText("Nuovo").assertIsEnabled().tocca()
        listOf("Lezione da ascoltare", "Schema", "Riassunto", "Verifica simulata").forEach { riga ->
            compose.onNode(hasText(riga) and hasText("Serve la rete")).assertIsNotEnabled()
        }
        // Le schede sono già sul telefono: il ripasso parte anche senza rete (§35.5).
        rigaLampo().assertIsEnabled().tocca()
        assertTrue(lampo)
        assertFalse(esiste("Cosa vuoi fare?"))
    }

    @Test
    fun senzaSchemiESenzaSchedeLeRigheDiconoPerche() {
        val vm = materia(matematica)
        mostra { MateriaScreen(vm, AzioniMateria(), esempio = false, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.onNodeWithText("Nuovo").tocca()
        compose.onNode(hasText("Riassunto") and hasText("Prima serve uno schema pronto")).assertIsNotEnabled()
        compose.onNode(hasText("Ripasso lampo") and hasText("Niente per oggi")).assertIsNotEnabled()
        compose.onNodeWithText("Lezione da ascoltare").assertIsEnabled()
        compose.onNodeWithText("Schema").assertIsEnabled()
        compose.onNodeWithText("Verifica simulata").assertIsEnabled()
    }

    // --- Riassunto: la lettura dello schema col foglio già aperto (§43.3) ------------------------

    @Test
    fun loSchemaChiestoDaRiassuntoSiApreColFoglioDeiRiassunti() {
        val vm = SchemaViewModel(esempio, SorgenteEsempio.SCHEMA_STORIA)
        vm.chiediRiassunti()
        mostra { LetturaSchemaScreen(vm, NonHoCapitoViewModel(esempio), AzioniSchema(), esempio, esempio = true, senzaRete = false) }
        compose.waitUntil(10_000) { esiste("Che riassunto vuoi?") }
        assertFalse("la richiesta vale una volta", vm.stato.value.chiediRiassunti)
    }

    @Test
    fun loSchemaApertoNormalmenteNonApreIlFoglio() {
        val vm = SchemaViewModel(esempio, SorgenteEsempio.SCHEMA_STORIA)
        mostra { LetturaSchemaScreen(vm, NonHoCapitoViewModel(esempio), AzioniSchema(), esempio, esempio = true, senzaRete = false) }
        compose.waitUntil(10_000) { vm.stato.value.schema != null }
        compose.waitForIdle()
        assertFalse(esiste("Che riassunto vuoi?"))
    }

    // --- §43.4 Visita guidata ----------------------------------------------------------------------

    @Test
    fun nellaVisitaNuovoPortaAllaRigaGiusta() {
        val visita = Visita()
        var lezione = false
        val vm = materia()
        mostra(visita) { MateriaScreen(vm, AzioniMateria(nuovaLezione = { lezione = true }), esempio = true, senzaRete = false, scaricate = emptySet()) }
        aspettaMateria(vm)
        compose.runOnIdle {
            visita.inizia()
            repeat(5) { if (visita.corrente.pratico) visita.azione(visita.corrente.azioneGiusta!!) else visita.avanti() }
        }
        compose.waitForIdle()
        assertEquals(Bersagli.NUOVO, visita.corrente.bersaglio)
        assertTrue("Nuovo è segnato per il riflettore", visita.bersagli.containsKey(Bersagli.NUOVO))
        compose.onNodeWithText("Nuovo").tocca()
        compose.onNodeWithText("Cosa vuoi fare?").assertExists()
        // Col foglio aperto il riflettore passa alla riga Lezione da ascoltare.
        assertEquals(Bersagli.RIGA_LEZIONE, visita.bersaglioTemporaneo)
        assertTrue(visita.bersagli.containsKey(Bersagli.RIGA_LEZIONE))
        assertEquals(5, visita.indice)
        // Un'altra riga non fa niente.
        compose.onNodeWithText("Schema").tocca()
        assertEquals(5, visita.indice)
        compose.onNodeWithText("Cosa vuoi fare?").assertExists()
        compose.onNodeWithText("Lezione da ascoltare").tocca()
        assertEquals(6, visita.indice)
        assertFalse("la pagina dopo la apre la visita", lezione)
        assertFalse(esiste("Cosa vuoi fare?"))
        assertNull(visita.bersaglioTemporaneo)
    }
}
