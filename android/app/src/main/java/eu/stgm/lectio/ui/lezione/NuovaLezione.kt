package eu.stgm.lectio.ui.lezione

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.NuovaLezione
import eu.stgm.lectio.dati.RichiestaStima
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.dati.Voce
import eu.stgm.lectio.logica.Costi
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.Indicazioni
import eu.stgm.lectio.logica.Per
import eu.stgm.lectio.logica.SceltaMateriale
import eu.stgm.lectio.logica.Struttura
import eu.stgm.lectio.logica.TipoLezione
import eu.stgm.lectio.logica.TipoVoce
import eu.stgm.lectio.logica.VistaCosto
import eu.stgm.lectio.logica.VociDisponibili
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.BarraDueToni
import eu.stgm.lectio.ui.Binario
import eu.stgm.lectio.ui.CampoBordato
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaOpzione
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.SezioneNumerata
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.bordoScelto
import eu.stgm.lectio.ui.bordoSpento
import eu.stgm.lectio.ui.fondoScelto
import eu.stgm.lectio.ui.fondoSpento
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.morbido
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Le durate offerte (aspetto.md §6). */
val DURATE = listOf(15, 20, 30, 45)

/** Quanto si aspetta dopo l'ultima lettera dell'argomento prima di chiedere i suggeriti (contratto §16.2). */
const val ATTESA_SUGGERITI_MS = 400L

/** Una scheda della sezione Voce: la voce (se il server la elenca) e se si può scegliere. */
data class SchedaVoce(val tipo: TipoVoce, val voce: Voce?, val attiva: Boolean)

data class StatoNuovaLezione(
    val materia: Materia? = null,
    val argomento: String = "",
    /** Le prossime verifiche e interrogazioni di questa materia (PER). */
    val eventi: List<Evento> = emptyList(),
    val eventoId: Long? = null,
    /** Tutto il materiale della materia, anche quello in lettura o in errore. */
    val materiale: List<Materiale> = emptyList(),
    /** Le cartelle della materia (pillole CARTELLE, aspetto.md §42.7). */
    val cartelle: List<Cartella> = emptyList(),
    /** Gli id che il server dice che parlano dell'argomento. */
    val suggeriti: Set<Long> = emptySet(),
    /**
     * null = le caselle seguono i suggeriti ("Sull'argomento"); altrimenti sono come le
     * ha messe Andrea (contratto §16.2).
     */
    val sceltiAMano: Set<Long>? = null,
    val cerca: String = "",
    val tipo: TipoLezione = TipoLezione.SPIEGAZIONE,
    val indicazioni: String = "",
    val durata: Int = 20,
    val saltabili: Boolean = false,
    val voci: VociDisponibili = VociDisponibili(null, null, null),
    /** Tutte le voci del server, anche quelle non ancora attive (schede tratteggiate). */
    val elencoVoci: List<Voce> = emptyList(),
    val voceScelta: TipoVoce = TipoVoce.ESPRESSIVA,
    /** La voce precisa toccata da Andrea (o la predefinita): conta quando ce n'è più d'una gratis. */
    val voceId: String? = null,
    val stimaEspressiva: Stima? = null,
    val stimaGratis: Stima? = null,
    val stimaProva: Stima? = null,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val creata: Lezione? = null,
    val pronto: Boolean = false,
) {
    /**
     * I soldi dalle stime. Senza materiale spuntato la stima non si chiede: niente cifra
     * sul pulsante né "dopo questa", resta solo quanto è già speso nel mese.
     */
    val costo: VistaCosto
        get() {
            val c = VistaCosto.calcola(stimaEspressiva, stimaGratis, stimaProva, voceScelta)
            return if (materialiDaMandare.isEmpty()) c.copy(costoScelto = null, dopo = null, frazioneQuesta = 0f) else c
        }

    /** Il materiale che si può spuntare (solo quello letto, contratto §16.2). */
    val pronti: List<Materiale> get() = materiale.filter(SceltaMateriale::pronto)

    /** true = "Sull'argomento": le caselle seguono i suggeriti. */
    val auto: Boolean get() = sceltiAMano == null

    /** Le caselle spuntate, sempre e solo tra il materiale pronto. */
    val scelti: Set<Long>
        get() {
            val ids = pronti.map { it.id }.toSet()
            return (sceltiAMano ?: suggeriti).filter { it in ids }.toSet()
        }

    /** I `materiali` da mandare: l'elenco completo degli spuntati, nell'ordine della materia. */
    val materialiDaMandare: List<Long> get() = scelti.let { s -> pronti.filter { it.id in s }.map { it.id } }

    val evento: Evento? get() = eventi.firstOrNull { it.id == eventoId }

    /** Cosa manca prima di poter preparare, in ambra (aspetto.md §6). */
    val manca: String?
        get() = when {
            argomento.isBlank() -> "Manca l'argomento"
            scelti.isEmpty() -> "Scegli almeno un materiale"
            else -> null
        }

    /** La voce che parte davvero col tocco su Prepara. */
    val voceDaUsare: Voce?
        get() = when (costo.voceScelta) {
            TipoVoce.ESPRESSIVA -> voci.espressiva
            TipoVoce.GRATIS -> voci.gratis
            TipoVoce.PROVA -> voci.prova ?: voci.gratis ?: voci.espressiva
        }

    /** Si può preparare: argomento, almeno un materiale, una stima consentita, una voce. */
    val puoPreparare: Boolean
        get() = pronto && manca == null && costo.consentita && !inCorso && voceDaUsare != null

    /**
     * Le schede della sezione Voce: tutte le voci del server, una scheda ciascuna (le gratis
     * del NAS possono essere più d'una), tratteggiate se non attive. Senza chiavi: la voce di prova.
     */
    val schedeVoci: List<SchedaVoce>
        get() {
            fun di(tipo: String, genere: TipoVoce, attive: Boolean) =
                elencoVoci.filter { it.tipo == tipo }.map { SchedaVoce(genere, it, attive && it.disponibile) }
            if (voci.soloProva) {
                return listOf(SchedaVoce(TipoVoce.PROVA, voci.prova, true)) +
                    di("espressiva", TipoVoce.ESPRESSIVA, false) + di("gratis", TipoVoce.GRATIS, false)
            }
            return di("espressiva", TipoVoce.ESPRESSIVA, true) + di("gratis", TipoVoce.GRATIS, true)
        }

    /** La scheda accesa: il genere che parte davvero e, dentro il genere, la voce scelta. */
    fun schedaScelta(v: SchedaVoce): Boolean {
        if (!v.attiva || v.tipo != costo.voceScelta) return false
        val id = when (v.tipo) {
            TipoVoce.ESPRESSIVA -> voci.espressiva?.id
            TipoVoce.GRATIS -> voci.gratis?.id
            TipoVoce.PROVA -> voci.prova?.id
        }
        return v.voce == null || v.voce.id == id
    }

    /** La riga della barra in basso: "Spiegazione · 20 min · 3 materiali" (o cosa manca). */
    val riassunto: String get() = manca ?: "${tipo.nomeBreve} · $durata min · ${SceltaMateriale.quanti(scelti.size)}"

    /** "mese: 0,32 € di 2 €": il mese dopo questa lezione. */
    val rigaMese: String get() = costo.let { "mese: ${Formati.euro(it.dopo ?: it.spesoMese)} di ${Formati.euro(it.tetto)}" }
}

