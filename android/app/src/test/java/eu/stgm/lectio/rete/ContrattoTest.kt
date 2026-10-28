package eu.stgm.lectio.rete

import eu.stgm.lectio.Esempi
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.MaterialeScritto
import eu.stgm.lectio.dati.NuovaLezione
import eu.stgm.lectio.dati.RichiestaStima
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tutte le risposte del contratto (docs/contratto-api.md v1.0 + §15) lette dal
 * client vero contro un server finto, e le richieste come il server le vuole.
 */
class ContrattoTest {
    private val server = ServerFinto()
    private val client = server.client()

    @After
    fun chiudi() = server.close()

    private inline fun <T> ok(e: Esito<T>, verifica: (T) -> Unit) {
        assertTrue("non ok: $e", e is Esito.Ok)
        verifica((e as Esito.Ok).dato)
    }

    @Test
    fun versioneESalute() = runBlocking {
        server.rispondi("GET /api/versione", """{"server":"1.0.0","android":{"codice":2,"nome":"1.0.1","sha256":"ab12"},"pc":{"codice":1,"nome":"1.0.0","sha256":"cd"},"nota":"prova"}""")
        ok(client.versione()) { assertEquals(2, it.android?.codice); assertEquals("ab12", it.android?.sha256); assertNull(it.android?.byte) }
        server.rispondi("GET /api/salute", """{"ok": true, "versione": "1.0.0", "modalita_prova": true,
 "testo": {"stato": "prova", "fornitore": "finto"}, "voce_espressiva": {"stato": "non_disponibile", "fornitore": null},
 "voce_gratis": {"stato": "pronta", "fornitore": "azure"}, "mancano": ["la chiave Mistral"], "coda": {"lezioni_in_preparazione": 1}}""")
        ok(client.salute()) {
            assertTrue(it.modalitaProva)
            assertFalse(it.voceEspressiva!!.pronto)
            assertTrue(it.voceGratis!!.pronto)
            assertEquals(listOf("la chiave Mistral"), it.mancano)
            assertEquals(1, it.coda?.lezioniInPreparazione)
        }
    }

    @Test
    fun materieESchedaDelProf() = runBlocking {
        server.rispondi("GET /api/materie", "[${Esempi.MATERIA}, {\"id\":2,\"nome\":\"Inglese\",\"prof\":\"Bianchi\",\"colore\":\"verde\",\"conteggi\":{\"verifiche\":0,\"interrogazioni\":0,\"appunti\":0,\"lezioni\":0},\"centratura\":null}]")
        ok(client.materie()) {
            assertEquals(63, it[0].centratura?.percentuale)
            assertEquals(3, it[0].daRipassare)
            assertNull(it[1].centratura)
        }
        server.rispondi("GET /api/materie/1/scheda", """{"materia_id": 1, "aggiornata_il": "2026-10-27T10:00:00+01:00", "basata_su": {"verifiche": 4, "interrogazioni": 1, "esiti": 2},
 "argomenti": [{"nome": "Cause e conseguenze", "volte": 4, "esempio": "Perché cadde la Repubblica?"}],
 "tipi_domanda": [{"tipo": "aperta", "quota": 0.6}, {"tipo": "definizione", "quota": 0.25}, {"tipo": "date", "quota": 0.15}],
 "cosa_corregge": ["Vuole le date precise"], "cosa_premia": ["I collegamenti tra fatti"],
 "lunghezza_risposte": "5-8 righe per le domande aperte", "consigli": ["Parti dalla definizione"],
 "note_mie": "", "stato": "pronta"}""")
        ok(client.scheda(1)) {
            assertEquals(4, it.basataSu.verifiche)
            assertEquals(0.6, it.tipiDomanda[0].quota, 0.0)
            assertEquals("pronta", it.stato)
        }
        server.rispondi("POST /api/materie", Esempi.MATERIA, 201)
        ok(client.creaMateria(eu.stgm.lectio.dati.NuovaMateria("Storia", "Rossi", "ambra"))) { assertEquals(1L, it.id) }
        server.rispondi("DELETE /api/materie/1", "")
        ok(client.eliminaMateria(1)) {}
        assertEquals("""{"conferma":true}""", server.richiesteA("DELETE /api/materie/1").single().body.readUtf8())
    }

