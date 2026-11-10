package eu.stgm.lectio.rete

import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.dati.CorpoChiarimento
import eu.stgm.lectio.dati.CorpoSalvaSimulata
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.NuovaSimulata
import eu.stgm.lectio.dati.NuovoSchema
import eu.stgm.lectio.dati.Oggi
import eu.stgm.lectio.dati.RichiestaStimaSchema
import eu.stgm.lectio.dati.RichiestaStimaSimulata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Schemi, riassunti, "Non ho capito", Ripasso lampo e simulate (contratto §18-21, §26) letti dal client vero. */
class ContrattoStudioTest {
    @get:Rule
    val cartella = TemporaryFolder()

    private val server = ServerFinto()
    private val client = server.client()

    @After
    fun chiudi() = server.close()

    private inline fun <T> ok(e: Esito<T>, verifica: (T) -> Unit) {
        assertTrue("non ok: $e", e is Esito.Ok)
        verifica((e as Esito.Ok).dato)
    }

    private fun corpo(percorso: String): JsonObject =
        JsonLectio.parseToJsonElement(server.richiesteA(percorso).last().body.clone().readUtf8()).jsonObject

    private val schema = """{"id": 3, "materia_id": 7, "evento_id": null, "argomento": "La scultura", "titolo": "La scultura classica",
 "forma": "capitoli", "livello": "completo", "indicazioni": "", "materiali": [1, 2], "stato": "pronto", "fase": null, "fatto": 8, "totale": 8,
 "frase": null, "errore": null, "nota": null, "stima_eur": 0.08, "creato_il": "2026-11-10T10:00:00+01:00", "pronto_il": "2026-11-10T10:04:00+01:00",
 "carte": 12, "riassunti": {"stima_eur": 0.01, "fatti": [{"id": 4, "forma": "misto", "stato": "pronto"}]},
 "contenuto": {"forma": "capitoli",
  "sezioni": [{"n": 1, "titolo": "Mirone", "campi": [{"nome": "Autore", "valore": "Mirone"}],
   "blocchi": [
     {"tipo": "paragrafo", "titolo": "Descrizione", "testo": "Il **Discobolo** è in bronzo.", "fonti": ["B1"]},
     {"tipo": "video", "url": "un tipo nuovo che questa versione non conosce"},
     {"tipo": "punti", "titolo": null, "voci": ["uno"], "fonti": ["B1"]},
     {"tipo": "passaggi", "titolo": null, "voci": ["causa", "effetto"], "fonti": ["B2"]},
     {"tipo": "definizione", "termine": "chiasmo", "testo": "una posizione", "fonti": ["B2"]},
     {"tipo": "tabella", "titolo": "Date", "intestazione": ["Anno", "Evento"], "righe": [["1122", "fatto"]], "fonti": []},
     {"tipo": "prof", "testo": "Chiesto nella verifica del 12/10.", "fonti": ["V1"]},
     {"tipo": "immagine", "n": 1, "didascalia": "Discobolo di Mirone", "credito": "Foto di esempio"}
   ]}],
  "fonti": {"B1": {"materiale_id": 1, "titolo": "Libro", "tipo": "libro", "pagina": 42, "etichetta": "Libro, pag. 42"}},
  "copertura": {"concetti": 42, "coperti": 41, "mancanti": ["il Doriforo"]},
  "avvisi": ["Per la parte 4 non ho trovato una foto giusta."]}}"""

    private val riassunto = """{"id": 4, "schema_id": 3, "materia_id": 7, "forma": "misto", "titolo": "La scultura", "stato": "pronto", "frase": null,
 "errore": null, "nota": null, "stima_eur": 0.01, "creato_il": "x", "pronto_il": "y", "parole": 480,
 "contenuto": {"parti": [{"titolo": null, "paragrafi": ["Uno **forte**."], "punti": ["due"]}]}}"""

