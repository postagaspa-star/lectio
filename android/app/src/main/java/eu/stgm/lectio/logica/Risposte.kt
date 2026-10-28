package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.daRitentare
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Dove sta la risposta scritta alla domanda aperta (aspetto.md §7). */
sealed interface StatoRisposta {
    /** Si scrive (o non si è ancora cominciato). */
    data object DaScrivere : StatoRisposta

    /** "Correggo…": la risposta è partita, si aspetta il server. */
    data class Correggo(val testo: String) : StatoRisposta

    data class Corretta(val testo: String, val correzione: Correzione) : StatoRisposta

    /** Senza rete: la risposta è in coda, si vede subito la risposta giusta. */
    data class InCoda(val testo: String) : StatoRisposta

    /** Saltata (anche col play degli auricolari a schermo spento): va in "Da ripassare". */
    data object Saltata : StatoRisposta

    /** Il server ha detto di no (per esempio una risposta troppo lunga): si può correggere e rimandare. */
    data class Rifiutata(val testo: String, val messaggio: String) : StatoRisposta
}

/** L'esito come lo legge Andrea: parola e tono (mai rosso: aspetto.md §7). */
enum class ToniEsito { GIUSTA, QUASI, DA_RIVEDERE, NEUTRO }

object Esiti {
    fun parola(esito: String?): String = when (esito) {
        "giusta" -> "Giusta"
        "parziale" -> "Quasi"
        "sbagliata" -> "Da rivedere"
        "saltata" -> "Saltata"
        else -> "Da confrontare"
    }

    fun tono(esito: String?): ToniEsito = when (esito) {
        "giusta" -> ToniEsito.GIUSTA
        "parziale" -> ToniEsito.QUASI
        "sbagliata", "saltata" -> ToniEsito.DA_RIVEDERE
        else -> ToniEsito.NEUTRO
    }

    /** In "Da ripassare" la saltata si legge come "Da rivedere" (prototipo Materia). */
    fun parolaRipasso(esito: String?): String = if (esito == "parziale") "Quasi" else "Da rivedere"
}

@Serializable
data class RispostaInCoda(
    val lezioneId: Long,
    val domandaId: Long,
    val testo: String,
    val saltata: Boolean,
    val quando: Long,
)

interface MemoriaRisposte {
    suspend fun leggiRisposte(): List<RispostaInCoda>
    suspend fun scriviRisposte(risposte: List<RispostaInCoda>)
}

class MemoriaRisposteInMemoria(iniziali: List<RispostaInCoda> = emptyList()) : MemoriaRisposte {
    var risposte: List<RispostaInCoda> = iniziali
    override suspend fun leggiRisposte() = risposte
    override suspend fun scriviRisposte(risposte: List<RispostaInCoda>) {
        this.risposte = risposte
    }
}

fun codificaRisposte(r: List<RispostaInCoda>): String = JsonLectio.encodeToString(ListSerializer(RispostaInCoda.serializer()), r)
fun decodificaRisposte(testo: String?): List<RispostaInCoda> = try {
    if (testo.isNullOrBlank()) emptyList() else JsonLectio.decodeFromString(ListSerializer(RispostaInCoda.serializer()), testo)
} catch (e: Exception) {
    emptyList()
}

/** Com'è andato l'invio di una risposta. */
sealed interface EsitoRisposta {
    data class Corretta(val correzione: Correzione) : EsitoRisposta
    data object InCoda : EsitoRisposta
    data class Rifiutata(val messaggio: String) : EsitoRisposta
}

/**
 * Le risposte scritte (contratto §15.2). Senza rete restano in coda sul telefono
 * (una per domanda: vale l'ultima) e partono appena c'è rete.
 */
class CodaRisposte(
    private val memoria: MemoriaRisposte,
    private val invia: suspend (Long, Long, CorpoRisposta) -> Esito<Correzione>,
    private val adesso: () -> Long = System::currentTimeMillis,
) {
    private val blocco = Mutex()

    suspend fun rispondi(lezioneId: Long, domandaId: Long, testo: String, saltata: Boolean): EsitoRisposta {
        val pulito = if (saltata) "" else testo.trim().take(MASSIMO_CARATTERI)
        return when (val e = invia(lezioneId, domandaId, CorpoRisposta(pulito, saltata))) {
            is Esito.Ok -> {
                // Una risposta nuova arrivata vale più di una vecchia rimasta in coda.
                blocco.withLock { memoria.scriviRisposte(memoria.leggiRisposte().filterNot { it.domandaId == domandaId && it.lezioneId == lezioneId }) }
                EsitoRisposta.Corretta(e.dato)
            }
            is Esito.Errore ->
                if (e.daRitentare) metti(lezioneId, domandaId, pulito, saltata) else EsitoRisposta.Rifiutata(e.messaggio)
            Esito.Irraggiungibile -> metti(lezioneId, domandaId, pulito, saltata)
        }
    }

    private suspend fun metti(lezioneId: Long, domandaId: Long, testo: String, saltata: Boolean): EsitoRisposta {
        blocco.withLock {
            val altre = memoria.leggiRisposte().filterNot { it.domandaId == domandaId && it.lezioneId == lezioneId }
            memoria.scriviRisposte(altre + RispostaInCoda(lezioneId, domandaId, testo, saltata, adesso()))
        }
        return EsitoRisposta.InCoda
    }

    /** true = non resta niente in coda. Un rifiuto del server la toglie (non si riproverebbe mai). */
    suspend fun inviaInCoda(): Boolean = blocco.withLock {
        val tutte = memoria.leggiRisposte()
        if (tutte.isEmpty()) return@withLock true
        val rimaste = mutableListOf<RispostaInCoda>()
        var senzaRete = false
        for (r in tutte) {
            if (senzaRete) {
                rimaste += r
                continue
            }
            val e = invia(r.lezioneId, r.domandaId, CorpoRisposta(r.testo, r.saltata))
            if (e.daRitentare) {
                senzaRete = true
                rimaste += r
            }
        }
        memoria.scriviRisposte(rimaste)
        rimaste.isEmpty()
    }

    suspend fun inCoda(): List<RispostaInCoda> = memoria.leggiRisposte()

    companion object {
        const val MASSIMO_CARATTERI = 2000
    }
}
