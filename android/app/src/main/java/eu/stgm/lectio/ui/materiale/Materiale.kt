package eu.stgm.lectio.ui.materiale

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Famiglia
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.CampoBordato
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaOpzione
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Segnaposto
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.altre.paragrafiLeggibili
import eu.stgm.lectio.ui.fondoSpento
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.morbido
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// --- "Che cos'è": le due famiglie (contratto §16.4, aspetto.md §5.1) ---------------------------

/**
 * Le due schede "Da studiare" e "Già corretta dal prof", ognuna con le sue pillole. Sul
 * telefono una sotto l'altra. Un tocco sulla scheda dell'altra famiglia sceglie il suo
 * primo tipo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceltaCheCosE(tipo: TipoMateriale, onScegli: (TipoMateriale) -> Unit, modifier: Modifier = Modifier) {
    val c = L.colori
    val scelta = Famiglia.di(tipo)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Etichetta("Che cos'è")
        Famiglia.entries.forEach { f ->
            val accesa = f == scelta
            val forma = RoundedCornerShape(18.dp)
            val bordo by animateColorAsState(if (accesa) c.accento else c.superficie2, morbido(200), label = "famiglia")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(forma)
                    .background(if (accesa) c.superficie else c.fondoSpento)
                    .border(2.dp, bordo, forma)
                    .selectable(accesa, role = Role.RadioButton) { if (!accesa) onScegli(f.tipi.first()) }
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(f.titolo, style = L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = c.testo)
                Text(f.frase, style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium, lineHeight = 18.sp), color = c.tenue)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.tipi.forEach { t -> PillolaOpzione(nomeTipo(t), t == tipo, { onScegli(t) }) }
                }
            }
        }
    }
}

/** "Verifica", non "Verifica corretta": la famiglia dice già che è corretta. */
private fun nomeTipo(t: TipoMateriale): String = when (t) {
    TipoMateriale.VERIFICA -> "Verifica"
    else -> t.etichetta
}

// --- Un materiale: titolo, che cos'è, cosa ho letto ---------------------------------------------

/** Titolo pulito come lo vuole il server (§16.3): spazi in più tolti, al massimo 120 caratteri. */
fun pulisciTitolo(t: String): String = t.trim().split(Regex("\\s+")).joinToString(" ").take(120)

/**
 * Il corpo del PATCH /api/materiale/{id} (contratto §16.3): SOLO i campi cambiati.
 * null = niente da salvare (o titolo vuoto, che il server rifiuta).
 */
fun cambiMateriale(originale: Materiale, titolo: String, tipo: TipoMateriale): JsonObject? {
    val t = pulisciTitolo(titolo)
    val nuovoTitolo = t.takeIf { it.isNotEmpty() && it != originale.titolo }
    val nuovoTipo = tipo.codice.takeIf { it != originale.tipo }
    if (nuovoTitolo == null && nuovoTipo == null) return null
    return buildJsonObject {
        nuovoTitolo?.let { put("titolo", it) }
        nuovoTipo?.let { put("tipo", it) }
    }
}

/**
 * La riga sotto il titolo (aspetto.md §5.1): da dove viene e chi l'ha scritto.
 * `lectio` → "Dalla foto IMG_1.jpg · titolo scritto da Lectio leggendo la foto";
 * `file` → "Dal file verifica.pdf"; `utente` (o server vecchio) → niente.
 */
fun notaTitolo(m: Materiale): String? {
    val nome = m.nomeFile?.takeIf { it.isNotBlank() }
    val da = when (m.origine) {
        "foto" -> nome?.let { "Dalla foto $it" }
        "file" -> nome?.let { "Dal file $it" }
        else -> null
    }
    return when (m.titoloDa) {
        "lectio" -> {
            val cosa = when (m.origine) {
                "foto" -> "la foto"
                "file" -> "il file"
                else -> "il testo"
            }
            listOfNotNull(da, "titolo scritto da Lectio leggendo $cosa").joinToString(" · ").replaceFirstChar { it.uppercase() }
        }
        "file" -> da
        else -> null
    }
}

