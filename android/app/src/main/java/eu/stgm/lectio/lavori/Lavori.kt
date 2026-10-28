package eu.stgm.lectio.lavori

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.stgm.lectio.LectioApp
import eu.stgm.lectio.aggiornamento.Aggiornatore
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MemoriaNovita
import eu.stgm.lectio.dati.Novita
import eu.stgm.lectio.logica.Preparazione
import eu.stgm.lectio.notifiche.Notificatore
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.LectioClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/** Chi riceve gli avvisi del giro (nei test: una lista). */
interface Avvisi {
    fun avanzamento(titolo: String, frase: String, frazione: Float?, lezioneId: Long)
    fun pronta(testo: String, lezioneId: Long)
    fun nonRiuscita(testo: String, lezioneId: Long)
    fun altro(id: Long, testo: String)
}

enum class Giro { IN_CORSO, FINITO, SENZA_RETE }

/**
 * Un giro del controllo durante la preparazione (contratto §11: ogni 20 s mentre
 * una lezione è in preparazione). Legge le lezioni (fase e numero per la notifica)
 * e le novità; "La lezione di Storia è pronta" si dice una volta sola.
 */
class GiroPreparazione(
    private val lezioni: suspend () -> Esito<List<Lezione>>,
    private val novita: suspend (Long) -> Esito<List<Novita>>,
    private val nomeMateria: suspend (Long) -> String?,
    private val memoria: MemoriaNovita,
    private val avvisi: Avvisi,
) {
    /** Le lezioni viste in preparazione: se finiscono senza novità, l'avviso lo fa l'app. */
    private val seguite = mutableSetOf<Long>()

    suspend fun giro(): Giro {
        val elenco = when (val e = lezioni()) {
            is Esito.Ok -> e.dato
            is Esito.Errore -> if (LectioClient.eIrraggiungibile(e)) return Giro.SENZA_RETE else emptyList()
            Esito.Irraggiungibile -> return Giro.SENZA_RETE
        }
        val inLavoro = elenco.filter { it.inLavoro }
        seguite += inLavoro.map { it.id }
        val prima = inLavoro.firstOrNull { it.stato == "in_preparazione" } ?: inLavoro.firstOrNull()
        if (prima != null) {
            val materia = nomeMateria(prima.materiaId)
            val titolo = if (materia != null) "Preparo la lezione di $materia" else "Preparo la lezione"
            avvisi.avanzamento(titolo, Preparazione.frase(prima), Preparazione.frazione(prima), prima.id)
        }

        val avvisate = memoria.avvisate().toMutableSet()
        val cursore = memoria.cursore()
        val lette = (novita(cursore) as? Esito.Ok)?.dato.orEmpty().sortedBy { it.id }
        // Primo giro dopo l'abbinamento (cursore 0): le novità vecchie non si notificano
        // (né si scaricano le loro lezioni): si fissa solo il cursore all'id più alto.
        val nuove = if (cursore == 0L) emptyList() else lette
        for (n in nuove) {
            val id = n.lezioneId
            when (n.tipo) {
                "lezione_pronta" -> if (id != null && id !in avvisate) {
                    avvisi.pronta(n.testo, id)
                    memoria.segnaAvvisata(id)
                    avvisate += id
                }
                "lezione_errore" -> if (id != null && id !in avvisate) {
                    avvisi.nonRiuscita(n.testo, id)
                    memoria.segnaAvvisata(id)
                    avvisate += id
                }
                "tetto_vicino", "materiale_errore" -> avvisi.altro(n.id, n.testo)
            }
        }
        lette.maxOfOrNull { it.id }?.let { memoria.ricordaCursore(it) }

        // Finita senza novità (server vecchio o novità perse): l'avviso lo fa l'app.
        for (l in elenco) {
            if (l.id !in seguite || l.id in avvisate || l.inLavoro) continue
            val materia = nomeMateria(l.materiaId)
            if (l.pronta) {
                avvisi.pronta(if (materia != null) "La lezione di $materia è pronta" else "La lezione è pronta", l.id)
                memoria.segnaAvvisata(l.id)
            } else if (l.fallita) {
                avvisi.nonRiuscita(l.errore ?: "La lezione non è riuscita", l.id)
                memoria.segnaAvvisata(l.id)
            }
        }
        return if (inLavoro.isEmpty()) Giro.FINITO else Giro.IN_CORSO
    }
}

