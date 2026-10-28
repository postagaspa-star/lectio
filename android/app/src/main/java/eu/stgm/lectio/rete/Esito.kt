package eu.stgm.lectio.rete

/**
 * Com'è andata una chiamata al server. Ogni fallimento porta già la frase da
 * mostrare, in italiano semplice: le schermate non traducono niente.
 */
sealed interface Esito<out T> {
    data class Ok<T>(val dato: T) : Esito<T>

    /** Il server ha risposto di no. [messaggio] è il suo `errore` o una frase nostra. */
    data class Errore(
        val messaggio: String,
        val codice: String? = null,
        val stato: Int = 0,
    ) : Esito<Nothing>

    /** Rete assente, server spento o tunnel che non trova il server. */
    data object Irraggiungibile : Esito<Nothing>
}

val Esito<*>.messaggio: String?
    get() = when (this) {
        is Esito.Ok -> null
        is Esito.Errore -> messaggio
        Esito.Irraggiungibile -> Testi.SERVER_IRRAGGIUNGIBILE
    }

fun <T> Esito<T>.datoOppure(): T? = (this as? Esito.Ok)?.dato

/**
 * Un invio da ritentare più tardi (posizioni, risposte): niente rete, server spento,
 * tunnel che non trova il server o un errore del server (stato 500 e oltre). Un
 * rifiuto vero (404, 422…) invece non si ritenta: non cambierebbe niente.
 */
val Esito<*>.daRitentare: Boolean
    get() = this is Esito.Irraggiungibile || (this is Esito.Errore && (stato >= 500 || stato <= 0))

inline fun <T, R> Esito<T>.mappa(trasforma: (T) -> R): Esito<R> = when (this) {
    is Esito.Ok -> Esito.Ok(trasforma(dato))
    is Esito.Errore -> this
    Esito.Irraggiungibile -> Esito.Irraggiungibile
}

/** Le frasi di errore di tutta l'app, in un posto solo (aspetto.md §10: mai codici). */
object Testi {
    const val SERVER_IRRAGGIUNGIBILE =
        "Non riesco a raggiungere il server di casa. Controlla la connessione o che il server sia acceso."
    const val NON_ABBINATO = "Questo telefono non è ancora collegato al server di casa."
    const val NON_AUTORIZZATO =
        "Il server non riconosce più questo telefono. Fai preparare un codice nuovo e ricollegalo."
    const val NON_TROVATO = "Non trovo più questo elemento: forse è stato tolto. Aggiorna la pagina."
    const val CONFLITTO = "Questa operazione adesso non si può fare. Aggiorna la pagina e riprova."
    const val NON_VALIDO = "Il server non ha accettato i dati. Controllali e riprova."
    const val TROPPO_GRANDE = "Il file è troppo grande: il massimo è 20 MB."
    const val TROPPI_TENTATIVI = "Troppi tentativi. Aspetta qualche minuto e riprova."
    const val TETTO = "Sforerebbe il tetto del mese: usa la voce gratis o alza il tetto nelle Impostazioni."
    const val FORNITORE = "Il servizio che scrive o legge le lezioni ora non risponde. Riprova tra poco."
    const val PROBLEMA_SERVER = "Il server di casa ha avuto un problema. Riprova tra poco."
    const val GENERICO = "Qualcosa non è andato. Riprova."
    const val RISPOSTA_STRANA = "Il server ha risposto in un modo che l'app non capisce. Forse va aggiornata."
    const val INDIRIZZO_NON_PROTETTO =
        "Questo indirizzo non è protetto e non è della rete di casa: usa l'indirizzo https di Tailscale."
    const val SENZA_RETE = "Senza rete: vedi solo le lezioni scaricate"

    const val CODICE_NON_AUTORIZZATO = "non_autorizzato"
    const val CODICE_NON_ABBINATO = "non_abbinato"
    const val CODICE_TETTO = "tetto_raggiunto"
}
