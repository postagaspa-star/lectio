package eu.stgm.lectio.ui.lettore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Domanda
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.MateriaBreve
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.lettore.Lettore
import eu.stgm.lectio.lettore.StatoLettore
import eu.stgm.lectio.lettore.prossimaVelocita
import eu.stgm.lectio.logica.Esiti
import eu.stgm.lectio.logica.FaseDomanda
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.ModoDomande
import eu.stgm.lectio.logica.PosizioneLocale
import eu.stgm.lectio.logica.RegolaPosizione
import eu.stgm.lectio.logica.StatoRisposta
import eu.stgm.lectio.logica.ToniEsito
import eu.stgm.lectio.logica.etichettaDomanda
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Barrette
import eu.stgm.lectio.ui.BarretteInCorso
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.Piastrella
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaElenco
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.Segnaposto
import eu.stgm.lectio.ui.materie.PillolaEsito
import eu.stgm.lectio.ui.ombraColore
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Quello che il lettore ricorda per la schermata (oltre allo stato dell'audio). */
data class StatoSchermataLettore(
    val caricamento: Boolean = false,
    val errore: String? = null,
    /** Dopo un salto: dove si era, e fino a quando mostrare "Torna dov'eri" (5 s). */
    val tornaA: Double? = null,
    val testoAperto: Boolean = false,
    /** Le risposte che Andrea sta scrivendo, per domanda (restano se ripete la domanda). */
    val bozze: Map<Long, String> = emptyMap(),
    val confermaSalto: Boolean = false,
    val messaggio: String? = null,
)

class LettoreViewModel(
    private val sorgente: Sorgente,
    val lettore: Lettore,
    private val posizioneLocale: suspend (Long) -> PosizioneLocale? = { null },
    private val dimenticaPosizione: suspend (Long) -> Unit = {},
    /** Sopravvive anche alla chiusura del processo da parte di Android (punto 3). */
    private val salvato: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoSchermataLettore())
    val stato: StateFlow<StatoSchermataLettore> = _stato.asStateFlow()

    /**
     * La pagina del lettore si apre (o si ricrea). [idPagina] viene dallo stato
     * salvabile della pagina: uguale all'ultimo visto = la stessa pagina ricreata
     * (rotazione, tema, processo chiuso da Android), quindi niente da capo, niente
     * avvio da solo e niente ritorno alla domanda di "Da ripassare" (punto 3).
     */
    fun apriPagina(lezioneId: Long, allaDomanda: Long?, idPagina: String) {
        val ripristinata = salvato.get<String>(CHIAVE_PAGINA) == idPagina
        salvato[CHIAVE_PAGINA] = idPagina
        if (ripristinata) apri(lezioneId, allaDomanda = null, avvia = false) else apri(lezioneId, allaDomanda, avvia = true)
    }

    /** Apre una lezione: riprende dal punto più recente tra telefono e server ("vince il più recente"). */
    fun apri(lezioneId: Long, allaDomanda: Long? = null, avvia: Boolean = true) {
        val s = lettore.stato.value
        // Già caricata e senza errori: si resta dove si è. In errore (rete caduta) si ricarica (punto 1).
        if (s.lezione?.id == lezioneId && allaDomanda == null && s.errore == null) return
        _stato.update { it.copy(caricamento = true, errore = null) }
        viewModelScope.launch {
            val l = sorgente.lezione(lezioneId)
            val lezione = l.dato
            if (lezione == null || !lezione.pronta) {
                _stato.update { it.copy(caricamento = false, errore = l.errore ?: "Questa lezione non è ancora pronta.") }
                return@launch
            }
            val materia = sorgente.materie().dato?.firstOrNull { it.id == lezione.materiaId }?.let { MateriaBreve(it.id, it.nome, it.colore) }
            val ripresa = RegolaPosizione.scegli(posizioneLocale(lezioneId), lezione.ascolto)
            if (ripresa.daInviare == null) dimenticaPosizione(lezioneId)
            val da = if (ripresa.finita) 0.0 else ripresa.posizioneS
            lettore.carica(lezione, materia, da, avvia && allaDomanda == null, allaDomanda)
            _stato.update { it.copy(caricamento = false) }
        }
    }

    /** Un salto voluto (capitolo o barra): per 5 s c'è "Torna dov'eri". */
    fun salta(a: Double) {
        val prima = lettore.stato.value.posizioneS
        lettore.vaiA(a)
        _stato.update { it.copy(tornaA = prima) }
        viewModelScope.launch {
            delay(5_000)
            _stato.update { if (it.tornaA == prima) it.copy(tornaA = null) else it }
        }
    }

    fun tornaDovEri() {
        val a = _stato.value.tornaA ?: return
        lettore.vaiA(a)
        _stato.update { it.copy(tornaA = null) }
    }

    fun testo() = _stato.update { it.copy(testoAperto = !it.testoAperto) }
    fun bozza(domandaId: Long, testo: String) = _stato.update { it.copy(bozze = it.bozze + (domandaId to testo.take(2000))) }
    fun chiediSalto(si: Boolean) = _stato.update { it.copy(confermaSalto = si) }

    fun invia(domandaId: Long) {
        val t = _stato.value.bozze[domandaId].orEmpty()
        if (t.isBlank()) return
        lettore.rispondi(t)
        _stato.update { it.copy(bozze = it.bozze - domandaId, confermaSalto = false) }
    }

    fun saltaDomanda() {
        lettore.salta()
        _stato.update { it.copy(confermaSalto = false) }
    }

    /** Dal menu del lettore: le domande di questa lezione si possono saltare o no (contratto §15.4). */
    fun cambiaSaltabili(si: Boolean) {
        val l = lettore.stato.value.lezione ?: return
        lettore.cambiaSaltabili(si)
        viewModelScope.launch {
            val e = sorgente.modificaLezione(l.id, buildJsonObject { put("domande_saltabili", si) })
            if (e !is Esito.Ok) _stato.update { it.copy(messaggio = e.messaggio) }
        }
    }

    fun messaggioVisto() = _stato.update { it.copy(messaggio = null) }

    companion object {
        const val CHIAVE_PAGINA = "pagina_lettore"
    }
}

