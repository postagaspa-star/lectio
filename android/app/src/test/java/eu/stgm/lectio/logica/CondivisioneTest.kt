package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Materia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Condividi → Lectio" e il selettore di file: quali file si leggono e come arrivano al server. */
class CondivisioneTest {

    @Test
    fun iFormatiDelContratto() {
        assertEquals(GenereFile.PDF, Condivisione.genere("application/pdf", "verifica.pdf"))
        assertEquals(GenereFile.WORD, Condivisione.genere("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx"))
        assertEquals(GenereFile.ODT, Condivisione.genere(null, "appunti.odt"))
        assertEquals(GenereFile.RTF, Condivisione.genere("text/rtf", null))
        assertEquals(GenereFile.TESTO, Condivisione.genere("text/plain", "trascrizione.txt"))
        assertEquals(GenereFile.MARKDOWN, Condivisione.genere(null, "note.MD"))
        assertEquals(GenereFile.JPEG, Condivisione.genere("image/jpeg", "IMG_1.JPG"))
        assertEquals(GenereFile.PNG, Condivisione.genere("image/png", null))
        assertEquals(GenereFile.WEBP, Condivisione.genere("image/webp", "x.webp"))
    }

    @Test
    fun ilNomeContaPiuDelTipoDichiarato() {
        // Molte app dichiarano "application/octet-stream": conta l'estensione.
        assertEquals(GenereFile.PDF, Condivisione.genere("application/octet-stream", "verifica.pdf"))
        assertEquals(GenereFile.PDF, Condivisione.genere("application/pdf; charset=binary", null))
    }

    @Test
    fun ilVecchioDocEAltroNonSiLeggono() {
        assertNull(Condivisione.genere("application/msword", "vecchio.doc"))
        assertNull(Condivisione.genere("video/mp4", "film.mp4"))
        assertNull(Condivisione.genere(null, null))
        assertTrue(Condivisione.NON_LEGGIBILE.isNotBlank())
    }

    @Test
    fun heicDiventaJpeg() {
        val g = Condivisione.genere("image/heic", "IMG_2041.HEIC")
        assertEquals(GenereFile.HEIC, g)
        assertTrue(g!!.daConvertire)
        assertEquals("IMG_2041.jpg", Condivisione.nomeDaCaricare("IMG_2041.HEIC", g))
        assertEquals("image/jpeg", Condivisione.mimeDaCaricare(g))
        assertEquals(GenereFile.HEIC, Condivisione.genere("image/heif", null))
        assertFalse(GenereFile.JPEG.daConvertire)
    }

    @Test
    fun nomiDaCaricare() {
        assertEquals("verifica.pdf", Condivisione.nomeDaCaricare("content/verifica.pdf", GenereFile.PDF))
        assertEquals("foto-3.jpg", Condivisione.nomeDaCaricare(null, GenereFile.JPEG, 2))
        assertEquals("file-1.docx", Condivisione.nomeDaCaricare("", GenereFile.WORD))
    }

    @Test
    fun ilTestoCondivisoDiventaUnFileTxt() {
        assertEquals("verifica-da-gemini.txt", Condivisione.nomeTesto(null))
        assertEquals("Verifica di Storia.txt", Condivisione.nomeTesto("Verifica di Storia!"))
    }

    @Test
    fun laMateriaDalTestoDiGemini() {
        val materie = listOf(Materia(1, "Storia", "Rossi"), Materia(2, "Diritto ed economia", "Bianchi"))
        assertEquals(1L, Condivisione.materiaDalTesto("MATERIA: Storia | PROF: Rossi | DATA: 12/10 | TIPO: scritta", materie))
        assertEquals(2L, Condivisione.materiaDalTesto("materia: diritto | prof: Bianchi", materie))
        assertNull(Condivisione.materiaDalTesto("MATERIA: … | PROF: …", materie))
        assertNull(Condivisione.materiaDalTesto("Una verifica qualsiasi", materie))
        assertNull(Condivisione.materiaDalTesto(null, materie))
    }

    @Test
    fun ilCostoDelleFoto() {
        assertEquals(2, Condivisione.quanteFoto(listOf(GenereFile.JPEG, GenereFile.PDF, GenereFile.HEIC, null)))
    }

    @Test
    fun iTipiDelSelettoreSonoQuelliDelManifest() {
        assertTrue("application/pdf" in Condivisione.TIPI_SELETTORE)
        assertTrue("image/heic" in Condivisione.TIPI_SELETTORE)
        assertTrue(TipoMateriale.SCELTE.map { it.etichetta } == listOf("Verifica corretta", "Interrogazione", "Appunti", "Libro"))
        assertEquals(TipoMateriale.ALTRO, TipoMateriale.da("sconosciuto"))
    }
}
