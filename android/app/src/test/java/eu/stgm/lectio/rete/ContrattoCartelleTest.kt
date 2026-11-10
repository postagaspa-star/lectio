package eu.stgm.lectio.rete

import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.MaterialeScritto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
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

/** Le cartelle (contratto §27) lette e scritte dal client vero contro un server finto. */
class ContrattoCartelleTest {
    private val server = ServerFinto()
    private val client = server.client()

    @After
    fun chiudi() = server.close()

    private inline fun <T> ok(e: Esito<T>, verifica: (T) -> Unit) {
        assertTrue("non ok: $e", e is Esito.Ok)
        verifica((e as Esito.Ok).dato)
    }

    private fun corpo(metodoPercorso: String, indice: Int = -1): JsonObject {
        val r = server.richiesteA(metodoPercorso)
        return JsonLectio.parseToJsonElement((if (indice < 0) r.last() else r[indice]).body.clone().readUtf8()).jsonObject
    }

    private fun cartella(id: Long, nome: String, file: Int = 0, messi: Int = 0) =
        """{"id": $id, "materia_id": 7, "nome": "$nome", "file": $file, "messi_da_lectio": $messi, "creata_il": "2026-11-10T10:00:00+01:00"}"""

    @Test
    fun elencoCreaRinomina() = runBlocking {
        server.rispondi("GET /api/materie/7/cartelle", "[${cartella(3, "Arte greca", 8, 2)}, ${cartella(4, "Arte romana")}]")
        ok(client.cartelle(7)) {
            assertEquals(listOf("Arte greca", "Arte romana"), it.map { c -> c.nome })
            assertEquals(7L, it[0].materiaId)
            assertEquals(8, it[0].file)
            assertEquals(2, it[0].messiDaLectio)
            assertEquals(0, it[1].file)
        }

        server.rispondi("POST /api/materie/7/cartelle", cartella(5, "Arte etrusca"), 201)
        ok(client.creaCartella(7, "Arte etrusca")) { assertEquals(5L, it.id) }
        assertEquals("""{"nome":"Arte etrusca"}""", server.richiesteA("POST /api/materie/7/cartelle").single().body.readUtf8())

        // Il nome doppio: la frase del server va sotto il campo così com'è.
        server.rispondi("POST /api/materie/7/cartelle", """{"errore": "C'è già una cartella con questo nome in questa materia.", "codice": "conflitto", "campo": "nome"}""", 409)
        val doppia = client.creaCartella(7, "arte greca")
        assertTrue(doppia is Esito.Errore)
        assertEquals("C'è già una cartella con questo nome in questa materia.", doppia.messaggio)

        server.rispondi("PATCH /api/cartelle/3", cartella(3, "Grecia", 8))
        ok(client.rinominaCartella(3, "Grecia")) { assertEquals("Grecia", it.nome) }
        assertEquals("""{"nome":"Grecia"}""", server.richiesteA("PATCH /api/cartelle/3").single().body.readUtf8())
    }

    @Test
    fun eliminaMandaSempreConfermaEConIFile() = runBlocking {
        server.rispondi("DELETE /api/cartelle/3", """{"ok": true, "file_eliminati": 8}""")
        ok(client.eliminaCartella(3, conIFile = true)) { assertEquals(8, it.fileEliminati) }
        server.rispondi("DELETE /api/cartelle/3", """{"ok": true, "file_eliminati": 0}""")
        ok(client.eliminaCartella(3, conIFile = false)) { assertEquals(0, it.fileEliminati) }
        val anche = corpo("DELETE /api/cartelle/3", 0)
        val solo = corpo("DELETE /api/cartelle/3", 1)
        assertEquals("true", anche["conferma"]!!.jsonPrimitive.content)
        assertEquals("true", anche["con_i_file"]!!.jsonPrimitive.content)
        assertEquals("true", solo["conferma"]!!.jsonPrimitive.content)
        assertEquals("false", solo["con_i_file"]!!.jsonPrimitive.content)
    }

    @Test
    fun spostaEProposti() = runBlocking {
        server.rispondi("POST /api/materie/7/sposta", """{"spostati": 3}""")
        ok(client.sposta(7, listOf(11, 12, 13), 3)) { assertEquals(3, it.spostati) }
        val dentro = corpo("POST /api/materie/7/sposta")
        assertEquals("[11,12,13]", dentro["materiali"].toString())
        assertEquals("3", dentro["cartella_id"]!!.jsonPrimitive.content)

        // Fuori dalle cartelle: si manda proprio null.
        ok(client.sposta(7, listOf(11), null)) {}
        val fuori = corpo("POST /api/materie/7/sposta")
        assertTrue(fuori.containsKey("cartella_id"))
        assertEquals(JsonNull, fuori["cartella_id"])

        server.rispondi("GET /api/cartelle/3/proposti", """{"proposti": [12, 14]}""")
        ok(client.proposti(3)) { assertEquals(listOf(12L, 14L), it.proposti) }
    }

    @Test
    fun ilMaterialeDiceInQualeCartellaSta() {
        val m = JsonLectio.decodeFromString(
            Materiale.serializer(),
            """{"id": 12, "materia_id": 7, "tipo": "appunti", "titolo": "Il Partenone", "stato": "pronto", "cartella_id": 3, "cartella_da": "lectio"}""",
        )
        assertEquals(3L, m.cartellaId)
        assertEquals("lectio", m.cartellaDa)
        // Un server di prima delle cartelle non li manda: fuori.
        val vecchio = JsonLectio.decodeFromString(Materiale.serializer(), """{"id": 1, "materia_id": 7, "tipo": "appunti", "titolo": "X", "stato": "pronto"}""")
        assertNull(vecchio.cartellaId)
        assertNull(vecchio.cartellaDa)
    }

    @Test
    fun caricareInUnaCartella() = runBlocking {
        val f = File.createTempFile("appunti", ".txt").apply { writeText("Il Partenone") }
        server.rispondi("POST /api/materie/7/materiale", """{"id": 20, "materia_id": 7, "tipo": "appunti", "titolo": "Partenone", "stato": "in_lettura"}""", 201)
        // File: la cartella è un campo di testo del multipart.
        ok(client.caricaFile(7, FileDaCaricare(f, "appunti.txt", "text/plain"), "appunti", null, null, cartellaId = 3)) {}
        val conCartella = server.richiesteA("POST /api/materie/7/materiale")[0].body.readUtf8()
        assertTrue(conCartella, Regex("name=\"cartella_id\"(\\r\\n[^\\r\\n]+)*\\r\\n\\r\\n3\\r\\n").containsMatchIn(conCartella))
        // "Decide Lectio": niente campo.
        ok(client.caricaFile(7, FileDaCaricare(f, "appunti.txt", "text/plain"), "appunti", null, null)) {}
        assertFalse(server.richiesteA("POST /api/materie/7/materiale")[1].body.readUtf8().contains("cartella_id"))
        f.delete()

        // Appunti scritti: un numero nel JSON; senza cartella il campo non c'è.
        ok(client.caricaTesto(7, MaterialeScritto("appunti", "Partenone", null, "Il Partenone.", cartellaId = 3))) {}
        assertEquals("3", corpo("POST /api/materie/7/materiale", 2)["cartella_id"]!!.jsonPrimitive.content)
        ok(client.caricaTesto(7, MaterialeScritto("appunti", "Partenone", null, "Il Partenone."))) {}
        assertFalse(corpo("POST /api/materie/7/materiale", 3).containsKey("cartella_id"))
    }
}
