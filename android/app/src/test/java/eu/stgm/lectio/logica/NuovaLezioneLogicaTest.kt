package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.ui.materiale.cambiMateriale
import eu.stgm.lectio.ui.materiale.notaTitolo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Le regole pure della v1.4: struttura in parole, indicazioni, materiale, "Che cos'è", titolo. */
class NuovaLezioneLogicaTest {

    // --- Struttura (contratto §16.1) ------------------------------------------------------------

    @Test
    fun arrotondaPerEccessoAMeta() {
        assertEquals(3, Struttura.arrotonda(2.5))
        assertEquals(2, Struttura.arrotonda(2.4999))
        assertEquals(13, Struttura.arrotonda(12.8))
        assertEquals(6, Struttura.arrotonda(6.4))
        assertEquals(4, Struttura.arrotonda(3.5))
    }

    @Test
    fun spiegazioneComeNelContratto() {
        assertEquals("3 parti, una domanda dopo ognuna e 4 alla fine", Struttura.frase(TipoLezione.SPIEGAZIONE, 15))
        assertEquals("4 parti, una domanda dopo ognuna e 5 alla fine", Struttura.frase(TipoLezione.SPIEGAZIONE, 20))
        assertEquals("6 parti, una domanda dopo ognuna e 6 alla fine", Struttura.frase(TipoLezione.SPIEGAZIONE, 30))
        assertEquals("9 parti, una domanda dopo ognuna e 8 alla fine", Struttura.frase(TipoLezione.SPIEGAZIONE, 45))
        // Fuori tabella: 3 + arrotonda(durata / 10); parti almeno 3.
        assertEquals(6, Struttura.finali(25)) // 3 + arrotonda(2,5) = 3 + 3
        assertEquals(5, Struttura.parti(25)) // arrotonda(5)
        assertEquals(3, Struttura.parti(10))
        assertEquals(4, Struttura.finali(10))
    }

    @Test
    fun ripassoComeNelContratto() {
        assertEquals("I punti chiave in breve e 6 domande alla fine", Struttura.frase(TipoLezione.RIPASSO, 15))
        assertEquals("I punti chiave in breve e 8 domande alla fine", Struttura.frase(TipoLezione.RIPASSO, 20))
        assertEquals("I punti chiave in breve e 10 domande alla fine", Struttura.frase(TipoLezione.RIPASSO, 30))
        assertEquals("I punti chiave in breve e 13 domande alla fine", Struttura.frase(TipoLezione.RIPASSO, 45))
    }

    @Test
    fun interrogazioneComeNelContratto() {
        assertEquals("9 domande come le fa il prof, con la risposta giusta dopo ognuna", Struttura.frase(TipoLezione.INTERROGAZIONE, 15))
        assertEquals("12 domande come le fa il prof, con la risposta giusta dopo ognuna", Struttura.frase(TipoLezione.INTERROGAZIONE, 20))
        assertEquals("18 domande come le fa il prof, con la risposta giusta dopo ognuna", Struttura.frase(TipoLezione.INTERROGAZIONE, 30))
        assertEquals("27 domande come le fa il prof, con la risposta giusta dopo ognuna", Struttura.frase(TipoLezione.INTERROGAZIONE, 45))
    }

    @Test
    fun titoliETipi() {
        assertEquals("Spiegazione di 20 minuti", Struttura.titolo(TipoLezione.SPIEGAZIONE, 20))
        assertEquals("Interrogazione di 45 minuti", Struttura.titolo(TipoLezione.INTERROGAZIONE, 45))
        assertEquals("Interroga", TipoLezione.INTERROGAZIONE.nomeBreve)
        assertEquals(TipoLezione.SPIEGAZIONE, TipoLezione.da(null))
        assertEquals(TipoLezione.RIPASSO, TipoLezione.da("ripasso"))
        // Testi dell'interfaccia: niente punti esclamativi.
        TipoLezione.entries.forEach { assertFalse(it.frase.contains('!')) }
    }

    // --- Indicazioni e spunti -------------------------------------------------------------------