class NuovaLezioneViewModel(
    private val sorgente: Sorgente,
    private val materiaId: Long,
    private val eventoIniziale: Long?,
    private val oggi: () -> LocalDate = LocalDate::now,
    private val attesaSuggeritiMs: Long = ATTESA_SUGGERITI_MS,
    /** "Ascolta questa parte" di uno schema: argomento e documenti già messi (aspetto.md §33). */
    private val argomentoIniziale: String = "",
    private val materialiIniziali: List<Long>? = null,
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoNuovaLezione())
    val stato: StateFlow<StatoNuovaLezione> = _stato.asStateFlow()
    private var stime: Job? = null
    private var suggerimenti: Job? = null
    private var giroSuggeriti = 0

    /** Durata, materiale e voci dell'ultima stima chiesta: si richiede solo quando cambiano. */
    private var ultimaStima: List<Any?>? = null

    fun carica() {
        viewModelScope.launch {
            val materie = async { sorgente.materie() }
            val eventi = async { sorgente.eventi() }
            val materiale = async { sorgente.materiale(materiaId) }
            val cartelle = async { sorgente.cartelle(materiaId) }
            val voci = async { sorgente.voci() }
            val imp = async { sorgente.impostazioni() }
            val giorno = oggi().toString()
            val prossimi = eventi.await().dato.orEmpty().filter { it.materiaId == materiaId && it.data >= giorno && !it.haEsito }.sortedBy { it.data }
            val evento = prossimi.firstOrNull { it.id == eventoIniziale } ?: prossimi.firstOrNull()
            // PER: la prossima (o quella da cui si arriva) e, se ci sono, le altre due dopo.
            val per = (listOfNotNull(evento) + prossimi.filter { it.id != evento?.id }).take(3).sortedBy { it.data }
            val elenco = (voci.await() as? Esito.Ok)?.dato.orEmpty()
            val preferita = _stato.value.let { s -> if (s.pronto) s.voceId else null } ?: imp.await().dato?.vocePredefinita
            val vociDisp = Costi.voci(elenco, preferita)
            val i = imp.await().dato
            val erroreVoci = voci.await().let { v -> if (v is Esito.Ok) null else v.messaggio }
            _stato.update {
                it.copy(
                    materia = materie.await().dato?.firstOrNull { m -> m.id == materiaId } ?: it.materia,
                    eventi = per,
                    eventoId = if (it.pronto) it.eventoId else evento?.id,
                    argomento = it.argomento.ifBlank { argomentoIniziale.ifBlank { evento?.argomento ?: "" } },
                    sceltiAMano = if (it.pronto) it.sceltiAMano else materialiIniziali?.toSet(),
                    materiale = materiale.await().dato ?: it.materiale,
                    cartelle = cartelle.await().dato ?: it.cartelle,
                    durata = if (it.pronto) it.durata else i?.durataPredefinita?.takeIf { d -> d in DURATE } ?: 20,
                    saltabili = if (it.pronto) it.saltabili else i?.domandeSaltabili ?: false,
                    voci = vociDisp,
                    elencoVoci = elenco,
                    voceScelta = if (it.pronto) it.voceScelta else Costi.voceIniziale(vociDisp, i?.vocePredefinita),
                    voceId = preferita,
                    errore = erroreVoci,
                    pronto = true,
                )
            }
            ultimaStima = null
            chiediSuggeriti(0)
            forseStima()
        }
    }

    // --- 1. Su cosa --------------------------------------------------------------------------

    fun argomento(t: String) {
        val nuovo = t.take(160)
        if (nuovo == _stato.value.argomento) return
        _stato.update { it.copy(argomento = nuovo) }
        chiediSuggeriti(attesaSuggeritiMs)
    }

    /** PER: una data o nessuna. Con l'argomento vuoto si prende quello della verifica. */
    fun evento(id: Long?) {
        val prima = _stato.value.argomento
        _stato.update { s ->
            val e = s.eventi.firstOrNull { it.id == id }
            s.copy(eventoId = id, argomento = if (s.argomento.isBlank() && e != null) e.argomento else s.argomento)
        }
        if (_stato.value.argomento != prima) chiediSuggeriti(0)
    }

    /**
     * I suggeriti per l'argomento attuale, [ritardo] ms dopo l'ultima lettera. Una
     * risposta arrivata dopo che l'argomento è cambiato non vale più.
     */
    private fun chiediSuggeriti(ritardo: Long) {
        suggerimenti?.cancel()
        val giro = ++giroSuggeriti
        suggerimenti = viewModelScope.launch {
            if (ritardo > 0) delay(ritardo)
            val argomento = _stato.value.argomento.trim()
            val ids: Set<Long>? = if (argomento.isEmpty()) {
                emptySet()
            } else {
                when (val e = sorgente.suggeriti(materiaId, argomento)) {
                    is Esito.Ok -> e.dato.suggeriti.toSet()
                    // Un server di prima della v1.4 non sa suggerire: come "Tutti", così si prepara lo stesso.
                    is Esito.Errore -> if (e.codice == "non_trovato" && e.stato == 404) _stato.value.pronti.map { it.id }.toSet() else null
                    else -> null
                }
            }
            if (ids == null || giro != giroSuggeriti || _stato.value.argomento.trim() != argomento) return@launch
            _stato.update { it.copy(suggeriti = ids) }
            forseStima()
        }
    }

    // --- 2. Da quale materiale ---------------------------------------------------------------

    /** Una casella toccata: da qui le caselle restano come le mette Andrea. */
    fun tocca(id: Long) {
        _stato.update { s ->
            if (s.pronti.none { it.id == id }) return@update s
            val base = s.scelti
            s.copy(sceltiAMano = if (id in base) base - id else base + id)
        }
        forseStima()
    }

    fun sullArgomento() {
        _stato.update { it.copy(sceltiAMano = null) }
        forseStima()
    }

    fun tutti() {
        _stato.update { s -> s.copy(sceltiAMano = s.pronti.map { it.id }.toSet()) }
        forseStima()
    }

    fun nessuno() {
        _stato.update { it.copy(sceltiAMano = emptySet()) }
        forseStima()
    }

    /** Una pillola CARTELLE (aspetto.md §42.7): spunta o toglie tutto il suo materiale pronto; conta come un tocco. */
    fun cartella(id: Long) {
        _stato.update { s ->
            val ids = s.pronti.filter { it.cartellaId == id }.map { it.id }
            if (ids.isEmpty()) return@update s
            s.copy(sceltiAMano = SceltaMateriale.alternaCartella(s.scelti, ids))
        }
        forseStima()
    }

    fun cerca(t: String) = _stato.update { it.copy(cerca = t.take(80)) }

    // --- 3. Come la vuoi ---------------------------------------------------------------------

    fun tipo(t: TipoLezione) = _stato.update { it.copy(tipo = t) }

    /** Le indicazioni non rifanno la stima: il costo non dipende da loro. */
    fun indicazioni(t: String) = _stato.update { it.copy(indicazioni = t.take(Indicazioni.MASSIMO)) }

    fun spunto(frase: String) = _stato.update { it.copy(indicazioni = Indicazioni.alterna(it.indicazioni, frase)) }

    fun durata(d: Int) {
        _stato.update { it.copy(durata = d) }
        forseStima()
    }

    fun saltabili(si: Boolean) = _stato.update { it.copy(saltabili = si) }

    // --- 4. Voce -----------------------------------------------------------------------------

    /** Ogni voce ha già la sua stima: cambiare voce non chiama il server. */
    fun voce(t: TipoVoce) = _stato.update { it.copy(voceScelta = t) }

    /**
     * Una scheda voce toccata. Fra le voci gratis del NAS cambia anche la stima (il tempo
     * di preparazione è della voce): si richiede con la voce nuova.
     */
    fun voce(v: Voce) {
        if (!v.disponibile) return
        val tipo = when (v.tipo) {
            "espressiva" -> TipoVoce.ESPRESSIVA
            "prova" -> TipoVoce.PROVA
            else -> TipoVoce.GRATIS
        }
        _stato.update { it.copy(voceId = v.id, voci = Costi.voci(it.elencoVoci, v.id), voceScelta = tipo) }
        forseStima()
    }

    // --- Stime e Prepara ---------------------------------------------------------------------

    /** La stima si rifà solo quando cambiano durata o materiale spuntato; senza materiale non si chiede. */
    private fun forseStima() {
        val s = _stato.value
        if (!s.pronto) return
        val chiave = listOf(s.durata, s.materialiDaMandare, s.voci.espressiva?.id, s.voci.gratis?.id, s.voci.prova?.id)
        if (chiave == ultimaStima) return
        ultimaStima = chiave
        if (s.materialiDaMandare.isEmpty()) {
            stime?.cancel()
            return
        }
        aggiornaStime()
    }

    /** Una stima per voce: il costo di ogni scheda viene dal server (contratto §8). */
    private fun aggiornaStime() {
        stime?.cancel()
        stime = viewModelScope.launch {
            val s = _stato.value
            fun richiesta(v: Voce?) = v?.let { RichiestaStima(materiaId, s.durata, it.id, s.materialiDaMandare, s.tipo.codice, s.indicazioni.trim()) }
            val e = richiesta(s.voci.espressiva)?.let { async { sorgente.stima(it) } }
            val g = richiesta(s.voci.gratis)?.let { async { sorgente.stima(it) } }
            val p = richiesta(s.voci.prova)?.let { async { sorgente.stima(it) } }
            val re = e?.await()
            val rg = g?.await()
            val rp = p?.await()
            _stato.update {
                it.copy(
                    stimaEspressiva = (re as? Esito.Ok)?.dato,
                    stimaGratis = (rg as? Esito.Ok)?.dato,
                    stimaProva = (rp as? Esito.Ok)?.dato,
                    errore = listOfNotNull(re, rg, rp).firstOrNull { r -> r !is Esito.Ok }?.messaggio,
                )
            }
        }
    }

    /** Dopo un errore: si rilegge tutto e si richiede la stima. */
    fun riprova() {
        ultimaStima = null
        carica()
    }

    fun prepara() {
        val s = _stato.value
        val voce = s.voceDaUsare ?: return
        if (!s.puoPreparare) return
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            val e = sorgente.creaLezione(
                NuovaLezione(
                    materiaId = materiaId,
                    eventoId = s.eventoId,
                    argomento = s.argomento.trim(),
                    materiali = s.materialiDaMandare,
                    durataMin = s.durata,
                    voce = voce.id,
                    domandeSaltabili = s.saltabili,
                    tipo = s.tipo.codice,
                    indicazioni = s.indicazioni.trim(),
                ),
            )
            _stato.update {
                when (e) {
                    is Esito.Ok -> it.copy(inCorso = false, creata = e.dato)
                    else -> it.copy(inCorso = false, errore = e.messaggio)
                }
            }
        }
    }
}

