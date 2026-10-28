package eu.stgm.lectio.ui.materie

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.NuovaMateria
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.CampoConEtichetta
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.StatoVuoto
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.ombra
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.NOMI_COLORI
import eu.stgm.lectio.ui.tema.materia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class StatoMaterie(
    val materie: List<Materia> = emptyList(),
    val caricamento: Boolean = true,
    val letta: Boolean = false,
    val errore: String? = null,
)

class MaterieViewModel(private val sorgente: Sorgente) : ViewModel() {
    private val _stato = MutableStateFlow(StatoMaterie())
    val stato: StateFlow<StatoMaterie> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val l = sorgente.materie()
            _stato.update { it.copy(materie = l.dato ?: it.materie, caricamento = false, letta = true, errore = if (l.dato == null) l.errore else null) }
        }
    }

    /** Crea o modifica; [fatto] riceve la materia salvata, o la frase dell'errore. */
    fun salva(id: Long?, nome: String, prof: String, colore: String, fatto: (Materia?, String?) -> Unit) {
        viewModelScope.launch {
            val e = if (id == null) {
                sorgente.creaMateria(NuovaMateria(nome.trim(), prof.trim(), colore))
            } else {
                sorgente.modificaMateria(id, buildJsonObject {
                    put("nome", nome.trim())
                    put("prof", prof.trim())
                    put("colore", colore)
                })
            }
            when (e) {
                is Esito.Ok -> {
                    carica()
                    fatto(e.dato, null)
                }
                else -> fatto(null, e.messaggio)
            }
        }
    }

    fun elimina(id: Long, fatto: (String?) -> Unit) {
        viewModelScope.launch {
            val e = sorgente.eliminaMateria(id)
            if (e is Esito.Ok) carica()
            fatto(e.messaggio)
        }
    }
}

/** "Prof. Rossi" (se Andrea ha già scritto "Prof.ssa Bianchi" si lascia com'è). */
fun scriviProf(prof: String): String? {
    val p = prof.trim()
    if (p.isEmpty()) return null
    return if (p.lowercase().startsWith("prof")) p else "Prof. $p"
}

/** "Prof. Rossi · 4 verifiche · 63%" (prototipo Materie). */
fun sottoMateria(m: Materia): String {
    val parti = mutableListOf<String>()
    scriviProf(m.prof)?.let { parti += it }
    val k = m.conteggi
    when {
        k.verifiche > 0 -> parti += if (k.verifiche == 1) "1 verifica" else "${k.verifiche} verifiche"
        k.interrogazioni > 0 -> parti += if (k.interrogazioni == 1) "1 interrogazione" else "${k.interrogazioni} interrogazioni"
        k.appunti == 0 && k.lezioni == 0 -> parti += "ancora vuota"
    }
    m.centratura?.let { parti += "${it.percentuale}%" }
    return parti.joinToString(" · ")
}

/** La piastrella della materia con l'iniziale (48, raggio 15). */
@Composable
fun PiastrellaIniziale(nome: String, colore: String?, modifier: Modifier = Modifier, fondo: Color? = null, dimensione: Dp = 48.dp) {
    val m = materia(colore)
    Box(
        modifier.size(dimensione).clip(RoundedCornerShape(15.dp)).background(fondo ?: m.tenue),
        contentAlignment = Alignment.Center,
    ) {
        Text(nome.trim().firstOrNull()?.uppercase() ?: "?", style = L.testi.forteGrande.copy(fontSize = 20.sp), color = m.testo)
    }
}

