package eu.stgm.lectio.lettore

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import eu.stgm.lectio.LectioApp
import eu.stgm.lectio.MainActivity
import eu.stgm.lectio.R
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MateriaBreve
import eu.stgm.lectio.logica.Comando
import eu.stgm.lectio.logica.ControlloreDomande
import eu.stgm.lectio.logica.EsitoRisposta
import eu.stgm.lectio.logica.FaseDomanda
import eu.stgm.lectio.logica.ModoDomande
import eu.stgm.lectio.logica.RegolaPosizione
import eu.stgm.lectio.logica.StatoRisposta
import eu.stgm.lectio.logica.rigaNotificaDomanda
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Il ponte tra il servizio del lettore e le schermate, nello stesso processo:
 * il servizio pubblica lo stato, le schermate leggono e chiamano i gesti.
 */
object CentroAscolto {
    internal val _stato = MutableStateFlow(StatoLettore())
    val stato: StateFlow<StatoLettore> = _stato.asStateFlow()

    @Volatile
    var servizio: ServizioLettore? = null

    /** La schermata del lettore è davanti (per ripartire da soli dopo la risposta a schermo spento). */
    @Volatile
    var lettoreVisibile: Boolean = false

    /** "Fermati alle domande" e "Risposte scritte" (impostazioni, con copia locale). */
    @Volatile
    var fermateAttive: Boolean = true

    @Volatile
    var risposteScritte: Boolean = true
        set(valore) {
            field = valore
            _stato.update { it.copy(risposteScritte = valore) }
            servizio?.aggiornaModo()
        }

    /** La velocità che il lettore usa quando carica una lezione. */
    @Volatile
    var velocita: Float = 1f
}

/**
 * Il lettore vero: ExoPlayer dentro un MediaSessionService, così suona a schermo
 * spento con i controlli in notifica, nella schermata di blocco e dagli auricolari.
 *
 * Tutti i comandi che arrivano da fuori (notifica, auricolari, schermata di blocco)
 * passano da [GiocatoreConDomande]: il tasto play alla domanda fa partire la
 * risposta (o, con le risposte scritte, salta la domanda) e i salti riarmano le
 * fermate.
 */
@OptIn(UnstableApi::class)
class ServizioLettore : MediaSessionService() {

    private var sessione: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var giocatore: GiocatoreConDomande
    private var controllore = ControlloreDomande(emptyList())
    private val principale = Handler(Looper.getMainLooper())
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ultimoSalvataggio: Long? = null
    private var capitolo = -1
    private var lezione: Lezione? = null
    private var materia: MateriaBreve? = null
    private var voceBase: MediaItem? = null

    private val app: LectioApp get() = application as LectioApp

