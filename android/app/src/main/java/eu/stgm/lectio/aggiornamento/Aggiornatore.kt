package eu.stgm.lectio.aggiornamento

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import eu.stgm.lectio.BuildConfig
import eu.stgm.lectio.LectioApp
import eu.stgm.lectio.dati.VersioneApp
import eu.stgm.lectio.lettore.CentroAscolto
import eu.stgm.lectio.notifiche.Notificatore
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.LectioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface EsitoAggiornamento {
    data object GiaAggiornato : EsitoAggiornamento
    data class Avviato(val versione: String) : EsitoAggiornamento
    data class Rimandato(val versione: String) : EsitoAggiornamento
    data class NonRiuscito(val messaggio: String) : EsitoAggiornamento
}

fun EsitoAggiornamento.testo(): String = when (this) {
    EsitoAggiornamento.GiaAggiornato -> "Hai già l'ultima versione."
    is EsitoAggiornamento.Avviato -> "Sto installando la versione $versione. Se Android lo chiede, conferma."
    is EsitoAggiornamento.Rimandato -> "La versione $versione è pronta: riprovo più tardi."
    is EsitoAggiornamento.NonRiuscito -> messaggio
}

/** L'impronta SHA-256 di un file, in esadecimale minuscolo. */
fun impronta(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ingresso ->
        val pezzo = ByteArray(64 * 1024)
        while (true) {
            val n = ingresso.read(pezzo)
            if (n < 0) break
            digest.update(pezzo, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Il file è proprio quello pubblicato dal server: stessa impronta (e stessa dimensione, se la dice). */
fun coincide(file: File, versione: VersioneApp): Boolean {
    val sha = versione.sha256?.trim()?.lowercase()?.takeIf { it.length == 64 } ?: return false
    if (!file.isFile) return false
    if (versione.byte != null && file.length() != versione.byte) return false
    return impronta(file) == sha
}

/**
 * La parte senza Android dell'aggiornamento: chiede la versione, scarica l'APK e
 * lo controlla. Un file che non coincide non arriva mai a PackageInstaller.
 */
class PreparatoreAggiornamento(
    private val client: LectioClient,
    private val cartella: File,
    private val codiceAttuale: Int,
) {
    sealed interface Preparazione {
        data class Pronto(val file: File, val versione: VersioneApp) : Preparazione
        data class NonRiuscito(val messaggio: String) : Preparazione
    }

    suspend fun versioneNuova(): Pair<VersioneApp?, String?> = when (val e = client.versione()) {
        is Esito.Ok -> e.dato.android?.takeIf { it.codice > codiceAttuale } to null
        is Esito.Errore -> null to e.messaggio
        Esito.Irraggiungibile -> null to "Non riesco a controllare: il server di casa non risponde."
    }

    suspend fun prepara(versione: VersioneApp): Preparazione {
        if (versione.sha256.isNullOrBlank()) {
            return Preparazione.NonRiuscito("Il server non indica l'impronta dell'aggiornamento: per sicurezza non lo installo.")
        }
        return withContext(Dispatchers.IO) {
            cartella.mkdirs()
            val file = File(cartella, "lectio-${versione.codice}.apk")
            if (coincide(file, versione)) return@withContext Preparazione.Pronto(file, versione)
            cartella.listFiles()?.forEach { it.delete() }
            val temporaneo = File(cartella, "scaricamento.tmp")
            if (!client.scaricaApk(temporaneo)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito("Non sono riuscito a scaricare l'aggiornamento. Riprovo più tardi.")
            }
            if (!coincide(temporaneo, versione)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito("Il file scaricato non coincide con quello del server: l'ho scartato. Riprovo più tardi.")
            }
            if (!temporaneo.renameTo(file)) {
                temporaneo.delete()
                return@withContext Preparazione.NonRiuscito("Non c'è spazio per l'aggiornamento.")
            }
            Preparazione.Pronto(file, versione)
        }
    }
}

/**
 * Aggiornamento da sé (come Sol): se `android.codice` è più alto del nostro scarica
 * /scarica/lectio.apk, ne controlla l'impronta e lo passa a PackageInstaller con
 * UPDATE_PACKAGES_WITHOUT_USER_ACTION; se Android vuole la conferma, una notifica.
 */
class Aggiornatore(private val context: Context) {

    suspend fun controlla(forza: Boolean = false, inSottofondo: Boolean = false): EsitoAggiornamento {
        val app = context.applicationContext as LectioApp
        val preparatore = PreparatoreAggiornamento(app.client, File(context.cacheDir, "aggiornamenti"), BuildConfig.VERSION_CODE)
        val (ultima, problema) = preparatore.versioneNuova()
        if (problema != null) return EsitoAggiornamento.NonRiuscito(problema)
        if (ultima == null) return EsitoAggiornamento.GiaAggiornato
        if (inSottofondo && !forza) {
            val rimandato = app.archivio.aggiornamentoRimandato()
            if (rimandato != null && rimandato.first == ultima.codice &&
                System.currentTimeMillis() - rimandato.second < TimeUnit.HOURS.toMillis(24)
            ) {
                return EsitoAggiornamento.Rimandato(ultima.nome)
            }
            // L'installazione chiude l'app: mentre la usi o ascolti si scarica soltanto, e si installa
            // quando la lasci (AggiornamentoWorker parte da MainActivity.onStop).
            if (!MomentoAggiornamento.libero(AppInPrimoPiano.visibile, CentroAscolto.stato.value.suona)) {
                preparatore.prepara(ultima)
                return EsitoAggiornamento.Rimandato(ultima.nome)
            }
        }
        return when (val pronto = preparatore.prepara(ultima)) {
            is PreparatoreAggiornamento.Preparazione.Pronto -> {
                if (inSottofondo) app.archivio.registraAggiornamentoRimandato(ultima.codice, System.currentTimeMillis())
                if (installa(pronto.file, ultima)) {
                    EsitoAggiornamento.Avviato(ultima.nome)
                } else {
                    EsitoAggiornamento.NonRiuscito("Android non ha accettato l'aggiornamento. Riprova più tardi.")
                }
            }
            is PreparatoreAggiornamento.Preparazione.NonRiuscito -> EsitoAggiornamento.NonRiuscito(pronto.messaggio)
        }
    }

    /** Solo per le Impostazioni: c'è una versione nuova? (senza scaricarla). */
    suspend fun versioneDisponibile(): VersioneApp? {
        val app = context.applicationContext as LectioApp
        return PreparatoreAggiornamento(app.client, File(context.cacheDir, "aggiornamenti"), BuildConfig.VERSION_CODE).versioneNuova().first
    }

    private suspend fun installa(apk: File, versione: VersioneApp): Boolean = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val parametri = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        var sessionId = -1
        try {
            sessionId = installer.createSession(parametri)
            installer.openSession(sessionId).use { sessione ->
                apk.inputStream().use { ingresso ->
                    sessione.openWrite("lectio.apk", 0, apk.length()).use { uscita ->
                        ingresso.copyTo(uscita)
                        sessione.fsync(uscita)
                    }
                }
                sessione.commit(intentEsito(sessionId, versione).intentSender)
            }
            true
        } catch (e: IOException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        } catch (e: SecurityException) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    private fun intentEsito(sessionId: Int, versione: VersioneApp): PendingIntent {
        val intent = Intent(context, AggiornamentoReceiver::class.java)
            .setAction(AggiornamentoReceiver.AZIONE)
            .setPackage(context.packageName)
            .putExtra(AggiornamentoReceiver.EXTRA_CODICE, versione.codice)
            .putExtra(AggiornamentoReceiver.EXTRA_NOME, versione.nome)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flag)
    }
}

/** Lectio è sullo schermo? Da dietro le quinte Android non lascia aprire finestre. */
object AppInPrimoPiano {
    @Volatile
    var visibile: Boolean = false
}

/** Quando un aggiornamento trovato in sottofondo si può installare senza disturbare. */
object MomentoAggiornamento {
    /** Mai con Lectio sullo schermo né con una lezione che suona: l'installazione chiuderebbe tutto. */
    fun libero(visibile: Boolean, suona: Boolean): Boolean = !visibile && !suona
}

/** L'esito di PackageInstaller: se Android vuole la conferma, finestra subito o notifica. */
class AggiornamentoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stato = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val codice = intent.getIntExtra(EXTRA_CODICE, 0)
        val nome = intent.getStringExtra(EXTRA_NOME)
        when (stato) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val conferma = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (AppInPrimoPiano.visibile) {
                    conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(conferma)
                } else {
                    Notificatore.mostraAggiornamento(context, nome, conferma)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val app = context.applicationContext as? LectioApp ?: return
                val attesa = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        if (codice > 0) app.archivio.registraAggiornamentoRimandato(codice, System.currentTimeMillis())
                    } finally {
                        attesa.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val AZIONE = "eu.stgm.lectio.ESITO_INSTALLAZIONE"
        const val EXTRA_CODICE = "codice"
        const val EXTRA_NOME = "nome"
    }
}
