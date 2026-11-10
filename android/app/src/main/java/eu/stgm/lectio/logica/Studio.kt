package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.BloccoSchema
import eu.stgm.lectio.dati.ContenutoSchema
import eu.stgm.lectio.dati.DomandaSimulata
import eu.stgm.lectio.dati.EsitoDomandaSimulata
import eu.stgm.lectio.dati.EsitoSimulata
import eu.stgm.lectio.dati.Fonte
import eu.stgm.lectio.dati.RiassuntoBreve
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Simulata
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** Un pezzo di testo: normale o in grassetto. */
data class PezzoTesto(val testo: String, val forte: Boolean)

/** `**parola**` e nient'altro (contratto §18). Due asterischi spaiati non si vedono mai. */
object Grassetto {
    fun pezzi(testo: String): List<PezzoTesto> {
        val parti = testo.split("**")
        val separatori = parti.size - 1
        return parti.mapIndexedNotNull { i, p ->
            if (p.isEmpty()) return@mapIndexedNotNull null
            // L'ultimo pezzo dopo un "**" senza compagno resta normale.
            PezzoTesto(p, i % 2 == 1 && (i < parti.lastIndex || separatori % 2 == 0))
        }
    }

    fun togli(testo: String): String = testo.replace("**", "")
}

/** Da dove viene un blocco: "Libro, pag. 42 · Verifica del 12/10", al massimo due poi "+1" (aspetto.md §33.4). */
object SegniFonte {
    fun etichette(ids: List<String>, fonti: Map<String, Fonte>): List<Fonte> =
        ids.mapNotNull { fonti[it] }.distinctBy { it.etichetta }

    fun riga(elenco: List<Fonte>): String? {
        if (elenco.isEmpty()) return null
        val base = elenco.take(2).joinToString(" · ") { it.etichetta }
        return if (elenco.size > 2) "$base +${elenco.size - 2}" else base
    }

    /** Gli indici dei blocchi che mostrano il segno: con più blocchi di fila uguali, solo l'ultimo. */
    fun visibili(blocchi: List<BloccoSchema>): Set<Int> = blocchi.indices.filter { i ->
        val b = blocchi[i]
        b.fonti.isNotEmpty() && blocchi.getOrNull(i + 1)?.fonti?.toSet() != b.fonti.toSet()
    }.toSet()

    /** Nella pillola sotto il blocco: l'etichetta, o "2 fonti". */
    fun pillola(elenco: List<Fonte>): String? = when {
        elenco.isEmpty() -> null
        elenco.size == 1 -> elenco[0].etichetta
        else -> "${elenco.size} fonti"
    }
}

/** I testi attorno agli schemi. */
object TestiSchema {
    const val MASSIMO_PASSAGGIO = 4000
    const val MASSIMO_DOMANDA = 300

    /** I blocchi su cui si può chiedere "Non ho capito". */
    val SPIEGABILI = setOf("paragrafo", "punti", "passaggi", "definizione", "prof")

    fun nomeForma(forma: String): String? = when (forma) {
        "capitoli" -> "A capitoli"
        "schede" -> "Schede per opera"
        else -> null
    }

    fun nomeLivello(livello: String): String = when (livello) {
        "essenziale" -> "Essenziale"
        "approfondito" -> "Approfondito"
        else -> "Completo"
    }

    /** "A capitoli · Completo" (`auto` non si scrive). */
    fun sottoPronto(forma: String, livello: String): String = listOfNotNull(nomeForma(forma), nomeLivello(livello)).joinToString(" · ")

    /** Sotto il titolo di una riga dell'elenco; per i lavori in corso la frase del server. */
    fun sottoRiga(s: Schema): String = when (s.stato) {
        "pronto" -> sottoPronto(s.forma, s.livello)
        "in_coda" -> "In coda"
        "in_preparazione" -> s.frase?.takeIf { it.isNotBlank() } ?: "Lo sto preparando"
        "errore" -> "Non è riuscito"
        "annullato" -> "Annullato"
        else -> ""
    }

    /** "A capitoli · Completo · 8 parti · 12 min di lettura". */
    fun sottoTitolo(s: Schema): String {
        val c = s.contenuto
        val parti = c?.sezioni?.size ?: 0
        return listOfNotNull(
            sottoPronto(c?.forma ?: s.forma, s.livello),
            parti.takeIf { it > 0 }?.let { if (it == 1) "1 parte" else "$it parti" },
            c?.let { "${minutiLettura(it)} min di lettura" },
        ).joinToString(" · ")
    }

    private fun parole(t: String?): Int = t?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.size ?: 0

    private fun paroleBlocco(b: BloccoSchema): Int =
        parole(b.titolo) + parole(b.testo) + parole(b.termine) + b.voci.sumOf(::parole) +
            b.righe.sumOf { r -> r.sumOf(::parole) }

