package eu.stgm.lectio.ui.lezioni

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.Preparazione
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Barra
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.Piastrella
import eu.stgm.lectio.ui.PillolaScelta
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.RigaElenco
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.StatoVuoto
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.tema.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoLezioni(
    val lezioni: List<Lezione> = emptyList(),
    val materie: List<Materia> = emptyList(),
    val filtro: Long? = null,
    val letta: Boolean = false,
    val errore: String? = null,
    val messaggio: String? = null,
)

class LezioniViewModel(private val sorgente: Sorgente) : ViewModel() {
    private val _stato = MutableStateFlow(StatoLezioni())
    val stato: StateFlow<StatoLezioni> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val l = sorgente.lezioni()
            val m = sorgente.materie()
            _stato.update {
                it.copy(
                    lezioni = (l.dato ?: it.lezioni).filter { x -> x.stato != "annullata" },
                    materie = m.dato ?: it.materie,
                    letta = true,
                    errore = if (l.dato == null) l.errore else null,
                )
            }
        }
    }

    fun filtra(materiaId: Long?) = _stato.update { it.copy(filtro = materiaId) }

    fun elimina(id: Long) {
        viewModelScope.launch {
            val e = sorgente.eliminaLezione(id)
            _stato.update { it.copy(messaggio = if (e is Esito.Ok) "Lezione eliminata" else e.messaggio) }
            carica()
        }
    }

    fun messaggioVisto() = _stato.update { it.copy(messaggio = null) }
}

/** Lo stato di una lezione in una riga (§10): pronta, ascoltata per metà, finita, in preparazione. */
fun statoBreve(l: Lezione): String = when {
    l.inLavoro -> Preparazione.frase(l)
    l.fallita -> "Non riuscita"
    l.ascolto?.finita == true -> "Finita"
    (l.ascolto?.posizioneS ?: 0.0) > 5 -> Formati.mancanoMinuti(l.durataSecondi - (l.ascolto?.posizioneS ?: 0.0))
    l.pronta -> "Pronta"
    else -> ""
}

/** Una riga di lezione: piastrella, titolo, materia · durata · stato, spunta se scaricata. */
@Composable
fun RigaLezione(l: Lezione, materia: String?, colore: String?, scaricata: Boolean, onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    val c = L.colori
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Piastrella(colore)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(l.argomento, style = L.testi.forte, color = c.testo, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val parti = listOfNotNull(materia, "${l.durataMin} min", statoBreve(l).takeIf { it.isNotEmpty() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.ascolto?.finita == true) {
                    Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                }
                Text(parti.joinToString(" · "), style = L.testi.secondario, color = if (l.fallita) c.erroreTesto else c.tenue, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val pos = l.ascolto?.posizioneS ?: 0.0
            if (l.inLavoro) Barra(Preparazione.frazione(l), altezza = 4.dp)
            else if (l.ascolto?.finita != true && pos > 5) Barra((pos / l.durataSecondi).toFloat(), altezza = 4.dp)
        }
        if (scaricata) Icon(Icone.Scarica, contentDescription = "Scaricata sul telefono", tint = c.fatto, modifier = Modifier.size(20.dp))
        if (onMenu != null) PulsanteTondo(Icone.AltreAzioni, "Altre azioni", onMenu, fondo = c.superficie, tinta = c.tenue, dimensione = 40.dp)
    }
}

data class AzioniLezioni(
    val apri: (Lezione) -> Unit = {},
    val scarica: (Long) -> Unit = {},
    val togli: (Long) -> Unit = {},
    /** Eliminata dal server: prima il lettore la lascia, poi audio e testo spariscono dal telefono. */
    val elimina: (Long) -> Unit = {},
    val nuova: () -> Unit = {},
)

/** Lezioni (aspetto.md §10): per data, filtro a pillole per materia, menu con Scarica / Togli / Elimina. */
@Composable
fun LezioniScreen(vm: LezioniViewModel, azioni: AzioniLezioni, scaricate: Set<Long>, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    var menu by remember { mutableStateOf<Lezione?>(null) }
    var elimina by remember { mutableStateOf<Lezione?>(null) }
    var filtro by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PilloleStato(esempio, senzaRete)
        TitoloPagina("Lezioni", Modifier.padding(top = 8.dp))
        val usate = s.materie.filter { m -> s.lezioni.any { it.materiaId == m.id } }
        if (usate.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillolaScelta("Tutte", filtro == null, { filtro = null })
                usate.forEach { m -> PillolaScelta(m.nome, filtro == m.id, { filtro = m.id }) }
            }
        }
        if (s.errore != null && s.lezioni.isEmpty()) RigaErrore(s.errore!!, onRiprova = vm::carica)
        val visibili = s.lezioni.filter { filtro == null || it.materiaId == filtro }
        if (s.letta && s.lezioni.isEmpty() && s.errore == null) {
            StatoVuoto("Nessuna lezione ancora", "Nuova lezione", azioni.nuova, icona = Icone.Piu)
        }
        if (visibili.isNotEmpty()) {
            Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                visibili.forEachIndexed { i, l ->
                    if (i > 0) Riga()
                    val m = s.materie.firstOrNull { it.id == l.materiaId }
                    val scaricata = l.id in scaricate
                    // Senza rete si aprono solo le lezioni scaricate.
                    val apribile = !senzaRete || scaricata || l.inLavoro
                    Box(Modifier.alpha(if (apribile) 1f else 0.45f)) {
                        RigaLezione(l, m?.nome, m?.colore, scaricata, { if (apribile) azioni.apri(l) }, onMenu = { menu = l })
                    }
                }
            }
        }
    }
    menu?.let { l ->
        val scaricata = l.id in scaricate
        Foglio(onChiudi = { menu = null }, titolo = l.argomento) {
            if (l.pronta && !scaricata) RigaElenco("Scarica", null, inizio = { Icon(Icone.Scarica, null, tint = c.testo) }, onClick = { azioni.scarica(l.id); menu = null })
            if (scaricata) RigaElenco("Togli dal telefono", "Resta sul server di casa", inizio = { Icon(Icone.Chiudi, null, tint = c.testo) }, onClick = { azioni.togli(l.id); menu = null })
            if (!senzaRete) RigaElenco("Elimina", null, inizio = { Icon(Icone.Cestino, null, tint = c.testo) }, onClick = { elimina = l; menu = null })
        }
    }
    elimina?.let { l ->
        Conferma(
            "Eliminare la lezione?",
            "Spariscono l'audio e le risposte di \"${l.argomento}\". Le spese già fatte restano nel conto del mese.",
            "Elimina",
            { elimina = null; azioni.elimina(l.id); vm.elimina(l.id) },
            { elimina = null },
        )
    }
}

/** Per la pagina "Vedi tutte": la riga di un impegno in arrivo. */
fun rigaImpegno(tipo: String, data: String?): String = "$tipo · ${Formati.giornoOVuoto(data)}"
