package eu.stgm.lectio.logica

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Cartelle e zip (le stesse regole del computer): cosa si manda, cosa resta fuori e con quale frase. */
class ArchiviTest {

    @get:Rule
    val cartella = TemporaryFolder()

    private fun zip(charset: Charset = Charsets.UTF_8, vararg voci: Pair<String, ByteArray?>): ByteArray {
        val uscita = ByteArrayOutputStream()
        ZipOutputStream(uscita, charset).use { z ->
            for ((nome, dati) in voci) {
                val voce = ZipEntry(nome)
                if (dati == null) {
                    z.putNextEntry(voce)
                } else {
                    z.putNextEntry(voce)
                    z.write(dati)
                }
                z.closeEntry()
            }
        }
        return uscita.toByteArray()
    }

    private fun stored(nome: String, dati: ByteArray): ByteArray {
        val uscita = ByteArrayOutputStream()
        ZipOutputStream(uscita).use { z ->
            val voce = ZipEntry(nome)
            voce.method = ZipEntry.STORED
            voce.size = dati.size.toLong()
            voce.crc = CRC32().apply { update(dati) }.value
            z.putNextEntry(voce)
            z.write(dati)
            z.closeEntry()
        }
        return uscita.toByteArray()
    }

    private fun testo(n: Int) = "Appunti di storia numero $n. ".repeat(30).toByteArray()

    private fun apri(byte: ByteArray, nome: String = "Materiale storia.zip"): Raccolta {
        val r = Raccolta()
        Archivi.estrai(ByteArrayInputStream(byte), nome, cartella.newFolder(), r, r.radice("zip", nome))
        return r
    }

    @Test
    fun loZipDaIFileCheLectioLeggeAncheDalloZipDentro() {
        val dentro = zip(Charsets.UTF_8, "foto lavagna.jpg" to byteArrayOf(-1, -40, -1, 1, 2), "IMG_1.heic" to byteArrayOf(1, 2, 3))
        val r = apri(
            zip(
                Charsets.UTF_8,
                "Storia/" to null,
                "Storia/verifica 1.pdf" to "%PDF-1.4 ".toByteArray() + testo(1),
                "Storia/appunti.txt" to testo(2),
                "Storia/video.mp4" to byteArrayOf(1),
                "__MACOSX/Storia/._appunti.txt" to byteArrayOf(1),
                "Storia/.DS_Store" to byteArrayOf(1),
                "Storia/~\$bozza.docx" to byteArrayOf(1),
                "Storia/Thumbs.db" to byteArrayOf(1),
                "Storia/vuoto.txt" to ByteArray(0),
                "Storia/libro intero.pdf" to ByteArray(Condivisione.MASSIMO_BYTE.toInt() + 1),
                "Storia/Medioevo/crociate.md" to testo(4),
                "dentro.zip" to dentro,
            ),
        )
        assertEquals(listOf("foto lavagna.jpg", "IMG_1.heic", "appunti.txt", "crociate.md", "verifica 1.pdf"), r.buoni.map { it.nome })
        assertEquals("Materiale storia.zip/Storia/Medioevo/crociate.md", r.buoni[3].percorso)
        assertEquals(GenereFile.HEIC, r.buoni[1].genere)
        assertTrue("i file sono già nella cache", r.buoni.all { it.file!!.isFile })
        assertEquals(String(testo(2)), r.buoni[2].file!!.readText())
        assertTrue(r.buoni[0].file!!.readBytes().contentEquals(byteArrayOf(-1, -40, -1, 1, 2)))
        assertEquals(
            mapOf("video.mp4" to MotivoFuori.FORMATO, "vuoto.txt" to MotivoFuori.VUOTO, "libro intero.pdf" to MotivoFuori.GRANDE),
            r.fuori.associate { it.nome to it.motivo },
        )
        assertEquals("5 file da Materiale storia.zip", Archivi.descrizione(r))
        assertEquals(
            listOf(
                "Lascio fuori 1 file che Lectio non sa leggere: video.mp4.",
                "Lascio fuori 1 file vuoto: vuoto.txt.",
                "Lascio fuori 1 file più grande di 20 MB: libro intero.pdf.",
            ),
            Archivi.frasiFuori(r.fuori),
        )
    }

