package eu.stgm.lectio.ui

/** Le quattro voci della barra in basso (aspetto.md §2). */
enum class Scheda(val etichetta: String) {
    OGGI("Oggi"),
    MATERIE("Materie"),
    LEZIONI("Lezioni"),
    GEMINI("Per Gemini"),
}

/** Le pagine che si aprono sopra una scheda. */
sealed interface Pagina {
    data class Materia(val id: Long) : Pagina
    data class ModificaScheda(val materiaId: Long) : Pagina
    data class TestoMateriale(val id: Long) : Pagina
    data class NuovaLezione(val materiaId: Long, val eventoId: Long? = null) : Pagina
    data class Preparazione(val lezioneId: Long) : Pagina
    /** [domandaId]: aperto da "Da ripassare", fermo su quella domanda. */
    data class Lettore(val lezioneId: Long, val domandaId: Long? = null) : Pagina
    data class ComeAndata(val eventoId: Long) : Pagina
    data object Prossime : Pagina
    data object Impostazioni : Pagina
    data object Abbinamento : Pagina
}

/**
 * Dove si trova Andrea: una scheda e, sopra, una pila di pagine. Indietro chiude
 * l'ultima pagina; da una scheda torna a Oggi; da Oggi esce.
 */
data class Navigazione(val scheda: Scheda = Scheda.OGGI, val pagine: List<Pagina> = emptyList()) {
    val inCima: Pagina? get() = pagine.lastOrNull()

    fun scegli(scheda: Scheda) = Navigazione(scheda)

    fun apri(pagina: Pagina): Navigazione =
        if (inCima == pagina) this else copy(pagine = pagine.filterNot { it == pagina } + pagina)

    /** Sostituisce la pagina in cima (Nuova lezione che diventa In preparazione). */
    fun sostituisci(pagina: Pagina): Navigazione = copy(pagine = pagine.dropLast(1).filterNot { it == pagina } + pagina)

    /** null = si esce dall'app. */
    fun indietro(): Navigazione? = when {
        pagine.isNotEmpty() -> copy(pagine = pagine.dropLast(1))
        scheda != Scheda.OGGI -> Navigazione(Scheda.OGGI)
        else -> null
    }

    /** Quante pagine ci sono: per scegliere il verso dell'animazione (avanti o indietro). */
    val profondita: Int get() = pagine.size

    /** In testo, per sopravvivere alla rotazione e alla chiusura da parte di Android. */
    fun codifica(): String = (listOf(scheda.name) + pagine.map {
        when (it) {
            is Pagina.Materia -> "materia:${it.id}"
            is Pagina.ModificaScheda -> "scheda:${it.materiaId}"
            is Pagina.TestoMateriale -> "materiale:${it.id}"
            is Pagina.NuovaLezione -> "nuova:${it.materiaId}:${it.eventoId ?: ""}"
            is Pagina.Preparazione -> "preparazione:${it.lezioneId}"
            is Pagina.Lettore -> "lettore:${it.lezioneId}:${it.domandaId ?: ""}"
            is Pagina.ComeAndata -> "esito:${it.eventoId}"
            Pagina.Prossime -> "prossime"
            Pagina.Impostazioni -> "impostazioni"
            Pagina.Abbinamento -> "abbinamento"
        }
    }).joinToString("|")

    companion object {
        fun decodifica(testo: String?): Navigazione {
            val parti = testo?.split('|').orEmpty()
            val scheda = Scheda.entries.firstOrNull { it.name == parti.firstOrNull() } ?: Scheda.OGGI
            val pagine = parti.drop(1).mapNotNull { p ->
                val pezzi = p.split(':')
                val n = pezzi.getOrNull(1)?.toLongOrNull()
                when (pezzi[0]) {
                    "materia" -> n?.let { Pagina.Materia(it) }
                    "scheda" -> n?.let { Pagina.ModificaScheda(it) }
                    "materiale" -> n?.let { Pagina.TestoMateriale(it) }
                    "nuova" -> n?.let { Pagina.NuovaLezione(it, pezzi.getOrNull(2)?.toLongOrNull()) }
                    "preparazione" -> n?.let { Pagina.Preparazione(it) }
                    "lettore" -> n?.let { Pagina.Lettore(it, pezzi.getOrNull(2)?.toLongOrNull()) }
                    "esito" -> n?.let { Pagina.ComeAndata(it) }
                    "prossime" -> Pagina.Prossime
                    "impostazioni" -> Pagina.Impostazioni
                    "abbinamento" -> Pagina.Abbinamento
                    else -> null
                }
            }
            return Navigazione(scheda, pagine)
        }
    }
}
