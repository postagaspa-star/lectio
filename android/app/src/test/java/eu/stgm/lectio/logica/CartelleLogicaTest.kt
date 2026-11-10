package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.dati.MemoriaNovita
import eu.stgm.lectio.lavori.novitaDaMostrare
import eu.stgm.lectio.rete.Esito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Le frasi e le regole delle cartelle (aspetto.md §42), senza schermate. */
class CartelleLogicaTest {
    private val oggi = LocalDate.of(2026, 11, 10)

    private fun m(id: Long, titolo: String, cartella: Long? = null, tipo: String = "appunti", stato: String = "pronto", data: String? = "2026-11-04") =
        Materiale(id, 7, tipo, titolo, data, "scritto", null, 100, stato, cartellaId = cartella)

    private val greca = Cartella(3, 7, "Arte greca")
    private val romana = Cartella(4, 7, "Arte romana")

    @Test
    fun frasiDelleCartelle() {
        assertEquals("Vuota", TestiCartelle.quantiFile(0))
        assertEquals("1 file", TestiCartelle.quantiFile(1))
        assertEquals("8 file", TestiCartelle.quantiFile(8))
        assertNull(TestiCartelle.messiDaLectio(0))
        assertEquals("1 messo ora da Lectio", TestiCartelle.messiDaLectio(1))
        assertEquals("2 messi ora da Lectio", TestiCartelle.messiDaLectio(2))
        assertEquals("Storia dell'Arte · 8 file", TestiCartelle.sottoTestata("Storia dell'Arte", 8))
        assertEquals("Metti in Arte greca", TestiCartelle.titoloMetti("Arte greca"))
        assertEquals("1 scelto", TestiCartelle.scelti(1))
        assertEquals("3 scelti", TestiCartelle.scelti(3))
        assertEquals("Metti qui 1 file", TestiCartelle.pulsanteMetti(1))
        assertEquals("Metti qui 3 file", TestiCartelle.pulsanteMetti(3))
        assertEquals("Fatto: 3 file in Arte greca", TestiCartelle.fatto(3, "Arte greca"))
        assertEquals("Eliminare la cartella «Arte greca»?", TestiCartelle.titoloElimina("Arte greca"))
        assertEquals("Dentro ci sono 8 file.", TestiCartelle.dentro(8))
        assertEquals("Dentro c'è 1 file.", TestiCartelle.dentro(1))
        assertEquals("Cartella eliminata", TestiCartelle.eliminata(0))
        assertEquals("Cartella e 8 file eliminati", TestiCartelle.eliminata(8))
        assertEquals("Arte greca", TestiCartelle.pulisciNome("  Arte   greca "))
    }

    @Test
    fun lArticoloDavantiAlNumero() {
        assertEquals("Elimina anche gli 8 file", TestiCartelle.eliminaAnche(8))
        assertEquals("Elimina anche gli 11 file", TestiCartelle.eliminaAnche(11))
        assertEquals("Elimina anche gli 80 file", TestiCartelle.eliminaAnche(80))
        assertEquals("Elimina anche i 2 file", TestiCartelle.eliminaAnche(2))
        assertEquals("Elimina anche i 10 file", TestiCartelle.eliminaAnche(10))
        assertEquals("Elimina anche il file", TestiCartelle.eliminaAnche(1))
    }

    @Test
    fun sottoLeRigheDelFoglioMetti() {
        assertEquals("Appunti · 4 novembre", TestiCartelle.sottoScelta(m(1, "A"), null, oggi))
        assertEquals("In Arte romana · Appunti", TestiCartelle.sottoScelta(m(1, "A", 4), "Arte romana", oggi))
        assertEquals("Lo sto leggendo", TestiCartelle.sottoScelta(m(1, "A", stato = "in_lettura"), null, oggi))
    }

