package eu.stgm.lectio.ui

import eu.stgm.lectio.dati.Conteggi
import eu.stgm.lectio.dati.Centratura
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.ui.altre.righe
import eu.stgm.lectio.ui.impostazioni.tettoValido
import eu.stgm.lectio.ui.materiale.testoInterrogazione
import eu.stgm.lectio.ui.materie.scriviProf
import eu.stgm.lectio.ui.materie.sottoMateria
import eu.stgm.lectio.ui.abbinamento.soloCifre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La navigazione tra le pagine e qualche regola piccola delle schermate. */
class NavigazioneTest {

    @Test
    fun indietroChiudeLaPaginaPoiTornaAOggi() {
        val n = Navigazione(Scheda.MATERIE).apri(Pagina.Materia(1)).apri(Pagina.NuovaLezione(1, 3))
        assertEquals(Pagina.NuovaLezione(1, 3), n.inCima)
        val dopo = n.indietro()!!.indietro()!!
        assertEquals(Navigazione(Scheda.MATERIE), dopo)
        assertEquals(Navigazione(Scheda.OGGI), dopo.indietro())
        assertNull(Navigazione().indietro())
    }

    @Test
    fun nuovaLezioneDiventaInPreparazione() {
        val n = Navigazione().apri(Pagina.Materia(1)).apri(Pagina.NuovaLezione(1)).sostituisci(Pagina.Preparazione(13))
        assertEquals(listOf(Pagina.Materia(1), Pagina.Preparazione(13)), n.pagine)
    }

    @Test
    fun codificaTuttoEPoiLoRilegge() {
        val n = Navigazione(Scheda.LEZIONI, listOf(
            Pagina.Materia(1), Pagina.ModificaScheda(1), Pagina.TestoMateriale(7), Pagina.NuovaLezione(1, null),
            Pagina.NuovaLezione(2, 3), Pagina.Preparazione(13), Pagina.Lettore(12), Pagina.Lettore(12, 32),
            Pagina.ComeAndata(4), Pagina.Prossime, Pagina.Impostazioni, Pagina.Abbinamento,
        ))
        assertEquals(n, Navigazione.decodifica(n.codifica()))
        assertEquals(Navigazione(), Navigazione.decodifica("spazzatura"))
    }

    @Test
    fun regolePiccole() {
        assertEquals("Prof. Rossi", scriviProf("Rossi"))
        assertEquals("Prof.ssa Bianchi", scriviProf("Prof.ssa Bianchi"))
        assertNull(scriviProf("  "))
        assertEquals("Prof. Rossi · 4 verifiche · 63%", sottoMateria(Materia(1, "Storia", "Rossi", "ambra", Conteggi(4, 1, 3, 2), Centratura(10, 16, 63))))
        assertEquals("Prof. Rossi · 1 interrogazione", sottoMateria(Materia(4, "Scienze umane", "Rossi", "viola", Conteggi(0, 1, 0, 0))))
        assertEquals("ancora vuota", sottoMateria(Materia(5, "Nuova", "", "blu")))
        assertEquals(2.5, tettoValido(2.4), 0.0)
        assertEquals(0.5, tettoValido(0.0), 0.0)
        assertEquals(10.0, tettoValido(12.0), 0.0)
        assertEquals("48213377", soloCifre("4821 3377"))
        assertEquals(listOf("Uno", "Due"), righe(" Uno \n\n Due"))
        assertEquals("Cosa mi ha chiesto il prof:\nChi era Seiano?\n\nCosa ho risposto:\nIl capo dei pretoriani", testoInterrogazione("Chi era Seiano?", "Il capo dei pretoriani", ""))
    }
}
