package eu.stgm.lectio.dati

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * I dati del server di Lectio, campo per campo come in docs/contratto-api.md (v1.0).
 * Tutto facoltativo dove il contratto può mandare null o niente: un campo in più o
 * in meno non deve mai far cadere una schermata.
 */
val JsonLectio = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

// --- §2 Abbinamento, §3 salute e versioni -------------------------------------------------------

@Serializable
data class CorpoAbbina(val codice: String, val nome: String, val tipo: String = "android")

@Serializable
data class Abbinamento(
    val token: String = "",
    @SerialName("dispositivo_id") val dispositivoId: Long? = null,
    /** v1.3 §15.5: l'identità del server, da salvare con il token. */
    @SerialName("server_id") val serverId: String? = null,
    /** v1.3 §15.5: l'https da usare fuori casa, scelto sul server (null = nessuno). */
    @SerialName("indirizzo_fuori_casa") val indirizzoFuoriCasa: String? = null,
)

@Serializable
data class Dispositivo(val id: Long = 0, val nome: String = "", val tipo: String? = null)

@Serializable
data class VersioneApp(val codice: Int = 0, val nome: String = "", val sha256: String? = null, val byte: Long? = null)

@Serializable
data class Versioni(
    val server: String? = null,
    val android: VersioneApp? = null,
    val pc: VersioneApp? = null,
    val nota: String? = null,
)

@Serializable
data class StatoServizio(val stato: String = "non_disponibile", val fornitore: String? = null) {
    val pronto: Boolean get() = stato == "pronto" || stato == "pronta"
}

@Serializable
data class Coda(@SerialName("lezioni_in_preparazione") val lezioniInPreparazione: Int = 0)

@Serializable
data class Salute(
    val ok: Boolean = false,
    val versione: String? = null,
    @SerialName("modalita_prova") val modalitaProva: Boolean = false,
    val testo: StatoServizio? = null,
    @SerialName("voce_espressiva") val voceEspressiva: StatoServizio? = null,
    @SerialName("voce_gratis") val voceGratis: StatoServizio? = null,
    val mancano: List<String> = emptyList(),
    val coda: Coda? = null,
    /** v1.2: identità fissa del server, anche senza token (punto 6 della revisione). */
    @SerialName("server_id") val serverId: String? = null,
    /** v1.3 §15.5, solo con il token: l'https da usare fuori casa (null = nessuno). */
    @SerialName("indirizzo_fuori_casa") val indirizzoFuoriCasa: String? = null,
)

// --- §4 Materie, §5 scheda del prof -----------------------------------------------------------

@Serializable
data class Conteggi(
    val verifiche: Int = 0,
    val interrogazioni: Int = 0,
    val appunti: Int = 0,
    val lezioni: Int = 0,
)

@Serializable
data class Centratura(
    @SerialName("previste_uscite") val previsteUscite: Int = 0,
    @SerialName("uscite_totali") val usciteTotali: Int = 0,
    val percentuale: Int = 0,
)

@Serializable
data class Materia(
    val id: Long = 0,
    val nome: String = "",
    val prof: String = "",
    val colore: String = "ambra",
    val conteggi: Conteggi = Conteggi(),
    val centratura: Centratura? = null,
    /** v1.1 §15.2: quante domande sono da ripassare. */
    @SerialName("da_ripassare") val daRipassare: Int = 0,
)

/** La materia in breve, come arriva dentro Oggi. */
@Serializable
data class MateriaBreve(val id: Long = 0, val nome: String = "", val colore: String = "ambra")

@Serializable
data class NuovaMateria(val nome: String, val prof: String, val colore: String? = null)

@Serializable
data class BasataSu(val verifiche: Int = 0, val interrogazioni: Int = 0, val esiti: Int = 0)

@Serializable
data class Argomento(val nome: String = "", val volte: Int = 0, val esempio: String? = null)

@Serializable
data class TipoDomanda(val tipo: String = "altro", val quota: Double = 0.0)

@Serializable
data class SchedaProf(
    @SerialName("materia_id") val materiaId: Long = 0,
    @SerialName("aggiornata_il") val aggiornataIl: String? = null,
    @SerialName("basata_su") val basataSu: BasataSu = BasataSu(),
    val argomenti: List<Argomento> = emptyList(),
    @SerialName("tipi_domanda") val tipiDomanda: List<TipoDomanda> = emptyList(),
    @SerialName("cosa_corregge") val cosaCorregge: List<String> = emptyList(),
    @SerialName("cosa_premia") val cosaPremia: List<String> = emptyList(),
    @SerialName("lunghezza_risposte") val lunghezzaRisposte: String? = null,
    val consigli: List<String> = emptyList(),
    @SerialName("note_mie") val noteMie: String = "",
    val stato: String = "vuota",
    val errore: String? = null,
)