    private val simulata = """{"id": 9, "materia_id": 7, "evento_id": null, "argomento": "Augusto", "titolo": "Verifica", "minuti": 50, "materiali": [1],
 "indicazioni": "", "stato": "corretta", "fase": null, "fatto": 0, "totale": 0, "frase": null, "errore": null, "nota": null, "stima_eur": 0.03,
 "creata_il": "x", "iniziata_il": "y", "consegnata_il": "z",
 "domande": [{"n": 1, "tipo": "aperta", "testo": "Perché?", "punti": 3, "sezione": "Aperte"}],
 "risposte": {"1": "Perché sì."},
 "esito": {"punti": 2.5, "punti_totali": 3, "voto_stimato": 6.5, "soglia": 6, "da_rifare": 1,
  "domande": [{"n": 1, "esito": "parziale", "punti": 2.5, "commento": "Quasi.", "mancava": ["il perché"], "risposta_attesa": "Così.",
   "fonti": [{"materiale_id": 1, "titolo": "Libro", "tipo": "libro", "pagina": 42, "etichetta": "Libro, pag. 42"}]},
   {"n": 2, "esito": "non_corretta", "punti": null, "commento": null, "mancava": [], "risposta_attesa": null, "fonti": []}]}}"""

    @Test
    fun stimaDelloSchemaConTuttiICampi() = runBlocking {
        server.rispondi("POST /api/schemi/stima", """{"costo_eur": 0.08, "spesa_mese_eur": 0.21, "impegnato_eur": 0, "tetto_eur": 2, "dopo_eur": 0.29,
 "consentita": true, "motivo": null, "si_azzera_il": "2026-12-01", "minuti_preparazione": 4, "immagini": 3}""")
        ok(client.stimaSchema(RichiestaStimaSchema(7, "La scultura classica", listOf(1, 2), "auto", "completo", ""))) {
            assertEquals(0.08, it.costoEur, 0.0)
            assertEquals(3, it.immagini)
            assertEquals(4, it.minutiPreparazione)
        }
        val b = corpo("POST /api/schemi/stima")
        assertEquals("7", b["materia_id"]!!.jsonPrimitive.content)
        assertEquals("auto", b["forma"]!!.jsonPrimitive.content)
        assertEquals("completo", b["livello"]!!.jsonPrimitive.content)
        assertEquals(2, b["materiali"]!!.jsonArray.size)
    }

    @Test
    fun loSchemaSiLeggeEIBlocchiSconosciutiSiSaltano() = runBlocking {
        server.rispondi("GET /api/schemi/3", schema)
        ok(client.schema(3)) {
            val blocchi = it.contenuto!!.sezioni[0].blocchi
            assertEquals(listOf("paragrafo", "punti", "passaggi", "definizione", "tabella", "prof", "immagine"), blocchi.map { b -> b.tipo })
            assertEquals("Il **Discobolo** è in bronzo.", blocchi[0].testo)
            assertEquals("chiasmo", blocchi[3].termine)
            assertEquals(listOf(listOf("1122", "fatto")), blocchi[4].righe)
            assertEquals(1, blocchi[6].n)
            assertEquals("Mirone", it.contenuto!!.sezioni[0].campi[0].valore)
            assertEquals("Libro, pag. 42", it.contenuto!!.fonti["B1"]?.etichetta)
            assertEquals(listOf("il Doriforo"), it.contenuto!!.copertura?.mancanti)
            assertEquals(1, it.contenuto!!.avvisi.size)
            assertEquals("misto", it.riassunti?.fatti?.single()?.forma)
            assertEquals(12, it.carte)
            assertTrue(it.pronto)
        }
    }

    @Test
    fun laCreazioneELeAzioniDegliSchemi() = runBlocking {
        server.rispondi("POST /api/schemi", """{"id": 3, "materia_id": 7, "argomento": "x", "stato": "in_coda"}""", 201)
        ok(client.creaSchema(NuovoSchema(7, 5, "x", listOf(1), "schede", "essenziale", "metti le date"))) { assertTrue(it.inLavoro) }
        assertEquals("5", corpo("POST /api/schemi")["evento_id"]!!.jsonPrimitive.content)

        server.rispondi("GET /api/schemi?materia_id=7", """[{"id": 3, "materia_id": 7, "stato": "errore", "errore": "Si è fermato", "contenuto": null}]""")
        ok(client.schemi(7)) { assertTrue(it.single().fallito) }

        server.rispondi("POST /api/schemi/3/annulla", """{"ok": true}""")
        server.rispondi("POST /api/schemi/3/riprova", """{"ok": true}""")
        server.rispondi("DELETE /api/schemi/3", """{"ok": true}""")
        assertTrue(client.annullaSchema(3) is Esito.Ok)
        assertTrue(client.riprovaSchema(3) is Esito.Ok)
        assertTrue(client.eliminaSchema(3) is Esito.Ok)
    }

