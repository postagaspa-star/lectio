package eu.stgm.lectio.ui.studio

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Chiarimento
import eu.stgm.lectio.dati.CorpoChiarimento
import eu.stgm.lectio.dati.Fonte
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.SpuntiStudio
import eu.stgm.lectio.logica.TestiSchema
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.FoglioFermo
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaOpzione
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Segnaposto
import eu.stgm.lectio.ui.ardesiaTenue
import eu.stgm.lectio.ui.ardesiaTesto
import eu.stgm.lectio.ui.fondoBlocco
import eu.stgm.lectio.ui.materie.iconaMateriale
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoNonHoCapito(
    val aperto: Boolean = false,
    val materiaId: Long = 0,
    val schemaId: Long? = null,
    val sezione: Int? = null,
    val lezioneId: Long? = null,
    val posizioneS: Double? = null,
    val passaggio: String? = null,
    /** "AL MINUTO 5:12 · LA CRISI DEL TRECENTO" (dal Lettore). */
    val etichetta: String? = null,
    val domanda: String = "",
    val inAttesa: Boolean = false,
    val lungaAttesa: Boolean = false,
    val risposte: List<Chiarimento> = emptyList(),
    val errore: String? = null,
    /** "Non ancora": il campo torna sotto la risposta. */
    val altraDomanda: Boolean = false,
) {
    val ultima: Chiarimento? get() = risposte.lastOrNull()
    val mostraComandi: Boolean get() = !inAttesa && (ultima == null || altraDomanda)
}

/**
 * "Non ho capito" (aspetto.md §34). Vive con la pagina: si apre il materiale di una
 * fonte, si torna indietro e il foglio è ancora lì con la risposta.
 */
class NonHoCapitoViewModel(private val sorgente: Sorgente) : ViewModel() {
    private val _stato = MutableStateFlow(StatoNonHoCapito())
    val stato: StateFlow<StatoNonHoCapito> = _stato.asStateFlow()
    private var invio: Job? = null

    /** Da uno schema: il testo del blocco e la `n` della parte. */
    fun apriDaSchema(materiaId: Long, schemaId: Long, sezione: Int, passaggio: String) {
        _stato.value = StatoNonHoCapito(aperto = true, materiaId = materiaId, schemaId = schemaId, sezione = sezione, passaggio = passaggio)
    }

    /** Dal Lettore: il minuto e il capitolo; il testo arriva con la risposta. */
    fun apriDaLezione(materiaId: Long, lezioneId: Long, posizioneS: Double, etichetta: String) {
        _stato.value = StatoNonHoCapito(aperto = true, materiaId = materiaId, lezioneId = lezioneId, posizioneS = posizioneS, etichetta = etichetta)
    }

    fun domanda(t: String) = _stato.update { it.copy(domanda = t.take(TestiSchema.MASSIMO_DOMANDA), errore = null) }

    /** Lo spunto riempie il campo. */
    fun spunto(frase: String) = _stato.update { it.copy(domanda = frase, errore = null) }

    fun invia() {
        val s = _stato.value
        if (s.inAttesa) return
        _stato.update { it.copy(inAttesa = true, lungaAttesa = false, errore = null) }
        val orologio = viewModelScope.launch {
            delay(15_000)
            _stato.update { it.copy(lungaAttesa = true) }
        }
        invio = viewModelScope.launch {
            val e = sorgente.chiarisci(
                CorpoChiarimento(
                    materiaId = s.materiaId,
                    schemaId = s.schemaId,
                    sezione = s.sezione,
                    passaggio = s.passaggio?.take(TestiSchema.MASSIMO_PASSAGGIO),
                    lezioneId = s.lezioneId,
                    posizioneS = s.posizioneS,
                    domanda = s.domanda.trim(),
                ),
            )
            orologio.cancel()
            _stato.update {
                when (e) {
                    // `trovato: false` non è un errore: è una risposta come le altre.
                    is Esito.Ok -> it.copy(
                        inAttesa = false, lungaAttesa = false, risposte = it.risposte + e.dato, domanda = "", altraDomanda = false,
                        passaggio = it.passaggio ?: e.dato.passaggio,
                    )
                    else -> it.copy(inAttesa = false, lungaAttesa = false, errore = e.messaggio)
                }
            }
        }
    }

    fun nonAncora() = _stato.update { it.copy(altraDomanda = true) }