/** Il PUT della scheda: tutto tranne i campi calcolati (§5). */
@Serializable
data class ModificaScheda(
    val argomenti: List<Argomento>,
    @SerialName("tipi_domanda") val tipiDomanda: List<TipoDomanda>,
    @SerialName("cosa_corregge") val cosaCorregge: List<String>,
    @SerialName("cosa_premia") val cosaPremia: List<String>,
    @SerialName("lunghezza_risposte") val lunghezzaRisposte: String?,
    val consigli: List<String>,
    @SerialName("note_mie") val noteMie: String,
)

// --- §6 Materiale ------------------------------------------------------------------------------

@Serializable
data class Materiale(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    val tipo: String = "altro",
    val titolo: String = "",
    val data: String? = null,
    val origine: String? = null,
    @SerialName("nome_file") val nomeFile: String? = null,
    val caratteri: Int? = null,
    val stato: String = "pronto",
    val errore: String? = null,
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    @SerialName("creato_il") val creatoIl: String? = null,
    /** Solo in GET /api/materiale/{id}: quello che il server ha letto. */
    val testo: String? = null,
    /** v1.3: una frase da mostrare sotto il titolo ("Ho letto solo le prime 70 pagine…"). */
    val avviso: String? = null,
)

@Serializable
data class MaterialeScritto(val tipo: String, val titolo: String?, val data: String?, val testo: String)

// --- §7 Eventi ---------------------------------------------------------------------------------

@Serializable
data class Evento(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    val tipo: String = "scritta",
    val argomento: String = "",
    val data: String = "",
    /** Il contratto non ne fissa la forma: basta sapere se c'è. */
    val esito: JsonElement? = null,
    @SerialName("lezione_id") val lezioneId: Long? = null,
) {
    val haEsito: Boolean get() = esito != null && esito !is JsonNull
    val orale: Boolean get() = tipo == "orale"
}

@Serializable
data class NuovoEvento(val tipo: String, val argomento: String, val data: String)

@Serializable
data class CorpoEsito(
    @SerialName("domande_uscite") val domandeUscite: List<Long>,
    @SerialName("altre_domande") val altreDomande: String,
    val voto: String?,
)

@Serializable
data class RisultatoEsito(
    @SerialName("previste_uscite") val previsteUscite: Int = 0,
    @SerialName("totale_vere") val totaleVere: Int = 0,
    val percentuale: Int = 0,
)

// --- §8 Voci, stime, spesa ---------------------------------------------------------------------

@Serializable
data class Voce(
    val id: String = "",
    val nome: String = "",
    val tipo: String = "gratis",
    val fornitore: String? = null,
    @SerialName("euro_al_minuto") val euroAlMinuto: Double = 0.0,
    val disponibile: Boolean = true,
)

@Serializable
data class RichiestaStima(
    @SerialName("materia_id") val materiaId: Long,
    @SerialName("durata_min") val durataMin: Int,
    val voce: String,
    /** Come in POST /api/lezioni: solo se Andrea ha toccato "Cambia" (null = tutto il materiale; [] → 422). */
    val materiali: List<Long>? = null,
)

@Serializable
data class Stima(
    @SerialName("caratteri_stimati") val caratteriStimati: Int = 0,
    @SerialName("costo_testo_eur") val costoTestoEur: Double = 0.0,
    @SerialName("costo_voce_eur") val costoVoceEur: Double = 0.0,
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    @SerialName("spesa_mese_eur") val spesaMeseEur: Double = 0.0,
    @SerialName("tetto_eur") val tettoEur: Double = 2.0,
    @SerialName("dopo_eur") val dopoEur: Double = 0.0,
    val consentita: Boolean = true,
    val motivo: String? = null,
    @SerialName("voce_gratis_caratteri_rimasti") val voceGratisCaratteriRimasti: Long? = null,
    @SerialName("si_azzera_il") val siAzzeraIl: String? = null,
    /** v1.3: la parte di [spesaMeseEur] che è lavoro ancora in corso (lezioni in coda, letture, schede). */
    @SerialName("impegnato_eur") val impegnatoEur: Double = 0.0,
)

@Serializable
data class VoceGratisMese(
    @SerialName("caratteri_usati") val caratteriUsati: Long = 0,
    @SerialName("caratteri_mese") val caratteriMese: Long = 0,
)

@Serializable
data class VoceSpesa(
    val quando: String? = null,
    val cosa: String? = null,
    val fornitore: String? = null,
    val quantita: Double? = null,
    val unita: String? = null,
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    @SerialName("lezione_id") val lezioneId: Long? = null,
)

