package eu.stgm.lectio.ui.altre

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Argomento
import eu.stgm.lectio.dati.CorpoEsito
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.ModificaScheda
import eu.stgm.lectio.dati.RisultatoEsito
import eu.stgm.lectio.dati.SchedaProf
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.CampoConEtichetta
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.Interruttore
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.Pallino
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.Testata
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.ui.tema.morbido
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// --- Com'è andata (aspetto.md §8) -------------------------------------------------------------

data class StatoEsito(
    val evento: Evento? = null,
    val materia: Materia? = null,
    val lezione: Lezione? = null,
    val uscite: Set<Long> = emptySet(),
    val altre: String = "",
    val voto: String = "",
    val inCorso: Boolean = false,
    val risultato: RisultatoEsito? = null,
    val errore: String? = null,
)

class ComeAndataViewModel(private val sorgente: Sorgente, private val eventoId: Long) : ViewModel() {
    private val _stato = MutableStateFlow(StatoEsito())
    val stato: StateFlow<StatoEsito> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val e = sorgente.eventi().dato?.firstOrNull { it.id == eventoId }
            val m = e?.let { ev -> sorgente.materie().dato?.firstOrNull { it.id == ev.materiaId } }
            val l = e?.lezioneId?.let { sorgente.lezione(it).dato }
            _stato.update { it.copy(evento = e, materia = m, lezione = l, errore = if (e == null) "Non trovo più questa verifica." else null) }
        }
    }

    fun uscita(id: Long, si: Boolean) = _stato.update { it.copy(uscite = if (si) it.uscite + id else it.uscite - id) }
    fun altre(t: String) = _stato.update { it.copy(altre = t) }
    fun voto(t: String) = _stato.update { it.copy(voto = t.take(10)) }

    fun salva() {
        val s = _stato.value
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            val e = sorgente.salvaEsito(eventoId, CorpoEsito(s.uscite.toList(), s.altre.trim(), s.voto.trim().ifEmpty { null }))
            _stato.update {
                when (e) {
                    is Esito.Ok -> it.copy(inCorso = false, risultato = e.dato)
                    else -> it.copy(inCorso = false, errore = e.messaggio)
                }
            }
        }
    }
}

@Composable
fun ComeAndataScreen(vm: ComeAndataViewModel, onIndietro: () -> Unit, onCaricaVerifica: (materiaId: Long) -> Unit, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Testata(onIndietro)
        val cosa = if (s.evento?.orale == true) "l'interrogazione" else "la verifica"
        TitoloPagina("Com'è andata $cosa di ${s.materia?.nome ?: ""}?")
        s.errore?.let { RigaErrore(it) }
        val r = s.risultato
        if (r != null) {
            Risultato(r)
            Text("Ho aggiornato la scheda del prof.", style = L.testi.testo, color = c.testo)
            s.materia?.let { m -> PulsanteSecondario("Carica la verifica corretta", { onCaricaVerifica(m.id) }, Modifier.fillMaxWidth(), altezza = 50.dp) }
            return@Column
        }
        val domande = s.lezione?.domande.orEmpty()
        if (domande.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Etichetta("Le domande previste", Modifier.weight(1f))
                    Etichetta("È uscita")
                }
                Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                    domande.forEachIndexed { i, d ->
                        if (i > 0) Riga()
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.testo, style = L.testi.testoMedio, color = c.testo, modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Interruttore(d.id in s.uscite, { vm.uscita(d.id, it) }, descrizione = "È uscita: ${d.testo}")
                        }
                    }
                }
            }
        }
        CampoConEtichetta("Altre domande uscite") { Campo(s.altre, vm::altre, righe = 4, segnaposto = "Una per riga") }
        CampoConEtichetta("Voto") { Campo(s.voto, vm::voto, segnaposto = "Facoltativo", tastiera = KeyboardOptions(keyboardType = KeyboardType.Text)) }
        PulsantePieno(if (s.inCorso) "Salvo…" else "Salva", vm::salva, abilitato = !s.inCorso && !senzaRete && s.evento != null)
    }
}

/** L'anello "4 di 6 domande previste" che si riempie (niente feste). */
@Composable
private fun Risultato(r: RisultatoEsito) {
    val c = L.colori
    val f by animateFloatAsState(if (r.totaleVere > 0) r.previsteUscite.toFloat() / r.totaleVere else 0f, morbido(700), label = "anello")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(120.dp).semantics { contentDescription = "${r.previsteUscite} di ${r.totaleVere} domande previste" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 12.dp.toPx()
                drawArc(c.superficie2, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(size.width - s, size.height - s), style = Stroke(s))
                drawArc(c.accento, -90f, 360f * f, false, topLeft = androidx.compose.ui.geometry.Offset(s / 2, s / 2), size = androidx.compose.ui.geometry.Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
            }
            Text("${r.previsteUscite} di ${r.totaleVere}", style = L.testi.titoloSezione, color = c.testo)
        }
        Column {
            Text("domande previste", style = L.testi.forteGrande, color = c.testo)
            Text("${r.percentuale}% di quelle uscite", style = L.testi.secondario, color = c.tenue)
        }
    }
}

