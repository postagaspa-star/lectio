package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.CartaLampo
import eu.stgm.lectio.dati.DepositoStudio
import eu.stgm.lectio.dati.EsitoInCoda
import eu.stgm.lectio.dati.RispostaLampo
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.daRitentare
import kotlinx.coroutines.sync.withLock

/** Un giudizio dato su una scheda. */
data class EsitoDato(val cartaId: Long, val esito: String)

/**
 * Il Ripasso lampo in corso (aspetto.md §35.2), senza schermo né rete. L'ultimo esito
 * resta in sospeso finché non arriva il prossimo o si esce: così **Annulla** lo butta
 * senza dover chiedere niente al server.
 */
data class SessioneLampo(
    val carte: List<CartaLampo>,
    val indice: Int = 0,
    val rispostaAperta: Boolean = false,
    val apertaAlle: Long = 0,
    val inSospeso: EsitoDato? = null,
    val puoAnnullare: Boolean = false,
    val sapevo: Int = 0,
    val quasi: Int = 0,
    val nonSapevo: Int = 0,
) {
    val carta: CartaLampo? get() = carte.getOrNull(indice)
    val finita: Boolean get() = indice >= carte.size
    val fatte: Int get() = sapevo + quasi + nonSapevo
    val conteggio: String get() = "${(indice + 1).coerceAtMost(carte.size)} di ${carte.size}"
    val frazione: Float get() = if (carte.isEmpty()) 1f else indice.toFloat() / carte.size

    fun mostra(adesso: Long): SessioneLampo = if (rispostaAperta || finita) this else copy(rispostaAperta = true, apertaAlle = adesso)

    /** I tre esiti non rispondono per 300 ms: un doppio tocco su Mostra non dà un voto. */
    fun esitiAttivi(adesso: Long): Boolean = rispostaAperta && adesso - apertaAlle >= ATTESA_ESITI_MS

    /**
     * Un esito dato. Torna la sessione e quello di prima, che adesso va mandato.
     * Sull'ultima scheda manda anche questo: il ripasso è finito, come uscire.
     */
    fun esito(esito: String, adesso: Long): Pair<SessioneLampo, List<EsitoDato>> {
        val c = carta ?: return this to emptyList()
        if (!esitiAttivi(adesso)) return this to emptyList()
        val dato = EsitoDato(c.id, esito)
        val daMandare = listOfNotNull(inSospeso).toMutableList()
        val prossimo = indice + 1
        val fine = prossimo >= carte.size
        if (fine) daMandare += dato
        return conta(esito, 1).copy(
            indice = prossimo,
            rispostaAperta = false,
            apertaAlle = 0,
            inSospeso = if (fine) null else dato,
            puoAnnullare = !fine,
        ) to daMandare
    }

    private fun conta(esito: String, quanto: Int): SessioneLampo = when (esito) {
        "sapevo" -> copy(sapevo = sapevo + quanto)
        "quasi" -> copy(quasi = quasi + quanto)
        else -> copy(nonSapevo = nonSapevo + quanto)
    }

    /** Butta l'ultimo esito e riporta la sua scheda con la risposta aperta. Una volta sola. */
    fun annulla(): SessioneLampo {
        val s = inSospeso
        if (!puoAnnullare || s == null) return this
        return conta(s.esito, -1).copy(
            indice = (indice - 1).coerceAtLeast(0),
            rispostaAperta = true,
            apertaAlle = 0,
            inSospeso = null,
            puoAnnullare = false,
        )
    }

    /** Toglie la scheda corrente dal giro. */
    fun togliCorrente(): SessioneLampo {
        val c = carta ?: return this
        return copy(carte = carte.filterNot { it.id == c.id }, rispostaAperta = false, apertaAlle = 0)
    }

    /** Uscendo: quello in sospeso parte (quello fatto è salvato, aspetto.md §35.2). */
    fun uscita(): Pair<SessioneLampo, EsitoDato?> = copy(inSospeso = null, puoAnnullare = false) to inSospeso

    companion object {
        const val ATTESA_ESITI_MS = 300L
    }
}

/** Com'è andato un giro di invii. */
data class InvioLampo(
    /** Non è rimasto niente da riprovare. */
    val finito: Boolean,
    /** Il server ha rifiutato qualcosa: "Non ho salvato l'ultimo esito: riprovo". */
    val rifiutato: Boolean,
)

/**
 * Gli esiti del Ripasso lampo vanno al server in ordine, una volta sola ciascuno (§35.5).
 * Senza rete si aspetta; un esito rifiutato si riprova una volta e poi si lascia perdere.
 */
class CodaLampo(
    private val deposito: DepositoStudio,
    private val manda: suspend (Long, String) -> Esito<RispostaLampo>,
) {
    fun accoda(e: EsitoDato) = synchronized(deposito) {
        deposito.scriviCodaLampo(deposito.codaLampo() + EsitoInCoda(e.cartaId, e.esito))
        deposito.togliCartaSalvata(e.cartaId)
    }

    // Un giro alla volta per deposito: lo schermo e il lavoro in sottofondo non si accavallano.
    suspend fun invia(): InvioLampo = deposito.giroLampo.withLock {
        var rifiutato = false
        for (voce in deposito.codaLampo()) {
            val r = manda(voce.cartaId, voce.esito)
            when {
                r is Esito.Ok -> togli(voce)
                r.daRitentare -> return@withLock InvioLampo(finito = false, rifiutato = rifiutato)
                voce.rifiuti >= 1 -> togli(voce)
                else -> {
                    rifiutato = true
                    segnaRifiuto(voce)
                }
            }
        }
        InvioLampo(finito = deposito.codaLampo().isEmpty(), rifiutato = rifiutato)
    }

    private fun togli(v: EsitoInCoda) = synchronized(deposito) {
        deposito.scriviCodaLampo(deposito.codaLampo().filterNot { it.cartaId == v.cartaId && it.esito == v.esito })
    }

    private fun segnaRifiuto(v: EsitoInCoda) = synchronized(deposito) {
        deposito.scriviCodaLampo(deposito.codaLampo().map { if (it.cartaId == v.cartaId && it.esito == v.esito) it.copy(rifiuti = it.rifiuti + 1) else it })
    }
}
