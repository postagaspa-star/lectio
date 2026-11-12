package eu.stgm.lectio.logica

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Perché un file di una cartella o di uno zip resta fuori: [uno] per un file, [tanti] per più file. */
enum class MotivoFuori(val uno: String, val tanti: String) {
    FORMATO("che Lectio non sa leggere", "che Lectio non sa leggere"),
    GRANDE("più grande di 20 MB", "più grandi di 20 MB"),
    VUOTO("vuoto", "vuoti"),
    PASSWORD("protetto da password", "protetti da password"),
    ROTTO("che non riesco ad aprire", "che non riesco ad aprire"),
}

data class Fuori(val nome: String, val motivo: MotivoFuori)

/**
 * Un file da mandare trovato in una cartella o in uno zip: un documento del telefono ([uri]) o un file già tirato
 * fuori dallo zip nella cache dell'app ([file]). [radice] dice da dove viene (per "12 file da Storia.zip").
 */
data class Trovato(
    val nome: String,
    val genere: GenereFile?,
    val percorso: String,
    val radice: Int,
    val uri: String? = null,
    val mime: String? = null,
    val file: File? = null,
)

data class Radice(val tipo: String, val nome: String)

/** Quello che si raccoglie aprendo cartelle e zip. */
class Raccolta {
    val buoni = mutableListOf<Trovato>()
    val fuori = mutableListOf<Fuori>()
    val radici = mutableListOf<Radice>()
    var troppi = false
    var byteEstratti = 0L
    private var contatore = 0

    fun radice(tipo: String, nome: String): Int {
        val i = radici.indexOfFirst { it.tipo == tipo && it.nome == nome }
        if (i >= 0) return i
        radici += Radice(tipo, nome)
        return radici.size - 1
    }

    fun pieno(): Boolean {
        if (buoni.size >= Archivi.MASSIMO_FILE) troppi = true
        return troppi
    }

    fun fuori(percorso: String, motivo: MotivoFuori) {
        fuori += Fuori(Archivi.nomeBase(percorso), motivo)
    }

    /** Un documento del telefono (da una cartella scelta): si tiene se Lectio lo legge. */
    fun documento(uri: String, nome: String, mime: String?, byte: Long, percorso: String, radice: Int) {
        val genere = Condivisione.genere(mime, nome)
        val motivo = Archivi.motivo(genere, byte)
        if (motivo != null) fuori(percorso, motivo) else buoni += Trovato(nome, genere, percorso, radice, uri = uri, mime = mime)
    }

    fun nuovoFile(cartella: File, estensione: String): File = File(cartella, "voce-${contatore++}.$estensione")
}

/**
 * Cartelle e zip (stesse regole del programma per il computer, pc/ui/archivi.js): si tirano fuori i file che Lectio
 * legge, anche dalle sottocartelle e dagli zip dentro gli zip; gli avanzi (Mac, nascosti, Thumbs.db, "~$" di Word) si
 * saltano senza dirlo, gli altri file restano fuori e si dice quali.
 */
object Archivi {
    const val MASSIMO_FILE = 300
    /** Uno zip dentro lo zip si copia prima nella cache: fino a 200 MB. */
    const val MASSIMO_ZIP_DENTRO = 200L * 1024 * 1024
    /** In tutto, quello che si tira fuori dagli zip nella cache del telefono. */
    const val MASSIMO_BYTE_ESTRATTI = 600L * 1024 * 1024
    const val PROFONDITA = 3

    /** I tipi con cui le app dichiarano uno zip (WhatsApp, Files, Drive, Gmail). */
    val TIPI_ZIP = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip")

    /** I nomi negli zip vecchi di Windows (senza UTF-8) sono nella code page 850 dell'Europa occidentale. */
    const val CP850 =
        "ÇüéâäàåçêëèïîìÄÅ" + "ÉæÆôöòûùÿÖÜø£Ø×ƒ" + "áíóúñÑªº¿®¬½¼¡«»" + "░▒▓│┤ÁÂÀ©╣║╗╝¢¥┐" +
            "└┴┬├─┼ãÃ╚╔╩╦╠═╬¤" + "ðÐÊËÈıÍÎÏ┘┌█▄¦Ì▀" + "ÓßÔÒõÕµþÞÚÛÙýÝ¯´" + "­±‗¾¶§÷¸°¨·¹³²■ "

    fun nomeBase(percorso: String?): String = percorso.orEmpty().split('/', '\\').lastOrNull { it.isNotEmpty() }.orEmpty()