    @Test
    fun materialeEventiEsito() = runBlocking {
        server.rispondi("GET /api/materie/1/materiale", """[{"id":7,"materia_id":1,"tipo":"verifica","titolo":"Verifica 12 ottobre","data":"2026-10-12","origine":"file","nome_file":"verifica.pdf","caratteri":5321,"stato":"pronto","errore":null,"costo_eur":0.0,"creato_il":"2026-10-12T15:00:00+02:00"}]""")
        ok(client.materiale(1)) { assertEquals("verifica.pdf", it[0].nomeFile) }
        server.rispondi("GET /api/eventi", """[{"id":3,"materia_id":1,"tipo":"scritta","argomento":"Impero romano","data":"2026-10-30","esito":null,"lezione_id":12},
 {"id":4,"materia_id":1,"tipo":"orale","argomento":"Augusto","data":"2026-10-01","esito":{"previste_uscite":3,"totale_vere":5,"percentuale":60},"lezione_id":null}]""")
        ok(client.eventi()) {
            assertFalse(it[0].haEsito)
            assertTrue(it[1].haEsito)
            assertTrue(it[1].orale)
        }
        server.rispondi("POST /api/eventi/3/esito", """{"previste_uscite":3,"totale_vere":5,"percentuale":60}""")
        ok(client.salvaEsito(3, eu.stgm.lectio.dati.CorpoEsito(listOf(31, 33), "Chi era Seiano?", "7"))) { assertEquals(60, it.percentuale) }
        val corpo = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/eventi/3/esito").single().body.readUtf8()).jsonObject
        assertEquals("7", corpo["voto"]!!.jsonPrimitive.content)
    }

    @Test
    fun caricaUnFileMultipartEUnTestoJson() = runBlocking {
        val f = File.createTempFile("verifica", ".txt").apply { writeText("MATERIA: Storia | PROF: Rossi") }
        server.rispondi("POST /api/materie/1/materiale", """{"id":8,"materia_id":1,"tipo":"verifica","titolo":"Storia","stato":"in_lettura"}""", 201)
        ok(client.caricaFile(1, FileDaCaricare(f, "verifica.txt", "text/plain"), "verifica", "Verifica di prova", "2026-10-12")) { assertEquals("in_lettura", it.stato) }
        val r = server.richiesteA("POST /api/materie/1/materiale").single()
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val testo = r.body.readUtf8()
        assertTrue(testo.contains("name=\"file\"; filename=\"verifica.txt\""))
        assertTrue(testo.contains("name=\"tipo\"") && testo.contains("verifica"))
        assertTrue(testo.contains("name=\"data\"") && testo.contains("2026-10-12"))
        assertEquals("Bearer token-finto-di-prova", r.getHeader("Authorization"))
        f.delete()

        ok(client.caricaTesto(1, MaterialeScritto("appunti", "Augusto", null, "Princeps, non re."))) {}
        val json = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/materie/1/materiale")[1].body.readUtf8()).jsonObject
        assertEquals("appunti", json["tipo"]!!.jsonPrimitive.content)
        assertFalse("i null non si mandano", json.containsKey("data"))
    }

