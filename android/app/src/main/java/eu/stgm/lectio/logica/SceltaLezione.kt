package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Materiale
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/** Uno spunto a pillola sotto le indicazioni: aggiunge o toglie una frase (aspetto.md §6). */
data class Spunto(val etichetta: String, val frase: String)

/** Le indicazioni di Andrea per una lezione (contratto §16.1: 0-600 caratteri). */
object Indicazioni {
    const val MASSIMO = 600

    /** Le sei pillole del telefono, con le frasi generiche della guida. */
    val SPUNTI = listOf(
        Spunto("Da zero", "Spiegalo partendo da zero, come se non l'avessi mai studiato."),
        Spunto("Date e nomi", "Insisti su date e nomi."),
        Spunto("Confronta", "Confronta gli autori tra loro: cosa cambia da uno all'altro."),
        Spunto("Descrivi le opere", "Descrivi bene le opere, come se le avessi davanti."),
        Spunto("Più difficili", "Fammi domande più difficili di quelle del prof."),
        Spunto("Esempi", "Usa tanti esempi concreti."),
    )

    fun contiene(testo: String, frase: String): Boolean = testo.contains(frase)

    /**
     * Il tocco su uno spunto: se la frase c'è la toglie (e gli spazi doppi rimasti),
     * altrimenti la aggiunge in fondo. Una frase che non ci sta nei 600 caratteri non
     * si aggiunge a metà: il testo resta com'è.
     */
    fun alterna(testo: String, frase: String): String {
        if (contiene(testo, frase)) {
            return testo.replace(frase, "").replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" *\\n *"), "\n").trim()
        }
        val base = testo.trim()
        val nuovo = if (base.isEmpty()) frase else "$base $frase"
        return if (nuovo.length > MASSIMO) testo else nuovo
    }

    /** "12 / 600". */
    fun contatore(testo: String): String = "${testo.length} / $MASSIMO"

    /** Nel riepilogo: le prime 140 lettere, o cosa succede se non ce ne sono. */
    fun breve(testo: String): String {
        val t = testo.trim()
        if (t.isEmpty()) return "Nessuna: segue il materiale e la scheda del prof"
        return if (t.length > 140) t.take(140).trimEnd() + "…" else t
    }
}

/** Le due famiglie di "Che cos'è" (contratto §16.4). */
enum class Famiglia(val titolo: String, val frase: String, val tipi: List<TipoMateriale>) {
    DA_STUDIARE("Da studiare", "Lectio lo spiega e ci fa le domande", listOf(TipoMateriale.APPUNTI, TipoMateriale.LIBRO)),
    DEL_PROF("Già corretta dal prof", "Lectio impara come chiede e corregge", listOf(TipoMateriale.VERIFICA, TipoMateriale.INTERROGAZIONE)),
    ;

    companion object {
        fun di(tipo: TipoMateriale): Famiglia? = entries.firstOrNull { tipo in it.tipi }
    }
}

/** Il materiale nella pagina Nuova lezione: gruppi, ricerca, righe, riepilogo (aspetto.md §6). */
object SceltaMateriale {
    /** Le verifiche e le interrogazioni del prof (il resto è "Appunti e libro"). */
    fun delProf(m: Materiale): Boolean = m.tipo == "verifica" || m.tipo == "interrogazione"

    /** Si spunta solo il materiale letto; quello in errore non compare. */
    fun visibile(m: Materiale): Boolean = m.stato != "errore"
    fun pronto(m: Materiale): Boolean = m.stato == "pronto"