/**
 * Nuova lezione (aspetto.md §6, prototipo NuovaLezioneTel.dc.html): quattro decisioni
 * numerate, un riepilogo in fondo e la barra fissa con il pulsante.
 */
@Composable
fun NuovaLezioneScreen(vm: NuovaLezioneViewModel, onIndietro: () -> Unit, onCreata: (Lezione) -> Unit, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.creata) { s.creata?.let(onCreata) }
    val c = L.colori
    val prova = s.voci.soloProva
    val bloccatoDaRete = senzaRete && !esempio
    Box(Modifier.fillMaxSize().background(c.fondo)) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 168.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsanteTondo(Icone.Chiudi, "Chiudi", onIndietro, dimensioneIcona = 18.dp)
                Box(Modifier.weight(1f)) { s.materia?.let { PillolaMateria(it.nome, it.colore) } }
                if (esempio) PillolaStato(TipoPillola.ESEMPIO) else if (prova) PillolaStato(TipoPillola.PROVA)
            }
            Text(
                "Nuova lezione",
                style = L.testi.titoloPagina.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = c.testo,
                modifier = Modifier.semantics { heading() },
            )
            SezioneSuCosa(s.argomento, vm::argomento, s.eventi, s.eventoId, vm::evento)
            SezioneMateriale(s.materiale, s.pronti, s.scelti, s.suggeriti, s.auto, s.cerca, vm::cerca, vm::tocca, vm::sullArgomento, vm::tutti, vm::nessuno, s.cartelle, vm::cartella)
            ComeLaVuoi(s, vm)
            SezioneVoce(s, vm)
            Riepilogo(s)
            s.errore?.let { RigaErrore(it, onRiprova = vm::riprova) }
            if (bloccatoDaRete) RigaAvviso("Senza rete non si può preparare: il server di casa non risponde.")
        }
        BarraPrepara(s, vm, bloccatoDaRete, Modifier.align(Alignment.BottomCenter))
    }
}