    @Test
    fun vociStimeSpesa() = runBlocking {
        server.rispondi("GET /api/voci", """[{"id": "mistral:voice_482", "nome": "Lorenzo", "tipo": "espressiva", "fornitore": "mistral", "euro_al_minuto": 0.0126, "disponibile": true},
 {"id": "azure:it-IT-IsabellaMultilingualNeural", "nome": "Isabella", "tipo": "gratis", "fornitore": "azure", "euro_al_minuto": 0, "disponibile": true}]""")
        ok(client.voci()) { assertEquals(2, it.size); assertEquals(0.0126, it[0].euroAlMinuto, 0.0) }
        server.rispondi("POST /api/lezioni/stima", Esempi.STIMA)
        ok(client.stima(RichiestaStima(1, 20, "mistral:voice_482"))) {
            assertEquals(0.25, it.costoEur, 0.0)
            assertEquals(412000L, it.voceGratisCaratteriRimasti)
        }
        server.rispondi("GET /api/spesa", """{"mese":"2026-10","speso_eur":0.80,"tetto_eur":2.00,"percentuale":40,"si_azzera_il":"2026-11-01","voce_gratis":{"caratteri_usati":88000,"caratteri_mese":500000},"voci":[{"quando":"2026-10-27T10:00:00+01:00","cosa":"voce","fornitore":"mistral","quantita":18300,"unita":"caratteri","costo_eur":0.23,"lezione_id":12}]}""")
        ok(client.spesa()) { assertEquals(500000L, it.voceGratis?.caratteriMese); assertEquals(12L, it.voci[0].lezioneId) }
    }

    @Test
    fun lezioniEDettaglio() = runBlocking {
        server.rispondi("GET /api/lezioni", "[${Esempi.LEZIONE}]")
        ok(client.lezioni()) {
            val l = it.single()
            assertTrue(l.pronta)
            assertEquals(1187.0, l.durataSecondi, 0.0)
            assertEquals(462.0, l.ascolto!!.posizioneS, 0.0)
            assertEquals("Registro la voce · 3 di 7", l.avanzamento?.frase)
        }
        server.rispondi("GET /api/lezioni/12", Esempi.DETTAGLIO)
        ok(client.lezione(12)) {
            assertEquals(1, it.capitoli.size)
            assertEquals(286.9, it.domande[0].fineDomandaS, 0.0)
            assertEquals("parziale", it.domande[0].miaRisposta?.esito)
            assertEquals(listOf("Primo.", "Secondo."), it.testo[0].paragrafi)
        }
        server.rispondi("GET /api/lezioni?materia_id=1", "[]")
        ok(client.lezioni(1)) { assertTrue(it.isEmpty()) }
    }

    @Test
    fun nuovaLezioneSenzaMaterialiEConDomandeSaltabili() = runBlocking {
        server.rispondi("POST /api/lezioni", Esempi.LEZIONE.replace("\"pronta\"", "\"in_coda\""), 201)
        ok(client.creaLezione(NuovaLezione(1, 3, "L'Impero romano", null, 20, "mistral:voice_482", domandeSaltabili = true))) { assertTrue(it.inLavoro) }
        val corpo = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/lezioni").single().body.readUtf8()).jsonObject
        assertFalse("di base non si manda materiali (contratto §15.1)", corpo.containsKey("materiali"))
        assertEquals("true", corpo["domande_saltabili"]!!.jsonPrimitive.content)
        assertEquals("20", corpo["durata_min"]!!.jsonPrimitive.content)

        ok(client.creaLezione(NuovaLezione(1, null, "Augusto", listOf(7L, 9L), 15, "azure:x"))) {}
        val corpo2 = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/lezioni")[1].body.readUtf8()).jsonObject
        assertEquals("[7,9]", corpo2["materiali"].toString())
        assertFalse(corpo2.containsKey("evento_id"))
    }

