package eu.stgm.lectio.dati

import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.FileDaCaricare
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.rete.mappa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Quale documento scaricare come PDF. */
sealed interface DocumentoPdf {
    data class DiSchema(val id: Long) : DocumentoPdf
    data class DiSimulata(val id: Long) : DocumentoPdf
    data class DiRiassunto(val id: Long) : DocumentoPdf
}

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
    /** [cartellaId]: v1.6 §27, null = la sceglie Lectio dopo averlo letto. */
    suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?, cartellaId: Long? = null): Esito<Materiale>
    suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto): Esito<Materiale>
    suspend fun eliminaMateriale(id: Long): Esito<Unit>
    suspend fun riprovaMateriale(id: Long): Esito<Unit>
    /** v1.4 §16.3: titolo e tipo di un materiale (solo i campi cambiati). */
    suspend fun modificaMateriale(id: Long, cambi: JsonObject): Esito<Materiale>
    /** v1.4 §16.2: il materiale che parla dell'argomento. */
    suspend fun suggeriti(materiaId: Long, argomento: String): Esito<Suggeriti>

    // --- v1.6 §27: cartelle dentro la materia ---------------------------------------------------

    suspend fun cartelle(materiaId: Long): Lettura<List<Cartella>>
    suspend fun creaCartella(materiaId: Long, nome: String): Esito<Cartella>
    suspend fun rinominaCartella(id: Long, nome: String): Esito<Cartella>
    /** [conIFile] false = i file restano nella materia, fuori dalle cartelle. */
    suspend fun eliminaCartella(id: Long, conIFile: Boolean): Esito<CartellaEliminata>
    /** [cartellaId] null = fuori dalle cartelle. */
    suspend fun sposta(materiaId: Long, materiali: List<Long>, cartellaId: Long?): Esito<Spostati>
    /** Gli id da spuntare già in "Metti dei file qui". */
    suspend fun proposti(cartellaId: Long): Esito<List<Long>>

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

    // --- v1.5: schemi, riassunti, "Non ho capito", Ripasso lampo, verifica simulata ------------

    /** Quello che resta sul dispositivo (verifiche in corso, coda dei ripassi, dove si era). */
    val deposito: DepositoStudio

    suspend fun stimaSchema(richiesta: RichiestaStimaSchema): Esito<StimaStudio>
    suspend fun creaSchema(nuovo: NuovoSchema): Esito<Schema>
    suspend fun schemi(materiaId: Long): Lettura<List<Schema>>
    suspend fun schema(id: Long): Lettura<Schema>
    suspend fun annullaSchema(id: Long): Esito<Unit>
    suspend fun riprovaSchema(id: Long): Esito<Unit>
    suspend fun eliminaSchema(id: Long): Esito<Unit>
    suspend fun scaricaPdf(documento: DocumentoPdf, destinazione: File): Esito<Unit>

    /** I byte della foto: dal telefono se c'è già, altrimenti dal server. [rinfresca] = rileggila dal server. */
    suspend fun immagineSchema(id: Long, n: Int, rinfresca: Boolean = false): Esito<ByteArray>
    suspend fun cambiaImmagine(id: Long, n: Int): Esito<Schema>
    suspend fun togliImmagine(id: Long, n: Int): Esito<Schema>

    suspend fun creaRiassunto(schemaId: Long, forma: String): Esito<Riassunto>
    suspend fun riassunti(schemaId: Long): Lettura<List<Riassunto>>
    suspend fun riassunto(id: Long): Lettura<Riassunto>
    suspend fun riprovaRiassunto(id: Long): Esito<Unit>
    suspend fun eliminaRiassunto(id: Long): Esito<Unit>

    suspend fun chiarisci(corpo: CorpoChiarimento): Esito<Chiarimento>

    suspend fun lampo(materiaId: Long?, limite: Int = 12): Lettura<Lampo>
    suspend fun esitoLampo(id: Long, esito: String): Esito<RispostaLampo>
    suspend fun togliCarta(id: Long): Esito<Unit>

    suspend fun stimaSimulata(richiesta: RichiestaStimaSimulata): Esito<StimaStudio>
    suspend fun creaSimulata(nuova: NuovaSimulata): Esito<Simulata>
    suspend fun simulate(materiaId: Long): Lettura<List<Simulata>>
    suspend fun simulata(id: Long): Lettura<Simulata>
    suspend fun annullaSimulata(id: Long): Esito<Unit>
    suspend fun riprovaSimulata(id: Long): Esito<Unit>
    suspend fun eliminaSimulata(id: Long): Esito<Unit>
    suspend fun salvaSimulata(id: Long, corpo: CorpoSalvaSimulata): Esito<Simulata>
    suspend fun consegnaSimulata(id: Long): Esito<Simulata>
}

