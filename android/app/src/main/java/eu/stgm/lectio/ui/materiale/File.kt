package eu.stgm.lectio.ui.materiale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import eu.stgm.lectio.logica.Archivi
import eu.stgm.lectio.logica.Condivisione
import eu.stgm.lectio.logica.GenereFile
import eu.stgm.lectio.logica.MotivoFuori
import eu.stgm.lectio.logica.Raccolta
import eu.stgm.lectio.logica.Trovato
import eu.stgm.lectio.rete.FileDaCaricare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Un file scelto o condiviso, già letto (nome e tipo) ma non ancora copiato. Serializzabile: sta nello stato salvato. */
@kotlinx.serialization.Serializable
data class FileScelto(val uri: String, val nome: String?, val mime: String?, val genere: GenereFile?)

/** Dopo aver aperto zip e cartelle: i file da mandare, le frasi per quelli rimasti fuori, "12 file da Storia.zip". */
data class Aperti(val file: List<FileScelto>, val avvisi: List<String>, val descrizione: String?)

sealed interface FilePronto {
    data class Pronto(val file: FileDaCaricare) : FilePronto
    data class Errore(val messaggio: String) : FilePronto
}

/**
 * Prepara i file da mandare al server: li copia nella cache privata dell'app,
 * controlla i 20 MB e fa diventare JPEG le foto HEIC (il server non le legge).
 */
class PreparatoreFile(private val context: Context) {

    private val cartella: File get() = File(context.cacheDir, "invio").also { it.mkdirs() }

    /** Dove si tirano fuori i file degli zip (si svuota quando il foglio si chiude o i file sono andati). */
    private val cartellaArchivi: File get() = File(context.cacheDir, "archivi")