// --- Modifica della scheda del prof (aspetto.md §4) -------------------------------------------

class ModificaSchedaViewModel(private val sorgente: Sorgente, private val materiaId: Long) : ViewModel() {
    private val _scheda = MutableStateFlow<SchedaProf?>(null)
    val scheda: StateFlow<SchedaProf?> = _scheda.asStateFlow()
    val errore = MutableStateFlow<String?>(null)
    val salvata = MutableStateFlow(false)

    fun carica() {
        viewModelScope.launch { _scheda.value = sorgente.scheda(materiaId).dato }
    }

    fun salva(m: ModificaScheda) {
        viewModelScope.launch {
            when (val e = sorgente.salvaScheda(materiaId, m)) {
                is Esito.Ok -> salvata.value = true
                else -> errore.value = e.messaggio
            }
        }
    }
}

/** Una riga per voce: "Cause e conseguenze · 4" diventa "Cause e conseguenze". */
fun righe(t: String): List<String> = t.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Il PUT della scheda dai campi della pagina Modifica. Un campo svuotato si manda
 * vuoto ("" e liste vuote), mai omesso: così il server lo svuota davvero (punto 15).
 */
fun modificaDaiCampi(
    scheda: SchedaProf,
    argomenti: String,
    corregge: String,
    premia: String,
    lunghezza: String,
    consigli: String,
    note: String,
): ModificaScheda {
    val vecchi = scheda.argomenti.associateBy { it.nome }
    return ModificaScheda(
        argomenti = righe(argomenti).map { vecchi[it] ?: Argomento(it, 1) },
        tipiDomanda = scheda.tipiDomanda,
        cosaCorregge = righe(corregge),
        cosaPremia = righe(premia),
        lunghezzaRisposte = lunghezza.trim(),
        consigli = righe(consigli),
        noteMie = note,
    )
}

/**
 * Il testo letto di un materiale a pezzi (punto 19): un libro arriva a 200.000
 * caratteri e in un solo testo la pagina si blocca. Paragrafi separati da righe
 * vuote, e i paragrafi lunghissimi tagliati a [massimo] caratteri (a fine parola).
 */
fun paragrafiLeggibili(testo: String?, massimo: Int = 1500): List<String> {
    if (testo.isNullOrBlank()) return emptyList()
    val pezzi = mutableListOf<String>()
    for (p in testo.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
        var resto = p
        while (resto.length > massimo) {
            val taglio = resto.lastIndexOf(' ', massimo).takeIf { it > massimo / 2 } ?: massimo
            pezzi += resto.substring(0, taglio).trim()
            resto = resto.substring(taglio).trim()
        }
        if (resto.isNotEmpty()) pezzi += resto
    }
    return pezzi
}

@Composable
fun ModificaSchedaScreen(vm: ModificaSchedaViewModel, onIndietro: () -> Unit) {
    val s by vm.scheda.collectAsStateWithLifecycle()
    val errore by vm.errore.collectAsStateWithLifecycle()
    val salvata by vm.salvata.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(salvata) { if (salvata) onIndietro() }
    val scheda = s
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Testata(onIndietro)
        TitoloPagina("Cosa chiede il prof")
        if (scheda == null) return@Column
        var argomenti by rememberSaveable { mutableStateOf(scheda.argomenti.joinToString("\n") { it.nome }) }
        var corregge by rememberSaveable { mutableStateOf(scheda.cosaCorregge.joinToString("\n")) }
        var premia by rememberSaveable { mutableStateOf(scheda.cosaPremia.joinToString("\n")) }
        var lunghezza by rememberSaveable { mutableStateOf(scheda.lunghezzaRisposte ?: "") }
        var consigli by rememberSaveable { mutableStateOf(scheda.consigli.joinToString("\n")) }
        var note by rememberSaveable { mutableStateOf(scheda.noteMie) }
        CampoConEtichetta("Argomenti che tornano") { Campo(argomenti, { argomenti = it }, righe = 3, segnaposto = "Uno per riga") }
        CampoConEtichetta("Cosa corregge") { Campo(corregge, { corregge = it }, righe = 3, segnaposto = "Uno per riga") }
        CampoConEtichetta("Cosa premia") { Campo(premia, { premia = it }, righe = 3, segnaposto = "Uno per riga") }
        CampoConEtichetta("Quanto lunghe le risposte") { Campo(lunghezza, { lunghezza = it }) }
        CampoConEtichetta("Consigli") { Campo(consigli, { consigli = it }, righe = 3, segnaposto = "Uno per riga") }
        CampoConEtichetta("Le tue note") { Campo(note, { note = it }, righe = 4) }
        errore?.let { RigaErrore(it) }
        PulsantePieno("Salva", { vm.salva(modificaDaiCampi(scheda, argomenti, corregge, premia, lunghezza, consigli, note)) })
    }
}