// --- 1. Su cosa ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SezioneSuCosa(argomento: String, onArgomento: (String) -> Unit, eventi: List<Evento>, eventoId: Long?, onEvento: (Long?) -> Unit) {
    SezioneNumerata(1, "Su cosa") {
        Etichetta("Argomento")
        CampoBordato(
            argomento, onArgomento,
            segnaposto = "Per esempio L'Impero romano",
            stile = L.testi.forteGrande.copy(fontWeight = FontWeight.SemiBold),
            tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            descrizione = "Argomento",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.heightIn(min = 48.dp).padding(end = 2.dp), contentAlignment = Alignment.Center) { Etichetta("Per") }
            PillolaOpzione("Nessuna data", eventoId == null, { onEvento(null) })
            eventi.forEach { e -> PillolaOpzione(Per.pillola(e), eventoId == e.id, { onEvento(e.id) }) }
        }
    }
}

// --- 2. Da quale materiale -------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SezioneMateriale(
    materiale: List<Materiale>,
    pronti: List<Materiale>,
    scelti: Set<Long>,
    suggeriti: Set<Long>,
    auto: Boolean,
    cerca: String,
    onCerca: (String) -> Unit,
    onTocca: (Long) -> Unit,
    onSull: () -> Unit,
    onTutti: () -> Unit,
    onNessuno: () -> Unit,
    /** v1.6 (aspetto.md §42.7): le cartelle della materia, per le pillole CARTELLE e le righe. */
    cartelle: List<Cartella> = emptyList(),
    onCartella: (Long) -> Unit = {},
) {
    val c = L.colori
    val n = scelti.size
    val totale = pronti.size
    val nomi = cartelle.associate { it.id to it.nome }
    val nomeCartella: (Materiale) -> String? = { m -> m.cartellaId?.let { nomi[it] } }
    SezioneNumerata(
        2, "Da quale materiale",
        destra = { Text("$n di $totale scelti", style = L.testi.pillola, color = c.tenue, maxLines = 1) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillolaOpzione("Sull'argomento", auto, onSull)
            PillolaOpzione("Tutti", !auto && n == totale && totale > 0, onTutti)
            PillolaOpzione("Nessuno", !auto && n == 0, onNessuno)
        }
        val conPronti = SceltaMateriale.cartelleConPronti(cartelle, pronti)
        if (conPronti.isNotEmpty()) {
            Etichetta("Cartelle")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                conPronti.forEach { (cartella, ids) ->
                    PillolaOpzione(
                        cartella.nome,
                        accesa = ids.all { it in scelti },
                        onClick = { onCartella(cartella.id) },
                        ruolo = Role.Checkbox,
                        dopo = " · ${ids.size}",
                    )
                }
            }
        }
        val visibili = materiale.filter(SceltaMateriale::visibile)
        if (visibili.size > 4) {
            CampoBordato(
                cerca, onCerca,
                segnaposto = "Cerca nel materiale",
                stile = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold),
                altezza = 48.dp,
                fondo = c.fondo,
                bordoSpento = c.fondo,
                descrizione = "Cerca nel materiale",
            )
        }
        val filtrati = SceltaMateriale.filtra(visibili, cerca, nomeCartella)
        GruppoMateriale("Appunti e libro", "Nessuno ancora", visibili.filterNot(SceltaMateriale::delProf), filtrati.filterNot(SceltaMateriale::delProf), scelti, suggeriti, nomeCartella, onTocca)
        GruppoMateriale("Verifiche e interrogazioni del prof", "Nessuna ancora", visibili.filter(SceltaMateriale::delProf), filtrati.filter(SceltaMateriale::delProf), scelti, suggeriti, nomeCartella, onTocca)
    }
}

