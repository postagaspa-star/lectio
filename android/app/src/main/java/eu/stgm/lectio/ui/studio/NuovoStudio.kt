package eu.stgm.lectio.ui.studio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.NuovaSimulata
import eu.stgm.lectio.dati.NuovoSchema
import eu.stgm.lectio.dati.RichiestaStimaSchema
import eu.stgm.lectio.dati.RichiestaStimaSimulata
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Simulata
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.Spesa
import eu.stgm.lectio.dati.StimaStudio
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.Indicazioni
import eu.stgm.lectio.logica.SceltaMateriale
import eu.stgm.lectio.logica.SpuntiStudio
import eu.stgm.lectio.logica.TestiSimulata
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
import eu.stgm.lectio.ui.lezione.ATTESA_SUGGERITI_MS
import eu.stgm.lectio.ui.lezione.RigaRiepilogo
import eu.stgm.lectio.ui.lezione.SezioneMateriale
import eu.stgm.lectio.ui.lezione.SezioneSuCosa
import eu.stgm.lectio.ui.tema.L
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

enum class GenereStudio { SCHEMA, SIMULATA }

const val ATTESA_STIMA_MS = 400L

data class StatoNuovoStudio(
    val genere: GenereStudio = GenereStudio.SCHEMA,
    val materia: Materia? = null,
    val argomento: String = "",
    val eventi: List<Evento> = emptyList(),
    val eventoId: Long? = null,
    val materiale: List<Materiale> = emptyList(),
    /** Le cartelle della materia (pillole CARTELLE, aspetto.md §42.7). */
    val cartelle: List<Cartella> = emptyList(),
    val suggeriti: Set<Long> = emptySet(),
    val sceltiAMano: Set<Long>? = null,
    val cerca: String = "",
    val forma: String = "auto",
    val livello: String = "completo",
    val indicazioni: String = "",
    val minuti: Int = 50,
    /** La stima di quello che c'è adesso: null finché non arriva, e il pulsante resta spento. */
    val stima: StimaStudio? = null,
    val spesa: Spesa? = null,
    val prova: Boolean = false,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val schemaCreato: Schema? = null,
    val simulataCreata: Simulata? = null,
    val pronto: Boolean = false,
) {
    val pronti: List<Materiale> get() = materiale.filter(SceltaMateriale::pronto)
    val auto: Boolean get() = sceltiAMano == null

    val scelti: Set<Long>
        get() {
            val ids = pronti.map { it.id }.toSet()
            return (sceltiAMano ?: suggeriti).filter { it in ids }.toSet()
        }

    val materialiDaMandare: List<Long> get() = scelti.let { s -> pronti.filter { it.id in s }.map { it.id } }
    val evento: Evento? get() = eventi.firstOrNull { it.id == eventoId }

    val manca: String?
        get() = when {
            argomento.isBlank() -> "Manca l'argomento"
            scelti.isEmpty() -> "Scegli almeno un materiale"
            else -> null
        }

    val puoPreparare: Boolean get() = pronto && manca == null && stima?.consentita == true && !inCorso

    private val base: String get() = if (genere == GenereStudio.SCHEMA) "Prepara lo schema" else "Prepara la verifica"

    /** Con la stima dice quanto costa; senza è spento e senza prezzo (aspetto.md §30.3). */
    val etichettaPulsante: String get() = stima?.let { "$base · ${Formati.euro(it.costoEur)}" } ?: base

    private val nomeFormaLunga: String
        get() = when (forma) {
            "capitoli" -> "A capitoli"
            "schede" -> "Schede per opera o autore"
            else -> "La forma la scelgo io"
        }

    /** La riga della barra ferma: "Completo · A capitoli · 3 materiali" o cosa manca. */
    val riassunto: String
        get() = manca ?: if (genere == GenereStudio.SCHEMA) {
            val f = when (forma) {
                "capitoli" -> "A capitoli"
                "schede" -> "Schede"
                else -> "Lo decide Lectio"
            }
            "${eu.stgm.lectio.logica.TestiSchema.nomeLivello(livello)} · $f · ${SceltaMateriale.quanti(scelti.size)}"
        } else {
            "$minuti min · ${SceltaMateriale.quanti(scelti.size)}"
        }

    val titoloRiepilogo: String
        get() = if (genere == GenereStudio.SCHEMA) "Schema ${eu.stgm.lectio.logica.TestiSchema.nomeLivello(livello).lowercase()}" else "Verifica di $minuti minuti"

    val sottoRiepilogo: String? get() = if (genere == GenereStudio.SCHEMA) nomeFormaLunga else null

    val spesoMese: Double get() = stima?.spesaMeseEur ?: spesa?.spesoEur ?: 0.0
    val tetto: Double get() = stima?.tettoEur ?: spesa?.tettoEur ?: 2.0

    /** "mese: 0,29 € di 2 €". */
    val rigaMese: String get() = "mese: ${Formati.euro(stima?.dopoEur ?: spesoMese)} di ${Formati.euro(tetto)}"
}

