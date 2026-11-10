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
    fun leNovitaDellaUnoPuntoDueComeNellaGuida() {
        val (v, aperte) = nuova()
        v.iniziaNovita()
        assertEquals(Bersagli.SEGMENTO_SCHEMI, v.passi.first().bersaglio)
        assertEquals(15, v.passi.size)
        assertEquals("Ecco fatto", v.passi.last().titolo)
        assertNull(v.passi.last().bersaglio)
        v.passi.forEach { p ->
            assertFalse(p.frase, p.frase.contains('!'))
            assertTrue(p.frase, p.frase.length <= 90)
        }
        assertEquals(SchermataVisita.MATERIA, aperte.last())
        // Passano da tutte le pagine nuove.
        assertEquals(
            setOf(SchermataVisita.MATERIA, SchermataVisita.NUOVO_SCHEMA, SchermataVisita.SCHEMA, SchermataVisita.OGGI, SchermataVisita.LAMPO, SchermataVisita.SIMULATA_IN_CORSO, SchermataVisita.SIMULATA_RISULTATI),
            v.passi.map { it.schermata }.toSet(),
        )
    }

    @Test
    fun dalleImpostazioniQuellaDiSempreEPoiLeNovita() {
        val (v, _) = nuova()
        v.iniziaTutte()
        assertEquals(8 + 15, v.passi.size)
        assertEquals(Bersagli.PROSSIMA, v.passi.first().bersaglio)
        assertEquals(Bersagli.SEGMENTO_SCHEMI, v.passi[8].bersaglio)
        assertEquals(1, v.passi.count { it.titolo == "Ecco fatto" })
    }

    @Test
    fun nelleNovitaSiAvanzaSoloFacendo() {
        val (v, _) = nuova()
        v.iniziaNovita()
        assertFalse(v.azione(Bersagli.RIGA_SCHEMA))
        assertEquals(0, v.indice)
        assertTrue(v.azione(Bersagli.SEGMENTO_SCHEMI))
        assertEquals(1, v.indice)
        // Il foglio dell'indice sposta il riflettore senza far avanzare la visita.
        v.bersaglioTemporaneo = Bersagli.RIGA_INDICE
        assertEquals(1, v.indice)
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
        // Nuovo apre il foglio e basta: si va avanti toccando Lezione da ascoltare (aspetto.md §43.4).
        assertFalse(v.azione(Bersagli.NUOVO))
        assertTrue(v.azione(Bersagli.RIGA_LEZIONE))
        assertEquals(SchermataVisita.NUOVA_LEZIONE, aperte.last())
        assertEquals(Bersagli.RIEPILOGO, v.corrente.bersaglio)
        assertEquals("Come la vuoi", v.corrente.titolo)
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

    /** Passo 7 (v1.4): il riflettore va sulla barra in basso di Nuova lezione, riepilogo e pulsante insieme. */
    @Test
    fun ilPasso7IlluminaLaBarraDelRiepilogo() {
        val (v, _) = nuova()
        v.inizia()
        repeat(6) { if (v.corrente.pratico) v.azione(v.corrente.azioneGiusta!!) else v.avanti() }
        assertEquals(Bersagli.RIEPILOGO, v.corrente.bersaglio)
        assertEquals("Scegli materiale, tipo e indicazioni: qui vedi cosa esce e quanto costa.", v.corrente.frase)
        assertNull("prima che la barra si veda non c'è niente da illuminare", v.rettangolo())
        v.bersagli[Bersagli.PREPARA] = Rect(16f, 760f, 374f, 814f)
        v.bersagli[Bersagli.RIEPILOGO] = Rect(0f, 712f, 390f, 844f)
        assertEquals(Rect(0f, 712f, 390f, 844f), v.rettangolo())
    }

    /** aspetto.md §43.4: il passo indica Nuovo, poi (col foglio aperto) la riga giusta. */
    @Test
    fun ilPassoDellaLezioneIndicaNuovoEPoiLaRiga() {
        val (v, aperte) = nuova()
        v.inizia()
        repeat(5) { if (v.corrente.pratico) v.azione(v.corrente.azioneGiusta!!) else v.avanti() }
        val passo = v.corrente
        assertEquals(Bersagli.NUOVO, passo.bersaglio)
        assertEquals(Bersagli.RIGA_LEZIONE, passo.poi)
        assertEquals("Tocca Nuovo, poi Lezione da ascoltare, per prepararne una.", passo.frase)
        assertEquals(SchermataVisita.MATERIA, aperte.last())
        // Prima il riflettore è su Nuovo.
        v.bersagli[Bersagli.NUOVO] = Rect(16f, 770f, 374f, 828f)
        v.bersagli[Bersagli.RIGA_LEZIONE] = Rect(16f, 300f, 374f, 380f)
        assertEquals(Rect(16f, 770f, 374f, 828f), v.rettangolo())
        // Col foglio aperto passa alla riga; toccare Nuovo o un'altra riga non fa avanzare.
        v.bersaglioTemporaneo = passo.poi
        assertEquals(Rect(16f, 300f, 374f, 380f), v.rettangolo())
        assertFalse(v.azione(Bersagli.NUOVO))
        assertFalse(v.azione(Bersagli.RIGA_SCHEMA))
        assertEquals(5, v.indice)
        assertTrue(v.azione(Bersagli.RIGA_LEZIONE))
        assertEquals(SchermataVisita.NUOVA_LEZIONE, aperte.last())
    }

    @Test
    fun ilPassoDelloSchemaIndicaNuovoEPoiSchema() {
        val (v, aperte) = nuova()
        v.iniziaNovita()
        assertTrue(v.azione(Bersagli.SEGMENTO_SCHEMI))
        assertEquals(Bersagli.NUOVO, v.corrente.bersaglio)
        assertEquals(Bersagli.RIGA_SCHEMA, v.corrente.poi)
        assertEquals("Si crea come una lezione.", v.corrente.frase)
        assertFalse(v.azione(Bersagli.NUOVO))
        assertFalse(v.azione(Bersagli.RIGA_LEZIONE))
        assertTrue(v.azione(Bersagli.RIGA_SCHEMA))
        assertEquals(SchermataVisita.NUOVO_SCHEMA, aperte.last())
        // Tornando indietro si riparte dalla materia, col foglio chiuso.
        v.indietro()
        assertEquals(Bersagli.NUOVO, v.corrente.bersaglio)
        assertEquals(SchermataVisita.MATERIA, aperte.last())
    }
}