/** Materie (aspetto.md §4, prototipo Materie.dc.html): elenco e "Nuova materia" in basso. */
@Composable
fun MaterieScreen(vm: MaterieViewModel, onApri: (Long) -> Unit, esempio: Boolean, senzaRete: Boolean, apriNuova: Boolean = false, onNuovaAperta: () -> Unit = {}) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    var foglio by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(apriNuova) {
        if (apriNuova) {
            foglio = true
            onNuovaAperta()
        }
    }
    val c = L.colori
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(start = MARGINE, end = MARGINE, top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PilloleStato(esempio, senzaRete)
            TitoloPagina("Materie", Modifier.padding(top = 8.dp, bottom = 8.dp))
            if (s.errore != null && s.materie.isEmpty()) RigaErrore(s.errore!!, onRiprova = vm::carica)
            if (s.letta && s.materie.isEmpty() && s.errore == null) {
                StatoVuoto("Nessuna materia ancora", null, {})
            }
            s.materie.forEach { m ->
                Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = { onApri(m.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PiastrellaIniziale(m.nome, m.colore)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.nome, style = L.testi.forteGrande.copy(fontWeight = FontWeight.Bold), color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sottoMateria(m), style = L.testi.secondario, color = c.tenue, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (m.daRipassare > 0) {
                            Text(
                                "${m.daRipassare} da ripassare",
                                style = L.testi.piccolo.copy(fontWeight = FontWeight.Bold),
                                color = c.avvisoTesto,
                                modifier = Modifier.clip(CircleShape).background(c.avvisoTenue).padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
        eu.stgm.lectio.ui.ZonaPulsanteFisso(Modifier.align(Alignment.BottomCenter)) {
            PulsantePieno("Nuova materia", { foglio = true }, icona = Icone.Piu, abilitato = !senzaRete)
        }
    }
    if (foglio) {
        FoglioMateria(
            esistente = null,
            usati = s.materie.map { it.colore },
            onSalva = { nome, prof, colore, fatto -> vm.salva(null, nome, prof, colore) { m, e -> fatto(e); if (m != null) foglio = false } },
            onElimina = null,
            onChiudi = { foglio = false },
        )
    }
}

/**
 * Nuova materia / Modifica materia (aspetto.md §4): anteprima dal vivo, nome,
 * prof facoltativo, 8 colori (il primo non usato già scelto), Elimina con conferma.
 */
@Composable
fun FoglioMateria(
    esistente: Materia?,
    usati: List<String>,
    onSalva: (nome: String, prof: String, colore: String, fatto: (String?) -> Unit) -> Unit,
    onElimina: ((fatto: (String?) -> Unit) -> Unit)?,
    onChiudi: () -> Unit,
) {
    val c = L.colori
    var nome by rememberSaveable { mutableStateOf(esistente?.nome ?: "") }
    var prof by rememberSaveable { mutableStateOf(esistente?.prof ?: "") }
    var colore by rememberSaveable { mutableStateOf(esistente?.colore ?: (NOMI_COLORI.firstOrNull { it !in usati } ?: NOMI_COLORI.first())) }
    var errore by remember { mutableStateOf<String?>(null) }
    var inCorso by remember { mutableStateOf(false) }
    var conferma by remember { mutableStateOf(false) }
    val m = materia(colore)
    Foglio(onChiudi = onChiudi, titolo = if (esistente == null) "Nuova materia" else "Modifica materia") {
        // Anteprima dal vivo della scheda della materia.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(m.tenue).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiastrellaIniziale(nome.ifBlank { "?" }, colore, fondo = c.superficie)
            Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(nome.trim().ifEmpty { "Nome della materia" }, style = L.testi.forteGrande, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(scriviProf(prof) ?: "Prof. da scrivere", style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
            }
        }
        CampoConEtichetta("Nome") {
            Campo(
                nome, { nome = it.take(40); errore = null }, segnaposto = "Per esempio Italiano",
                tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), errore = errore != null,
            )
            if (errore != null) Text(errore!!, style = L.testi.secondario, color = c.erroreTesto)
        }
        CampoConEtichetta("Prof") {
            Campo(prof, { prof = it.take(40) }, segnaposto = "Cognome, se vuoi", tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Etichetta("Colore")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NOMI_COLORI.forEach { nomeColore ->
                    val pieno = materia(nomeColore).pieno
                    val scelto = nomeColore == colore
                    Box(
                        Modifier.weight(1f).height(44.dp).clickable(role = Role.RadioButton) { colore = nomeColore }
                            .semantics { contentDescription = nomeColore + if (scelto) ", scelto" else "" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(38.dp)
                                .then(if (scelto) Modifier.border(2.dp, pieno, CircleShape).padding(4.dp) else Modifier)
                                .clip(CircleShape).background(pieno),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (scelto) Icon(Icone.Spunta, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        PulsantePieno(
            if (esistente == null) "Crea materia" else "Salva",
            {
                inCorso = true
                onSalva(nome, prof, colore) { e ->
                    inCorso = false
                    errore = e
                }
            },
            abilitato = nome.isNotBlank() && !inCorso,
        )
        if (onElimina != null && esistente != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PulsanteTesto("Elimina materia", { conferma = true }, colore = c.testo)
            }
        }
    }
    if (conferma && onElimina != null && esistente != null) {
        Conferma(
            titolo = "Eliminare ${esistente.nome}?",
            testo = "Spariscono verifiche, appunti, lezioni e audio di ${esistente.nome}. Non si può tornare indietro.",
            conferma = "Elimina ${esistente.nome}",
            onConferma = {
                conferma = false
                onElimina { e -> errore = e }
            },
            onAnnulla = { conferma = false },
        )
    }
}