@Composable
private fun GruppoMateriale(
    titolo: String,
    vuoto: String,
    tutti: List<Materiale>,
    mostrati: List<Materiale>,
    scelti: Set<Long>,
    suggeriti: Set<Long>,
    nomeCartella: (Materiale) -> String?,
    onTocca: (Long) -> Unit,
) {
    val c = L.colori
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Etichetta(titolo)
        when {
            tutti.isEmpty() -> Text(vuoto, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.tenue)
            mostrati.isEmpty() -> Text("Niente con questo nome", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.tenue)
            else -> mostrati.forEach { m ->
                RigaScelta(m, m.id in scelti, m.id in suggeriti && SceltaMateriale.pronto(m), nomeCartella(m)) { onTocca(m.id) }
            }
        }
    }
}

/**
 * Una riga del materiale (aspetto.md §6): casella 22 raggio 7, titolo 15 / 700 su una riga,
 * sotto "Appunti · foto · 4 novembre" (con la cartella dopo il tipo, §42.7), a destra
 * "argomento" se è tra i suggeriti.
 */
@Composable
private fun RigaScelta(m: Materiale, scelto: Boolean, suggerito: Boolean, cartella: String?, onTocca: () -> Unit) {
    val c = L.colori
    val inLettura = !SceltaMateriale.pronto(m)
    RigaCasella(
        titolo = m.titolo.ifBlank { m.nomeFile ?: "Materiale" },
        sotto = if (inLettura) "Lo sto leggendo" else SceltaMateriale.sotto(m, cartella = cartella),
        scelto = scelto,
        onTocca = onTocca,
        abilitato = !inLettura,
        descrizioneStato = if (suggerito) (if (scelto) "Scelto" else "Non scelto") + ", sull'argomento" else null,
        destra = if (!suggerito) null else {
            {
                Text(
                    "argomento",
                    maxLines = 1,
                    style = L.testi.etichetta.copy(letterSpacing = 0.sp, fontWeight = FontWeight.ExtraBold),
                    color = c.accentoTesto,
                    modifier = Modifier.clip(CircleShape).background(c.accentoTenue).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        },
    )
}

/**
 * La riga di scelta con la casella (aspetto.md §6), usata anche da "Metti dei file qui"
 * (§42.4). [attenuata] = mezza trasparente (il materiale ancora in lettura).
 */
@Composable
fun RigaCasella(
    titolo: String,
    sotto: String,
    scelto: Boolean,
    onTocca: () -> Unit,
    modifier: Modifier = Modifier,
    abilitato: Boolean = true,
    attenuata: Boolean = !abilitato,
    descrizioneStato: String? = null,
    destra: (@Composable () -> Unit)? = null,
) {
    val c = L.colori
    val forma = RoundedCornerShape(14.dp)
    val bordo by animateColorAsState(if (scelto) c.bordoScelto else c.riga, morbido(200), label = "bordo")
    val fondo by animateColorAsState(if (scelto) c.fondoScelto else c.superficie, morbido(200), label = "fondo")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(forma)
            .background(fondo)
            .border(2.dp, bordo, forma)
            .toggleable(scelto, enabled = abilitato, role = Role.Checkbox) { onTocca() }
            .semantics { if (descrizioneStato != null) stateDescription = descrizioneStato }
            .alpha(if (attenuata) 0.55f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Casella(scelto)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titolo, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold, lineHeight = 20.sp), color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sotto,
                style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium),
                color = c.tenue,
                // Sui telefoni stretti la data va a capo invece di sparire nei puntini.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        destra?.invoke()
    }
}

