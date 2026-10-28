package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Avanzamento
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.dati.Voce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Stime, costi mostrati, tetto (aspetto.md §6 e §0.6) e i formati di date e tempi. */
class CostiEFormatiTest {

    private fun stima(costo: Double, voce: Double, consentita: Boolean = true, speso: Double = 0.80, tetto: Double = 2.0) =
        Stima(18300, costo - voce, voce, costo, speso, tetto, speso + costo, consentita, if (consentita) null else "Sforerebbe il tetto di 2 €: usa la voce gratis", 412_000, "2026-11-01")

    @Test
    fun soldiComeNelPrototipo() {
        assertEquals("0,25 €", Formati.euro(0.25))
        assertEquals("2 €", Formati.euro(2.0))
        assertEquals("0 €", Formati.euro(0.0))
        assertEquals("1,05 €", Formati.euro(1.05))
        assertEquals("0,80 €", Formati.euro(0.8))
        assertEquals("10 €", Formati.euro(10.0))
        assertEquals("0,4 cent", Formati.cent(Costi.COSTO_FOTO_EUR))
        assertEquals("1,2 cent", Formati.cent(Costi.costoFoto(3)))
    }

    @Test
    fun pulsanteEBarraConLaVoceEspressiva() {
        val v = VistaCosto.calcola(stima(0.25, 0.23), stima(0.02, 0.0), null, TipoVoce.ESPRESSIVA)
        assertEquals("Prepara la lezione · 0,25 €", v.etichettaPulsante)
        assertEquals(0.23, v.costoVoceEspressiva!!, 1e-9)
        assertFalse(v.espressivaSpenta)
        assertEquals("Questo mese 0,80 €", v.rigaMese)
        assertEquals("dopo questa 1,05 € di 2 €", v.rigaDopo)
        assertEquals(0.4f, v.frazioneSpeso, 1e-4f)
        assertEquals(0.125f, v.frazioneQuesta, 1e-4f)
        assertEquals(412_000L, v.caratteriGratisRimasti)
        assertTrue(v.consentita)
    }

    @Test
    fun conLaVoceGratisSiPagaSoloIlTesto() {
        val v = VistaCosto.calcola(stima(0.25, 0.23), stima(0.02, 0.0), null, TipoVoce.GRATIS)
        assertEquals("Prepara la lezione · 0,02 €", v.etichettaPulsante)
        assertEquals(TipoVoce.GRATIS, v.voceScelta)
    }

    @Test
    fun ilTettoSpegneLaVoceEspressivaESceglieLaGratis() {
        val v = VistaCosto.calcola(stima(0.61, 0.57, consentita = false, speso = 1.70), stima(0.04, 0.0, speso = 1.70), null, TipoVoce.ESPRESSIVA)
        assertTrue(v.espressivaSpenta)
        assertEquals("Sforerebbe il tetto di 2 €", v.motivoSpenta)
        assertEquals(TipoVoce.GRATIS, v.voceScelta)
        assertEquals("Prepara la lezione · 0,04 €", v.etichettaPulsante)
        assertTrue(v.consentita)
        assertNull(v.blocco)
    }

    @Test
    fun seNienteStaNelTettoNonSiPrepara() {
        val v = VistaCosto.calcola(stima(0.61, 0.57, consentita = false, speso = 1.99), stima(0.04, 0.0, consentita = false, speso = 1.99), null, TipoVoce.ESPRESSIVA)
        assertFalse(v.consentita)
        assertTrue(v.blocco!!.startsWith("Sforerebbe il tetto"))
    }

    @Test
    fun laBarraNonEsceDalBinario() {
        val v = VistaCosto.calcola(stima(0.5, 0.45, speso = 1.9), null, null, TipoVoce.ESPRESSIVA)
        assertTrue(v.frazioneSpeso + v.frazioneQuesta <= 1.0001f)
    }

    @Test
    fun modalitaProvaACostoZero() {
        val p = Stima(1000, 0.0, 0.0, 0.0, 0.0, 2.0, 0.0, true, null, null, "2026-11-01")
        val v = VistaCosto.calcola(null, null, p, TipoVoce.PROVA)
        assertEquals(TipoVoce.PROVA, v.voceScelta)
        assertEquals("Prepara la lezione · 0 €", v.etichettaPulsante)
    }