/**
 * Il lettore è "visibile" solo con l'app davanti (punto 12): con un'altra app sopra,
 * a schermo acceso, la lezione dopo la risposta deve ripartire da sola.
 */
class OsservatoreVisibilita(private val lettore: Lettore) : androidx.lifecycle.LifecycleEventObserver {
    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_START -> lettore.visibile(true)
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> lettore.visibile(false)
            else -> Unit
        }
    }
}

/** Il play del mini-lettore: alla domanda (risposte scritte) apre il lettore, dove ci sono Invia e Salta (punto 14). */
fun playDelMiniLettoreApre(st: StatoLettore): Boolean =
    st.fermate.fase == FaseDomanda.ALLA_DOMANDA && st.risposteScritte

data class AzioniLettore(
    val chiudi: () -> Unit = {},
    val scarica: (Long) -> Unit = {},
    val togli: (Long) -> Unit = {},
    val comeAndata: (Long) -> Unit = {},
)

/** Il lettore (aspetto.md §7, prototipo Lettore.dc.html). */
@Composable
fun LettoreScreen(
    vm: LettoreViewModel,
    azioni: AzioniLettore,
    scaricata: Boolean,
    scaricamento: Float?,
    esempio: Boolean,
    senzaRete: Boolean,
) {
    val st by vm.lettore.stato.collectAsStateWithLifecycle()
    val ui by vm.stato.collectAsStateWithLifecycle()
    val ciclo = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(vm, ciclo) {
        val osservatore = OsservatoreVisibilita(vm.lettore)
        ciclo.lifecycle.addObserver(osservatore)
        onDispose {
            ciclo.lifecycle.removeObserver(osservatore)
            vm.lettore.visibile(false)
        }
    }
    val c = L.colori
    val l = st.lezione
    val m = materia(st.materia?.colore)
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsanteTondo(Icone.Giu, "Chiudi il lettore", azioni.chiudi)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    st.materia?.let { PillolaMateria(it.nome, it.colore) }
                }
                if (l != null && !esempio) {
                    val descrizione = when {
                        scaricata -> "Scaricata sul telefono"
                        scaricamento != null -> "Sto scaricando"
                        else -> "Scarica sul telefono"
                    }
                    PulsanteTondo(Icone.Scarica, descrizione, { if (!scaricata && scaricamento == null) azioni.scarica(l.id) }, tinta = if (scaricata) c.fatto else c.testo)
                    Spacer(Modifier.width(8.dp))
                    PulsanteTondo(Icone.AltreAzioni, "Altre azioni", { menu = true })
                } else if (esempio) {
                    Etichetta("Esempio", colore = c.avvisoTesto)
                }
            }
            ui.errore?.let { RigaErrore(it) }
            st.errore?.let { RigaErrore(it) }
            if (l == null) {
                if (ui.caricamento) Segnaposto(altezza = 120.dp)
                return@Column
            }
            // La piastrella grande col titolo.
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(m.tenue).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.height(26.dp), contentAlignment = Alignment.BottomStart) {
                    Barrette(m.pieno, larghezza = 5.dp, altezze = listOf(12.dp, 24.dp, 17.dp), spazio = 4.dp, inBasso = true)
                }
                Text(l.argomento, style = L.testi.titoloScheda, color = m.titolo)
                Text(eu.stgm.lectio.ui.oggi.riassuntoLezione(l, l), style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = m.testo)
            }
            // Capitoli (o testo) prendono lo spazio che resta: i comandi stanno sempre in basso.
            Column(Modifier.weight(1f)) {
                if (ui.testoAperto) {
                    TestoDellaLezione(l, st, Modifier.weight(1f, fill = false))
                } else {
                    Capitoli(l, st, vm::salta, Modifier.weight(1f, fill = false))
                }
            }
            if (st.finita) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(20.dp))
                    Text("Finita", style = L.testi.forte, color = c.testo)
                    if (l.eventoId != null) PulsanteTesto("Dopo la verifica raccontami com'è andata", { azioni.comeAndata(l.eventoId) })
                }
            }
            if (ui.tornaA != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.clip(CircleShape).background(c.accentoTenue).clickable(role = Role.Button, onClick = vm::tornaDovEri).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icone.Riprendi, contentDescription = null, tint = c.accentoTesto, modifier = Modifier.size(16.dp))
                        Text("Torna dov'eri", style = L.testi.pillola, color = c.accentoTesto, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            BarraLettore(st, vm::salta)
            Comandi(st, vm.lettore)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                PillolaLettore(Formati.velocita(st.velocita), false) { vm.lettore.cambiaVelocita(prossimaVelocita(st.velocita)) }
                PillolaLettore("Testo", ui.testoAperto, vm::testo)
            }
        }
        SchedaDomanda(st, ui, vm, esempio)
    }
    if (menu && l != null) {
        Foglio(onChiudi = { menu = false }, titolo = l.argomento) {
            Etichetta("Domande")
            eu.stgm.lectio.ui.Segmenti(
                listOf("Obbligatorie", "Si possono saltare"), if (l.domandeSaltabili) 1 else 0,
                { vm.cambiaSaltabili(it == 1) }, abilitato = !senzaRete,
            )
            if (scaricata) RigaElenco("Togli dal telefono", "Resta sul server di casa", inizio = { Icon(Icone.Chiudi, null, tint = c.testo) }, onClick = { azioni.togli(l.id); menu = false })
        }
    }
}