/** La casella: spuntata = accento con la spunta bianca; vuota = bordo 2. */
@Composable
private fun Casella(scelta: Boolean) {
    val c = L.colori
    val forma = RoundedCornerShape(7.dp)
    val fondo by animateColorAsState(if (scelta) c.accento else c.superficie, morbido(200), label = "casella")
    Box(
        Modifier
            .size(22.dp)
            .clip(forma)
            .background(fondo)
            .then(if (scelta) Modifier else Modifier.border(2.dp, if (c.scuro) Color(0xFF55555E) else Color(0xFFC9C7C0), forma)),
        contentAlignment = Alignment.Center,
    ) {
        if (scelta) Icon(Icone.Spunta, contentDescription = null, tint = c.suAccento, modifier = Modifier.size(14.dp))
    }
}

// --- 3. Come la vuoi -------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComeLaVuoi(s: StatoNuovaLezione, vm: NuovaLezioneViewModel) {
    val c = L.colori
    SezioneNumerata(3, "Come la vuoi") {
        val tipi = TipoLezione.entries
        Binario(tipi.map { it.nomeBreve }, tipi.indexOf(s.tipo), { vm.tipo(tipi[it]) })
        Text(s.tipo.frase, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Etichetta("Le tue indicazioni", Modifier.weight(1f))
            Etichetta(Indicazioni.contatore(s.indicazioni))
        }
        CampoBordato(
            s.indicazioni, vm::indicazioni,
            segnaposto = "Per esempio: insisti su quello che il prof chiede sempre",
            righe = 4,
            stile = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium, lineHeight = 22.sp),
            tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            descrizione = "Le tue indicazioni",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Indicazioni.SPUNTI.forEach { sp ->
                val dentro = Indicazioni.contiene(s.indicazioni, sp.frase)
                PillolaOpzione(sp.etichetta, dentro, { vm.spunto(sp.frase) }, icona = if (dentro) Icone.Spunta else Icone.Piu, ruolo = Role.Checkbox)
            }
        }
        Etichetta("Durata", Modifier.padding(top = 4.dp))
        Binario(DURATE.map { "$it min" }, DURATE.indexOf(s.durata).coerceAtLeast(0), { vm.durata(DURATE[it]) })
        Etichetta("Domande", Modifier.padding(top = 4.dp))
        Binario(listOf("Obbligatorie", "Si possono saltare"), if (s.saltabili) 1 else 0, { vm.saltabili(it == 1) })
    }
}

