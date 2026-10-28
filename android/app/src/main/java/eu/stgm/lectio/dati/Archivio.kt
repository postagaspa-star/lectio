package eu.stgm.lectio.dati

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import eu.stgm.lectio.logica.MemoriaPosizioni
import eu.stgm.lectio.logica.MemoriaRisposte
import eu.stgm.lectio.logica.RispostaInCoda
import eu.stgm.lectio.logica.codificaRisposte
import eu.stgm.lectio.logica.decodificaRisposte
import eu.stgm.lectio.logica.PosizioneLocale
import eu.stgm.lectio.logica.codificaPosizioni
import eu.stgm.lectio.logica.decodificaPosizioni
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.IndirizzoServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dove sta l'abbinamento. Interfaccia: nei test vive in memoria. */
interface DepositoConfigurazione {
    val flusso: Flow<Configurazione?>
    suspend fun leggi(): Configurazione?
    suspend fun salva(configurazione: Configurazione)
    suspend fun dimentica()
}

/** Il tema scelto nelle Impostazioni: sistema segue il telefono. */
enum class TemaScelto { SISTEMA, CHIARO, SCURO }

/** Le scelte di questo telefono. Interfaccia: nei test vive in memoria. */
interface Preferenze {
    val tema: Flow<TemaScelto>
    suspend fun cambiaTema(tema: TemaScelto)

    /** "Meno animazioni" scelto nell'app (oltre a quello del sistema). */
    val menoAnimazioni: Flow<Boolean>
    suspend fun cambiaMenoAnimazioni(si: Boolean)

    /** La visita guidata è già partita una volta (al primo avvio parte da sola, una volta). */
    val visitaVista: Flow<Boolean>
    suspend fun segnaVisitaVista()
}

/** Le novità già viste e le lezioni già avvisate (per non ripetere le notifiche). */
interface MemoriaNovita {
    suspend fun cursore(): Long
    suspend fun ricordaCursore(id: Long)
    suspend fun avvisate(): Set<Long>
    suspend fun segnaAvvisata(lezioneId: Long)
}

private val Context.dataStore by preferencesDataStore(name = "lectio")

/**
 * Le impostazioni del telefono (DataStore). Il token è cifrato con una chiave
 * dell'Android Keystore che non esce mai dal telefono (come Sol).
 */
class Archivio(private val context: Context) : DepositoConfigurazione, Preferenze, MemoriaNovita, MemoriaPosizioni, MemoriaRisposte {

    private object Chiavi {
        val SERVER = stringPreferencesKey("server")
        val TOKEN_CIFRATO = stringPreferencesKey("token_cifrato")
        val DISPOSITIVO_ID = longPreferencesKey("dispositivo_id")
        val SERVER_ID = stringPreferencesKey("server_id")
        val FUORI_CASA = stringPreferencesKey("fuori_casa")
        /** Andrea l'ha scritto lui nelle Impostazioni: il server non lo cambia più. */
        val FUORI_CASA_A_MANO = booleanPreferencesKey("fuori_casa_a_mano")
        val ABBINAMENTO_PERSO = booleanPreferencesKey("abbinamento_perso")
        val TEMA = stringPreferencesKey("tema")
        val MENO_ANIMAZIONI = booleanPreferencesKey("meno_animazioni")
        val VISITA_VISTA = booleanPreferencesKey("visita_vista")
        val RICHIESTA_NOTIFICHE_FATTA = booleanPreferencesKey("richiesta_notifiche_fatta")
        val CURSORE = longPreferencesKey("cursore_novita")
        val AVVISATE = stringSetPreferencesKey("lezioni_avvisate")
        val POSIZIONI = stringPreferencesKey("posizioni_in_sospeso")
        val VELOCITA = floatPreferencesKey("velocita")
        val FERMATE = booleanPreferencesKey("fermati_alle_domande")
        val RISPOSTE_SCRITTE = booleanPreferencesKey("risposte_scritte")
        val RISPOSTE = stringPreferencesKey("risposte_in_coda")
        val AGGIORNAMENTO_FALLITO_CODICE = intPreferencesKey("aggiornamento_fallito_codice")
        val AGGIORNAMENTO_FALLITO_IL = longPreferencesKey("aggiornamento_fallito_il")
    }

