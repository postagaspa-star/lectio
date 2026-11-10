package eu.stgm.lectio.ui.studio

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.CopiaSimulata
import eu.stgm.lectio.dati.CorpoSalvaSimulata
import eu.stgm.lectio.dati.DocumentoPdf
import eu.stgm.lectio.dati.DomandaSimulata
import eu.stgm.lectio.dati.EsitoDomandaSimulata
import eu.stgm.lectio.dati.Fonte
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Simulata
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.SegniFonte
import eu.stgm.lectio.logica.TempoSimulata
import eu.stgm.lectio.logica.TestiSimulata
import eu.stgm.lectio.logica.ToniEsito
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.daRitentare
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.FoglioFermo
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.bordoSpento
import eu.stgm.lectio.ui.fondoSpento
import eu.stgm.lectio.ui.materie.coloriEsito
import eu.stgm.lectio.ui.rispostaFondo
import eu.stgm.lectio.ui.rispostaTesto
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.ui.tema.morbido
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatoSalva { FERMO, SALVO, SALVATA, SUL_TELEFONO, ERRORE }

data class StatoSimulata(
    val simulata: Simulata? = null,
    val materia: Materia? = null,
    val caricato: Boolean = false,
    val errore: String? = null,
    val inCorso: Boolean = false,
    val risposte: Map<String, String> = emptyMap(),
    val secondi: Int = 0,
    val tempoNascosto: Boolean = false,
    val salva: StatoSalva = StatoSalva.FERMO,
    val indice: Int = 0,
    val avvisoCinque: Boolean = false,
    val avvisoFine: Boolean = false,
    val erroreConsegna: String? = null,
    val cartaFatta: Boolean = false,
    val eliminata: Boolean = false,
    /** I secondi contati sul telefono: per "Consegnata dopo 56 minuti su 50". */
    val secondiLocali: Int? = null,
)