    fun leggi(uri: Uri, mimeDichiarato: String? = null): FileScelto {
        var nome: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) nome = c.getString(0)
            }
        } catch (e: Exception) {
            // Alcune app non dicono il nome: si usa quello predefinito.
        }
        if (nome == null) nome = uri.lastPathSegment?.substringAfterLast('/')
        val mime = context.contentResolver.getType(uri) ?: mimeDichiarato
        return FileScelto(uri.toString(), nome, mime, Condivisione.genere(mime, nome))
    }

    suspend fun prepara(scelto: FileScelto, indice: Int): FilePronto = withContext(Dispatchers.IO) {
        val genere = scelto.genere ?: return@withContext FilePronto.Errore(Condivisione.NON_LEGGIBILE)
        val nome = Condivisione.nomeDaCaricare(scelto.nome, genere, indice)
        val destinazione = File(cartella, "${System.nanoTime()}-$nome")
        try {
            if (genere.daConvertire) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return@withContext FilePronto.Errore("Questo telefono non sa aprire le foto HEIC: mandala come JPEG.")
                }
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(scelto.uri))) { decoder, info, _ ->
                    // Abbastanza per leggere una verifica, senza sforare i 20 MB.
                    val lato = maxOf(info.size.width, info.size.height)
                    if (lato > LATO_MASSIMO) {
                        val f = LATO_MASSIMO.toFloat() / lato
                        decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                destinazione.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            } else {
                val ingresso = context.contentResolver.openInputStream(Uri.parse(scelto.uri))
                    ?: return@withContext FilePronto.Errore("Non riesco ad aprire questo file.")
                ingresso.use { dentro ->
                    destinazione.outputStream().use { fuori ->
                        val pezzo = ByteArray(64 * 1024)
                        var totale = 0L
                        while (true) {
                            val n = dentro.read(pezzo)
                            if (n < 0) break
                            totale += n
                            if (totale > Condivisione.MASSIMO_BYTE) {
                                fuori.close()
                                destinazione.delete()
                                return@withContext FilePronto.Errore(Condivisione.TROPPO_GRANDE)
                            }
                            fuori.write(pezzo, 0, n)
                        }
                    }
                }
            }
            if (destinazione.length() > Condivisione.MASSIMO_BYTE) {
                destinazione.delete()
                return@withContext FilePronto.Errore(Condivisione.TROPPO_GRANDE)
            }
            FilePronto.Pronto(FileDaCaricare(destinazione, nome, Condivisione.mimeDaCaricare(genere)))
        } catch (e: IOException) {
            destinazione.delete()
            FilePronto.Errore("Non riesco a leggere questo file dal telefono.")
        } catch (e: Exception) {
            destinazione.delete()
            FilePronto.Errore("Non riesco a leggere questa foto. Prova a mandarla come JPEG.")
        }
    }

    /**
     * L'inizio di un file di testo (al massimo 4 KB), per riconoscere i testi di Gemini
     * prima di mandarli. null per le foto, i PDF o se non si legge.
     */
    suspend fun inizio(scelto: FileScelto): String? = withContext(Dispatchers.IO) {
        if (scelto.genere != GenereFile.TESTO && scelto.genere != GenereFile.MARKDOWN) return@withContext null
        try {
            context.contentResolver.openInputStream(Uri.parse(scelto.uri))?.use { dentro ->
                val pezzo = ByteArray(4096)
                val n = dentro.read(pezzo)
                if (n > 0) String(pezzo, 0, n, Charsets.UTF_8) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Un testo condiviso (da Gemini) diventa un file .txt. */
    suspend fun testo(testo: String, oggetto: String?): FileDaCaricare = withContext(Dispatchers.IO) {
        val nome = Condivisione.nomeTesto(oggetto)
        val f = File(cartella, "${System.nanoTime()}-$nome")
        f.writeText(testo, Charsets.UTF_8)
        FileDaCaricare(f, nome, GenereFile.TESTO.mime)
    }

    /**
     * Apre gli zip scelti o condivisi (anche quelli dentro altri zip) e tiene i file che Lectio legge; gli altri file
     * scelti restano come sono.
     */
    suspend fun espandi(scelti: List<FileScelto>): Aperti = withContext(Dispatchers.IO) {
        val r = Raccolta()
        val dove = File(cartellaArchivi, System.nanoTime().toString())
        for (s in scelti) {
            if (r.pieno()) break
            val nome = s.nome ?: "file"
            if (Archivi.eZip(s.mime, s.nome)) {
                val radice = r.radice("zip", nome)
                try {
                    context.contentResolver.openInputStream(Uri.parse(s.uri))?.use { Archivi.estrai(it, nome, dove, r, radice) }
                        ?: r.fuori(nome, MotivoFuori.ROTTO)
                } catch (e: Exception) {
                    r.fuori(nome, MotivoFuori.ROTTO)
                }
            } else {
                r.buoni += Trovato(nome, s.genere, nome, r.radice("file", nome), uri = s.uri, mime = s.mime)
            }
        }
        aperti(r)
    }

    /** Una cartella del telefono (scelta con "Cartella del telefono"): tutti i file che Lectio legge, anche nelle sottocartelle e negli zip. */
    suspend fun cartella(albero: Uri): Aperti = withContext(Dispatchers.IO) {
        val r = Raccolta()
        val dove = File(cartellaArchivi, System.nanoTime().toString())
        try {
            val radiceId = DocumentsContract.getTreeDocumentId(albero)
            val nome = nomeDocumento(DocumentsContract.buildDocumentUriUsingTree(albero, radiceId)) ?: "cartella"
            scendi(albero, radiceId, nome, dove, r, r.radice("cartella", nome), 0)
        } catch (e: Exception) {
            r.fuori("cartella", MotivoFuori.ROTTO)
        }
        aperti(r)
    }

    private data class Figlio(val id: String, val nome: String, val mime: String?, val byte: Long)

    private fun scendi(albero: Uri, id: String, percorso: String, dove: File, r: Raccolta, radice: Int, livello: Int) {
        if (livello > 20) return
        val figli = mutableListOf<Figlio>()
        val colonne = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(albero, id), colonne, null, null, null)?.use { c ->
            while (c.moveToNext()) figli += Figlio(c.getString(0), c.getString(1).orEmpty(), c.getString(2), if (c.isNull(3)) -1L else c.getLong(3))
        }
        val ordine = java.text.Collator.getInstance(java.util.Locale.ITALIAN).apply { strength = java.text.Collator.SECONDARY }
        figli.sortWith { a, b -> ordine.compare(a.nome, b.nome) }
        for (f in figli) {
            val p = "$percorso/${f.nome}"
            if (Archivi.daIgnorare(p)) continue
            if (r.pieno()) return
            if (f.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                scendi(albero, f.id, p, dove, r, radice, livello + 1)
                continue
            }
            val uri = DocumentsContract.buildDocumentUriUsingTree(albero, f.id)
            if (Archivi.eZip(f.mime, f.nome)) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { Archivi.estrai(it, p, dove, r, radice) } ?: r.fuori(p, MotivoFuori.ROTTO)
                } catch (e: Exception) {
                    r.fuori(p, MotivoFuori.ROTTO)
                }
            } else {
                r.documento(uri.toString(), f.nome, f.mime, f.byte, p, radice)
            }
        }
    }

    private fun nomeDocumento(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun aperti(r: Raccolta): Aperti {
        val file = r.buoni.map { t ->
            if (t.file != null) {
                FileScelto(Uri.fromFile(t.file).toString(), t.nome, t.genere?.mime, t.genere)
            } else {
                FileScelto(t.uri.orEmpty(), t.nome, t.mime, t.genere)
            }
        }
        val avvisi = if (file.isEmpty()) listOf(Archivi.fraseNiente(r)) else Archivi.avvisi(r)
        return Aperti(file, avvisi, Archivi.descrizione(r))
    }

    /** Via i file tirati fuori dagli zip. */
    fun pulisciArchivi() {
        cartellaArchivi.deleteRecursively()
    }

    /** Il file dove la fotocamera salva la foto scattata ("Aggiungi → Foto"). */
    fun nuovaFoto(): File = File(File(context.cacheDir, "foto").also { it.mkdirs() }, "foto-${System.currentTimeMillis()}.jpg")

    /** Via i file mandati (restano solo per il tempo dell'invio). */
    fun pulisci() {
        cartella.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val LATO_MASSIMO = 3000
    }
}
