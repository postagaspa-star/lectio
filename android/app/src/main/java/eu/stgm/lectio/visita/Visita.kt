package eu.stgm.lectio.visita

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Le schermate che la visita apre da sé (con i dati d'esempio). */
enum class SchermataVisita { OGGI, MATERIA, FOGLIO_AGGIUNGI, NUOVA_LEZIONE, LETTORE, NUOVO_SCHEMA, SCHEMA, LAMPO, SIMULATA_IN_CORSO, SIMULATA_RISULTATI }

/**
 * Un passo: l'elemento da illuminare ([bersaglio], null = nessuno), il titolo
 * corto, UNA frase, e se si va avanti leggendo ("Avanti") o facendo l'azione vera.
 * [poi]: il passo si fa in due tocchi (aspetto.md §43.4): il [bersaglio] apre un foglio
 * e il riflettore passa a [poi], la riga da toccare; si va avanti solo toccando quella.
 */
data class PassoVisita(
    val bersaglio: String?,
    val titolo: String,
    val frase: String,
    val pratico: Boolean,
    val schermata: SchermataVisita,
    val poi: String? = null,
) {
    /** Il tocco che fa andare avanti un passo pratico. */
    val azioneGiusta: String? get() = poi ?: bersaglio
}

/** Gli id dei bersagli: le schermate li segnano con [bersaglio]. */
object Bersagli {
    const val PROSSIMA = "prossima"
    const val PILLOLA_MATERIA = "pillola-materia"
    const val SCHEDA_PROF = "scheda-prof"
    const val AGGIUNGI = "aggiungi"
    const val TESTO_GEMINI = "testo-gemini"
    /** Il pulsante Nuovo della materia (aspetto.md §43.2). */
    const val NUOVO = "nuovo"
    /** Le righe del foglio "Cosa vuoi fare?" che la visita fa toccare (§43.4). */
    const val RIGA_LEZIONE = "riga-lezione"
    const val RIGA_SCHEMA = "riga-schema"
    const val PREPARA = "prepara"
    /** Il passo 7: la barra in basso di Nuova lezione (riepilogo + pulsante, aspetto.md §12). */
    const val RIEPILOGO = "riepilogo"
    /** Il passo 8: scrivi la risposta e premi Invia (aspetto.md §12, v1.1). */
    const val SENTI_RISPOSTA = "invia-risposta"

    // Le cose nuove (aspetto.md §39).
    const val SEGMENTO_SCHEMI = "segmento-schemi"
    const val COME_LO_VUOI = "come-lo-vuoi"
    const val SPUNTO_IMMAGINI = "spunto-immagini"
    const val BARRA_LETTURA = "barra-lettura"
    /** Il passo dell'indice: col foglio aperto il riflettore passa alla riga da toccare. */
    const val RIGA_INDICE = "riga-indice"
    const val PARAGRAFO = "paragrafo"
    const val NON_HO_CAPITO = "non-ho-capito"
    const val FONTI_RISPOSTA = "fonti-risposta"
    const val LAMPO_OGGI = "lampo-oggi"
    const val MOSTRA_RISPOSTA = "mostra-risposta"
    const val TRE_ESITI = "tre-esiti"
    const val SEGMENTO_SIMULATE = "segmento-simulate"
    const val TEMPO = "tempo"
    const val STIMA = "stima"
}

/**
 * La visita guidata (aspetto.md §12): si impara sulle schermate vere, coi dati
 * d'esempio, facendo le cose. "Indietro" sempre (tranne al primo passo), "Avanti"
 * solo nei passi da leggere, "Salta la visita" sempre. Nei passi pratici si va
 * avanti solo facendo l'azione giusta.
 */
class Visita {

    /** I passi di questo giro: quelli di sempre, le cose nuove o tutti e due. */
    var passi by mutableStateOf(PASSI_BASE)
        private set

    var attiva by mutableStateOf(false)
        private set
    var indice by mutableIntStateOf(0)
        private set

    /** Quante volte è partita: le schermate d'esempio ripartono pulite ogni volta. */
    var giro by mutableIntStateOf(0)
        private set

    /** Il fumetto fa un piccolo scatto quando si tocca fuori dal riflettore. */
    var scatto by mutableIntStateOf(0)

    /** Dove sono gli elementi delle schermate, in coordinate della finestra. */
    val bersagli = mutableStateMapOf<String, Rect>()

    /** Chi apre le schermate: lo imposta l'attività. */
    var vaiA: (SchermataVisita) -> Unit = {}
    var allaFine: () -> Unit = {}

    val corrente: PassoVisita get() = passi[indice.coerceIn(0, passi.lastIndex)]
    val ultimo: Boolean get() = indice == passi.lastIndex

    /** "3 di 8": l'ultimo passo ("Ecco fatto") non si conta. */
    val conteggio: String get() = "${indice + 1} di ${passi.size - 1}"

    val puoTornare: Boolean get() = indice > 0 && !ultimo

    /** Quella di sempre. */
    fun inizia() = parti(PASSI_BASE)

    /** Solo le cose nuove: la prima volta dopo l'aggiornamento (aspetto.md §39). */
    fun iniziaNovita() = parti(PASSI_NOVITA + PASSO_FINE)

    /** Dalle Impostazioni e alla prima apertura: quella di sempre e subito dopo le novità. */
    fun iniziaTutte() = parti(PASSI_BASE.dropLast(1) + PASSI_NOVITA + PASSO_FINE)

    private fun parti(elenco: List<PassoVisita>) {
        passi = elenco
        bersaglioTemporaneo = null
        indice = 0
        giro++
        attiva = true
        vaiA(corrente.schermata)
    }

    /** "Avanti" (solo nei passi da leggere) e l'azione giusta nei passi pratici. */
    fun avanti() {
        if (!attiva) return
        if (ultimo) {
            fine()
            return
        }
        indice++
        vaiA(corrente.schermata)
    }

    fun indietro() {
        if (!puoTornare) return
        indice--
        vaiA(corrente.schermata)
    }

    /**
     * Le schermate lo chiamano quando si fa un'azione: se è quella che il passo
     * chiede si va avanti. true = l'azione era quella della visita.
     */
    fun azione(id: String): Boolean {
        if (!attiva || !corrente.pratico || corrente.azioneGiusta != id) return false
        avanti()
        return true
    }

    /** Nei passi pratici "Avanti" non c'è: si va avanti solo facendo. */
    val mostraAvanti: Boolean get() = !corrente.pratico

    fun salta() = fine()

    private fun fine() {
        attiva = false
        indice = 0
        allaFine()
    }

    /** Un foglio aperto durante il passo può spostare il riflettore su di sé. */
    var bersaglioTemporaneo by mutableStateOf<String?>(null)

    /** Il rettangolo da illuminare (i passi con due bersagli prendono l'unione). */
    fun rettangolo(): Rect? {
        val id = bersaglioTemporaneo ?: corrente.bersaglio ?: return null
        val parti = id.split('+').mapNotNull { bersagli[it] }
        if (parti.isEmpty()) return null
        return parti.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    }
}

private val PASSI_BASE = listOf(
    PassoVisita(Bersagli.PROSSIMA, "La prossima verifica", "Qui c'è sempre la prossima verifica e la sua lezione.", false, SchermataVisita.OGGI),
    PassoVisita(Bersagli.PILLOLA_MATERIA, "Apri la materia", "Tocca Storia per aprire la sua pagina.", true, SchermataVisita.OGGI),
    PassoVisita(Bersagli.SCHEDA_PROF, "Cosa chiede il prof", "L'ho scritta io dalle tue verifiche. Puoi correggerla.", false, SchermataVisita.MATERIA),
    PassoVisita(Bersagli.AGGIUNGI, "Aggiungi una verifica", "Tocca Aggiungi per caricare una verifica corretta.", true, SchermataVisita.MATERIA),
    PassoVisita(Bersagli.TESTO_GEMINI, "Testo per Gemini", "Copialo, dallo a Gemini con la foto, poi manda qui il file.", false, SchermataVisita.FOGLIO_AGGIUNGI),
    PassoVisita(Bersagli.NUOVO, "Nuova lezione", "Tocca Nuovo, poi Lezione da ascoltare, per prepararne una.", true, SchermataVisita.MATERIA, poi = Bersagli.RIGA_LEZIONE),
    PassoVisita(Bersagli.RIEPILOGO, "Come la vuoi", "Scegli materiale, tipo e indicazioni: qui vedi cosa esce e quanto costa.", false, SchermataVisita.NUOVA_LEZIONE),
    PassoVisita(Bersagli.SENTI_RISPOSTA, "La domanda", "Scrivi la tua risposta e premi Invia: te la correggo.", true, SchermataVisita.LETTORE),
    PassoVisita(null, "Ecco fatto", "Adesso Lectio lavora con le tue lezioni vere.", false, SchermataVisita.LETTORE),
)

/** I passi delle pagine nuove, con le frasi di aspetto.md §39. */
private val PASSI_NOVITA = listOf(
    PassoVisita(Bersagli.SEGMENTO_SCHEMI, "Schemi", "Qui trovi gli schemi da leggere.", true, SchermataVisita.MATERIA),
    PassoVisita(Bersagli.NUOVO, "Nuovo schema", "Si crea come una lezione.", true, SchermataVisita.MATERIA, poi = Bersagli.RIGA_SCHEMA),
    PassoVisita(Bersagli.COME_LO_VUOI, "Come lo vuoi", "Scegli forma e livello: qui vedi cosa esce e quanto costa.", false, SchermataVisita.NUOVO_SCHEMA),
    PassoVisita(Bersagli.SPUNTO_IMMAGINI, "Un'immagine utile", "Vuoi più foto? Basta questo.", false, SchermataVisita.NUOVO_SCHEMA),
    PassoVisita(Bersagli.BARRA_LETTURA, "Indice", "Da qui salti a ogni parte.", true, SchermataVisita.SCHEMA),
    PassoVisita(Bersagli.PARAGRAFO, "Un paragrafo", "Toccalo per vedere cosa puoi fare.", true, SchermataVisita.SCHEMA),
    PassoVisita(Bersagli.NON_HO_CAPITO, "Non ho capito", "Te lo rispiego solo con i tuoi documenti.", true, SchermataVisita.SCHEMA),
    PassoVisita(Bersagli.FONTI_RISPOSTA, "Da dove viene", "Qui controlli da dove l'ho preso.", false, SchermataVisita.SCHEMA),
    PassoVisita(Bersagli.LAMPO_OGGI, "Ripasso lampo", "Poche schede al giorno, tre minuti.", true, SchermataVisita.OGGI),
    PassoVisita(Bersagli.MOSTRA_RISPOSTA, "Mostra la risposta", "Prima prova a ricordare.", true, SchermataVisita.LAMPO),
    PassoVisita(Bersagli.TRE_ESITI, "Tre parole", "Quasi: te ne ricordavi solo una parte.", true, SchermataVisita.LAMPO),
    PassoVisita(Bersagli.SEGMENTO_SIMULATE, "Verifica simulata", "Una verifica come quelle del tuo prof, per allenarti.", true, SchermataVisita.MATERIA),
    PassoVisita(Bersagli.TEMPO, "Il tempo", "Se ti agita, toccalo e sparisce.", true, SchermataVisita.SIMULATA_IN_CORSO),
    PassoVisita(Bersagli.STIMA, "La stima", "È una stima. Il voto vero lo dà il prof.", false, SchermataVisita.SIMULATA_RISULTATI),
)

private val PASSO_FINE = PassoVisita(null, "Ecco fatto", "Adesso Lectio lavora con i tuoi documenti veri.", false, SchermataVisita.SIMULATA_RISULTATI)

val LocalVisita = staticCompositionLocalOf<Visita?> { null }

/**
 * Segna un elemento come possibile bersaglio del riflettore. La posizione si tiene
 * sempre aggiornata (costa pochissimo): quando la visita parte è già pronta. Quando
 * tocca a lui, se è fuori schermo si fa portare in vista.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bersaglio(id: String): Modifier = composed {
    val v = LocalVisita.current
    if (v == null) {
        this
    } else {
        val porta = remember { BringIntoViewRequester() }
        val densita = LocalDensity.current
        var misura by remember { mutableStateOf(IntSize.Zero) }
        val acceso = v.attiva && (v.bersaglioTemporaneo ?: v.corrente.bersaglio)?.split('+')?.contains(id) == true
        LaunchedEffect(acceso, v.indice) {
            if (acceso) {
                // Aspetta che la pagina nuova sia entrata.
                kotlinx.coroutines.delay(350)
                // Con margine: in basso c'è il pulsante fisso (Nuovo) e in alto "Salta la visita". Senza,
                // un bersaglio in fondo alla pagina (Aggiungi del Materiale) finiva sotto il pulsante.
                val sopra = with(densita) { 96.dp.toPx() }
                val sotto = with(densita) { 170.dp.toPx() }
                porta.bringIntoView(Rect(0f, -sopra, misura.width.toFloat(), misura.height + sotto))
            }
        }
        this.bringIntoViewRequester(porta).onGloballyPositioned {
            v.bersagli[id] = it.boundsInRoot()
            misura = it.size
        }
    }
}
