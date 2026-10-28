package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.domanda
import eu.stgm.lectio.lettore.LettoreEsempio
import eu.stgm.lectio.rete.Esito
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le risposte scritte (contratto §15.2): correzione, coda senza rete, salto. */
@OptIn(ExperimentalCoroutinesApi::class)
class RisposteTest {

    private val corretta = Correzione(88, 32, "parziale", 0.5, "Giusto a metà.", listOf("il ruolo di Seiano"), "Perché non si fidava del senato.")

    @Test
    fun conLaReteArrivaLaCorrezione() = runBlocking {
        val mandate = mutableListOf<CorpoRisposta>()
        val coda = CodaRisposte(MemoriaRisposteInMemoria(), { _, _, c -> mandate += c; Esito.Ok(corretta) })
        val e = coda.rispondi(12, 32, "  Perché non si fidava del senato  ", saltata = false)
        assertEquals(EsitoRisposta.Corretta(corretta), e)
        assertEquals(CorpoRisposta("Perché non si fidava del senato", false), mandate.single())
    }

    @Test
    fun senzaReteVaInCodaEPoiParte() = runBlocking {
        val memoria = MemoriaRisposteInMemoria()
        var rete = false
        val mandate = mutableListOf<Pair<Long, CorpoRisposta>>()
        val coda = CodaRisposte(memoria, { _, d, c -> if (rete) { mandate += d to c; Esito.Ok(corretta) } else Esito.Irraggiungibile })
        assertEquals(EsitoRisposta.InCoda, coda.rispondi(12, 32, "prima", false))
        assertEquals(EsitoRisposta.InCoda, coda.rispondi(12, 32, "seconda", false)) // vale l'ultima
        assertEquals(EsitoRisposta.InCoda, coda.rispondi(12, 33, "", true))
        assertEquals(2, memoria.risposte.size)
        assertFalse(coda.inviaInCoda())
        rete = true
        assertTrue(coda.inviaInCoda())
        assertEquals(listOf(32L to CorpoRisposta("seconda", false), 33L to CorpoRisposta("", true)), mandate)
        assertTrue(memoria.risposte.isEmpty())
    }

    @Test
    fun unRifiutoSiMostraENonVaInCoda() = runBlocking {
        val memoria = MemoriaRisposteInMemoria()
        val coda = CodaRisposte(memoria, { _, _, _ -> Esito.Errore("La risposta è troppo lunga.", "non_valido", 422) })
        assertEquals(EsitoRisposta.Rifiutata("La risposta è troppo lunga."), coda.rispondi(12, 32, "x", false))
        assertTrue(memoria.risposte.isEmpty())
    }

    @Test
    fun laRispostaSiTagliaA2000Caratteri() = runBlocking {
        var lunghezza = 0
        val coda = CodaRisposte(MemoriaRisposteInMemoria(), { _, _, c -> lunghezza = c.testo.length; Esito.Ok(corretta) })
        coda.rispondi(12, 32, "a".repeat(5000), false)
        assertEquals(2000, lunghezza)
    }

    @Test
    fun paroleEColoriDegliEsiti() {
        assertEquals("Giusta", Esiti.parola("giusta"))
        assertEquals("Quasi", Esiti.parola("parziale"))
        assertEquals("Da rivedere", Esiti.parola("sbagliata"))
        assertEquals(ToniEsito.GIUSTA, Esiti.tono("giusta"))
        assertEquals(ToniEsito.QUASI, Esiti.tono("parziale"))
        assertEquals(ToniEsito.DA_RIVEDERE, Esiti.tono("saltata"))
        assertEquals(ToniEsito.NEUTRO, Esiti.tono("non_corretta"))
        assertEquals("Da rivedere", Esiti.parolaRipasso("saltata"))
    }

    // --- Il lettore d'esempio usa le stesse regole del vero ------------------------------------

    private fun lezione(saltabili: Boolean) = Lezione(12, 1, argomento = "L'Impero romano", durataMin = 20, durataS = 1187.0, stato = "pronta",
        domande = listOf(domanda(), domanda(id = 32, n = 2, inizio = 561.2, fineDomanda = 567.8, inizioRisposta = 570.3, fine = 589.0)),
        domandeSaltabili = saltabili)