    @Test
    fun ascoltoERisposta() = runBlocking {
        server.rispondi("PUT /api/lezioni/12/ascolto", "{}")
        ok(client.salvaAscolto(12, CorpoAscolto(462.0, false, "2026-10-28T09:00:00Z"))) {}
        val a = JsonLectio.parseToJsonElement(server.richiesteA("PUT /api/lezioni/12/ascolto").single().body.readUtf8()).jsonObject
        assertEquals("462.0", a["posizione_s"]!!.jsonPrimitive.content)
        assertEquals("2026-10-28T09:00:00Z", a["aggiornato_il"]!!.jsonPrimitive.content)

        server.rispondi("POST /api/lezioni/12/domande/32/risposta", """{"id": 88, "domanda_id": 32, "esito": "parziale", "punteggio": 0.5,
 "commento": "Giusto: non si fidava del senato. Manca chi governava Roma al posto suo.",
 "mancava": ["il ruolo di Seiano"], "risposta_attesa": "Perché non si fidava del senato…", "costo_eur": 0.0, "quando": "2026-10-28T09:00:00Z"}""")
        ok(client.rispondi(12, 32, CorpoRisposta("Perché non si fidava del senato", false))) {
            assertEquals("parziale", it.esito)
            assertEquals(listOf("il ruolo di Seiano"), it.mancava)
        }
        server.rispondi("GET /api/materie/1/da_ripassare", """[{"domanda_id":32,"lezione_id":12,"lezione":"L'Impero romano","testo":"Perché Tiberio lasciò Roma?","esito":"saltata","commento":null,"quando":"2026-10-27T10:00:00+01:00"}]""")
        ok(client.daRipassare(1)) { assertEquals("saltata", it.single().esito) }
        server.rispondi("PATCH /api/lezioni/12", Esempi.LEZIONE.replace("\"domande_saltabili\": false", "\"domande_saltabili\": true"))
        ok(client.modificaLezione(12, kotlinx.serialization.json.buildJsonObject { put("domande_saltabili", kotlinx.serialization.json.JsonPrimitive(true)) })) {
            assertTrue(it.domandeSaltabili)
        }
    }

