package eu.stgm.lectio.ui.lezione

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Preparazione
import eu.stgm.lectio.logica.StatoPasso
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Barra
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.materia
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoPreparazione(
    val lezione: Lezione? = null,
    val materia: Materia? = null,
    val errore: String? = null,
    val inCorso: Boolean = false,
)

class PreparazioneViewModel(private val sorgente: Sorgente, private val lezioneId: Long) : ViewModel() {
    private val _stato = MutableStateFlow(StatoPreparazione())
    val stato: StateFlow<StatoPreparazione> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val l = sorgente.lezione(lezioneId)
            val m = l.dato?.let { lez -> sorgente.materie().dato?.firstOrNull { it.id == lez.materiaId } }
            _stato.update { it.copy(lezione = l.dato ?: it.lezione, materia = m ?: it.materia, errore = if (l.dato == null) l.errore else null) }
        }
    }

    fun riprova() = azione { sorgente.riprovaLezione(lezioneId) }
    fun annulla() = azione { sorgente.annullaLezione(lezioneId) }

    private fun azione(chiamata: suspend () -> Esito<*>) {
        _stato.update { it.copy(inCorso = true) }
        viewModelScope.launch {
            val e = chiamata()
            _stato.update { it.copy(inCorso = false, errore = e.messaggio) }
            carica()
        }
    }
}

/**
 * In preparazione (aspetto.md §6, prototipo NuovaLezione.dc.html): quattro fasi con
 * spunta, barra, "Puoi uscire: ti avviso io". Fine: spunta grande + Ascolta.
 */
@Composable
fun PreparazioneScreen(
    vm: PreparazioneViewModel,
    onIndietro: () -> Unit,
    onAscolta: (Long) -> Unit,
    onInLavoro: () -> Unit,
    controlloOgniMs: Long = 5_000,
    /** false solo per il fotografo: niente controlli ripetuti. */
    controlla: Boolean = true,
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val l = s.lezione
    // Mentre il server lavora si ricontrolla qui (la notifica la tiene il lavoro in sottofondo).
    LaunchedEffect(l?.stato) {
        if (l?.inLavoro == true && controlla) {
            onInLavoro()
            while (true) {
                delay(controlloOgniMs)
                vm.carica()
            }
        }
    }
    val c = L.colori
    val m = materia(s.materia?.colore)
    var conferma by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
        if (l == null) {
            s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Etichetta("${s.materia?.nome ?: "Lezione"} · ${l.durataMin} min", colore = m.testo)
            Text(l.argomento, style = L.testi.titoloScheda.copy(fontSize = L.testi.titoloPagina.fontSize * 0.94f), color = c.testo)
        }
        when {
            l.pronta -> Pronta(l, onAscolta)
            // Annullata: lo si dice chiaro, niente fasi né barra che sembrano ancora al lavoro.
            l.annullata -> {
                eu.stgm.lectio.ui.RigaAvviso("Lezione annullata: il server ha smesso di prepararla. Le spese già fatte restano nel conto del mese.")
            }
            else -> {
                Scheda(rilievo = true, padding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), spazio = 0.dp) {
                    Preparazione.passi(l).forEachIndexed { i, p ->
                        if (i > 0) Riga()
                        Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                            SegnoPasso(p.stato)
                            Text(
                                p.testo,
                                style = L.testi.forte.copy(fontWeight = if (p.stato == StatoPasso.IN_CORSO) FontWeight.ExtraBold else FontWeight.SemiBold),
                                color = when (p.stato) {
                                    StatoPasso.FATTO -> c.tenue
                                    StatoPasso.IN_CORSO -> c.accentoTesto
                                    StatoPasso.DA_FARE -> c.moltoTenue
                                },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                if (l.fallita) {
                    RigaErrore(l.errore ?: "La lezione non è riuscita.")
                    PulsantePieno("Riprova", vm::riprova, abilitato = !s.inCorso)
                } else {
                    Barra(Preparazione.frazione(l), altezza = 8.dp)
                    if (l.stato == "in_coda") RigaAvviso(Preparazione.frase(l))
                    Text("Puoi uscire: ti avviso io.", style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
                    l.nota?.let { Text(it, style = L.testi.secondario, color = c.tenue) }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PulsanteTesto("Annulla la lezione", { conferma = true }, colore = c.tenue)
                    }
                }
                s.errore?.let { RigaErrore(it) }
            }
        }
    }
    if (conferma) {
        Conferma(
            "Annullare la lezione?",
            "Il server smette di prepararla. Le spese già fatte restano nel conto del mese.",
            "Annulla la lezione",
            { conferma = false; vm.annulla() },
            { conferma = false },
        )
    }
}

@Composable
private fun SegnoPasso(stato: StatoPasso) {
    val c = L.colori
    when (stato) {
        StatoPasso.FATTO -> Icon(Icone.Spunta, contentDescription = "Fatto", tint = c.fatto, modifier = Modifier.size(22.dp))
        // Un anello fermo con un quarto in accento (niente rotelle che girano, §0.7 e §13).
        StatoPasso.IN_CORSO -> Canvas(Modifier.size(22.dp)) {
            val spessore = 3.dp.toPx()
            drawCircle(c.accentoTenue, radius = size.minDimension / 2 - spessore / 2, style = Stroke(spessore))
            drawArc(c.accento, -90f, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(spessore / 2, spessore / 2),
                size = androidx.compose.ui.geometry.Size(size.width - spessore, size.height - spessore), style = Stroke(spessore, cap = StrokeCap.Round))
        }
        StatoPasso.DA_FARE -> Box(Modifier.size(22.dp).border(2.dp, c.superficie2, CircleShape))
    }
}

/** La fine: la spunta grande che compare e "Ascolta" (peak-end, niente feste). */
@Composable
private fun Pronta(l: Lezione, onAscolta: (Long) -> Unit) {
    val c = L.colori
    val muove = LocalMovimenti.current
    val visibile = remember { MutableTransitionState(!muove).apply { targetState = true } }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AnimatedVisibility(visibleState = visibile, enter = scaleIn(tween(300), initialScale = 0.6f) + fadeIn(tween(300))) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.fattoTenue), contentAlignment = Alignment.Center) {
                Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(48.dp))
            }
        }
        Text("La lezione è pronta", style = L.testi.titoloSezione, color = c.testo)
        l.nota?.let { Text(it, style = L.testi.secondario, color = c.tenue) }
    }
    PulsantePieno("Ascolta", { onAscolta(l.id) }, icona = Icone.Play)
}
