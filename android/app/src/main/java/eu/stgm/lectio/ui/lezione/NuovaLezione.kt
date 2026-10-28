package eu.stgm.lectio.ui.lezione

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import eu.stgm.lectio.dati.Evento
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.NuovaLezione
import eu.stgm.lectio.dati.RichiestaStima
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.dati.Voce
import eu.stgm.lectio.logica.Costi
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TipoVoce
import eu.stgm.lectio.logica.VistaCosto
import eu.stgm.lectio.logica.VociDisponibili
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.BarraDueToni
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.CampoConEtichetta
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaScelta
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaElenco
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.Segmenti
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.ombra
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.morbido
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Le durate offerte (aspetto.md §6). */
val DURATE = listOf(15, 20, 30, 45)

data class StatoNuovaLezione(
    val materia: Materia? = null,
    val argomento: String = "",
    val eventi: List<Evento> = emptyList(),
    val eventoId: Long? = null,
    val materiale: List<Materiale> = emptyList(),
    /** null = "Userò" tutto il materiale (non si manda `materiali`, contratto §15.1). */
    val appuntiScelti: Set<Long>? = null,
    val durata: Int = 20,
    val saltabili: Boolean = false,
    val voci: VociDisponibili = VociDisponibili(null, null, null),
    val voceScelta: TipoVoce = TipoVoce.ESPRESSIVA,
    val stimaEspressiva: Stima? = null,
    val stimaGratis: Stima? = null,
    val stimaProva: Stima? = null,
    val inCorso: Boolean = false,
    val errore: String? = null,
    val creata: Lezione? = null,
    val pronto: Boolean = false,
) {
    val costo: VistaCosto get() = VistaCosto.calcola(stimaEspressiva, stimaGratis, stimaProva, voceScelta)

    val verifiche: List<Materiale> get() = materiale.filter { (it.tipo == "verifica" || it.tipo == "interrogazione") && it.stato == "pronto" }
    val appunti: List<Materiale> get() = materiale.filter { it.tipo != "verifica" && it.tipo != "interrogazione" && it.stato == "pronto" }

    /** "4 verifiche e 3 appunti di Storia" (aspetto.md §6). */
    val rigaUsero: String
        get() {
            val v = verifiche.size
            val a = appuntiScelti?.size ?: appunti.size
            val parti = listOfNotNull(
                v.takeIf { it > 0 }?.let { if (it == 1) "1 verifica" else "$it verifiche" },
                a.takeIf { it > 0 }?.let { if (it == 1) "1 appunto" else "$it appunti" },
            )
            val di = materia?.nome?.let { " di $it" } ?: ""
            return if (parti.isEmpty()) "Nessun materiale$di" else parti.joinToString(" e ") + di
        }

    /**
     * I `materiali` da mandare (contratto §15.1 e §15.5): null = Andrea non ha toccato
     * "Cambia" e la lezione usa tutto. Se c'è, è l'elenco COMPLETO: tutte le verifiche e
     * interrogazioni pronte più gli appunti lasciati accesi (solo materiale pronto).
     */
    val materialiScelti: List<Long>?
        get() = appuntiScelti?.let { scelti -> verifiche.map { it.id } + appunti.map { it.id }.filter { it in scelti } }

    /**
     * Ha tolto tutti gli appunti e non ci sono verifiche: `materiali: []` il server lo
     * rifiuta (422, contratto v1.3), quindi non si manda e lo si dice prima (punto 17).
     */
    val senzaMateriale: Boolean get() = materialiScelti?.isEmpty() == true

    val fraseSenzaMateriale: String? get() =
        if (senzaMateriale) "Scegli almeno un appunto: senza materiale la lezione non ha da dove partire." else null
}

class NuovaLezioneViewModel(private val sorgente: Sorgente, private val materiaId: Long, private val eventoIniziale: Long?) : ViewModel() {
    private val _stato = MutableStateFlow(StatoNuovaLezione())
    val stato: StateFlow<StatoNuovaLezione> = _stato.asStateFlow()
    private var stime: Job? = null

