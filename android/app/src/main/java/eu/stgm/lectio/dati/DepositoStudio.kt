package eu.stgm.lectio.dati

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Un piccolo armadio di testi per chiave. A differenza della [Cache] non butta via
 * niente da solo: le risposte di una verifica e gli schemi aperti restano qui.
 */
interface Scaffale {
    fun leggi(chiave: String): String?
    fun scrivi(chiave: String, testo: String)
    fun togli(chiave: String)
    fun chiavi(prefisso: String): List<String>
    fun svuota()
}

class ScaffaleFile(private val cartella: File) : Scaffale {
    private fun file(chiave: String) = File(cartella, chiave.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".json")

    override fun leggi(chiave: String): String? = try {
        file(chiave).takeIf { it.exists() }?.readText()
    } catch (e: IOException) {
        null
    }

    override fun scrivi(chiave: String, testo: String) {
        try {
            cartella.mkdirs()
            val f = file(chiave)
            val temporaneo = File(cartella, f.name + ".tmp")
            temporaneo.writeText(testo)
            if (!temporaneo.renameTo(f)) {
                f.delete()
                temporaneo.renameTo(f)
            }
        } catch (e: IOException) {
            // Memoria piena: resta valido quello che c'era.
        }
    }

    override fun togli(chiave: String) {
        file(chiave).delete()
    }

    override fun chiavi(prefisso: String): List<String> =
        cartella.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".json") && it.startsWith(prefisso) }.map { it.removeSuffix(".json") }

    override fun svuota() {
        cartella.listFiles()?.forEach { it.delete() }
    }
}

class ScaffaleMemoria : Scaffale {
    private val voci = ConcurrentHashMap<String, String>()
    override fun leggi(chiave: String): String? = voci[chiave]
    override fun scrivi(chiave: String, testo: String) {
        voci[chiave] = testo
    }
    override fun togli(chiave: String) {
        voci.remove(chiave)
    }
    override fun chiavi(prefisso: String): List<String> = voci.keys.filter { it.startsWith(prefisso) }
    override fun svuota() = voci.clear()
}

/** Quello che si tiene sul telefono di una verifica in corso. */
@Serializable
data class CopiaSimulata(
    val risposte: Map<String, String> = emptyMap(),
    /** I secondi contati a pagina aperta. */
    val secondi: Int = 0,
    /** C'è qualcosa che il server non ha ancora ricevuto. */
    val daInviare: Boolean = false,
    val avvisato5: Boolean = false,
    val avvisatoFine: Boolean = false,
    /** "Il tempo è finito" chiuso con la X: non torna. */
    val fineChiuso: Boolean = false,
)

/** Un esito del Ripasso lampo che deve ancora arrivare al server. */
@Serializable
data class EsitoInCoda(val cartaId: Long, val esito: String, val rifiuti: Int = 0)

/** Schemi, simulate e riassunti di cui si aspetta la fine per avvisare con una notifica. */
@Serializable
data class Seguiti(
    val schemi: Set<Long> = emptySet(),
    val simulate: Set<Long> = emptySet(),
    val riassunti: Set<Long> = emptySet(),
) {
    val vuoti: Boolean get() = schemi.isEmpty() && simulate.isEmpty() && riassunti.isEmpty()
}

/**
 * Quello che schemi, riassunti, Ripasso lampo e simulate tengono sul dispositivo
 * (aspetto.md §33.9, §35.5, §36.3, §41.4). Un'unica classe sopra uno [Scaffale]:
 * su file nell'app vera, in memoria nella visita guidata e nelle prove.
 */
