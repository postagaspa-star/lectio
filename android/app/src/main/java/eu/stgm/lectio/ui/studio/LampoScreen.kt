package eu.stgm.lectio.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.CodaLampo
import eu.stgm.lectio.logica.EsitoDato
import eu.stgm.lectio.logica.SegniFonte
import eu.stgm.lectio.logica.SessioneLampo
import eu.stgm.lectio.logica.TestiLampo
import eu.stgm.lectio.logica.ToniEsito
import eu.stgm.lectio.ui.Barra
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MenuPuntini
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.VoceMenu
import eu.stgm.lectio.ui.materie.coloriEsito
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.morbido
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoLampo(
    val sessione: SessioneLampo? = null,
    val domani: Int = 0,
    val daFareDopo: Int = 0,
    val errore: String? = null,
    val caricato: Boolean = false,
    val senzaCopia: Boolean = false,
    val iniziataAlle: Long = 0,
    val finitaAlle: Long = 0,
    val avviso: String? = null,
)

/** Il Ripasso lampo (aspetto.md §35). */
class LampoViewModel(
    private val sorgente: Sorgente,
    private val materiaId: Long?,
    private val adesso: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoLampo())
    val stato: StateFlow<StatoLampo> = _stato.asStateFlow()
    private val coda = CodaLampo(sorgente.deposito, sorgente::esitoLampo)

    fun carica() {
        if (_stato.value.sessione?.let { !it.finita && it.carte.isNotEmpty() } == true) return
        viewModelScope.launch {
            // Prima partono gli esiti rimasti in coda: il server così dà le schede giuste.
            coda.invia()
            val l = sorgente.lampo(materiaId)
            _stato.update {
                it.copy(
                    sessione = l.dato?.let { d -> SessioneLampo(d.carte) } ?: it.sessione,
                    domani = l.dato?.domani ?: it.domani,
                    daFareDopo = ((l.dato?.daFare ?: 0) - (l.dato?.carte?.size ?: 0)).coerceAtLeast(0),
                    errore = if (l.dato == null) l.errore else null,
                    senzaCopia = l.dato == null && l.senzaRete,
                    caricato = true,
                    iniziataAlle = adesso(),
                    finitaAlle = 0,
                )
            }
        }
    }

    fun mostra() = _stato.update { it.copy(sessione = it.sessione?.mostra(adesso())) }

    /** Solo per il passo indietro della visita guidata. */
    fun nascondi() = _stato.update { it.copy(sessione = it.sessione?.copy(rispostaAperta = false)) }

    fun esito(esito: String): Boolean {
        val s = _stato.value.sessione ?: return false
        if (!s.esitiAttivi(adesso())) return false
        val (nuova, daMandare) = s.esito(esito, adesso())
        _stato.update { it.copy(sessione = nuova, finitaAlle = if (nuova.finita) adesso() else 0) }
        manda(daMandare)
        return true
    }

    fun annulla() = _stato.update { it.copy(sessione = it.sessione?.annulla()) }

    fun togliCorrente() {
        val s = _stato.value.sessione ?: return
        val id = s.carta?.id ?: return
        _stato.update { it.copy(sessione = s.togliCorrente()) }
        viewModelScope.launch { sorgente.togliCarta(id) }
    }

    /** Uscendo: quello fatto resta salvato, nessuna conferma. */
    fun esci() {
        val s = _stato.value.sessione ?: return
        val (nuova, sospeso) = s.uscita()
        _stato.update { it.copy(sessione = nuova) }
        manda(listOfNotNull(sospeso))
    }

    private fun manda(dati: List<EsitoDato>) {
        if (dati.isEmpty()) return
        dati.forEach(coda::accoda)
        viewModelScope.launch {
            val r = coda.invia()
            if (r.rifiutato) {
                _stato.update { it.copy(avviso = "Non ho salvato l'ultimo esito: riprovo") }
                delay(3_500)
                _stato.update { it.copy(avviso = null) }
            }
        }
    }

    fun altreSchede() {
        _stato.update { it.copy(sessione = null, caricato = false) }
        carica()
    }
}