    @Test
    fun nomiDeiVecchiZipDiWindowsEDelMac() {
        // Windows vecchio: CP850 senza il segno UTF-8 (è = 0x8A, ° = 0xF8).
        val r = apri(zip(Charset.forName("IBM850"), "Verifica 1° perchè.txt" to testo(1)))
        assertEquals(listOf("Verifica 1° perchè.txt"), r.buoni.map { it.nome })
        // UTF-8 senza il segno (Mac): letto come ISO-8859-1 diventa "perchÃ©", si riconosce.
        assertEquals("perché.pdf", Archivi.nomeVero("perchÃ©.pdf"))
        // Già giusto (zip col segno UTF-8): resta com'è.
        assertEquals("perché.pdf", Archivi.nomeVero("perché.pdf"))
        assertEquals("Ελλάδα.txt", Archivi.nomeVero("Ελλάδα.txt"))
        assertEquals("a.txt", Archivi.nomeVero("a.txt"))
        assertEquals(128, Archivi.CP850.length)
    }

    @Test
    fun voceNonCompressa() {
        val r = apri(stored("note.txt", testo(3)))
        assertEquals(String(testo(3)), r.buoni.single().file!!.readText())
    }

    @Test
    fun zipRovinatoOVuotoLoDice() {
        val r = apri("non sono uno zip".toByteArray(), "rotto.zip")
        assertTrue(r.buoni.isEmpty())
        assertEquals(listOf(Fuori("rotto.zip", MotivoFuori.ROTTO)), r.fuori)
        assertEquals("Non riesco ad aprire lo zip: forse è rovinato o non è finito di scaricare.", Archivi.fraseNiente(r))
        val soloVideo = apri(zip(Charsets.UTF_8, "film.mp4" to byteArrayOf(1)), "video.zip")
        assertEquals("Nello zip non c'è niente che Lectio sa leggere: PDF, Word, ODT, RTF, testo o foto.", Archivi.fraseNiente(soloVideo))
    }

    @Test
    fun oltreIlLimiteSiMandanoIPrimi() {
        val voci = (1..Archivi.MASSIMO_FILE + 5).map { "f$it.txt" to "x".toByteArray() }.toTypedArray()
        val r = apri(zip(Charsets.UTF_8, *voci))
        assertEquals(Archivi.MASSIMO_FILE, r.buoni.size)
        assertTrue(r.troppi)
        assertTrue(Archivi.avvisi(r).last().startsWith("Sono troppi file insieme: mando i primi ${Archivi.MASSIMO_FILE}"))
    }

    @Test
    fun avanziZipEFrasi() {
        for (p in listOf("__MACOSX/a.pdf", "Storia/.DS_Store", "a/._b.pdf", "Thumbs.db", "x/desktop.ini", "x/~\$v.docx", "")) assertTrue(p, Archivi.daIgnorare(p))
        for (p in listOf("Storia/verifica.pdf", "a.b.c.txt", "note~.txt")) assertFalse(p, Archivi.daIgnorare(p))
        assertTrue(Archivi.eZip(null, "Materiale.ZIP"))
        assertTrue(Archivi.eZip("application/x-zip-compressed", "download"))
        assertTrue(Archivi.eZip("application/zip; charset=binary", null))
        assertFalse(Archivi.eZip("application/pdf", "a.pdf"))
        assertEquals("a", Archivi.elencoNomi(listOf("a")))
        assertEquals("a e b", Archivi.elencoNomi(listOf("a", "b")))
        assertEquals("a, b, c e un altro", Archivi.elencoNomi(listOf("a", "b", "c", "d")))
        assertEquals("a, b, c e altri 2", Archivi.elencoNomi(listOf("a", "b", "c", "d", "e")))
        val r = Raccolta()
        val radice = r.radice("cartella", "Filosofia")
        r.documento("content://x/1", "Platone.pdf", "application/pdf", 1000, "Filosofia/Platone.pdf", radice)
        r.documento("content://x/2", "voti.xlsx", "application/vnd.ms-excel", 1000, "Filosofia/voti.xlsx", radice)
        r.documento("content://x/3", "IMG.heic", "image/heic", 30L * 1024 * 1024, "Filosofia/IMG.heic", radice)
        r.documento("content://x/4", "libro.pdf", "application/pdf", 30L * 1024 * 1024, "Filosofia/libro.pdf", radice)
        assertEquals(listOf("Platone.pdf", "IMG.heic"), r.buoni.map { it.nome })
        assertEquals("2 file dalla cartella Filosofia", Archivi.descrizione(r))
        assertEquals(listOf("Lascio fuori 1 file che Lectio non sa leggere: voti.xlsx.", "Lascio fuori 1 file più grande di 20 MB: libro.pdf."), Archivi.frasiFuori(r.fuori))
    }
}
