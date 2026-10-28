package eu.stgm.lectio.ui.oggi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Lezione
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Oggi
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.Preparazione
import eu.stgm.lectio.ui.Barra
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PaddingPagina
import eu.stgm.lectio.ui.Pallino
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.Piastrella
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.SimboloLectio
import eu.stgm.lectio.ui.StatoVuoto
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StatoOggi(
    val oggi: Oggi? = null,
    val materie: List<Materia> = emptyList(),
    /** Il dettaglio della lezione della prossima (capitoli e domande per "4 capitoli · 6 domande"). */
    val dettaglioProssima: Lezione? = null,
    /** GET /api/voci dice che la voce gratis c'è (senza Azure o a caratteri finiti no). */
    val voceGratis: Boolean = false,
    val caricamento: Boolean = true,
    val errore: String? = null,
    val primaLettura: Boolean = false,
)

class OggiViewModel(private val sorgente: Sorgente) : ViewModel() {
    private val _stato = MutableStateFlow(StatoOggi())
    val stato: StateFlow<StatoOggi> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            _stato.update { it.copy(caricamento = true) }
            val oggi = sorgente.oggi()
            val materie = sorgente.materie()
            val prossima = oggi.dato?.prossima?.lezione
            val dettaglio = if (prossima != null && prossima.pronta) sorgente.lezione(prossima.id).dato else null
            val voci = (sorgente.voci() as? eu.stgm.lectio.rete.Esito.Ok)?.dato.orEmpty()
            _stato.update {
                it.copy(
                    oggi = oggi.dato ?: it.oggi,
                    materie = materie.dato ?: it.materie,
                    dettaglioProssima = dettaglio,
                    voceGratis = voci.any { v -> v.tipo == "gratis" && v.disponibile },
                    caricamento = false,
                    errore = if (oggi.dato == null) oggi.errore else null,
                    primaLettura = true,
                )
            }
        }
    }
}

/** Cosa si può fare da Oggi (le pagine le apre l'attività). */
data class AzioniOggi(
    val impostazioni: () -> Unit = {},
    val apriMateria: (Long) -> Unit = {},
    val ascolta: (Long) -> Unit = {},
    val preparazione: (Long) -> Unit = {},
    val prepara: (materiaId: Long, eventoId: Long?) -> Unit = { _, _ -> },
    val racconta: (Long) -> Unit = {},
    val vediTutte: () -> Unit = {},
    val nuovaMateria: () -> Unit = {},
    val apriMaterie: () -> Unit = {},
    val visita: () -> Unit = {},
)