/** La verifica simulata (aspetto.md §36): ogni risposta si scrive prima sul telefono, poi si manda. */
class SimulataViewModel(
    private val sorgente: Sorgente,
    val simulataId: Long,
    private val attesaSalvaMs: Long = ATTESA_SALVA_MS,
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoSimulata(tempoNascosto = sorgente.deposito.tempoNascosto))
    val stato: StateFlow<StatoSimulata> = _stato.asStateFlow()
    private var invio: Job? = null
    private var dieciSecondi = 0

    private val deposito get() = sorgente.deposito

    fun carica() {
        viewModelScope.launch {
            val l = sorgente.simulata(simulataId)
            val materie = l.dato?.let { sorgente.materie().dato }
            val copia = deposito.copia(simulataId)
            _stato.update {
                val s = l.dato ?: it.simulata
                it.copy(
                    simulata = s,
                    materia = materie?.firstOrNull { m -> m.id == s?.materiaId } ?: it.materia,
                    caricato = true,
                    errore = if (s == null) l.errore else null,
                    // Mentre si scrive vale quello che c'è a schermo.
                    risposte = if (it.caricato) it.risposte else TestiSimulata.unisci(s?.risposte.orEmpty(), copia?.risposte, copia?.daInviare == true),
                    secondi = if (it.caricato) it.secondi else copia?.secondi ?: 0,
                    secondiLocali = copia?.secondi ?: it.secondiLocali,
                    avvisoFine = if (it.caricato) it.avvisoFine else (copia?.avvisatoFine == true && copia.fineChiuso.not()),
                    salva = if (!it.caricato && copia?.daInviare == true) StatoSalva.SUL_TELEFONO else it.salva,
                )
            }
            if (_stato.value.salva == StatoSalva.SUL_TELEFONO && !l.senzaRete) invia()
        }
    }

    fun tempo() {
        val nuovo = !_stato.value.tempoNascosto
        deposito.tempoNascosto = nuovo
        _stato.update { it.copy(tempoNascosto = nuovo, avvisoCinque = false) }
    }

    /** Un secondo a pagina aperta: si scrive sul dispositivo ogni 10 s (aspetto.md §36.3). */
    fun passaUnSecondo() {
        val s = _stato.value.simulata ?: return
        if (!s.inCorso) return
        val secondi = _stato.value.secondi + 1
        var copia = deposito.copia(simulataId) ?: CopiaSimulata()
        var cambiata = false
        _stato.update { it.copy(secondi = secondi) }
        if (TempoSimulata.tempoDiAvviso(s.minuti, secondi) && !copia.avvisato5) {
            copia = copia.copy(avvisato5 = true)
            cambiata = true
            if (!_stato.value.tempoNascosto) {
                _stato.update { it.copy(avvisoCinque = true) }
                viewModelScope.launch {
                    delay(4_000)
                    _stato.update { it.copy(avvisoCinque = false) }
                }
            }
        }
        if (TempoSimulata.finito(s.minuti, secondi) && !copia.avvisatoFine) {
            copia = copia.copy(avvisatoFine = true)
            cambiata = true
            _stato.update { it.copy(avvisoFine = true) }
        }
        if (++dieciSecondi >= 10 || cambiata) {
            dieciSecondi = 0
            deposito.scriviCopia(simulataId, copia.copy(secondi = secondi))
        }
    }

    fun chiudiAvvisoFine() {
        _stato.update { it.copy(avvisoFine = false) }
        deposito.scriviCopia(simulataId, (deposito.copia(simulataId) ?: CopiaSimulata()).copy(fineChiuso = true))
    }

    fun inizia() {
        val s = _stato.value.simulata ?: return
        _stato.update { it.copy(simulata = s.copy(stato = "in_corso"), indice = 0) }
        deposito.scriviCopia(simulataId, (deposito.copia(simulataId) ?: CopiaSimulata()).copy(daInviare = true))
        viewModelScope.launch { invia() }
    }

    fun vai(i: Int) = _stato.update { it.copy(indice = i.coerceIn(0, (it.simulata?.domande?.lastIndex ?: 0))) }

    fun scrivi(n: Int, testo: String) {
        val nuove = _stato.value.risposte + (n.toString() to testo)
        _stato.update { it.copy(risposte = nuove, salva = StatoSalva.SALVO) }
        deposito.scriviCopia(simulataId, (deposito.copia(simulataId) ?: CopiaSimulata()).copy(risposte = nuove, secondi = _stato.value.secondi, daInviare = true))
        invio?.cancel()
        invio = viewModelScope.launch {
            delay(attesaSalvaMs)
            invia()
        }
    }

    /** Si manda tutta la mappa: il server fonde, così un invio perso si recupera col prossimo. */
    suspend fun invia() {
        val risposte = _stato.value.risposte
        val e = sorgente.salvaSimulata(simulataId, CorpoSalvaSimulata(risposte, iniziata = true))
        // Se nel frattempo si è scritto altro, ci pensa l'invio di quello.
        val uguali = _stato.value.risposte == risposte
        when {
            e is Esito.Ok -> {
                if (uguali) {
                    deposito.scriviCopia(simulataId, (deposito.copia(simulataId) ?: CopiaSimulata()).copy(daInviare = false))
                    _stato.update { it.copy(salva = StatoSalva.SALVATA, simulata = it.simulata?.copy(stato = e.dato.stato)) }
                }
            }
            e.daRitentare -> if (uguali) _stato.update { it.copy(salva = StatoSalva.SUL_TELEFONO) }
            else -> if (uguali) _stato.update { it.copy(salva = StatoSalva.ERRORE) }
        }
    }

    fun riprovaSalva() {
        _stato.update { it.copy(salva = StatoSalva.SALVO) }
        viewModelScope.launch { invia() }
    }

    /** Senza rete si riprova da soli: la scritta resta "Salvata sul telefono". */
    fun riprovaDiNascosto() {
        viewModelScope.launch { invia() }
    }

    fun consegna(fatto: () -> Unit) {
        _stato.update { it.copy(inCorso = true, erroreConsegna = null) }
        viewModelScope.launch {
            invia()
            val e = sorgente.consegnaSimulata(simulataId)
            if (e is Esito.Ok) {
                deposito.seguiSimulata(simulataId)
                deposito.scriviCopia(simulataId, (deposito.copia(simulataId) ?: CopiaSimulata()).copy(secondi = _stato.value.secondi, daInviare = false))
                _stato.update { it.copy(inCorso = false, simulata = e.dato, secondiLocali = it.secondi) }
                fatto()
            } else {
                _stato.update { it.copy(inCorso = false, erroreConsegna = "Non sono riuscito a consegnare. Le risposte sono salve.") }
            }
        }
    }

    private fun azione(chiamata: suspend () -> Esito<*>) {
        _stato.update { it.copy(inCorso = true) }
        viewModelScope.launch {
            val e = chiamata()
            if (e is Esito.Ok) carica()
            _stato.update { it.copy(inCorso = false, errore = e.messaggio ?: it.errore) }
        }
    }

    fun annulla() = azione { sorgente.annullaSimulata(simulataId) }
    fun riprova() = azione {
        val e = sorgente.riprovaSimulata(simulataId)
        if (e is Esito.Ok) deposito.seguiSimulata(simulataId)
        e
    }

    fun elimina() {
        viewModelScope.launch {
            val e = sorgente.eliminaSimulata(simulataId)
            if (e is Esito.Ok) deposito.smettiSimulata(simulataId)
            _stato.update { it.copy(eliminata = e is Esito.Ok, errore = e.messaggio) }
        }
    }

    fun cartaScaricata() = _stato.update { it.copy(cartaFatta = true) }

    private companion object {
        const val ATTESA_SALVA_MS = 800L
    }
}