@Composable
private fun Capitoli(l: Lezione, st: StatoLettore, salta: (Double) -> Unit, modifier: Modifier) {
    val c = L.colori
    if (l.capitoli.isEmpty()) return
    val corrente = st.capitoloCorrente?.n
    Scheda(modifier, padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        l.capitoli.forEachIndexed { i, cap ->
            if (i > 0) Riga()
            val fatto = corrente != null && cap.n < corrente
            val inCorso = cap.n == corrente
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable(role = Role.Button) { salta(cap.inizioS) }
                    .semantics(mergeDescendants = true) { contentDescription = "${cap.titolo}, " + if (fatto) "fatto" else if (inCorso) "in corso" else "da fare" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        fatto -> Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(20.dp))
                        inCorso -> BarretteInCorso(c.accento)
                    }
                }
                Text(
                    cap.titolo, Modifier.weight(1f),
                    style = L.testi.testoMedio.copy(fontWeight = if (inCorso) FontWeight.ExtraBold else FontWeight.SemiBold),
                    color = when {
                        fatto -> c.moltoTenue
                        inCorso -> c.accentoTesto
                        else -> c.testo
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(Formati.durataBreve(cap.fineS - cap.inizioS), style = L.testi.numeri, color = if (inCorso) c.accentoTesto else if (fatto) c.moltoTenue else c.tenue)
            }
        }
      }
    }
}