    /** Minuscole e senza accenti: "Società" → "societa". */
    fun normalizza(t: String): String =
        Normalizer.normalize(t.lowercase(Locale.ITALIAN), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Il filtro di "Cerca nel materiale": per titolo, senza badare ad accenti e maiuscole. */
    fun filtra(elenco: List<Materiale>, cerca: String): List<Materiale> {
        val f = normalizza(cerca.trim())
        if (f.isEmpty()) return elenco
        return elenco.filter { normalizza(it.titolo).contains(f) }
    }

    fun nomeTipo(tipo: String): String = when (tipo) {
        "verifica" -> "Verifica"
        "interrogazione" -> "Interrogazione"
        "appunti" -> "Appunti"
        "libro" -> "Libro"
        else -> "Altro"
    }

    /** "Appunti · foto · 4 novembre" sotto il titolo della riga. */
    fun sotto(m: Materiale, oggi: LocalDate = LocalDate.now()): String = listOfNotNull(
        nomeTipo(m.tipo),
        when (m.origine) {
            "foto" -> "foto"
            "file" -> "file"
            "scritto" -> "scritto"
            else -> null
        },
        // Spazio che non va a capo: sui telefoni stretti "24 ottobre" resta intero.
        Formati.data(m.data)?.let { Formati.giornoBreve(it, oggi).replace(' ', ' ') },
    ).joinToString(" · ")

    /** MATERIALE nel riepilogo: "Mirone e il Discobolo" o "3 materiali: A, B e un altro". */
    fun riepilogo(scelti: List<Materiale>): String = when (scelti.size) {
        0 -> "Scegli almeno un materiale"
        1 -> scelti[0].titolo
        else -> {
            val resto = scelti.size - 2
            "${scelti.size} materiali: " + scelti.take(2).joinToString(", ") { it.titolo } +
                when (resto) {
                    0 -> ""
                    1 -> " e un altro"
                    else -> " e altri $resto"
                }
        }
    }

    /** "3 materiali", "1 materiale". */
    fun quanti(n: Int): String = if (n == 1) "1 materiale" else "$n materiali"
}

/** PER: la verifica o l'interrogazione per cui si prepara la lezione. */
object Per {
    /** La pillola: "Interrogazione di lunedì 10 novembre". */
    fun pillola(e: Evento, oggi: LocalDate = LocalDate.now()): String = "${Formati.tipoEvento(e)} di ${Formati.giornoOVuoto(e.data, oggi)}"

    /** Nel riepilogo: "L'interrogazione di lunedì 10 novembre · domande obbligatorie". */
    fun riepilogo(e: Evento?, saltabili: Boolean, oggi: LocalDate = LocalDate.now()): String {
        val quando = e?.let { (if (it.orale) "L'interrogazione" else "La verifica") + " di ${Formati.giornoOVuoto(it.data, oggi)}" } ?: "Nessuna data"
        return "$quando · domande " + if (saltabili) "che si possono saltare" else "obbligatorie"
    }
}

/**
 * Il materiale che parla di un argomento, come il server (contratto §16.2). Serve alla
 * modalità esempio della visita, che non chiama il server.
 */
object ParoleArgomento {
    private val VUOTE = setOf(
        "della", "delle", "degli", "dello", "dalla", "dalle", "dagli", "dallo", "nella", "nelle", "negli", "nello",
        "sulla", "sulle", "sugli", "sullo", "alla", "alle", "agli", "allo", "come", "tutta", "tutto", "tutti", "tutte",
        "sono", "anche", "questo", "questa", "quello", "quella", "quando", "perche", "dove", "molto", "dopo", "prima",
        "loro", "suoi", "sue", "fino", "verso", "contro", "senza", "sopra", "sotto", "tra", "fra",
    )

    /** La radice di una parola: senza le vocali finali (scultura/sculture → scultur). */
    fun radice(parola: String): String = if (parola.length > 4) parola.trimEnd('a', 'e', 'i', 'o') else parola

    fun parole(testo: String): List<String> =
        SceltaMateriale.normalizza(testo).split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in VUOTE }

    /** Gli id suggeriti tra il materiale pronto. */
    fun suggeriti(argomento: String, materiale: List<Materiale>): List<Long> {
        val pronti = materiale.filter(SceltaMateriale::pronto)
        val radici = parole(argomento).map(::radice).distinct()
        if (radici.isEmpty() || pronti.isEmpty()) return emptyList()
        fun punti(m: Materiale, r: String): Int {
            val titolo = parole(m.titolo).map(::radice)
            if (r in titolo) return 2
            val testo = parole(m.testo.orEmpty()).map(::radice)
            return if (testo.count { it == r } >= 2) 1 else 0
        }
        // Le parole che stanno quasi dappertutto non distinguono niente.
        val comuni = if (pronti.size >= 4) radici.filter { r -> pronti.count { punti(it, r) > 0 } > pronti.size * 0.6 }.toSet() else emptySet()
        val contano = radici.filterNot { it in comuni }.ifEmpty { radici }
        val soglia = if (radici.size == 1) 1 else 2
        return pronti.filter { m -> contano.sumOf { punti(m, it) } >= soglia }.map { it.id }
    }
}
