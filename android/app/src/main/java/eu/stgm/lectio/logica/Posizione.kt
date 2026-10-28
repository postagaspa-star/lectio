package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Ascolto
import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.daRitentare
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Una posizione d'ascolto registrata sul telefono. [quando] è il momento in cui è
 * stata registrata (millisecondi), non quello in cui arriva al server: così una
 * posizione tenuta offline non vince su una più recente fatta sul PC.
 */
@Serializable
data class PosizioneLocale(
    val lezioneId: Long,
    val posizioneS: Double,
    val finita: Boolean,
    val quando: Long,
)

/** Il punto da cui riprendere una lezione e se va mandato al server. */
data class PuntoDiRipresa(val posizioneS: Double, val finita: Boolean, val daInviare: PosizioneLocale?)

object RegolaPosizione {

    /** "2026-10-28T10:15:00+01:00" → millisecondi; null se manca o non si legge. */
    fun istante(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(iso).toEpochMilli()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    /**
     * Vince il più recente (contratto §9): la posizione del server se è più nuova
     * di quella tenuta sul telefono, altrimenti quella del telefono (che allora va
     * ancora mandata). A parità vince il server: è già salvata.
     */
    fun scegli(locale: PosizioneLocale?, server: Ascolto?): PuntoDiRipresa {
        val quandoServer = istante(server?.aggiornatoIl)
        return when {
            locale == null && server == null -> PuntoDiRipresa(0.0, false, null)
            locale == null -> PuntoDiRipresa(server!!.posizioneS, server.finita, null)
            server == null || quandoServer == null -> PuntoDiRipresa(locale.posizioneS, locale.finita, locale)
            locale.quando > quandoServer -> PuntoDiRipresa(locale.posizioneS, locale.finita, locale)
            else -> PuntoDiRipresa(server.posizioneS, server.finita, null)
        }
    }

    /** Ogni ~15 s mentre suona (aspetto.md: "posizione salvata ogni ~15 s, in pausa e all'uscita"). */
    const val OGNI_MS = 15_000L

    fun eOraDiSalvare(ultimoSalvataggio: Long?, adesso: Long): Boolean =
        ultimoSalvataggio == null || adesso - ultimoSalvataggio >= OGNI_MS
}

/** Dove il telefono tiene le posizioni non ancora arrivate al server. Nei test vive in memoria. */
interface MemoriaPosizioni {
    suspend fun leggiPosizioni(): List<PosizioneLocale>
    suspend fun scriviPosizioni(posizioni: List<PosizioneLocale>)
}

class MemoriaPosizioniInMemoria(iniziali: List<PosizioneLocale> = emptyList()) : MemoriaPosizioni {
    var posizioni: List<PosizioneLocale> = iniziali
    override suspend fun leggiPosizioni(): List<PosizioneLocale> = posizioni
    override suspend fun scriviPosizioni(posizioni: List<PosizioneLocale>) {
        this.posizioni = posizioni
    }
}

fun codificaPosizioni(p: List<PosizioneLocale>): String = JsonLectio.encodeToString(ListSerializer(PosizioneLocale.serializer()), p)
fun decodificaPosizioni(testo: String?): List<PosizioneLocale> = try {
    if (testo.isNullOrBlank()) emptyList() else JsonLectio.decodeFromString(ListSerializer(PosizioneLocale.serializer()), testo)
} catch (e: Exception) {
    emptyList()
}

/**
 * Manda le posizioni al server; senza rete le tiene (una per lezione, l'ultima) e
 * le rimanda appena si può. [invia] è il PUT …/ascolto.
 */
class SincronizzatorePosizioni(
    private val memoria: MemoriaPosizioni,
    private val invia: suspend (Long, CorpoAscolto) -> Esito<Unit>,
    private val adesso: () -> Long = System::currentTimeMillis,
    /**
     * Dopo ogni invio riuscito: la copia sul telefono della lezione (cache e
     * dettaglio scaricato) prende la posizione nuova, così senza rete non si
     * riparte da una posizione vecchia.
     */
    private val dopoInvio: suspend (Long, Ascolto) -> Unit = { _, _ -> },
) {
    private val blocco = Mutex()

    /** Registra e prova a mandare. true = arrivata al server. */
    suspend fun registra(lezioneId: Long, posizioneS: Double, finita: Boolean): Boolean {
        val p = PosizioneLocale(lezioneId, posizioneS, finita, adesso())
        blocco.withLock {
            memoria.scriviPosizioni(memoria.leggiPosizioni().filterNot { it.lezioneId == lezioneId } + p)
        }
        return inviaInSospeso()
    }

    /** L'ultima posizione tenuta sul telefono per una lezione (non ancora mandata). */
    suspend fun inSospeso(lezioneId: Long): PosizioneLocale? = memoria.leggiPosizioni().firstOrNull { it.lezioneId == lezioneId }

    /**
     * true = non resta niente da mandare. Senza rete o con un errore del server
     * (502/503 del tunnel compresi) la posizione resta; un rifiuto vero (es. lezione
     * cancellata, 404) la toglie.
     */
    suspend fun inviaInSospeso(): Boolean = blocco.withLock {
        val tutte = memoria.leggiPosizioni()
        if (tutte.isEmpty()) return@withLock true
        val rimaste = mutableListOf<PosizioneLocale>()
        var senzaRete = false
        for (p in tutte) {
            if (senzaRete) {
                rimaste += p
                continue
            }
            val quando = Instant.ofEpochMilli(p.quando).toString()
            val e = invia(p.lezioneId, CorpoAscolto(p.posizioneS, p.finita, quando))
            when {
                e is Esito.Ok -> dopoInvio(p.lezioneId, Ascolto(p.posizioneS, p.finita, quando))
                e.daRitentare -> {
                    senzaRete = true
                    rimaste += p
                }
                else -> Unit
            }
        }
        memoria.scriviPosizioni(rimaste)
        rimaste.isEmpty()
    }

    /** Il server ha già una posizione più nuova: quella del telefono non serve più. */
    suspend fun dimentica(lezioneId: Long) = blocco.withLock {
        memoria.scriviPosizioni(memoria.leggiPosizioni().filterNot { it.lezioneId == lezioneId })
    }
}
