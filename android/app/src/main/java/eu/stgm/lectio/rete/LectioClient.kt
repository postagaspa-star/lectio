package eu.stgm.lectio.rete

import eu.stgm.lectio.dati.Abbinamento
import eu.stgm.lectio.dati.CorpoAbbina
import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.dati.CorpoEsito
import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.DaRipassare
import eu.stgm.lectio.dati.Dispositivo
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Impostazioni
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.MaterialeScritto
import eu.stgm.lectio.dati.ModificaScheda
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.dati.NuovaLezione
import eu.stgm.lectio.dati.NuovaMateria
import eu.stgm.lectio.dati.NuovoEvento
import eu.stgm.lectio.dati.Oggi
import eu.stgm.lectio.dati.RichiestaStima
import eu.stgm.lectio.dati.RisultatoEsito
import eu.stgm.lectio.dati.Salute
import eu.stgm.lectio.dati.SchedaProf
import eu.stgm.lectio.dati.Spesa
import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.dati.TestoGemini
import eu.stgm.lectio.dati.Versioni
import eu.stgm.lectio.dati.Voce
import eu.stgm.lectio.dati.leggiErroreServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Dove sta il server e con che token parlargli. [serverId] è l'identità del server
 * (campo `server_id` di GET /api/salute senza token) letta all'abbinamento; serve a
 * non mandare mai il token a un altro apparecchio con lo stesso indirizzo di casa.
 * [fuoriCasa] è l'indirizzo https di Tailscale, usato quando quello di casa non risponde
 * come il server di Andrea.
 */
data class Configurazione(
    val server: String,
    val token: String,
    val dispositivoId: Long? = null,
    val serverId: String? = null,
    val fuoriCasa: String? = null,
) {
    val completa: Boolean get() = server.isNotBlank() && token.isNotBlank()
}

/**
 * Quale indirizzo usare col token (punto 6 della revisione). In https il server si
 * riconosce dal certificato: si usa così com'è. In http (rete di casa) chiunque sulla
 * stessa Wi-Fi può fingersi il server: prima si chiede GET /api/salute senza token e
 * il token parte solo se `server_id` coincide con quello dell'abbinamento. Se non
 * coincide si usa l'indirizzo https da fuori casa; se non c'è, è come essere senza rete.
 */
object SceltaIndirizzo {
    suspend fun scegli(
        primario: String,
        fuoriCasa: String?,
        richiedeVerifica: (String) -> Boolean,
        verifica: suspend (String) -> Boolean,
    ): String? {
        if (!richiedeVerifica(primario)) return primario
        if (verifica(primario)) return primario
        return fuoriCasa?.trimEnd('/')?.takeIf { it.startsWith("https://") && it != primario }
    }
}

/** Un file da caricare (già copiato dall'app in un file suo). */
data class FileDaCaricare(val file: File, val nome: String, val tipoMime: String)

/**
 * Il client delle API di Lectio (docs/contratto-api.md, v1.0). Una finestra sul
 * server: niente logica qui, solo richieste e traduzione degli esiti in frasi.
 *
 * [configurazione] si rilegge a ogni chiamata: dopo un nuovo abbinamento il
 * client giusto è sempre questo. [alNonAutorizzato] parte quando il server dice
 * (due volte) che questo telefono non è più abbinato.
 */
