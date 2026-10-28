package eu.stgm.lectio.ui.materiale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import eu.stgm.lectio.logica.Condivisione
import eu.stgm.lectio.logica.GenereFile
import eu.stgm.lectio.rete.FileDaCaricare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Un file scelto o condiviso, già letto (nome e tipo) ma non ancora copiato. Serializzabile: sta nello stato salvato. */
@kotlinx.serialization.Serializable
data class FileScelto(val uri: String, val nome: String?, val mime: String?, val genere: GenereFile?)

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

    /** Un testo condiviso (da Gemini) diventa un file .txt. */
    suspend fun testo(testo: String, oggetto: String?): FileDaCaricare = withContext(Dispatchers.IO) {
        val nome = Condivisione.nomeTesto(oggetto)
        val f = File(cartella, "${System.nanoTime()}-$nome")
        f.writeText(testo, Charsets.UTF_8)
        FileDaCaricare(f, nome, GenereFile.TESTO.mime)
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