    fun carica() {
        viewModelScope.launch {
            val materie = async { sorgente.materie() }
            val eventi = async { sorgente.eventi() }
            val materiale = async { sorgente.materiale(materiaId) }
            val voci = async { sorgente.voci() }
            val imp = async { sorgente.impostazioni() }
            val oggi = LocalDate.now().toString()
            val prossimi = eventi.await().dato.orEmpty().filter { it.materiaId == materiaId && it.data >= oggi }.sortedBy { it.data }
            val evento = prossimi.firstOrNull { it.id == eventoIniziale } ?: prossimi.firstOrNull()
            val vociDisp = Costi.voci((voci.await() as? Esito.Ok)?.dato.orEmpty())
            val i = imp.await().dato
            _stato.update {
                it.copy(
                    materia = materie.await().dato?.firstOrNull { m -> m.id == materiaId },
                    eventi = prossimi,
                    eventoId = evento?.id,
                    argomento = it.argomento.ifBlank { evento?.argomento ?: "" },
                    materiale = materiale.await().dato.orEmpty(),
                    durata = i?.durataPredefinita?.takeIf { d -> d in DURATE } ?: 20,
                    saltabili = i?.domandeSaltabili ?: false,
                    voci = vociDisp,
                    voceScelta = Costi.voceIniziale(vociDisp, i?.vocePredefinita),
                    errore = (voci.await() as? Esito.Errore)?.messaggio ?: if (voci.await() is Esito.Irraggiungibile) voci.await().messaggio else null,
                    pronto = true,
                )
            }
            aggiornaStime()
        }
    }

    fun argomento(t: String) = _stato.update { it.copy(argomento = t.take(160)) }
    fun evento(id: Long?) = _stato.update { s ->
        val e = s.eventi.firstOrNull { it.id == id }
        s.copy(eventoId = id, argomento = if (s.argomento.isBlank() && e != null) e.argomento else s.argomento)
    }
    fun durata(d: Int) {
        _stato.update { it.copy(durata = d) }
        aggiornaStime()
    }
    fun saltabili(si: Boolean) = _stato.update { it.copy(saltabili = si) }
    fun voce(t: TipoVoce) = _stato.update { it.copy(voceScelta = t) }

    /** "Cambia": spegne o riaccende un appunto (le verifiche si usano sempre). */
    fun appunto(id: Long) {
        _stato.update { s ->
            val tutti = s.appunti.map { it.id }.toSet()
            val attuali = s.appuntiScelti ?: tutti
            val nuovi = if (id in attuali) attuali - id else attuali + id
            s.copy(appuntiScelti = if (nuovi == tutti) null else nuovi)
        }
        // Meno appunti, lezione più corta da scrivere: la stima va rifatta con gli stessi materiali.
        aggiornaStime()
    }

    /** Una stima per voce: il costo di ogni scheda viene dal server (contratto §8). */
    private fun aggiornaStime() {
        stime?.cancel()
        stime = viewModelScope.launch {
            val s = _stato.value
            if (s.senzaMateriale) return@launch
            fun richiesta(v: Voce?) = v?.let { RichiestaStima(materiaId, s.durata, it.id, s.materialiScelti) }
            val e = richiesta(s.voci.espressiva)?.let { async { sorgente.stima(it) } }
            val g = richiesta(s.voci.gratis)?.let { async { sorgente.stima(it) } }
            val p = richiesta(s.voci.prova)?.let { async { sorgente.stima(it) } }
            val re = e?.await()
            val rg = g?.await()
            val rp = p?.await()
            _stato.update {
                it.copy(
                    stimaEspressiva = (re as? Esito.Ok)?.dato,
                    stimaGratis = (rg as? Esito.Ok)?.dato,
                    stimaProva = (rp as? Esito.Ok)?.dato,
                    errore = listOfNotNull(re, rg, rp).firstOrNull { r -> r !is Esito.Ok }?.messaggio,
                )
            }
        }
    }

