package eu.stgm.lectio.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.DocumentoPdf
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.ParteRiassunto
import eu.stgm.lectio.dati.Riassunto
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.FormaRiassunto
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TestiRiassunto
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MenuPuntini
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.VoceMenu
import eu.stgm.lectio.ui.fondoBlocco
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.materia
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoRiassunto(
    val schema: Schema? = null,
    val materia: Materia? = null,
    val riassunti: List<Riassunto> = emptyList(),
    val forma: FormaRiassunto = FormaRiassunto.MISTO,
    val caricato: Boolean = false,
    val errore: String? = null,
    val inCorso: Boolean = false,
    val prova: Boolean = false,
    val uscito: Boolean = false,
) {
    val corrente: Riassunto? get() = riassunti.firstOrNull { it.forma == forma.codice }
    val inLavoro: Boolean get() = riassunti.any { it.inLavoro }
}

/** La pagina del riassunto (aspetto.md §41.3): una forma alla volta, con la barra a tre segmenti. */
class RiassuntoViewModel(private val sorgente: Sorgente, val schemaId: Long, forma: String) : ViewModel() {
    private val _stato = MutableStateFlow(StatoRiassunto(forma = FormaRiassunto.di(forma)))
    val stato: StateFlow<StatoRiassunto> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val elenco = sorgente.riassunti(schemaId)
            // Titolo, prezzo e colore si leggono una volta: il controllo ogni 3 s chiede solo i riassunti.
            val schema = _stato.value.schema ?: sorgente.schema(schemaId).dato
            val materie = if (_stato.value.materia == null) schema?.let { sorgente.materie().dato } else null
            val prova = if (!_stato.value.caricato) (sorgente.salute() as? Esito.Ok)?.dato?.modalitaProva ?: false else _stato.value.prova
            _stato.update {
                it.copy(
                    riassunti = elenco.dato ?: it.riassunti,
                    schema = schema ?: it.schema,
                    materia = materie?.firstOrNull { m -> m.id == schema?.materiaId } ?: it.materia,
                    caricato = true,
                    errore = if (elenco.dato == null && it.riassunti.isEmpty()) elenco.errore else null,
                    prova = prova,
                )
            }
            ricordaCorrente()
        }
    }

    /** La forma che si guarda resta sul telefono anche senza l'elenco. */
    private suspend fun ricordaCorrente() {
        val r = _stato.value.corrente ?: return
        if (!r.pronto) return
        val letto = sorgente.riassunto(r.id).dato ?: return
        _stato.update { it.copy(riassunti = it.riassunti.map { x -> if (x.id == letto.id) letto else x }) }
    }

    fun scegli(f: FormaRiassunto) {
        _stato.update { it.copy(forma = f) }
        viewModelScope.launch { ricordaCorrente() }
    }

    private fun azione(chiamata: suspend () -> Esito<*>) {
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            val e = chiamata()
            if (e is Esito.Ok) carica()
            _stato.update { it.copy(inCorso = false, errore = e.messaggio ?: it.errore) }
        }
    }

    /** Scrive la forma scelta (la barra è sulla pagina: niente foglio). */
    fun scrivi() = azione { crea() }

    private suspend fun crea(): Esito<Riassunto> {
        val e = sorgente.creaRiassunto(schemaId, _stato.value.forma.codice)
        if (e is Esito.Ok) sorgente.deposito.seguiRiassunto(e.dato.id)
        return e
    }

    fun riprova() {
        val r = _stato.value.corrente ?: return
        azione {
            val e = sorgente.riprovaRiassunto(r.id)
            if (e is Esito.Ok) sorgente.deposito.seguiRiassunto(r.id)
            e
        }
    }

    /** Rifai: si toglie e si riscrive da capo (contratto §26). */
    fun rifai() {
        val r = _stato.value.corrente ?: return
        azione {
            val e = sorgente.eliminaRiassunto(r.id)
            if (e is Esito.Ok) {
                _stato.update { s -> s.copy(riassunti = s.riassunti.filterNot { it.id == r.id }) }
                crea()
            } else {
                e
            }
        }
    }

    fun elimina() {
        val r = _stato.value.corrente ?: return
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            val e = sorgente.eliminaRiassunto(r.id)
            _stato.update {
                if (e is Esito.Ok) {
                    val resto = it.riassunti.filterNot { x -> x.id == r.id }
                    it.copy(inCorso = false, riassunti = resto, uscito = resto.isEmpty())
                } else {
                    it.copy(inCorso = false, errore = e.messaggio)
                }
            }
        }
    }
}