class LectioClient(
    private val configurazione: suspend () -> Configurazione?,
    private val alNonAutorizzato: suspend () -> Unit = {},
    /** Solo per le prove: anche localhost in http va verificato col `server_id`. */
    private val verificaAncheLocale: Boolean = false,
    private val adesso: () -> Long = System::currentTimeMillis,
) {
    private val revoca = Mutex()

    /** L'ultima verifica del server in http: (indirizzo, riuscita, quando). Vale [VALIDITA_VERIFICA_MS]. */
    @Volatile
    private var verificato: Triple<String, Boolean, Long>? = null

    private fun richiedeVerifica(base: String): Boolean {
        val url = base.toHttpUrlOrNull() ?: return true
        if (url.isHttps) return false
        return verificaAncheLocale || !IndirizzoServer.locale(url.host)
    }

    /** Il server in http è proprio quello dell'abbinamento? (GET /api/salute senza token). */
    private suspend fun verificaServer(base: String, atteso: String?): Boolean {
        if (atteso.isNullOrBlank()) return false
        verificato?.let { (b, ok, quando) -> if (b == base && adesso() - quando < VALIDITA_VERIFICA_MS) return ok }
        val id = (serverIdDi(base) as? Esito.Ok)?.dato
        val ok = id != null && id == atteso
        verificato = Triple(base, ok, adesso())
        return ok
    }

    /**
     * L'indirizzo a cui mandare adesso il token, o null: niente richiesta, come se
     * non ci fosse rete (mai un 401, quindi mai l'abbinamento cancellato).
     */
    suspend fun indirizzoSicuro(conf: Configurazione? = null): String? {
        val c = conf ?: configurazione() ?: return null
        return SceltaIndirizzo.scegli(c.server, c.fuoriCasa, ::richiedeVerifica) { verificaServer(it, c.serverId) }
    }

    /** Il lettore chiede se può mettere il token in una richiesta audio verso [indirizzo]. */
    suspend fun tokenPermessoPer(indirizzo: String): Boolean {
        val base = indirizzoSicuro() ?: return false
        return indirizzo.startsWith("$base/")
    }

    // --- §2-3 Salute, versioni, dispositivi ---------------------------------------------------

    suspend fun versione(): Esito<Versioni> = chiama("GET", "/api/versione", null, Versioni.serializer())
    suspend fun salute(): Esito<Salute> = chiama("GET", "/api/salute", null, Salute.serializer())
    suspend fun dispositivi(): Esito<List<Dispositivo>> =
        chiama("GET", "/api/dispositivi", null, ListSerializer(Dispositivo.serializer()))
    suspend fun revocaDispositivo(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/dispositivi/$id", null)

    // --- §4-5 Materie e scheda del prof -------------------------------------------------------

    suspend fun materie(): Esito<List<Materia>> = chiama("GET", "/api/materie", null, ListSerializer(Materia.serializer()))

    suspend fun creaMateria(nuova: NuovaMateria): Esito<Materia> =
        chiama("POST", "/api/materie", JsonLectio.encodeToString(NuovaMateria.serializer(), nuova), Materia.serializer())

    suspend fun modificaMateria(id: Long, cambi: JsonObject): Esito<Materia> =
        chiama("PATCH", "/api/materie/$id", cambi.toString(), Materia.serializer())

    suspend fun eliminaMateria(id: Long): Esito<Unit> =
        chiamaSenzaDato("DELETE", "/api/materie/$id", CORPO_CONFERMA)

    suspend fun scheda(materiaId: Long): Esito<SchedaProf> =
        chiama("GET", "/api/materie/$materiaId/scheda", null, SchedaProf.serializer())

    suspend fun salvaScheda(materiaId: Long, modifica: ModificaScheda): Esito<SchedaProf> =
        chiama("PUT", "/api/materie/$materiaId/scheda", JsonLectio.encodeToString(ModificaScheda.serializer(), modifica), SchedaProf.serializer())

    suspend fun aggiornaScheda(materiaId: Long): Esito<Unit> =
        chiamaSenzaDato("POST", "/api/materie/$materiaId/scheda/aggiorna", CORPO_VUOTO)

    // --- §6 Materiale -------------------------------------------------------------------------

    suspend fun materiale(materiaId: Long): Esito<List<Materiale>> =
        chiama("GET", "/api/materie/$materiaId/materiale", null, ListSerializer(Materiale.serializer()))

    suspend fun materialeDettaglio(id: Long): Esito<Materiale> =
        chiama("GET", "/api/materiale/$id", null, Materiale.serializer())

    suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto): Esito<Materiale> =
        chiama("POST", "/api/materie/$materiaId/materiale", JsonLectio.encodeToString(MaterialeScritto.serializer(), scritto), Materiale.serializer())

    /** Multipart: `file` più `tipo`, `titolo`, `data` facoltativi (§6). */
    suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?): Esito<Materiale> {
        val corpo = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.nome, file.file.asRequestBody(file.tipoMime.toMediaTypeOrNull()))
            .addFormDataPart("tipo", tipo)
            .apply {
                titolo?.trim()?.takeIf { it.isNotEmpty() }?.let { addFormDataPart("titolo", it) }
                data?.takeIf { it.isNotBlank() }?.let { addFormDataPart("data", it) }
            }
            .build()
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val base = indirizzoSicuro(conf) ?: return Esito.Irraggiungibile
        val url = (base + "/api/materie/$materiaId/materiale").toHttpUrlOrNull() ?: return Esito.Irraggiungibile
        if (!trasportoConsentito(url)) return fallimento(CODICE_NON_PROTETTO, null)
        val richiesta = Request.Builder().url(url).header("Authorization", "Bearer ${conf.token}").post(corpo).build()
        val risposta = esegui(httpLento, richiesta) ?: return Esito.Irraggiungibile
        if (risposta.codice == 401) gestisciNonAutorizzato(conf, base)
        return interpreta(risposta.codice, risposta.corpo, Materiale.serializer())
    }

    suspend fun eliminaMateriale(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/materiale/$id", null)
    suspend fun riprovaMateriale(id: Long): Esito<Unit> = chiamaSenzaDato("POST", "/api/materiale/$id/riprova", CORPO_VUOTO)

    // --- §7 Eventi ----------------------------------------------------------------------------

    suspend fun eventi(da: String? = null): Esito<List<Evento>> =
        chiama("GET", if (da == null) "/api/eventi" else "/api/eventi?da=$da", null, ListSerializer(Evento.serializer()))

    suspend fun creaEvento(materiaId: Long, nuovo: NuovoEvento): Esito<Evento> =
        chiama("POST", "/api/materie/$materiaId/eventi", JsonLectio.encodeToString(NuovoEvento.serializer(), nuovo), Evento.serializer())

    suspend fun modificaEvento(id: Long, nuovo: NuovoEvento): Esito<Evento> =
        chiama("PATCH", "/api/eventi/$id", JsonLectio.encodeToString(NuovoEvento.serializer(), nuovo), Evento.serializer())

    suspend fun eliminaEvento(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/eventi/$id", null)

    suspend fun salvaEsito(eventoId: Long, corpo: CorpoEsito): Esito<RisultatoEsito> =
        chiama("POST", "/api/eventi/$eventoId/esito", JsonLectio.encodeToString(CorpoEsito.serializer(), corpo), RisultatoEsito.serializer())

    // --- §8 Voci, stime, spesa ----------------------------------------------------------------

    suspend fun voci(): Esito<List<Voce>> = chiama("GET", "/api/voci", null, ListSerializer(Voce.serializer()))

    suspend fun stima(richiesta: RichiestaStima): Esito<Stima> =
        chiama("POST", "/api/lezioni/stima", JsonLectio.encodeToString(RichiestaStima.serializer(), richiesta), Stima.serializer())

    suspend fun spesa(): Esito<Spesa> = chiama("GET", "/api/spesa", null, Spesa.serializer())

    // --- §9 Lezioni ---------------------------------------------------------------------------

    suspend fun lezioni(materiaId: Long? = null): Esito<List<Lezione>> =
        chiama("GET", if (materiaId == null) "/api/lezioni" else "/api/lezioni?materia_id=$materiaId", null, ListSerializer(Lezione.serializer()))

    suspend fun lezione(id: Long): Esito<Lezione> = chiama("GET", "/api/lezioni/$id", null, Lezione.serializer())

    suspend fun creaLezione(nuova: NuovaLezione): Esito<Lezione> =
        chiama("POST", "/api/lezioni", JsonLectio.encodeToString(NuovaLezione.serializer(), nuova), Lezione.serializer())

    /** v1.2 §15.4: PATCH della lezione (per ora solo `domande_saltabili`). */
    suspend fun modificaLezione(id: Long, cambi: JsonObject): Esito<Lezione> =
        chiama("PATCH", "/api/lezioni/$id", cambi.toString(), Lezione.serializer())

    suspend fun salvaAscolto(id: Long, corpo: CorpoAscolto): Esito<Unit> =
        chiamaSenzaDato("PUT", "/api/lezioni/$id/ascolto", JsonLectio.encodeToString(CorpoAscolto.serializer(), corpo))

    /** v1.1 §15.2: la risposta scritta (o saltata) a una domanda; torna la correzione. */
    suspend fun rispondi(lezioneId: Long, domandaId: Long, corpo: CorpoRisposta): Esito<Correzione> =
        chiama("POST", "/api/lezioni/$lezioneId/domande/$domandaId/risposta", JsonLectio.encodeToString(CorpoRisposta.serializer(), corpo), Correzione.serializer())

    suspend fun daRipassare(materiaId: Long): Esito<List<DaRipassare>> =
        chiama("GET", "/api/materie/$materiaId/da_ripassare", null, ListSerializer(DaRipassare.serializer()))

    suspend fun annullaLezione(id: Long): Esito<Unit> = chiamaSenzaDato("POST", "/api/lezioni/$id/annulla", CORPO_VUOTO)
    suspend fun riprovaLezione(id: Long): Esito<Unit> = chiamaSenzaDato("POST", "/api/lezioni/$id/riprova", CORPO_VUOTO)
    suspend fun eliminaLezione(id: Long): Esito<Unit> = chiamaSenzaDato("DELETE", "/api/lezioni/$id", null)

    // --- §10-13 Oggi, novità, Gemini, impostazioni --------------------------------------------

    suspend fun oggi(): Esito<Oggi> = chiama("GET", "/api/oggi", null, Oggi.serializer())

    suspend fun novita(dopoId: Long): Esito<List<Novita>> =
        chiama("GET", "/api/novita?dopo_id=$dopoId", null, ListSerializer(Novita.serializer()))

    suspend fun gemini(): Esito<TestoGemini> = chiama("GET", "/api/gemini", null, TestoGemini.serializer())

    suspend fun impostazioni(): Esito<Impostazioni> = chiama("GET", "/api/impostazioni", null, Impostazioni.serializer())

    /** PATCH con i soli campi cambiati (nomi del contratto). */
    suspend fun modificaImpostazioni(cambi: JsonObject): Esito<Impostazioni> =
        chiama("PATCH", "/api/impostazioni", cambi.toString(), Impostazioni.serializer())

    // --- File ---------------------------------------------------------------------------------

    /** L'indirizzo dell'audio di una lezione (il lettore aggiunge l'intestazione col token). */
    suspend fun indirizzoAudio(id: Long): String? = indirizzoSicuro()?.let { "$it/api/lezioni/$id/audio" }

    /**
     * Scarica l'audio di una lezione in [destinazione] (prima in un file .tmp, poi
     * rinominato: un file a metà non sembra mai scaricato). Se il server indica
     * l'impronta, il file deve coincidere.
     */
    suspend fun scaricaAudio(id: Long, destinazione: File, sha256: String?, avanzamento: (Float) -> Unit = {}): Esito<Unit> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val base = indirizzoSicuro(conf) ?: return Esito.Irraggiungibile
        val url = (base + "/api/lezioni/$id/audio").toHttpUrlOrNull() ?: return Esito.Irraggiungibile
        if (!trasportoConsentito(url)) return fallimento(CODICE_NON_PROTETTO, null)
        return withContext(Dispatchers.IO) {
            val temporaneo = File(destinazione.parentFile, destinazione.name + ".tmp")
            try {
                destinazione.parentFile?.mkdirs()
                val richiesta = Request.Builder().url(url).header("Authorization", "Bearer ${conf.token}").get().build()
                httpLento.newCall(richiesta).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) {
                        return@withContext fallimento(risposta.code, corpo?.string())
                    }
                    val totale = corpo.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    temporaneo.outputStream().use { uscita ->
                        corpo.byteStream().use { ingresso ->
                            val pezzo = ByteArray(64 * 1024)
                            var letti = 0L
                            while (true) {
                                val n = ingresso.read(pezzo)
                                if (n < 0) break
                                uscita.write(pezzo, 0, n)
                                digest.update(pezzo, 0, n)
                                letti += n
                                if (totale > 0) avanzamento((letti.toFloat() / totale).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val impronta = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!sha256.isNullOrBlank() && !sha256.trim().equals(impronta, ignoreCase = true)) {
                        temporaneo.delete()
                        return@withContext Esito.Errore("L'audio scaricato non è completo. Riprova.", "audio_diverso")
                    }
                    destinazione.delete()
                    if (!temporaneo.renameTo(destinazione)) {
                        temporaneo.delete()
                        return@withContext Esito.Errore("Non c'è spazio sul telefono per scaricare la lezione.", "spazio")
                    }
                    Esito.Ok(Unit)
                }
            } catch (e: IOException) {
                temporaneo.delete()
                Esito.Irraggiungibile
            }
        }
    }

    /**
     * Scarica l'APK nuovo da GET /scarica/lectio.apk (percorso fisso, mai un URL
     * arrivato dal server). Senza token: è un file pubblico; prima di installarlo
     * l'app ne controlla l'impronta.
     */
    suspend fun scaricaApk(destinazione: File): Boolean {
        val base = indirizzoSicuro() ?: return false
        val url = (base + PERCORSO_APK).toHttpUrlOrNull() ?: return false
        if (!trasportoConsentito(url)) return false
        return withContext(Dispatchers.IO) {
            try {
                httpLento.newCall(Request.Builder().url(url).get().build()).execute().use { risposta ->
                    val corpo = risposta.body
                    if (!risposta.isSuccessful || corpo == null) return@use false
                    destinazione.outputStream().use { uscita -> corpo.byteStream().copyTo(uscita) }
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    }

    // --- Meccanica ----------------------------------------------------------------------------

    private suspend fun <T> chiama(metodo: String, percorso: String, corpo: String?, serializer: KSerializer<T>): Esito<T> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, metodo, percorso, corpo) ?: return Esito.Irraggiungibile
        return interpreta(risposta.codice, risposta.corpo, serializer)
    }

    private suspend fun chiamaSenzaDato(metodo: String, percorso: String, corpo: String?): Esito<Unit> {
        val conf = configurazione()
        if (conf == null || !conf.completa) return Esito.Errore(Testi.NON_ABBINATO, Testi.CODICE_NON_ABBINATO)
        val risposta = esegui(conf, metodo, percorso, corpo) ?: return Esito.Irraggiungibile
        return if (risposta.codice in 200..299) Esito.Ok(Unit) else fallimento(risposta.codice, risposta.corpo)
    }

    /** null = rete caduta, server spento o server di casa non riconosciuto. */
    private suspend fun esegui(conf: Configurazione, metodo: String, percorso: String, corpo: String?): RispostaHttp? {
        val base = indirizzoSicuro(conf) ?: return null
        val url = (base + percorso).toHttpUrlOrNull() ?: return null
        if (!trasportoConsentito(url)) return RispostaHttp(CODICE_NON_PROTETTO, null)
        val richiesta = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${conf.token}")
            .method(metodo, corpo?.toRequestBody(JSON))
            .build()
        // Le scritture non si ritentano da sole: una lezione chiesta due volte costa due volte.
        val client = if (metodo == "GET") http else httpScritture
        val risposta = esegui(client, richiesta) ?: return null
        if (risposta.codice == 401) gestisciNonAutorizzato(conf, base)
        return risposta
    }

    /**
     * Il server non riconosce più il token. Prima di dimenticare tutto si riprova
     * una lettura semplice: solo un secondo 401 conferma. Una volta per token.
     */
    private suspend fun gestisciNonAutorizzato(conf: Configurazione, base: String) {
        revoca.withLock {
            if (conf.token == tokenGiaRevocato) return
            val url = (base + "/api/impostazioni").toHttpUrlOrNull() ?: return
            val prova = esegui(http, Request.Builder().url(url).header("Authorization", "Bearer ${conf.token}").get().build())
            if (prova == null || prova.codice != 401) return
            tokenGiaRevocato = conf.token
        }
        alNonAutorizzato()
    }

    @Volatile
    private var tokenGiaRevocato: String? = null

    private data class RispostaHttp(val codice: Int, val corpo: String?)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CORPO_VUOTO = "{}"
        private const val CORPO_CONFERMA = """{"conferma":true}"""
        const val PERCORSO_APK = "/scarica/lectio.apk"
        private const val CODICE_NON_PROTETTO = -1

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Senza ritentativi e senza connessioni tenute aperte. */
        private val httpScritture: OkHttpClient = http.newBuilder()
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** File grandi in andata (foto, PDF) e in ritorno (audio, APK). */
        val httpLento: OkHttpClient = http.newBuilder()
            .retryOnConnectionFailure(false)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()

        /** In chiaro solo verso la rete di casa. */
        fun trasportoConsentito(url: HttpUrl): Boolean = url.isHttps || IndirizzoServer.httpConsentito(url.host)

        private suspend fun esegui(client: OkHttpClient, richiesta: Request): RispostaHttp? =
            suspendCancellableCoroutine { continuazione ->
                val chiamata = client.newCall(richiesta)
                continuazione.invokeOnCancellation { chiamata.cancel() }
                chiamata.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuazione.isActive) continuazione.resume(null)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val letta = try {
                                response.use { RispostaHttp(it.code, it.body?.string()) }
                            } catch (e: IOException) {
                                null
                            }
                            if (continuazione.isActive) continuazione.resume(letta)
                        }
                    },
                )
            }

        internal fun <T> interpreta(codice: Int, corpo: String?, serializer: KSerializer<T>): Esito<T> {
            if (codice !in 200..299) return fallimento(codice, corpo)
            return try {
                Esito.Ok(JsonLectio.decodeFromString(serializer, corpo.orEmpty()))
            } catch (e: Exception) {
                Esito.Errore(Testi.RISPOSTA_STRANA, stato = codice)
            }
        }

        /**
         * Un rifiuto del server in una frase da mostrare. Vince il suo `errore` (è già
         * scritto per Andrea), tranne per il 401. Un 502/503/504 senza il JSON di Lectio
         * viene dal tunnel: il server non c'è.
         */
        internal fun fallimento(codice: Int, corpo: String?): Esito<Nothing> {
            if (codice == CODICE_NON_PROTETTO) return Esito.Errore(Testi.INDIRIZZO_NON_PROTETTO, "indirizzo_non_protetto")
            val errore = leggiErroreServer(corpo)
            // 502/503/504 senza il JSON di Lectio vengono dal tunnel: il server non c'è.
            if (errore == null && codice in 502..504) return Esito.Irraggiungibile
            val codiceErrore = if (codice == 401) Testi.CODICE_NON_AUTORIZZATO else errore?.codice ?: codiceDaStato(codice)
            val frase = if (codice == 401) {
                Testi.NON_AUTORIZZATO
            } else {
                errore?.errore ?: when (codiceErrore) {
                    "non_trovato" -> Testi.NON_TROVATO
                    "conflitto" -> Testi.CONFLITTO
                    "non_valido" -> Testi.NON_VALIDO
                    "troppo_grande" -> Testi.TROPPO_GRANDE
                    "troppi_tentativi" -> Testi.TROPPI_TENTATIVI
                    "tetto_raggiunto" -> Testi.TETTO
                    "fornitore_non_disponibile" -> Testi.FORNITORE
                    else -> if (codice >= 500) Testi.PROBLEMA_SERVER else Testi.GENERICO
                }
            }
            return Esito.Errore(frase, codiceErrore, codice)
        }

        /** Rete assente, server spento, tunnel che non lo trova o server di casa non riconosciuto. */
        fun eIrraggiungibile(esito: Esito<*>): Boolean = esito is Esito.Irraggiungibile

        /** Quanto vale una verifica del server di casa in http prima di rifarla. */
        const val VALIDITA_VERIFICA_MS = 30_000L

        private val httpVeloce: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        }

        /** GET /api/salute SENZA token: il `server_id` del server a quell'indirizzo (null se non lo dice). */
        suspend fun serverIdDi(server: String): Esito<String?> {
            val url = (server.trimEnd('/') + "/api/salute").toHttpUrlOrNull() ?: return Esito.Irraggiungibile
            if (!trasportoConsentito(url)) return Esito.Irraggiungibile
            val risposta = esegui(httpVeloce, Request.Builder().url(url).get().build()) ?: return Esito.Irraggiungibile
            if (risposta.codice !in 200..299) return Esito.Irraggiungibile
            return try {
                Esito.Ok(JsonLectio.decodeFromString(Salute.serializer(), risposta.corpo.orEmpty()).serverId)
            } catch (e: Exception) {
                Esito.Ok(null)
            }
        }

        private fun codiceDaStato(codice: Int): String? = when (codice) {
            401 -> "non_autorizzato"
            402 -> "tetto_raggiunto"
            404 -> "non_trovato"
            409 -> "conflitto"
            413 -> "troppo_grande"
            422 -> "non_valido"
            429 -> "troppi_tentativi"
            503 -> "fornitore_non_disponibile"
            else -> null
        }

        /**
         * POST /api/abbina: senza token e senza ritentativi. NonCancellable: il codice
         * vale una volta sola, e un token perso a schermata chiusa lo sprecherebbe.
         */
        suspend fun abbina(server: String, codice: String, nome: String): Esito<Abbinamento> =
            withContext(NonCancellable) {
                val url = (server + "/api/abbina").toHttpUrlOrNull()
                    ?: return@withContext Esito.Errore("Questo indirizzo non sembra giusto.")
                if (!trasportoConsentito(url)) {
                    return@withContext Esito.Errore(Testi.INDIRIZZO_NON_PROTETTO, "indirizzo_non_protetto")
                }
                val corpo = JsonLectio.encodeToString(CorpoAbbina.serializer(), CorpoAbbina(codice, nome))
                val richiesta = Request.Builder().url(url).post(corpo.toRequestBody(JSON)).build()
                val risposta = esegui(httpScritture, richiesta) ?: return@withContext Esito.Irraggiungibile
                when (risposta.codice) {
                    in 200..299 -> interpreta(risposta.codice, risposta.corpo, Abbinamento.serializer())
                    401 -> Esito.Errore(
                        "Il codice è sbagliato o scaduto. Fanne preparare uno nuovo sul server e riprova.",
                        Testi.CODICE_NON_AUTORIZZATO,
                        401,
                    )
                    429 -> Esito.Errore("Troppi codici sbagliati. Aspetta 15 minuti e riprova.", "troppi_tentativi", 429)
                    404, 405 -> Esito.Errore(
                        "A questo indirizzo non risponde Lectio. Controlla l'indirizzo del server.",
                        "non_trovato",
                        risposta.codice,
                    )
                    else -> fallimento(risposta.codice, risposta.corpo)
                }
            }

        /** Il corpo di un PATCH con un solo campo. */
        fun campo(nome: String, valore: String): JsonObject = buildJsonObject { put(nome, valore) }
    }
}