@Composable
private fun TestoDellaLezione(l: Lezione, st: StatoLettore, modifier: Modifier) {
    val c = L.colori
    val n = st.capitoloCorrente?.n ?: l.testo.firstOrNull()?.capitolo
    val pezzo = l.testo.firstOrNull { it.capitolo == n }
    val titolo = l.capitoli.firstOrNull { it.n == n }?.titolo
    Scheda(modifier, padding = PaddingValues(18.dp)) {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        titolo?.let { Etichetta(it, colore = c.accentoTesto) }
        if (pezzo == null) Text("Il testo di questa lezione non c'è.", style = L.testi.testo, color = c.tenue)
        pezzo?.paragrafi?.forEach { Text(it.replace(Regex("\\[[a-z]+=[a-z]+]"), "").trim(), style = L.testi.testo, color = c.testo) }
      }
    }
}

/** La barra trascinabile: tempo passato e che manca, segni sottili dove ci sono domande (§7). */
@Composable
private fun BarraLettore(st: StatoLettore, salta: (Double) -> Unit) {
    val c = L.colori
    val durata = st.durataS.takeIf { it > 0 } ?: 1.0
    var trascino by remember { mutableStateOf<Float?>(null) }
    val frazione = trascino ?: (st.posizioneS / durata).toFloat().coerceIn(0f, 1f)
    val domande = st.lezione?.domande.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics {
                    contentDescription = "Avanzamento della lezione"
                    progressBarRangeInfo = ProgressBarRangeInfo(frazione, 0f..1f)
                    setProgress { v -> salta(v.toDouble() * durata); true }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val larghezza = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(durata) {
                        detectTapGestures { o -> salta((o.x / larghezza).coerceIn(0f, 1f).toDouble() * durata) }
                    }
                    .pointerInput(durata) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> trascino = (o.x / larghezza).coerceIn(0f, 1f) },
                            onDragEnd = { trascino?.let { salta(it.toDouble() * durata) }; trascino = null },
                            onDragCancel = { trascino = null },
                        ) { cambio, _ -> trascino = (cambio.position.x / larghezza).coerceIn(0f, 1f) }
                    },
            )
            Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val y = size.height / 2
                val h = 6.dp.toPx()
                drawRoundRect(c.superficie2, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                drawRoundRect(c.accento, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * frazione, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
                domande.forEach { d ->
                    val x = (d.fineDomandaS / durata).toFloat() * size.width
                    drawRect(if (x < size.width * frazione) c.suAccento.copy(alpha = 0.7f) else c.moltoTenue, Offset(x - 1.dp.toPx(), y - h / 2), androidx.compose.ui.geometry.Size(2.dp.toPx(), h))
                }
                val r = 8.dp.toPx()
                val cx = (size.width * frazione).coerceIn(r, size.width - r)
                drawCircle(c.superficie, r + 3.dp.toPx(), Offset(cx, y))
                drawCircle(c.accento, r, Offset(cx, y))
            }
        }
        Row {
            Text(Formati.tempo(frazione * durata), style = L.testi.numeri, color = c.tenue)
            Spacer(Modifier.weight(1f))
            Text(Formati.mancante(durata - frazione * durata), style = L.testi.numeri, color = c.tenue)
        }
    }
}

@Composable
private fun Comandi(st: StatoLettore, lettore: Lettore) {
    val c = L.colori
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        TondoTesto("-15", "Indietro di 15 secondi") { lettore.sposta(-15.0) }
        val forma = CircleShape
        Box(
            Modifier.size(76.dp).ombraColore(forma, c.accento).clip(forma).background(c.accento)
                .clickable(role = Role.Button, onClickLabel = if (st.suona) "Pausa" else "Riproduci") { lettore.playPausa() }
                .semantics { contentDescription = if (st.suona) "Pausa" else "Riproduci" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (st.suona) Icone.Pausa else Icone.Play, contentDescription = null, tint = c.suAccento, modifier = Modifier.size(30.dp))
        }
        TondoTesto("+30", "Avanti di 30 secondi") { lettore.sposta(30.0) }
    }
}

@Composable
private fun TondoTesto(testo: String, descrizione: String, onClick: () -> Unit) {
    val c = L.colori
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(c.superficie2).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = L.testi.pillola.copy(fontWeight = FontWeight.ExtraBold), color = c.testo)
    }
}

@Composable
private fun PillolaLettore(testo: String, attiva: Boolean, onClick: () -> Unit) {
    val c = L.colori
    Box(
        Modifier.height(40.dp).clip(CircleShape).background(if (attiva) c.accentoTenue else c.superficie2).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = L.testi.pillola, color = if (attiva) c.accentoTesto else c.testo)
    }
}

/**
 * La scheda della domanda che sale (§7): scrivi e Invia, Ripeti, Salta (o "Salta
 * comunque" con conferma), "Correggo…", la correzione e la risposta giusta.
 */