    @Test
    fun chiSiPuoMettereQui() {
        val materiale = listOf(
            m(1, "Il Partenone"), m(2, "Fidia", 3), m(3, "Il Colosseo", 4), m(4, "Foto rovinata", stato = "errore"),
            m(5, "Prassitele", stato = "in_lettura"), m(6, "Augusto di Prima Porta", 4),
        )
        val candidati = MettiQui.candidati(materiale, 3)
        // Non quelli già qui, non quelli in errore; quelli in lettura sì.
        assertEquals(listOf(1L, 3L, 5L, 6L), candidati.map { it.id })
        assertEquals(listOf(1L, 5L), MettiQui.fuori(candidati).map { it.id })
        assertEquals(listOf(3L, 6L), MettiQui.altrove(candidati, listOf(romana, greca)).map { it.id })
        // I proposti valgono solo tra quelli che si possono scegliere.
        assertEquals(setOf(1L), MettiQui.iniziali(listOf(1, 2, 99), candidati))
        assertEquals(listOf(1L, 6L), MettiQui.daMandare(candidati, listOf(greca, romana), setOf(6, 1, 2)))
        assertTrue(!MettiQui.conCerca(candidati))
        assertTrue(MettiQui.conCerca((1L..7L).map { m(it, "x") }))
    }

    @Test
    fun lePilloleCartelleNellaSceltaDelMateriale() {
        val pronti = listOf(m(1, "Il Partenone", 3), m(2, "Fidia", 3), m(3, "Il Colosseo", 4), m(5, "Augusto"))
        val pillole = SceltaMateriale.cartelleConPronti(listOf(romana, greca, Cartella(9, 7, "Vuota")), pronti)
        // In ordine alfabetico, solo quelle con materiale pronto, ognuna con i suoi id.
        assertEquals(listOf("Arte greca" to listOf(1L, 2L), "Arte romana" to listOf(3L)), pillole.map { it.first.nome to it.second })
        // Tocco: se non era tutto spuntato lo spunta tutto, altrimenti lo toglie.
        assertEquals(setOf(1L, 2L, 5L), SceltaMateriale.alternaCartella(setOf(1L, 5L), listOf(1L, 2L)))
        assertEquals(setOf(5L), SceltaMateriale.alternaCartella(setOf(1L, 2L, 5L), listOf(1L, 2L)))
        // Il nome della cartella dopo il tipo, e la ricerca anche per cartella.
        assertEquals("Appunti · Arte greca · scritto · 4 novembre", SceltaMateriale.sotto(pronti[0], oggi, "Arte greca"))
        assertEquals("Appunti · scritto · 4 novembre", SceltaMateriale.sotto(pronti[3], oggi))
        val nomi = mapOf(3L to "Arte greca", 4L to "Arte romana")
        assertEquals(listOf(1L, 2L), SceltaMateriale.filtra(pronti, "greca") { x -> x.cartellaId?.let { nomi[it] } }.map { it.id })
        assertEquals(listOf(3L), SceltaMateriale.filtra(pronti, "colosseo") { x -> x.cartellaId?.let { nomi[it] } }.map { it.id })
    }

    @Test
    fun laNovitaMaterialeInCartellaSiMostra() = runBlocking {
        val memoria = object : MemoriaNovita {
            var c = 5L
            val viste = mutableSetOf<Long>()
            override suspend fun cursore() = c
            override suspend fun ricordaCursore(id: Long) { c = id }
            override suspend fun avvisate() = viste.toSet()
            override suspend fun segnaAvvisata(lezioneId: Long) { viste += lezioneId }
        }
        val nuove = listOf(
            Novita(6, "materiale_in_cartella", "Ho messo «Il Partenone» in Arte greca.", quando = "2026-11-10T10:00:00+01:00", materiaId = 7),
            Novita(7, "materiale_letto", "Ho letto «Fidia».", materiaId = 7),
        )
        val da = novitaDaMostrare({ Esito.Ok(nuove) }, memoria)
        assertEquals(listOf("materiale_in_cartella", "materiale_letto"), da.map { it.tipo })
    }
}