data class AzioniSimulata(
    val indietro: () -> Unit = {},
    val apriMateriale: (Long) -> Unit = {},
    val ripassa: (Long) -> Unit = {},
    val rifai: (Simulata) -> Unit = {},
    val inLavoro: () -> Unit = {},
)

@Composable
fun SimulataScreen(vm: SimulataViewModel, azioni: AzioniSimulata, sorgente: Sorgente, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val q = s.simulata
    val attesa = q?.inLavoro == true || q?.inCorrezione == true
    LaunchedEffect(q?.stato) {
        if (attesa) {
            azioni.inLavoro()
            while (true) {
                delay(4_000)
                vm.carica()
            }
        }
    }
    LaunchedEffect(s.eliminata) { if (s.eliminata) azioni.indietro() }
    val offline = senzaRete && !esempio
    val c = L.colori
    when {
        q?.inCorso == true -> MentreLaFai(q, s, vm, azioni, esempio, offline)
        q?.corretta == true && q.esito != null -> Risultati(q, s, azioni, esempio)
        else -> Column(
            Modifier.fillMaxSize().background(c.fondo).verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = MARGINE, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, dimensione = 48.dp)
                s.materia?.let { PillolaMateria(it.nome, it.colore, Modifier.weight(1f, fill = false)) }
            }
            if (q != null) Text(q.nome, style = L.testi.titoloScheda, color = c.testo, modifier = Modifier.semantics { heading() })
            when {
                !s.caricato -> TreSegnaposto()
                q == null -> {
                    Text("Si apre quando torna la rete.", style = L.testi.testo, color = c.tenue)
                    s.errore?.takeIf { !offline }?.let { RigaErrore(it) }
                    PulsantePieno("Riprova", vm::carica)
                }
                q.inLavoro -> VistaInLavoro(q.frase?.takeIf { it.isNotBlank() } ?: if (q.stato == "in_coda") "In coda" else "La sto preparando", q.fatto, q.totale, vm::annulla.takeIf { !offline })
                q.inCorrezione -> VistaInLavoro("La sto correggendo", q.fatto, q.totale, null)
                q.fallita || q.annullata -> {
                    if (q.fallita) RigaErrore(q.errore ?: "Non è riuscita.") else Text("Annullata.", style = L.testi.testo, color = c.tenue)
                    PulsantePieno("Riprova", vm::riprova, abilitato = !s.inCorso && !offline)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PulsanteTesto("Elimina", vm::elimina, colore = c.tenue, abilitato = !offline) }
                }
                q.pronta -> Pronta(q, s, vm, sorgente, offline)
            }
            if (q != null) s.errore?.let { RigaErrore(it) }
        }
    }
}