/** Oggi (aspetto.md §3, prototipo Main.dc.html): parte dalla prossima verifica. */
@Composable
fun OggiScreen(vm: OggiViewModel, azioni: AzioniOggi, esempio: Boolean, senzaRete: Boolean, scaricate: Set<Long> = emptySet()) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    val visita = LocalVisita.current
    val o = s.oggi
    val oggiData = Formati.data(o?.oggi) ?: LocalDate.now()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(PaddingPagina),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SimboloLectio()
            Spacer(Modifier.width(10.dp))
            Text("Lectio", style = L.testi.forteGrande.copy(fontSize = 18.sp, letterSpacing = (-0.3).sp), color = c.testo)
            Spacer(Modifier.weight(1f))
            PulsanteTondo(Icone.Ingranaggio, "Impostazioni", azioni.impostazioni)
        }
        PilloleStato(esempio = esempio, senzaRete = senzaRete, prova = o?.modalitaProva == true)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TitoloPagina("Oggi")
            Text(Formati.giorno(oggiData, oggiData), style = L.testi.secondario, color = c.tenue)
        }

        if (o == null) {
            if (s.errore != null) RigaErrore(s.errore!!, onRiprova = vm::carica)
            return@Column
        }
        if (s.primaLettura && s.materie.isEmpty()) {
            StatoVuoto("Aggiungi la prima materia", "Nuova materia", azioni.nuovaMateria, icona = Icone.Piu)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PulsanteTesto("Prima volta? Guarda come funziona", azioni.visita)
            }
            return@Column
        }

        val p = o.prossima
        if (p == null) {
            StatoVuoto("Nessuna verifica in arrivo", "Apri le materie", azioni.apriMaterie)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Etichetta("La prossima")
                Scheda(Modifier.bersaglio(Bersagli.PROSSIMA), rilievo = true, padding = PaddingValues(20.dp), spazio = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillolaMateria(p.materia.nome, p.materia.colore, Modifier.bersaglio(Bersagli.PILLOLA_MATERIA)) {
                            visita?.azione(Bersagli.PILLOLA_MATERIA)
                            azioni.apriMateria(p.materia.id)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(Formati.traQuanto(p.traGiorni), style = L.testi.pillola, color = c.testo)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Formati.titoloEvento(p.evento), style = L.testi.titoloScheda, color = c.testo)
                        Text(
                            "${Formati.giornoOVuoto(p.evento.data, oggiData)} · ${Formati.tipoBreve(p.evento)}",
                            style = L.testi.secondario, color = c.tenue,
                        )
                    }
                    StatoLezioneProssima(p.lezione, s.dettaglioProssima, p.materia.colore, p.materia.id, p.evento.id, azioni, senzaRete, scaricate)
                }
            }
        }

        o.riprendi?.let { l ->
            val m = s.materie.firstOrNull { it.id == l.materiaId }
            Scheda {
                Etichetta("Riprendi")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Piastrella(m?.colore)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(l.argomento, style = L.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pos = l.ascolto?.posizioneS ?: 0.0
                        Barra((pos / l.durataSecondi).toFloat(), altezza = 4.dp)
                        Text(
                            listOfNotNull(m?.nome, Formati.mancanoMinuti(l.durataSecondi - pos)).joinToString(" · "),
                            style = L.testi.secondario, color = c.tenue, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    PulsanteTondo(Icone.Play, "Riprendi la lezione", { azioni.ascolta(l.id) }, fondo = c.accentoTenue, tinta = c.accentoTesto, dimensione = 48.dp, dimensioneIcona = 18.dp)
                }
            }
        }

        o.daRaccontare.forEach { r ->
            Scheda {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Etichetta("Dopo la verifica")
                        val cosa = if (r.evento.orale) "l'interrogazione" else "la verifica"
                        Text("Com'è andata $cosa di ${r.materia.nome}?", style = L.testi.forte, color = c.testo)
                        Text(Formati.giornoOVuoto(r.evento.data, oggiData), style = L.testi.secondario, color = c.tenue)
                    }
                    PulsanteSecondario("Racconta", { azioni.racconta(r.evento.id) }, abilitato = !senzaRete)
                }
            }
        }

        if (o.piuAvanti.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Etichetta("Più avanti")
                Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                    o.piuAvanti.take(4).forEachIndexed { i, a ->
                        if (i > 0) Riga()
                        Row(
                            Modifier.fillMaxWidth().clickable { azioni.apriMateria(a.materia.id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Pallino(materia(a.materia.colore).pieno)
                            Column(Modifier.weight(1f)) {
                                Text(a.materia.nome, style = L.testi.forte, color = c.testo)
                                Text("${Formati.tipoEvento(a.evento)} · ${Formati.giornoOVuoto(a.evento.data, oggiData)}", style = L.testi.secondario, color = c.tenue)
                            }
                            Text(Formati.giorniBreve(a.traGiorni), style = L.testi.secondario.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.tenue)
                        }
                    }
                }
                PulsanteTesto("Vedi tutte", azioni.vediTutte)
            }
        }

        SpesaDelMese(o.spesa.spesoEur, o.spesa.tettoEur, o.spesa.siAzzeraIl, o.spesa.avviso, s.voceGratis)
    }
}