/** Il server di casa, con la cache per quando non c'è rete. */
class SorgenteServer(
    private val client: LectioClient,
    private val cache: Cache,
    private val audio: ArchivioAudio? = null,
    /** Ogni salute letta (con il token): da qui arriva l'indirizzo da fuori casa (contratto 15.5). */
    private val alSalute: suspend (Salute) -> Unit = {},
    override val deposito: DepositoStudio = DepositoStudio(),
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
    override suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?, cartellaId: Long?) =
        segna(client.caricaFile(materiaId, file, tipo, titolo, data, cartellaId))
    override suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto) = segna(client.caricaTesto(materiaId, scritto))
    override suspend fun eliminaMateriale(id: Long) = segna(client.eliminaMateriale(id))
    override suspend fun riprovaMateriale(id: Long) = segna(client.riprovaMateriale(id))
    override suspend fun modificaMateriale(id: Long, cambi: JsonObject) = segna(client.modificaMateriale(id, cambi))
    override suspend fun suggeriti(materiaId: Long, argomento: String) = segna(client.suggeriti(materiaId, argomento))

    override suspend fun cartelle(materiaId: Long) =
        segna(leggiConCache(cache, "cartelle-$materiaId", ListSerializer(Cartella.serializer())) { client.cartelle(materiaId) })
    override suspend fun creaCartella(materiaId: Long, nome: String) = segna(client.creaCartella(materiaId, nome))
    override suspend fun rinominaCartella(id: Long, nome: String) = segna(client.rinominaCartella(id, nome))
    override suspend fun eliminaCartella(id: Long, conIFile: Boolean) = segna(client.eliminaCartella(id, conIFile))
    override suspend fun sposta(materiaId: Long, materiali: List<Long>, cartellaId: Long?) = segna(client.sposta(materiaId, materiali, cartellaId))
    override suspend fun proposti(cartellaId: Long) = segna(client.proposti(cartellaId)).mappa { it.proposti }

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

    // --- Schemi -------------------------------------------------------------------------------

    override suspend fun stimaSchema(richiesta: RichiestaStimaSchema) = segna(client.stimaSchema(richiesta))
    override suspend fun creaSchema(nuovo: NuovoSchema) = segna(client.creaSchema(nuovo))
    override suspend fun schemi(materiaId: Long) =
        segna(leggiConCache(cache, "schemi-$materiaId", ListSerializer(Schema.serializer())) { client.schemi(materiaId) })
    override suspend fun schema(id: Long) =
        segna(leggiConCache(deposito.durevole, "schema-$id", Schema.serializer()) { client.schema(id) })
    override suspend fun annullaSchema(id: Long) = segna(client.annullaSchema(id))
    override suspend fun riprovaSchema(id: Long) = segna(client.riprovaSchema(id))
    override suspend fun eliminaSchema(id: Long): Esito<Unit> {
        val e = segna(client.eliminaSchema(id))
        if (e is Esito.Ok) withContext(Dispatchers.IO) { deposito.togliSchema(id) }
        return e
    }

    override suspend fun scaricaPdf(documento: DocumentoPdf, destinazione: File) = segna(
        when (documento) {
            is DocumentoPdf.DiSchema -> client.pdfSchema(documento.id, destinazione)
            is DocumentoPdf.DiSimulata -> client.pdfSimulata(documento.id, destinazione)
            is DocumentoPdf.DiRiassunto -> client.pdfRiassunto(documento.id, destinazione)
        },
    )

    override suspend fun immagineSchema(id: Long, n: Int, rinfresca: Boolean): Esito<ByteArray> {
        val file = deposito.fileImmagine(id, n) ?: return Esito.Errore("Foto non disponibile")
        if (!rinfresca && withContext(Dispatchers.IO) { file.exists() }) return Esito.Ok(withContext(Dispatchers.IO) { file.readBytes() })
        return segna(client.immagineSchema(id, n, file)).mappa { withContext(Dispatchers.IO) { file.readBytes() } }
    }

    override suspend fun cambiaImmagine(id: Long, n: Int) = aggiornaCopiaSchema(segna(client.cambiaImmagine(id, n)))
    override suspend fun togliImmagine(id: Long, n: Int): Esito<Schema> {
        val e = aggiornaCopiaSchema(segna(client.togliImmagine(id, n)))
        if (e is Esito.Ok) withContext(Dispatchers.IO) { deposito.togliImmagine(id, n) }
        return e
    }

    /** Dopo Cambia o Togli foto la copia sul telefono è quella nuova. */
    private suspend fun aggiornaCopiaSchema(e: Esito<Schema>): Esito<Schema> {
        if (e is Esito.Ok) withContext(Dispatchers.IO) { deposito.durevole.scrivi("schema-${e.dato.id}", JsonLectio.encodeToString(Schema.serializer(), e.dato)) }
        return e
    }

    // --- Riassunti ----------------------------------------------------------------------------

    override suspend fun creaRiassunto(schemaId: Long, forma: String) = segna(client.creaRiassunto(schemaId, forma))
    override suspend fun riassunti(schemaId: Long) =
        segna(leggiConCache(deposito.durevole, "riassunti-$schemaId", ListSerializer(Riassunto.serializer())) { client.riassunti(schemaId) })
    override suspend fun riassunto(id: Long) = segna(leggiConCache(deposito.durevole, "riassunto-$id", Riassunto.serializer()) { client.riassunto(id) })
    override suspend fun riprovaRiassunto(id: Long) = segna(client.riprovaRiassunto(id))
    override suspend fun eliminaRiassunto(id: Long): Esito<Unit> {
        val e = segna(client.eliminaRiassunto(id))
        if (e is Esito.Ok) withContext(Dispatchers.IO) { deposito.durevole.cancella("riassunto-$id") }
        return e
    }

    // --- "Non ho capito", Ripasso lampo --------------------------------------------------------

    override suspend fun chiarisci(corpo: CorpoChiarimento) = segna(client.chiarisci(corpo))

    override suspend fun lampo(materiaId: Long?, limite: Int) = segna(
        leggiConCache(deposito.durevole, if (materiaId == null) "lampo-tutte" else "lampo-$materiaId", Lampo.serializer()) { client.lampo(materiaId, limite) },
    )
    override suspend fun esitoLampo(id: Long, esito: String) = segna(client.esitoLampo(id, esito))
    override suspend fun togliCarta(id: Long): Esito<Unit> {
        val e = segna(client.togliCarta(id))
        if (e is Esito.Ok) withContext(Dispatchers.IO) { deposito.togliCartaSalvata(id) }
        return e
    }

    // --- Verifica simulata --------------------------------------------------------------------

    override suspend fun stimaSimulata(richiesta: RichiestaStimaSimulata) = segna(client.stimaSimulata(richiesta))
    override suspend fun creaSimulata(nuova: NuovaSimulata) = segna(client.creaSimulata(nuova))
    override suspend fun simulate(materiaId: Long) =
        segna(leggiConCache(cache, "simulate-$materiaId", ListSerializer(Simulata.serializer())) { client.simulate(materiaId) })
    override suspend fun simulata(id: Long) =
        segna(leggiConCache(deposito.durevole, "simulata-det-$id", Simulata.serializer()) { client.simulata(id) })
    override suspend fun annullaSimulata(id: Long) = segna(client.annullaSimulata(id))
    override suspend fun riprovaSimulata(id: Long) = segna(client.riprovaSimulata(id))
    override suspend fun eliminaSimulata(id: Long): Esito<Unit> {
        val e = segna(client.eliminaSimulata(id))
        if (e is Esito.Ok) {
            withContext(Dispatchers.IO) {
                deposito.durevole.cancella("simulata-det-$id")
                deposito.togliCopia(id)
            }
        }
        return e
    }
    override suspend fun salvaSimulata(id: Long, corpo: CorpoSalvaSimulata) = segna(client.salvaSimulata(id, corpo))
    override suspend fun consegnaSimulata(id: Long) = segna(client.consegnaSimulata(id))
}