/** Nuovo schema (aspetto.md §32) e Nuova verifica simulata (§36.1): la stessa pagina. */
class NuovoStudioViewModel(
    private val sorgente: Sorgente,
    private val genere: GenereStudio,
    private val materiaId: Long,
    private val eventoIniziale: Long?,
    private val daId: Long?,
    private val oggi: () -> LocalDate = LocalDate::now,
    private val attesaSuggeritiMs: Long = ATTESA_SUGGERITI_MS,
    private val attesaStimaMs: Long = ATTESA_STIMA_MS,
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoNuovoStudio(genere = genere))
    val stato: StateFlow<StatoNuovoStudio> = _stato.asStateFlow()
    private var suggerimenti: Job? = null
    private var richiestaStima: Job? = null
    private var giroSuggeriti = 0
    private var ultimaChiave: List<Any?>? = null

    fun carica() {
        viewModelScope.launch {
            val materie = async { sorgente.materie() }
            val eventi = async { sorgente.eventi() }
            val materiale = async { sorgente.materiale(materiaId) }
            val cartelle = async { sorgente.cartelle(materiaId) }
            val spesa = async { sorgente.spesa() }
            val salute = async { sorgente.salute() }
            val giorno = oggi().toString()
            val prossimi = eventi.await().dato.orEmpty().filter { it.materiaId == materiaId && it.data >= giorno && !it.haEsito }.sortedBy { it.data }
            val schemaDa = if (genere == GenereStudio.SCHEMA) daId?.let { sorgente.schema(it).dato } else null
            val simulataDa = if (genere == GenereStudio.SIMULATA) daId?.let { sorgente.simulata(it).dato } else null
            val inizialeEvento = schemaDa?.eventoId ?: simulataDa?.eventoId ?: eventoIniziale
            val evento = prossimi.firstOrNull { it.id == inizialeEvento } ?: if (daId == null) prossimi.firstOrNull() else null
            val per = (listOfNotNull(evento) + prossimi.filter { it.id != evento?.id }).take(3).sortedBy { it.data }
            _stato.update {
                val gia = it.pronto
                it.copy(
                    materia = materie.await().dato?.firstOrNull { m -> m.id == materiaId } ?: it.materia,
                    eventi = per,
                    eventoId = if (gia) it.eventoId else evento?.id,
                    argomento = it.argomento.ifBlank { schemaDa?.argomento ?: simulataDa?.argomento ?: evento?.argomento ?: "" },
                    materiale = materiale.await().dato ?: it.materiale,
                    cartelle = cartelle.await().dato ?: it.cartelle,
                    sceltiAMano = if (gia) it.sceltiAMano else (schemaDa?.materiali ?: simulataDa?.materiali)?.toSet(),
                    forma = if (gia) it.forma else schemaDa?.forma ?: it.forma,
                    livello = if (gia) it.livello else schemaDa?.livello ?: it.livello,
                    indicazioni = if (gia) it.indicazioni else schemaDa?.indicazioni ?: simulataDa?.indicazioni ?: it.indicazioni,
                    minuti = if (gia) it.minuti else simulataDa?.minuti?.takeIf { m -> m in TestiSimulata.DURATE } ?: it.minuti,
                    spesa = spesa.await().dato ?: it.spesa,
                    prova = (salute.await() as? Esito.Ok)?.dato?.modalitaProva == true,
                    errore = if (materiale.await().dato == null) materiale.await().errore else null,
                    pronto = true,
                )
            }
            ultimaChiave = null
            chiediSuggeriti(0)
            forseStima()
        }
    }

    fun argomento(t: String) {
        val nuovo = t.take(160)
        if (nuovo == _stato.value.argomento) return
        _stato.update { it.copy(argomento = nuovo) }
        chiediSuggeriti(attesaSuggeritiMs)
        forseStima()
    }

    fun evento(id: Long?) {
        val prima = _stato.value.argomento
        _stato.update { s ->
            val e = s.eventi.firstOrNull { it.id == id }
            s.copy(eventoId = id, argomento = if (s.argomento.isBlank() && e != null) e.argomento else s.argomento)
        }
        if (_stato.value.argomento != prima) chiediSuggeriti(0)
        forseStima()
    }

    private fun chiediSuggeriti(ritardo: Long) {
        suggerimenti?.cancel()
        val giro = ++giroSuggeriti
        suggerimenti = viewModelScope.launch {
            if (ritardo > 0) delay(ritardo)
            val argomento = _stato.value.argomento.trim()
            val ids: Set<Long>? = if (argomento.isEmpty()) emptySet() else (sorgente.suggeriti(materiaId, argomento) as? Esito.Ok)?.dato?.suggeriti?.toSet()
            if (ids == null || giro != giroSuggeriti || _stato.value.argomento.trim() != argomento) return@launch
            _stato.update { it.copy(suggeriti = ids) }
            forseStima()
        }
    }

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

    fun forma(f: String) {
        _stato.update { it.copy(forma = f) }
        forseStima()
    }

    fun livello(l: String) {
        _stato.update { it.copy(livello = l) }
        forseStima()
    }

    fun minuti(m: Int) {
        _stato.update { it.copy(minuti = m) }
        forseStima()
    }

    fun indicazioni(t: String) {
        _stato.update { it.copy(indicazioni = t.take(Indicazioni.MASSIMO)) }
        forseStima()
    }

    fun spunto(frase: String) {
        _stato.update { it.copy(indicazioni = Indicazioni.alterna(it.indicazioni, frase)) }
        forseStima()
    }

    /** Quello da cui dipende la stima; null = non si può ancora chiedere. */
    private fun chiave(s: StatoNuovoStudio): List<Any?>? =
        if (!s.pronto || s.argomento.isBlank() || s.materialiDaMandare.isEmpty()) {
            null
        } else {
            listOf(s.argomento.trim(), s.materialiDaMandare, s.forma, s.livello, s.minuti, s.indicazioni.trim())
        }

    /** La stima di prima non vale più appena cambia qualcosa: pulsante spento finché non arriva. */
    private fun forseStima() {
        val s = _stato.value
        val k = chiave(s)
        if (k == ultimaChiave && (k == null || s.stima != null)) return
        ultimaChiave = k
        richiestaStima?.cancel()
        _stato.update { it.copy(stima = null) }
        if (k == null) return
        richiestaStima = viewModelScope.launch {
            delay(attesaStimaMs)
            val e = _stato.value.let { x ->
                if (genere == GenereStudio.SCHEMA) {
                    sorgente.stimaSchema(RichiestaStimaSchema(materiaId, x.argomento.trim(), x.materialiDaMandare, x.forma, x.livello, x.indicazioni.trim()))
                } else {
                    sorgente.stimaSimulata(RichiestaStimaSimulata(materiaId, x.argomento.trim(), x.materialiDaMandare, x.minuti, x.indicazioni.trim()))
                }
            }
            if (chiave(_stato.value) != k) return@launch
            _stato.update { it.copy(stima = (e as? Esito.Ok)?.dato, errore = if (e is Esito.Ok) null else e.messaggio) }
        }
    }

    fun riprova() {
        ultimaChiave = null
        carica()
    }

    fun prepara() {
        val s = _stato.value
        if (!s.puoPreparare) return
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            if (genere == GenereStudio.SCHEMA) {
                val e = sorgente.creaSchema(NuovoSchema(materiaId, s.eventoId, s.argomento.trim(), s.materialiDaMandare, s.forma, s.livello, s.indicazioni.trim()))
                if (e is Esito.Ok) sorgente.deposito.seguiSchema(e.dato.id)
                _stato.update { if (e is Esito.Ok) it.copy(inCorso = false, schemaCreato = e.dato) else it.copy(inCorso = false, errore = e.messaggio) }
            } else {
                val e = sorgente.creaSimulata(NuovaSimulata(materiaId, s.eventoId, s.argomento.trim(), s.materialiDaMandare, s.minuti, s.indicazioni.trim()))
                if (e is Esito.Ok) sorgente.deposito.seguiSimulata(e.dato.id)
                _stato.update { if (e is Esito.Ok) it.copy(inCorso = false, simulataCreata = e.dato) else it.copy(inCorso = false, errore = e.messaggio) }
            }
        }
    }
}

