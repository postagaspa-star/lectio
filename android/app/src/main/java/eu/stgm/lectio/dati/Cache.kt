package eu.stgm.lectio.dati

import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.rete.Testi
import eu.stgm.lectio.rete.messaggio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Gli ultimi dati visti, per consultarli anche senza rete (in autobus, in giro).
 * Una voce per schermata, sovrascritta a ogni lettura riuscita.
 */
interface Cache {
    fun leggi(chiave: String): VoceCache?
    fun scrivi(chiave: String, json: String)
    fun cancella(chiave: String)
    fun svuota()
}

data class VoceCache(val json: String, val salvataIl: Long)

/** Su file nella memoria privata dell'app (niente backup: v. regole_backup.xml). */
class CacheFile(
    private val cartella: File,
    private val orologio: () -> Long = System::currentTimeMillis,
    private val massimoByte: Long = MASSIMO_BYTE,
) : Cache {

    private var scrittureDallaPotatura = 0

    private fun file(chiave: String): File {
        val nome = chiave.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return File(cartella, "$nome.json")
    }

    override fun leggi(chiave: String): VoceCache? = try {
        val f = file(chiave)
        if (f.exists()) VoceCache(f.readText(), f.lastModified()) else null
    } catch (e: IOException) {
        null
    }

    override fun scrivi(chiave: String, json: String) {
        try {
            cartella.mkdirs()
            val f = file(chiave)
            val temporaneo = File(cartella, f.name + ".tmp")
            temporaneo.writeText(json)
            if (!temporaneo.renameTo(f)) {
                f.delete()
                temporaneo.renameTo(f)
            }
            f.setLastModified(orologio())
        } catch (e: IOException) {
            // Una cache che non si scrive non è un errore da mostrare.
        }
        synchronized(this) {
            if (++scrittureDallaPotatura >= 50) {
                scrittureDallaPotatura = 0
                pota()
            }
        }
    }

    override fun cancella(chiave: String) {
        file(chiave).delete()
    }

    override fun svuota() {
        cartella.listFiles()?.forEach { it.delete() }
    }

    fun pota() {
        val limite = orologio() - TimeUnit.DAYS.toMillis(MASSIMO_GIORNI)
        val rimasti = cartella.listFiles()?.filter { it.isFile }.orEmpty().filter { f ->
            if (f.lastModified() < limite) {
                f.delete()
                false
            } else {
                true
            }
        }
        var totale = rimasti.sumOf { it.length() }
        for (f in rimasti.sortedBy { it.lastModified() }) {
            if (totale <= massimoByte) break
            totale -= f.length()
            f.delete()
        }
    }

    companion object {
        const val MASSIMO_GIORNI = 60L
        const val MASSIMO_BYTE = 20L * 1024 * 1024
    }
}

class CacheMemoria(private val orologio: () -> Long = System::currentTimeMillis) : Cache {
    private val voci = ConcurrentHashMap<String, VoceCache>()
    override fun leggi(chiave: String): VoceCache? = voci[chiave]
    override fun scrivi(chiave: String, json: String) {
        voci[chiave] = VoceCache(json, orologio())
    }
    override fun cancella(chiave: String) {
        voci.remove(chiave)
    }
    override fun svuota() = voci.clear()
}

/**
 * Il risultato di una lettura per una schermata: il dato (fresco o dalla cache),
 * l'eventuale problema da mostrare e se manca la rete.
 */
data class Lettura<T>(
    val dato: T?,
    val errore: String? = null,
    val codiceErrore: String? = null,
    val daCacheDel: Long? = null,
    val senzaRete: Boolean = false,
)

suspend fun <T> leggiConCache(
    cache: Cache,
    chiave: String,
    serializer: KSerializer<T>,
    chiamata: suspend () -> Esito<T>,
): Lettura<T> {
    val esito = chiamata()
    if (esito is Esito.Ok) {
        withContext(Dispatchers.IO) { cache.scrivi(chiave, JsonLectio.encodeToString(serializer, esito.dato)) }
        return Lettura(esito.dato)
    }
    if (esito is Esito.Errore && esito.codice == Testi.CODICE_NON_AUTORIZZATO) {
        return Lettura(dato = null, errore = Testi.NON_AUTORIZZATO, codiceErrore = Testi.CODICE_NON_AUTORIZZATO)
    }
    val voce = withContext(Dispatchers.IO) { cache.leggi(chiave) }
    val vecchio = voce?.let {
        try {
            JsonLectio.decodeFromString(serializer, it.json)
        } catch (e: Exception) {
            null
        }
    }
    return Lettura(
        dato = vecchio,
        errore = esito.messaggio,
        codiceErrore = (esito as? Esito.Errore)?.codice,
        daCacheDel = if (vecchio != null) voce?.salvataIl else null,
        senzaRete = LectioClient.eIrraggiungibile(esito),
    )
}