/**
 * Le novità all'apertura dell'app (contratto §11: "altrimenti all'apertura"). Torna
 * quelle da dire una volta ad Andrea (lezione pronta o non riuscita non ancora
 * avvisata, tetto vicino, materiale non letto, scheda aggiornata) e sposta il cursore.
 * Con cursore 0 (primo avvio) fissa solo il cursore: niente valanga di novità vecchie.
 */
suspend fun novitaDaMostrare(novita: suspend (Long) -> Esito<List<Novita>>, memoria: MemoriaNovita): List<Novita> {
    val cursore = memoria.cursore()
    val lette = (novita(cursore) as? Esito.Ok)?.dato.orEmpty().sortedBy { it.id }
    lette.maxOfOrNull { it.id }?.let { memoria.ricordaCursore(it) }
    if (cursore == 0L) return emptyList()
    val avvisate = memoria.avvisate()
    val da = lette.filter { n ->
        when (n.tipo) {
            "lezione_pronta", "lezione_errore" -> n.lezioneId != null && n.lezioneId !in avvisate
            "tetto_vicino", "materiale_errore", "scheda_aggiornata", "materiale_letto" -> true
            else -> false
        }
    }
    da.mapNotNull { it.lezioneId }.forEach { memoria.segnaAvvisata(it) }
    return da
}

/**
 * Mentre il server prepara una lezione: notifica fissa con fase e barra, giro ogni
 * 20 s, notifica "pronta" alla fine. WorkManager con servizio in primo piano
 * (dataSync): continua ad app chiusa, riparte se Android chiude il processo e
 * aspetta la rete da solo.
 */
class PreparazioneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LectioApp
        if (app.archivio.leggi() == null) return Result.success()
        primoPiano(Notificatore.preparazione(app, "Preparo la lezione", "Controllo a che punto è", null, null))
        val avvisi = object : Avvisi {
            override fun avanzamento(titolo: String, frase: String, frazione: Float?, lezioneId: Long) {
                val n = Notificatore.preparazione(app, titolo, frase, frazione, lezioneId)
                try {
                    if (Notificatore.puoNotificare(app)) NotificationManagerCompat.from(app).notify(Notificatore.ID_PREPARAZIONE, n)
                } catch (e: SecurityException) {
                    // Senza permesso la notifica non si vede: l'avanzamento resta nell'app.
                }
            }
            override fun pronta(testo: String, lezioneId: Long) {
                Notificatore.lezionePronta(app, testo, lezioneId)
                scaricaSeWifi(app, lezioneId)
            }
            override fun nonRiuscita(testo: String, lezioneId: Long) = Notificatore.lezioneNonRiuscita(app, testo, lezioneId)
            override fun altro(id: Long, testo: String) = Notificatore.avviso(app, (id % 1000).toInt() + 30_000, testo)
        }
        val giro = GiroPreparazione(
            lezioni = { app.client.lezioni() },
            novita = { app.client.novita(it) },
            nomeMateria = { id -> app.sorgente.materie().dato?.firstOrNull { it.id == id }?.nome },
            memoria = app.archivio,
            avvisi = avvisi,
        )
        var senzaRete = 0
        try {
            repeat(MASSIMO_GIRI) {
                when (giro.giro()) {
                    Giro.FINITO -> return Result.success()
                    Giro.SENZA_RETE -> if (++senzaRete >= 15) return Result.retry()
                    Giro.IN_CORSO -> senzaRete = 0
                }
                delay(OGNI_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            Notificatore.togliPreparazione(app)
        }
        return Result.success()
    }

    private suspend fun primoPiano(notifica: android.app.Notification) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(Notificatore.ID_PREPARAZIONE, notifica, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(Notificatore.ID_PREPARAZIONE, notifica)
            }
            setForeground(info)
        } catch (e: IllegalStateException) {
            // Android non lascia partire il primo piano da dietro le quinte: si continua
            // come lavoro normale (al peggio WorkManager lo riprende più tardi).
        }
    }

    companion object {
        private const val NOME = "preparazione"
        const val OGNI_MS = 20_000L
        private const val MASSIMO_GIRI = 360 // due ore

        fun pianifica(context: Context) {
            val richiesta = OneTimeWorkRequestBuilder<PreparazioneWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOME, ExistingWorkPolicy.KEEP, richiesta)
        }
    }
}