    @Test
    fun lettoreDEsempioInviaECorregge() {
        val ambito = TestScope(StandardTestDispatcher())
        val mandate = mutableListOf<CorpoRisposta>()
        val l = LettoreEsempio(ambito, correttore = { _, _, c -> mandate += c; Esito.Ok(corretta) }, attesaCorrezioneMs = 100)
        l.carica(lezione(false), null, 0.0, false)
        l.fermaAllaDomanda(2)
        assertEquals(FaseDomanda.ALLA_DOMANDA, l.stato.value.fermate.fase)
        l.rispondi("Perché non si fidava")
        assertTrue(l.stato.value.risposta is StatoRisposta.Correggo)
        ambito.advanceTimeBy(150)
        ambito.runCurrent()
        assertEquals(StatoRisposta.Corretta("Perché non si fidava", corretta), l.stato.value.risposta)
        // Corretta: parte la risposta giusta.
        assertEquals(FaseDomanda.RISPOSTA, l.stato.value.fermate.fase)
        assertEquals(570.3, l.stato.value.posizioneS, 0.01)
        assertEquals(1, mandate.size)
        l.ferma()
    }

    @Test
    fun obbligatorieIlPlayDegliAuricolariNonSalta() {
        val ambito = TestScope(StandardTestDispatcher())
        val l = LettoreEsempio(ambito)
        l.carica(lezione(saltabili = false), null, 0.0, false)
        l.fermaAllaDomanda(2)
        l.premiPlayEsterno()
        // Ripete la domanda: si torna all'inizio, la risposta non è partita.
        assertEquals(561.2, l.stato.value.posizioneS, 0.01)
        assertTrue(l.stato.value.risposta is StatoRisposta.DaScrivere)
        l.ferma()
    }

    @Test
    fun saltabiliIlPlayDegliAuricolariSalta() {
        val ambito = TestScope(StandardTestDispatcher())
        val mandate = mutableListOf<CorpoRisposta>()
        val l = LettoreEsempio(ambito, correttore = { _, _, c -> mandate += c; Esito.Ok(corretta) })
        l.carica(lezione(saltabili = true), null, 0.0, false)
        l.fermaAllaDomanda(2)
        l.premiPlayEsterno()
        assertEquals(StatoRisposta.Saltata, l.stato.value.risposta)
        assertEquals(FaseDomanda.RISPOSTA, l.stato.value.fermate.fase)
        ambito.runCurrent()
        assertEquals(CorpoRisposta("", true), mandate.single())
        l.ferma()
    }

    @Test
    fun ilPlayGrandeAllaDomandaNonFaNiente() {
        val ambito = TestScope(StandardTestDispatcher())
        val l = LettoreEsempio(ambito)
        l.carica(lezione(saltabili = true), null, 0.0, false)
        l.fermaAllaDomanda(1)
        l.playPausa()
        assertEquals(FaseDomanda.ALLA_DOMANDA, l.stato.value.fermate.fase)
        l.cambiaSaltabili(false)
        assertFalse(l.stato.value.lezione!!.domandeSaltabili)
        l.ferma()
    }

    @Test
    fun senzaReteSiVedeSubitoLaRispostaGiusta() {
        val ambito = TestScope(StandardTestDispatcher())
        val l = LettoreEsempio(ambito, correttore = { _, _, _ -> Esito.Irraggiungibile }, attesaCorrezioneMs = 0)
        l.carica(lezione(false), null, 0.0, false)
        l.fermaAllaDomanda(1)
        l.rispondi("Per paura del senato")
        ambito.advanceUntilIdleSoltanto()
        assertEquals(StatoRisposta.InCoda("Per paura del senato"), l.stato.value.risposta)
        assertEquals(FaseDomanda.RISPOSTA, l.stato.value.fermate.fase)
        l.ferma()
    }

    /** Fa girare quello che è pronto senza far avanzare l'orologio dell'ascolto all'infinito. */
    private fun TestScope.advanceUntilIdleSoltanto() {
        advanceTimeBy(10)
        runCurrent()
    }
}