private val MODO_CONFERMA_RIFAI = "rifai"
private val MODO_CONFERMA_ELIMINA = "elimina"

@Composable
fun RiassuntoScreen(vm: RiassuntoViewModel, onIndietro: () -> Unit, onInLavoro: () -> Unit, sorgente: Sorgente, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.inLavoro) {
        if (s.inLavoro) {
            onInLavoro()
            while (true) {
                delay(3_000)
                vm.carica()
            }
        }
    }
    LaunchedEffect(s.uscito) { if (s.uscito) onIndietro() }
    val c = L.colori
    val offline = senzaRete && !esempio
    val r = s.corrente
    val pdf = LocalGestorePdf.current
    val pdfInCorso = r != null && pdf?.inCorso?.contains(DocumentoPdf.DiRiassunto(r.id)) == true
    val muove = LocalMovimenti.current
    var conferma by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(c.fondo).verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 672.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulsanteTondo(Icone.Indietro, "Indietro", onIndietro, dimensione = 48.dp)
                Box(Modifier.weight(1f)) { s.materia?.let { PillolaMateria(it.nome, it.colore) } }
                if (r?.pronto == true) {
                    if (pdfInCorso) {
                        Text("Preparo il PDF…", style = L.testi.piccolo, color = c.tenue, maxLines = 1)
                    } else {
                        PulsanteTondo(
                            Icone.Scarica, "Scarica PDF",
                            { if (!offline) pdf?.scarica(sorgente, DocumentoPdf.DiRiassunto(r.id), r.titolo.ifBlank { "Riassunto" }) },
                            dimensione = 48.dp, tinta = if (offline) c.moltoTenue else c.testo,
                        )
                    }
                    MenuPuntini(listOf(VoceMenu("Rifai", !offline) { conferma = MODO_CONFERMA_RIFAI }, VoceMenu("Elimina", !offline) { conferma = MODO_CONFERMA_ELIMINA }))
                }
            }
            if (esempio) {
                PillolaStato(TipoPillola.ESEMPIO)
            } else if (offline) {
                PillolaStato(TipoPillola.SENZA_RETE_BREVE)
            } else if (s.prova) {
                PillolaStato(TipoPillola.PROVA)
            }
            val titolo = r?.titolo?.takeIf { it.isNotBlank() } ?: s.schema?.nome.orEmpty()
            if (titolo.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(titolo, style = L.testi.titoloPagina.copy(fontSize = 26.sp, lineHeight = 32.sp), color = c.testo, modifier = Modifier.semantics { heading() })
                    if (r?.pronto == true && r.parole > 0) Text(TestiRiassunto.sottoTitolo(r.parole), style = L.testi.secondario, color = c.tenue)
                }
            }
            BarraForme(s.forma, s.riassunti.map { it.forma }.toSet(), offline, vm::scegli)
            r?.nota?.takeIf { it.isNotBlank() && r.pronto }?.let {
                Text(it, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.avvisoTesto, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.avvisoTenue).padding(horizontal = 16.dp, vertical = 14.dp))
            }
            s.errore?.let { RigaErrore(it) }
            AnimatedContent(
                targetState = s.forma,
                transitionSpec = { fadeIn(tween(if (muove) 180 else 0)) togetherWith fadeOut(tween(if (muove) 180 else 0)) },
                label = "forma",
            ) { forma ->
                val x = s.riassunti.firstOrNull { it.forma == forma.codice }
                when {
                    !s.caricato -> TreSegnaposto()
                    s.riassunti.isEmpty() && offline -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Si apre quando torna la rete.", style = L.testi.testo, color = c.tenue)
                        PulsantePieno("Riprova", vm::carica)
                    }
                    x == null -> Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fondoBlocco).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(forma.frase, style = L.testi.testo, color = c.tenue)
                        val prezzo = s.schema?.riassunti?.stimaEur
                        PulsantePieno(
                            if (s.prova || prezzo == null) "Scrivilo" else "Scrivilo · ${Formati.euro(prezzo)}",
                            vm::scrivi, abilitato = !offline && !s.inCorso,
                        )
                    }
                    x.inLavoro -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(x.frase?.takeIf { it.isNotBlank() } ?: "Scrivo il riassunto", style = L.testi.forte, color = c.accentoTesto)
                        TreSegnaposto()
                    }
                    x.fallito -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RigaErrore(x.errore ?: "Si è fermato.")
                        PulsantePieno("Riprova", vm::riprova, abilitato = !offline && !s.inCorso)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PulsanteTesto("Elimina", { conferma = MODO_CONFERMA_ELIMINA }, colore = c.tenue, abilitato = !offline)
                        }
                    }
                    else -> TestoRiassunto(x, materia(s.materia?.colore).pieno)
                }
            }
        }
    }
    when (conferma) {
        MODO_CONFERMA_RIFAI -> Conferma("Lo riscrivo da capo?", "Quello di adesso si cancella e ne scrivo uno nuovo.", "Riscrivilo", { conferma = null; vm.rifai() }, { conferma = null })
        MODO_CONFERMA_ELIMINA -> Conferma("Eliminare il riassunto?", "Lo schema resta. Non si può tornare indietro.", "Elimina", { conferma = null; vm.elimina() }, { conferma = null })
    }
}

