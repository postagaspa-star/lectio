package eu.stgm.lectio.lettore

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import eu.stgm.lectio.dati.ArchivioAudio
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MateriaBreve
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cosa fare del lettore prima di togliere dal telefono o eliminare una lezione (punto 11). */
enum class AzioneSulLettore { NIENTE, STREAMING, FERMA }

/**
 * La lezione [id] sta per sparire dal telefono ([elimina] = anche dal server). Se è
 * quella che suona dal file: eliminata → si ferma; tolta dal telefono → continua dal
 * server. Le altre non c'entrano.
 */
fun azioneSuLezioneInAscolto(inAscolto: Long?, id: Long, elimina: Boolean, dalFile: Boolean): AzioneSulLettore = when {
    inAscolto != id -> AzioneSulLettore.NIENTE
    elimina -> AzioneSulLettore.FERMA
    dalFile -> AzioneSulLettore.STREAMING
    else -> AzioneSulLettore.NIENTE
}

/**
 * Il lettore vero visto dalle schermate. Il MediaController tiene acceso e legato
 * il servizio; i gesti vanno al servizio nello stesso processo ([CentroAscolto]).
 * Se la lezione è scaricata suona dal file sul telefono, altrimenti dal server
 * riconosciuto ([indirizzoServer]: https, o http col `server_id` giusto).
 */
class LettoreMedia(
    private val context: Context,
    private val audio: ArchivioAudio,
    private val indirizzoServer: suspend () -> String?,
    /** La velocità scelta resta su questo telefono (contratto §13: tema e velocità in locale). */
    private val salvaVelocita: (Float) -> Unit = {},
) : Lettore {

    override val stato: StateFlow<StatoLettore> = CentroAscolto.stato

    private var futuro: ListenableFuture<MediaController>? = null
    private val ambito = MainScope()

    /** Lega il servizio (lo crea se serve) e poi fa [gesto]. */
    private fun conServizio(gesto: (ServizioLettore) -> Unit) {
        CentroAscolto.servizio?.let {
            gesto(it)
            return
        }
        futuro?.let { vecchio -> if (vecchio.isDone) runCatching { vecchio.get().release() } }
        val nuovo = MediaController.Builder(context, SessionToken(context, ComponentName(context, ServizioLettore::class.java))).buildAsync()
        futuro = nuovo
        nuovo.addListener({ CentroAscolto.servizio?.let(gesto) }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun dalServer(id: Long): Uri? = indirizzoServer()?.let { Uri.parse("$it/api/lezioni/$id/audio") }

    /** Il file sul telefono se è scaricata, altrimenti il server (null = non si può ascoltare ora). */
    suspend fun indirizzo(id: Long): Uri? {
        val file = audio.fileAudio(id)
        if (file.isFile) return Uri.fromFile(file)
        return dalServer(id)
    }

    override fun carica(lezione: Lezione, materia: MateriaBreve?, daSecondi: Double, avvia: Boolean, allaDomanda: Long?) {
        ambito.launch {
            val uri = indirizzo(lezione.id)
            if (uri == null) {
                CentroAscolto._stato.update {
                    it.copy(errore = "Non riesco a raggiungere il server di casa: senza rete si ascoltano solo le lezioni scaricate.")
                }
                return@launch
            }
            conServizio { it.carica(lezione, materia, uri, daSecondi, avvia, allaDomanda) }
        }
    }

    /** Prima di togliere dal telefono o eliminare la lezione [id]: il lettore non deve restare su un file che sparisce. */
    override suspend fun liberaFile(lezioneId: Long, elimina: Boolean) {
        val s = stato.value
        val dalFile = audio.scaricata(lezioneId)
        when (azioneSuLezioneInAscolto(s.lezione?.id, lezioneId, elimina, dalFile)) {
            AzioneSulLettore.NIENTE -> Unit
            AzioneSulLettore.FERMA -> ferma()
            AzioneSulLettore.STREAMING -> {
                val l = s.lezione ?: return
                // Senza server riconosciuto non si può continuare in streaming: si ferma.
                val uri = dalServer(lezioneId) ?: return ferma()
                conServizio { it.carica(l, s.materia, uri, s.posizioneS, s.suona, null, forza = true) }
            }
        }
    }

    override fun playPausa() = conServizio { it.playPausa() }
    override fun vaiA(secondi: Double) = conServizio { it.vaiA(secondi) }
    override fun sposta(deltaSecondi: Double) = conServizio { it.sposta(deltaSecondi) }
    override fun cambiaVelocita(v: Float) {
        salvaVelocita(v)
        conServizio { it.cambiaVelocita(v) }
    }
    override fun rispondi(testo: String) = conServizio { it.rispondi(testo) }
    override fun salta() = conServizio { it.salta() }
    override fun cambiaSaltabili(si: Boolean) = conServizio { it.cambiaSaltabili(si) }
    override fun sentiRisposta() = conServizio { it.sentiRisposta() }
    override fun ripetiDomanda() = conServizio { it.ripetiDomanda() }
    override fun avanti() = conServizio { it.avanti() }

    override fun ferma() {
        CentroAscolto.servizio?.ferma()
        futuro?.let { f -> if (f.isDone) runCatching { f.get().release() } }
        futuro = null
    }

    override fun visibile(si: Boolean) {
        CentroAscolto.lettoreVisibile = si
    }
}