@Composable
private fun Pronta(q: Simulata, s: StatoSimulata, vm: SimulataViewModel, sorgente: Sorgente, offline: Boolean) {
    val c = L.colori
    val pdf = LocalGestorePdf.current
    val inCorso = pdf?.inCorso?.contains(DocumentoPdf.DiSimulata(q.id)) == true
    Text(TestiSimulata.intestazione(q), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
    q.nota?.takeIf { it.isNotBlank() }?.let { RigaAvviso(it) }
    PulsantePieno("Inizia", vm::inizia)
    PulsanteSecondario(
        if (inCorso) "Preparo il PDF…" else "Fai su carta",
        {
            pdf?.scarica(sorgente, DocumentoPdf.DiSimulata(q.id), q.nome)
            vm.cartaScaricata()
        },
        Modifier.fillMaxWidth(), altezza = 52.dp, raggio = 16.dp, abilitato = !inCorso && !offline,
    )
    if (s.cartaFatta) Text("Quando hai finito, premi Inizia e copia qui le risposte: te le correggo.", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.tenue)
}

// --- Mentre la fai (telefono: una domanda alla volta) --------------------------------------------

@Composable
private fun MentreLaFai(q: Simulata, s: StatoSimulata, vm: SimulataViewModel, azioni: AzioniSimulata, esempio: Boolean, offline: Boolean) {
    val c = L.colori
    val visita = LocalVisita.current
    val ciclo = LocalLifecycleOwner.current
    // Il tempo conta solo con la pagina aperta (aspetto.md §36.3).
    LaunchedEffect(q.id, ciclo) {
        ciclo.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1_000)
                vm.passaUnSecondo()
            }
        }
    }
    LaunchedEffect(s.salva) {
        while (s.salva == StatoSalva.SUL_TELEFONO) {
            delay(15_000)
            vm.riprovaDiNascosto()
        }
    }
    var esci by remember { mutableStateOf(false) }
    var foglioDomande by remember { mutableStateOf(false) }
    var foglioConsegna by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = !foglioDomande && !foglioConsegna && visita?.attiva != true) { esci = true }
    val d = q.domande.getOrNull(s.indice)
    Column(Modifier.fillMaxSize().background(c.fondo).imePadding().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsanteTondo(Icone.Chiudi, "Esci", { esci = true }, dimensione = 48.dp, dimensioneIcona = 18.dp)
            Text("Domanda ${s.indice + 1} di ${q.domande.size}", style = L.testi.pillola, color = c.testo, modifier = Modifier.weight(1f).padding(start = 12.dp))
            PillolaTempo(q, s) {
                vm.tempo()
                visita?.azione(Bersagli.TEMPO)
            }
        }
        if (esempio) {
            PillolaStato(TipoPillola.ESEMPIO, Modifier.padding(start = 16.dp, bottom = 6.dp))
        } else if (offline) {
            PillolaStato(TipoPillola.SENZA_RETE_BREVE, Modifier.padding(start = 16.dp, bottom = 6.dp))
        }
        if (s.avvisoCinque) RigaAvviso("Mancano 5 minuti", Modifier.padding(horizontal = 16.dp))
        if (s.avvisoFine) {
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.superficie2).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Il tempo è finito. Puoi continuare.", style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo, modifier = Modifier.weight(1f))
                PulsanteTondo(Icone.Chiudi, "Chiudi l'avviso", vm::chiudiAvvisoFine, fondo = androidx.compose.ui.graphics.Color.Transparent, dimensione = 48.dp, dimensioneIcona = 16.dp)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (d != null) {
                val inizioGruppo = d.sezione != null && q.domande.getOrNull(s.indice - 1)?.sezione != d.sezione
                if (inizioGruppo) Etichetta(d.sezione.orEmpty(), Modifier.padding(start = 8.dp))
                SchedaDomanda(d, s, vm)
            }
        }
        BarraDomande(q, s, vm, onIndice = { foglioDomande = true }, onConsegna = { foglioConsegna = true })
    }
    if (esci) {
        Conferma("Uscire? Le risposte restano salvate.", "Puoi riprenderla da Oggi o dalla materia.", "Esci", { esci = false; azioni.indietro() }, { esci = false }, pericolosa = false, annulla = "Resto")
    }
    if (foglioDomande) {
        FoglioDomande(q, s, vm, { foglioDomande = false }) {
            foglioDomande = false
            foglioConsegna = true
        }
    }
    if (foglioConsegna) {
        FoglioFermo(
            onChiudi = { foglioConsegna = false },
            titolo = "Consegnare?",
            piede = {
                s.erroreConsegna?.let { RigaErrore(it) }
                if (offline) Text("Senza rete non posso correggerla. Le risposte restano salvate.", style = L.testi.secondario, color = c.tenue)
                PulsantePieno(if (s.erroreConsegna != null) "Riprova" else "Consegna", { vm.consegna { foglioConsegna = false } }, abilitato = !offline && !s.inCorso)
                PulsanteSecondario("Torna alle domande", { foglioConsegna = false }, Modifier.fillMaxWidth(), altezza = 52.dp, raggio = 16.dp)
            },
        ) {
            Text(TestiSimulata.rigaConsegna(q.domande, s.risposte), style = L.testi.forte.copy(fontWeight = FontWeight.SemiBold), color = c.testo)
        }
    }
}

