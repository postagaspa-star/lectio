package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Evento
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Date, tempi e soldi scritti come li legge Andrea (aspetto.md §1, prototipo). */
object Formati {
    private val GIORNI = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")
    private val MESI = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )

    fun data(iso: String?): LocalDate? = try {
        iso?.take(10)?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    }

    /** "martedì 28 ottobre" (con l'anno solo se non è quello di [oggi]). */
    fun giorno(d: LocalDate, oggi: LocalDate = LocalDate.now()): String {
        val base = "${GIORNI[d.dayOfWeek.value - 1]} ${giornoDelMese(d)} ${MESI[d.monthValue - 1]}"
        return if (d.year != oggi.year) "$base ${d.year}" else base
    }

    /** "12 ottobre" (nelle righe del materiale). */
    fun giornoBreve(d: LocalDate, oggi: LocalDate = LocalDate.now()): String {
        val base = "${giornoDelMese(d)} ${MESI[d.monthValue - 1]}"
        return if (d.year != oggi.year) "$base ${d.year}" else base
    }

    /** "1°" il primo del mese, come si scrive in italiano. */
    private fun giornoDelMese(d: LocalDate) = if (d.dayOfMonth == 1) "1°" else d.dayOfMonth.toString()

    fun giornoOVuoto(iso: String?, oggi: LocalDate = LocalDate.now()): String = data(iso)?.let { giorno(it, oggi) } ?: ""

    /** "oggi", "domani", "tra 2 giorni", "ieri", "3 giorni fa". */
    fun traQuanto(giorni: Int): String = when {
        giorni == 0 -> "oggi"
        giorni == 1 -> "domani"
        giorni > 1 -> "tra $giorni giorni"
        giorni == -1 -> "ieri"
        else -> "${-giorni} giorni fa"
    }

    /** Nelle righe di Più avanti: "6 giorni", "domani". */
    fun giorniBreve(giorni: Int): String = when (giorni) {
        0 -> "oggi"
        1 -> "domani"
        else -> "$giorni giorni"
    }

    fun giorniTra(da: LocalDate, a: LocalDate): Int = ChronoUnit.DAYS.between(da, a).toInt()

    /** "Si azzera il 1° novembre". */
    fun siAzzera(iso: String?, oggi: LocalDate = LocalDate.now()): String? =
        data(iso)?.let { "Si azzera il ${giornoBreve(it, oggi)}" }

    /** "07:42" (sotto l'ora: minuti e secondi a due cifre); "1:02:03" oltre l'ora. */
    fun tempo(secondi: Double): String {
        val s = secondi.coerceAtLeast(0.0).toLong()
        val ore = s / 3600
        val min = (s % 3600) / 60
        val sec = s % 60
        return if (ore > 0) "%d:%02d:%02d".format(ore, min, sec) else "%02d:%02d".format(min, sec)
    }

    /** "-12:18": quanto manca. */
    fun mancante(secondi: Double): String = "-" + tempo(secondi)

    /** La durata di un capitolo: "5:01". */
    fun durataBreve(secondi: Double): String {
        val s = secondi.coerceAtLeast(0.0).roundToLong()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** "mancano 8 min" (arrotondato in su: "mancano 1 min" fino all'ultimo). */
    fun mancanoMinuti(secondi: Double): String {
        val m = ceil(secondi.coerceAtLeast(0.0) / 60.0).toInt().coerceAtLeast(1)
        return if (m == 1) "manca 1 min" else "mancano $m min"
    }

    /** "1,0×", "1,2×". */
    fun velocita(v: Float): String = String.format(Locale.ITALY, "%.1f×", v)

    /** "412.000". */
    fun numero(n: Long): String = String.format(Locale.ITALY, "%,d", n)

    /** "63%". */
    fun percentuale(p: Int): String = "$p%"

    /**
     * I soldi (aspetto.md e prototipo): "0,25 €", e senza decimali quando sono
     * interi: "2 €", "0 €".
     */
    fun euro(valore: Double): String {
        val centesimi = (valore * 100).roundToLong()
        if (centesimi % 100 == 0L) return "${centesimi / 100} €"
        val segno = if (centesimi < 0) "-" else ""
        val a = abs(centesimi)
        return "$segno${a / 100},${"%02d".format(a % 100)} €"
    }

    /** "circa 0,4 cent a foto": i centesimi con un decimale. */
    fun cent(valore: Double): String {
        val decimi = (valore * 1000).roundToInt()
        return if (decimi % 10 == 0) "${decimi / 10} cent" else "${decimi / 10},${decimi % 10} cent"
    }

    /** "Verifica sull'Impero romano", "Interrogazione su equazioni": l'articolo se c'è. */
    fun titoloEvento(e: Evento): String {
        val cosa = if (e.orale) "Interrogazione" else "Verifica"
        val arg = e.argomento.trim()
        if (arg.isEmpty()) return cosa
        return "$cosa ${preposizioneSu(arg)}"
    }

    /** su + articolo: "sull'Impero", "sulla rivoluzione", "sui Romani"; senza articolo "su …". */
    fun preposizioneSu(argomento: String): String {
        val minuscolo = argomento.lowercase(Locale.ITALIAN)
        val articoli = listOf("il " to "sul ", "lo " to "sullo ", "la " to "sulla ", "i " to "sui ", "gli " to "sugli ", "le " to "sulle ")
        if (minuscolo.startsWith("l'") || minuscolo.startsWith("l’")) return "sull'" + argomento.drop(2)
        for ((art, prep) in articoli) {
            if (minuscolo.startsWith(art)) return prep + argomento.drop(art.length)
        }
        return "su $argomento"
    }

    /** "Verifica · lunedì 3 novembre" nelle righe dei prossimi impegni. */
    fun tipoEvento(e: Evento): String = if (e.orale) "Interrogazione" else "Verifica"

    /** "scritta" / "orale" sotto il titolo della prossima. */
    fun tipoBreve(e: Evento): String = if (e.orale) "orale" else "scritta"
}
