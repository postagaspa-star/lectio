package eu.stgm.lectio.ui.impostazioni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Impostazioni
import eu.stgm.lectio.dati.Salute
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.Spesa
import eu.stgm.lectio.dati.TemaScelto
import eu.stgm.lectio.dati.Voce
import eu.stgm.lectio.lettore.VELOCITA
import eu.stgm.lectio.logica.Costi
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.Interruttore
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PillolaScelta
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaElenco
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.Segmenti
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.Testata
import eu.stgm.lectio.ui.lezione.DURATE
import eu.stgm.lectio.ui.tema.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

data class StatoImpostazioni(
    val impostazioni: Impostazioni? = null,
    val voci: List<Voce> = emptyList(),
    val spesa: Spesa? = null,
    val salute: Salute? = null,
    val saluteErrore: String? = null,
    val errore: String? = null,
    val aggiornamento: String? = null,
    val versioneNuova: String? = null,
    val controllo: Boolean = false,
)

class ImpostazioniViewModel(
    private val sorgente: Sorgente,
    private val versioneNuova: suspend () -> String? = { null },
    private val aggiorna: suspend () -> String = { "" },
    /** Le copie locali che il lettore usa senza rete. */
    private val ricordaLocale: suspend (Impostazioni) -> Unit = {},
    /** La velocità predefinita scelta qui vale subito anche sul telefono (punto 20). */
    private val cambiaVelocitaLocale: suspend (Float) -> Unit = {},
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoImpostazioni())
    val stato: StateFlow<StatoImpostazioni> = _stato.asStateFlow()

    fun carica() {
        viewModelScope.launch {
            val imp = sorgente.impostazioni()
            val voci = sorgente.voci()
            val spesa = sorgente.spesa()
            val salute = sorgente.salute()
            imp.dato?.let { ricordaLocale(it) }
            _stato.update {
                it.copy(
                    impostazioni = imp.dato ?: it.impostazioni,
                    voci = (voci as? Esito.Ok)?.dato ?: it.voci,
                    spesa = spesa.dato ?: it.spesa,
                    salute = (salute as? Esito.Ok)?.dato,
                    saluteErrore = salute.messaggio,
                    errore = if (imp.dato == null) imp.errore else null,
                )
            }
            val nuova = versioneNuova()
            _stato.update { it.copy(versioneNuova = nuova) }
        }
    }

    fun cambia(cambi: JsonObject) {
        viewModelScope.launch {
            when (val e = sorgente.modificaImpostazioni(cambi)) {
                is Esito.Ok -> {
                    ricordaLocale(e.dato)
                    if (cambi.containsKey("velocita")) cambiaVelocitaLocale(e.dato.velocita.toFloat())
                    _stato.update { it.copy(impostazioni = e.dato, errore = null) }
                }
                else -> _stato.update { it.copy(errore = e.messaggio) }
            }
        }
    }

    fun aggiornaApp() {
        _stato.update { it.copy(controllo = true) }
        viewModelScope.launch {
            val frase = aggiorna()
            _stato.update { it.copy(controllo = false, aggiornamento = frase) }
        }
    }
}

data class AzioniImpostazioni(
    val indietro: () -> Unit = {},
    /** "Indirizzo da fuori casa" (https di Tailscale); torna la frase d'errore o null. */
    val cambiaFuoriCasa: (String) -> String? = { null },
    val tema: (TemaScelto) -> Unit = {},
    val menoAnimazioni: (Boolean) -> Unit = {},
    val visita: () -> Unit = {},
    val scollega: () -> Unit = {},
    val licenza: () -> String = { "" },
)