@Composable
private fun PillolaTempo(q: Simulata, s: StatoSimulata, onClick: () -> Unit) {
    val c = L.colori
    Box(Modifier.bersaglio(Bersagli.TEMPO).size(width = if (s.tempoNascosto) 48.dp else androidx.compose.ui.unit.Dp.Unspecified, height = 48.dp).clickable(role = Role.Button, onClickLabel = if (s.tempoNascosto) "Mostra il tempo" else "Nascondi il tempo", onClick = onClick), contentAlignment = Alignment.Center) {
        if (s.tempoNascosto) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.superficie2), contentAlignment = Alignment.Center) {
                Icon(Icone.Orologio, contentDescription = "Tempo nascosto", tint = c.tenue, modifier = Modifier.size(18.dp))
            }
        } else {
            Row(Modifier.height(44.dp).clip(CircleShape).background(c.superficie2).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icone.Orologio, contentDescription = null, tint = c.tenue, modifier = Modifier.size(16.dp))
                Text(TempoSimulata.etichetta(q.minuti, s.secondi), style = L.testi.numeri.copy(fontWeight = FontWeight.Bold), color = c.tenue, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun SchedaDomanda(d: DomandaSimulata, s: StatoSimulata, vm: SimulataViewModel) {
    val c = L.colori
    Scheda(padding = PaddingValues(20.dp), spazio = 12.dp) {
        Etichetta("Domanda ${d.n} · ${eu.stgm.lectio.logica.numeroBreve(d.punti)} punti")
        Text(d.testo, style = L.testi.testo.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold), color = c.testo)
        val valore = s.risposte[d.n.toString()].orEmpty()
        BasicTextField(
            value = valore,
            onValueChange = { vm.scrivi(d.n, it) },
            minLines = 5,
            textStyle = L.testi.testo.copy(color = c.testo),
            cursorBrush = SolidColor(c.accento),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "La tua risposta" },
            decorationBox = { campo ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.superficie2).padding(14.dp)) {
                    if (valore.isEmpty()) Text("Scrivi la tua risposta", style = L.testi.testo, color = c.moltoTenue)
                    campo()
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when (s.salva) {
                StatoSalva.FERMO -> Unit
                StatoSalva.SALVO -> Text("Salvo…", style = L.testi.piccolo, color = c.tenue)
                StatoSalva.SALVATA -> Text("Salvata", style = L.testi.piccolo, color = c.tenue)
                StatoSalva.SUL_TELEFONO -> Text("Salvata sul telefono", style = L.testi.piccolo, color = c.tenue)
                StatoSalva.ERRORE -> {
                    Text("Non riesco a salvare", style = L.testi.piccolo, color = c.erroreTesto)
                    PulsanteTesto("Riprova", vm::riprovaSalva, colore = c.erroreTesto)
                }
            }
        }
    }
}