    /** Duecento parole al minuto, almeno uno. */
    fun minutiLettura(c: ContenutoSchema): Int {
        val totale = c.sezioni.sumOf { s -> parole(s.titolo) + s.blocchi.sumOf(::paroleBlocco) }
        return ceil(totale / 200.0).toInt().coerceAtLeast(1)
    }

    /** Il `passaggio` da mandare a "Non ho capito": il testo del blocco, le voci una per riga. */
    fun passaggio(b: BloccoSchema): String {
        val testo = when (b.tipo) {
            "punti", "passaggi" -> b.voci.joinToString("\n")
            "definizione" -> listOfNotNull(b.termine, b.testo).joinToString(": ")
            else -> b.testo.orEmpty()
        }
        return Grassetto.togli(testo).take(MASSIMO_PASSAGGIO)
    }

    /** Al massimo cinque cose mancanti, poi "e altri 3". */
    fun mancanti(elenco: List<String>): Pair<List<String>, String?> {
        val resto = elenco.size - 5
        return elenco.take(5) to resto.takeIf { it > 0 }?.let { if (it == 1) "e un altro" else "e altri $it" }
    }

    fun haNote(s: Schema): Boolean =
        !s.nota.isNullOrBlank() || s.contenuto?.copertura?.mancanti.orEmpty().isNotEmpty() || s.contenuto?.avvisi.orEmpty().isNotEmpty()
}

/** Le frasi-pillola di "Le tue indicazioni" (aspetto.md §32) e i tre spunti di §34. */
object SpuntiStudio {
    val SCHEMA = listOf(
        Spunto("Tante immagini", "Metti tante immagini, quando aiutano a capire."),
        Spunto("Da zero", "Spiegalo partendo da zero, come se non l'avessi mai studiato."),
        Spunto("Date e nomi", "Insisti su date e nomi."),
        Spunto("Confronta", "Confronta gli autori tra loro: cosa cambia da uno all'altro."),
        Spunto("Descrivi le opere", "Descrivi bene le opere, come se le avessi davanti."),
        Spunto("Esempi", "Usa tanti esempi concreti."),
        Spunto("Come il prof", "Dai più spazio a quello che il prof chiede di solito."),
    )

    val NON_HO_CAPITO = listOf(
        Spunto("Più semplice", "Spiegalo in modo più semplice."),
        Spunto("Un esempio", "Fammi un esempio."),
        Spunto("Una parola", "Cosa vuol dire questa parola?"),
    )
}

/** Le tre forme di un riassunto (contratto §26, aspetto.md §41.2). */
enum class FormaRiassunto(val codice: String, val nome: String, val frase: String) {
    PUNTI("punti", "Per punti", "Una cosa per riga"),
    DISCORSIVO("discorsivo", "Discorsivo", "Da leggere di seguito"),
    MISTO("misto", "Via di mezzo", "Un po' di testo e i punti da ricordare"),
    ;

    companion object {
        fun di(codice: String?): FormaRiassunto = entries.firstOrNull { it.codice == codice } ?: MISTO
    }
}

object TestiRiassunto {
    /** A destra della riga: "Pronto", "Lo sto scrivendo", "Si è fermato"; null = si mostra il prezzo. */
    fun statoForma(stato: String?): String? = when (stato) {
        "pronto" -> "Pronto"
        "in_coda", "in_preparazione" -> "Lo sto scrivendo"
        "errore" -> "Si è fermato"
        else -> null
    }

    /** L'ultimo fatto: "Apri il riassunto" porta lì. */
    fun ultimo(fatti: List<RiassuntoBreve>): RiassuntoBreve? = fatti.maxByOrNull { it.id }

    /** Duecento parole al minuto, arrotondato in su, almeno uno. */
    fun minuti(parole: Int): Int = ceil(parole / 200.0).toInt().coerceAtLeast(1)

    /** "480 parole · 3 min di lettura". */
    fun sottoTitolo(parole: Int): String = "$parole parole · ${minuti(parole)} min di lettura"
}

/** Quante schede e quanti minuti (aspetto.md §31). */
object TestiLampo {
    /** Venti secondi a scheda, arrotondati in su. */
    fun minuti(schede: Int): Int = ceil(schede * 20 / 60.0).toInt().coerceAtLeast(1)

    fun schede(n: Int): String = if (n == 1) "1 scheda" else "$n schede"

    fun riga(schede: Int, minuti: Int): String = "${schede(schede)} · circa $minuti min"

    fun domani(n: Int): String = "Domani ${schede(n)}"

    /** "12 schede in 3 minuti". */
    fun durata(schede: Int, millisecondi: Long): String {
        val m = ceil(millisecondi / 60_000.0).toInt().coerceAtLeast(1)
        return "${schede(schede)} in " + if (m == 1) "1 minuto" else "$m minuti"
    }
}