@Composable
fun NuovoStudioScreen(
    vm: NuovoStudioViewModel,
    onIndietro: () -> Unit,
    onSchema: (Schema) -> Unit,
    onSimulata: (Simulata) -> Unit,
    esempio: Boolean,
    senzaRete: Boolean,
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.schemaCreato) { s.schemaCreato?.let(onSchema) }
    LaunchedEffect(s.simulataCreata) { s.simulataCreata?.let(onSimulata) }
    val c = L.colori
    val schema = s.genere == GenereStudio.SCHEMA
    val bloccato = senzaRete && !esempio
    Box(Modifier.fillMaxSize().background(c.fondo)) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 168.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsanteTondo(Icone.Chiudi, "Chiudi", onIndietro, dimensione = 48.dp, dimensioneIcona = 18.dp)
                Box(Modifier.weight(1f)) { s.materia?.let { PillolaMateria(it.nome, it.colore) } }
                if (esempio) PillolaStato(TipoPillola.ESEMPIO) else if (s.prova) PillolaStato(TipoPillola.PROVA)
            }
            Text(
                if (schema) "Nuovo schema" else "Nuova verifica simulata",
                style = L.testi.titoloPagina.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = c.testo,
                modifier = Modifier.semantics { heading() },
            )
            SezioneSuCosa(s.argomento, vm::argomento, s.eventi, s.eventoId, vm::evento)
            SezioneMateriale(s.materiale, s.pronti, s.scelti, s.suggeriti, s.auto, s.cerca, vm::cerca, vm::tocca, vm::sullArgomento, vm::tutti, vm::nessuno, s.cartelle, vm::cartella)
            if (schema) ComeLoVuoi(s, vm) else ComeLaVuoi(s, vm)
            Riepilogo(s)
            s.errore?.let { RigaErrore(it, onRiprova = vm::riprova) }
            if (bloccato) RigaAvviso(if (schema) "Senza rete non si può preparare uno schema." else "Senza rete non si può preparare una verifica.")
        }
        BarraPrepara(s, vm, bloccato, Modifier.align(Alignment.BottomCenter))
    }
}

