package eu.stgm.lectio.logica

import eu.stgm.lectio.domanda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il controllore delle fermate alle domande (aspetto.md §7, contratto §15.2 e §15.4). */
class FermateTest {

    private val d1 = domanda()
    private val d2 = domanda(id = 32, n = 2, inizio = 561.2, fineDomanda = 567.8, inizioRisposta = 570.3, fine = 589.0)

    /** Simula l'ascolto da [da] ad [a] a [velocita], un controllo ogni [passoMs] ms veri. */
    private fun ascolta(c: ControlloreDomande, da: Double, a: Double, velocita: Double = 1.0, passoMs: Long = 150): Pair<Double, List<Comando>> {
        var pos = da
        c.posizione(pos, false)
        val passo = passoMs / 1000.0 * velocita
        while (pos < a) {
            pos += passo
            val comandi = c.posizione(pos, true)
            if (comandi.isNotEmpty()) return pos to comandi
        }
        return pos to emptyList()
    }

    @Test
    fun siFermaAllaFineDellaDomanda() {
        val c = ControlloreDomande(listOf(d1, d2))
        val (pos, comandi) = ascolta(c, 270.0, 300.0)
        assertEquals(listOf(Comando.Pausa), comandi)
        assertEquals(FaseDomanda.ALLA_DOMANDA, c.stato.fase)
        assertEquals(31L, c.stato.domanda?.id)
        // Si ferma subito dopo la fine della domanda, dentro i 2,5 s di silenzio.
        assertTrue("fermo a $pos", pos >= d1.fineDomandaS && pos < d1.inizioRispostaS)
    }

    @Test
    fun primaDellaSogliaNonSiFerma() {
        val c = ControlloreDomande(listOf(d1))
        val (_, comandi) = ascolta(c, 270.0, 286.7)
        assertTrue(comandi.isEmpty())
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
    }

    @Test
    fun aOgniVelocitaSiFermaNelSilenzio() {
        for (v in listOf(0.8, 1.0, 1.2, 1.4, 1.6)) {
            val c = ControlloreDomande(listOf(d1))
            val (pos, comandi) = ascolta(c, 280.0, 300.0, velocita = v)
            assertEquals("velocità $v", listOf(Comando.Pausa), comandi)
            assertTrue("velocità $v: fermo a $pos", pos < d1.inizioRispostaS)
        }
    }

    @Test
    fun controlliRadiNonSaltanoLaDomanda() {
        // Il telefono rallenta: un controllo ogni 1,5 s a 1,6× (2,4 s di audio per passo).
        val c = ControlloreDomande(listOf(d1))
        val (_, comandi) = ascolta(c, 270.0, 300.0, velocita = 1.6, passoMs = 1500)
        assertEquals(listOf(Comando.Pausa), comandi)
    }

    @Test
    fun spentoNonSiFermaMai() {
        val c = ControlloreDomande(listOf(d1, d2), attivo = false)
        val (_, comandi) = ascolta(c, 0.0, 600.0)
        assertTrue(comandi.isEmpty())
    }

    @Test
    fun saltareSopraUnaDomandaNonFerma() {
        val c = ControlloreDomande(listOf(d1))
        c.posizione(250.0, true)
        c.salto(295.0) // un salto con la barra oltre la domanda
        assertTrue(c.posizione(295.15, true).isEmpty())
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
    }

    @Test
    fun unSaltoNonVistoOltreLaRispostaNonFerma() {
        val c = ControlloreDomande(listOf(d1))
        c.posizione(250.0, true)
        assertTrue(c.posizione(320.0, true).isEmpty())
    }

    @Test
    fun tornareIndietroRiarmaLaDomanda() {
        val c = ControlloreDomande(listOf(d1))
        c.salto(295.0)
        c.posizione(296.0, true)
        c.salto(270.0)
        val (_, comandi) = ascolta(c, 270.0, 300.0)
        assertEquals(listOf(Comando.Pausa), comandi)
    }

    @Test
    fun sentiLaRispostaSaltaAllInizioDellaRisposta() {
        val c = ControlloreDomande(listOf(d1))
        ascolta(c, 280.0, 300.0)
        val comandi = c.sentiRisposta()
        assertEquals(listOf(Comando.Vai(289.4), Comando.Riproduci), comandi)
        assertEquals(FaseDomanda.RISPOSTA, c.stato.fase)
        // Mentre suona la risposta non si riferma sulla stessa domanda.
        assertTrue(c.posizione(289.6, true).isEmpty())
    }

    @Test
    fun allaFineDellaRispostaSiAspettaAvanti() {
        val c = ControlloreDomande(listOf(d1))
        ascolta(c, 280.0, 300.0)
        c.sentiRisposta()
        c.posizione(300.9, true)
        assertEquals(listOf(Comando.Pausa), c.posizione(301.05, true))
        assertEquals(FaseDomanda.DOPO_RISPOSTA, c.stato.fase)
        assertEquals(listOf(Comando.Riproduci), c.avanti())
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
    }

    @Test
    fun ripetiTornaAllInizioERiferma() {
        val c = ControlloreDomande(listOf(d1))
        ascolta(c, 280.0, 300.0)
        assertEquals(listOf(Comando.Vai(280.4), Comando.Riproduci), c.ripeti())
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
        val (_, comandi) = ascolta(c, 280.4, 300.0)
        assertEquals(listOf(Comando.Pausa), comandi)
        assertEquals(FaseDomanda.ALLA_DOMANDA, c.stato.fase)
    }

