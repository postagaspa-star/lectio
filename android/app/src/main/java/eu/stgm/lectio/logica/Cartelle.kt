package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.Materiale
import java.time.LocalDate

/**
 * Le frasi e le regole delle cartelle (contratto §27, aspetto.md §42): un livello solo,
 * solo per il materiale. Qui niente Compose, così si provano da sole.
 */
object TestiCartelle {
    /** Come il server: al massimo 40 caratteri (spazi in più tolti). */
    const val NOME_MASSIMO = 40

    /** Il nome come lo salva il server: spazi in più tolti. */
    fun pulisciNome(nome: String): String = nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** "8 file", "1 file", "Vuota". */
    fun quantiFile(n: Int): String = when (n) {
        0 -> "Vuota"
        1 -> "1 file"
        else -> "$n file"
    }

    /** "2 messi ora da Lectio", "1 messo ora da Lectio"; null se nessuno. */
    fun messiDaLectio(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 messo ora da Lectio"
        else -> "$n messi ora da Lectio"
    }

    /** Sotto il nome nella testata: "Storia dell'Arte · 8 file". */
    fun sottoTestata(materia: String?, n: Int): String = listOfNotNull(materia?.takeIf { it.isNotBlank() }, quantiFile(n)).joinToString(" · ")

    const val MESSO_DA_LECTIO = "messo qui da Lectio"

    fun titoloMetti(nome: String): String = "Metti in $nome"

    /** "3 scelti", "1 scelto". */
    fun scelti(n: Int): String = if (n == 1) "1 scelto" else "$n scelti"

    /** "Metti qui 3 file", "Metti qui 1 file". */
    fun pulsanteMetti(n: Int): String = "Metti qui $n file"

    /** La riga conferma dopo lo spostamento: "Fatto: 3 file in Arte greca". */
    fun fatto(n: Int, nome: String): String = "Fatto: $n file in $nome"

    fun titoloElimina(nome: String): String = "Eliminare la cartella «$nome»?"

    /** "Dentro ci sono 8 file.", "Dentro c'è 1 file." */
    fun dentro(n: Int): String = if (n == 1) "Dentro c'è 1 file." else "Dentro ci sono $n file."

    /** "Elimina anche gli 8 file", "Elimina anche i 3 file", "Elimina anche il file". */
    fun eliminaAnche(n: Int): String = if (n == 1) "Elimina anche il file" else "Elimina anche ${articoloPlurale(n)} $n file"

    /** Dopo l'eliminazione, sulla pagina della materia. */
    fun eliminata(fileEliminati: Int): String = if (fileEliminati <= 0) "Cartella eliminata" else "Cartella e $fileEliminati file eliminati"

    /**
     * L'articolo davanti al numero letto ad alta voce: "gli otto", "gli undici", "gli
     * ottanta", ma "i tre", "i dieci", "i cento".
     */
    fun articoloPlurale(n: Int): String {
        val vocale = n.toString().startsWith("8") || n == 11 || n in 11_000..11_999
        return if (vocale) "gli" else "i"
    }

    /** "Appunti · 4 novembre" (fuori dalle cartelle), "In Arte romana · Appunti" (in un'altra), "Lo sto leggendo". */
    fun sottoScelta(m: Materiale, nomeCartella: String?, oggi: LocalDate = LocalDate.now()): String {
        if (m.stato == "in_lettura") return "Lo sto leggendo"
        val tipo = SceltaMateriale.nomeTipo(m.tipo)
        if (nomeCartella != null) return "In $nomeCartella · $tipo"
        // Spazio che non va a capo: sui telefoni stretti "24 ottobre" resta intero.
        val quando = Formati.data(m.data)?.let { Formati.giornoBreve(it, oggi).replace(' ', ' ') }
        return listOfNotNull(tipo, quando).joinToString(" · ")
    }
}

/** "Metti dei file qui" (aspetto.md §42.4): chi si può scegliere e in che gruppi. */
object MettiQui {
    /** Il materiale che si può mettere in [cartellaId]: tutto quello fuori da lei, tranne quello in errore. */
    fun candidati(materiale: List<Materiale>, cartellaId: Long): List<Materiale> =
        materiale.filter { it.cartellaId != cartellaId && it.stato != "errore" }

    /** FUORI DALLE CARTELLE, nell'ordine della materia (più recente prima). */
    fun fuori(candidati: List<Materiale>): List<Materiale> = candidati.filter { it.cartellaId == null }

    /** IN ALTRE CARTELLE, cartella per cartella in ordine alfabetico. */
    fun altrove(candidati: List<Materiale>, cartelle: List<Cartella>): List<Materiale> {
        val ordine = cartelle.sortedBy { SceltaMateriale.normalizza(it.nome) }.mapIndexed { i, c -> c.id to i }.toMap()
        return candidati.filter { it.cartellaId != null }.sortedBy { ordine[it.cartellaId] ?: Int.MAX_VALUE }
    }

    /** All'apertura: i proposti del server, solo tra quelli che si possono scegliere. */
    fun iniziali(proposti: List<Long>, candidati: List<Materiale>): Set<Long> {
        val ids = candidati.map { it.id }.toSet()
        return proposti.filter { it in ids }.toSet()
    }

    /** Il campo Cerca c'è solo con più di 6 elementi. */
    fun conCerca(candidati: List<Materiale>): Boolean = candidati.size > 6

    /** Gli id da mandare, nell'ordine in cui si vedono. */
    fun daMandare(candidati: List<Materiale>, cartelle: List<Cartella>, scelti: Set<Long>): List<Long> =
        (fuori(candidati) + altrove(candidati, cartelle)).map { it.id }.filter { it in scelti }
}
