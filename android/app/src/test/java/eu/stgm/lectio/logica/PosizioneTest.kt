package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Ascolto
import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.rete.Esito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** La posizione d'ascolto: "vince il più recente" tra telefono e server (contratto §9). */
class PosizioneTest {

    private val ore10 = Instant.parse("2026-10-28T09:00:00Z").toEpochMilli()

    @Test
    fun senzaNienteSiPartedaZero() {
        assertEquals(PuntoDiRipresa(0.0, false, null), RegolaPosizione.scegli(null, null))
    }

    @Test
    fun soloServer() {
        val r = RegolaPosizione.scegli(null, Ascolto(462.0, false, "2026-10-28T10:00:00+01:00"))
        assertEquals(462.0, r.posizioneS, 0.0)
        assertNull(r.daInviare)
    }

    @Test
    fun telefonoPiuRecenteVinceEVaMandato() {
        val locale = PosizioneLocale(12, 600.0, false, ore10 + 60_000)
        val r = RegolaPosizione.scegli(locale, Ascolto(462.0, false, "2026-10-28T10:00:00+01:00"))
        assertEquals(600.0, r.posizioneS, 0.0)
        assertEquals(locale, r.daInviare)
    }

    @Test
    fun serverPiuRecenteVince() {
        // Sul PC si è andati avanti dopo: la posizione vecchia del telefono non conta.
        val locale = PosizioneLocale(12, 600.0, false, ore10 - 60_000)
        val r = RegolaPosizione.scegli(locale, Ascolto(900.0, true, "2026-10-28T10:00:00+01:00"))
        assertEquals(900.0, r.posizioneS, 0.0)
        assertTrue(r.finita)
        assertNull(r.daInviare)
    }

    @Test
    fun aParitaVinceIlServer() {
        val locale = PosizioneLocale(12, 600.0, false, ore10)
        val r = RegolaPosizione.scegli(locale, Ascolto(462.0, false, "2026-10-28T10:00:00+01:00"))
        assertEquals(462.0, r.posizioneS, 0.0)
    }

    @Test
    fun dataDelServerIlleggibileVinceIlTelefono() {
        val locale = PosizioneLocale(12, 600.0, false, ore10)
        assertEquals(600.0, RegolaPosizione.scegli(locale, Ascolto(462.0, false, "ieri")).posizioneS, 0.0)
        assertEquals(600.0, RegolaPosizione.scegli(locale, Ascolto(462.0, false, null)).posizioneS, 0.0)
    }

    @Test
    fun leggeIDueFormatiDiData() {
        assertEquals(ore10, RegolaPosizione.istante("2026-10-28T10:00:00+01:00"))
        assertEquals(ore10, RegolaPosizione.istante("2026-10-28T09:00:00Z"))
        assertNull(RegolaPosizione.istante(""))
    }

    @Test
    fun ogniQuindiciSecondi() {
        assertTrue(RegolaPosizione.eOraDiSalvare(null, 0))
        assertFalse(RegolaPosizione.eOraDiSalvare(1_000, 15_999))
        assertTrue(RegolaPosizione.eOraDiSalvare(1_000, 16_000))
    }

    @Test
    fun senzaReteLaTieneEPoiLaManda() = runBlocking {
        val memoria = MemoriaPosizioniInMemoria()
        var rete = false
        val mandate = mutableListOf<Pair<Long, CorpoAscolto>>()
        val s = SincronizzatorePosizioni(memoria, { id, corpo ->
            if (rete) {
                mandate += id to corpo
                Esito.Ok(Unit)
            } else {
                Esito.Irraggiungibile
            }
        }, adesso = { ore10 })
        assertFalse(s.registra(12, 300.0, false))
        assertFalse(s.registra(12, 330.0, false)) // una sola per lezione: l'ultima
        assertFalse(s.registra(9, 50.0, false))
        assertEquals(2, memoria.posizioni.size)
        assertNotNull(s.inSospeso(12))
        rete = true
        assertTrue(s.inviaInSospeso())
        assertTrue(memoria.posizioni.isEmpty())
        val dodici = mandate.first { it.first == 12L }.second
        assertEquals(330.0, dodici.posizioneS, 0.0)
        // Il momento della registrazione, non dell'invio: così il server sa chi è più recente.
        assertEquals("2026-10-28T09:00:00Z", dodici.aggiornatoIl)
    }

    @Test
    fun unRifiutoDelServerNonSiRiprova() = runBlocking {
        val memoria = MemoriaPosizioniInMemoria()
        val s = SincronizzatorePosizioni(memoria, { _, _ -> Esito.Errore("Non trovo più questo elemento", "non_trovato", 404) })
        assertTrue(s.registra(12, 300.0, false))
        assertTrue(memoria.posizioni.isEmpty())
    }

    @Test
    fun dimenticaTogliLaPosizioneVecchia() = runBlocking {
        val memoria = MemoriaPosizioniInMemoria(listOf(PosizioneLocale(12, 1.0, false, 0)))
        val s = SincronizzatorePosizioni(memoria, { _, _ -> Esito.Irraggiungibile })
        s.dimentica(12)
        assertNull(s.inSospeso(12))
    }

    @Test
    fun codificaEDecodifica() {
        val p = listOf(PosizioneLocale(12, 300.5, true, ore10))
        assertEquals(p, decodificaPosizioni(codificaPosizioni(p)))
        assertTrue(decodificaPosizioni("non json").isEmpty())
    }
}
