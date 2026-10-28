package eu.stgm.lectio.visita

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Le schermate che la visita apre da sé (con i dati d'esempio). */
enum class SchermataVisita { OGGI, MATERIA, FOGLIO_AGGIUNGI, NUOVA_LEZIONE, LETTORE }

/**
 * Un passo: l'elemento da illuminare ([bersaglio], null = nessuno), il titolo
 * corto, UNA frase, e se si va avanti leggendo ("Avanti") o facendo l'azione vera.
 */
data class PassoVisita(
    val bersaglio: String?,
    val titolo: String,
    val frase: String,
    val pratico: Boolean,
    val schermata: SchermataVisita,
)

/** Gli id dei bersagli: le schermate li segnano con [bersaglio]. */
object Bersagli {
    const val PROSSIMA = "prossima"
    const val PILLOLA_MATERIA = "pillola-materia"
    const val SCHEDA_PROF = "scheda-prof"
    const val AGGIUNGI = "aggiungi"
    const val TESTO_GEMINI = "testo-gemini"
    const val NUOVA_LEZIONE = "nuova-lezione"
    const val DURATA = "durata"
    const val PREPARA = "prepara"
    /** Il passo 8: scrivi la risposta e premi Invia (aspetto.md §12, v1.1). */
    const val SENTI_RISPOSTA = "invia-risposta"

    /** Durata e costo: il binario e il pulsante insieme. */
    const val DURATA_E_COSTO = "durata+prepara"
}

/**
 * La visita guidata (aspetto.md §12): si impara sulle schermate vere, coi dati
 * d'esempio, facendo le cose. "Indietro" sempre (tranne al primo passo), "Avanti"
 * solo nei passi da leggere, "Salta la visita" sempre. Nei passi pratici si va
 * avanti solo facendo l'azione giusta.
 */
class Visita {

    val passi = listOf(
        PassoVisita(Bersagli.PROSSIMA, "La prossima verifica", "Qui c'è sempre la prossima verifica e la sua lezione.", false, SchermataVisita.OGGI),
        PassoVisita(Bersagli.PILLOLA_MATERIA, "Apri la materia", "Tocca Storia per aprire la sua pagina.", true, SchermataVisita.OGGI),
        PassoVisita(Bersagli.SCHEDA_PROF, "Cosa chiede il prof", "L'ho scritta io dalle tue verifiche. Puoi correggerla.", false, SchermataVisita.MATERIA),
        PassoVisita(Bersagli.AGGIUNGI, "Aggiungi una verifica", "Tocca Aggiungi per caricare una verifica corretta.", true, SchermataVisita.MATERIA),
        PassoVisita(Bersagli.TESTO_GEMINI, "Testo per Gemini", "Copialo, dallo a Gemini con la foto, poi manda qui il file.", false, SchermataVisita.FOGLIO_AGGIUNGI),
        PassoVisita(Bersagli.NUOVA_LEZIONE, "Nuova lezione", "Tocca Nuova lezione per prepararne una.", true, SchermataVisita.MATERIA),
        PassoVisita(Bersagli.DURATA_E_COSTO, "Durata e costo", "Scegli quanto dura. Il costo lo vedi prima.", false, SchermataVisita.NUOVA_LEZIONE),
        PassoVisita(Bersagli.SENTI_RISPOSTA, "La domanda", "Scrivi la tua risposta e premi Invia: te la correggo.", true, SchermataVisita.LETTORE),
        PassoVisita(null, "Ecco fatto", "Adesso Lectio lavora con le tue lezioni vere.", false, SchermataVisita.LETTORE),
    )

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

    fun inizia() {
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
        if (!attiva || !corrente.pratico || corrente.bersaglio != id) return false
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

    /** Il rettangolo da illuminare (i passi con due bersagli prendono l'unione). */
    fun rettangolo(): Rect? {
        val id = corrente.bersaglio ?: return null
        val parti = id.split('+').mapNotNull { bersagli[it] }
        if (parti.isEmpty()) return null
        return parti.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    }
}

val LocalVisita = staticCompositionLocalOf<Visita?> { null }

/**
 * Segna un elemento come possibile bersaglio del riflettore. La posizione si tiene
 * sempre aggiornata (costa pochissimo): quando la visita parte è già pronta.
 */
fun Modifier.bersaglio(id: String): Modifier = composed {
    val v = LocalVisita.current
    if (v == null) this else this.onGloballyPositioned { v.bersagli[id] = it.boundsInRoot() }
}
