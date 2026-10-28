package eu.stgm.lectio.dati

import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.FileDaCaricare
import eu.stgm.lectio.rete.LectioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/**
 * Da dove le schermate prendono i dati. Due strade con la stessa forma: il server
 * di casa ([SorgenteServer]) e i dati finti in memoria della visita guidata
 * ([SorgenteEsempio], nessuna chiamata di rete).
 */
interface Sorgente {
    /** true = modalità esempio (pillola "Esempio", niente server). */
    val esempio: Boolean

    /** L'ultima chiamata non ha trovato il server: pillola "Senza rete". */
    val senzaRete: StateFlow<Boolean>

    suspend fun salute(): Esito<Salute>
    suspend fun oggi(): Lettura<Oggi>

    suspend fun materie(): Lettura<List<Materia>>
    suspend fun creaMateria(nuova: NuovaMateria): Esito<Materia>
    suspend fun modificaMateria(id: Long, cambi: JsonObject): Esito<Materia>
    suspend fun eliminaMateria(id: Long): Esito<Unit>

    suspend fun scheda(materiaId: Long): Lettura<SchedaProf>
    suspend fun salvaScheda(materiaId: Long, modifica: ModificaScheda): Esito<SchedaProf>
    suspend fun aggiornaScheda(materiaId: Long): Esito<Unit>

    suspend fun materiale(materiaId: Long): Lettura<List<Materiale>>
    suspend fun materialeDettaglio(id: Long): Esito<Materiale>
    suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?): Esito<Materiale>
    suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto): Esito<Materiale>
    suspend fun eliminaMateriale(id: Long): Esito<Unit>
    suspend fun riprovaMateriale(id: Long): Esito<Unit>

    suspend fun eventi(): Lettura<List<Evento>>
    suspend fun creaEvento(materiaId: Long, nuovo: NuovoEvento): Esito<Evento>
    suspend fun modificaEvento(id: Long, nuovo: NuovoEvento): Esito<Evento>
    suspend fun eliminaEvento(id: Long): Esito<Unit>
    suspend fun salvaEsito(eventoId: Long, corpo: CorpoEsito): Esito<RisultatoEsito>

    suspend fun voci(): Esito<List<Voce>>
    suspend fun stima(richiesta: RichiestaStima): Esito<Stima>
    suspend fun spesa(): Lettura<Spesa>

    suspend fun lezioni(materiaId: Long? = null): Lettura<List<Lezione>>
    suspend fun lezione(id: Long): Lettura<Lezione>
    suspend fun creaLezione(nuova: NuovaLezione): Esito<Lezione>
    suspend fun annullaLezione(id: Long): Esito<Unit>
    suspend fun modificaLezione(id: Long, cambi: JsonObject): Esito<Lezione>
    suspend fun rispondi(lezioneId: Long, domandaId: Long, corpo: CorpoRisposta): Esito<Correzione>
    suspend fun daRipassare(materiaId: Long): Lettura<List<DaRipassare>>
    suspend fun riprovaLezione(id: Long): Esito<Unit>
    suspend fun eliminaLezione(id: Long): Esito<Unit>

    suspend fun gemini(): Lettura<TestoGemini>
    suspend fun impostazioni(): Lettura<Impostazioni>
    suspend fun modificaImpostazioni(cambi: JsonObject): Esito<Impostazioni>
}