    @Test
    fun leFotoDelloSchema() = runBlocking {
        val byte = byteArrayOf(1, 2, 3, 4)
        server.rispondi("GET /api/schemi/3/immagini/1", MockResponse().setBody(Buffer().write(byte)).setHeader("Content-Type", "image/jpeg"))
        val file = cartella.newFile("foto.jpg")
        assertTrue(client.immagineSchema(3, 1, file) is Esito.Ok)
        assertArrayEquals(byte, file.readBytes())

        server.rispondi("POST /api/schemi/3/immagini/1/cambia", """{"errore": "Non ne ho trovate altre", "codice": "non_trovato"}""", 404)
        val cambio = client.cambiaImmagine(3, 1)
        assertEquals(404, (cambio as Esito.Errore).stato)

        server.rispondi("DELETE /api/schemi/3/immagini/1", schema)
        ok(client.togliImmagine(3, 1)) { assertEquals(3L, it.id) }
    }

    @Test
    fun iPdfLiFaIlServer() = runBlocking {
        val pdf = "%PDF-finto".toByteArray()
        listOf("/api/schemi/3/pdf", "/api/simulate/9/pdf", "/api/riassunti/4/pdf").forEach {
            server.rispondi("GET $it", MockResponse().setBody(Buffer().write(pdf)).setHeader("Content-Type", "application/pdf"))
        }
        val a = cartella.newFile("a.pdf")
        val b = cartella.newFile("b.pdf")
        val c = cartella.newFile("c.pdf")
        assertTrue(client.pdfSchema(3, a) is Esito.Ok)
        assertTrue(client.pdfSimulata(9, b) is Esito.Ok)
        assertTrue(client.pdfRiassunto(4, c) is Esito.Ok)
        assertArrayEquals(pdf, c.readBytes())
    }

    @Test
    fun iRiassunti() = runBlocking {
        server.rispondi("POST /api/schemi/3/riassunti", riassunto, 201)
        ok(client.creaRiassunto(3, "misto")) { assertEquals(480, it.parole) }
        assertEquals("misto", corpo("POST /api/schemi/3/riassunti")["forma"]!!.jsonPrimitive.content)

        server.rispondi("GET /api/schemi/3/riassunti", "[$riassunto]")
        ok(client.riassunti(3)) {
            val parte = it.single().contenuto!!.parti.single()
            assertNull(parte.titolo)
            assertEquals(listOf("Uno **forte**."), parte.paragrafi)
            assertEquals(listOf("due"), parte.punti)
        }
        server.rispondi("GET /api/riassunti/4", riassunto)
        ok(client.riassunto(4)) { assertTrue(it.pronto) }

        server.rispondi("POST /api/riassunti/4/riprova", """{"ok": true}""")
        server.rispondi("DELETE /api/riassunti/4", """{"ok": true}""")
        assertTrue(client.riprovaRiassunto(4) is Esito.Ok)
        assertTrue(client.eliminaRiassunto(4) is Esito.Ok)
    }