    fun eZip(mime: String?, nome: String?): Boolean {
        if (nome != null && nome.lowercase(Locale.ROOT).endsWith(".zip")) return true
        val tipo = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return false
        return tipo in TIPI_ZIP
    }

    fun daIgnorare(percorso: String): Boolean {
        val parti = percorso.split('/', '\\').filter { it.isNotEmpty() }
        if (parti.isEmpty()) return true
        if (parti.any { it == "__MACOSX" || it.startsWith(".") }) return true
        val nome = parti.last().lowercase(Locale.ROOT)
        return nome == "thumbs.db" || nome == "desktop.ini" || nome.startsWith("~$")
    }

    /** null se si manda; le foto HEIC vanno bene (l'app le fa diventare JPEG, anche più piccole). */
    fun motivo(genere: GenereFile?, byte: Long): MotivoFuori? = when {
        genere == null -> MotivoFuori.FORMATO
        byte == 0L -> MotivoFuori.VUOTO
        byte > Condivisione.MASSIMO_BYTE && !genere.daConvertire -> MotivoFuori.GRANDE
        else -> null
    }

    /**
     * Il nome giusto di una voce letta con ISO-8859-1 (così non si rompe mai): se lo zip lo segna come UTF-8 Java l'ha
     * già letto bene; UTF-8 senza segno (Mac) si riconosce; i caratteri di controllo 0x80-0x9F (è, à, ù, é in CP850)
     * vogliono dire Windows vecchio.
     */
    fun nomeVero(grezzo: String): String {
        if (grezzo.any { it.code > 0xFF }) return grezzo
        val byte = grezzo.toByteArray(Charsets.ISO_8859_1)
        if (byte.all { it >= 0 }) return grezzo
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(byte)).toString()
        } catch (e: CharacterCodingException) {
            // Non è UTF-8.
        }
        if (grezzo.any { it.code in 0x80..0x9F }) {
            return buildString { byte.forEach { b -> val u = b.toInt() and 0xFF; append(if (u < 128) u.toChar() else CP850[u - 128]) } }
        }
        return grezzo
    }

    /** Copia al massimo [limite] byte: -1 se ce n'erano di più (il file si toglie). */
    private fun copia(dentro: InputStream, destinazione: File, limite: Long): Long {
        var totale = 0L
        destinazione.outputStream().use { fuori ->
            val pezzo = ByteArray(64 * 1024)
            while (true) {
                val n = dentro.read(pezzo)
                if (n < 0) break
                totale += n
                if (totale > limite) break
                fuori.write(pezzo, 0, n)
            }
        }
        if (totale > limite) {
            destinazione.delete()
            return -1
        }
        return totale
    }

    private val ordine: Comparator<Trovato> = Collator.getInstance(Locale.ITALIAN).let { c ->
        c.strength = Collator.SECONDARY
        Comparator { a, b -> c.compare(a.percorso, b.percorso) }
    }

    /**
     * Tira fuori da uno zip (letto a flusso) i file che Lectio legge, copiandoli in [cartella]. [percorso] = il nome
     * dello zip con davanti le cartelle (per gli zip dentro gli zip).
     */
    fun estrai(ingresso: InputStream, percorso: String, cartella: File, r: Raccolta, radice: Int, profondita: Int = 0) {
        if (profondita >= PROFONDITA) {
            r.fuori(percorso, MotivoFuori.ROTTO)
            return
        }
        cartella.mkdirs()
        val inizio = r.buoni.size
        var viste = 0
        try {
            val zip = ZipInputStream(ingresso.buffered(), Charsets.ISO_8859_1)
            while (true) {
                val voce = zip.nextEntry ?: break
                viste++
                val nome = nomeVero(voce.name).replace('\\', '/')
                if (voce.isDirectory || daIgnorare(nome)) continue
                if (r.pieno()) break
                val p = "$percorso/$nome"
                val base = nomeBase(nome)
                if (eZip(null, base)) {
                    val dentro = r.nuovoFile(cartella, "zip")
                    if (copia(zip, dentro, MASSIMO_ZIP_DENTRO) < 0) {
                        r.fuori(p, MotivoFuori.GRANDE)
                        continue
                    }
                    FileInputStream(dentro).use { estrai(it, p, cartella, r, radice, profondita + 1) }
                    dentro.delete()
                    continue
                }
                val genere = Condivisione.genere(null, base)
                val fuori = motivo(genere, if (voce.size < 0) 1 else voce.size)
                if (fuori != null) {
                    r.fuori(p, fuori)
                    continue
                }
                val limite = if (genere!!.daConvertire) MASSIMO_ZIP_DENTRO else Condivisione.MASSIMO_BYTE
                val destinazione = r.nuovoFile(cartella, genere.estensione)
                val byte = copia(zip, destinazione, limite)
                when {
                    byte < 0 -> r.fuori(p, MotivoFuori.GRANDE)
                    byte == 0L -> { destinazione.delete(); r.fuori(p, MotivoFuori.VUOTO) }
                    r.byteEstratti + byte > MASSIMO_BYTE_ESTRATTI -> { destinazione.delete(); r.troppi = true }
                    else -> {
                        r.byteEstratti += byte
                        r.buoni += Trovato(base, genere, p, radice, file = destinazione)
                    }
                }
                if (r.troppi) break
            }
            if (viste == 0) r.fuori(percorso, MotivoFuori.ROTTO)
        } catch (e: ZipException) {
            r.fuori(percorso, if (e.message.orEmpty().contains("encrypt", ignoreCase = true)) MotivoFuori.PASSWORD else MotivoFuori.ROTTO)
        } catch (e: Exception) {
            // IOException, nomi con ".." rifiutati da Android 14, memoria: lo zip (o il resto) resta fuori.
            r.fuori(percorso, MotivoFuori.ROTTO)
        }
        // Nello stesso ordine del computer: per percorso, in ordine alfabetico.
        val nuovi = r.buoni.subList(inizio, r.buoni.size)
        val ordinati = nuovi.sortedWith(ordine)
        nuovi.clear()
        nuovi.addAll(ordinati)
    }

    fun elencoNomi(nomi: List<String>, quanti: Int = 3): String = when {
        nomi.size <= 1 -> nomi.joinToString("")
        nomi.size <= quanti -> nomi.dropLast(1).joinToString(", ") + " e " + nomi.last()
        else -> {
            val altri = nomi.size - quanti
            nomi.take(quanti).joinToString(", ") + if (altri == 1) " e un altro" else " e altri $altri"
        }
    }

    /** "Lascio fuori 2 file che Lectio non sa leggere: a.mp4 e b.xlsx.", una frase per motivo. */
    fun frasiFuori(fuori: List<Fuori>): List<String> = fuori.groupBy { it.motivo }.map { (m, lista) ->
        val nomi = lista.map { it.nome }
        val quanti = if (nomi.size == 1) "1 file ${m.uno}" else "${nomi.size} file ${m.tanti}"
        "Lascio fuori $quanti: ${elencoNomi(nomi)}."
    }

    /** "12 file da Storia.zip", "12 file dalla cartella Storia", altrimenti null (si vedono i nomi e basta). */
    fun descrizione(r: Raccolta): String? {
        val radici = r.buoni.map { it.radice }.toSet()
        if (radici.size != 1) return if (r.buoni.size > 1) "${r.buoni.size} file" else null
        val radice = r.radici.getOrNull(radici.first()) ?: return null
        val quanti = if (r.buoni.size == 1) "1 file" else "${r.buoni.size} file"
        return when (radice.tipo) {
            "zip" -> "$quanti da ${radice.nome}"
            "cartella" -> "$quanti dalla cartella ${radice.nome}"
            else -> if (r.buoni.size > 1) quanti else null
        }
    }

    /** Le frasi da mostrare sopra l'elenco: chi resta fuori e, se servisse, il limite dei file. */
    fun avvisi(r: Raccolta): List<String> = buildList {
        addAll(frasiFuori(r.fuori))
        if (r.troppi) add("Sono troppi file insieme: mando i primi ${r.buoni.size}, gli altri aggiungili dopo.")
    }

    /** Quando non resta niente da mandare. */
    fun fraseNiente(r: Raccolta): String {
        if (r.fuori.isNotEmpty() && r.fuori.all { it.motivo == MotivoFuori.ROTTO }) return "Non riesco ad aprire lo zip: forse è rovinato o non è finito di scaricare."
        if (r.fuori.isNotEmpty() && r.fuori.all { it.motivo == MotivoFuori.PASSWORD }) return "Lo zip è protetto da password: non riesco ad aprirlo."
        val dove = when (r.radici.singleOrNull()?.tipo) {
            "zip" -> "Nello zip"
            "cartella" -> "Nella cartella"
            else -> "Qui"
        }
        return "$dove non c'è niente che Lectio sa leggere: PDF, Word, ODT, RTF, testo o foto."
    }
}