private val FORME = listOf("auto", "capitoli", "schede")
private val LIVELLI = listOf("essenziale", "completo", "approfondito")

private fun fraseForma(f: String): String = when (f) {
    "capitoli" -> "Una parte dopo l'altra, dall'inizio alla fine"
    "schede" -> "Una scheda per ogni opera o autore: dati, descrizione, analisi"
    else -> "Scelgo io la forma adatta a questo argomento"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComeLoVuoi(s: StatoNuovoStudio, vm: NuovoStudioViewModel) {
    val c = L.colori
    SezioneNumerata(3, "Come lo vuoi", Modifier.bersaglio(Bersagli.COME_LO_VUOI)) {
        Etichetta("Forma")
        Binario(listOf("Lo decide Lectio", "A capitoli", "Schede"), FORME.indexOf(s.forma).coerceAtLeast(0), { vm.forma(FORME[it]) }, pesi = listOf(1.6f, 1f, 1f))
        Text(fraseForma(s.forma), style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
        Etichetta("Livello", Modifier.padding(top = 4.dp))
        Binario(listOf("Essenziale", "Completo", "Approfondito"), LIVELLI.indexOf(s.livello).coerceAtLeast(1), { vm.livello(LIVELLI[it]) }, pesi = listOf(1f, 1f, 1.25f))
        Indicazioni(s, vm, "Per esempio: metti una tabella con le date")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SpuntiStudio.SCHEMA.forEach { sp ->
                val dentro = Indicazioni.contiene(s.indicazioni, sp.frase)
                PillolaOpzione(
                    sp.etichetta, dentro, { vm.spunto(sp.frase) },
                    if (sp.etichetta == "Tante immagini") Modifier.bersaglio(Bersagli.SPUNTO_IMMAGINI) else Modifier,
                    icona = if (dentro) Icone.Spunta else Icone.Piu, ruolo = Role.Checkbox,
                )
            }
        }
    }
}

@Composable
private fun ComeLaVuoi(s: StatoNuovoStudio, vm: NuovoStudioViewModel) {
    SezioneNumerata(3, "Come la vuoi") {
        Etichetta("Durata")
        Binario(TestiSimulata.DURATE.map { "$it min" }, TestiSimulata.DURATE.indexOf(s.minuti).coerceAtLeast(0), { vm.minuti(TestiSimulata.DURATE[it]) })
        Indicazioni(s, vm, "Per esempio: più domande sul Seicento")
    }
}

@Composable
private fun Indicazioni(s: StatoNuovoStudio, vm: NuovoStudioViewModel, segnaposto: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Etichetta("Le tue indicazioni", Modifier.weight(1f))
        Etichetta(Indicazioni.contatore(s.indicazioni))
    }
    CampoBordato(
        s.indicazioni, vm::indicazioni,
        segnaposto = segnaposto,
        righe = 4,
        stile = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium, lineHeight = 22.sp),
        tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        descrizione = "Le tue indicazioni",
    )
}