@Composable
private fun SchedaDomanda(st: StatoLettore, ui: StatoSchermataLettore, vm: LettoreViewModel, esempio: Boolean) {
    val c = L.colori
    val d = st.fermate.domanda
    val aperta = st.fermate.schedaAperta && d != null
    val muove = LocalMovimenti.current
    AnimatedVisibility(aperta, enter = fadeIn(tween(if (muove) 200 else 0)), exit = fadeOut(tween(if (muove) 150 else 0))) {
        Box(Modifier.fillMaxSize().background(c.velo).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            aperta,
            enter = slideInVertically(tween(if (muove) 300 else 0)) { it } + fadeIn(tween(if (muove) 200 else 0)),
            exit = slideOutVertically(tween(if (muove) 200 else 0)) { it } + fadeOut(tween(if (muove) 150 else 0)),
        ) {
            if (d != null) CorpoDomanda(d, st, ui, vm, esempio)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorpoDomanda(d: Domanda, st: StatoLettore, ui: StatoSchermataLettore, vm: LettoreViewModel, esempio: Boolean) {
    val c = L.colori
    val visita = LocalVisita.current
    val lettore = vm.lettore
    val modo = ModoDomande.da(st.risposteScritte, st.lezione?.domandeSaltabili ?: false)
    val fase = st.fermate.fase
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(c.superficie)
            .imePadding()
            .navigationBarsPadding()
            // Il riflettore della visita illumina tutta la scheda: domanda, campo e Invia.
            .bersaglio(Bersagli.SENTI_RISPOSTA)
            .verticalScroll(rememberScrollState())
            .padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(CircleShape).background(c.superficie2))
        Etichetta(etichettaDomanda(d), colore = c.accentoTesto)
        Text(d.testo, style = L.testi.domanda, color = c.testo)

        if (!modo.scritte) {
            // Il vecchio "Senti la risposta" (Risposte scritte spento).
            if (fase == FaseDomanda.ALLA_DOMANDA) {
                PulsantePieno("Senti la risposta", { lettore.sentiRisposta() })
                PulsanteTesto("Ripeti la domanda", { lettore.ripetiDomanda() }, Modifier.align(Alignment.CenterHorizontally))
            } else {
                RispostaGiusta(d, fase == FaseDomanda.RISPOSTA && st.suona)
                PulsantePieno("Avanti", { lettore.avanti() })
            }
            return@Column
        }

        when (val r = st.risposta) {
            StatoRisposta.DaScrivere, is StatoRisposta.Rifiutata -> if (fase == FaseDomanda.ALLA_DOMANDA) {
                val testo = ui.bozze[d.id] ?: (r as? StatoRisposta.Rifiutata)?.testo ?: ""
                val fuoco = remember { FocusRequester() }
                LaunchedEffect(d.id) {
                    if (!esempio) runCatching { fuoco.requestFocus() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextField(
                        value = testo,
                        onValueChange = { vm.bozza(d.id, it) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(fuoco),
                        placeholder = { Text("Scrivi la tua risposta", style = L.testi.testo, color = c.moltoTenue) },
                        textStyle = L.testi.testo.copy(color = c.testo),
                        minLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = c.superficie2, unfocusedContainerColor = c.superficie2,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = c.accento, focusedTextColor = c.testo, unfocusedTextColor = c.testo,
                        ),
                    )
                    if (r is StatoRisposta.Rifiutata) RigaErrore(r.messaggio)
                    PulsantePieno("Invia risposta", {
                        vm.invia(d.id)
                        visita?.azione(Bersagli.SENTI_RISPOSTA)
                    }, abilitato = testo.isNotBlank())
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTesto("Ripeti la domanda", { lettore.ripetiDomanda() })
                    Spacer(Modifier.weight(1f))
                    if (modo == ModoDomande.SCRITTE_SALTABILI) {
                        PulsanteTesto("Salta", vm::saltaDomanda, colore = c.tenue)
                    } else {
                        Box(
                            Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { vm.chiediSalto(true) }.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("Salta comunque", style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.moltoTenue) }
                    }
                }
                if (ui.confermaSalto && modo == ModoDomande.SCRITTE_OBBLIGATORIE) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fondo).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Saltarla? Finisce in Da ripassare.", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold), color = c.testo)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BottoneConferma("Sì, salta", c.superficie2, c.testo, Modifier.weight(1f), vm::saltaDomanda)
                            BottoneConferma("Rispondo", c.accentoTenue, c.accentoTesto, Modifier.weight(1f)) { vm.chiediSalto(false) }
                        }
                    }
                }
            } else {
                RispostaGiusta(d, fase == FaseDomanda.RISPOSTA && st.suona)
                PulsantePieno("Avanti", { lettore.avanti() })
            }
            is StatoRisposta.Correggo -> {
                val muove = LocalMovimenti.current
                val alfa = if (muove) rememberInfiniteTransition(label = "correggo").animateFloat(0.55f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
                Text(
                    "Correggo…",
                    style = L.testi.testo.copy(fontWeight = FontWeight.Bold),
                    color = c.tenue,
                    modifier = Modifier.fillMaxWidth().alpha(alfa).clip(RoundedCornerShape(18.dp)).background(c.superficie2).padding(16.dp),
                )
            }
            is StatoRisposta.Corretta -> {
                val corr = r.correzione
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (corr.esito != "non_corretta") PillolaEsito(Esiti.parola(corr.esito), Esiti.tono(corr.esito), grande = true)
                    corr.commento?.let { Text(it, style = L.testi.testo, color = c.testo) }
                    if (corr.mancava.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Mancava:", style = L.testi.pillola, color = c.tenue, modifier = Modifier.padding(top = 5.dp))
                            corr.mancava.forEach { m ->
                                Text(m, style = L.testi.pillola, color = c.testo, modifier = Modifier.clip(CircleShape).background(c.superficie2).padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                }
                RispostaGiusta(d, fase == FaseDomanda.RISPOSTA && st.suona, corr.rispostaAttesa)
                PulsantePieno("Avanti", { lettore.avanti() })
            }
            is StatoRisposta.InCoda -> {
                RigaAvviso("Senza rete: la correggo appena torna la connessione.")
                RispostaGiusta(d, fase == FaseDomanda.RISPOSTA && st.suona)
                PulsantePieno("Avanti", { lettore.avanti() })
            }
            StatoRisposta.Saltata -> {
                PillolaEsito("Saltata · in Da ripassare", ToniEsito.DA_RIVEDERE, grande = true)
                st.materia?.let { Text("La ritrovi nella pagina di ${it.nome}, sotto Da ripassare.", style = L.testi.testo, color = c.testo) }
                RispostaGiusta(d, fase == FaseDomanda.RISPOSTA && st.suona)
                PulsantePieno("Avanti", { lettore.avanti() })
            }
        }
    }
}

@Composable
private fun BottoneConferma(testo: String, fondo: Color, colore: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(fondo).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(testo, style = L.testi.pulsantePiccolo, color = colore) }
}