    fun prepara() {
        val s = _stato.value
        val costo = s.costo
        val voce = when (costo.voceScelta) {
            TipoVoce.ESPRESSIVA -> s.voci.espressiva
            TipoVoce.GRATIS -> s.voci.gratis
            TipoVoce.PROVA -> s.voci.prova ?: s.voci.gratis ?: s.voci.espressiva
        } ?: return
        if (s.argomento.isBlank() || s.inCorso || !costo.consentita || s.senzaMateriale) return
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            val materiali = s.materialiScelti
            val e = sorgente.creaLezione(
                NuovaLezione(materiaId, s.eventoId, s.argomento.trim(), materiali, s.durata, voce.id, s.saltabili),
            )
            _stato.update {
                when (e) {
                    is Esito.Ok -> it.copy(inCorso = false, creata = e.dato)
                    else -> it.copy(inCorso = false, errore = e.messaggio)
                }
            }
        }
    }
}

/** Nuova lezione (aspetto.md §6, prototipo NuovaLezione.dc.html). */
@Composable
fun NuovaLezioneScreen(vm: NuovaLezioneViewModel, onIndietro: () -> Unit, onCreata: (Lezione) -> Unit, esempio: Boolean, senzaRete: Boolean) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    LaunchedEffect(s.creata) { s.creata?.let(onCreata) }
    val c = L.colori
    var sceltaPer by remember { mutableStateOf(false) }
    var cambia by remember { mutableStateOf(false) }
    val costo = s.costo
    val prova = s.voci.soloProva
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulsanteTondo(Icone.Indietro, "Indietro", onIndietro)
                s.materia?.let { PillolaMateria(it.nome, it.colore) }
                Spacer(Modifier.weight(1f))
                if (esempio) PillolaStato(TipoPillola.ESEMPIO) else if (prova) PillolaStato(TipoPillola.PROVA)
            }
            TitoloPagina("Nuova lezione")
            CampoConEtichetta("Argomento") {
                Campo(s.argomento, vm::argomento, segnaposto = "Per esempio L'Impero romano", tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            CampoConEtichetta("Per") {
                val e = s.eventi.firstOrNull { it.id == s.eventoId }
                val testo = e?.let { "${Formati.tipoEvento(it)} di ${Formati.giornoOVuoto(it.data)}" } ?: "Nessuna"
                Text(
                    testo,
                    style = L.testi.pulsantePiccolo,
                    color = c.testo,
                    modifier = Modifier.ombra(CircleShape).clip(CircleShape).background(c.superficie)
                        .clickable(role = Role.Button) { sceltaPer = true }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spazio = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateContentSize(morbido())) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Etichetta("Userò")
                        Text(s.rigaUsero, style = L.testi.pulsantePiccolo, color = c.testo)
                    }
                    if (s.appunti.isNotEmpty()) PulsanteTesto(if (cambia) "Fatto" else "Cambia", { cambia = !cambia })
                }
                if (cambia) AppuntiDaUsare(s, vm::appunto)
                s.fraseSenzaMateriale?.let { Text(it, style = L.testi.secondario, color = c.avvisoTesto) }
            }
            CampoConEtichetta("Durata") {
                Segmenti(DURATE.map { "$it min" }, DURATE.indexOf(s.durata).coerceAtLeast(0), { vm.durata(DURATE[it]) }, Modifier.bersaglio(Bersagli.DURATA))
            }
            CampoConEtichetta("Domande") {
                Segmenti(listOf("Obbligatorie", "Si possono saltare"), if (s.saltabili) 1 else 0, { vm.saltabili(it == 1) })
            }
            CampoConEtichetta("Voce") {
                if (s.pronto && s.voci.espressiva == null && s.voci.gratis == null && s.voci.prova == null && s.errore == null) {
                    RigaAvviso("Nessuna voce è pronta sul server di casa: serve la chiave Mistral o quella di Azure.")
                } else if (prova) {
                    RigaAvviso("Voce di prova, robotica: serve la chiave Mistral per quella vera.")
                } else {
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SchedaVoce(
                            "Lorenzo", "voce espressiva",
                            if (costo.espressivaSpenta) costo.motivoSpenta ?: "" else costo.costoVoceEspressiva?.let(Formati::euro) ?: "…",
                            null,
                            scelta = costo.voceScelta == TipoVoce.ESPRESSIVA,
                            spenta = costo.espressivaSpenta || s.voci.espressiva == null,
                            onClick = { vm.voce(TipoVoce.ESPRESSIVA) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        SchedaVoce(
                            "Voce gratis", s.voci.gratis?.nome ?: "Isabella", "0 €",
                            costo.caratteriGratisRimasti?.let { "${Formati.numero(it)} caratteri gratis" },
                            scelta = costo.voceScelta == TipoVoce.GRATIS,
                            spenta = s.voci.gratis == null,
                            onClick = { vm.voce(TipoVoce.GRATIS) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), spazio = 8.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(costo.rigaMese, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo, modifier = Modifier.weight(1f))
                    val dopo by animateFloatAsState((costo.dopo ?: costo.spesoMese).toFloat(), morbido(350), label = "dopo")
                    Text(
                        if (costo.dopo == null) costo.rigaDopo else "dopo questa ${Formati.euro(dopo.toDouble())} di ${Formati.euro(costo.tetto)}",
                        style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo,
                    )
                }
                // Il lavoro già in corso (lezioni in coda, letture): conta nel mese, ma a parte.
                costo.rigaImpegnato?.let { Text(it, style = L.testi.secondario, color = c.tenue) }
                BarraDueToni(costo.frazioneSpeso, costo.frazioneQuesta)
                Formati.siAzzera(costo.siAzzeraIl)?.let { Text(it, style = L.testi.secondario, color = c.moltoTenue) }
            }
            costo.blocco?.let { RigaAvviso(it) }
            s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }
            if (senzaRete && !esempio) RigaAvviso("Senza rete non si può preparare: il server di casa non risponde.")
        }
        eu.stgm.lectio.ui.ZonaPulsanteFisso(Modifier.align(Alignment.BottomCenter), verticale = 24.dp) {
            PulsantePieno(
                if (s.inCorso) "Preparo…" else costo.etichettaPulsante,
                vm::prepara,
                Modifier.bersaglio(Bersagli.PREPARA),
                abilitato = s.pronto && s.argomento.isNotBlank() && costo.consentita && !s.senzaMateriale && !s.inCorso && (!senzaRete || esempio),
            )
        }
    }
    if (sceltaPer) {
        Foglio(onChiudi = { sceltaPer = false }, titolo = "Per quale verifica?") {
            s.eventi.forEach { e ->
                RigaElenco(Formati.titoloEvento(e), Formati.giornoOVuoto(e.data), onClick = { vm.evento(e.id); sceltaPer = false })
            }
            RigaElenco("Nessuna", null, onClick = { vm.evento(null); sceltaPer = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppuntiDaUsare(s: StatoNuovaLezione, onTocca: (Long) -> Unit) {
    val scelti = s.appuntiScelti ?: s.appunti.map { it.id }.toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.appunti.forEach { m -> PillolaScelta(m.titolo, m.id in scelti, { onTocca(m.id) }, icona = if (m.id in scelti) Icone.Spunta else null) }
    }
}

@Composable
private fun SchedaVoce(nome: String, sotto: String, costo: String, extra: String?, scelta: Boolean, spenta: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = L.colori
    val forma = RoundedCornerShape(18.dp)
    Column(
        modifier
            .ombra(forma)
            .clip(forma)
            .background(c.superficie)
            .border(2.dp, if (scelta && !spenta) c.accento else c.superficie.copy(alpha = 0f), forma)
            .clickable(enabled = !spenta, role = Role.RadioButton, onClick = onClick)
            .alpha(if (spenta) 0.55f else 1f)
            .padding(14.dp)
            .semantics { contentDescription = "$nome, $sotto, $costo" + if (scelta) ", scelta" else "" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(nome, style = L.testi.forteGrande, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sotto, style = L.testi.secondario, color = c.tenue)
        Text(costo, style = if (spenta) L.testi.secondario.copy(fontWeight = FontWeight.Bold) else L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = c.testo, modifier = Modifier.padding(top = 6.dp))
        if (extra != null) Text(extra, style = L.testi.piccolo, color = c.tenue)
    }
}