    @Test
    fun oggiNovitaGeminiImpostazioni() = runBlocking {
        server.rispondi("GET /api/oggi", Esempi.OGGI)
        ok(client.oggi()) {
            assertEquals(2, it.prossima?.traGiorni)
            assertEquals("Storia", it.prossima?.materia?.nome)
            assertNull(it.riprendi)
            assertEquals("Inglese", it.daRaccontare.single().materia.nome)
            assertEquals(6, it.piuAvanti.single().traGiorni)
            assertFalse(it.spesa.avviso)
        }
        server.rispondi("GET /api/novita?dopo_id=0", """[{"id":51,"tipo":"lezione_pronta","testo":"La lezione di Storia è pronta","lezione_id":12,"quando":"2026-10-28T09:00:00Z"}]""")
        ok(client.novita(0)) { assertEquals(12L, it.single().lezioneId) }
        server.rispondi("GET /api/gemini", """{"testo":"Trascrivi questa verifica…","aggiornato_il":"2026-10-20T10:00:00+02:00"}""")
        ok(client.gemini()) { assertTrue(it.testo.startsWith("Trascrivi")) }
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2.00,"voce_predefinita":"mistral:voice_482","durata_predefinita":20,"fermati_alle_domande":true,"velocita":1.0,"nome":"Andrea","risposte_scritte":true,"domande_saltabili":false}""")
        ok(client.impostazioni()) { assertTrue(it.risposteScritte); assertFalse(it.domandeSaltabili); assertEquals(20, it.durataPredefinita) }
        // Un server v1.0 senza i campi nuovi: valgono i predefiniti del contratto.
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":3.5}""")
        ok(client.impostazioni()) { assertTrue(it.risposteScritte); assertTrue(it.fermatiAlleDomande); assertEquals(3.5, it.tettoEur, 0.0) }
    }

    @Test
    fun erroriInItalianoMaiCodici() = runBlocking {
        server.rispondi("POST /api/lezioni", """{"errore":"Sforerebbe il tetto di 2 €: usa la voce gratis","codice":"tetto_raggiunto"}""", 402)
        val e = client.creaLezione(NuovaLezione(1, null, "x", null, 20, "mistral:voice_482")) as Esito.Errore
        assertEquals("Sforerebbe il tetto di 2 €: usa la voce gratis", e.messaggio)
        assertEquals("tetto_raggiunto", e.codice)

        server.rispondi("POST /api/materie/1/materiale", "", 413)
        val f = File.createTempFile("grande", ".pdf")
        val grande = client.caricaFile(1, FileDaCaricare(f, "x.pdf", "application/pdf"), "verifica", null, null) as Esito.Errore
        assertEquals(Testi.TROPPO_GRANDE, grande.messaggio)
        f.delete()

        server.rispondi("GET /api/voci", "<html>Bad gateway</html>", 502)
        assertTrue(LectioClient.eIrraggiungibile(client.voci()))

        server.rispondi("GET /api/spesa", """{"errore":"Il servizio non risponde","codice":"fornitore_non_disponibile"}""", 503)
        assertEquals("Il servizio non risponde", (client.spesa() as Esito.Errore).messaggio)

        server.rispondi("GET /api/oggi", "non è json", 200)
        assertEquals(Testi.RISPOSTA_STRANA, (client.oggi() as Esito.Errore).messaggio)
    }

    @Test
    fun il401ConfermatoScollegaIlTelefono() = runBlocking {
        var scollegato = false
        val c = server.client(alNonAutorizzato = { scollegato = true })
        server.rispondi("GET /api/oggi", """{"errore":"no","codice":"non_autorizzato"}""", 401)
        server.rispondi("GET /api/impostazioni", """{"errore":"no","codice":"non_autorizzato"}""", 401)
        val e = c.oggi() as Esito.Errore
        assertEquals(Testi.NON_AUTORIZZATO, e.messaggio)
        assertTrue(scollegato)
    }

    @Test
    fun unSolo401NonScollega() = runBlocking {
        var scollegato = false
        val c = server.client(alNonAutorizzato = { scollegato = true })
        server.rispondi("GET /api/oggi", "", 401)
        server.rispondi("GET /api/impostazioni", """{"tetto_eur":2}""")
        c.oggi()
        assertFalse(scollegato)
    }

    @Test
    fun abbinamento() = runBlocking {
        server.rispondi("POST /api/abbina", """{"token":"${"t".repeat(64)}","dispositivo_id":1}""")
        val e = LectioClient.abbina(server.url, "48213377", "Telefono")
        assertTrue(e is Esito.Ok)
        val corpo = JsonLectio.parseToJsonElement(server.richiesteA("POST /api/abbina").single().body.readUtf8()) as JsonObject
        assertEquals("android", corpo["tipo"]!!.jsonPrimitive.content)
        assertNull("niente token nell'abbinamento", server.richiesteA("POST /api/abbina").single().getHeader("Authorization"))
        server.rispondi("POST /api/abbina", """{"errore":"Codice sbagliato","codice":"non_autorizzato"}""", 401)
        assertTrue((LectioClient.abbina(server.url, "11111111", "x") as Esito.Errore).messaggio.contains("sbagliato o scaduto"))
        server.rispondi("POST /api/abbina", "", 429)
        assertTrue((LectioClient.abbina(server.url, "11111111", "x") as Esito.Errore).messaggio.contains("15 minuti"))
    }

    @Test
    fun indirizzi() {
        assertEquals(IndirizzoServer.Risultato.Valido("https://lectio.esempio.ts.net"), IndirizzoServer.normalizza("https://lectio.esempio.ts.net/"))
        assertEquals(IndirizzoServer.Risultato.Valido("http://192.168.0.20:8300"), IndirizzoServer.normalizza("192.168.0.20:8300"))
        assertEquals(IndirizzoServer.Risultato.Valido("https://lectio.esempio.it"), IndirizzoServer.normalizza("lectio.esempio.it"))
        assertTrue(IndirizzoServer.normalizza("http://lectio.esempio.it") is IndirizzoServer.Risultato.NonValido)
        assertTrue(IndirizzoServer.normalizza("") is IndirizzoServer.Risultato.NonValido)
    }
}
