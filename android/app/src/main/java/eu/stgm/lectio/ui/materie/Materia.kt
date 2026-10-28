package eu.stgm.lectio.ui.materie

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.DaRipassare
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.NuovaMateria
import eu.stgm.lectio.dati.NuovoEvento
import eu.stgm.lectio.dati.SchedaProf
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Esiti
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.logica.ToniEsito
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
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.Segmenti
import eu.stgm.lectio.ui.Segnaposto
import eu.stgm.lectio.ui.TitoloSezione
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.lezioni.RigaLezione
import eu.stgm.lectio.ui.materiale.CampoData
import eu.stgm.lectio.ui.tema.L
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
import java.time.LocalDate

data class StatoMateria(
    val materia: Materia? = null,
    val usati: List<String> = emptyList(),
    val scheda: SchedaProf? = null,
    val prossime: List<Evento> = emptyList(),
    val lezioniEventi: Map<Long, Lezione> = emptyMap(),
    val materiale: List<Materiale> = emptyList(),
    val ripassare: List<DaRipassare> = emptyList(),
    val lezioni: List<Lezione> = emptyList(),
    val errore: String? = null,
    val messaggio: String? = null,
    val eliminata: Boolean = false,
    val oggi: LocalDate = LocalDate.now(),
)

class MateriaViewModel(
    private val sorgente: Sorgente,
    val materiaId: Long,
    private val oggi: () -> LocalDate = LocalDate::now,
    /** Dopo "Elimina materia": le sue lezioni spariscono anche dal telefono (audio e testi). */
    private val togliLezioniLocali: suspend (List<Long>) -> Unit = {},
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoMateria())
    val stato: StateFlow<StatoMateria> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val materie = sorgente.materie()
            val scheda = sorgente.scheda(materiaId)
            val eventi = sorgente.eventi()
            val materiale = sorgente.materiale(materiaId)
            val ripassare = sorgente.daRipassare(materiaId)
            val lezioni = sorgente.lezioni(materiaId)
            val oggiData = oggi()
            val prossime = eventi.dato.orEmpty()
                .filter { it.materiaId == materiaId && (Formati.data(it.data)?.let { d -> !d.isBefore(oggiData) } ?: false) }
                .sortedBy { it.data }
            _stato.update {
                it.copy(
                    materia = materie.dato?.firstOrNull { m -> m.id == materiaId } ?: it.materia,
                    usati = materie.dato.orEmpty().filter { m -> m.id != materiaId }.map { m -> m.colore },
                    scheda = scheda.dato ?: it.scheda,
                    prossime = prossime,
                    lezioniEventi = lezioni.dato.orEmpty().associateBy { l -> l.id },
                    materiale = materiale.dato ?: it.materiale,
                    ripassare = ripassare.dato ?: it.ripassare,
                    lezioni = lezioni.dato ?: it.lezioni,
                    errore = listOf(materie, scheda, materiale).firstNotNullOfOrNull { l -> l.errore.takeIf { l.dato == null } },
                    oggi = oggiData,
                )
            }
        }
    }

    /** Mentre il server legge un file o aggiorna la scheda, si ricontrolla ogni pochi secondi. */
    val inAttesa: Boolean
        get() = _stato.value.materiale.any { it.stato == "in_lettura" } || _stato.value.scheda?.stato == "in_aggiornamento"

    fun aggiornaScheda() = azione { sorgente.aggiornaScheda(materiaId) }
    fun riprovaMateriale(id: Long) = azione { sorgente.riprovaMateriale(id) }

    fun salvaEvento(id: Long?, nuovo: NuovoEvento, fatto: (String?) -> Unit) {
        viewModelScope.launch {
            val e = if (id == null) sorgente.creaEvento(materiaId, nuovo) else sorgente.modificaEvento(id, nuovo)
            if (e is Esito.Ok) carica()
            fatto(e.messaggio)
        }
    }

    fun eliminaEvento(id: Long) = azione { sorgente.eliminaEvento(id) }

    fun salvaMateria(nome: String, prof: String, colore: String, fatto: (String?) -> Unit) {
        viewModelScope.launch {
            val e = sorgente.modificaMateria(materiaId, buildJsonObject {
                put("nome", nome.trim())
                put("prof", prof.trim())
                put("colore", colore)
            })
            if (e is Esito.Ok) carica()
            fatto(e.messaggio)
        }
    }

    fun eliminaMateria(fatto: (String?) -> Unit) {
        viewModelScope.launch {
            // Prima si raccolgono le sue lezioni: dopo il server non le ha più.
            val lezioni = (sorgente.lezioni(materiaId).dato ?: _stato.value.lezioni).map { it.id }.toSet() + _stato.value.lezioni.map { it.id }
            val e = sorgente.eliminaMateria(materiaId)
            if (e is Esito.Ok) {
                togliLezioniLocali(lezioni.toList())
                _stato.update { it.copy(eliminata = true) }
            }
            fatto(e.messaggio)
        }
    }

    private fun azione(chiamata: suspend () -> Esito<*>) {
        viewModelScope.launch {
            val e = chiamata()
            _stato.update { it.copy(messaggio = e.messaggio) }
            carica()
        }
    }

    fun messaggioVisto() = _stato.update { it.copy(messaggio = null) }

    /** Per i test: crea la materia nel finto (non usato dall'app). */
    suspend fun crea(nuova: NuovaMateria) = sorgente.creaMateria(nuova)
}

