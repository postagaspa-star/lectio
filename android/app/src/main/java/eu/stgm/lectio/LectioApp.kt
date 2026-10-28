package eu.stgm.lectio

import android.app.Application
import eu.stgm.lectio.dati.Archivio
import eu.stgm.lectio.dati.ArchivioAudio
import eu.stgm.lectio.dati.CacheFile
import eu.stgm.lectio.dati.SorgenteServer
import eu.stgm.lectio.lavori.ControlloWorker
import eu.stgm.lectio.lavori.InviiWorker
import eu.stgm.lectio.lettore.CentroAscolto
import eu.stgm.lectio.lettore.LettoreMedia
import eu.stgm.lectio.logica.CodaRisposte
import eu.stgm.lectio.logica.SincronizzatorePosizioni
import eu.stgm.lectio.notifiche.Notificatore
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.LectioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Gli oggetti condivisi di tutta l'app, creati una volta sola. */
class LectioApp : Application() {
    val archivio: Archivio by lazy { Archivio(this) }

    /** Gli ultimi dati visti, per consultarli senza rete. */
    val cache: CacheFile by lazy { CacheFile(File(filesDir, "ultimi-dati")) }

    /** Le lezioni scaricate (MP3 + dettaglio), nella memoria privata dell'app. */
    val audio: ArchivioAudio by lazy { ArchivioAudio(File(filesDir, "lezioni")) }

    val client: LectioClient by lazy { LectioClient({ archivio.leggi() }, alNonAutorizzato = ::scollegatoDalServer) }

    val sorgente: SorgenteServer by lazy { SorgenteServer(client, cache, audio, alSalute = { archivio.adottaFuoriCasa(it.indirizzoFuoriCasa) }) }

    val posizioni: SincronizzatorePosizioni by lazy {
        SincronizzatorePosizioni(archivio, client::salvaAscolto, dopoInvio = { id, ascolto -> sorgente.aggiornaAscoltoLocale(id, ascolto) })
    }

    val risposte: CodaRisposte by lazy { CodaRisposte(archivio, client::rispondi) }

    private val _configurazione = MutableStateFlow<Configurazione?>(null)

    /** L'abbinamento attuale, sempre pronto (il lettore ci legge il token senza aspettare). */
    val configurazioneAttuale: StateFlow<Configurazione?> = _configurazione.asStateFlow()

    val lettore: LettoreMedia by lazy {
        LettoreMedia(this, audio, { client.indirizzoSicuro() }, { v -> ambito.launch { archivio.salvaVelocita(v) } })
    }

    val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Il server non riconosce più il telefono (due 401 di fila): si toglie il token e
     * l'abbinamento chiede di ricollegare. Le lezioni scaricate restano.
     */
    private suspend fun scollegatoDalServer() {
        archivio.dimentica(perso = true)
    }

    /** Prima di un abbinamento nuovo: i dati di prima vanno via solo se è un altro server. */
    suspend fun svuotaSeAltroServer(server: String, serverId: String?) {
        if (eu.stgm.lectio.dati.deveSvuotare(archivio.ultimoServer(), archivio.ultimoServerId(), server, serverId)) svuotaDatiLocali()
    }

    /** Un altro server, un nuovo abbinamento o uno scollegamento: niente dati di prima. */
    fun svuotaDatiLocali() {
        cache.svuota()
        audio.svuota()
    }

    /** Posizioni e risposte rimaste sul telefono partono appena c'è rete. */
    fun pianificaInvii() = InviiWorker.pianifica(this)

    override fun onCreate() {
        super.onCreate()
        Notificatore.creaCanali(this)
        ControlloWorker.pianifica(this)
        ambito.launch { archivio.flusso.collect { _configurazione.value = it } }
        ambito.launch {
            CentroAscolto.fermateAttive = archivio.fermateAttive()
            CentroAscolto.risposteScritte = archivio.risposteScritte()
            archivio.velocita()?.let { CentroAscolto.velocita = it }
        }
        ambito.launch(Dispatchers.IO) { cache.pota() }
    }
}