data class StatoMateriale(
    val materiale: Materiale? = null,
    val materia: Materia? = null,
    val titolo: String = "",
    val tipo: TipoMateriale = TipoMateriale.APPUNTI,
    val salvando: Boolean = false,
    /** "Salvato" per 2 secondi dopo un salvataggio riuscito. */
    val salvato: Boolean = false,
    val errore: String? = null,
    val eliminato: Boolean = false,
) {
    val cambi: JsonObject? get() = materiale?.let { cambiMateriale(it, titolo, tipo) }
    val puoSalvare: Boolean get() = cambi != null && pulisciTitolo(titolo).isNotEmpty() && !salvando
}

class MaterialeViewModel(private val sorgente: Sorgente, private val id: Long, private val durataSalvato: Long = 2_000) : ViewModel() {
    private val _stato = MutableStateFlow(StatoMateriale())
    val stato: StateFlow<StatoMateriale> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            when (val e = sorgente.materialeDettaglio(id)) {
                is Esito.Ok -> {
                    val m = e.dato
                    val materia = sorgente.materie().dato?.firstOrNull { it.id == m.materiaId }
                    _stato.update { it.copy(materiale = m, materia = materia ?: it.materia, titolo = m.titolo, tipo = TipoMateriale.da(m.tipo), errore = null) }
                }
                else -> _stato.update { it.copy(errore = e.messaggio) }
            }
        }
    }

    fun titolo(t: String) = _stato.update { it.copy(titolo = t.take(120), salvato = false) }
    fun tipo(t: TipoMateriale) = _stato.update { it.copy(tipo = t, salvato = false) }

    /** PATCH con i soli campi cambiati, poi si rilegge il materiale dal server. */
    fun salva() {
        val s = _stato.value
        val cambi = s.cambi ?: return
        if (!s.puoSalvare) return
        _stato.update { it.copy(salvando = true, errore = null) }
        viewModelScope.launch {
            when (val e = sorgente.modificaMateriale(id, cambi)) {
                is Esito.Ok -> {
                    val riletto = (sorgente.materialeDettaglio(id) as? Esito.Ok)?.dato
                        ?: e.dato.copy(testo = e.dato.testo ?: s.materiale?.testo)
                    _stato.update {
                        it.copy(materiale = riletto, titolo = riletto.titolo, tipo = TipoMateriale.da(riletto.tipo), salvando = false, salvato = true)
                    }
                    delay(durataSalvato)
                    _stato.update { it.copy(salvato = false) }
                }
                else -> _stato.update { it.copy(salvando = false, errore = e.messaggio) }
            }
        }
    }

    fun elimina() {
        viewModelScope.launch {
            when (val e = sorgente.eliminaMateriale(id)) {
                is Esito.Ok -> _stato.update { it.copy(eliminato = true) }
                else -> _stato.update { it.copy(errore = e.messaggio) }
            }
        }
    }
}

/** Quante lettere si vedono di "Cosa ho letto" prima di "Mostra tutto". */
private const val ANTEPRIMA = 360

/**
 * Un materiale (aspetto.md §5.1, prototipo MaterialePc.dc.html; sul telefono è una pagina):
 * titolo da cambiare, che cos'è, cosa ha letto il server, Salva ed Elimina.
 */
