package eu.stgm.lectio.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * I dati nuovi della v1.5 del contratto (docs/contratto-api.md §17-21): fonti, schemi,
 * "Non ho capito", ripasso lampo, verifica simulata. Tutto facoltativo come in Modelli.kt.
 */

// --- §17.2 Fonti --------------------------------------------------------------------------------

@Serializable
data class Fonte(
    @SerialName("materiale_id") val materialeId: Long = 0,
    val titolo: String = "",
    val tipo: String = "altro",
    val pagina: Int? = null,
    /** Quello che le app mostrano: "Libro, pag. 42", "Verifica del 12/10". */
    val etichetta: String = "",
)

// --- Stima comune a schemi e simulate ------------------------------------------------------------

/** §18: la stima di uno schema; §21: "stessi campi" per la simulata, senza `immagini`. */
@Serializable
data class StimaStudio(
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    @SerialName("spesa_mese_eur") val spesaMeseEur: Double = 0.0,
    @SerialName("impegnato_eur") val impegnatoEur: Double = 0.0,
    @SerialName("tetto_eur") val tettoEur: Double = 2.0,
    @SerialName("dopo_eur") val dopoEur: Double = 0.0,
    val consentita: Boolean = true,
    val motivo: String? = null,
    @SerialName("si_azzera_il") val siAzzeraIl: String? = null,
    @SerialName("minuti_preparazione") val minutiPreparazione: Int? = null,
    val immagini: Int = 0,
)

// --- §18 Schemi ---------------------------------------------------------------------------------

@Serializable
data class RichiestaStimaSchema(
    @SerialName("materia_id") val materiaId: Long,
    val argomento: String,
    val materiali: List<Long>,
    val forma: String,
    val livello: String,
    val indicazioni: String,
)

@Serializable
data class NuovoSchema(
    @SerialName("materia_id") val materiaId: Long,
    @SerialName("evento_id") val eventoId: Long?,
    val argomento: String,
    val materiali: List<Long>,
    val forma: String,
    val livello: String,
    val indicazioni: String,
)

/** I tipi di blocco che le app sanno mostrare: gli altri si saltano (contratto §18). */
val TIPI_BLOCCO = setOf("paragrafo", "punti", "passaggi", "definizione", "tabella", "prof", "immagine")

@Serializable
data class BloccoSchema(
    val tipo: String = "",
    val titolo: String? = null,
    val testo: String? = null,
    val voci: List<String> = emptyList(),
    val fonti: List<String> = emptyList(),
    val termine: String? = null,
    val intestazione: List<String> = emptyList(),
    val righe: List<List<String>> = emptyList(),
    val n: Int = 0,
    val didascalia: String? = null,
    val credito: String? = null,
)

/**
 * Salta i blocchi di tipo sconosciuto già nella lettura: un blocco nuovo fatto in un altro
 * modo non deve mai far cadere tutto lo schema.
 */