    @Test
    fun unoSpuntoAggiungeETogieLaSuaFrase() {
        val zero = Indicazioni.SPUNTI.first { it.etichetta == "Da zero" }.frase
        val esempi = Indicazioni.SPUNTI.first { it.etichetta == "Esempi" }.frase
        val uno = Indicazioni.alterna("", zero)
        assertEquals(zero, uno)
        assertTrue(Indicazioni.contiene(uno, zero))
        val due = Indicazioni.alterna("Insisti su Seiano.  ", esempi)
        assertEquals("Insisti su Seiano. $esempi", due)
        val tre = Indicazioni.alterna(Indicazioni.alterna(due, zero), esempi)
        assertEquals("Insisti su Seiano. $zero", tre)
        assertFalse(Indicazioni.contiene(tre, esempi))
        assertEquals("Insisti su Seiano.", Indicazioni.alterna(tre, zero))
    }

    @Test
    fun unaFraseCheNonCiStaNon_siAggiungeAMeta() {
        val pieno = "a".repeat(590)
        val frase = Indicazioni.SPUNTI.last().frase
        assertEquals(pieno, Indicazioni.alterna(pieno, frase))
        assertEquals("590 / 600", Indicazioni.contatore(pieno))
    }

    @Test
    fun leSeiPilloleDellaGuida() {
        assertEquals(listOf("Da zero", "Date e nomi", "Confronta", "Descrivi le opere", "Più difficili", "Esempi"), Indicazioni.SPUNTI.map { it.etichetta })
        assertEquals("Spiegalo partendo da zero, come se non l'avessi mai studiato.", Indicazioni.SPUNTI[0].frase)
        assertEquals("Insisti su date e nomi.", Indicazioni.SPUNTI[1].frase)
        assertEquals("Confronta gli autori tra loro: cosa cambia da uno all'altro.", Indicazioni.SPUNTI[2].frase)
    }

    @Test
    fun indicazioniNelRiepilogo() {
        assertEquals("Nessuna: segue il materiale e la scheda del prof", Indicazioni.breve("   "))
        assertEquals("Insisti su Seiano.", Indicazioni.breve(" Insisti su Seiano. "))
        val lungo = "parola ".repeat(40)
        assertTrue(Indicazioni.breve(lungo).endsWith("…"))
        assertTrue(Indicazioni.breve(lungo).length <= 141)
    }

    // --- Materiale ------------------------------------------------------------------------------

    private fun mat(id: Long, titolo: String, tipo: String = "appunti", stato: String = "pronto", testo: String? = null, origine: String? = "foto", data: String? = "2026-11-04") =
        Materiale(id, 1, tipo, titolo, data, origine, null, 100, stato, testo = testo)

    @Test
    fun gruppiRicercaERighe() {
        val elenco = listOf(
            mat(1, "Società e cultura"), mat(2, "Verifica di ottobre", "verifica"), mat(3, "Interrogazione", "interrogazione"),
            mat(4, "Rovinato", stato = "errore"), mat(5, "Da leggere", stato = "in_lettura"),
        )
        assertEquals(listOf(1L, 5L), elenco.filter(SceltaMateriale::visibile).filterNot(SceltaMateriale::delProf).map { it.id })
        assertEquals(listOf(2L, 3L), elenco.filter(SceltaMateriale::delProf).map { it.id })
        assertEquals(listOf(1L), SceltaMateriale.filtra(elenco, "SOCIETA").map { it.id })
        assertEquals(elenco, SceltaMateriale.filtra(elenco, "  "))
        assertEquals("Appunti · foto · 4 novembre", SceltaMateriale.sotto(elenco[0], LocalDate.of(2026, 11, 4)))
        assertEquals("Verifica · scritto", SceltaMateriale.sotto(mat(9, "x", "verifica", origine = "scritto", data = null)))
    }

    @Test
    fun materialeNelRiepilogo() {
        assertEquals("Scegli almeno un materiale", SceltaMateriale.riepilogo(emptyList()))
        assertEquals("Mirone", SceltaMateriale.riepilogo(listOf(mat(1, "Mirone"))))
        assertEquals("2 materiali: A, B", SceltaMateriale.riepilogo(listOf(mat(1, "A"), mat(2, "B"))))
        assertEquals("3 materiali: A, B e un altro", SceltaMateriale.riepilogo(listOf(mat(1, "A"), mat(2, "B"), mat(3, "C"))))
        assertEquals("4 materiali: A, B e altri 2", SceltaMateriale.riepilogo(listOf(mat(1, "A"), mat(2, "B"), mat(3, "C"), mat(4, "D"))))
        assertEquals("1 materiale", SceltaMateriale.quanti(1))
    }

