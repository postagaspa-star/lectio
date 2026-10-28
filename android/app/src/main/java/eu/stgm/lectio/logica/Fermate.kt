package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Domanda

/** Dove siamo rispetto alle domande della lezione (aspetto.md §7, "Alla domanda"). */
enum class FaseDomanda {
    /** Si ascolta la lezione. */
    ASCOLTO,

    /** L'audio si è fermato alla fine della domanda: tocca ad Andrea rispondere a voce. */
    ALLA_DOMANDA,

    /** Suona la risposta. */
    RISPOSTA,

    /** La risposta è finita: la scheda mostra il testo e "Avanti". */
    DOPO_RISPOSTA,
}

data class StatoFermate(val fase: FaseDomanda = FaseDomanda.ASCOLTO, val domanda: Domanda? = null) {
    /** La scheda della domanda è su (anche mentre suona la risposta). */
    val schedaAperta: Boolean get() = fase != FaseDomanda.ASCOLTO && domanda != null
}

/**
 * Come si risponde alle domande di questa lezione (aspetto.md §7, contratto
 * §15.2 e §15.4): decide cosa fa il tasto play quando l'audio è fermo alla domanda.
 */
enum class ModoDomande {
    /** "Risposte scritte" spento: il play fa sentire la risposta (comportamento di prima). */
    SENTI_RISPOSTA,

    /** Risposte scritte, domande saltabili: il play vale "Salta". */
    SCRITTE_SALTABILI,

    /** Risposte scritte, domande obbligatorie: il play ripete la domanda e poi si riferma. */
    SCRITTE_OBBLIGATORIE,
    ;

    val scritte: Boolean get() = this != SENTI_RISPOSTA

    companion object {
        fun da(risposteScritte: Boolean, saltabili: Boolean): ModoDomande = when {
            !risposteScritte -> SENTI_RISPOSTA
            saltabili -> SCRITTE_SALTABILI
            else -> SCRITTE_OBBLIGATORIE
        }
    }
}

/** Il tasto play premuto: i comandi e se la domanda è stata saltata (va mandato "saltata" al server). */
data class EsitoPlay(val comandi: List<Comando>, val saltata: Boolean = false)

/** Cosa deve fare il lettore. */
sealed interface Comando {
    data object Pausa : Comando
    data object Riproduci : Comando
    data class Vai(val secondi: Double) : Comando
}

/**
 * Le fermate alle domande, senza Android: il servizio del lettore gli passa la
 * posizione mentre suona (ogni ~150 ms) e i gesti di Andrea, e lui risponde con i
 * comandi da dare al lettore.
 *
 * Regole:
 * - mentre suona, quando la posizione supera `fine_domanda_s` di una domanda (e
 *   "Fermati alle domande" è acceso) → pausa e scheda della domanda;
 * - un salto (barra, capitolo, -15/+30) non è "ascolto": passare sopra una
 *   domanda saltando non ferma niente, e tornare indietro la riarma;
 * - "Senti la risposta" o il tasto play (auricolari, schermata di blocco) alla
 *   domanda → salta a `inizio_risposta_s` e riparte;
 * - "Ripeti la domanda" → torna a `inizio_s` (e alla fine si riferma);
 * - finita la risposta (`fine_s`) → pausa con il testo della risposta e "Avanti".
 *
 * Le soglie sono in secondi dell'audio, quindi valgono a ogni velocità: a 1,6× la
 * posizione avanza di più tra un controllo e l'altro, ma il passaggio sopra la
 * soglia si vede lo stesso (e ci sono 2,5 s di silenzio dopo ogni domanda).
 */