/** Le tre forme: segmento attivo con fondo accento tenue e peso 800 (aspetto.md §41.3). */
@Composable
private fun BarraForme(scelta: FormaRiassunto, fatte: Set<String>, offline: Boolean, onScegli: (FormaRiassunto) -> Unit) {
    val c = L.colori
    val forma = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.superficie2).padding(2.dp).selectableGroup()) {
        FormaRiassunto.entries.forEach { f ->
            val attiva = f == scelta
            val accesa = !offline || f.codice in fatte
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(forma)
                    .background(if (attiva) c.accentoTenue else Color.Transparent)
                    .selectable(attiva, enabled = accesa, role = Role.RadioButton) { onScegli(f) }
                    .alpha(if (accesa) 1f else 0.5f)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    f.nome,
                    style = L.testi.pillola.copy(fontWeight = if (attiva) FontWeight.ExtraBold else FontWeight.SemiBold),
                    color = if (attiva) c.accentoTesto else c.testo,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TestoRiassunto(r: Riassunto, pallino: androidx.compose.ui.graphics.Color) {
    val parti = r.contenuto?.parti.orEmpty()
    Scheda(padding = PaddingValues(16.dp), spazio = 28.dp) {
        SelectionContainer {
            Column(Modifier.widthIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                parti.forEach { ParteDiRiassunto(it, pallino) }
            }
        }
    }
}

@Composable
private fun ParteDiRiassunto(p: ParteRiassunto, pallino: androidx.compose.ui.graphics.Color) {
    Column {
        p.titolo?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = L.testi.titoloSezione, color = L.colori.testo)
            Spacer(Modifier.height(10.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            p.paragrafi.forEach { TestoRicco(it, L.testi.lettura) }
        }
        if (p.paragrafi.isNotEmpty() && p.punti.isNotEmpty()) Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.punti.forEach { VoceAPunti(it, pallino) }
        }
    }
}
