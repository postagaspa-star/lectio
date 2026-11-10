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
    /** [segmento]: Lezioni 0, Schemi 1, Simulate 2, quando si arriva da una notifica. */
    data class Materia(val id: Long, val segmento: Int? = null) : Pagina
    /** v1.6 (aspetto.md §42.2): una cartella dentro la sua materia. */
    data class Cartella(val id: Long, val materiaId: Long) : Pagina
    data class ModificaScheda(val materiaId: Long) : Pagina
    data class TestoMateriale(val id: Long) : Pagina
    /** [argomento] e [materiali]: "Ascolta questa parte" apre la pagina già compilata. */
    data class NuovaLezione(val materiaId: Long, val eventoId: Long? = null, val argomento: String? = null, val materiali: List<Long>? = null) : Pagina
    /** [daSchema]: si riparte da uno schema già fatto, con tutto compilato. */
    data class NuovoSchema(val materiaId: Long, val eventoId: Long? = null, val daSchema: Long? = null) : Pagina
    data class Schema(val id: Long) : Pagina
    /** [forma]: il segmento da cui partire (punti, discorsivo, misto). */
    data class Riassunto(val schemaId: Long, val forma: String) : Pagina
    data class NuovaSimulata(val materiaId: Long, val eventoId: Long? = null, val daSimulata: Long? = null) : Pagina
    data class Simulata(val id: Long) : Pagina
    /** null = tutte le materie (da Oggi). */
    data class Lampo(val materiaId: Long? = null) : Pagina
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
            is Pagina.Materia -> "materia:${it.id}:${it.segmento ?: ""}"
            is Pagina.Cartella -> "cartella:${it.id}:${it.materiaId}"
            is Pagina.ModificaScheda -> "scheda:${it.materiaId}"
            is Pagina.TestoMateriale -> "materiale:${it.id}"
            is Pagina.NuovaLezione -> "nuova:${it.materiaId}:${it.eventoId ?: ""}:${it.argomento?.let(::chiudiTesto) ?: ""}:${it.materiali?.joinToString(",") ?: ""}"
            is Pagina.NuovoSchema -> "nuovoschema:${it.materiaId}:${it.eventoId ?: ""}:${it.daSchema ?: ""}"
            is Pagina.Schema -> "schema:${it.id}"
            is Pagina.Riassunto -> "riassunto:${it.schemaId}:${it.forma}"
            is Pagina.NuovaSimulata -> "nuovasimulata:${it.materiaId}:${it.eventoId ?: ""}:${it.daSimulata ?: ""}"
            is Pagina.Simulata -> "simulata:${it.id}"
            is Pagina.Lampo -> "lampo:${it.materiaId ?: ""}"
            is Pagina.Preparazione -> "preparazione:${it.lezioneId}"
            is Pagina.Lettore -> "lettore:${it.lezioneId}:${it.domandaId ?: ""}"
            is Pagina.ComeAndata -> "esito:${it.eventoId}"
            Pagina.Prossime -> "prossime"
            Pagina.Impostazioni -> "impostazioni"
            Pagina.Abbinamento -> "abbinamento"
        }
    }).joinToString("|")

    companion object {
        /** Un testo libero dentro il codice: niente ":" né "|" che spezzerebbero tutto. */
        private fun chiudiTesto(t: String): String = java.net.URLEncoder.encode(t, "UTF-8")
        private fun apriTesto(t: String): String = java.net.URLDecoder.decode(t, "UTF-8")

        fun decodifica(testo: String?): Navigazione {
            val parti = testo?.split('|').orEmpty()
            val scheda = Scheda.entries.firstOrNull { it.name == parti.firstOrNull() } ?: Scheda.OGGI
            val pagine = parti.drop(1).mapNotNull { p ->
                val pezzi = p.split(':')
                val n = pezzi.getOrNull(1)?.toLongOrNull()
                when (pezzi[0]) {
                    "materia" -> n?.let { Pagina.Materia(it, pezzi.getOrNull(2)?.toIntOrNull()) }
                    "cartella" -> n?.let { id -> pezzi.getOrNull(2)?.toLongOrNull()?.let { m -> Pagina.Cartella(id, m) } }
                    "scheda" -> n?.let { Pagina.ModificaScheda(it) }
                    "materiale" -> n?.let { Pagina.TestoMateriale(it) }
                    "nuova" -> n?.let {
                        Pagina.NuovaLezione(
                            it, pezzi.getOrNull(2)?.toLongOrNull(),
                            pezzi.getOrNull(3)?.takeIf { a -> a.isNotEmpty() }?.let(::apriTesto),
                            pezzi.getOrNull(4)?.takeIf { m -> m.isNotEmpty() }?.split(',')?.mapNotNull { m -> m.toLongOrNull() },
                        )
                    }
                    "nuovoschema" -> n?.let { Pagina.NuovoSchema(it, pezzi.getOrNull(2)?.toLongOrNull(), pezzi.getOrNull(3)?.toLongOrNull()) }
                    "schema" -> n?.let { Pagina.Schema(it) }
                    "riassunto" -> n?.let { Pagina.Riassunto(it, pezzi.getOrNull(2) ?: "misto") }
                    "nuovasimulata" -> n?.let { Pagina.NuovaSimulata(it, pezzi.getOrNull(2)?.toLongOrNull(), pezzi.getOrNull(3)?.toLongOrNull()) }
                    "simulata" -> n?.let { Pagina.Simulata(it) }
                    "lampo" -> Pagina.Lampo(n)
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