    @Test
    fun perNellePillole() {
        val e = Evento(3, 1, "orale", "Impero", "2026-11-09")
        assertEquals("Interrogazione di lunedì 9 novembre", Per.pillola(e, LocalDate.of(2026, 11, 4)))
        assertEquals("L'interrogazione di lunedì 9 novembre · domande obbligatorie", Per.riepilogo(e, false, LocalDate.of(2026, 11, 4)))
        assertEquals("Nessuna data · domande che si possono saltare", Per.riepilogo(null, true))
    }

    @Test
    fun suggeritiComeIlServer() {
        val elenco = listOf(
            mat(10, "Mirone e il Discobolo"),
            mat(11, "Appunti vari", testo = "Il discobolo di Mirone. Mirone scolpì il discobolo."),
            mat(12, "Fidia", testo = "Mirone una volta."),
            mat(13, "Policleto", stato = "in_lettura"),
        )
        assertEquals(listOf(10L, 11L), ParoleArgomento.suggeriti("Mirone e il Discobolo", elenco))
        // Una parola sola: basta un punto.
        assertEquals(listOf(10L, 11L), ParoleArgomento.suggeriti("Mirone", elenco))
        assertTrue(ParoleArgomento.suggeriti("  ", elenco).isEmpty())
        // Radici: scultura / sculture.
        assertEquals(listOf(1L), ParoleArgomento.suggeriti("Le sculture", listOf(mat(1, "La scultura greca"), mat(2, "Pittura"))))
    }

    // --- "Che cos'è", titolo ---------------------------------------------------------------------

    @Test
    fun famiglieETestiDiGemini() {
        assertEquals(Famiglia.DA_STUDIARE, Famiglia.di(TipoMateriale.LIBRO))
        assertEquals(Famiglia.DEL_PROF, Famiglia.di(TipoMateriale.INTERROGAZIONE))
        assertNull(Famiglia.di(TipoMateriale.ALTRO))
        assertEquals(TipoMateriale.VERIFICA, Condivisione.tipoDaGemini("MATERIA: Storia | PROF: Rossi | DATA: 12/10 | TIPO: scritta | VOTO: 7\n## Domanda 1"))
        assertEquals(TipoMateriale.INTERROGAZIONE, Condivisione.tipoDaGemini("**MATERIA: Storia | PROF: Rossi | TIPO: orale | VOTO: 7**"))
        assertNull(Condivisione.tipoDaGemini("Appunti su Augusto e il principato"))
        assertNull(Condivisione.tipoDaGemini("MATERIA: Storia"))
        assertNull(Condivisione.tipoDaGemini(null))
    }

    @Test
    fun notaSottoIlTitolo() {
        val foto = Materiale(7, 1, "appunti", "Mirone e il Discobolo", origine = "foto", nomeFile = "IMG_1.jpg", titoloDa = "lectio")
        assertEquals("Dalla foto IMG_1.jpg · titolo scritto da Lectio leggendo la foto", notaTitolo(foto))
        assertEquals("Dal file verifica.pdf", notaTitolo(foto.copy(origine = "file", nomeFile = "verifica.pdf", titoloDa = "file")))
        assertNull(notaTitolo(foto.copy(titoloDa = "utente")))
        assertNull("un server vecchio non lo dice: niente", notaTitolo(foto.copy(titoloDa = null)))
        assertEquals("Titolo scritto da Lectio leggendo il testo", notaTitolo(foto.copy(origine = "scritto", nomeFile = null)))
    }

    @Test
    fun soloICampiCambiati() {
        val m = Materiale(7, 1, "appunti", "Foto 4 novembre")
        assertNull(cambiMateriale(m, "Foto 4 novembre", TipoMateriale.APPUNTI))
        assertNull("un titolo vuoto non si manda", cambiMateriale(m, "   ", TipoMateriale.APPUNTI))
        assertEquals("""{"titolo":"Mirone e il Discobolo"}""", cambiMateriale(m, "  Mirone   e il Discobolo ", TipoMateriale.APPUNTI).toString())
        assertEquals("""{"tipo":"verifica"}""", cambiMateriale(m, "Foto 4 novembre", TipoMateriale.VERIFICA).toString())
        assertEquals("""{"titolo":"Discobolo","tipo":"libro"}""", cambiMateriale(m, "Discobolo", TipoMateriale.LIBRO).toString())
    }
}
