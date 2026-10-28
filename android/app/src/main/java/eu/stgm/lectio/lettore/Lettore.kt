package eu.stgm.lectio.lettore

import eu.stgm.lectio.dati.Capitolo
import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.Correzione
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MateriaBreve
import eu.stgm.lectio.logica.Comando
import eu.stgm.lectio.logica.ControlloreDomande
import eu.stgm.lectio.logica.FaseDomanda
import eu.stgm.lectio.logica.ModoDomande
import eu.stgm.lectio.logica.StatoFermate
import eu.stgm.lectio.logica.StatoRisposta
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Quello che il lettore mostra: la lezione caricata e dove si è. */
data class StatoLettore(
    /** Il dettaglio della lezione (capitoli, domande, testo). null = niente caricato. */
    val lezione: Lezione? = null,
    val materia: MateriaBreve? = null,
    val posizioneS: Double = 0.0,
    val durataS: Double = 0.0,
    val suona: Boolean = false,
    val caricamento: Boolean = false,
    val velocita: Float = 1f,
    val fermate: StatoFermate = StatoFermate(),
    /** La risposta scritta alla domanda aperta (§7, contratto §15.2). */
    val risposta: StatoRisposta = StatoRisposta.DaScrivere,
    /** "Risposte scritte" delle impostazioni: false = solo "Senti la risposta". */
    val risposteScritte: Boolean = true,
    val finita: Boolean = false,
    val errore: String? = null,
) {
    val capitoloCorrente: Capitolo?
        get() = lezione?.capitoli?.lastOrNull { posizioneS >= it.inizioS } ?: lezione?.capitoli?.firstOrNull()

    val mancanteS: Double get() = (durataS - posizioneS).coerceAtLeast(0.0)
}

/**
 * Il lettore visto dalle schermate. Due strade con la stessa forma: quello vero
 * (ExoPlayer nel servizio, [LettoreMedia]) e quello d'esempio della visita guidata
 * e del fotografo ([LettoreEsempio], niente audio).
 */
interface Lettore {
    val stato: StateFlow<StatoLettore>

    /**
     * Carica [lezione] (col dettaglio) da [daSecondi]; [avvia] = parte subito;
     * [allaDomanda] = fermo su quella domanda per rispondere di nuovo ("Da ripassare").
     */
    fun carica(lezione: Lezione, materia: MateriaBreve?, daSecondi: Double, avvia: Boolean, allaDomanda: Long? = null)
    fun playPausa()

    /** Un salto voluto (capitolo, barra): la scheda della domanda si chiude. */
    fun vaiA(secondi: Double)

    /** -15 / +30 secondi. */
    fun sposta(deltaSecondi: Double)
    fun cambiaVelocita(v: Float)

    /** Risposte scritte: manda la risposta e, corretta (o in coda), fa sentire la risposta giusta. */
    fun rispondi(testo: String)

    /** Salta la domanda: parte subito la risposta giusta, la domanda va in "Da ripassare". */
    fun salta()

    /** "Domande: si possono saltare" cambiato dal menu del lettore. */
    fun cambiaSaltabili(si: Boolean)

    /** Senza risposte scritte: il vecchio "Senti la risposta". */
    fun sentiRisposta()
    fun ripetiDomanda()
    fun avanti()

    /** Chiude la lezione (la posizione si salva). */
    fun ferma()

    /**
     * Prima di togliere dal telefono ([elimina] false) o eliminare la lezione: se è
     * quella che suona dal file, passa al server o si ferma (punto 11).
     */
    suspend fun liberaFile(lezioneId: Long, elimina: Boolean)

    /** La schermata del lettore è davanti: a schermo spento, dopo la risposta, si riparte da soli. */
    fun visibile(si: Boolean)
}

/** Le velocità della pillola (§7): un tocco passa alla prossima. */
val VELOCITA = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f)

fun prossimaVelocita(attuale: Float): Float {
    val i = VELOCITA.indexOfFirst { kotlin.math.abs(it - attuale) < 0.01f }
    return VELOCITA[(i + 1).mod(VELOCITA.size)]
}

