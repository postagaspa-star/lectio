package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Materia
import java.util.Locale

/** I file che il server sa leggere (contratto §6), e le foto HEIC che l'app converte in JPEG. */
enum class GenereFile(val estensione: String, val mime: String) {
    TESTO("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    PDF("pdf", "application/pdf"),
    WORD("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ODT("odt", "application/vnd.oasis.opendocument.text"),
    RTF("rtf", "application/rtf"),
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),

    /** HEIC/HEIF (le foto di molti telefoni): il server non le legge, l'app le fa diventare JPEG. */
    HEIC("heic", "image/heic"),
    ;

    val immagine: Boolean get() = this == JPEG || this == PNG || this == WEBP || this == HEIC
    val daConvertire: Boolean get() = this == HEIC
}

/** Cosa è arrivato da "Condividi" di un'altra app, già letto dall'Intent. */
data class Condiviso(
    /** Il testo (EXTRA_TEXT), se è una condivisione di testo. */
    val testo: String?,
    /** Il titolo (EXTRA_SUBJECT o EXTRA_TITLE), se c'è. */
    val oggetto: String?,
    /** I file (EXTRA_STREAM), come (indirizzo, tipo dichiarato). */
    val file: List<Pair<String, String?>>,
)

object Condivisione {
    const val MASSIMO_BYTE = 20L * 1024 * 1024

    /** I tipi dichiarati nel Manifest e chiesti al selettore di file. */
    val TIPI_SELETTORE = arrayOf(
        "text/plain", "text/markdown", "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/msword", "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf",
        "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
    )

    private val DA_MIME = mapOf(
        "text/plain" to GenereFile.TESTO,
        "text/markdown" to GenereFile.MARKDOWN,
        "text/x-markdown" to GenereFile.MARKDOWN,
        "application/pdf" to GenereFile.PDF,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to GenereFile.WORD,
        "application/vnd.oasis.opendocument.text" to GenereFile.ODT,
        "application/rtf" to GenereFile.RTF,
        "text/rtf" to GenereFile.RTF,
        "image/jpeg" to GenereFile.JPEG,
        "image/jpg" to GenereFile.JPEG,
        "image/png" to GenereFile.PNG,
        "image/webp" to GenereFile.WEBP,
        "image/heic" to GenereFile.HEIC,
        "image/heif" to GenereFile.HEIC,
        "image/heic-sequence" to GenereFile.HEIC,
        "image/heif-sequence" to GenereFile.HEIC,
    )

    private val DA_ESTENSIONE = mapOf(
        "txt" to GenereFile.TESTO,
        "md" to GenereFile.MARKDOWN,
        "pdf" to GenereFile.PDF,
        "docx" to GenereFile.WORD,
        "odt" to GenereFile.ODT,
        "rtf" to GenereFile.RTF,
        "jpg" to GenereFile.JPEG,
        "jpeg" to GenereFile.JPEG,
        "png" to GenereFile.PNG,
        "webp" to GenereFile.WEBP,
        "heic" to GenereFile.HEIC,
        "heif" to GenereFile.HEIC,
    )

    /**
     * Il genere di un file dal tipo dichiarato e dal nome. Conta prima l'estensione
     * del nome (le app spesso dichiarano "application/octet-stream"), poi il tipo.
     * null = Lectio non lo legge (per esempio il vecchio .doc).
     */
    fun genere(mime: String?, nome: String?): GenereFile? {
        val estensione = nome?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        DA_ESTENSIONE[estensione]?.let { return it }
        val tipo = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return DA_MIME[tipo]
    }

    /** Il nome con cui il file arriva al server (con l'estensione giusta, .jpg dopo la conversione). */
    fun nomeDaCaricare(nome: String?, genere: GenereFile, indice: Int = 0): String {
        val base = nome?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (genere.immagine) "foto-${indice + 1}" else "file-${indice + 1}"
        val estensione = if (genere.daConvertire) GenereFile.JPEG.estensione else genere.estensione
        return "$base.$estensione"
    }

    fun mimeDaCaricare(genere: GenereFile): String = if (genere.daConvertire) GenereFile.JPEG.mime else genere.mime

    /** La frase per un file che il server non legge. */
    const val NON_LEGGIBILE = "Lectio legge PDF, Word, testo e foto. Questo file non lo sa leggere."
    const val TROPPO_GRANDE = "Il file è più grande di 20 MB: il server di casa non lo accetta."

    /**
     * Una condivisione di testo (per esempio da Gemini) diventa un file .txt: così il
     * server prende il titolo dalla riga MATERIA/PROF/DATA/TIPO come per i file.
     */
    fun nomeTesto(oggetto: String?): String {
        val pulito = oggetto?.replace(Regex("[^\\p{L}\\p{N} _-]"), "")?.trim()?.take(60)
        return (pulito?.takeIf { it.isNotEmpty() } ?: "verifica-da-gemini") + ".txt"
    }

    /**
     * La materia scritta nel testo di Gemini ("MATERIA: Storia | PROF: …"), per
     * proporla già scelta. null se non c'è o non corrisponde a nessuna materia.
     */
    fun materiaDalTesto(testo: String?, materie: List<Materia>): Long? {
        if (testo.isNullOrBlank()) return null
        val trovata = Regex("MATERIA\\s*:\\s*([^|\\n]+)", RegexOption.IGNORE_CASE).find(testo)
            ?.groupValues?.get(1)?.trim()?.lowercase(Locale.ITALIAN) ?: return null
        if (trovata.isEmpty() || trovata.startsWith("…")) return null
        return materie.firstOrNull { it.nome.lowercase(Locale.ITALIAN) == trovata }?.id
            ?: materie.firstOrNull { trovata.contains(it.nome.lowercase(Locale.ITALIAN)) || it.nome.lowercase(Locale.ITALIAN).contains(trovata) }?.id
    }

    /** Quanti dei file condivisi sono foto (per il costo mostrato prima). */
    fun quanteFoto(generi: List<GenereFile?>): Int = generi.count { it?.immagine == true }
}

/** I tipi di materiale del contratto, con le parole dell'interfaccia (aspetto.md §5). */
enum class TipoMateriale(val codice: String, val etichetta: String) {
    VERIFICA("verifica", "Verifica corretta"),
    INTERROGAZIONE("interrogazione", "Interrogazione"),
    APPUNTI("appunti", "Appunti"),
    LIBRO("libro", "Libro"),
    ALTRO("altro", "Altro"),
    ;

    companion object {
        fun da(codice: String?): TipoMateriale = entries.firstOrNull { it.codice == codice } ?: ALTRO

        /** Le quattro scelte del foglio Aggiungi. */
        val SCELTE = listOf(VERIFICA, INTERROGAZIONE, APPUNTI, LIBRO)
    }
}
