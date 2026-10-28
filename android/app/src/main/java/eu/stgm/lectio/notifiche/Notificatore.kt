package eu.stgm.lectio.notifiche

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.stgm.lectio.MainActivity
import eu.stgm.lectio.R

/** Le notifiche di Lectio: preparazione in corso, lezione pronta, aggiornamenti. */
object Notificatore {
    const val CANALE_PREPARAZIONE = "preparazione"
    const val CANALE_PRONTE = "lezioni_pronte"
    private const val CANALE_AGGIORNAMENTI = "aggiornamenti"
    const val ID_PREPARAZIONE = 7001
    private const val ID_AGGIORNAMENTO = 7002

    fun creaCanali(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CANALE_PREPARAZIONE, "Lezione in preparazione", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A che punto è la lezione che il server sta preparando"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CANALE_PRONTE, "Lezioni pronte", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Una lezione è pronta da ascoltare, o non è riuscita"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CANALE_AGGIORNAMENTI, "Aggiornamenti dell'app", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Una versione nuova di Lectio pronta da installare"
            },
        )
    }

    fun puoNotificare(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun apriApp(context: Context, codice: Int, lezioneId: Long?, lettore: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                if (lezioneId != null) {
                    putExtra(if (lettore) MainActivity.EXTRA_LEZIONE_LETTORE else MainActivity.EXTRA_LEZIONE_PREPARAZIONE, lezioneId)
                }
            }
        return PendingIntent.getActivity(context, codice, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** La notifica fissa mentre il server prepara: fase e barra (§0.7, niente rotelle infinite). */
    fun preparazione(context: Context, titolo: String, frase: String, frazione: Float?, lezioneId: Long?): Notification =
        NotificationCompat.Builder(context, CANALE_PREPARAZIONE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(titolo)
            .setContentText(frase)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, ((frazione ?: 0f) * 100).toInt(), frazione == null)
            .setContentIntent(apriApp(context, 1, lezioneId, lettore = false))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    /** "La lezione di Storia è pronta": un tocco apre il lettore. */
    fun lezionePronta(context: Context, testo: String, lezioneId: Long) {
        mostra(context, lezioneId.toInt() + 10_000, NotificationCompat.Builder(context, CANALE_PRONTE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(testo)
            .setContentText("Tocca per ascoltarla.")
            .setAutoCancel(true)
            .setContentIntent(apriApp(context, lezioneId.toInt() + 10_000, lezioneId, lettore = true))
            .build())
    }

    /** Una lezione non riuscita: la frase del server, un tocco apre la pagina con Riprova. */
    fun lezioneNonRiuscita(context: Context, testo: String, lezioneId: Long) {
        mostra(context, lezioneId.toInt() + 20_000, NotificationCompat.Builder(context, CANALE_PRONTE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(testo)
            .setContentText("Tocca per vedere cosa fare.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setAutoCancel(true)
            .setContentIntent(apriApp(context, lezioneId.toInt() + 20_000, lezioneId, lettore = false))
            .build())
    }

    /** Le altre novità importanti (tetto vicino, materiale non letto). */
    fun avviso(context: Context, id: Int, testo: String) {
        mostra(context, id, NotificationCompat.Builder(context, CANALE_PRONTE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(testo)
            .setAutoCancel(true)
            .setContentIntent(apriApp(context, id, null, false))
            .build())
    }

    fun togliPreparazione(context: Context) = NotificationManagerCompat.from(context).cancel(ID_PREPARAZIONE)

    /** L'aggiornamento è pronto ma Android vuole la conferma: la notifica la apre. */
    fun mostraAggiornamento(context: Context, versione: String?, conferma: Intent) {
        val tocco = PendingIntent.getActivity(
            context, ID_AGGIORNAMENTO, conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mostra(context, ID_AGGIORNAMENTO, NotificationCompat.Builder(context, CANALE_AGGIORNAMENTI)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("Versione nuova di Lectio" + (versione?.let { " ($it)" } ?: ""))
            .setContentText("Tocca per installarla.")
            .setContentIntent(tocco)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private fun mostra(context: Context, id: Int, notifica: Notification) {
        if (!puoNotificare(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notifica)
        } catch (e: SecurityException) {
            // Permesso tolto nel frattempo: le novità restano visibili aprendo l'app.
        }
    }
}
