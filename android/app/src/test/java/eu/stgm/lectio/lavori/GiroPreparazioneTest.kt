package eu.stgm.lectio.lavori

import eu.stgm.lectio.dati.Avanzamento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MemoriaNovita
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.rete.Esito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il controllo ogni 20 s durante la preparazione (contratto §11): avanzamento e "pronta" una volta sola. */
class GiroPreparazioneTest {

    private class Memoria : MemoriaNovita {
        var c = 0L
        val a = mutableSetOf<Long>()
        override suspend fun cursore() = c
        override suspend fun ricordaCursore(id: Long) { if (id > c) c = id }
        override suspend fun avvisate() = a.toSet()
        override suspend fun segnaAvvisata(lezioneId: Long) { a += lezioneId }
    }

    private class Registro : Avvisi {
        val righe = mutableListOf<String>()
        override fun avanzamento(titolo: String, frase: String, frazione: Float?, lezioneId: Long) { righe += "avanzamento:$titolo:$frase" }
        override fun pronta(testo: String, lezioneId: Long) { righe += "pronta:$lezioneId:$testo" }
        override fun nonRiuscita(testo: String, lezioneId: Long) { righe += "errore:$lezioneId:$testo" }
        override fun altro(id: Long, testo: String) { righe += "altro:$testo" }
    }

    private val inPreparazione = Lezione(12, 1, argomento = "Impero", stato = "in_preparazione", avanzamento = Avanzamento("voce", 3, 7, "Registro la voce · 3 di 7"))

    @Test
    fun avanzamentoPoiProntaUnaVolta() = runBlocking {
        var elenco = listOf(inPreparazione)
        var novita = emptyList<Novita>()
        val memoria = Memoria()
        val r = Registro()
        val giro = GiroPreparazione({ Esito.Ok(elenco) }, { dopo -> Esito.Ok(novita.filter { it.id > dopo }) }, { "Storia" }, memoria, r)
        assertEquals(Giro.IN_CORSO, giro.giro())
        assertEquals(listOf("avanzamento:Preparo la lezione di Storia:Registro la voce · 3 di 7"), r.righe)

        elenco = listOf(inPreparazione.copy(stato = "pronta", avanzamento = null))
        novita = listOf(Novita(51, "lezione_pronta", "La lezione di Storia è pronta", 12))
        assertEquals(Giro.FINITO, giro.giro())
        assertEquals("pronta:12:La lezione di Storia è pronta", r.righe.last())
        assertEquals(51L, memoria.c)
        // Un altro giro non la ripete.
        giro.giro()
        assertEquals(1, r.righe.count { it.startsWith("pronta") })
    }

    @Test
    fun senzaNovitaLAvvisoLoFaLApp() = runBlocking {
        var elenco = listOf(inPreparazione)
        val r = Registro()
        val giro = GiroPreparazione({ Esito.Ok(elenco) }, { Esito.Ok(emptyList()) }, { "Storia" }, Memoria(), r)
        giro.giro()
        elenco = listOf(inPreparazione.copy(stato = "errore", errore = "Il servizio della voce non risponde."))
        assertEquals(Giro.FINITO, giro.giro())
        assertEquals("errore:12:Il servizio della voce non risponde.", r.righe.last())
    }

    @Test
    fun senzaReteSiAspetta() = runBlocking {
        val giro = GiroPreparazione({ Esito.Irraggiungibile }, { Esito.Irraggiungibile }, { null }, Memoria(), Registro())
        assertEquals(Giro.SENZA_RETE, giro.giro())
    }

    @Test
    fun ilTettoVicinoSiDice() = runBlocking {
        val r = Registro()
        // Cursore già fissato (con il cursore a 0 il primo giro solo lo fissa: punto 5).
        val memoria = Memoria().apply { c = 59 }
        val giro = GiroPreparazione({ Esito.Ok(emptyList()) }, { dopo -> Esito.Ok(listOf(Novita(60, "tetto_vicino", "Hai speso l'80% del tetto del mese")).filter { it.id > dopo }) }, { null }, memoria, r)
        assertEquals(Giro.FINITO, giro.giro())
        assertTrue(r.righe.contains("altro:Hai speso l'80% del tetto del mese"))
    }
}