@Composable
fun LampoScreen(vm: LampoViewModel, daMateria: Boolean, onChiudi: () -> Unit, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    DisposableEffect(vm) { onDispose { vm.esci() } }
    val c = L.colori
    val sessione = s.sessione
    val offline = senzaRete && !esempio
    val visita = LocalVisita.current
    // Visita guidata: ogni passo trova la scheda com'è giusto, anche tornando indietro.
    if (visita != null) {
        LaunchedEffect(visita.attiva, visita.indice, sessione != null, sessione?.rispostaAperta) {
            if (!visita.attiva || sessione == null) return@LaunchedEffect
            when (visita.corrente.bersaglio) {
                Bersagli.MOSTRA_RISPOSTA -> if (sessione.rispostaAperta) vm.nascondi()
                Bersagli.TRE_ESITI -> if (!sessione.rispostaAperta) vm.mostra()
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.fondo).statusBarsPadding().padding(horizontal = 16.dp)) {
        when {
            !s.caricato && sessione == null -> {
                TestaRipasso(null, onChiudi, vm, offline, esempio)
                Spacer(Modifier.height(16.dp))
                Scheda(padding = PaddingValues(20.dp)) { TreSegnaposto() }
            }
            sessione == null -> {
                TestaRipasso(null, onChiudi, vm, offline, esempio)
                Spacer(Modifier.height(24.dp))
                if (s.senzaCopia) {
                    Text("Senza rete non ho le schede di oggi.", style = L.testi.testo, color = c.testo)
                    Spacer(Modifier.height(12.dp))
                    PulsantePieno("Riprova", vm::carica)
                } else {
                    s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }
                }
            }
            sessione.carte.isEmpty() -> NienteDaFare(s.domani, daMateria, onChiudi)
            sessione.finita -> Fine(s, sessione, onChiudi, vm::altreSchede)
            else -> {
                TestaRipasso(sessione, onChiudi, vm, offline, esempio)
                Spacer(Modifier.height(4.dp))
                Barra(sessione.frazione, altezza = 4.dp, durataMs = 250)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val muove = LocalMovimenti.current
                    AnimatedContent(
                        targetState = sessione.indice,
                        transitionSpec = {
                            if (!muove) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else {
                                (slideInHorizontally(tween(240)) { it / 8 } + fadeIn(tween(240))) togetherWith
                                    (slideOutHorizontally(tween(180)) { -24 } + fadeOut(tween(180)))
                            }
                        },
                        label = "scheda",
                    ) { i -> SchedaCorrente(sessione, i) }
                }
                ZonaEsiti(sessione, vm, visita)
            }
        }
    }
    s.avviso?.let { a ->
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
            Text(a, style = L.testi.secondario, color = c.fondo, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c.testo).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun TestaRipasso(sessione: SessioneLampo?, onChiudi: () -> Unit, vm: LampoViewModel, offline: Boolean, esempio: Boolean) {
    val c = L.colori
    var conferma by remember(sessione?.indice) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsanteTondo(Icone.Chiudi, "Esci dal ripasso", onChiudi, dimensione = 48.dp, dimensioneIcona = 18.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (sessione != null) Text(sessione.conteggio, style = L.testi.numeri, color = c.tenue)
            }
            if (sessione?.puoAnnullare == true) PulsanteTesto("Annulla", vm::annulla)
            if (sessione != null) MenuPuntini(listOf(VoceMenu("Togli questa scheda", !offline) { conferma = true }))
        }
        if (esempio) {
            PillolaStato(TipoPillola.ESEMPIO)
        } else if (offline) {
            PillolaStato(TipoPillola.SENZA_RETE_BREVE)
        }
        if (conferma) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Toglierla per sempre?", style = L.testi.secondario.copy(fontWeight = FontWeight.Bold), color = c.testo, modifier = Modifier.weight(1f))
                PulsanteTesto("Togli", { conferma = false; vm.togliCorrente() }, colore = c.testo)
                PulsanteTesto("Lascia", { conferma = false }, colore = c.tenue)
            }
        }
    }
}