/** Impostazioni (aspetto.md §10). */
@Composable
fun ImpostazioniScreen(
    vm: ImpostazioniViewModel,
    azioni: AzioniImpostazioni,
    server: String?,
    fuoriCasa: String? = null,
    tema: TemaScelto,
    menoAnimazioni: Boolean,
    versione: String,
    senzaRete: Boolean,
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    var scollega by remember { mutableStateOf(false) }
    var modificaFuori by remember { mutableStateOf(false) }
    var licenza by remember { mutableStateOf(false) }
    val imp = s.impostazioni
    val attivo = imp != null && !senzaRete
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Testata(azioni.indietro)
        TitoloPagina("Impostazioni")
        s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }

        Sezione("Collegamento") {
            RigaValore("Server di casa", server ?: "Non collegato")
            Riga()
            eu.stgm.lectio.ui.RigaElenco("Indirizzo da fuori casa", fuoriCasa, onClick = { modificaFuori = true })
            Riga()
            val statoServer = when {
                s.salute?.modalitaProva == true -> "Modalità prova: " + (s.salute?.mancano?.joinToString(", ")?.let { "manca $it" } ?: "mancano le chiavi")
                s.salute?.ok == true -> "Collegato"
                else -> s.saluteErrore ?: "Controllo…"
            }
            RigaValore("Stato", statoServer)
            Riga()
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { PulsanteTesto("Scollega", { scollega = true }, colore = c.testo) }
        }

        Sezione("Lezioni") {
            Etichetta("Voce predefinita", Modifier.padding(top = 12.dp))
            val voci = Costi.voci(s.voci)
            val elenco = listOfNotNull(voci.espressiva, voci.gratis, voci.prova)
            if (elenco.isNotEmpty()) {
                Segmenti(elenco.map { it.nome }, elenco.indexOfFirst { it.id == imp?.vocePredefinita }.coerceAtLeast(0), { vm.cambia(buildJsonObject { put("voce_predefinita", elenco[it].id) }) }, Modifier.padding(vertical = 8.dp), abilitato = attivo)
            }
            Etichetta("Durata predefinita", Modifier.padding(top = 4.dp))
            Segmenti(DURATE.map { "$it min" }, DURATE.indexOf(imp?.durataPredefinita ?: 20).coerceAtLeast(0), { vm.cambia(buildJsonObject { put("durata_predefinita", DURATE[it]) }) }, Modifier.padding(vertical = 8.dp), abilitato = attivo)
            Etichetta("Domande predefinite", Modifier.padding(top = 4.dp))
            Segmenti(listOf("Obbligatorie", "Si possono saltare"), if (imp?.domandeSaltabili == true) 1 else 0, { vm.cambia(buildJsonObject { put("domande_saltabili", it == 1) }) }, Modifier.padding(top = 8.dp, bottom = 12.dp), abilitato = attivo)
        }

        Sezione("Ascolto") {
            Interruttore("Fermati alle domande", imp?.fermatiAlleDomande ?: true, attivo) { vm.cambia(buildJsonObject { put("fermati_alle_domande", it) }) }
            Riga()
            Interruttore("Risposte scritte", imp?.risposteScritte ?: true, attivo) { vm.cambia(buildJsonObject { put("risposte_scritte", it) }) }
            Riga()
            Etichetta("Velocità predefinita", Modifier.padding(top = 12.dp))
            Segmenti(
                VELOCITA.map { Formati.velocita(it) },
                VELOCITA.indexOfFirst { kotlin.math.abs((imp?.velocita ?: 1.0) - it) < 0.01 }.coerceAtLeast(0),
                { vm.cambia(buildJsonObject { put("velocita", VELOCITA[it].toDouble()) }) },
                Modifier.padding(top = 8.dp, bottom = 12.dp), abilitato = attivo,
            )
        }

        Sezione("Tetto del mese") {
            val tetto = imp?.tettoEur ?: 2.0
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Formati.euro(tetto), style = L.testi.titoloSezione, color = c.testo)
                    s.spesa?.let { Text("Questo mese hai speso ${Formati.euro(it.spesoEur)}", style = L.testi.secondario, color = c.tenue) }
                }
                PulsanteTondo(Icone.Meno, "Abbassa il tetto", { cambiaTetto(vm, tetto - 0.5) })
                Spacer(Modifier.padding(4.dp))
                PulsanteTondo(Icone.Piu, "Alza il tetto", { cambiaTetto(vm, tetto + 0.5) })
            }
        }

        Sezione("Aspetto") {
            Etichetta("Tema", Modifier.padding(top = 12.dp))
            Segmenti(listOf("Sistema", "Chiaro", "Scuro"), TemaScelto.entries.indexOf(tema), { azioni.tema(TemaScelto.entries[it]) }, Modifier.padding(vertical = 8.dp))
            Riga()
            Interruttore("Meno animazioni", menoAnimazioni, true, azioni.menoAnimazioni)
        }

        Sezione("Aiuto") {
            RigaElenco("Come funziona Lectio", "La visita guidata con una lezione d'esempio", onClick = azioni.visita)
        }

        Sezione("Aggiornamenti") {
            RigaValore("Versione", versione)
            Riga()
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.versioneNuova?.let { Text("C'è la versione $it", style = L.testi.forte, color = c.testo) }
                PulsanteSecondario(
                    if (s.controllo) "Controllo…" else if (s.versioneNuova != null) "Aggiorna" else "Controlla",
                    vm::aggiornaApp, Modifier.fillMaxWidth(), abilitato = !s.controllo && !senzaRete,
                )
                s.aggiornamento?.let { Text(it, style = L.testi.secondario, color = c.tenue) }
            }
        }

        Sezione("Informazioni") {
            RigaValore("Lectio", "Audio-lezioni tarate sulle verifiche del tuo prof")
            Riga()
            RigaElenco("Carattere Figtree", "Licenza SIL Open Font License 1.1", onClick = { licenza = true })
        }
    }
    if (modificaFuori) {
        var testo by remember { mutableStateOf(fuoriCasa ?: "") }
        var errore by remember { mutableStateOf<String?>(null) }
        Foglio(onChiudi = { modificaFuori = false }, titolo = "Indirizzo da fuori casa") {
            Campo(testo, { testo = it; errore = null }, segnaposto = "https://", tastiera = KeyboardOptions(keyboardType = KeyboardType.Uri), errore = errore != null)
            errore?.let { Text(it, style = L.testi.secondario, color = c.erroreTesto) }
            PulsantePieno("Salva", {
                errore = azioni.cambiaFuoriCasa(testo)
                if (errore == null) modificaFuori = false
            })
        }
    }
    if (scollega) {
        Conferma(
            "Scollegare il telefono?",
            "Le lezioni scaricate spariscono dal telefono. Per ricollegarlo serve un codice nuovo dal server di casa.",
            "Scollega",
            { scollega = false; azioni.scollega() },
            { scollega = false },
        )
    }
    if (licenza) {
        Foglio(onChiudi = { licenza = false }, titolo = "Figtree · SIL OFL 1.1") {
            Text(azioni.licenza(), style = L.testi.piccolo.copy(fontWeight = FontWeight.Normal), color = c.testo)
        }
    }
}

private fun cambiaTetto(vm: ImpostazioniViewModel, valore: Double) {
    vm.cambia(buildJsonObject { put("tetto_eur", tettoValido(valore)) })
}

@Composable
private fun Sezione(titolo: String, contenuto: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Etichetta(titolo)
        Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) { contenuto() }
    }
}

@Composable
private fun RigaValore(titolo: String, valore: String) {
    val c = L.colori
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(titolo, style = L.testi.forte, color = c.testo)
        Text(valore, style = L.testi.secondario, color = c.tenue)
    }
}

@Composable
private fun Interruttore(titolo: String, acceso: Boolean, abilitato: Boolean, onCambia: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titolo, style = L.testi.forte, color = L.colori.testo, modifier = Modifier.weight(1f))
        Interruttore(acceso, onCambia, abilitato, titolo)
    }
}

/** Per i test: il tetto arrotondato a 50 centesimi tra 0,50 e 10 €. */
fun tettoValido(valore: Double): Double = ((valore.coerceIn(0.5, 10.0)) * 2).roundToInt() / 2.0

/** "2,50 €" per le voci del tetto (Locale italiano). */
fun euroTetto(v: Double): String = String.format(Locale.ITALY, "%.2f €", v)