data class AzioniMateria(
    val indietro: () -> Unit = {},
    val modificaScheda: () -> Unit = {},
    val aggiungi: (TipoMateriale?) -> Unit = {},
    val apriMateriale: (Long) -> Unit = {},
    val nuovaLezione: (eventoId: Long?) -> Unit = {},
    val apriLezione: (Lezione) -> Unit = {},
    val ripassa: (DaRipassare) -> Unit = {},
)

/** La pagina della materia (aspetto.md §4, prototipo Materia.dc.html). */
@Composable
fun MateriaScreen(vm: MateriaViewModel, azioni: AzioniMateria, esempio: Boolean, senzaRete: Boolean, scaricate: Set<Long>) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.materiale, s.scheda) {
        while (vm.inAttesa) {
            delay(4_000)
            vm.carica()
        }
    }
    LaunchedEffect(s.eliminata) { if (s.eliminata) azioni.indietro() }
    val c = L.colori
    val m = s.materia
    val colori = materia(m?.colore)
    val visita = LocalVisita.current
    var foglioMateria by remember { mutableStateOf(false) }
    var evento by remember { mutableStateOf<Evento?>(null) }
    var nuovoEvento by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp)) {
            // Testata col fondo tenue della materia.
            Column(
                Modifier.fillMaxWidth().background(colori.tenue).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, fondo = c.superficie.copy(alpha = 0.7f), tinta = colori.testo)
                    Spacer(Modifier.weight(1f))
                    PilloleStato(esempio, senzaRete)
                    Spacer(Modifier.width(8.dp))
                    if (!esempio) PulsanteTondo(Icone.AltreAzioni, "Modifica materia", { foglioMateria = true }, fondo = c.superficie.copy(alpha = 0.7f), tinta = colori.testo)
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(m?.nome ?: "", style = L.testi.titoloPagina, color = colori.testo)
                    scriviProf(m?.prof ?: "")?.let { Text(it, style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = colori.testo) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Numero(m?.conteggi?.verifiche?.toString() ?: "0", "verifiche lette", Modifier.weight(1f))
                    Numero(m?.conteggi?.lezioni?.toString() ?: "0", "lezioni", Modifier.weight(1f))
                    Numero(m?.centratura?.let { "${it.percentuale}%" } ?: "–", "centratura", Modifier.weight(1f))
                }
            }

            Column(Modifier.padding(MARGINE), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }
                SchedaDelProf(s.scheda, m?.colore, senzaRete, azioni, vm::aggiornaScheda, Modifier.bersaglio(Bersagli.SCHEDA_PROF))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TitoloSezione("Prossime", Modifier.weight(1f))
                        PulsanteTesto("Aggiungi data", { nuovoEvento = true }, abilitato = !senzaRete)
                    }
                    s.prossime.forEach { e ->
                        val lezione = e.lezioneId?.let { s.lezioniEventi[it] }
                        val statoLezione = when {
                            lezione == null -> "nessuna lezione"
                            lezione.pronta -> "lezione pronta"
                            lezione.inLavoro -> "lezione in preparazione"
                            else -> "lezione da rifare"
                        }
                        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), onClick = { evento = e }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(Formati.titoloEvento(e), style = L.testi.forte, color = c.testo)
                                    Text("${Formati.giornoOVuoto(e.data, s.oggi)} · $statoLezione", style = L.testi.secondario, color = c.tenue)
                                }
                                val giorni = Formati.data(e.data)?.let { Formati.giorniTra(s.oggi, it) } ?: 0
                                Text(Formati.traQuanto(giorni), style = L.testi.pillola, color = c.testo)
                            }
                        }
                    }
                }

                if (s.ripassare.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TitoloSezione("Da ripassare")
                        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                            s.ripassare.forEachIndexed { i, r ->
                                if (i > 0) Riga()
                                Row(
                                    Modifier.fillMaxWidth().clickable(role = Role.Button) { azioni.ripassa(r) }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(r.testo, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold, lineHeight = 20.sp), color = c.testo)
                                        val quando = Formati.data(r.quando)?.let { Formati.traQuanto(Formati.giorniTra(s.oggi, it)) }
                                        Text(listOfNotNull(r.lezione.takeIf { it.isNotBlank() }, quando).joinToString(" · "), style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium), color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    PillolaEsito(Esiti.parolaRipasso(r.esito), Esiti.tono(r.esito))
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TitoloSezione("Materiale", Modifier.weight(1f))
                        PulsanteSecondario(
                            "Aggiungi",
                            {
                                visita?.azione(Bersagli.AGGIUNGI)
                                if (visita?.attiva != true) azioni.aggiungi(null)
                            },
                            Modifier.bersaglio(Bersagli.AGGIUNGI), altezza = 40.dp, raggio = 12.dp,
                            abilitato = !senzaRete || esempio,
                        )
                    }
                    if (s.materiale.isNotEmpty()) {
                        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                            s.materiale.forEachIndexed { i, mat ->
                                if (i > 0) Riga()
                                RigaMateriale(mat, colori.testo, s.oggi, { azioni.apriMateriale(mat.id) }, { vm.riprovaMateriale(mat.id) })
                            }
                        }
                    }
                }

                if (s.lezioni.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TitoloSezione("Lezioni")
                        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                            s.lezioni.forEachIndexed { i, l ->
                                if (i > 0) Riga()
                                RigaLezione(l, m?.nome, m?.colore, l.id in scaricate, { azioni.apriLezione(l) })
                            }
                        }
                    }
                }
            }
        }
        eu.stgm.lectio.ui.ZonaPulsanteFisso(Modifier.align(Alignment.BottomCenter)) {
            PulsantePieno(
                "Nuova lezione",
                {
                    visita?.azione(Bersagli.NUOVA_LEZIONE)
                    if (visita?.attiva != true) azioni.nuovaLezione(s.prossime.firstOrNull()?.id)
                },
                Modifier.bersaglio(Bersagli.NUOVA_LEZIONE), icona = Icone.Piu, abilitato = !senzaRete || esempio,
            )
        }
    }

    if (foglioMateria && m != null) {
        FoglioMateria(
            esistente = m,
            usati = s.usati,
            onSalva = { nome, prof, colore, fatto -> vm.salvaMateria(nome, prof, colore) { e -> fatto(e); if (e == null) foglioMateria = false } },
            onElimina = { fatto -> vm.eliminaMateria(fatto) },
            onChiudi = { foglioMateria = false },
        )
    }
    if (nuovoEvento || evento != null) {
        FoglioEvento(evento, onSalva = { nuovo, fatto ->
            vm.salvaEvento(evento?.id, nuovo) { e ->
                fatto(e)
                if (e == null) {
                    evento = null
                    nuovoEvento = false
                }
            }
        }, onElimina = evento?.let { e -> { vm.eliminaEvento(e.id); evento = null } }, onChiudi = {
            evento = null
            nuovoEvento = false
        })
    }
}