@Composable
fun MaterialeScreen(vm: MaterialeViewModel, onIndietro: () -> Unit, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.eliminato) { if (s.eliminato) onIndietro() }
    val c = L.colori
    var tutto by rememberSaveable { mutableStateOf(false) }
    var conferma by remember { mutableStateOf(false) }
    val m = s.materiale
    val testo = m?.testo.orEmpty()
    val pezzi = remember(testo, tutto) { if (tutto) paragrafiLeggibili(testo) else emptyList() }
    val bloccato = senzaRete && !esempio
    Box(Modifier.fillMaxSize().background(c.fondo)) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // La pillola a sinistra, la X tutta a destra (aspetto.md §5.1).
                    Box(Modifier.weight(1f)) { s.materia?.let { PillolaMateria(it.nome, it.colore) } }
                    if (esempio) PillolaStato(TipoPillola.ESEMPIO)
                    PulsanteTondo(Icone.Chiudi, "Chiudi", onIndietro, dimensioneIcona = 18.dp)
                }
            }
            s.errore?.let { e -> item { RigaErrore(e, Modifier.padding(top = 18.dp), onRiprova = if (m == null) vm::carica else null) } }
            if (m == null) {
                if (s.errore == null) item { Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Segnaposto(altezza = 52.dp); Segnaposto(altezza = 120.dp) } }
                return@LazyColumn
            }
            item {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Etichetta("Titolo")
                    CampoBordato(
                        s.titolo, vm::titolo,
                        stile = L.testi.forteGrande.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        descrizione = "Titolo",
                        abilitato = !bloccato,
                    )
                    notaTitolo(m)?.let { Text(it, style = L.testi.piccolo, color = c.tenue) }
                }
            }
            item { SceltaCheCosE(s.tipo, vm::tipo, Modifier.padding(top = 18.dp)) }
            m.avviso?.takeIf { it.isNotBlank() }?.let { a -> item { RigaAvviso(a, Modifier.padding(top = 18.dp)) } }
            // "Cosa ho letto": a pezzi quando è aperto, così un libro intero non blocca la pagina.
            val caratteri = Formati.numero((m.caratteri ?: testo.length).toLong())
            val forma = RoundedCornerShape(18.dp)
            if (!tutto) {
                item {
                    Column(
                        Modifier.padding(top = 18.dp).fillMaxWidth().clip(forma).background(c.superficie).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Etichetta("Cosa ho letto · $caratteri caratteri")
                        val inizio = if (testo.length > ANTEPRIMA) testo.take(ANTEPRIMA).substringBeforeLast(' ').trimEnd() + "…" else testo
                        Text(inizio.ifBlank { "Nessun testo letto." }, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium, lineHeight = 22.5.sp), color = c.testo)
                        if (testo.length > ANTEPRIMA) PulsanteTesto("Mostra tutto", { tutto = true }, Modifier.heightIn(min = 48.dp))
                        else Spacer(Modifier.height(8.dp))
                    }
                }
            } else {
                item {
                    Column(
                        Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(c.superficie)
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    ) { Etichetta("Cosa ho letto · $caratteri caratteri") }
                }
                items(pezzi.size) { i ->
                    SelectionContainer(Modifier.fillMaxWidth().background(c.superficie).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
                        Text(pezzi[i], style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium, lineHeight = 22.5.sp), color = c.testo)
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)).background(c.superficie).padding(start = 6.dp, bottom = 6.dp)) {
                        PulsanteTesto("Mostra meno", { tutto = false }, Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        // Salva ed Elimina in basso, sempre a portata di pollice.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(c.fondo)
                .drawBehind { drawRect(c.riga, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())) }
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulsantePieno(
                when {
                    s.salvando -> "Salvo…"
                    s.salvato -> "Salvato"
                    else -> "Salva"
                },
                vm::salva,
                Modifier.weight(1f).semantics { contentDescription = if (s.salvato) "Salvato" else "Salva" },
                icona = if (s.salvato) Icone.Spunta else null,
                abilitato = (s.puoSalvare || s.salvato) && !bloccato,
                altezza = 52.dp,
                raggio = 16.dp,
            )
            PulsanteTesto(
                "Elimina", { conferma = true },
                Modifier.heightIn(min = 48.dp),
                colore = if (c.scuro) c.erroreTesto else c.errore,
                abilitato = m != null && !bloccato,
            )
        }
    }
    if (conferma) {
        Conferma("Eliminare questo materiale?", "Le prossime lezioni non lo useranno più.", "Elimina", { conferma = false; vm.elimina() }, { conferma = false })
    }
}