@Composable
private fun BarraDomande(q: Simulata, s: StatoSimulata, vm: SimulataViewModel, onIndice: () -> Unit, onConsegna: () -> Unit) {
    val ultima = s.indice >= q.domande.lastIndex
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PulsanteTondo(Icone.Indietro, "Domanda prima", { vm.vai(s.indice - 1) }, dimensione = 48.dp)
        if (ultima) {
            PulsantePieno("Consegna", onConsegna, Modifier.weight(1f), altezza = 48.dp, raggio = 14.dp)
        } else {
            PulsanteSecondario("Avanti", { vm.vai(s.indice + 1) }, Modifier.weight(1f), altezza = 48.dp)
        }
        PulsanteTondo(Icone.Indice, "Tutte le domande", onIndice, dimensione = 48.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoglioDomande(q: Simulata, s: StatoSimulata, vm: SimulataViewModel, onChiudi: () -> Unit, onConsegna: () -> Unit) {
    val c = L.colori
    FoglioFermo(
        onChiudi = onChiudi,
        titolo = "Le domande",
        piede = { PulsantePieno("Consegna", onConsegna) },
    ) {
        FlowRow(maxItemsInEachRow = 6, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            q.domande.forEachIndexed { i, d ->
                val scritta = !s.risposte[d.n.toString()].isNullOrBlank()
                val corrente = i == s.indice
                val forma = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(forma)
                        .background(if (scritta) c.fattoTenue else androidx.compose.ui.graphics.Color.Transparent)
                        .border(2.dp, if (corrente) c.accento else if (scritta) c.fattoTenue else c.riga, forma)
                        .clickable(role = Role.Button) {
                            vm.vai(i)
                            onChiudi()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text((i + 1).toString(), style = L.testi.pillola, color = if (scritta) c.fattoTesto else c.testo)
                }
            }
        }
    }
}

// --- Risultati ------------------------------------------------------------------------------------

@Composable
private fun Risultati(q: Simulata, s: StatoSimulata, azioni: AzioniSimulata, esempio: Boolean) {
    val c = L.colori
    val e = q.esito ?: return
    val m = materia(s.materia?.colore)
    Column(Modifier.fillMaxSize().background(c.fondo)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, dimensione = 48.dp)
                    s.materia?.let { PillolaMateria(it.nome, it.colore, Modifier.weight(1f, fill = false)) }
                    if (esempio) PillolaStato(TipoPillola.ESEMPIO)
                }
                Column(
                    Modifier.fillMaxWidth().bersaglio(Bersagli.STIMA).clip(RoundedCornerShape(22.dp)).background(m.tenue).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Etichetta("Voto stimato", colore = m.testo)
                    e.votoStimato?.let { Text("circa ${eu.stgm.lectio.logica.numeroBreve(it)}", style = L.testi.voto, color = m.titolo) }
                    Text(TestiSimulata.rigaPunti(e), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = m.titolo)
                    Text("È una stima di Lectio. Il voto vero lo dà il prof.", style = L.testi.secondario, color = m.testo)
                    TempoSimulata.oltre(q.minuti, s.secondiLocali)?.let { Text(it, style = L.testi.secondario, color = m.testo) }
                }
                e.domande.forEach { x ->
                    RigaRisultato(x, q.domande.firstOrNull { it.n == x.n }, q.risposte[x.n.toString()] ?: s.risposte[x.n.toString()], azioni)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(c.fondo).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.daRifare > 0) {
                Text(TestiSimulata.daRifare(e.daRifare), style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.testo)
                PulsantePieno("Ripassa adesso", { azioni.ripassa(q.materiaId) }, altezza = 54.dp)
            }
            PulsanteSecondario("Rifai la verifica", { azioni.rifai(q) }, Modifier.fillMaxWidth(), altezza = 48.dp, raggio = 16.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RigaRisultato(x: EsitoDomandaSimulata, d: DomandaSimulata?, risposta: String?, azioni: AzioniSimulata) {
    val c = L.colori
    var aperta by remember { mutableStateOf(x.esito != "giusta") }
    val freccia by animateFloatAsState(if (aperta) 180f else 0f, morbido(200), label = "freccia")
    val forma = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(forma).background(c.superficie).animateContentSize(morbido(240))) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { aperta = !aperta }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Etichetta("Domanda ${x.n}")
                PillolaRisultato(x.esito)
                Spacer(Modifier.weight(1f))
                TestiSimulata.puntiDomanda(x, d)?.let { Text(it, style = L.testi.numeri.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = c.testo) }
                Icon(Icone.Giu, contentDescription = null, tint = c.tenue, modifier = Modifier.size(18.dp).rotate(freccia))
            }
            Text(d?.testo.orEmpty(), style = L.testi.forte.copy(fontWeight = FontWeight.SemiBold), color = c.testo, maxLines = if (aperta) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        }
        if (aperta) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val medio = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium)
                Etichetta("La tua risposta")
                if (risposta.isNullOrBlank()) Text("Non hai risposto", style = medio, color = c.tenue) else Text(risposta, style = medio, color = c.testo)
                if (x.esito == "non_corretta") {
                    Text("Nei tuoi documenti non c'è la soluzione. Confronta tu con il libro o con il prof.", style = medio, color = c.tenue)
                } else {
                    x.commento?.takeIf { it.isNotBlank() }?.let {
                        Etichetta("Commento")
                        TestoRicco(it, L.testi.testo.copy(fontWeight = FontWeight.Medium))
                    }
                    if (x.mancava.isNotEmpty()) {
                        Etichetta("Mancava")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            x.mancava.forEach { Text(it, style = L.testi.pillola, color = c.testo, modifier = Modifier.clip(CircleShape).background(c.superficie2).padding(horizontal = 12.dp, vertical = 6.dp)) }
                        }
                    }
                    x.rispostaAttesa?.takeIf { it.isNotBlank() }?.let {
                        Etichetta("Risposta attesa")
                        TestoRicco(it, medio, c.rispostaTesto, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.rispostaFondo).padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
                val fonti: List<Fonte> = x.fonti.distinctBy { it.etichetta }
                SegniFonte.riga(fonti)?.let { Text(it, style = L.testi.fonte, color = c.tenue, modifier = if (fonti.size == 1) Modifier.clickable { azioni.apriMateriale(fonti[0].materialeId) } else Modifier) }
            }
        }
    }
}