/**
 * Il lettore d'esempio: nessun audio, il tempo avanza da solo mentre "suona", le
 * fermate alle domande sono le stesse del lettore vero ([ControlloreDomande]) e la
 * correzione è quella finta della modalità esempio ([correttore]).
 */
class LettoreEsempio(
    private val ambito: CoroutineScope = MainScope(),
    private val passoMs: Long = 250,
    private val correttore: suspend (Long, Long, CorpoRisposta) -> Esito<Correzione> = { _, _, _ -> Esito.Irraggiungibile },
    private val attesaCorrezioneMs: Long = 900,
) : Lettore {
    private val _stato = MutableStateFlow(StatoLettore())
    override val stato: StateFlow<StatoLettore> = _stato.asStateFlow()
    private var controllore = ControlloreDomande(emptyList())
    private var orologio: Job? = null

    override fun carica(lezione: Lezione, materia: MateriaBreve?, daSecondi: Double, avvia: Boolean, allaDomanda: Long?) {
        orologio?.cancel()
        controllore = ControlloreDomande(lezione.domande, true, ModoDomande.da(_stato.value.risposteScritte, lezione.domandeSaltabili))
        controllore.salto(daSecondi)
        _stato.value = StatoLettore(lezione, materia, daSecondi, lezione.durataSecondi, false, velocita = 1f, risposteScritte = _stato.value.risposteScritte)
        // Come il lettore vero: una lezione lasciata ferma a una domanda riparte dalla domanda.
        val d = allaDomanda?.let { id -> lezione.domande.firstOrNull { it.id == id } } ?: controllore.domandaDaRiprendere(daSecondi)
        if (d != null) {
            controllore.fermaA(d)
            _stato.update { it.copy(posizioneS = d.fineDomandaS, fermate = controllore.stato) }
        } else if (avvia) {
            esegui(controllore.riproduci())
        }
    }

    fun impostaRisposteScritte(si: Boolean) {
        _stato.update { it.copy(risposteScritte = si) }
        controllore.modo = ModoDomande.da(si, _stato.value.lezione?.domandeSaltabili ?: false)
    }

    override fun cambiaSaltabili(si: Boolean) {
        _stato.update { it.copy(lezione = it.lezione?.copy(domandeSaltabili = si)) }
        controllore.modo = ModoDomande.da(_stato.value.risposteScritte, si)
    }

    /** Il play degli auricolari (per i test e la visita): stesse regole del servizio. */
    fun premiPlayEsterno() {
        val e = controllore.premiPlay()
        if (e.saltata) salta() else esegui(e.comandi)
    }

    /** La visita e il fotografo: fermo alla domanda numero [n] (come se ci fosse appena arrivato). */
    fun fermaAllaDomanda(n: Int) {
        val d = _stato.value.lezione?.domande?.firstOrNull { it.n == n } ?: return
        orologio?.cancel()
        controllore.fermaA(d)
        _stato.update { it.copy(posizioneS = d.fineDomandaS, suona = false, fermate = controllore.stato, risposta = StatoRisposta.DaScrivere) }
    }

    /** Il fotografo: la correzione già arrivata e la risposta giusta in ascolto. */
    fun mostraCorrezione(n: Int, testo: String, correzione: Correzione) {
        fermaAllaDomanda(n)
        val d = _stato.value.fermate.domanda ?: return
        controllore.riproduci()
        _stato.update { it.copy(posizioneS = d.inizioRispostaS + 2, suona = false, fermate = controllore.stato, risposta = StatoRisposta.Corretta(testo, correzione)) }
    }

    private fun esegui(comandi: List<Comando>) {
        for (c in comandi) {
            when (c) {
                Comando.Pausa -> {
                    orologio?.cancel()
                    _stato.update { it.copy(suona = false) }
                }
                Comando.Riproduci -> avviaOrologio()
                is Comando.Vai -> _stato.update { it.copy(posizioneS = c.secondi.coerceIn(0.0, it.durataS)) }
            }
        }
        val f = controllore.stato
        _stato.update { it.copy(fermate = f, risposta = if (f.fase == FaseDomanda.ASCOLTO) StatoRisposta.DaScrivere else it.risposta) }
    }

    private fun avviaOrologio() {
        orologio?.cancel()
        _stato.update { it.copy(suona = true, finita = false) }
        orologio = ambito.launch {
            while (isActive) {
                delay(passoMs)
                val s = _stato.value
                val nuova = (s.posizioneS + passoMs / 1000.0 * s.velocita).coerceAtMost(s.durataS)
                _stato.update { it.copy(posizioneS = nuova) }
                val comandi = controllore.posizione(nuova, true)
                if (comandi.isNotEmpty()) esegui(comandi)
                if (nuova >= s.durataS) {
                    _stato.update { it.copy(suona = false, finita = true) }
                    break
                }
            }
        }
    }

    override fun playPausa() {
        val s = _stato.value
        when {
            s.suona -> esegui(listOf(Comando.Pausa))
            s.fermate.fase == FaseDomanda.ALLA_DOMANDA && controllore.modo.scritte -> Unit
            else -> esegui(controllore.riproduci())
        }
    }

    override fun vaiA(secondi: Double) {
        val s = secondi.coerceIn(0.0, _stato.value.durataS)
        controllore.salto(s)
        _stato.update { it.copy(posizioneS = s, fermate = controllore.stato, risposta = StatoRisposta.DaScrivere, finita = false) }
    }

    override fun sposta(deltaSecondi: Double) = vaiA(_stato.value.posizioneS + deltaSecondi)

    override fun cambiaVelocita(v: Float) = _stato.update { it.copy(velocita = v) }

    override fun rispondi(testo: String) {
        val s = _stato.value
        val d = s.fermate.domanda ?: return
        val l = s.lezione ?: return
        if (s.fermate.fase != FaseDomanda.ALLA_DOMANDA || testo.isBlank()) return
        _stato.update { it.copy(risposta = StatoRisposta.Correggo(testo)) }
        ambito.launch {
            delay(attesaCorrezioneMs)
            val nuovo = when (val e = correttore(l.id, d.id, CorpoRisposta(testo, false))) {
                is Esito.Ok -> StatoRisposta.Corretta(testo, e.dato)
                is Esito.Errore -> StatoRisposta.Rifiutata(testo, e.messaggio)
                Esito.Irraggiungibile -> StatoRisposta.InCoda(testo)
            }
            _stato.update { it.copy(risposta = nuovo) }
            if (nuovo !is StatoRisposta.Rifiutata) esegui(controllore.riproduci())
        }
    }

    override fun salta() {
        if (_stato.value.fermate.fase != FaseDomanda.ALLA_DOMANDA) return
        val s = _stato.value
        val d = s.fermate.domanda
        val l = s.lezione
        _stato.update { it.copy(risposta = StatoRisposta.Saltata) }
        esegui(controllore.riproduci())
        if (d != null && l != null) ambito.launch { correttore(l.id, d.id, CorpoRisposta("", true)) }
    }

    override fun sentiRisposta() = esegui(controllore.sentiRisposta())
    override fun ripetiDomanda() = esegui(controllore.ripeti())
    override fun avanti() = esegui(controllore.avanti())

    override fun ferma() {
        orologio?.cancel()
        _stato.value = StatoLettore(risposteScritte = _stato.value.risposteScritte)
    }

    override fun visibile(si: Boolean) = Unit

    override suspend fun liberaFile(lezioneId: Long, elimina: Boolean) {
        if (_stato.value.lezione?.id == lezioneId && elimina) ferma()
    }

    val fase: FaseDomanda get() = controllore.stato.fase

    /** Per i test: un messaggio di errore leggibile. */
    fun messaggio(e: Esito<*>): String? = e.messaggio
}