/** Una lezione pronta si scarica da sola se il telefono è su una rete senza consumo (Wi-Fi di casa). */
fun scaricaSeWifi(context: Context, lezioneId: Long) {
    val richiesta = OneTimeWorkRequestBuilder<ScaricaWorker>()
        .setInputData(androidx.work.workDataOf(ScaricaWorker.LEZIONE to lezioneId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("scarica-$lezioneId", ExistingWorkPolicy.KEEP, richiesta)
}

/** Scarica l'audio di una lezione (e il suo dettaglio) nella memoria privata dell'app. */
class ScaricaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectioApp
        val id = inputData.getLong(LEZIONE, -1)
        if (id < 0 || app.audio.scaricata(id)) return Result.success()
        return when (scaricaLezione(app, id)) {
            is Esito.Ok -> Result.success()
            Esito.Irraggiungibile -> Result.retry()
            is Esito.Errore -> Result.failure()
        }
    }

    companion object {
        const val LEZIONE = "lezione"
    }
}

/** Scarica audio e dettaglio di una lezione; l'avanzamento si vede in [eu.stgm.lectio.dati.ArchivioAudio.inScaricamento]. */
suspend fun scaricaLezione(app: LectioApp, id: Long): Esito<Unit> {
    val dettaglio = when (val l = app.client.lezione(id)) {
        is Esito.Ok -> l.dato
        is Esito.Errore -> return l
        Esito.Irraggiungibile -> return Esito.Irraggiungibile
    }
    if (!dettaglio.pronta) return Esito.Errore("La lezione non è ancora pronta.")
    app.audio.inScaricamento.value = app.audio.inScaricamento.value + (id to 0f)
    val esito = app.client.scaricaAudio(id, app.audio.fileAudio(id), dettaglio.audio?.sha256) { f ->
        app.audio.inScaricamento.value = app.audio.inScaricamento.value + (id to f)
    }
    app.audio.inScaricamento.value = app.audio.inScaricamento.value - id
    if (esito is Esito.Ok) {
        app.audio.salvaDettaglio(dettaglio)
        app.audio.segnaScaricata()
    }
    return esito
}

/** Posizioni e risposte rimaste sul telefono: partono appena c'è rete. */
class InviiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectioApp
        if (app.archivio.leggi() == null) return Result.success()
        val posizioni = app.posizioni.inviaInSospeso()
        val risposte = app.risposte.inviaInCoda()
        return if (posizioni && risposte) Result.success() else Result.retry()
    }

    companion object {
        fun pianifica(context: Context) {
            val richiesta = OneTimeWorkRequestBuilder<InviiWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: se un invio è in corso il nuovo parte dopo (mai due insieme,
            // mai uno cancellato a metà); se il precedente è fallito lo sostituisce.
            WorkManager.getInstance(context).enqueueUniqueWork("invii", ExistingWorkPolicy.APPEND_OR_REPLACE, richiesta)
        }
    }
}

/** Due volte al giorno: posizioni e risposte rimaste, e se c'è una versione nuova dell'app. */
class ControlloWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LectioApp
        if (app.archivio.leggi() == null) return Result.success()
        app.posizioni.inviaInSospeso()
        app.risposte.inviaInCoda()
        try {
            Aggiornatore(app).controlla(inSottofondo = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Il prossimo giro ci riprova: un aggiornamento non deve mai fermare il resto.
        }
        return Result.success()
    }

    companion object {
        fun pianifica(context: Context) {
            val richiesta = PeriodicWorkRequestBuilder<ControlloWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("controllo", ExistingPeriodicWorkPolicy.UPDATE, richiesta)
        }
    }
}

/** Dopo il riavvio del telefono o un aggiornamento: i controlli ripartono. */
class AvvioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ControlloWorker.pianifica(context)
            InviiWorker.pianifica(context)
        }
    }
}