    // Decifrare passa dal Keystore, che su certi telefoni è lento: fuori dal thread principale.
    override val flusso: Flow<Configurazione?> = context.dataStore.data.map { p ->
        val server = p[Chiavi.SERVER] ?: return@map null
        val token = p[Chiavi.TOKEN_CIFRATO]?.let(Cassaforte::decifra) ?: return@map null
        Configurazione(server, token, p[Chiavi.DISPOSITIVO_ID], p[Chiavi.SERVER_ID], p[Chiavi.FUORI_CASA])
    }.flowOn(Dispatchers.Default)

    override suspend fun leggi(): Configurazione? = flusso.first()

    override suspend fun salva(configurazione: Configurazione) {
        val cifrato = withContext(Dispatchers.Default) { Cassaforte.cifra(configurazione.token) }
        context.dataStore.edit { p ->
            val altroServer = deveSvuotare(p[Chiavi.SERVER], p[Chiavi.SERVER_ID], configurazione.server, configurazione.serverId)
            // Con un altro server l'indirizzo scritto a mano non vale più.
            if (altroServer) p.remove(Chiavi.FUORI_CASA_A_MANO)
            val fuori = fuoriCasaScelto(p[Chiavi.FUORI_CASA], p[Chiavi.FUORI_CASA_A_MANO] ?: false, configurazione.fuoriCasa)
            if (altroServer) {
                p.remove(Chiavi.CURSORE)
                p.remove(Chiavi.AVVISATE)
                p.remove(Chiavi.POSIZIONI)
                p.remove(Chiavi.RISPOSTE)
            }
            p[Chiavi.SERVER] = configurazione.server
            p[Chiavi.TOKEN_CIFRATO] = cifrato
            val id = configurazione.dispositivoId
            if (id != null) p[Chiavi.DISPOSITIVO_ID] = id else p.remove(Chiavi.DISPOSITIVO_ID)
            val sid = configurazione.serverId
            if (sid != null) p[Chiavi.SERVER_ID] = sid else p.remove(Chiavi.SERVER_ID)
            if (fuori != null) p[Chiavi.FUORI_CASA] = fuori else p.remove(Chiavi.FUORI_CASA)
            p.remove(Chiavi.ABBINAMENTO_PERSO)
        }
    }

    override suspend fun dimentica() = dimentica(perso = false)

    /**
     * [perso]: è stato il server a non riconoscere più il telefono (due 401 di fila).
     * Allora si toglie solo il token e si chiede di ricollegare: lezioni scaricate,
     * posizioni e risposte in coda restano (con lo stesso server tornano utili).
     * Senza [perso] (Scollega scelto da Andrea) va via tutto.
     */
    suspend fun dimentica(perso: Boolean) {
        context.dataStore.edit { p ->
            p.remove(Chiavi.TOKEN_CIFRATO)
            p.remove(Chiavi.DISPOSITIVO_ID)
            if (perso) {
                p[Chiavi.ABBINAMENTO_PERSO] = true
            } else {
                p.remove(Chiavi.CURSORE)
                p.remove(Chiavi.AVVISATE)
                p.remove(Chiavi.POSIZIONI)
                p.remove(Chiavi.RISPOSTE)
                p.remove(Chiavi.ABBINAMENTO_PERSO)
            }
        }
    }

    /** L'identità del server dell'ultimo abbinamento (per capire se ci si ricollega allo stesso). */
    suspend fun ultimoServerId(): String? = context.dataStore.data.first()[Chiavi.SERVER_ID]

    /** "Indirizzo da fuori casa" (Impostazioni): l'https di Tailscale. */
    val fuoriCasa: Flow<String?> = context.dataStore.data.map { p -> p[Chiavi.FUORI_CASA] }

    /**
     * Scritto da Andrea nelle Impostazioni: da qui in poi vince sul server. Vuoto = si
     * torna a quello del server (arriva con la prossima lettura della salute).
     */
    suspend fun cambiaFuoriCasa(indirizzo: String?) {
        context.dataStore.edit { p ->
            if (indirizzo.isNullOrBlank()) {
                p.remove(Chiavi.FUORI_CASA)
                p.remove(Chiavi.FUORI_CASA_A_MANO)
            } else {
                p[Chiavi.FUORI_CASA] = indirizzo
                p[Chiavi.FUORI_CASA_A_MANO] = true
            }
        }
    }