    @Test
    fun avantiAllaDomandaSaltaLaRisposta() {
        val c = ControlloreDomande(listOf(d1))
        ascolta(c, 280.0, 300.0)
        assertEquals(listOf(Comando.Vai(301.0), Comando.Riproduci), c.avanti())
    }

    @Test
    fun ilSaltoChiudeLaScheda() {
        val c = ControlloreDomande(listOf(d1))
        ascolta(c, 280.0, 300.0)
        c.salto(100.0)
        assertEquals(FaseDomanda.ASCOLTO, c.stato.fase)
        assertNull(c.stato.domanda)
    }

    @Test
    fun laSecondaDomandaDopoLaPrima() {
        val c = ControlloreDomande(listOf(d2, d1))
        ascolta(c, 280.0, 300.0)
        c.sentiRisposta()
        c.posizione(301.1, true)
        c.avanti()
        val (_, comandi) = ascolta(c, 301.1, 590.0)
        assertEquals(listOf(Comando.Pausa), comandi)
        assertEquals(32L, c.stato.domanda?.id)
    }

    // --- Il tasto play nei tre modi --------------------------------------------------------------

    @Test
    fun senzaRisposteScritteIlPlayFaSentireLaRisposta() {
        val c = ControlloreDomande(listOf(d1), modo = ModoDomande.SENTI_RISPOSTA)
        ascolta(c, 280.0, 300.0)
        val e = c.premiPlay()
        assertEquals(listOf(Comando.Vai(289.4), Comando.Riproduci), e.comandi)
        assertEquals(false, e.saltata)
        assertEquals(FaseDomanda.RISPOSTA, c.stato.fase)
    }

    @Test
    fun conDomandeSaltabiliIlPlaySalta() {
        val c = ControlloreDomande(listOf(d1), modo = ModoDomande.SCRITTE_SALTABILI)
        ascolta(c, 280.0, 300.0)
        val e = c.premiPlay()
        assertTrue("la domanda va mandata come saltata", e.saltata)
        assertEquals(listOf(Comando.Vai(289.4), Comando.Riproduci), e.comandi)
        assertEquals(FaseDomanda.RISPOSTA, c.stato.fase)
    }

    @Test
    fun conDomandeObbligatorieIlPlayRipeteLaDomandaENonVaAvanti() {
        val c = ControlloreDomande(listOf(d1), modo = ModoDomande.SCRITTE_OBBLIGATORIE)
        ascolta(c, 280.0, 300.0)
        val e = c.premiPlay()
        assertEquals(false, e.saltata)
        assertEquals(listOf(Comando.Vai(280.4), Comando.Riproduci), e.comandi)
        // Ripetuta la domanda, l'audio si riferma lì: senza risposta non si va oltre.
        val (pos, comandi) = ascolta(c, 280.4, 320.0)
        assertEquals(listOf(Comando.Pausa), comandi)
        assertTrue(pos < d1.inizioRispostaS)
        assertEquals(FaseDomanda.ALLA_DOMANDA, c.stato.fase)
    }

    @Test
    fun ilPlayFuoriDallaDomandaEUnPlayNormale() {
        for (modo in ModoDomande.entries) {
            val c = ControlloreDomande(listOf(d1), modo = modo)
            assertEquals(EsitoPlay(listOf(Comando.Riproduci)), c.premiPlay())
        }
    }

    @Test
    fun ilModoDipendeDaRisposteScritteESaltabili() {
        assertEquals(ModoDomande.SENTI_RISPOSTA, ModoDomande.da(risposteScritte = false, saltabili = true))
        assertEquals(ModoDomande.SCRITTE_SALTABILI, ModoDomande.da(risposteScritte = true, saltabili = true))
        assertEquals(ModoDomande.SCRITTE_OBBLIGATORIE, ModoDomande.da(risposteScritte = true, saltabili = false))
    }

    @Test
    fun fermoSuUnaDomandaDaRipassare() {
        val c = ControlloreDomande(listOf(d1, d2), modo = ModoDomande.SCRITTE_OBBLIGATORIE)
        c.fermaA(d2)
        assertEquals(FaseDomanda.ALLA_DOMANDA, c.stato.fase)
        assertEquals(listOf(Comando.Vai(570.3), Comando.Riproduci), c.riproduci())
    }

    @Test
    fun etichetteENotifica() {
        assertEquals("DOMANDA 2 DI 6", etichettaDomanda(d2))
        assertEquals("DOMANDA DEL BLOCCO", etichettaDomanda(d2.copy(n = 0, di = 0)))
        assertEquals("Domanda 2 di 6 · play per la risposta", rigaNotificaDomanda(d2, ModoDomande.SENTI_RISPOSTA))
        assertEquals("Domanda 2 di 6 · rispondi nell'app o premi play per saltarla", rigaNotificaDomanda(d2, ModoDomande.SCRITTE_SALTABILI))
        assertEquals("Domanda 2 di 6 · rispondi nell'app", rigaNotificaDomanda(d2, ModoDomande.SCRITTE_OBBLIGATORIE))
    }
}