/** Un numero senza zeri inutili e con la virgola: 6.5 → "6,5", 6.0 → "6". */
fun numeroBreve(v: Double): String {
    val decimi = Math.round(v * 10)
    return if (decimi % 10 == 0L) (decimi / 10).toString() else String.format(Locale.ITALY, "%.1f", v)
}

/** Il tempo mostrato mentre si fa la verifica: solo minuti, mai secondi (aspetto.md §36.3). */
object TempoSimulata {
    fun mancano(minuti: Int, secondi: Int): Int = minuti * 60 - secondi

    fun etichetta(minuti: Int, secondi: Int): String {
        val resto = mancano(minuti, secondi)
        return when {
            resto <= 0 -> "+${floor(-resto / 60.0).toInt().coerceAtLeast(1)} min"
            resto <= 60 -> "meno di 1 min"
            else -> "${ceil(resto / 60.0).toInt()} min"
        }
    }

    /** Quando dire "Mancano 5 minuti": una volta sola, da 5 minuti in giù, a tempo non finito. */
    fun tempoDiAvviso(minuti: Int, secondi: Int): Boolean = mancano(minuti, secondi) in 1..300

    fun finito(minuti: Int, secondi: Int): Boolean = mancano(minuti, secondi) <= 0

    /** "Consegnata dopo 56 minuti su 50": solo se è andato oltre, e con i secondi contati qui. */
    fun oltre(minuti: Int, secondi: Int?): String? {
        if (secondi == null || secondi <= minuti * 60) return null
        return "Consegnata dopo ${ceil(secondi / 60.0).toInt()} minuti su $minuti"
    }
}

object TestiSimulata {
    val DURATE = listOf(15, 30, 50, 60)

    fun sottoRiga(s: Simulata): String = when (s.stato) {
        "in_coda" -> "In coda"
        "in_preparazione" -> s.frase?.takeIf { it.isNotBlank() } ?: "La sto preparando"
        "pronta" -> "Pronta · ${s.minuti} min"
        "in_corso" -> "A metà"
        "in_correzione" -> "La sto correggendo"
        "corretta" -> "Corretta" + (s.esito?.votoStimato?.let { " · stima circa ${numeroBreve(it)}" } ?: "")
        "errore" -> "Non è riuscita"
        "annullata" -> "Annullata"
        else -> ""
    }

    /** "8 domande · 100 punti · 50 minuti". */
    fun intestazione(s: Simulata): String {
        val n = s.domande.size
        val punti = s.domande.sumOf { it.punti }
        return "${if (n == 1) "1 domanda" else "$n domande"} · ${numeroBreve(punti)} punti · ${s.minuti} minuti"
    }

    private fun scritta(testo: String?) = !testo.isNullOrBlank()

    fun quanteScritte(domande: List<DomandaSimulata>, risposte: Map<String, String>): Int = domande.count { scritta(risposte[it.n.toString()]) }

    /** "5 scritte su 8 · 3 vuote". */
    fun rigaConsegna(domande: List<DomandaSimulata>, risposte: Map<String, String>): String {
        val fatte = quanteScritte(domande, risposte)
        val vuote = domande.size - fatte
        val base = "$fatte ${if (fatte == 1) "scritta" else "scritte"} su ${domande.size}"
        return if (vuote == 0) base else "$base · $vuote ${if (vuote == 1) "vuota" else "vuote"}"
    }

    /** "62 punti su 100 · la sufficienza è a 58" (la soglia solo se c'è). */
    fun rigaPunti(e: EsitoSimulata): String =
        "${numeroBreve(e.punti)} punti su ${numeroBreve(e.puntiTotali)}" + (e.soglia?.let { " · la sufficienza è a ${numeroBreve(it)}" } ?: "")

    /** "8 su 10"; niente per una domanda che non si è potuta correggere. */
    fun puntiDomanda(e: EsitoDomandaSimulata, d: DomandaSimulata?): String? {
        if (e.esito == "non_corretta") return null
        return "${numeroBreve(e.punti ?: 0.0)} su ${numeroBreve(d?.punti ?: 0.0)}"
    }

    fun parolaEsito(esito: String): String = when (esito) {
        "giusta" -> "Giusta"
        "parziale" -> "Quasi"
        "sbagliata" -> "Da rivedere"
        else -> "Confronta tu"
    }

    /** "3 domande da rifare sono già in Ripasso lampo." */
    fun daRifare(n: Int): String = if (n == 1) "1 domanda da rifare è già in Ripasso lampo." else "$n domande da rifare sono già in Ripasso lampo."

    fun formato(verifiche: Int): String = if (verifiche == 1) "Come la tua verifica" else "Come le tue $verifiche verifiche"

    /** Le risposte da mostrare: quelle del telefono vincono solo se il server non le ha ancora. */
    fun unisci(server: Map<String, String>, locali: Map<String, String>?, daInviare: Boolean): Map<String, String> =
        if (daInviare && locali != null) server + locali else server.ifEmpty { locali.orEmpty() }
}