object BlocchiNoti : JsonTransformingSerializer<List<BloccoSchema>>(ListSerializer(BloccoSchema.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        JsonArray((element as? JsonArray).orEmpty().filter { b -> ((b as? JsonObject)?.get("tipo") as? JsonPrimitive)?.content in TIPI_BLOCCO })
}

@Serializable
data class CampoSchema(val nome: String = "", val valore: String = "")

@Serializable
data class SezioneSchema(
    val n: Int = 0,
    val titolo: String = "",
    val campi: List<CampoSchema> = emptyList(),
    @Serializable(with = BlocchiNoti::class) val blocchi: List<BloccoSchema> = emptyList(),
)

@Serializable
data class Copertura(val concetti: Int = 0, val coperti: Int = 0, val mancanti: List<String> = emptyList())

@Serializable
data class ContenutoSchema(
    val forma: String = "capitoli",
    val sezioni: List<SezioneSchema> = emptyList(),
    val fonti: Map<String, Fonte> = emptyMap(),
    val copertura: Copertura? = null,
    val avvisi: List<String> = emptyList(),
)

@Serializable
data class Schema(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    @SerialName("evento_id") val eventoId: Long? = null,
    val argomento: String = "",
    val titolo: String = "",
    val forma: String = "auto",
    val livello: String = "completo",
    val indicazioni: String = "",
    val materiali: List<Long> = emptyList(),
    val stato: String = "in_coda",
    val fase: String? = null,
    val fatto: Int = 0,
    val totale: Int = 0,
    val frase: String? = null,
    val errore: String? = null,
    val nota: String? = null,
    @SerialName("stima_eur") val stimaEur: Double = 0.0,
    @SerialName("creato_il") val creatoIl: String? = null,
    @SerialName("pronto_il") val prontoIl: String? = null,
    val carte: Int = 0,
    val contenuto: ContenutoSchema? = null,
    /** §26: i riassunti già chiesti e quanto costa farne uno; null se lo schema non è pronto. */
    val riassunti: RiassuntiSchema? = null,
) {
    val pronto: Boolean get() = stato == "pronto"
    val inLavoro: Boolean get() = stato == "in_coda" || stato == "in_preparazione"
    val fallito: Boolean get() = stato == "errore"
    val annullato: Boolean get() = stato == "annullato"

    /** Il titolo se il server l'ha già scritto, altrimenti l'argomento chiesto. */
    val nome: String get() = titolo.ifBlank { argomento }
}

// --- §26 Riassunti scritti -----------------------------------------------------------------------

@Serializable
data class RiassuntoBreve(val id: Long = 0, val forma: String = "", val stato: String = "")

@Serializable
data class RiassuntiSchema(
    @SerialName("stima_eur") val stimaEur: Double = 0.0,
    val fatti: List<RiassuntoBreve> = emptyList(),
)

@Serializable
data class CorpoRiassunto(val forma: String)

@Serializable
data class ParteRiassunto(
    val titolo: String? = null,
    val paragrafi: List<String> = emptyList(),
    val punti: List<String> = emptyList(),
)

@Serializable
data class ContenutoRiassunto(val parti: List<ParteRiassunto> = emptyList())

@Serializable
data class Riassunto(
    val id: Long = 0,
    @SerialName("schema_id") val schemaId: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    val forma: String = "misto",
    val titolo: String = "",
    val stato: String = "in_coda",
    val frase: String? = null,
    val errore: String? = null,
    val nota: String? = null,
    @SerialName("stima_eur") val stimaEur: Double = 0.0,
    @SerialName("creato_il") val creatoIl: String? = null,
    @SerialName("pronto_il") val prontoIl: String? = null,
    val parole: Int = 0,
    val contenuto: ContenutoRiassunto? = null,
) {
    val pronto: Boolean get() = stato == "pronto"
    val inLavoro: Boolean get() = stato == "in_coda" || stato == "in_preparazione"
    val fallito: Boolean get() = stato == "errore"
}

// --- §19 "Non ho capito" ------------------------------------------------------------------------

@Serializable
data class CorpoChiarimento(
    @SerialName("materia_id") val materiaId: Long,
    @SerialName("schema_id") val schemaId: Long? = null,
    val sezione: Int? = null,
    val passaggio: String? = null,
    @SerialName("lezione_id") val lezioneId: Long? = null,
    @SerialName("posizione_s") val posizioneS: Double? = null,
    val domanda: String = "",
)

@Serializable
data class Chiarimento(
    val id: Long = 0,
    val passaggio: String? = null,
    val domanda: String? = null,
    val risposta: String = "",
    /** false = nei documenti non c'è: una risposta tranquilla, mai un errore. */
    val trovato: Boolean = true,
    val fonti: List<Fonte> = emptyList(),
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    val quando: String? = null,
)

// --- §20 Ripasso lampo --------------------------------------------------------------------------

@Serializable
data class CartaLampo(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    val materia: String = "",
    val colore: String = "ambra",
    val domanda: String = "",
    val risposta: String = "",
    val fonti: List<Fonte> = emptyList(),
    val scatola: Int = 1,
)

@Serializable
data class Lampo(
    val carte: List<CartaLampo> = emptyList(),
    @SerialName("da_fare") val daFare: Int = 0,
    val domani: Int = 0,
)

@Serializable
data class CorpoEsitoLampo(val esito: String)

@Serializable
data class RispostaLampo(val scatola: Int = 1, val prossima: String? = null, @SerialName("da_fare") val daFare: Int = 0)

/** Il pezzo `lampo` di GET /api/oggi. */
@Serializable
data class LampoOggi(@SerialName("da_fare") val daFare: Int = 0, val minuti: Int = 0)

// --- §21 Verifica simulata ----------------------------------------------------------------------

@Serializable
data class RichiestaStimaSimulata(
    @SerialName("materia_id") val materiaId: Long,
    val argomento: String,
    val materiali: List<Long>,
    val minuti: Int,
    val indicazioni: String,
)

@Serializable
data class NuovaSimulata(
    @SerialName("materia_id") val materiaId: Long,
    @SerialName("evento_id") val eventoId: Long?,
    val argomento: String,
    val materiali: List<Long>,
    val minuti: Int,
    val indicazioni: String,
)

@Serializable
data class DomandaSimulata(
    val n: Int = 0,
    val tipo: String = "aperta",
    val testo: String = "",
    val punti: Double = 0.0,
    val sezione: String? = null,
)

@Serializable
data class EsitoDomandaSimulata(
    val n: Int = 0,
    val esito: String = "non_corretta",
    val punti: Double? = null,
    val commento: String? = null,
    val mancava: List<String> = emptyList(),
    @SerialName("risposta_attesa") val rispostaAttesa: String? = null,
    val fonti: List<Fonte> = emptyList(),
)

@Serializable
data class EsitoSimulata(
    val punti: Double = 0.0,
    @SerialName("punti_totali") val puntiTotali: Double = 0.0,
    @SerialName("voto_stimato") val votoStimato: Double? = null,
    val soglia: Double? = null,
    val domande: List<EsitoDomandaSimulata> = emptyList(),
    @SerialName("da_rifare") val daRifare: Int = 0,
)

@Serializable
data class Simulata(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    @SerialName("evento_id") val eventoId: Long? = null,
    val argomento: String = "",
    val titolo: String = "",
    val minuti: Int = 50,
    val materiali: List<Long> = emptyList(),
    val indicazioni: String = "",
    val stato: String = "in_coda",
    val fase: String? = null,
    val fatto: Int = 0,
    val totale: Int = 0,
    val frase: String? = null,
    val errore: String? = null,
    val nota: String? = null,
    @SerialName("stima_eur") val stimaEur: Double = 0.0,
    @SerialName("creata_il") val creataIl: String? = null,
    @SerialName("iniziata_il") val iniziataIl: String? = null,
    @SerialName("consegnata_il") val consegnataIl: String? = null,
    val domande: List<DomandaSimulata> = emptyList(),
    val risposte: Map<String, String> = emptyMap(),
    val esito: EsitoSimulata? = null,
) {
    val inLavoro: Boolean get() = stato == "in_coda" || stato == "in_preparazione"
    val pronta: Boolean get() = stato == "pronta"
    val inCorso: Boolean get() = stato == "in_corso"
    val inCorrezione: Boolean get() = stato == "in_correzione"
    val corretta: Boolean get() = stato == "corretta"
    val fallita: Boolean get() = stato == "errore"
    val annullata: Boolean get() = stato == "annullata"
    val nome: String get() = titolo.ifBlank { argomento }
}

/** PATCH /api/simulate/{id}: quello che c'è, `iniziata` solo alla partenza. */
@Serializable
data class CorpoSalvaSimulata(val risposte: Map<String, String>, val iniziata: Boolean? = null)
