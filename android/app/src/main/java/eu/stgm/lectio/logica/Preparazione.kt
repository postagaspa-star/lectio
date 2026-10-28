package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Lezione

enum class StatoPasso { FATTO, IN_CORSO, DA_FARE }

data class PassoPreparazione(val testo: String, val stato: StatoPasso)

/**
 * Le quattro fasi di "In preparazione" (aspetto.md §6) dalle cinque del server
 * (contratto §9: lettura, piano, scrittura, voce, montaggio). Lettura e piano sono
 * "Leggo il materiale" (il piano lo fa un modello piccolo in pochi secondi).
 */
object Preparazione {
    private val ORDINE = listOf("lettura", "piano", "scrittura", "voce", "montaggio")

    private fun indicePasso(fase: String?): Int = when (fase) {
        "lettura", "piano" -> 0
        "scrittura" -> 1
        "voce" -> 2
        "montaggio" -> 3
        else -> 0
    }

    fun passi(lezione: Lezione): List<PassoPreparazione> {
        val a = lezione.avanzamento
        val tutti = lezione.pronta
        val corrente = if (tutti) 4 else if (lezione.stato == "in_coda" || lezione.annullata) -1 else indicePasso(a?.fase)
        val voce = if (a?.fase == "voce" && a.totale > 0) "Registro la voce · ${a.fatto} di ${a.totale}" else "Registro la voce"
        val testi = listOf("Leggo il materiale", "Scrivo la lezione", voce, "Monto l'audio")
        return testi.mapIndexed { i, t ->
            PassoPreparazione(
                t,
                when {
                    tutti || i < corrente -> StatoPasso.FATTO
                    i == corrente && !lezione.fallita -> StatoPasso.IN_CORSO
                    else -> StatoPasso.DA_FARE
                },
            )
        }
    }

    /**
     * Quanto è fatto, per la barra: ogni fase ha il suo pezzo; scrittura e voce
     * avanzano col loro "n di m".
     */
    fun frazione(lezione: Lezione): Float {
        if (lezione.pronta) return 1f
        if (lezione.stato == "in_coda" || lezione.annullata) return 0f
        val a = lezione.avanzamento ?: return 0.02f
        val pezzi = mapOf("lettura" to (0f to 0.08f), "piano" to (0.08f to 0.15f), "scrittura" to (0.15f to 0.5f), "voce" to (0.5f to 0.92f), "montaggio" to (0.92f to 1f))
        val (da, a2) = pezzi[a.fase] ?: return 0.02f
        val dentro = if (a.totale > 0) (a.fatto.toFloat() / a.totale).coerceIn(0f, 1f) else 0.3f
        return (da + (a2 - da) * dentro).coerceIn(0f, 0.99f)
    }

    /** La riga breve per la notifica e per Oggi: "Registro la voce · 3 di 7". */
    fun frase(lezione: Lezione): String = when {
        lezione.annullata -> "Annullata"
        // In coda: la frase del server se c'è (sa quante lezioni ci sono prima).
        lezione.stato == "in_coda" -> lezione.avanzamento?.frase?.takeIf { it.isNotBlank() } ?: "In coda: parte dopo quella in preparazione"
        lezione.avanzamento?.frase != null -> lezione.avanzamento.frase
        else -> passi(lezione).firstOrNull { it.stato == StatoPasso.IN_CORSO }?.testo ?: "In preparazione"
    }

    fun ordineFase(fase: String?): Int = ORDINE.indexOf(fase)
}