/** Il server di casa, con la cache per quando non c'è rete. */
class SorgenteServer(
    private val client: LectioClient,
    private val cache: Cache,
    private val audio: ArchivioAudio? = null,
    /** Ogni salute letta (con il token): da qui arriva l'indirizzo da fuori casa (contratto 15.5). */
    private val alSalute: suspend (Salute) -> Unit = {},
) : Sorgente {
    override val esempio = false

    private val _senzaRete = MutableStateFlow(false)
    override val senzaRete: StateFlow<Boolean> = _senzaRete.asStateFlow()

    private fun <T> segna(esito: Esito<T>): Esito<T> {
        _senzaRete.value = LectioClient.eIrraggiungibile(esito)
        return esito
    }

    private fun <T> segna(lettura: Lettura<T>): Lettura<T> {
        _senzaRete.value = lettura.senzaRete
        return lettura
    }

    override suspend fun salute(): Esito<Salute> {
        val e = segna(client.salute())
        if (e is Esito.Ok) alSalute(e.dato)
        return e
    }
    override suspend fun oggi() = segna(leggiConCache(cache, "oggi", Oggi.serializer()) { client.oggi() })

    override suspend fun materie() =
        segna(leggiConCache(cache, "materie", ListSerializer(Materia.serializer())) { client.materie() })
    override suspend fun creaMateria(nuova: NuovaMateria) = segna(client.creaMateria(nuova))
    override suspend fun modificaMateria(id: Long, cambi: JsonObject) = segna(client.modificaMateria(id, cambi))
    override suspend fun eliminaMateria(id: Long) = segna(client.eliminaMateria(id))

    override suspend fun scheda(materiaId: Long) =
        segna(leggiConCache(cache, "scheda-$materiaId", SchedaProf.serializer()) { client.scheda(materiaId) })
    override suspend fun salvaScheda(materiaId: Long, modifica: ModificaScheda) = segna(client.salvaScheda(materiaId, modifica))
    override suspend fun aggiornaScheda(materiaId: Long) = segna(client.aggiornaScheda(materiaId))

    override suspend fun materiale(materiaId: Long) =
        segna(leggiConCache(cache, "materiale-$materiaId", ListSerializer(Materiale.serializer())) { client.materiale(materiaId) })
    override suspend fun materialeDettaglio(id: Long) = segna(client.materialeDettaglio(id))
    override suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?) =
        segna(client.caricaFile(materiaId, file, tipo, titolo, data))
    override suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto) = segna(client.caricaTesto(materiaId, scritto))
    override suspend fun eliminaMateriale(id: Long) = segna(client.eliminaMateriale(id))
    override suspend fun riprovaMateriale(id: Long) = segna(client.riprovaMateriale(id))

    override suspend fun eventi() =
        segna(leggiConCache(cache, "eventi", ListSerializer(Evento.serializer())) { client.eventi() })
    override suspend fun creaEvento(materiaId: Long, nuovo: NuovoEvento) = segna(client.creaEvento(materiaId, nuovo))
    override suspend fun modificaEvento(id: Long, nuovo: NuovoEvento) = segna(client.modificaEvento(id, nuovo))
    override suspend fun eliminaEvento(id: Long) = segna(client.eliminaEvento(id))
    override suspend fun salvaEsito(eventoId: Long, corpo: CorpoEsito) = segna(client.salvaEsito(eventoId, corpo))

    override suspend fun voci() = segna(client.voci())
    override suspend fun stima(richiesta: RichiestaStima) = segna(client.stima(richiesta))
    override suspend fun spesa() = segna(leggiConCache(cache, "spesa", Spesa.serializer()) { client.spesa() })

    override suspend fun lezioni(materiaId: Long?) =
        segna(leggiConCache(cache, if (materiaId == null) "lezioni" else "lezioni-$materiaId", ListSerializer(Lezione.serializer())) { client.lezioni(materiaId) })

    /** Senza rete, una lezione scaricata si legge dal suo dettaglio salvato accanto all'audio. */
    override suspend fun lezione(id: Long): Lettura<Lezione> {
        val letta = segna(leggiConCache(cache, "lezione-$id", Lezione.serializer()) { client.lezione(id) })
        val a = audio ?: return letta
        if (letta.errore == null && letta.dato != null) {
            if (a.scaricata(id)) withContext(Dispatchers.IO) { a.salvaDettaglio(letta.dato) }
            return letta
        }
        val salvata = withContext(Dispatchers.IO) { a.dettaglio(id) } ?: return letta
        return letta.copy(dato = letta.dato ?: salvata)
    }

    /**
     * Dopo un PUT …/ascolto riuscito la copia sul telefono (cache e dettaglio della
     * lezione scaricata) prende la posizione appena salvata: senza rete si riparte da
     * lì, non da una posizione vecchia (punto 4 della revisione).
     */
    suspend fun aggiornaAscoltoLocale(id: Long, ascolto: Ascolto) = withContext(Dispatchers.IO) {
        fun nuova(l: Lezione) = if (l.id == id) l.copy(ascolto = ascolto) else l
        fun <T> aggiorna(chiave: String, serializer: kotlinx.serialization.KSerializer<T>, cambia: (T) -> T): T? {
            val voce = cache.leggi(chiave) ?: return null
            val letto = try {
                JsonLectio.decodeFromString(serializer, voce.json)
            } catch (e: Exception) {
                return null
            }
            val cambiato = cambia(letto)
            cache.scrivi(chiave, JsonLectio.encodeToString(serializer, cambiato))
            return cambiato
        }
        val elenco = ListSerializer(Lezione.serializer())
        val dettaglio = aggiorna("lezione-$id", Lezione.serializer(), ::nuova)
        val salvata = audio?.dettaglio(id)
        if (salvata != null) audio.salvaDettaglio(nuova(salvata))
        aggiorna("lezioni", elenco) { it.map(::nuova) }
        val materiaId = dettaglio?.materiaId ?: salvata?.materiaId
        if (materiaId != null) aggiorna("lezioni-$materiaId", elenco) { it.map(::nuova) }
        aggiorna("oggi", Oggi.serializer()) { o ->
            o.copy(riprendi = o.riprendi?.let(::nuova), prossima = o.prossima?.let { p -> p.copy(lezione = p.lezione?.let(::nuova)) })
        }
        Unit
    }

    override suspend fun creaLezione(nuova: NuovaLezione) = segna(client.creaLezione(nuova))
    override suspend fun annullaLezione(id: Long) = segna(client.annullaLezione(id))
    override suspend fun modificaLezione(id: Long, cambi: JsonObject) = segna(client.modificaLezione(id, cambi))
    override suspend fun rispondi(lezioneId: Long, domandaId: Long, corpo: CorpoRisposta) = segna(client.rispondi(lezioneId, domandaId, corpo))
    override suspend fun daRipassare(materiaId: Long) =
        segna(leggiConCache(cache, "ripassare-$materiaId", ListSerializer(DaRipassare.serializer())) { client.daRipassare(materiaId) })
    override suspend fun riprovaLezione(id: Long) = segna(client.riprovaLezione(id))
    override suspend fun eliminaLezione(id: Long) = segna(client.eliminaLezione(id))

    override suspend fun gemini() = segna(leggiConCache(cache, "gemini", TestoGemini.serializer()) { client.gemini() })
    override suspend fun impostazioni() =
        segna(leggiConCache(cache, "impostazioni", Impostazioni.serializer()) { client.impostazioni() })
    override suspend fun modificaImpostazioni(cambi: JsonObject) = segna(client.modificaImpostazioni(cambi))
}
