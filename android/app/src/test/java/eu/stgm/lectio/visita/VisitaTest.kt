package eu.stgm.lectio.visita

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La visita guidata (aspetto.md §12): avanza solo con l'azione giusta, Salta e Indietro. */
class VisitaTest {

    private fun nuova(): Pair<Visita, MutableList<SchermataVisita>> {
        val v = Visita()
        val aperte = mutableListOf<SchermataVisita>()
        v.vaiA = { aperte += it }
        return v to aperte
    }

    @Test
    fun noveTappeComeNellaGuida() {
        val v = Visita()
        assertEquals(9, v.passi.size)
        assertEquals(listOf(false, true, false, true, false, true, false, true, false), v.passi.map { it.pratico })
        assertNull(v.passi.last().bersaglio)
        assertEquals("Ecco fatto", v.passi.last().titolo)
        // UNA frase per passo, niente punti esclamativi.
        v.passi.forEach { p ->
            assertFalse(p.frase, p.frase.contains('!'))
            assertTrue(p.frase, p.frase.length <= 90)
        }
    }

    @Test
    fun parteDaOggiEContaDiOtto() {
        val (v, aperte) = nuova()
        v.inizia()
        assertTrue(v.attiva)
        assertEquals(listOf(SchermataVisita.OGGI), aperte)
        assertEquals("1 di 8", v.conteggio)
        assertTrue(v.mostraAvanti)
        assertFalse(v.puoTornare)
    }

    @Test
    fun neiPassiPraticiSiVaAvantiSoloFacendoLAzioneGiusta() {
        val (v, aperte) = nuova()
        v.inizia()
        v.avanti() // 2: tocca la pillola Storia
        assertTrue(v.corrente.pratico)
        assertFalse(v.mostraAvanti)
        assertFalse("un'azione sbagliata non fa niente", v.azione(Bersagli.AGGIUNGI))
        assertEquals(1, v.indice)
        assertTrue(v.azione(Bersagli.PILLOLA_MATERIA))
        assertEquals(2, v.indice)
        assertEquals(SchermataVisita.MATERIA, aperte.last())
        assertFalse("nei passi da leggere le azioni non contano", v.azione(Bersagli.SCHEDA_PROF))
    }

    @Test
    fun tuttaLaVisita() {
        val (v, aperte) = nuova()
        var finita = false
        v.allaFine = { finita = true }
        v.inizia()
        v.avanti()
        v.azione(Bersagli.PILLOLA_MATERIA)
        v.avanti() // scheda del prof
        v.azione(Bersagli.AGGIUNGI)
        assertEquals(SchermataVisita.FOGLIO_AGGIUNGI, aperte.last())
        v.avanti() // testo per Gemini
        assertEquals(SchermataVisita.MATERIA, aperte.last())
        v.azione(Bersagli.NUOVA_LEZIONE)
        assertEquals(SchermataVisita.NUOVA_LEZIONE, aperte.last())
        assertEquals(Bersagli.DURATA_E_COSTO, v.corrente.bersaglio)
        v.avanti()
        assertEquals(SchermataVisita.LETTORE, aperte.last())
        assertEquals("8 di 8", v.conteggio)
        assertTrue(v.azione(Bersagli.SENTI_RISPOSTA))
        assertTrue(v.ultimo)
        assertFalse("all'ultimo passo niente Indietro", v.puoTornare)
        assertFalse(finita)
        v.avanti() // "Inizia"
        assertTrue(finita)
        assertFalse(v.attiva)
    }

    @Test
    fun indietroRiapreLaSchermataDelPassoPrima() {
        val (v, aperte) = nuova()
        v.inizia()
        v.avanti()
        v.azione(Bersagli.PILLOLA_MATERIA)
        assertTrue(v.puoTornare)
        v.indietro()
        assertEquals(1, v.indice)
        assertEquals(SchermataVisita.OGGI, aperte.last())
    }

    @Test
    fun saltaFinisceSubito() {
        val (v, _) = nuova()
        var finita = 0
        v.allaFine = { finita++ }
        v.inizia()
        v.avanti()
        v.salta()
        assertFalse(v.attiva)
        assertEquals(1, finita)
        assertEquals(0, v.indice)
        assertFalse(v.azione(Bersagli.PILLOLA_MATERIA))
    }

    @Test
    fun ripartireRicominciaPulita() {
        val (v, _) = nuova()
        v.inizia()
        val giro = v.giro
        v.salta()
        v.inizia()
        assertEquals(giro + 1, v.giro)
        assertEquals(0, v.indice)
    }

    @Test
    fun ilRiflettoreSuDueBersagliPrendeLUnione() {
        val (v, _) = nuova()
        v.inizia()
        repeat(6) { if (v.corrente.pratico) v.azione(v.corrente.bersaglio!!) else v.avanti() }
        assertEquals(Bersagli.DURATA_E_COSTO, v.corrente.bersaglio)
        v.bersagli[Bersagli.DURATA] = Rect(20f, 300f, 370f, 352f)
        v.bersagli[Bersagli.PREPARA] = Rect(20f, 760f, 370f, 818f)
        assertEquals(Rect(20f, 300f, 370f, 818f), v.rettangolo())
    }
}