class DepositoStudio(
    private val scaffale: Scaffale = ScaffaleMemoria(),
    private val cartellaImmagini: File? = null,
) {
    /** Una "cache" che non scade: uno schema aperto una volta si rilegge sempre. */
    val durevole: Cache = object : Cache {
        override fun leggi(chiave: String): VoceCache? = scaffale.leggi("copia-$chiave")?.let { VoceCache(it, 0L) }
        override fun scrivi(chiave: String, json: String) = scaffale.scrivi("copia-$chiave", json)
        override fun cancella(chiave: String) = scaffale.togli("copia-$chiave")
        override fun svuota() = scaffale.chiavi("copia-").forEach(scaffale::togli)
    }

    // --- Foto degli schemi --------------------------------------------------------------------

    /** Dove sta (o andrà) la foto; null = niente disco (visita guidata, prove). */
    fun fileImmagine(schemaId: Long, n: Int): File? = cartellaImmagini?.let { File(it, "$schemaId-$n.jpg") }

    fun togliImmagine(schemaId: Long, n: Int) {
        fileImmagine(schemaId, n)?.delete()
    }

    fun togliSchema(schemaId: Long) {
        cartellaImmagini?.listFiles { f -> f.name.startsWith("$schemaId-") }?.forEach { it.delete() }
        durevole.cancella("schema-$schemaId")
        durevole.cancella("riassunti-$schemaId")
        scaffale.togli("parte-$schemaId")
    }

    // --- Dove si era arrivati -----------------------------------------------------------------

    fun parteLetta(schemaId: Long): Int? = scaffale.leggi("parte-$schemaId")?.toIntOrNull()
    fun ricordaParte(schemaId: Long, n: Int) = scaffale.scrivi("parte-$schemaId", n.toString())

    // --- Verifiche in corso -------------------------------------------------------------------

    fun copia(id: Long): CopiaSimulata? = scaffale.leggi("simulata-$id")?.let {
        try {
            JsonLectio.decodeFromString(CopiaSimulata.serializer(), it)
        } catch (e: Exception) {
            null
        }
    }

    fun scriviCopia(id: Long, copia: CopiaSimulata) = scaffale.scrivi("simulata-$id", JsonLectio.encodeToString(CopiaSimulata.serializer(), copia))
    fun togliCopia(id: Long) = scaffale.togli("simulata-$id")

    fun copieDaInviare(): List<Pair<Long, CopiaSimulata>> = scaffale.chiavi("simulata-").mapNotNull { k ->
        val id = k.removePrefix("simulata-").toLongOrNull() ?: return@mapNotNull null
        copia(id)?.takeIf { it.daInviare }?.let { id to it }
    }

    var tempoNascosto: Boolean
        get() = scaffale.leggi("tempo-nascosto") == "si"
        set(v) = scaffale.scrivi("tempo-nascosto", if (v) "si" else "no")

    // --- Ripasso lampo ------------------------------------------------------------------------

    /** Un invio della coda alla volta. */
    val giroLampo = Mutex()

    fun codaLampo(): List<EsitoInCoda> = scaffale.leggi("coda-lampo")?.let {
        try {
            JsonLectio.decodeFromString(ListSerializer(EsitoInCoda.serializer()), it)
        } catch (e: Exception) {
            emptyList()
        }
    }.orEmpty()

    fun scriviCodaLampo(coda: List<EsitoInCoda>) =
        scaffale.scrivi("coda-lampo", JsonLectio.encodeToString(ListSerializer(EsitoInCoda.serializer()), coda))

    /** Toglie una scheda da tutte le copie salvate: fatta senza rete, non deve ripresentarsi. */
    fun togliCartaSalvata(id: Long) {
        scaffale.chiavi("copia-lampo-").forEach { k ->
            val testo = scaffale.leggi(k) ?: return@forEach
            val l = try {
                JsonLectio.decodeFromString(Lampo.serializer(), testo)
            } catch (e: Exception) {
                return@forEach
            }
            if (l.carte.none { it.id == id }) return@forEach
            val resto = l.copy(carte = l.carte.filterNot { it.id == id }, daFare = (l.daFare - 1).coerceAtLeast(0))
            scaffale.scrivi(k, JsonLectio.encodeToString(Lampo.serializer(), resto))
        }
    }

    // --- Lavori da seguire --------------------------------------------------------------------

    @Synchronized
    fun seguiti(): Seguiti = scaffale.leggi("seguiti")?.let {
        try {
            JsonLectio.decodeFromString(Seguiti.serializer(), it)
        } catch (e: Exception) {
            null
        }
    } ?: Seguiti()

    @Synchronized
    private fun cambiaSeguiti(cambia: (Seguiti) -> Seguiti) =
        scaffale.scrivi("seguiti", JsonLectio.encodeToString(Seguiti.serializer(), cambia(seguiti())))

    fun seguiSchema(id: Long) = cambiaSeguiti { it.copy(schemi = it.schemi + id) }
    fun seguiSimulata(id: Long) = cambiaSeguiti { it.copy(simulate = it.simulate + id) }
    fun seguiRiassunto(id: Long) = cambiaSeguiti { it.copy(riassunti = it.riassunti + id) }
    fun smettiSchema(id: Long) = cambiaSeguiti { it.copy(schemi = it.schemi - id) }
    fun smettiSimulata(id: Long) = cambiaSeguiti { it.copy(simulate = it.simulate - id) }
    fun smettiRiassunto(id: Long) = cambiaSeguiti { it.copy(riassunti = it.riassunti - id) }

    fun svuota() {
        scaffale.svuota()
        cartellaImmagini?.listFiles()?.forEach { it.delete() }
    }
}