@Serializable
data class Spesa(
    val mese: String? = null,
    @SerialName("speso_eur") val spesoEur: Double = 0.0,
    @SerialName("tetto_eur") val tettoEur: Double = 2.0,
    val percentuale: Int = 0,
    @SerialName("si_azzera_il") val siAzzeraIl: String? = null,
    @SerialName("voce_gratis") val voceGratis: VoceGratisMese? = null,
    val voci: List<VoceSpesa> = emptyList(),
)

// --- §9 Lezioni --------------------------------------------------------------------------------

@Serializable
data class Avanzamento(
    val fase: String? = null,
    val fatto: Int = 0,
    val totale: Int = 0,
    val frase: String? = null,
)

@Serializable
data class Ascolto(
    @SerialName("posizione_s") val posizioneS: Double = 0.0,
    val finita: Boolean = false,
    @SerialName("aggiornato_il") val aggiornatoIl: String? = null,
)

@Serializable
data class InfoAudio(val byte: Long? = null, val sha256: String? = null)

@Serializable
data class Capitolo(
    val n: Int = 0,
    val titolo: String = "",
    @SerialName("inizio_s") val inizioS: Double = 0.0,
    @SerialName("fine_s") val fineS: Double = 0.0,
)

@Serializable
data class Domanda(
    val id: Long = 0,
    val tipo: String = "blocco",
    val n: Int = 0,
    val di: Int = 0,
    val testo: String = "",
    val risposta: String = "",
    @SerialName("inizio_s") val inizioS: Double = 0.0,
    @SerialName("fine_domanda_s") val fineDomandaS: Double = 0.0,
    @SerialName("inizio_risposta_s") val inizioRispostaS: Double = 0.0,
    @SerialName("fine_s") val fineS: Double = 0.0,
    /** v1.1 §15.2: l'ultima risposta scritta di Andrea, se c'è. */
    @SerialName("mia_risposta") val miaRisposta: MiaRisposta? = null,
)

@Serializable
data class MiaRisposta(
    val testo: String? = null,
    val esito: String? = null,
    val commento: String? = null,
    val mancava: List<String> = emptyList(),
)

/** v1.1 §15.2: POST …/domande/{domanda_id}/risposta. */
@Serializable
data class CorpoRisposta(val testo: String, val saltata: Boolean)

/** La correzione di una risposta scritta. `esito`: giusta | parziale | sbagliata | saltata | non_corretta. */
@Serializable
data class Correzione(
    val id: Long = 0,
    @SerialName("domanda_id") val domandaId: Long = 0,
    val esito: String = "non_corretta",
    val punteggio: Double? = null,
    val commento: String? = null,
    val mancava: List<String> = emptyList(),
    @SerialName("risposta_attesa") val rispostaAttesa: String? = null,
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    val quando: String? = null,
)

/** v1.1 §15.2: GET /api/materie/{id}/da_ripassare. */
@Serializable
data class DaRipassare(
    @SerialName("domanda_id") val domandaId: Long = 0,
    @SerialName("lezione_id") val lezioneId: Long = 0,
    val lezione: String = "",
    val testo: String = "",
    val esito: String = "sbagliata",
    val commento: String? = null,
    val quando: String? = null,
)

@Serializable
data class TestoCapitolo(val capitolo: Int = 0, val paragrafi: List<String> = emptyList())

@Serializable
data class Lezione(
    val id: Long = 0,
    @SerialName("materia_id") val materiaId: Long = 0,
    @SerialName("evento_id") val eventoId: Long? = null,
    val argomento: String = "",
    @SerialName("durata_min") val durataMin: Int = 0,
    @SerialName("durata_s") val durataS: Double? = null,
    val voce: String? = null,
    val stato: String = "in_coda",
    val avanzamento: Avanzamento? = null,
    @SerialName("costo_eur") val costoEur: Double = 0.0,
    @SerialName("creata_il") val creataIl: String? = null,
    @SerialName("pronta_il") val prontaIl: String? = null,
    val errore: String? = null,
    val nota: String? = null,
    val ascolto: Ascolto? = null,
    val audio: InfoAudio? = null,
    // Solo nel dettaglio (GET /api/lezioni/{id}).
    val capitoli: List<Capitolo> = emptyList(),
    val domande: List<Domanda> = emptyList(),
    val testo: List<TestoCapitolo> = emptyList(),
    /** v1.2 §15.4: true = alle domande si può saltare (anche col play degli auricolari). */
    @SerialName("domande_saltabili") val domandeSaltabili: Boolean = false,
) {
    val pronta: Boolean get() = stato == "pronta"
    val inLavoro: Boolean get() = stato == "in_coda" || stato == "in_preparazione"
    val fallita: Boolean get() = stato == "errore"
    val annullata: Boolean get() = stato == "annullata"

    /** La durata vera se c'è, altrimenti quella chiesta. */
    val durataSecondi: Double get() = durataS?.takeIf { it > 0 } ?: (durataMin * 60.0)
}