    @Test
    fun nonHoCapitoENelDocumentoNonCeNonEUnErrore() = runBlocking {
        server.rispondi("POST /api/chiarimenti", """{"id": 1, "passaggio": "Il Discobolo", "domanda": "Perché?", "risposta": "Nei tuoi documenti non c'è.", "trovato": false, "fonti": [], "costo_eur": 0.003, "quando": "x"}""")
        ok(client.chiarisci(CorpoChiarimento(7, schemaId = 3, sezione = 1, passaggio = "Il Discobolo", domanda = "Perché?"))) {
            assertFalse(it.trovato)
            assertEquals("Nei tuoi documenti non c'è.", it.risposta)
        }
        val b = corpo("POST /api/chiarimenti")
        assertEquals("3", b["schema_id"]!!.jsonPrimitive.content)
        assertEquals("1", b["sezione"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/chiarimenti", """{"id": 2, "risposta": "Così.", "fonti": [{"materiale_id": 1, "etichetta": "Libro, pag. 42"}]}""")
        ok(client.chiarisci(CorpoChiarimento(7, lezioneId = 12, posizioneS = 312.0))) {
            assertTrue(it.trovato)
            assertEquals("Libro, pag. 42", it.fonti.single().etichetta)
        }
    }

    @Test
    fun ilRipassoLampo() = runBlocking {
        val carte = """{"carte": [{"id": 5, "materia_id": 7, "materia": "Storia", "colore": "ambra", "domanda": "Chi?", "risposta": "Augusto.", "fonti": [], "scatola": 2}], "da_fare": 8, "domani": 3}"""
        server.rispondi("GET /api/lampo?limite=12", carte)
        server.rispondi("GET /api/lampo?materia_id=7&limite=12", carte)
        ok(client.lampo()) { assertEquals(8, it.daFare); assertEquals(3, it.domani) }
        ok(client.lampo(7)) { assertEquals(2, it.carte.single().scatola) }

        server.rispondi("POST /api/lampo/5", """{"scatola": 3, "prossima": "2026-11-14", "da_fare": 7}""")
        ok(client.esitoLampo(5, "sapevo")) { assertEquals(7, it.daFare) }
        assertEquals("sapevo", corpo("POST /api/lampo/5")["esito"]!!.jsonPrimitive.content)

        server.rispondi("DELETE /api/lampo/5", """{"ok": true}""")
        assertTrue(client.togliCarta(5) is Esito.Ok)
    }

    @Test
    fun laVerificaSimulata() = runBlocking {
        server.rispondi("POST /api/simulate/stima", """{"costo_eur": 0.03, "spesa_mese_eur": 0.2, "tetto_eur": 2, "dopo_eur": 0.23, "consentita": true, "minuti_preparazione": 2}""")
        ok(client.stimaSimulata(RichiestaStimaSimulata(7, "Augusto", listOf(1), 50, ""))) { assertEquals(0, it.immagini) }
        assertEquals("50", corpo("POST /api/simulate/stima")["minuti"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/simulate", """{"id": 9, "materia_id": 7, "stato": "in_coda", "minuti": 50}""", 201)
        ok(client.creaSimulata(NuovaSimulata(7, null, "Augusto", listOf(1), 50, ""))) { assertTrue(it.inLavoro) }

        server.rispondi("GET /api/simulate?materia_id=7", """[{"id": 9, "materia_id": 7, "stato": "corretta", "esito": {"punti": 7, "punti_totali": 10, "voto_stimato": 6.5, "soglia": 6, "da_rifare": 1}}]""")
        ok(client.simulate(7)) { assertEquals(6.5, it.single().esito?.votoStimato) }

        server.rispondi("GET /api/simulate/9", simulata)
        ok(client.simulata(9)) {
            assertTrue(it.corretta)
            assertEquals(3.0, it.domande.single().punti, 0.0)
            assertEquals("non_corretta", it.esito!!.domande[1].esito)
            assertNull(it.esito!!.domande[1].punti)
            assertEquals("Perché sì.", it.risposte["1"])
        }

        server.rispondi("PATCH /api/simulate/9", simulata)
        ok(client.salvaSimulata(9, CorpoSalvaSimulata(mapOf("1" to "Perché sì."), iniziata = true))) { assertEquals(9L, it.id) }
        val patch = corpo("PATCH /api/simulate/9")
        assertEquals("Perché sì.", patch["risposte"]!!.jsonObject["1"]!!.jsonPrimitive.content)
        assertEquals("true", patch["iniziata"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/simulate/9/consegna", simulata)
        ok(client.consegnaSimulata(9)) { assertTrue(it.corretta) }
        server.rispondi("POST /api/simulate/9/annulla", """{"ok": true}""")
        server.rispondi("POST /api/simulate/9/riprova", """{"ok": true}""")
        server.rispondi("DELETE /api/simulate/9", """{"ok": true}""")
        assertTrue(client.annullaSimulata(9) is Esito.Ok)
        assertTrue(client.riprovaSimulata(9) is Esito.Ok)
        assertTrue(client.eliminaSimulata(9) is Esito.Ok)
    }

    @Test
    fun oggiPortaIlLampoEUnServerVecchioNo() {
        val o = JsonLectio.decodeFromString(Oggi.serializer(), """{"oggi": "2026-11-10", "lampo": {"da_fare": 8, "minuti": 3}}""")
        assertEquals(8, o.lampo.daFare)
        assertEquals(3, o.lampo.minuti)
        assertEquals(0, JsonLectio.decodeFromString(Oggi.serializer(), """{"oggi": "2026-11-10"}""").lampo.daFare)
    }
}