    fun chiudi() {
        invio?.cancel()
        _stato.value = StatoNonHoCapito()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoglioNonHoCapito(
    vm: NonHoCapitoViewModel,
    senzaRete: Boolean,
    onAggiungiMateriale: (Long) -> Unit,
    onApriFonte: (Fonte) -> Unit,
    onChiuso: () -> Unit = {},
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    if (!s.aperto) return
    val scorre: ScrollState = rememberScrollState()
    val c = L.colori
    // La risposta nuova si porta in vista.
    LaunchedEffect(s.risposte.size) { if (s.risposte.isNotEmpty()) scorre.animateScrollTo(scorre.maxValue) }
    val chiudi = {
        vm.chiudi()
        onChiuso()
    }
    FoglioFermo(
        onChiudi = chiudi,
        titolo = "Non ho capito",
        scorrimento = scorre,
        piede = {
            val ultima = s.ultima
            when {
                s.inAttesa -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TreSegnaposto()
                    Text(if (s.lungaAttesa) "Ci vuole ancora qualche secondo" else "Rileggo i tuoi documenti…", style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
                }
                s.mostraComandi -> Comandi(s, vm, senzaRete)
                ultima != null && ultima.trovato -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulsantePieno("Ho capito", chiudi, Modifier.weight(1f), altezza = 52.dp)
                    PulsanteSecondario("Non ancora", vm::nonAncora, Modifier.weight(1f), altezza = 52.dp, raggio = 18.dp, stile = L.testi.pulsante)
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulsantePieno(
                        "Aggiungi materiale",
                        {
                            val materia = s.materiaId
                            chiudi()
                            onAggiungiMateriale(materia)
                        },
                        Modifier.weight(1.4f), altezza = 52.dp,
                    )
                    PulsanteSecondario("Chiudi", chiudi, Modifier.weight(1f), altezza = 52.dp, raggio = 18.dp, stile = L.testi.pulsante)
                }
            }
        },
    ) {
        s.etichetta?.let { Etichetta(it) }
        s.passaggio?.takeIf { it.isNotBlank() }?.let { RiquadroPassaggio(it) }
        s.risposte.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 1.dp).background(c.riga))
            if (r.trovato) Risposta(r, onApriFonte) else NonCe(r)
        }
        s.errore?.let { RigaErrore(it, onRiprova = if (senzaRete) null else vm::invia) }
    }
}

@Composable
private fun RiquadroPassaggio(testo: String) {
    val c = L.colori
    var tutto by remember { mutableStateOf(false) }
    Text(
        testo,
        style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium),
        color = c.testo,
        maxLines = if (tutto) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fondoBlocco)
            .clickable(onClickLabel = "Leggi tutto", role = Role.Button) { tutto = !tutto }.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Risposta(r: Chiarimento, onApriFonte: (Fonte) -> Unit) {
    val c = L.colori
    TestoRicco(r.risposta, L.testi.lettura)
    if (r.fonti.isNotEmpty()) {
        Column(Modifier.bersaglio(Bersagli.FONTI_RISPOSTA)) {
            Etichetta("Da dove l'ho preso")
            r.fonti.forEach { f -> RigaFonte(f, onApriFonte) }
        }
    }
    Text(
        if (r.costoEur < 0.01) "Costata meno di 1 cent" else "Costata ${Formati.euro(r.costoEur)}",
        style = L.testi.fonte, color = c.tenue,
    )
}

/** Una fonte: icona del tipo, etichetta, titolo, freccia. Apre il materiale. */
@Composable
fun RigaFonte(f: Fonte, onApri: (Fonte) -> Unit) {
    val c = L.colori
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onApri(f) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(iconaMateriale(f.tipo), contentDescription = null, tint = c.tenue, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(f.etichetta, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold, lineHeight = 20.sp()), color = c.testo)
            if (f.titolo.isNotBlank() && !f.etichetta.contains(f.titolo)) Text(f.titolo, style = L.testi.fonte, color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icone.Avanti, contentDescription = null, tint = c.tenue, modifier = Modifier.size(18.dp))
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** Nei documenti non c'è: riquadro ardesia, mai rosso (aspetto.md §34). */
@Composable
private fun NonCe(r: Chiarimento) {
    val c = L.colori
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.ardesiaTenue).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icone.Domanda, contentDescription = null, tint = c.ardesiaTesto, modifier = Modifier.size(20.dp))
            Text("Nei tuoi documenti non c'è", style = L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = c.ardesiaTesto)
        }
        TestoRicco(r.risposta, L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), c.ardesiaTesto)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Comandi(s: StatoNonHoCapito, vm: NonHoCapitoViewModel, senzaRete: Boolean) {
    val c = L.colori
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SpuntiStudio.NON_HO_CAPITO.forEach { sp -> PillolaOpzione(sp.etichetta, s.domanda == sp.frase, { vm.spunto(sp.frase) }) }
    }
    BasicTextField(
        value = s.domanda,
        onValueChange = vm::domanda,
        maxLines = 3,
        textStyle = L.testi.testo.copy(color = c.testo),
        cursorBrush = SolidColor(c.accento),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Cosa non ti è chiaro" },
        decorationBox = { campo ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.superficie2).padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (s.domanda.isEmpty()) Text("Cosa non ti è chiaro", style = L.testi.testo, color = c.moltoTenue)
                campo()
            }
        },
    )
    if (s.domanda.length >= 260) Text("${s.domanda.length} / ${TestiSchema.MASSIMO_DOMANDA}", style = L.testi.fonte, color = c.tenue, modifier = Modifier.align(Alignment.End))
    PulsantePieno("Spiegamelo", vm::invia, altezza = 56.dp, abilitato = !senzaRete)
    if (senzaRete) Text("Senza rete non posso rileggere i tuoi documenti.", style = L.testi.secondario, color = c.tenue)
}

/** Il segnaposto sottile usato qui e altrove quando si aspetta un'immagine. */
@Composable
fun SegnapostoAltezza(altezzaDp: Int) = Segnaposto(altezza = altezzaDp.dp)

/** Il tondo col numero della parte: tenue e testo della materia (28 dp, 14 / 800). */
@Composable
fun NumeroParte(n: Int, tenue: androidx.compose.ui.graphics.Color, testo: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(tenue), contentAlignment = Alignment.Center) {
        Text(n.toString(), style = L.testi.pillola.copy(fontWeight = FontWeight.ExtraBold), color = testo)
    }
}