class ControlloreDomande(
    domande: List<Domanda>,
    attivo: Boolean = true,
    var modo: ModoDomande = ModoDomande.SENTI_RISPOSTA,
) {

    private val ordinate = domande.filter { it.fineDomandaS > 0 }.sortedBy { it.fineDomandaS }

    var attivo: Boolean = attivo
        private set

    var stato: StatoFermate = StatoFermate()
        private set

    /** L'ultima posizione vista, per capire se la soglia è stata superata ascoltando. */
    private var ultima: Double? = null

    fun cambiaAttivo(acceso: Boolean) {
        attivo = acceso
    }

    /**
     * La posizione del lettore ([inRiproduzione]: sta suonando). [siVede]: la
     * schermata del lettore è davanti e lo schermo è acceso. Se non si vede, alla
     * fine della risposta NON ci si ferma: si continua la lezione (a schermo spento
     * nessuno può toccare "Avanti", e un timer per ripartire può non scattare mai).
     */
    fun posizione(secondi: Double, inRiproduzione: Boolean, siVede: Boolean = true): List<Comando> {
        val prima = ultima
        ultima = secondi
        if (!inRiproduzione || prima == null || secondi <= prima) return emptyList()
        val s = stato
        return when (s.fase) {
            FaseDomanda.ASCOLTO -> {
                if (!attivo) return emptyList()
                // La prima soglia superata in questo passo. Oltre la fine di tutto il
                // blocco domanda-risposta non è ascolto ma un salto non visto: niente.
                val d = ordinate.firstOrNull { prima < it.fineDomandaS && secondi >= it.fineDomandaS && secondi <= maxOf(it.fineS, it.inizioRispostaS) }
                    ?: return emptyList()
                stato = StatoFermate(FaseDomanda.ALLA_DOMANDA, d)
                listOf(Comando.Pausa)
            }
            FaseDomanda.RISPOSTA -> {
                val d = s.domanda ?: return emptyList()
                if (d.fineS > d.inizioRispostaS && prima < d.fineS && secondi >= d.fineS) {
                    if (siVede) {
                        stato = StatoFermate(FaseDomanda.DOPO_RISPOSTA, d)
                        listOf(Comando.Pausa)
                    } else {
                        stato = StatoFermate()
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }
            // Ferma alla domanda ma qualcosa suona (non dovrebbe): si lascia stare.
            FaseDomanda.ALLA_DOMANDA, FaseDomanda.DOPO_RISPOSTA -> emptyList()
        }
    }

    /**
     * Il tasto play da notifica, schermata di blocco o auricolari. Alla domanda:
     * - senza risposte scritte fa sentire la risposta;
     * - con domande saltabili vale "Salta" (parte la risposta, la domanda va in Da ripassare);
     * - con domande obbligatorie ripete la domanda: l'audio non va avanti senza risposta.
     */
    fun premiPlay(): EsitoPlay {
        if (stato.fase != FaseDomanda.ALLA_DOMANDA) return EsitoPlay(riproduci())
        return when (modo) {
            ModoDomande.SENTI_RISPOSTA -> EsitoPlay(riproduci())
            ModoDomande.SCRITTE_SALTABILI -> EsitoPlay(riproduci(), saltata = true)
            ModoDomande.SCRITTE_OBBLIGATORIE -> EsitoPlay(ripeti())
        }
    }

    /**
     * "+30" dalla schermata di blocco o dagli auricolari mentre l'audio è fermo alla
     * domanda: non deve saltarla senza traccia. Vale come il tasto play di quel modo
     * (obbligatorie: ripete la domanda; saltabili: Salta, con "saltata" al server).
     * null = non si è alla domanda: è un +30 normale.
     */
    fun premiAvanti(): EsitoPlay? = if (stato.fase == FaseDomanda.ALLA_DOMANDA) premiPlay() else null

    /**
     * Si va alla risposta (o si riprende): risposta inviata, "Salta", "Senti la
     * risposta". Fuori dalla domanda è un play normale.
     */
    fun riproduci(): List<Comando> {
        val s = stato
        val d = s.domanda
        return when {
            s.fase == FaseDomanda.ALLA_DOMANDA && d != null -> {
                stato = StatoFermate(FaseDomanda.RISPOSTA, d)
                ultima = d.inizioRispostaS
                listOf(Comando.Vai(d.inizioRispostaS), Comando.Riproduci)
            }
            s.fase == FaseDomanda.DOPO_RISPOSTA -> {
                stato = StatoFermate()
                listOf(Comando.Riproduci)
            }
            else -> listOf(Comando.Riproduci)
        }
    }

    /** "Senti la risposta": come il tasto play, ma solo alla domanda. */
    fun sentiRisposta(): List<Comando> =
        if (stato.fase == FaseDomanda.ALLA_DOMANDA) riproduci() else emptyList()

    /** "Ripeti la domanda": da capo, e alla fine si riferma. */
    fun ripeti(): List<Comando> {
        val d = stato.domanda ?: return emptyList()
        stato = StatoFermate()
        ultima = d.inizioS
        return listOf(Comando.Vai(d.inizioS), Comando.Riproduci)
    }

    /** "Avanti" dopo la risposta (o alla domanda, per non sentirla): si riprende la lezione. */
    fun avanti(): List<Comando> {
        val s = stato
        val d = s.domanda ?: return emptyList()
        return when (s.fase) {
            FaseDomanda.DOPO_RISPOSTA -> {
                stato = StatoFermate()
                listOf(Comando.Riproduci)
            }
            FaseDomanda.ALLA_DOMANDA, FaseDomanda.RISPOSTA -> {
                stato = StatoFermate()
                ultima = d.fineS
                listOf(Comando.Vai(d.fineS), Comando.Riproduci)
            }
            FaseDomanda.ASCOLTO -> emptyList()
        }
    }

    /**
     * Fermo alla domanda [d] senza esserci arrivato ascoltando: "Da ripassare" apre
     * il lettore lì per rispondere di nuovo (aspetto.md §4).
     */
    fun fermaA(d: Domanda) {
        stato = StatoFermate(FaseDomanda.ALLA_DOMANDA, d)
        ultima = d.fineDomandaS
    }

    /** Un salto voluto (barra, capitolo, -15/+30): la scheda si chiude e la soglia si riarma. */
    fun salto(secondi: Double) {
        stato = StatoFermate()
        ultima = secondi
    }

    /**
     * Si riprende una lezione da [secondi]: se cade dentro una domanda o nel suo
     * silenzio (tra `inizio_s` e `inizio_risposta_s`), la lezione era ferma lì e
     * va ripresa dalla domanda, non oltre (altrimenti la si salterebbe).
     */
    fun domandaDaRiprendere(secondi: Double): Domanda? =
        if (!attivo) null else ordinate.firstOrNull { secondi >= it.inizioS && secondi < it.inizioRispostaS }

    /** La domanda in cui cade [secondi] (per i segni sulla barra e la notifica). */
    fun domandaA(secondi: Double): Domanda? = ordinate.firstOrNull { secondi >= it.inizioS && secondi < it.fineS }

    val domande: List<Domanda> get() = ordinate
}

/**
 * "DOMANDA 2 DI 6" come nel prototipo (anche per le domande di blocco); "DOMANDA
 * DEL BLOCCO" solo se il server non manda i numeri (aspetto.md §7).
 */
fun etichettaDomanda(d: Domanda): String = when {
    d.n > 0 && d.di > 0 -> "DOMANDA ${d.n} DI ${d.di}"
    d.tipo == "blocco" -> "DOMANDA DEL BLOCCO"
    else -> "DOMANDA"
}

/**
 * La riga della notifica alla domanda: con le risposte scritte il play salta la
 * domanda (a schermo spento non si scrive, aspetto.md §7); senza, fa sentire la risposta.
 */
fun rigaNotificaDomanda(d: Domanda, modo: ModoDomande): String {
    val quale = if (d.n > 0 && d.di > 0) "Domanda ${d.n} di ${d.di}" else "Domanda"
    return when (modo) {
        ModoDomande.SENTI_RISPOSTA -> "$quale · play per la risposta"
        ModoDomande.SCRITTE_SALTABILI -> "$quale · rispondi nell'app o premi play per saltarla"
        ModoDomande.SCRITTE_OBBLIGATORIE -> "$quale · rispondi nell'app"
    }
}
