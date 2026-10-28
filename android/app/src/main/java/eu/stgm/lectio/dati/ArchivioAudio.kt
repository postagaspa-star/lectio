package eu.stgm.lectio.dati

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Le lezioni scaricate sul telefono, nella memoria privata dell'app: l'MP3 e,
 * accanto, il dettaglio (capitoli, domande, testo) per ascoltarle senza rete.
 */
class ArchivioAudio(private val cartella: File) {

    private val _scaricate = MutableStateFlow(elenca())

    /** Gli id delle lezioni scaricate (le schermate mostrano la spunta). */
    val scaricate: StateFlow<Set<Long>> = _scaricate.asStateFlow()

    /** Le lezioni che si stanno scaricando, con quanto manca (0-1). */
    val inScaricamento = MutableStateFlow<Map<Long, Float>>(emptyMap())

    private fun elenca(): Set<Long> =
        cartella.listFiles()?.filter { it.name.endsWith(".mp3") }?.mapNotNull { it.name.removeSuffix(".mp3").toLongOrNull() }?.toSet().orEmpty()

    fun fileAudio(id: Long): File = File(cartella, "$id.mp3")
    private fun fileDettaglio(id: Long): File = File(cartella, "$id.json")

    fun scaricata(id: Long): Boolean = fileAudio(id).isFile

    fun salvaDettaglio(lezione: Lezione) {
        try {
            cartella.mkdirs()
            fileDettaglio(lezione.id).writeText(JsonLectio.encodeToString(Lezione.serializer(), lezione))
        } catch (e: IOException) {
            // Senza il dettaglio la lezione suona lo stesso, senza capitoli.
        }
    }

    fun dettaglio(id: Long): Lezione? = try {
        val f = fileDettaglio(id)
        if (f.isFile) JsonLectio.decodeFromString(Lezione.serializer(), f.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun segnaScaricata() {
        _scaricate.value = elenca()
    }

    /** "Togli dal telefono": l'audio e il dettaglio vanno via, la lezione resta sul server. */
    fun togli(id: Long) {
        fileAudio(id).delete()
        fileDettaglio(id).delete()
        _scaricate.value = elenca()
    }

    fun svuota() {
        cartella.listFiles()?.forEach { it.delete() }
        _scaricate.value = emptySet()
    }

    fun cartella(): File = cartella.also { it.mkdirs() }
}