// --- 4. Voce ---------------------------------------------------------------------------------

@Composable
private fun SezioneVoce(s: StatoNuovaLezione, vm: NuovaLezioneViewModel) {
    val costo = s.costo
    SezioneNumerata(4, "Voce") {
        val schede = s.schedeVoci
        if (s.pronto && schede.isEmpty() && s.errore == null) {
            RigaAvviso("Nessuna voce è pronta sul server di casa: serve la chiave Mistral o una voce gratis sul NAS.")
        }
        schede.forEach { v ->
            val stima = when (v.tipo) {
                TipoVoce.ESPRESSIVA -> s.stimaEspressiva
                TipoVoce.GRATIS -> s.stimaGratis
                TipoVoce.PROVA -> s.stimaProva
            }
            val sforata = v.tipo == TipoVoce.ESPRESSIVA && costo.espressivaSpenta
            val nome = when (v.tipo) {
                TipoVoce.PROVA -> "Voce di prova"
                else -> v.voce?.nome ?: if (v.tipo == TipoVoce.GRATIS) "Voce gratis" else "Voce espressiva"
            }
            val genere = when (v.tipo) {
                TipoVoce.ESPRESSIVA -> "voce espressiva"
                TipoVoce.GRATIS -> "voce gratis"
                TipoVoce.PROVA -> "voce robotica"
            }
            val sotto = when {
                !v.attiva -> "Non ancora attiva"
                sforata -> costo.motivoSpenta ?: genere
                else -> "$genere · ${stima?.costoEur?.let(Formati::euro) ?: "…"}"
            }
            // Le voci del NAS dicono quanto lavorano: "pronta in circa 12 min".
            val pronta = if (v.attiva) Costi.prontaIn(v.voce, s.durata)?.let { "pronta in circa $it min" } else null
            RigaVoce(
                nome, sotto, pronta,
                scelta = !sforata && s.schedaScelta(v),
                attiva = v.attiva && !sforata,
                tratteggiata = !v.attiva,
                onClick = { if (v.voce != null) vm.voce(v.voce) else vm.voce(v.tipo) },
            )
        }
        if (s.voci.soloProva) RigaAvviso("Voce di prova, robotica: serve la chiave Mistral per quella vera.")
    }
}

