package eu.stgm.lectio.ui

import eu.stgm.lectio.RegolaMain
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.aspetta
import eu.stgm.lectio.dati.CacheMemoria
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.logica.GenereFile
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.FileScelto
import eu.stgm.lectio.ui.materiale.MaterialeViewModel
import eu.stgm.lectio.ui.materiale.PassoAggiunta
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materie.CartellaViewModel
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.studio.GenereStudio
import eu.stgm.lectio.ui.studio.NuovoStudioViewModel
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Le cartelle nei ViewModel (contratto §27, aspetto.md §42): cosa parte verso il server
 * da Aggiungi e da Un materiale, e le pagine con i dati di esempio.
 */
class CartelleTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val oggi = LocalDate.of(2026, 10, 28)

    @After
    fun chiudi() = server.close()

    private fun sorgente() = SorgenteServer(server.client(), CacheMemoria())

    private fun preparaMateria() {
        server.rispondi("GET /api/materie", """[{"id":7,"nome":"Storia dell'Arte","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi(
            "GET /api/materie/7/cartelle",
            """[{"id":3,"materia_id":7,"nome":"Arte greca","file":1,"messi_da_lectio":1,"creata_il":"x"},
               {"id":4,"materia_id":7,"nome":"Arte romana","file":0,"messi_da_lectio":0,"creata_il":"x"}]""",
        )
    }

    // --- Aggiungi: CARTELLA (aspetto.md §42.6) ---------------------------------------------------

    @Test
    fun aggiungiDallaCartellaMandaLaCartella() {
        preparaMateria()
        server.rispondi("POST /api/materie/7/materiale", """{"id":20,"materia_id":7,"tipo":"appunti","titolo":"Partenone","stato":"in_lettura"}""", 201)
        val f = File.createTempFile("partenone", ".txt").apply { writeText("Il Partenone") }
        try {
            val vm = AggiuntaViewModel(sorgente(), null)
            vm.apri(RichiestaAggiunta(7, PassoAggiunta.FILE, file = listOf(FileScelto("content://x/1", f.absolutePath, "text/plain", GenereFile.TESTO)), cartellaId = 3))
            aspetta { vm.stato.value.cartelle.size == 2 }
            assertEquals("aperto dalla cartella: è già scelta", 3L, vm.stato.value.richiesta.cartellaId)
            vm.inviaFile()
            aspetta { vm.stato.value.fatto }
            val corpo = server.richiesteA("POST /api/materie/7/materiale").single().body.readUtf8()
            assertTrue(corpo, Regex("name=\"cartella_id\"(\\r\\n[^\\r\\n]+)*\\r\\n\\r\\n3\\r\\n").containsMatchIn(corpo))
        } finally {
            f.delete()
        }
    }

    @Test
    fun decideLectioNonMandaLaCartella() {
        preparaMateria()
        server.rispondi("POST /api/materie/7/materiale", """{"id":21,"materia_id":7,"tipo":"appunti","titolo":"Fidia","stato":"pronto"}""", 201)
        val vm = AggiuntaViewModel(sorgente(), null)
        // Dalla materia: parte da "Decide Lectio".
        vm.apri(RichiestaAggiunta(7, PassoAggiunta.APPUNTI))
        aspetta { vm.stato.value.cartelle.size == 2 }
        assertNull(vm.stato.value.richiesta.cartellaId)
        vm.inviaTesto(TipoMateriale.APPUNTI, "Fidia", "Fidia e il Partenone.")
        aspetta { vm.stato.value.fatto }
        val senza = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/materie/7/materiale").single().body.readUtf8()).jsonObject
        assertFalse(senza.containsKey("cartella_id"))

        // Scelta a mano una cartella: si manda il suo numero.
        vm.apri(RichiestaAggiunta(7, PassoAggiunta.APPUNTI))
        aspetta { vm.stato.value.cartelle.size == 2 }
        vm.cartella(4)
        vm.inviaTesto(TipoMateriale.APPUNTI, "Il Colosseo", "Anfiteatro Flavio.")
        aspetta { server.richiesteA("POST /api/materie/7/materiale").size == 2 }
        val con = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/materie/7/materiale")[1].body.readUtf8()).jsonObject
        assertEquals("4", con["cartella_id"].toString())
    }

    @Test
    fun cambiareMateriaRipartedaDecideLectio() {
        val esempio = SorgenteEsempio(oggi)
        val vm = AggiuntaViewModel(esempio, null)
        vm.apri(RichiestaAggiunta(SorgenteEsempio.MATERIA_STORIA, PassoAggiunta.APPUNTI, cartellaId = SorgenteEsempio.CARTELLA_STORIA))
        aspetta { vm.stato.value.cartelle.isNotEmpty() }
        assertEquals(SorgenteEsempio.CARTELLA_STORIA, vm.stato.value.richiesta.cartellaId)
        vm.cambia(vm.stato.value.richiesta.copy(materiaId = 5))
        assertNull(vm.stato.value.richiesta.cartellaId)
        aspetta { vm.stato.value.cartelle.isEmpty() }
    }

    // --- Un materiale: CARTELLA e il PATCH (aspetto.md §42.6) -----------------------------------

    @Test
    fun unMaterialeSpostaConCartellaIdONull() {
        preparaMateria()
        server.rispondi("GET /api/materiale/12", """{"id":12,"materia_id":7,"tipo":"appunti","titolo":"Il Partenone","stato":"pronto","cartella_id":3,"cartella_da":"lectio"}""")
        server.rispondi("PATCH /api/materiale/12", """{"id":12,"materia_id":7,"tipo":"appunti","titolo":"Il Partenone","stato":"pronto","cartella_id":4,"cartella_da":"utente"}""")
        val vm = MaterialeViewModel(sorgente(), 12, durataSalvato = 0)
        vm.carica()
        aspetta { vm.stato.value.materiale != null && vm.stato.value.cartelle.size == 2 }
        assertEquals(3L, vm.stato.value.cartellaId)
        assertEquals("Messo qui da Lectio", vm.stato.value.notaCartella)
        assertFalse(vm.stato.value.puoSalvare)

        vm.cartella(4)
        assertNull("la nota vale solo per la cartella scelta da Lectio", vm.stato.value.notaCartella)
        server.rispondi("GET /api/materiale/12", """{"id":12,"materia_id":7,"tipo":"appunti","titolo":"Il Partenone","stato":"pronto","cartella_id":4,"cartella_da":"utente"}""")
        vm.salva()
        aspetta { server.richiesteA("PATCH /api/materiale/12").size == 1 && !vm.stato.value.salvando }
        assertEquals("""{"cartella_id":4}""", server.richiesteA("PATCH /api/materiale/12")[0].body.readUtf8())
        aspetta { vm.stato.value.materiale?.cartellaId == 4L }

        // "Fuori": si manda proprio null.
        vm.cartella(null)
        vm.salva()
        aspetta { server.richiesteA("PATCH /api/materiale/12").size == 2 }
        assertEquals("""{"cartella_id":null}""", server.richiesteA("PATCH /api/materiale/12")[1].body.readUtf8())
    }

    // --- Pagine con i dati di esempio (aspetto.md §42.9) ------------------------------------------

    @Test
    fun laMateriaDiEsempioHaUnaCartellaConDueFile() {
        val vm = MateriaViewModel(SorgenteEsempio(oggi), SorgenteEsempio.MATERIA_STORIA, oggi = { oggi })
        vm.carica()
        aspetta { vm.stato.value.cartelle.isNotEmpty() && vm.stato.value.materiale.isNotEmpty() }
        val c = vm.stato.value.cartelle.single()
        assertEquals("Imperatori", c.nome)
        assertEquals(2, c.file)
        assertEquals(1, c.messiDaLectio)
        // Nella scheda Materiale solo quelli fuori dalle cartelle.
        assertEquals(listOf(21L, 23L, 25L, 26L), vm.stato.value.sciolti.map { it.id }.sorted())
    }

    @Test
    fun nuovaCartellaEPoiSiApre() {
        val vm = MateriaViewModel(SorgenteEsempio(oggi), SorgenteEsempio.MATERIA_STORIA, oggi = { oggi })
        vm.carica()
        aspetta { vm.stato.value.cartelle.isNotEmpty() }
        var errore: String? = "non chiamato"
        var aperta: Long? = null
        vm.creaCartella("  Augusto  ", { errore = it }) { aperta = it.id }
        aspetta { aperta != null }
        assertNull(errore)
        aspetta { vm.stato.value.cartelle.map { it.nome } == listOf("Augusto", "Imperatori") }
        // Doppia (senza badare alle maiuscole): la frase del server, niente pagina.
        aperta = null
        vm.creaCartella("imperatori", { errore = it }) { aperta = it.id }
        aspetta { errore != null }
        assertEquals("C'è già una cartella con questo nome in questa materia.", errore)
        assertNull(aperta)
    }

    @Test
    fun mettiDeiFileQuiPartedaiPropostiESposta() {
        val esempio = SorgenteEsempio(oggi)
        val vm = CartellaViewModel(esempio, SorgenteEsempio.CARTELLA_STORIA, SorgenteEsempio.MATERIA_STORIA) { oggi }
        vm.carica()
        aspetta { vm.stato.value.caricata && vm.stato.value.cartella != null }
        assertEquals(listOf(24L, 22L), vm.stato.value.dentro.map { it.id })
        assertEquals(4, vm.stato.value.candidati.size)

        vm.apriMetti()
        aspetta { vm.stato.value.metti?.inAttesa == false }
        // "Imperatori" non ha parole in comune col materiale fuori: nessuno spuntato.
        assertTrue(vm.stato.value.metti!!.scelti.isEmpty())
        vm.toccaMetti(23)
        vm.toccaMetti(25)
        vm.toccaMetti(25)
        assertEquals(setOf(23L), vm.stato.value.metti!!.scelti)
        vm.metti()
        aspetta { vm.stato.value.metti == null && vm.stato.value.conferma != null }
        assertEquals("Fatto: 1 file in Imperatori", vm.stato.value.conferma)
        aspetta { vm.stato.value.dentro.size == 3 }
    }

    @Test
    fun eliminareTenendoIFileOConIFile() {
        val esempio = SorgenteEsempio(oggi)
        val solo = CartellaViewModel(esempio, SorgenteEsempio.CARTELLA_STORIA, SorgenteEsempio.MATERIA_STORIA) { oggi }
        solo.carica()
        aspetta { solo.stato.value.caricata }
        solo.elimina(conIFile = false)
        aspetta { solo.stato.value.eliminata != null }
        assertEquals("Cartella eliminata", solo.stato.value.eliminata)
        val materia = MateriaViewModel(esempio, SorgenteEsempio.MATERIA_STORIA, oggi = { oggi })
        materia.carica()
        aspetta { materia.stato.value.materiale.isNotEmpty() }
        assertTrue("i file restano, fuori", materia.stato.value.cartelle.isEmpty())
        assertEquals(6, materia.stato.value.sciolti.size)

        val altro = SorgenteEsempio(oggi)
        val anche = CartellaViewModel(altro, SorgenteEsempio.CARTELLA_STORIA, SorgenteEsempio.MATERIA_STORIA) { oggi }
        anche.carica()
        aspetta { anche.stato.value.caricata }
        anche.elimina(conIFile = true)
        aspetta { anche.stato.value.eliminata != null }
        assertEquals("Cartella e 2 file eliminati", anche.stato.value.eliminata)
    }

    // --- Scelta del materiale: pillole CARTELLE (aspetto.md §42.7) --------------------------------

    @Test
    fun laPillolaCartellaSpuntaETogliTutto() {
        val vm = NuovaLezioneViewModel(SorgenteEsempio(oggi), SorgenteEsempio.MATERIA_STORIA, 3, oggi = { oggi }, attesaSuggeritiMs = 0)
        vm.carica()
        aspetta { vm.stato.value.pronto && vm.stato.value.cartelle.isNotEmpty() && vm.stato.value.suggeriti.isNotEmpty() }
        assertEquals(setOf(21L, 23L, 24L), vm.stato.value.scelti)
        vm.cartella(SorgenteEsempio.CARTELLA_STORIA)
        assertFalse("conta come un tocco", vm.stato.value.auto)
        assertEquals(setOf(21L, 22L, 23L, 24L), vm.stato.value.scelti)
        vm.cartella(SorgenteEsempio.CARTELLA_STORIA)
        assertEquals(setOf(21L, 23L), vm.stato.value.scelti)

        val studio = NuovoStudioViewModel(SorgenteEsempio(oggi), GenereStudio.SCHEMA, SorgenteEsempio.MATERIA_STORIA, null, null, oggi = { oggi }, attesaSuggeritiMs = 0, attesaStimaMs = 0)
        studio.carica()
        aspetta { studio.stato.value.pronto && studio.stato.value.cartelle.isNotEmpty() }
        studio.nessuno()
        studio.cartella(SorgenteEsempio.CARTELLA_STORIA)
        assertEquals(setOf(22L, 24L), studio.stato.value.scelti)
    }
}