/** "RISPOSTA GIUSTA · IN ASCOLTO" e il suo testo (prototipo Lettore). */
@Composable
private fun RispostaGiusta(d: Domanda, inAscolto: Boolean, attesa: String? = null) {
    val c = L.colori
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fondo).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Etichetta(if (inAscolto) "Risposta giusta · in ascolto" else "Risposta giusta", colore = c.accentoTesto)
        Text(attesa?.takeIf { it.isNotBlank() } ?: d.risposta, style = L.testi.testo, color = c.testo)
    }
}

/** Il mini-lettore sopra la barra (§2): piastrella, titolo, quanto manca, play/pausa. */
@Composable
fun MiniLettore(st: StatoLettore, onApri: () -> Unit, onPlayPausa: () -> Unit) {
    val c = L.colori
    val l = st.lezione ?: return
    Column(Modifier.fillMaxWidth().background(c.superficie)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            val f = if (st.durataS > 0) (st.posizioneS / st.durataS).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(f).height(2.dp).background(c.accento))
        }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Apri il lettore", onClick = onApri).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Piastrella(st.materia?.colore, dimensione = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(l.argomento, style = L.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stato = when {
                    st.fermate.fase == FaseDomanda.ALLA_DOMANDA -> "Ferma alla domanda"
                    st.finita -> "Finita"
                    else -> Formati.mancanoMinuti(st.mancanteS)
                }
                Text(listOfNotNull(st.materia?.nome, stato).joinToString(" · "), style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium), color = c.tenue, maxLines = 1)
            }
            PulsanteTondo(if (st.suona) Icone.Pausa else Icone.Play, if (st.suona) "Pausa" else "Riproduci", onPlayPausa, fondo = c.accento, tinta = c.suAccento, dimensione = 44.dp, dimensioneIcona = 18.dp)
        }
    }
}