@Composable
private fun PillolaRisultato(esito: String) {
    val c = L.colori
    val parola = TestiSimulata.parolaEsito(esito)
    if (esito == "non_corretta") {
        val bordo = c.bordoSpento
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.fondoSpento)
                .drawBehind {
                    val s = 2.dp.toPx()
                    drawRoundRect(
                        bordo,
                        topLeft = Offset(s / 2, s / 2),
                        size = Size(size.width - s, size.height - s),
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icone.Confronta, contentDescription = null, tint = c.tenue, modifier = Modifier.size(14.dp))
            Text(parola, style = L.testi.piccolo.copy(fontWeight = FontWeight.ExtraBold), color = c.tenue, modifier = Modifier.padding(start = 5.dp))
        }
        return
    }
    val tono = when (esito) {
        "giusta" -> ToniEsito.GIUSTA
        "parziale" -> ToniEsito.QUASI
        else -> ToniEsito.DA_RIVEDERE
    }
    val (fondo, testo) = coloriEsito(tono)
    val icona = when (esito) {
        "giusta" -> Icone.Spunta
        "parziale" -> Icone.Quasi
        else -> Icone.Riprendi
    }
    Row(Modifier.clip(CircleShape).background(fondo).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icona, contentDescription = null, tint = testo, modifier = Modifier.size(14.dp))
        Text(parola, style = L.testi.piccolo.copy(fontWeight = FontWeight.ExtraBold), color = testo, modifier = Modifier.padding(start = 5.dp))
    }
}