/** Una voce: bordo accento se scelta; tratteggiata e spenta se non è ancora attiva (aspetto.md §6). */
@Composable
private fun RigaVoce(nome: String, sotto: String, dettaglio: String?, scelta: Boolean, attiva: Boolean, tratteggiata: Boolean, onClick: () -> Unit) {
    val c = L.colori
    val forma = RoundedCornerShape(16.dp)
    val bordo by animateColorAsState(if (scelta) c.accento else c.riga, morbido(200), label = "voce")
    val tratteggio = c.bordoSpento
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(forma)
            .background(if (scelta) c.fondoScelto else if (tratteggiata) c.fondoSpento else c.superficie)
            .then(
                if (tratteggiata) {
                    Modifier.drawBehind {
                        val spessore = 2.dp.toPx()
                        drawRoundRect(
                            tratteggio,
                            topLeft = androidx.compose.ui.geometry.Offset(spessore / 2, spessore / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - spessore, size.height - spessore),
                            cornerRadius = CornerRadius(16.dp.toPx() - spessore / 2),
                            style = Stroke(spessore, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                } else {
                    Modifier.border(2.dp, bordo, forma)
                },
            )
            .toggleable(scelta, enabled = attiva, role = Role.RadioButton) { onClick() }
            .semantics { contentDescription = listOfNotNull(nome, sotto, dettaglio).joinToString(", ") }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(nome, style = L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = if (attiva) c.testo else c.moltoTenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
            dettaglio?.let { Text(it, style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium), color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            sotto,
            style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold),
            color = if (attiva) c.tenue else c.moltoTenue,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

// --- La tua lezione (riepilogo in fondo) ---------------------------------------------------

@Composable
private fun Riepilogo(s: StatoNuovaLezione) {
    val c = L.colori
    val costo = s.costo
    val forma = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(forma).background(c.superficie).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Etichetta("La tua lezione")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Struttura.titolo(s.tipo, s.durata), style = L.testi.titoloScheda.copy(fontSize = 24.sp, lineHeight = 29.sp), color = c.testo)
            Text(Struttura.frase(s.tipo, s.durata), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold, lineHeight = 21.sp), color = c.tenue)
            // Dalla stima della voce scelta; i server vecchi non lo dicono e la riga non c'è.
            costo.minutiPreparazione?.takeIf { it > 0 }?.let {
                Text("Pronta in circa $it min", style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val scelti = s.pronti.filter { it.id in s.scelti }
            RigaRiepilogo("Su", s.argomento.trim().ifEmpty { "Manca l'argomento" }, manca = s.argomento.isBlank(), forte = true)
            RigaRiepilogo("Materiale", SceltaMateriale.riepilogo(scelti), manca = scelti.isEmpty())
            RigaRiepilogo("Indicazioni", Indicazioni.breve(s.indicazioni), manca = false)
            RigaRiepilogo("Per", Per.riepilogo(s.evento, s.saltabili), manca = false)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(costo.rigaMese, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo, modifier = Modifier.weight(1f))
                val dopo by animateFloatAsState((costo.dopo ?: costo.spesoMese).toFloat(), morbido(350), label = "dopo")
                Text(
                    if (costo.dopo == null) costo.rigaDopo else "dopo questa ${Formati.euro(dopo.toDouble())} di ${Formati.euro(costo.tetto)}",
                    style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo,
                )
            }
            // Il lavoro già in corso (lezioni in coda, letture): conta nel mese, ma a parte.
            costo.rigaImpegnato?.let { Text(it, style = L.testi.secondario, color = c.tenue) }
            BarraDueToni(costo.frazioneSpeso, costo.frazioneQuesta)
            Formati.siAzzera(costo.siAzzeraIl)?.let { Text(it, style = L.testi.secondario, color = c.moltoTenue) }
        }
    }
}

@Composable
fun RigaRiepilogo(etichetta: String, testo: String, manca: Boolean, forte: Boolean = false) {
    val c = L.colori
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            etichetta.uppercase(),
            style = L.testi.etichetta.copy(letterSpacing = 0.06.em),
            color = c.tenue,
            modifier = Modifier.width(92.dp).padding(top = 2.dp),
        )
        Text(
            testo,
            style = L.testi.testoMedio.copy(fontWeight = if (manca || forte) FontWeight.Bold else FontWeight.SemiBold, lineHeight = 21.sp),
            color = if (manca) c.avvisoTesto else c.testo,
            modifier = Modifier.weight(1f),
        )
    }
}

// --- La barra fissa in basso -----------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarraPrepara(s: StatoNuovaLezione, vm: NuovaLezioneViewModel, bloccatoDaRete: Boolean, modifier: Modifier) {
    val c = L.colori
    val costo = s.costo
    val abilitato = s.puoPreparare && !bloccatoDaRete
    // Sotto il pulsante spento, il perché del server (tetto, prezzi): mai un blocco muto.
    val motivo = if (s.manca == null) costo.blocco else null
    Column(
        modifier
            .fillMaxWidth()
            .bersaglio(Bersagli.RIEPILOGO)
            .background(c.superficie)
            .drawBehind { drawRect(c.riga, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Sui telefoni stretti "mese: …" va sotto invece di tagliare il riassunto.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                s.riassunto,
                style = L.testi.secondario.copy(fontWeight = FontWeight.Bold),
                color = if (s.manca != null) c.avvisoTesto else c.testo,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(s.rigaMese, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.tenue, maxLines = 1)
        }
        motivo?.let { Text(it, style = L.testi.piccolo, color = c.avvisoTesto) }
        PulsantePieno(
            if (s.inCorso) "Preparo…" else costo.etichettaPulsante,
            vm::prepara,
            Modifier.bersaglio(Bersagli.PREPARA),
            abilitato = abilitato,
            altezza = 54.dp,
            raggio = 16.dp,
        )
    }
}