@Composable
private fun StatoLezioneProssima(
    lezione: Lezione?,
    dettaglio: Lezione?,
    colore: String,
    materiaId: Long,
    eventoId: Long,
    azioni: AzioniOggi,
    senzaRete: Boolean,
    scaricate: Set<Long>,
) {
    val c = L.colori
    when {
        lezione == null || lezione.stato == "annullata" -> {
            PulsantePieno("Prepara la lezione", { azioni.prepara(materiaId, eventoId) }, icona = Icone.Piu, abilitato = !senzaRete)
            if (senzaRete) Text("Senza rete non si può preparare.", style = L.testi.secondario, color = c.tenue)
        }
        lezione.pronta -> {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Piastrella(colore)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Lezione pronta", style = L.testi.forte, color = c.testo)
                    Text(riassuntoLezione(lezione, dettaglio), style = L.testi.secondario, color = c.tenue)
                }
            }
            // Senza rete si ascoltano solo le lezioni scaricate (aspetto.md §10).
            val ascoltabile = !senzaRete || lezione.id in scaricate
            PulsantePieno("Ascolta", { azioni.ascolta(lezione.id) }, icona = Icone.Play, abilitato = ascoltabile)
            if (!ascoltabile) Text("Non è scaricata: si ascolta quando torna la rete.", style = L.testi.secondario, color = c.tenue)
        }
        lezione.inLavoro -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { azioni.preparazione(lezione.id) },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(Preparazione.frase(lezione), style = L.testi.forte, color = c.accentoTesto)
            Barra(Preparazione.frazione(lezione), altezza = 6.dp)
        }
        else -> {
            RigaErrore(lezione.errore ?: "La lezione non è riuscita.")
            PulsantePieno("Vedi cosa fare", { azioni.preparazione(lezione.id) })
        }
    }
}

/** L'avviso all'80% del tetto: la voce gratis si nomina solo se il server dice che c'è (punto 24). */
fun fraseAvvisoTetto(voceGratis: Boolean): String =
    if (voceGratis) "Sei vicino al tetto del mese: la voce gratis non consuma il tetto." else "Sei vicino al tetto del mese."

/** "20 min · 4 capitoli · 6 domande" (dal dettaglio se c'è). */
fun riassuntoLezione(l: Lezione, dettaglio: Lezione?): String {
    val d = dettaglio ?: l
    val minuti = (if (d.durataMin > 0) d.durataMin else Math.round(d.durataSecondi / 60).toInt()).coerceAtLeast(1)
    val parti = mutableListOf("$minuti min")
    if (d.capitoli.isNotEmpty()) parti += "${d.capitoli.size} capitoli"
    if (d.domande.isNotEmpty()) parti += "${d.domande.size} domande"
    return parti.joinToString(" · ")
}

/** "Questo mese 0,80 € di 2 €", barra sottile, "Si azzera il 1° novembre"; all'80% avviso tenue. */
@Composable
fun SpesaDelMese(speso: Double, tetto: Double, siAzzera: String?, avviso: Boolean, voceGratis: Boolean = false) {
    val c = L.colori
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (avviso) c.avvisoTenue else c.fondo)
            .padding(horizontal = if (avviso) 14.dp else 4.dp, vertical = if (avviso) 12.dp else 0.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Questo mese ${Formati.euro(speso)} di ${Formati.euro(tetto)}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Text("Questo mese", style = L.testi.secondario.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (avviso) c.avvisoTesto else c.testo)
            Spacer(Modifier.weight(1f))
            Text("${Formati.euro(speso)} di ${Formati.euro(tetto)}", style = L.testi.secondario.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (avviso) c.avvisoTesto else c.testo)
        }
        Barra(if (tetto > 0) (speso / tetto).toFloat() else 0f, altezza = 6.dp, colore = if (avviso) c.avvisoTesto else c.accento)
        if (avviso) Text(fraseAvvisoTetto(voceGratis), style = L.testi.secondario, color = c.avvisoTesto)
        Formati.siAzzera(siAzzera)?.let { Text(it, style = L.testi.secondario, color = if (avviso) c.avvisoTesto else c.moltoTenue) }
    }
}