@Serializable
data class NuovaLezione(
    @SerialName("materia_id") val materiaId: Long,
    @SerialName("evento_id") val eventoId: Long?,
    val argomento: String,
    /**
     * Dalla v1.1 (§15.1) si manda solo se Andrea ha toccato "Cambia": null = non si
     * manda e la lezione usa tutto il materiale della materia.
     */
    val materiali: List<Long>? = null,
    @SerialName("durata_min") val durataMin: Int,
    val voce: String,
    /** v1.2 §15.4: "Obbligatorie" (false) o "Si possono saltare" (true). */
    @SerialName("domande_saltabili") val domandeSaltabili: Boolean = false,
)

/**
 * Il corpo di PUT …/ascolto. [aggiornatoIl] è il momento in cui il telefono ha
 * registrato la posizione: il server tiene la più recente (§9, "vince il più recente").
 */
@Serializable
data class CorpoAscolto(
    @SerialName("posizione_s") val posizioneS: Double,
    val finita: Boolean,
    @SerialName("aggiornato_il") val aggiornatoIl: String,
)

// --- §10 Oggi ----------------------------------------------------------------------------------

@Serializable
data class Prossima(
    val evento: Evento = Evento(),
    val materia: MateriaBreve = MateriaBreve(),
    val lezione: Lezione? = null,
    @SerialName("tra_giorni") val traGiorni: Int = 0,
)

@Serializable
data class DaRaccontare(val evento: Evento = Evento(), val materia: MateriaBreve = MateriaBreve())

@Serializable
data class PiuAvanti(
    val evento: Evento = Evento(),
    val materia: MateriaBreve = MateriaBreve(),
    @SerialName("tra_giorni") val traGiorni: Int = 0,
)

@Serializable
data class SpesaBreve(
    @SerialName("speso_eur") val spesoEur: Double = 0.0,
    @SerialName("tetto_eur") val tettoEur: Double = 2.0,
    val percentuale: Int = 0,
    @SerialName("si_azzera_il") val siAzzeraIl: String? = null,
    val avviso: Boolean = false,
)

@Serializable
data class Oggi(
    val oggi: String? = null,
    val prossima: Prossima? = null,
    val riprendi: Lezione? = null,
    @SerialName("da_raccontare") val daRaccontare: List<DaRaccontare> = emptyList(),
    @SerialName("piu_avanti") val piuAvanti: List<PiuAvanti> = emptyList(),
    val spesa: SpesaBreve = SpesaBreve(),
    @SerialName("modalita_prova") val modalitaProva: Boolean = false,
)

// --- §11 Novità, §12 Gemini, §13 Impostazioni -------------------------------------------------

@Serializable
data class Novita(
    val id: Long = 0,
    val tipo: String = "",
    val testo: String = "",
    @SerialName("lezione_id") val lezioneId: Long? = null,
    val quando: String? = null,
)

@Serializable
data class TestoGemini(val testo: String = "", @SerialName("aggiornato_il") val aggiornatoIl: String? = null)

@Serializable
data class Impostazioni(
    @SerialName("tetto_eur") val tettoEur: Double = 2.0,
    @SerialName("voce_predefinita") val vocePredefinita: String? = null,
    @SerialName("durata_predefinita") val durataPredefinita: Int = 20,
    @SerialName("fermati_alle_domande") val fermatiAlleDomande: Boolean = true,
    val velocita: Double = 1.0,
    val nome: String? = null,
    /** v1.1 §15.2: false = alle domande solo "Senti la risposta", senza campo. */
    @SerialName("risposte_scritte") val risposteScritte: Boolean = true,
    /** v1.2 §15.4: le domande delle lezioni nuove si possono saltare (predefinito no). */
    @SerialName("domande_saltabili") val domandeSaltabili: Boolean = false,
)

// --- Errori ------------------------------------------------------------------------------------

/** `{"errore": "frase", "codice": "slug"}` (§1). */
data class ErroreServer(val errore: String?, val codice: String?)

fun leggiErroreServer(corpo: String?): ErroreServer? {
    if (corpo.isNullOrBlank()) return null
    val radice = try {
        JsonLectio.parseToJsonElement(corpo) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    // FastAPI mette a volte l'errore dentro "detail".
    val oggetto = (radice["detail"] as? JsonObject) ?: radice
    val errore = (oggetto["errore"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val codice = (oggetto["codice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (errore == null && codice == null) return null
    return ErroreServer(errore, codice)
}