@Composable
private fun SchedaCorrente(sessione: SessioneLampo, indice: Int) {
    val c = L.colori
    val carta = sessione.carte.getOrNull(indice) ?: return
    val aperta = sessione.rispostaAperta && indice == sessione.indice
    val muove = LocalMovimenti.current
    val fuoco = remember { FocusRequester() }
    LaunchedEffect(aperta) { if (aperta) runCatching { fuoco.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Scheda(rilievo = true, padding = PaddingValues(20.dp), spazio = 14.dp, modifier = Modifier.heightIn(min = 280.dp)) {
            PillolaMateria(carta.materia, carta.colore)
            Text(carta.domanda, style = L.testi.domanda, color = c.testo)
            // Prima del tocco la risposta non esiste nemmeno per i lettori di schermo.
            AnimatedVisibility(
                visible = aperta,
                enter = if (muove) fadeIn(tween(220)) + slideInVertically(tween(220)) { 24 } else fadeIn(tween(0)),
            ) {
                Column(Modifier.focusRequester(fuoco).focusable(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Riga()
                    Etichetta("Risposta")
                    TestoRicco(carta.risposta, L.testi.lettura.copy(fontWeight = FontWeight.Medium, lineHeight = 25.5.sp))
                    SegniFonte.riga(carta.fonti.distinctBy { it.etichetta })?.let { Text(it, style = L.testi.fonte, color = c.tenue) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ZonaEsiti(sessione: SessioneLampo, vm: LampoViewModel, visita: eu.stgm.lectio.visita.Visita?) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 14.dp).heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
        if (!sessione.rispostaAperta) {
            PulsantePieno(
                "Mostra la risposta",
                {
                    vm.mostra()
                    visita?.azione(Bersagli.MOSTRA_RISPOSTA)
                },
                Modifier.bersaglio(Bersagli.MOSTRA_RISPOSTA),
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stretto = maxWidth < 358.dp
                val inVisita = visita?.attiva == true
                Row(Modifier.fillMaxWidth().bersaglio(Bersagli.TRE_ESITI), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PulsanteEsito("Non lo sapevo", Icone.Riprendi, ToniEsito.DA_RIVEDERE, stretto, Modifier.weight(1f)) { if (!inVisita) vm.esito("non_sapevo") }
                    PulsanteEsito("Quasi", Icone.Quasi, ToniEsito.QUASI, stretto, Modifier.weight(1f)) {
                        if (vm.esito("quasi")) visita?.azione(Bersagli.TRE_ESITI)
                    }
                    PulsanteEsito("Lo sapevo", Icone.Spunta, ToniEsito.GIUSTA, stretto, Modifier.weight(1f)) { if (!inVisita) vm.esito("sapevo") }
                }
            }
        }
    }
}

@Composable
private fun PulsanteEsito(testo: String, icona: ImageVector, tono: ToniEsito, stretto: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (fondo, inchiostro) = coloriEsito(tono)
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fondo)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icona, contentDescription = null, tint = inchiostro, modifier = Modifier.size(22.dp))
        Text(testo, style = if (stretto) L.testi.pillola else L.testi.pulsantePiccolo, color = inchiostro, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Fine(s: StatoLampo, sessione: SessioneLampo, onChiudi: () -> Unit, altre: () -> Unit) {
    val c = L.colori
    Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Scheda(padding = PaddingValues(20.dp), spazio = 14.dp) {
            Text("Ripasso finito", style = L.testi.titoloScheda, color = c.testo)
            Text(TestiLampo.durata(sessione.fatte, (s.finitaAlle - s.iniziataAlle).coerceAtLeast(0)), style = L.testi.secondario, color = c.tenue)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Riquadro(sessione.sapevo, "Lo sapevo", ToniEsito.GIUSTA, Modifier.weight(1f))
                Riquadro(sessione.quasi, "Quasi", ToniEsito.QUASI, Modifier.weight(1f))
                Riquadro(sessione.nonSapevo, "Non lo sapevo", ToniEsito.DA_RIVEDERE, Modifier.weight(1f))
            }
            if (s.domani > 0) Text(TestiLampo.domani(s.domani), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
            PulsantePieno("Fine", onChiudi)
            if (s.daFareDopo > 0) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PulsanteTesto("Continua con altre", altre) }
            }
        }
    }
}

@Composable
private fun Riquadro(numero: Int, parola: String, tono: ToniEsito, modifier: Modifier, altezzaMinima: Dp = 76.dp) {
    val (fondo, inchiostro) = coloriEsito(tono)
    val mostrato by animateIntAsState(numero, morbido(350), label = "conta")
    Column(
        modifier.heightIn(min = altezzaMinima).clip(RoundedCornerShape(16.dp)).background(fondo).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(mostrato.toString(), style = L.testi.titoloScheda, color = inchiostro)
        Text(parola, style = L.testi.piccolo.copy(fontWeight = FontWeight.Bold), color = inchiostro, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NienteDaFare(domani: Int, daMateria: Boolean, onChiudi: () -> Unit) {
    val c = L.colori
    Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.Center) {
        Scheda(padding = PaddingValues(20.dp), spazio = 12.dp) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.fattoTenue), contentAlignment = Alignment.Center) {
                Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(28.dp))
            }
            Text("Per oggi niente da ripassare", style = L.testi.titoloSezione, color = c.testo)
            if (domani > 0) Text(TestiLampo.domani(domani), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
            PulsantePieno(if (daMateria) "Torna alla materia" else "Torna a Oggi", onChiudi)
        }
    }
}