@Composable
private fun Riepilogo(s: StatoNuovoStudio) {
    val c = L.colori
    val schema = s.genere == GenereStudio.SCHEMA
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.superficie).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Etichetta(if (schema) "Il tuo schema" else "La tua verifica")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.titoloRiepilogo, style = L.testi.titoloScheda, color = c.testo)
            s.sottoRiepilogo?.let { Text(it, style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold, lineHeight = 21.sp), color = c.tenue) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val scelti = s.pronti.filter { it.id in s.scelti }
            RigaRiepilogo("Su", s.argomento.trim().ifEmpty { "Manca l'argomento" }, manca = s.argomento.isBlank(), forte = true)
            RigaRiepilogo("Materiale", SceltaMateriale.riepilogo(scelti), manca = scelti.isEmpty())
            RigaRiepilogo("Indicazioni", Indicazioni.breve(s.indicazioni).let { if (s.indicazioni.isBlank()) "Nessuna" else it }, manca = false)
            RigaRiepilogo("Per", s.evento?.let { (if (it.orale) "L'interrogazione" else "La verifica") + " di ${Formati.giornoOVuoto(it.data)}" } ?: "Nessuna data", manca = false)
            if (schema) {
                s.stima?.immagini?.takeIf { it > 0 }?.let { RigaRiepilogo("Immagini", "circa $it", manca = false) }
                s.stima?.minutiPreparazione?.takeIf { it > 0 }?.let { RigaRiepilogo("Pronto", "in circa $it min", manca = false) }
            } else {
                val verifiche = s.materia?.conteggi?.verifiche ?: 0
                RigaRiepilogo("Formato", if (verifiche > 0) TestiSimulata.formato(verifiche) else "Nessuna verifica caricata: uso un formato normale", manca = verifiche == 0)
                s.stima?.minutiPreparazione?.takeIf { it > 0 }?.let { RigaRiepilogo("Pronta", "in circa $it min", manca = false) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.rigaMese, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo)
            val t = s.tetto.takeIf { it > 0 } ?: 1.0
            BarraDueToni((s.spesoMese / t).toFloat(), ((s.stima?.costoEur ?: 0.0) / t).toFloat())
            Formati.siAzzera(s.stima?.siAzzeraIl ?: s.spesa?.siAzzeraIl)?.let { Text(it, style = L.testi.secondario, color = c.moltoTenue) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarraPrepara(s: StatoNuovoStudio, vm: NuovoStudioViewModel, bloccato: Boolean, modifier: Modifier) {
    val c = L.colori
    val motivo = if (s.manca == null && s.stima?.consentita == false) s.stima.motivo else null
    Column(
        modifier
            .fillMaxWidth()
            .background(c.superficie)
            .drawBehind { drawRect(c.riga, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                s.riassunto,
                style = L.testi.testoMedio.copy(fontWeight = FontWeight.ExtraBold),
                color = if (s.manca != null) c.avvisoTesto else c.testo,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(s.rigaMese, style = L.testi.piccolo, color = c.tenue, maxLines = 1)
        }
        motivo?.let { Text(it, style = L.testi.piccolo, color = c.avvisoTesto) }
        PulsantePieno(
            if (s.inCorso) "Preparo…" else s.etichettaPulsante,
            vm::prepara,
            abilitato = s.puoPreparare && !bloccato,
            altezza = 56.dp,
            raggio = 16.dp,
        )
    }
}