@Composable
private fun Numero(valore: String, etichetta: String, modifier: Modifier) {
    val c = L.colori
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.superficie.copy(alpha = 0.75f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(valore, style = L.testi.forteGrande.copy(fontSize = 20.sp), color = c.testo)
        Text(etichetta, style = L.testi.piccolo, color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** La pillola dell'esito: Giusta verde, Quasi ambra, Da rivedere ardesia (mai rosso, §7). */
@Composable
fun PillolaEsito(parola: String, tono: ToniEsito, grande: Boolean = false) {
    val (fondo, testo) = coloriEsito(tono)
    Row(
        Modifier.clip(CircleShape).background(fondo).padding(horizontal = if (grande) 12.dp else 10.dp, vertical = if (grande) 6.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (grande) {
            val icona = when (tono) {
                ToniEsito.GIUSTA -> Icone.Spunta
                ToniEsito.QUASI -> Icone.Meta
                ToniEsito.DA_RIVEDERE -> Icone.Riprendi
                ToniEsito.NEUTRO -> null
            }
            if (icona != null) {
                Icon(icona, contentDescription = null, tint = testo, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(parola, style = (if (grande) L.testi.pulsantePiccolo else L.testi.piccolo).copy(fontWeight = FontWeight.ExtraBold), color = testo)
    }
}

@Composable
fun coloriEsito(tono: ToniEsito): Pair<Color, Color> {
    val c = L.colori
    return when (tono) {
        ToniEsito.GIUSTA -> c.fattoTenue to (if (c.scuro) c.fattoTesto else Color(0xFF1F6A30))
        ToniEsito.QUASI -> c.avvisoTenue to c.avvisoTesto
        ToniEsito.DA_RIVEDERE -> (if (c.scuro) Color(0xFF2A2D31) else Color(0xFFEEF0F2)) to (if (c.scuro) Color(0xFFC9CED4) else Color(0xFF343A40))
        ToniEsito.NEUTRO -> c.superficie2 to c.testo
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchedaDelProf(
    scheda: SchedaProf?,
    colore: String?,
    senzaRete: Boolean,
    azioni: AzioniMateria,
    aggiorna: () -> Unit,
    modifier: Modifier,
) {
    val c = L.colori
    val m = materia(colore)
    Scheda(modifier, padding = PaddingValues(18.dp), spazio = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitoloSezione("Cosa chiede il prof", Modifier.weight(1f))
            if (scheda != null && scheda.stato != "vuota") PulsanteTesto("Modifica", azioni.modificaScheda, abilitato = !senzaRete)
        }
        when (scheda?.stato) {
            null -> Segnaposto(altezza = 16.dp)
            "vuota" -> {
                Text("Ancora nessuna verifica letta.", style = L.testi.testo, color = c.tenue)
                PulsanteSecondario("Aggiungi una verifica", { azioni.aggiungi(TipoMateriale.VERIFICA) }, abilitato = !senzaRete)
            }
            "in_aggiornamento" -> {
                Text("Sto aggiornando la scheda dalle tue verifiche.", style = L.testi.secondario, color = c.tenue)
                Segnaposto(altezza = 30.dp)
                Segnaposto(altezza = 14.dp, larghezza = 0.7f)
            }
            "errore" -> RigaErrore(scheda.errore ?: "Non sono riuscito ad aggiornare la scheda.", onRiprova = if (senzaRete) null else aggiorna)
            else -> Unit
        }
        if (scheda != null && scheda.stato in listOf("pronta", "errore", "in_aggiornamento") && (scheda.argomenti.isNotEmpty() || scheda.tipiDomanda.isNotEmpty())) {
            if (scheda.argomenti.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scheda.argomenti.forEach { a ->
                        Text(
                            "${a.nome} · ${a.volte}",
                            style = L.testi.pillola, color = m.testo,
                            modifier = Modifier.clip(CircleShape).background(m.tenue).padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
            val tipi = scheda.tipiDomanda.filter { it.quota > 0 }.sortedByDescending { it.quota }
            if (tipi.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        tipi.forEachIndexed { i, t -> Box(Modifier.weight(t.quota.toFloat()).height(10.dp).background(m.sfumatura(i, c.superficie))) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        tipi.forEach { t -> Text("${nomeTipo(t.tipo)} ${(t.quota * 100).toInt()}%", style = L.testi.piccolo, color = c.tenue) }
                    }
                }
            }
            Elenco("Cosa corregge", scheda.cosaCorregge)
            Elenco("Cosa premia", scheda.cosaPremia)
            if (scheda.noteMie.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Etichetta("Le tue note")
                    Text(scheda.noteMie, style = L.testi.testoMedio, color = c.testo)
                }
            }
            Text(basataSu(scheda), style = L.testi.secondario, color = c.moltoTenue)
        }
    }
}

@Composable
private fun Elenco(titolo: String, voci: List<String>) {
    if (voci.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Etichetta(titolo)
        voci.forEach { Text(it, style = L.testi.testoMedio, color = L.colori.testo) }
    }
}

fun nomeTipo(tipo: String): String = when (tipo) {
    "aperta" -> "Aperte"
    "definizione" -> "Definizioni"
    "date" -> "Date"
    "esercizio" -> "Esercizi"
    "vero_falso" -> "Vero o falso"
    "scelta_multipla" -> "Scelta multipla"
    "collegamento" -> "Collegamenti"
    "traduzione" -> "Traduzioni"
    else -> "Altro"
}

/** "Basata su 4 verifiche e 1 interrogazione". */
fun basataSu(s: SchedaProf): String {
    val v = s.basataSu.verifiche
    val i = s.basataSu.interrogazioni
    val parti = listOfNotNull(
        v.takeIf { it > 0 }?.let { if (it == 1) "1 verifica" else "$it verifiche" },
        i.takeIf { it > 0 }?.let { if (it == 1) "1 interrogazione" else "$it interrogazioni" },
    )
    return if (parti.isEmpty()) "Basata sui tuoi appunti" else "Basata su " + parti.joinToString(" e ")
}

fun iconaMateriale(tipo: String): ImageVector = when (tipo) {
    "verifica" -> Icone.Verifica
    "interrogazione" -> Icone.Interrogazione
    "appunti" -> Icone.Appunti
    "libro" -> Icone.Libro
    else -> Icone.File
}

@Composable
private fun RigaMateriale(m: Materiale, colore: Color, oggi: LocalDate, onApri: () -> Unit, onRiprova: () -> Unit) {
    val c = L.colori
    Row(
        Modifier.fillMaxWidth().clickable(enabled = m.stato == "pronto", role = Role.Button, onClick = onApri).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(iconaMateriale(m.tipo), contentDescription = null, tint = colore, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (m.stato) {
                "in_lettura" -> {
                    Text(m.titolo.ifBlank { m.nomeFile ?: "File" }, style = L.testi.forte, color = c.tenue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Sto leggendo il file", style = L.testi.secondario, color = c.tenue)
                    Segnaposto(altezza = 8.dp, larghezza = 0.6f)
                }
                "errore" -> {
                    Text(m.titolo.ifBlank { m.nomeFile ?: "File" }, style = L.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.errore ?: "Non sono riuscito a leggerlo.", style = L.testi.secondario, color = c.erroreTesto)
                }
                else -> {
                    Text(m.titolo, style = L.testi.forte, color = c.testo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Formati.data(m.data)?.let { Formati.giornoBreve(it, oggi) } ?: TipoMateriale.da(m.tipo).etichetta, style = L.testi.secondario, color = c.tenue)
                    m.avviso?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.secondario, color = c.avvisoTesto) }
                }
            }
        }
        if (m.stato == "errore") PulsanteTesto("Riprova", onRiprova)
    }
}

/** Aggiungi data / modifica (aspetto.md §4): scritta o orale, argomento, data. */
@Composable
private fun FoglioEvento(esistente: Evento?, onSalva: (NuovoEvento, (String?) -> Unit) -> Unit, onElimina: (() -> Unit)?, onChiudi: () -> Unit) {
    var orale by rememberSaveable { mutableStateOf(esistente?.orale ?: false) }
    var argomento by rememberSaveable { mutableStateOf(esistente?.argomento ?: "") }
    var data by rememberSaveable { mutableStateOf(esistente?.data ?: LocalDate.now().plusDays(7).toString()) }
    var errore by remember { mutableStateOf<String?>(null) }
    var conferma by remember { mutableStateOf(false) }
    Foglio(onChiudi = onChiudi, titolo = if (esistente == null) "Aggiungi data" else "Modifica data") {
        Segmenti(listOf("Scritta", "Orale"), if (orale) 1 else 0, { orale = it == 1 })
        CampoConEtichetta("Argomento") {
            Campo(argomento, { argomento = it.take(120) }, segnaposto = "Per esempio L'Impero romano", tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        CampoData(data) { data = it }
        errore?.let { RigaErrore(it) }
        PulsantePieno("Salva", { onSalva(NuovoEvento(if (orale) "orale" else "scritta", argomento.trim(), data)) { errore = it } }, abilitato = argomento.isNotBlank())
        if (onElimina != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PulsanteTesto("Elimina questa data", { conferma = true }, colore = L.colori.testo)
            }
        }
    }
    if (conferma && onElimina != null) {
        Conferma("Eliminare questa data?", "La data sparisce da Oggi e dalla materia. Le lezioni restano.", "Elimina", { conferma = false; onElimina() }, { conferma = false })
    }
}