    /** Quello che manda il server (salute con token): si adotta se Andrea non ne ha scritto uno. */
    suspend fun adottaFuoriCasa(dalServer: String?) {
        context.dataStore.edit { p ->
            val fuori = fuoriCasaScelto(p[Chiavi.FUORI_CASA], p[Chiavi.FUORI_CASA_A_MANO] ?: false, dalServer)
            if (fuori != null) p[Chiavi.FUORI_CASA] = fuori else p.remove(Chiavi.FUORI_CASA)
        }
    }

    val abbinamentoPerso: Flow<Boolean> = context.dataStore.data.map { p -> p[Chiavi.ABBINAMENTO_PERSO] ?: false }

    suspend fun ultimoServer(): String? = context.dataStore.data.first()[Chiavi.SERVER]

    override val tema: Flow<TemaScelto> = context.dataStore.data.map { p ->
        TemaScelto.entries.firstOrNull { it.name == p[Chiavi.TEMA] } ?: TemaScelto.SISTEMA
    }

    override suspend fun cambiaTema(tema: TemaScelto) {
        context.dataStore.edit { p -> p[Chiavi.TEMA] = tema.name }
    }

    override val menoAnimazioni: Flow<Boolean> = context.dataStore.data.map { p -> p[Chiavi.MENO_ANIMAZIONI] ?: false }

    override suspend fun cambiaMenoAnimazioni(si: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.MENO_ANIMAZIONI] = si }
    }

    override val visitaVista: Flow<Boolean> = context.dataStore.data.map { p -> p[Chiavi.VISITA_VISTA] ?: false }

    override suspend fun segnaVisitaVista() {
        context.dataStore.edit { p -> p[Chiavi.VISITA_VISTA] = true }
    }

    val richiestaNotificheFatta: Flow<Boolean> = context.dataStore.data.map { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] ?: false }

    suspend fun registraRichiestaNotificheFatta() {
        context.dataStore.edit { p -> p[Chiavi.RICHIESTA_NOTIFICHE_FATTA] = true }
    }

    override suspend fun cursore(): Long = context.dataStore.data.first()[Chiavi.CURSORE] ?: 0L

    override suspend fun ricordaCursore(id: Long) {
        context.dataStore.edit { p -> if (id > (p[Chiavi.CURSORE] ?: 0L)) p[Chiavi.CURSORE] = id }
    }

    override suspend fun avvisate(): Set<Long> =
        context.dataStore.data.first()[Chiavi.AVVISATE].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    override suspend fun segnaAvvisata(lezioneId: Long) {
        context.dataStore.edit { p ->
            // Le ultime 100 bastano: le lezioni vecchie non tornano in preparazione.
            val insieme = (p[Chiavi.AVVISATE].orEmpty() + lezioneId.toString()).toList().takeLast(100).toSet()
            p[Chiavi.AVVISATE] = insieme
        }
    }

    override suspend fun leggiPosizioni(): List<PosizioneLocale> = decodificaPosizioni(context.dataStore.data.first()[Chiavi.POSIZIONI])

    override suspend fun scriviPosizioni(posizioni: List<PosizioneLocale>) {
        context.dataStore.edit { p ->
            if (posizioni.isEmpty()) p.remove(Chiavi.POSIZIONI) else p[Chiavi.POSIZIONI] = codificaPosizioni(posizioni)
        }
    }

    /** La velocità scelta l'ultima volta su questo telefono (null = quella delle impostazioni). */
    suspend fun velocita(): Float? = context.dataStore.data.first()[Chiavi.VELOCITA]

    suspend fun salvaVelocita(v: Float) {
        context.dataStore.edit { p -> p[Chiavi.VELOCITA] = v }
    }

    /** Copia locale di "Fermati alle domande", per ascoltare senza rete. */
    suspend fun fermateAttive(): Boolean = context.dataStore.data.first()[Chiavi.FERMATE] ?: true

    suspend fun salvaFermate(si: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.FERMATE] = si }
    }

    override suspend fun leggiRisposte(): List<RispostaInCoda> = decodificaRisposte(context.dataStore.data.first()[Chiavi.RISPOSTE])

    override suspend fun scriviRisposte(risposte: List<RispostaInCoda>) {
        context.dataStore.edit { p ->
            if (risposte.isEmpty()) p.remove(Chiavi.RISPOSTE) else p[Chiavi.RISPOSTE] = codificaRisposte(risposte)
        }
    }

    /** Copia locale di "Risposte scritte" (§15.2), per ascoltare senza rete. */
    suspend fun risposteScritte(): Boolean = context.dataStore.data.first()[Chiavi.RISPOSTE_SCRITTE] ?: true

    suspend fun salvaRisposteScritte(si: Boolean) {
        context.dataStore.edit { p -> p[Chiavi.RISPOSTE_SCRITTE] = si }
    }

    suspend fun aggiornamentoRimandato(): Pair<Int, Long>? {
        val p = context.dataStore.data.first()
        val codice = p[Chiavi.AGGIORNAMENTO_FALLITO_CODICE] ?: return null
        return codice to (p[Chiavi.AGGIORNAMENTO_FALLITO_IL] ?: 0L)
    }

    suspend fun registraAggiornamentoRimandato(codice: Int, quando: Long) {
        context.dataStore.edit { p ->
            p[Chiavi.AGGIORNAMENTO_FALLITO_CODICE] = codice
            p[Chiavi.AGGIORNAMENTO_FALLITO_IL] = quando
        }
    }
}