    @Test
    fun sceltaDelleVoci() {
        val elenco = listOf(
            Voce("mistral:voice_482", "Lorenzo", "espressiva", "mistral", 0.0126, true),
            Voce("azure:it-IT-IsabellaMultilingualNeural", "Isabella", "gratis", "azure", 0.0, true),
        )
        val v = Costi.voci(elenco)
        assertEquals("Lorenzo", v.espressiva?.nome)
        assertEquals(TipoVoce.ESPRESSIVA, Costi.voceIniziale(v, "mistral:voice_482"))
        assertEquals(TipoVoce.GRATIS, Costi.voceIniziale(v, "azure:it-IT-IsabellaMultilingualNeural"))
        val soloProva = Costi.voci(listOf(Voce("prova:espeak", "Voce di prova", "prova")))
        assertTrue(soloProva.soloProva)
        assertEquals(TipoVoce.PROVA, Costi.voceIniziale(soloProva, null))
        val senzaAzure = Costi.voci(elenco.map { if (it.tipo == "gratis") it.copy(disponibile = false) else it })
        assertNull(senzaAzure.gratis)
    }

    @Test
    fun avvisoAll80PerCento() {
        assertFalse(Costi.avviso(1.59, 2.0))
        assertTrue(Costi.avviso(1.60, 2.0))
    }

    @Test
    fun dateInItaliano() {
        val oggi = LocalDate.of(2026, 10, 28)
        assertEquals("mercoledì 28 ottobre", Formati.giorno(oggi, oggi))
        assertEquals("venerdì 30 ottobre", Formati.giorno(LocalDate.of(2026, 10, 30), oggi))
        assertEquals("domenica 1° novembre", Formati.giorno(LocalDate.of(2026, 11, 1), oggi))
        assertEquals("venerdì 1° gennaio 2027", Formati.giorno(LocalDate.of(2027, 1, 1), oggi))
        assertEquals("Si azzera il 1° novembre", Formati.siAzzera("2026-11-01", oggi))
        assertEquals("tra 2 giorni", Formati.traQuanto(2))
        assertEquals("domani", Formati.traQuanto(1))
        assertEquals("oggi", Formati.traQuanto(0))
        assertEquals("ieri", Formati.traQuanto(-1))
        assertEquals("6 giorni", Formati.giorniBreve(6))
    }

    @Test
    fun tempiDelLettore() {
        assertEquals("07:42", Formati.tempo(462.0))
        assertEquals("-12:05", Formati.mancante(725.0))
        assertEquals("1:02:03", Formati.tempo(3723.0))
        assertEquals("5:01", Formati.durataBreve(301.0))
        assertEquals("mancano 8 min", Formati.mancanoMinuti(470.0))
        assertEquals("manca 1 min", Formati.mancanoMinuti(10.0))
        assertEquals("1,0×", Formati.velocita(1.0f))
        assertEquals("1,4×", Formati.velocita(1.4f))
        assertEquals("412.000", Formati.numero(412_000))
    }

    @Test
    fun titoliDegliEventi() {
        fun e(arg: String, tipo: String = "scritta") = Evento(1, 1, tipo, arg, "2026-10-30")
        assertEquals("Verifica sull'Impero romano", Formati.titoloEvento(e("L'Impero romano")))
        assertEquals("Verifica sulla rivoluzione francese", Formati.titoloEvento(e("La rivoluzione francese")))
        assertEquals("Verifica sul Rinascimento", Formati.titoloEvento(e("Il Rinascimento")))
        assertEquals("Interrogazione sui Romani", Formati.titoloEvento(e("I Romani", "orale")))
        assertEquals("Verifica su equazioni di secondo grado", Formati.titoloEvento(e("equazioni di secondo grado")))
        assertEquals("Verifica", Formati.titoloEvento(e("")))
    }

    @Test
    fun fasiDellaPreparazione() {
        val voce = Lezione(stato = "in_preparazione", avanzamento = Avanzamento("voce", 3, 7, "Registro la voce · 3 di 7"))
        val passi = Preparazione.passi(voce)
        assertEquals(listOf(StatoPasso.FATTO, StatoPasso.FATTO, StatoPasso.IN_CORSO, StatoPasso.DA_FARE), passi.map { it.stato })
        assertEquals("Registro la voce · 3 di 7", passi[2].testo)
        assertEquals("Registro la voce · 3 di 7", Preparazione.frase(voce))
        val f = Preparazione.frazione(voce)
        assertTrue(f > 0.5f && f < 0.92f)
        assertEquals(1f, Preparazione.frazione(voce.copy(stato = "pronta")), 0f)
        assertEquals(0f, Preparazione.frazione(voce.copy(stato = "in_coda")), 0f)
        assertTrue(Preparazione.passi(voce.copy(stato = "pronta")).all { it.stato == StatoPasso.FATTO })
        val lettura = Preparazione.passi(voce.copy(avanzamento = Avanzamento("piano", 0, 0)))
        assertEquals(StatoPasso.IN_CORSO, lettura[0].stato)
        assertTrue(Preparazione.frase(voce.copy(stato = "in_coda", avanzamento = null)).startsWith("In coda"))
    }
}