    private val battito = object : Runnable {
        override fun run() {
            controlla()
            if (exo.isPlaying) principale.postDelayed(this, PASSO_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(eu.stgm.lectio.rete.LectioClient.httpLento)
        // Il token va solo al server riconosciuto (https, o http col server_id giusto),
        // mai a un altro apparecchio con lo stesso indirizzo. Gira sul thread di caricamento.
        val conToken = ResolvingDataSource.Factory(http) { spec ->
            val conf = app.configurazioneAttuale.value
            val indirizzo = spec.uri.toString()
            val permesso = conf != null && indirizzo.startsWith("http") && runBlocking { app.client.tokenPermessoPer(indirizzo) }
            if (permesso) spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer ${conf!!.token}")) else spec
        }
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, conToken)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo.addListener(ascoltatore)
        giocatore = GiocatoreConDomande(exo)

        val apri = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_APRI_LETTORE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val indietro = CommandButton.Builder()
            .setDisplayName("Indietro di 15 secondi")
            .setIconResId(R.drawable.ic_meno15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .build()
        val avanti = CommandButton.Builder()
            .setDisplayName("Avanti di 30 secondi")
            .setIconResId(R.drawable.ic_piu30)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .build()
        sessione = MediaSession.Builder(this, giocatore)
            .setSessionActivity(apri)
            .setCustomLayout(listOf(indietro, avanti))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    // Niente "precedente/successiva": una lezione alla volta, al loro posto -15 e +30.
                    val comandi = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .remove(Player.COMMAND_SEEK_TO_NEXT)
                        .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(comandi)
                        .build()
                }
            })
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CANALE)
                .setChannelName(R.string.canale_ascolto)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notifica) },
        )
        CentroAscolto.servizio = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = sessione

    /** L'app tolta dai recenti: se non sta suonando, il servizio si chiude (la posizione è salvata). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        salva()
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        salva()
        principale.removeCallbacksAndMessages(null)
        CentroAscolto.servizio = null
        CentroAscolto._stato.value = StatoLettore(risposteScritte = CentroAscolto.risposteScritte)
        sessione?.run {
            player.release()
            release()
        }
        sessione = null
        ambito.cancel()
        super.onDestroy()
    }

    // --- Comandi dalle schermate ----------------------------------------------------------------

    fun carica(l: Lezione, m: MateriaBreve?, uri: Uri, daSecondi: Double, avvia: Boolean, allaDomanda: Long?, forza: Boolean = false) {
        // Stessa lezione già pronta: niente ricarica, tranne se il lettore è in errore o fermo (IDLE).
        val stessa = !forza && lezione?.id == l.id && exo.mediaItemCount > 0 && exo.playbackState != Player.STATE_IDLE
        lezione = l
        materia = m
        controllore = ControlloreDomande(l.domande, CentroAscolto.fermateAttive, ModoDomande.da(CentroAscolto.risposteScritte, l.domandeSaltabili))
        capitolo = -1
        val inizio = if (daSecondi >= l.durataSecondi - 2) 0.0 else daSecondi
        if (!stessa) {
            val voce = MediaItem.Builder().setMediaId(l.id.toString()).setUri(uri).setMediaMetadata(metadati(l, m, null)).build()
            voceBase = voce
            exo.setMediaItem(voce, (inizio * 1000).toLong())
            exo.prepare()
        } else {
            exo.seekTo((inizio * 1000).toLong())
        }
        exo.setPlaybackSpeed(CentroAscolto.velocita)
        controllore.salto(inizio)
        // Una lezione lasciata ferma a una domanda riparte dalla domanda, non oltre.
        val d = allaDomanda?.let { id -> l.domande.firstOrNull { it.id == id } } ?: controllore.domandaDaRiprendere(inizio)
        CentroAscolto._stato.value = StatoLettore(
            lezione = l, materia = m, posizioneS = inizio, durataS = l.durataSecondi, velocita = CentroAscolto.velocita,
            risposteScritte = CentroAscolto.risposteScritte, caricamento = true,
        )
        if (d != null) {
            exo.pause()
            exo.seekTo((d.fineDomandaS * 1000).toLong())
            controllore.fermaA(d)
            pubblica(posizione = d.fineDomandaS)
            aggiornaNotifica()
        } else if (avvia) {
            esegui(controllore.riproduci())
        } else {
            pubblica()
        }
    }

    /** Il play/pausa grande dell'app: alla domanda non salta mai (lì ci sono Invia e Salta). */
    fun playPausa() {
        when {
            exo.isPlaying -> giocatore.pause()
            controllore.stato.fase == FaseDomanda.ALLA_DOMANDA && controllore.modo.scritte -> Unit
            else -> giocatore.play()
        }
    }

    fun vaiA(secondi: Double) = giocatore.seekTo((secondi.coerceAtLeast(0.0) * 1000).toLong())

    fun sposta(delta: Double) = vaiA(posizione() + delta)

    fun cambiaVelocita(v: Float) {
        CentroAscolto.velocita = v
        exo.setPlaybackSpeed(v)
        pubblica()
    }

    fun rispondi(testo: String) {
        val l = lezione ?: return
        val d = controllore.stato.domanda ?: return
        if (controllore.stato.fase != FaseDomanda.ALLA_DOMANDA || testo.isBlank()) return
        CentroAscolto._stato.update { it.copy(risposta = StatoRisposta.Correggo(testo)) }
        app.ambito.launch {
            val nuovo = when (val e = app.risposte.rispondi(l.id, d.id, testo, saltata = false)) {
                is EsitoRisposta.Corretta -> StatoRisposta.Corretta(testo, e.correzione)
                EsitoRisposta.InCoda -> {
                    app.pianificaInvii()
                    StatoRisposta.InCoda(testo)
                }
                is EsitoRisposta.Rifiutata -> StatoRisposta.Rifiutata(testo, e.messaggio)
            }
            withContext(Dispatchers.Main) {
                // Nel frattempo Andrea può aver saltato o cambiato domanda, o il lettore si è chiuso.
                if (CentroAscolto.servizio !== this@ServizioLettore) return@withContext
                if (controllore.stato.domanda?.id != d.id || controllore.stato.fase != FaseDomanda.ALLA_DOMANDA) return@withContext
                CentroAscolto._stato.update { it.copy(risposta = nuovo) }
                if (nuovo !is StatoRisposta.Rifiutata) esegui(controllore.riproduci())
            }
        }
    }

    /** Salta: parte la risposta giusta, la domanda va in "Da ripassare" (anche dagli auricolari). */
    fun salta() {
        val l = lezione ?: return
        val d = controllore.stato.domanda ?: return
        if (controllore.stato.fase != FaseDomanda.ALLA_DOMANDA) return
        CentroAscolto._stato.update { it.copy(risposta = StatoRisposta.Saltata) }
        esegui(controllore.riproduci())
        app.ambito.launch {
            if (app.risposte.rispondi(l.id, d.id, "", saltata = true) == EsitoRisposta.InCoda) app.pianificaInvii()
        }
    }

    fun sentiRisposta() = esegui(controllore.sentiRisposta())
    fun ripetiDomanda() = esegui(controllore.ripeti())
    fun avanti() = esegui(controllore.avanti())

    fun ferma() {
        salva()
        exo.stop()
        exo.clearMediaItems()
        lezione = null
        materia = null
        controllore = ControlloreDomande(emptyList())
        CentroAscolto._stato.value = StatoLettore(risposteScritte = CentroAscolto.risposteScritte)
        stopSelf()
    }

    fun cambiaFermate(attive: Boolean) = controllore.cambiaAttivo(attive)

    /** "Domande: si possono saltare" cambiato dal menu del lettore (§15.4). */
    fun cambiaSaltabili(si: Boolean) {
        val l = lezione ?: return
        lezione = l.copy(domandeSaltabili = si)
        aggiornaModo()
    }

    fun aggiornaModo() {
        val l = lezione ?: return
        controllore.modo = ModoDomande.da(CentroAscolto.risposteScritte, l.domandeSaltabili)
        aggiornaNotifica()
        pubblica()
    }

    // --- Meccanica ------------------------------------------------------------------------------

    private fun posizione(): Double = exo.currentPosition.coerceAtLeast(0) / 1000.0

    private fun esegui(comandi: List<Comando>) {
        for (c in comandi) {
            when (c) {
                Comando.Pausa -> exo.pause()
                Comando.Riproduci -> {
                    if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
                    if (exo.playbackState == Player.STATE_IDLE && exo.mediaItemCount > 0) exo.prepare()
                    CentroAscolto._stato.update { it.copy(errore = null) }
                    exo.play()
                }
                is Comando.Vai -> exo.seekTo((c.secondi * 1000).toLong())
            }
        }
        dopoCambioFase()
    }

    private var faseVista = FaseDomanda.ASCOLTO
    private var domandaVista: Long? = null

    /** La scheda della domanda è cambiata: notifica, risposta, ripartenza da sola. */
    private fun dopoCambioFase() {
        val f = controllore.stato
        if (f.fase != faseVista || f.domanda?.id != domandaVista) {
            // Una domanda nuova (o di nuovo l'ascolto): il campo della risposta riparte vuoto.
            if (f.fase == FaseDomanda.ASCOLTO || (f.fase == FaseDomanda.ALLA_DOMANDA && f.domanda?.id != domandaVista)) {
                CentroAscolto._stato.update { it.copy(risposta = StatoRisposta.DaScrivere) }
            }
            faseVista = f.fase
            domandaVista = f.domanda?.id
            aggiornaNotifica()
        }
        pubblica()
    }

    private fun siVede(): Boolean {
        val schermoAcceso = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        return schermoAcceso && CentroAscolto.lettoreVisibile
    }

    /** Ogni ~150 ms mentre suona: fermate, capitolo, salvataggio ogni 15 s. */
    private fun controlla() {
        val pos = posizione()
        // A schermo spento (o con un'altra app davanti) dopo la risposta non ci si ferma:
        // nessuno può toccare "Avanti" e un timer per ripartire può non scattare mai.
        val comandi = controllore.posizione(pos, exo.isPlaying, siVede())
        if (comandi.isNotEmpty()) esegui(comandi)
        val l = lezione
        if (l != null) {
            val n = l.capitoli.lastOrNull { pos >= it.inizioS }?.n ?: -1
            if (n != capitolo) {
                capitolo = n
                aggiornaNotifica()
            }
        }
        val adesso = System.currentTimeMillis()
        if (exo.isPlaying && RegolaPosizione.eOraDiSalvare(ultimoSalvataggio, adesso)) {
            if (ultimoSalvataggio != null) salva()
            ultimoSalvataggio = adesso
        }
        pubblica(pos)
    }

    private fun pubblica(posizione: Double = posizione()) {
        val l = lezione
        val durata = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: l?.durataSecondi ?: 0.0
        CentroAscolto._stato.update {
            it.copy(
                lezione = l,
                materia = materia,
                posizioneS = posizione,
                durataS = durata,
                suona = exo.isPlaying,
                caricamento = exo.playbackState == Player.STATE_BUFFERING,
                velocita = exo.playbackParameters.speed,
                fermate = controllore.stato,
                risposteScritte = CentroAscolto.risposteScritte,
                finita = exo.playbackState == Player.STATE_ENDED,
            )
        }
    }

    /** Titolo, materia · capitolo, e alla domanda "Domanda 2 di 6 · …" (§7). */
    private fun metadati(l: Lezione, m: MateriaBreve?, riga: String?): MediaMetadata {
        val cap = l.capitoli.firstOrNull { it.n == capitolo }?.titolo
        val sotto = riga ?: listOfNotNull(m?.nome, cap).joinToString(" · ")
        return MediaMetadata.Builder()
            .setTitle(l.argomento)
            .setArtist(sotto)
            .setAlbumTitle(m?.nome)
            .setDisplayTitle(l.argomento)
            .setSubtitle(sotto)
            .build()
    }

    private fun aggiornaNotifica() {
        val l = lezione ?: return
        val base = voceBase ?: return
        if (exo.mediaItemCount == 0) return
        val f = controllore.stato
        val riga = if (f.fase == FaseDomanda.ALLA_DOMANDA && f.domanda != null) rigaNotificaDomanda(f.domanda, controllore.modo) else null
        // Stessa sorgente audio: ExoPlayer aggiorna i dati senza fermare il suono.
        exo.replaceMediaItem(0, base.buildUpon().setMediaMetadata(metadati(l, materia, riga)).build())
    }

    /** La posizione va al server (o resta sul telefono finché non c'è rete). */
    private fun salva() {
        val l = lezione ?: return
        if (exo.mediaItemCount == 0) return
        val pos = posizione()
        val finita = exo.playbackState == Player.STATE_ENDED || pos >= l.durataSecondi - 2
        // Nell'ambito dell'app: il servizio che si chiude non interrompe l'invio.
        app.ambito.launch {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!app.posizioni.registra(l.id, if (finita) l.durataSecondi else pos, finita)) app.pianificaInvii()
            }
        }
    }

    private val ascoltatore = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                CentroAscolto._stato.update { it.copy(errore = null) }
                principale.removeCallbacks(battito)
                principale.post(battito)
            } else {
                salva()
                ultimoSalvataggio = null
            }
            pubblica()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) salva()
            pubblica()
        }

        override fun onPlayerError(error: PlaybackException) {
            CentroAscolto._stato.update {
                it.copy(
                    suona = false,
                    errore = "Non riesco a far partire l'audio. Senza rete si ascoltano solo le lezioni scaricate.",
                )
            }
        }
    }

    /**
     * Il giocatore che vede la sessione (notifica, auricolari, schermata di blocco):
     * play e salti passano dalle regole delle fermate.
     */
    inner class GiocatoreConDomande(giocatore: Player) : ForwardingPlayer(giocatore) {
        override fun play() = premutoPlay()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) premutoPlay() else super.setPlayWhenReady(false)
        }

        /**
         * A schermo spento non si scrive: con domande saltabili il play vale "Salta",
         * con domande obbligatorie ripete la domanda (aspetto.md §7, contratto §15.4).
         */
        private fun premutoPlay() {
            if (controllore.stato.fase == FaseDomanda.ALLA_DOMANDA && controllore.modo == ModoDomande.SCRITTE_SALTABILI) {
                salta()
            } else {
                esegui(controllore.premiPlay().comandi)
            }
        }

        override fun pause() {
            super.pause()
            pubblica()
        }

        override fun seekTo(positionMs: Long) {
            controllore.salto(positionMs / 1000.0)
            super.seekTo(positionMs)
            dopoCambioFase()
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            controllore.salto(positionMs / 1000.0)
            super.seekTo(mediaItemIndex, positionMs)
            dopoCambioFase()
        }

        override fun seekBack() = seekTo((exo.currentPosition - 15_000).coerceAtLeast(0))

        override fun seekForward() {
            controllore.premiAvanti()?.let { e ->
                if (e.saltata) salta() else esegui(e.comandi)
                return
            }
            val durata = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            seekTo((exo.currentPosition + 30_000).coerceAtMost(durata))
        }
    }

    companion object {
        const val CANALE = "ascolto"
        private const val PASSO_MS = 150L
    }
}