/**
 * L'indirizzo da fuori casa da tenere: quello scritto a mano da Andrea vince sempre;
 * altrimenti vale quello del server (solo https), e se il server non ne ha uno non c'è.
 */
fun fuoriCasaScelto(attuale: String?, aMano: Boolean, dalServer: String?): String? =
    if (aMano && attuale != null) attuale else IndirizzoServer.fuoriCasaDalServer(dalServer)

/**
 * Un abbinamento nuovo: i dati del telefono (lezioni scaricate, posizioni e risposte
 * in coda, novità viste) vanno via solo se è un altro server. Con l'identità del
 * server (`server_id`) si confronta quella; senza, l'indirizzo.
 */
fun deveSvuotare(vecchioServer: String?, vecchioId: String?, nuovoServer: String, nuovoId: String?): Boolean {
    if (vecchioServer == null && vecchioId == null) return false
    if (vecchioId != null && nuovoId != null) return vecchioId != nuovoId
    return vecchioServer?.trimEnd('/') != nuovoServer.trimEnd('/')
}

/** Le preferenze in memoria: per i test e per il fotografo. */
class PreferenzeInMemoria(tema: TemaScelto = TemaScelto.SISTEMA, visitaVista: Boolean = true) : Preferenze {
    private val temaFlusso = MutableStateFlow(tema)
    private val meno = MutableStateFlow(false)
    private val vista = MutableStateFlow(visitaVista)
    override val tema: Flow<TemaScelto> = temaFlusso
    override suspend fun cambiaTema(tema: TemaScelto) {
        temaFlusso.value = tema
    }
    override val menoAnimazioni: Flow<Boolean> = meno
    override suspend fun cambiaMenoAnimazioni(si: Boolean) {
        meno.value = si
    }
    override val visitaVista: Flow<Boolean> = vista
    override suspend fun segnaVisitaVista() {
        vista.value = true
    }
}

/** AES-GCM con la chiave nell'Android Keystore. */
private object Cassaforte {
    private const val ALIAS = "lectio_token"
    private const val TRASFORMAZIONE = "AES/GCM/NoPadding"

    private fun chiave(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generatore = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generatore.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generatore.generateKey()
    }

    fun cifra(testo: String): String {
        val cifrario = Cipher.getInstance(TRASFORMAZIONE).apply { init(Cipher.ENCRYPT_MODE, chiave()) }
        val dati = cifrario.doFinal(testo.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cifrario.iv + dati, Base64.NO_WRAP)
    }

    /** null se non si decifra (chiave persa dopo un ripristino): si rifà l'abbinamento. */
    fun decifra(grezzo: String): String? = try {
        val tutto = Base64.decode(grezzo, Base64.NO_WRAP)
        val iv = tutto.copyOfRange(0, 12)
        val dati = tutto.copyOfRange(12, tutto.size)
        val cifrario = Cipher.getInstance(TRASFORMAZIONE).apply {
            init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, iv))
        }
        String(cifrario.doFinal(dati), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