// --- Il testo letto di un materiale (aspetto.md §4) -------------------------------------------

class TestoMaterialeViewModel(private val sorgente: Sorgente, private val id: Long) : ViewModel() {
    val materiale = MutableStateFlow<Materiale?>(null)
    val errore = MutableStateFlow<String?>(null)
    val eliminato = MutableStateFlow(false)

    fun carica() {
        viewModelScope.launch {
            when (val e = sorgente.materialeDettaglio(id)) {
                is Esito.Ok -> materiale.value = e.dato
                else -> errore.value = e.messaggio
            }
        }
    }

    fun elimina() {
        viewModelScope.launch {
            when (val e = sorgente.eliminaMateriale(id)) {
                is Esito.Ok -> eliminato.value = true
                else -> errore.value = e.messaggio
            }
        }
    }
}

@Composable
fun TestoMaterialeScreen(vm: TestoMaterialeViewModel, onIndietro: () -> Unit, senzaRete: Boolean) {
    val m by vm.materiale.collectAsStateWithLifecycle()
    val errore by vm.errore.collectAsStateWithLifecycle()
    val eliminato by vm.eliminato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(eliminato) { if (eliminato) onIndietro() }
    var menu by remember { mutableStateOf(false) }
    var conferma by remember { mutableStateOf(false) }
    val c = L.colori
    val pezzi = remember(m?.testo) { paragrafiLeggibili(m?.testo) }
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Testata(onIndietro) {
                if (!senzaRete) eu.stgm.lectio.ui.PulsanteTondo(Icone.AltreAzioni, "Altre azioni", { menu = true })
            }
        }
        errore?.let { e -> item { RigaErrore(e) } }
        val mat = m
        if (mat != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Etichetta(TipoMateriale.da(mat.tipo).etichetta)
                    Text(mat.titolo, style = L.testi.titoloScheda, color = c.testo)
                    Text(listOfNotNull(Formati.giornoOVuoto(mat.data).takeIf { it.isNotEmpty() }, mat.caratteri?.let { "${Formati.numero(it.toLong())} caratteri letti" }).joinToString(" · "), style = L.testi.secondario, color = c.tenue)
                }
            }
            mat.avviso?.takeIf { it.isNotBlank() }?.let { a -> item { eu.stgm.lectio.ui.RigaAvviso(a) } }
            items(pezzi.size) { i ->
                SelectionContainer { Text(pezzi[i], style = L.testi.testoMedio, color = c.testo) }
            }
        }
    }
    if (menu) {
        eu.stgm.lectio.ui.Foglio(onChiudi = { menu = false }) {
            eu.stgm.lectio.ui.RigaElenco("Elimina", null, inizio = { androidx.compose.material3.Icon(Icone.Cestino, null, tint = c.testo) }, onClick = { menu = false; conferma = true })
        }
    }
    if (conferma) {
        Conferma("Eliminare questo materiale?", "Le prossime lezioni non lo useranno più.", "Elimina", { conferma = false; vm.elimina() }, { conferma = false })
    }
}

// --- Tutte le verifiche e interrogazioni in arrivo ("Vedi tutte") -----------------------------

class ProssimeViewModel(private val sorgente: Sorgente) : ViewModel() {
    val eventi = MutableStateFlow<List<Pair<Evento, Materia?>>>(emptyList())

    fun carica() {
        viewModelScope.launch {
            val oggi = LocalDate.now().toString()
            val materie = sorgente.materie().dato.orEmpty()
            eventi.value = sorgente.eventi().dato.orEmpty().filter { it.data >= oggi }.sortedBy { it.data }
                .map { e -> e to materie.firstOrNull { it.id == e.materiaId } }
        }
    }
}

@Composable
fun ProssimeScreen(vm: ProssimeViewModel, onIndietro: () -> Unit, onApriMateria: (Long) -> Unit) {
    val eventi by vm.eventi.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    val oggi = LocalDate.now()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Testata(onIndietro)
        TitoloPagina("In arrivo")
        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
            eventi.forEachIndexed { i, (e, m) ->
                if (i > 0) Riga()
                eu.stgm.lectio.ui.RigaElenco(
                    m?.nome ?: "",
                    "${Formati.tipoEvento(e)} · ${Formati.giornoOVuoto(e.data, oggi)}",
                    inizio = { Pallino(materia(m?.colore).pieno) },
                    fine = { Text(Formati.data(e.data)?.let { Formati.giorniBreve(Formati.giorniTra(oggi, it)) } ?: "", style = L.testi.secondario, color = c.tenue) },
                    onClick = { m?.let { onApriMateria(it.id) } },
                )
            }
        }
    }
}
