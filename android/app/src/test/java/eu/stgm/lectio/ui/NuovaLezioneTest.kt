package eu.stgm.lectio.ui

import eu.stgm.lectio.Esempi
import eu.stgm.lectio.RegolaMain
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.aspetta
import eu.stgm.lectio.dati.CacheMemoria
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.logica.Indicazioni
import eu.stgm.lectio.logica.TipoLezione
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.materiale.MaterialeViewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Nuova lezione v1.4 (contratto §16, aspetto.md §6) contro un server finto: caselle che
 * seguono i suggeriti finché Andrea non le tocca, corpi di stima e creazione, quando
 * Prepara è spento. E il PATCH del materiale con i soli campi cambiati.
 */
class NuovaLezioneTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()

    @After
    fun chiudi() = server.close()

    private fun corpo(metodoPercorso: String, indice: Int = -1): JsonObject {
        val r = server.richiesteA(metodoPercorso)
        return JsonLectio.parseToJsonElement((if (indice < 0) r.last() else r[indice]).body.readUtf8()).jsonObject
    }

    /** Storia con due appunti e una verifica; i suggeriti cambiano con l'argomento. */
    private fun preparaServer(stima: String = Esempi.STIMA) {
        server.rispondi("GET /api/materie", """[{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi("GET /api/eventi", "[]")
        server.rispondi(
            "GET /api/materie/1/materiale",
            """[{"id":21,"materia_id":1,"tipo":"verifica","titolo":"Verifica su Augusto","stato":"pronto"},
               {"id":23,"materia_id":1,"tipo":"appunti","titolo":"Augusto","stato":"pronto"},
               {"id":24,"materia_id":1,"tipo":"libro","titolo":"Tiberio","stato":"pronto"},
               {"id":25,"materia_id":1,"tipo":"appunti","titolo":"Foto nuova","stato":"in_lettura"}]""",
        )
        server.rispondi("GET /api/materie/1/suggeriti?argomento=Augusto", """{"argomento":"Augusto","parole":["augusto"],"suggeriti":[21,23]}""")
        server.rispondi("GET /api/materie/1/suggeriti?argomento=Tiberio", """{"argomento":"Tiberio","parole":["tiberio"],"suggeriti":[24]}""")
        server.rispondi("GET /api/voci", """[{"id":"mistral:voice_482","nome":"Lorenzo","tipo":"espressiva","disponibile":true},
            {"id":"azure:it-IT-IsabellaNeural","nome":"Isabella","tipo":"gratis","disponibile":false}]""")
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2,"durata_predefinita":20}""")
        server.rispondi("POST /api/lezioni/stima", stima)
    }

    private fun vm(attesa: Long = 0) =
        NuovaLezioneViewModel(SorgenteServer(server.client(), CacheMemoria()), 1, null, oggi = { LocalDate.of(2026, 11, 4) }, attesaSuggeritiMs = attesa).also {
            it.carica()
            aspetta { it.stato.value.pronto }
        }

    @Test
    fun finCheNonSiToccaLeCaselleSeguonoISuggeriti() {
        preparaServer()
        val vm = vm()
        assertTrue(vm.stato.value.auto)
        vm.argomento("Augusto")
        aspetta { vm.stato.value.scelti == setOf(21L, 23L) }
        vm.argomento("Tiberio")
        aspetta { vm.stato.value.scelti == setOf(24L) }
        // Una casella toccata: da qui restano come le mette Andrea.
        vm.tocca(23)
        assertFalse(vm.stato.value.auto)
        assertEquals(setOf(23L, 24L), vm.stato.value.scelti)
        vm.argomento("Augusto")
        aspetta { vm.stato.value.suggeriti == setOf(21L, 23L) }
        assertEquals("i suggeriti cambiano, le caselle no", setOf(23L, 24L), vm.stato.value.scelti)
        // "Sull'argomento" torna ai suggeriti.
        vm.sullArgomento()
        assertTrue(vm.stato.value.auto)
        assertEquals(setOf(21L, 23L), vm.stato.value.scelti)
        // "Tutti" = tutto il materiale pronto (non quello in lettura); "Nessuno" toglie tutto.
        vm.tutti()
        assertEquals(setOf(21L, 23L, 24L), vm.stato.value.scelti)
        assertFalse(vm.stato.value.auto)
        vm.nessuno()
        assertTrue(vm.stato.value.scelti.isEmpty())
        // Il materiale in lettura non si spunta.
        vm.tocca(25)
        assertTrue(vm.stato.value.scelti.isEmpty())
    }

    @Test
    fun iSuggeritiSiChiedono400msDopoLUltimaLettera() {
        preparaServer()
        val vm = vm(attesa = 400)
        listOf("A", "Au", "Aug", "Augu", "August", "Augusto").forEach {
            vm.argomento(it)
            Thread.sleep(40)
        }
        assertTrue("non prima dei 400 ms", server.richiesteA("GET /api/materie/1/suggeriti").isEmpty())
        aspetta { vm.stato.value.scelti == setOf(21L, 23L) }
        Thread.sleep(200)
        assertEquals(listOf("/api/materie/1/suggeriti?argomento=Augusto"), server.richiesteA("GET /api/materie/1/suggeriti").map { it.path })
    }

    @Test
    fun unaRispostaVecchiaNonVale() {
        preparaServer()
        // Augusto risponde tardi: intanto l'argomento è diventato Tiberio.
        server.rispondi(
            "GET /api/materie/1/suggeriti?argomento=Augusto",
            MockResponse().setBody("""{"argomento":"Augusto","suggeriti":[21,23]}""").setHeader("Content-Type", "application/json").setBodyDelay(600, TimeUnit.MILLISECONDS),
        )
        val vm = vm()
        vm.argomento("Augusto")
        aspetta { server.richiesteA("GET /api/materie/1/suggeriti").isNotEmpty() }
        vm.argomento("Tiberio")
        aspetta { vm.stato.value.scelti == setOf(24L) }
        Thread.sleep(900)
        assertEquals(setOf(24L), vm.stato.value.scelti)
        assertEquals(setOf(24L), vm.stato.value.suggeriti)
    }

    @Test
    fun stimaECreazioneMandanoTipoIndicazioniEMateriali() {
        preparaServer()
        val vm = vm()
        vm.argomento("Augusto")
        aspetta { vm.stato.value.stimaEspressiva != null }
        val stima = corpo("POST /api/lezioni/stima")
        assertEquals("[21,23]", stima["materiali"].toString())
        assertEquals("spiegazione", stima["tipo"]!!.jsonPrimitive.content)
        assertEquals("", stima["indicazioni"]!!.jsonPrimitive.content)
        assertEquals("solo le voci attive", 1, server.richiesteA("POST /api/lezioni/stima").size)

        // Tipo e indicazioni non rifanno la stima (il costo non cambia).
        vm.tipo(TipoLezione.RIPASSO)
        vm.indicazioni("  Insisti su Seiano.  ")
        vm.spunto(Indicazioni.SPUNTI.last().frase)
        Thread.sleep(150)
        assertEquals(1, server.richiesteA("POST /api/lezioni/stima").size)
        assertEquals("Insisti su Seiano. Usa tanti esempi concreti.", vm.stato.value.indicazioni)

        // La durata sì.
        vm.durata(30)
        aspetta { server.richiesteA("POST /api/lezioni/stima").size == 2 }
        val seconda = corpo("POST /api/lezioni/stima")
        assertEquals("30", seconda["durata_min"]!!.jsonPrimitive.content)
        assertEquals("ripasso", seconda["tipo"]!!.jsonPrimitive.content)
        assertEquals("Insisti su Seiano. Usa tanti esempi concreti.", seconda["indicazioni"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/lezioni", Esempi.LEZIONE, 201)
        vm.saltabili(true)
        vm.prepara()
        aspetta { vm.stato.value.creata != null }
        val crea = corpo("POST /api/lezioni")
        assertEquals("Augusto", crea["argomento"]!!.jsonPrimitive.content)
        assertEquals("[21,23]", crea["materiali"].toString())
        assertEquals("ripasso", crea["tipo"]!!.jsonPrimitive.content)
        assertEquals("Insisti su Seiano. Usa tanti esempi concreti.", crea["indicazioni"]!!.jsonPrimitive.content)
        assertEquals("30", crea["durata_min"]!!.jsonPrimitive.content)
        assertEquals("mistral:voice_482", crea["voce"]!!.jsonPrimitive.content)
        assertEquals("true", crea["domande_saltabili"]!!.jsonPrimitive.content)
        assertFalse("nessuna data scelta", crea.containsKey("evento_id"))
    }

    @Test
    fun indicazioniTagliateA600() {
        preparaServer()
        val vm = vm()
        vm.indicazioni("x".repeat(700))
        assertEquals(600, vm.stato.value.indicazioni.length)
    }

    @Test
    fun quandoPreparaESpento() {
        preparaServer()
        val vm = vm()
        // Argomento vuoto.
        assertEquals("Manca l'argomento", vm.stato.value.manca)
        assertEquals("Manca l'argomento", vm.stato.value.riassunto)
        assertFalse(vm.stato.value.puoPreparare)
        // Argomento, ma niente spuntato: niente stima.
        vm.argomento("Augusto")
        aspetta { vm.stato.value.stimaEspressiva != null }
        assertTrue(vm.stato.value.puoPreparare)
        assertEquals("Spiegazione · 20 min · 2 materiali", vm.stato.value.riassunto)
        assertEquals("mese: 1,05 € di 2 €", vm.stato.value.rigaMese)
        vm.nessuno()
        assertEquals("Scegli almeno un materiale", vm.stato.value.manca)
        assertFalse(vm.stato.value.puoPreparare)
        // Senza materiale niente stima: niente cifra vecchia sul pulsante, solo lo speso del mese.
        assertEquals("Prepara la lezione", vm.stato.value.costo.etichettaPulsante)
        assertEquals("mese: 0,80 € di 2 €", vm.stato.value.rigaMese)
        // La voce non attiva c'è, tratteggiata, e non si sceglie.
        val gratis = vm.stato.value.schedeVoci.single { it.tipo == eu.stgm.lectio.logica.TipoVoce.GRATIS }
        assertFalse(gratis.attiva)
        assertEquals("Isabella", gratis.voce?.nome)
    }

    @Test
    fun laStimaNonConsentitaSpegnePreparaColMotivoDelServer() {
        preparaServer(Esempi.STIMA.replace("\"consentita\": true", "\"consentita\": false").replace("\"motivo\": null", "\"motivo\": \"Sforerebbe il tetto di 2 €: usa la voce gratis\""))
        val vm = vm()
        vm.argomento("Augusto")
        aspetta { vm.stato.value.stimaEspressiva != null }
        assertNull(vm.stato.value.manca)
        assertFalse(vm.stato.value.puoPreparare)
        assertEquals("Sforerebbe il tetto di 2 €: usa la voce gratis", vm.stato.value.costo.blocco)
    }

    /** Le voci gratis del NAS: una scheda ciascuna, "pronta in circa N min", la stima con la voce toccata. */
    @Test
    fun piuVociGratisDelNas() {
        preparaServer()
        server.rispondi("GET /api/voci", """[{"id":"mistral:voice_482","nome":"Lorenzo","tipo":"espressiva","disponibile":true},
            {"id":"nas:giovanni","nome":"Giovanni","tipo":"gratis","fornitore":"nas","euro_al_minuto":0,"disponibile":true,"minuti_per_minuto":0.5},
            {"id":"nas:giovanni_grande","nome":"Giovanni grande","tipo":"gratis","fornitore":"nas","euro_al_minuto":0,"disponibile":true,"minuti_per_minuto":1.5},
            {"id":"nas:supertonic","nome":"Supertonic","tipo":"gratis","fornitore":"nas","euro_al_minuto":0,"disponibile":false,"minuti_per_minuto":1.2}]""")
        server.rispondi("POST /api/lezioni/stima", Esempi.STIMA.replace("}", ", \"minuti_preparazione\": 6}"))
        val vm = vm()
        vm.argomento("Augusto")
        aspetta { server.richiesteA("POST /api/lezioni/stima").size == 2 }
        val s = vm.stato.value
        assertEquals(listOf("Lorenzo", "Giovanni", "Giovanni grande", "Supertonic"), s.schedeVoci.map { it.voce?.nome })
        assertEquals(listOf(true, true, true, false), s.schedeVoci.map { it.attiva })
        assertEquals("la prima voce gratis attiva fa la stima", "nas:giovanni", s.voci.gratis?.id)
        assertEquals(listOf(true, false, false, false), s.schedeVoci.map { s.schedaScelta(it) })
        assertEquals(12, eu.stgm.lectio.logica.Costi.prontaIn(s.schedeVoci[1].voce, s.durata))
        assertNull("Lorenzo non lo dice", eu.stgm.lectio.logica.Costi.prontaIn(s.schedeVoci[0].voce, s.durata))
        aspetta { vm.stato.value.costo.minutiPreparazione == 6 }

        // Tocca "Giovanni grande": si sceglie quella e la stima gratis si rifà con la sua voce.
        vm.voce(s.schedeVoci[2].voce!!)
        aspetta { server.richiesteA("POST /api/lezioni/stima").size == 4 }
        val voci = server.richiesteA("POST /api/lezioni/stima").drop(2).map { JsonLectio.parseToJsonElement(it.body.readUtf8()).jsonObject["voce"]!!.jsonPrimitive.content }
        assertTrue(voci.contains("nas:giovanni_grande"))
        val dopo = vm.stato.value
        assertEquals(listOf(false, false, true, false), dopo.schedeVoci.map { dopo.schedaScelta(it) })
        assertEquals("nas:giovanni_grande", dopo.voceDaUsare?.id)
        // La voce non attiva non si sceglie.
        vm.voce(s.schedeVoci[3].voce!!)
        assertEquals("nas:giovanni_grande", vm.stato.value.voci.gratis?.id)

        server.rispondi("POST /api/lezioni", Esempi.LEZIONE, 201)
        aspetta { vm.stato.value.puoPreparare }
        vm.prepara()
        aspetta { vm.stato.value.creata != null }
        assertEquals("nas:giovanni_grande", corpo("POST /api/lezioni")["voce"]!!.jsonPrimitive.content)
    }

    @Test
    fun unServerSenzaSuggeritiFaComeTutti() {
        preparaServer()
        server.rispondi("GET /api/materie/1/suggeriti?argomento=Augusto", """{"detail":"Not Found"}""", 404)
        val vm = vm()
        vm.argomento("Augusto")
        aspetta { vm.stato.value.scelti == setOf(21L, 23L, 24L) }
        assertTrue(vm.stato.value.auto)
    }

    @Test
    fun laModalitaEsempioSuggerisceDaSola() {
        val esempio = SorgenteEsempio(LocalDate.of(2026, 10, 28))
        val vm = NuovaLezioneViewModel(esempio, SorgenteEsempio.MATERIA_STORIA, 3, oggi = { LocalDate.of(2026, 10, 28) })
        vm.carica()
        aspetta { vm.stato.value.stimaEspressiva != null }
        val s = vm.stato.value
        assertEquals("L'Impero romano", s.argomento)
        assertEquals(3L, s.eventoId)
        assertEquals(setOf(21L, 23L, 24L), s.scelti)
        assertEquals(6, s.pronti.size)
        assertTrue(s.puoPreparare)
        assertTrue(server.richieste.isEmpty())
    }

    // --- Un materiale: PATCH con i soli campi cambiati (contratto §16.3) -----------------------

    @Test
    fun ilMaterialeSalvaSoloICampiCambiati() {
        server.rispondi("GET /api/materiale/7", """{"id":7,"materia_id":1,"tipo":"appunti","titolo":"Foto 4 novembre","origine":"foto","nome_file":"IMG_1.jpg","caratteri":1976,"stato":"pronto","titolo_da":"file","testo":"Mirone di Eleutere."}""")
        server.rispondi("GET /api/materie", """[{"id":1,"nome":"Storia dell'Arte","prof":"Rossi","colore":"ambra"}]""")
        server.rispondi("PATCH /api/materiale/7", """{"id":7,"materia_id":1,"tipo":"appunti","titolo":"Mirone e il Discobolo","stato":"pronto","titolo_da":"utente"}""")
        val vm = MaterialeViewModel(SorgenteServer(server.client(), CacheMemoria()), 7, durataSalvato = 0)
        vm.carica()
        aspetta { vm.stato.value.materiale != null && vm.stato.value.materia != null }
        assertEquals("Storia dell'Arte", vm.stato.value.materia?.nome)
        assertFalse("niente cambiato, niente da salvare", vm.stato.value.puoSalvare)
        vm.salva()
        Thread.sleep(100)
        assertTrue(server.richiesteA("PATCH /api/materiale/7").isEmpty())

        vm.titolo("  Mirone   e il Discobolo ")
        // Dopo il PATCH si rilegge il materiale dal server.
        server.rispondi("GET /api/materiale/7", """{"id":7,"materia_id":1,"tipo":"appunti","titolo":"Mirone e il Discobolo","origine":"foto","nome_file":"IMG_1.jpg","caratteri":1976,"stato":"pronto","titolo_da":"utente","testo":"Mirone di Eleutere."}""")
        vm.salva()
        aspetta { server.richiesteA("PATCH /api/materiale/7").size == 1 && !vm.stato.value.salvando }
        assertEquals("""{"titolo":"Mirone e il Discobolo"}""", server.richiesteA("PATCH /api/materiale/7").single().body.readUtf8())
        aspetta { vm.stato.value.materiale?.titoloDa == "utente" }
        assertEquals(2, server.richiesteA("GET /api/materiale/7").size)
        assertEquals("Mirone di Eleutere.", vm.stato.value.materiale?.testo)

        vm.tipo(TipoMateriale.VERIFICA)
        vm.salva()
        aspetta { server.richiesteA("PATCH /api/materiale/7").size == 2 }
        assertEquals("""{"tipo":"verifica"}""", server.richiesteA("PATCH /api/materiale/7")[1].body.readUtf8())
    }

    @Test
    fun ilMaterialeMostraLErroreDelServer() {
        server.rispondi("GET /api/materiale/7", """{"id":7,"materia_id":1,"tipo":"appunti","titolo":"Foto","stato":"pronto"}""")
        server.rispondi("GET /api/materie", "[]")
        server.rispondi("PATCH /api/materiale/7", """{"errore":"Il titolo è troppo lungo","codice":"non_valido"}""", 422)
        val vm = MaterialeViewModel(SorgenteServer(server.client(), CacheMemoria()), 7, durataSalvato = 0)
        vm.carica()
        aspetta { vm.stato.value.materiale != null }
        vm.titolo("Nuovo")
        vm.salva()
        aspetta { vm.stato.value.errore != null }
        assertEquals("Il titolo è troppo lungo", vm.stato.value.errore)
        assertFalse(vm.stato.value.salvato)
    }
}
