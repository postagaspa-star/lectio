package eu.stgm.lectio.lavori

import eu.stgm.lectio.dati.DepositoStudio
import eu.stgm.lectio.dati.CopiaSimulata
import eu.stgm.lectio.dati.Riassunto
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Simulata
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.dati.MemoriaNovita
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.dati.CacheMemoria
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.rete.Esito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schemi, simulate e riassunti seguiti in sottofondo: si avvisa una volta sola. */
class GiroStudioTest {
    private class Registro : AvvisiStudio {
        val righe = mutableListOf<String>()
        override fun avanzamento(titolo: String, frase: String, frazione: Float?) { righe += "avanzamento:$titolo:$frase:$frazione" }
        override fun schema(testo: String, schema: Schema) { righe += "schema:${schema.id}:$testo" }
        override fun simulata(testo: String, simulata: Simulata) { righe += "simulata:${simulata.id}:$testo" }
        override fun riassunto(testo: String, riassunto: Riassunto) { righe += "riassunto:${riassunto.id}:$testo" }
    }

    private val deposito = DepositoStudio()
    private val r = Registro()
    private var schema: Esito<Schema> = Esito.Irraggiungibile
    private var simulata: Esito<Simulata> = Esito.Irraggiungibile
    private var riassunto: Esito<Riassunto> = Esito.Irraggiungibile
    private val giro = GiroStudio(deposito, { schema }, { simulata }, { riassunto }, { "Storia" }, r)

    @Test
    fun senzaNienteDaSeguireHaFinito() = runBlocking {
        assertEquals(Giro.FINITO, giro.giro())
        assertTrue(r.righe.isEmpty())
    }

    @Test
    fun loSchemaProntoSiDiceUnaVoltaSola() = runBlocking {
        deposito.seguiSchema(3)
        schema = Esito.Ok(Schema(3, 1, stato = "in_preparazione", frase = "Scrivo la parte 2 di 8", fatto = 2, totale = 8))
        assertEquals(Giro.IN_CORSO, giro.giro())
        assertEquals(listOf("avanzamento:Preparo lo schema di Storia:Scrivo la parte 2 di 8:0.25"), r.righe)

        schema = Esito.Ok(Schema(3, 1, stato = "pronto"))
        assertEquals(Giro.FINITO, giro.giro())
        assertEquals("schema:3:Lo schema di Storia è pronto", r.righe.last())
        assertTrue(deposito.seguiti().vuoti)

        val quante = r.righe.size
        giro.giro()
        assertEquals(quante, r.righe.size)
    }

    @Test
    fun laNotificaFissaLasciaIlPostoAlleLezioni() = runBlocking {
        deposito.seguiSchema(3)
        schema = Esito.Ok(Schema(3, 1, stato = "in_coda"))
        assertEquals(Giro.IN_CORSO, giro.giro(mostraAvanzamento = false))
        assertTrue(r.righe.isEmpty())
    }

    @Test
    fun laVerificaPrimaProntaPoiCorretta() = runBlocking {
        deposito.seguiSimulata(9)
        simulata = Esito.Ok(Simulata(9, 1, stato = "pronta"))
        assertEquals(Giro.FINITO, giro.giro())
        assertEquals("simulata:9:La verifica simulata di Storia è pronta", r.righe.last())

        deposito.seguiSimulata(9)
        simulata = Esito.Ok(Simulata(9, 1, stato = "in_correzione"))
        assertEquals(Giro.IN_CORSO, giro.giro())
        simulata = Esito.Ok(Simulata(9, 1, stato = "corretta"))
        assertEquals(Giro.FINITO, giro.giro())
        assertEquals("simulata:9:La verifica simulata di Storia è corretta", r.righe.last())
    }

    @Test
    fun ilRiassuntoEIFallimenti() = runBlocking {
        deposito.seguiRiassunto(4)
        riassunto = Esito.Ok(Riassunto(4, 3, 1, "misto", stato = "errore"))
        giro.giro()
        assertEquals("riassunto:4:Il riassunto di Storia non è riuscito", r.righe.last())

        deposito.seguiRiassunto(5)
        riassunto = Esito.Ok(Riassunto(5, 3, 1, "punti", stato = "pronto"))
        giro.giro()
        assertEquals("riassunto:5:Il riassunto di Storia è pronto", r.righe.last())
    }

    @Test
    fun senzaReteResta() = runBlocking {
        deposito.seguiSchema(3)
        assertEquals(Giro.SENZA_RETE, giro.giro())
        assertFalse(deposito.seguiti().vuoti)
        assertTrue(r.righe.isEmpty())
    }

    @Test
    fun cancellatoAltroveSiSmetteDiSeguire() = runBlocking {
        deposito.seguiSchema(3)
        schema = Esito.Errore("Non trovo più questo schema.", "non_trovato", 404)
        assertEquals(Giro.FINITO, giro.giro())
        assertTrue(deposito.seguiti().vuoti)
    }

    @Test
    fun annullatoNonDisturba() = runBlocking {
        deposito.seguiSchema(3)
        schema = Esito.Ok(Schema(3, 1, stato = "annullato"))
        assertEquals(Giro.FINITO, giro.giro())
        assertTrue(r.righe.isEmpty())
        assertTrue(deposito.seguiti().vuoti)
    }

    private class Memoria(var c: Long) : MemoriaNovita {
        override suspend fun cursore() = c
        override suspend fun ricordaCursore(id: Long) { c = id }
        override suspend fun avvisate() = emptySet<Long>()
        override suspend fun segnaAvvisata(lezioneId: Long) = Unit
    }

    @Test
    fun leNovitaNuoveSiDiconoAllApertura() = runBlocking {
        val novita = listOf("schema_pronto", "riassunto_errore", "simulata_corretta", "tipo_sconosciuto").mapIndexed { i, t -> Novita((i + 1).toLong(), t, "testo $t") }
        val da = novitaDaMostrare({ Esito.Ok(novita) }, Memoria(0 + 1))
        assertEquals(listOf("schema_pronto", "riassunto_errore", "simulata_corretta"), da.map { it.tipo })
    }

    @Test
    fun leRisposteRimasteSulTelefonoPartono() = runBlocking {
        val server = ServerFinto()
        try {
            val d = DepositoStudio()
            val s = SorgenteServer(server.client(), CacheMemoria(), deposito = d)
            d.scriviCopia(9, CopiaSimulata(mapOf("1" to "ciao"), daInviare = true))
            server.rispondi("PATCH /api/simulate/9", """{"id": 9, "stato": "in_corso"}""")
            assertTrue(inviaStudio(s))
            assertTrue(d.copieDaInviare().isEmpty())
            assertEquals(1, server.richiesteA("PATCH /api/simulate/9").size)

            // Rete giù: resta tutto da mandare.
            d.scriviCopia(9, CopiaSimulata(mapOf("1" to "due"), daInviare = true))
            server.spegni()
            assertFalse(inviaStudio(s))
            assertEquals(1, d.copieDaInviare().size)
        } finally {
            server.close()
        }
    }
}
