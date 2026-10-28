package eu.stgm.lectio.ui.abbinamento

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Abbinamento
import eu.stgm.lectio.dati.DepositoConfigurazione
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.IndirizzoServer
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.CampoConEtichetta
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.SimboloLectio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.tema.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatoAbbinamento(
    val indirizzo: String = "",
    val codice: String = "",
    val inCorso: Boolean = false,
    val errore: String? = null,
    val fatto: Boolean = false,
)

/** Le cifre del codice, senza spazi o trattini scritti per leggerlo meglio. */
fun soloCifre(testo: String): String = testo.filter { it.isDigit() }

/** Il nome del telefono che il server mostra nell'elenco dei dispositivi. */
fun nomeTelefono(modello: String? = Build.MODEL): String = "Telefono" + (modello?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")

class AbbinamentoViewModel(
    private val deposito: DepositoConfigurazione,
    private val abbina: suspend (String, String, String) -> Esito<Abbinamento> = { s, c, n -> LectioClient.abbina(s, c, n) },
    /** Prima di salvare: via i dati del telefono, ma solo se è un altro server. */
    private val prima: suspend (server: String, serverId: String?) -> Unit = { _, _ -> },
    private val nome: () -> String = ::nomeTelefono,
    /** L'identità del server (GET /api/salute senza token), letta subito dopo l'abbinamento. */
    private val serverId: suspend (String) -> String? = { (LectioClient.serverIdDi(it) as? Esito.Ok)?.dato },
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoAbbinamento())
    val stato: StateFlow<StatoAbbinamento> = _stato.asStateFlow()

    fun proponiIndirizzo(indirizzo: String?) {
        if (!indirizzo.isNullOrBlank()) _stato.update { it.copy(indirizzo = indirizzo) }
    }

    fun cambiaIndirizzo(t: String) = _stato.update { it.copy(indirizzo = t, errore = null) }
    fun cambiaCodice(t: String) = _stato.update { it.copy(codice = soloCifre(t).take(8), errore = null) }

    fun collega() {
        val s = _stato.value
        if (s.inCorso) return
        val server = when (val r = IndirizzoServer.normalizza(s.indirizzo)) {
            is IndirizzoServer.Risultato.Valido -> r.url
            is IndirizzoServer.Risultato.NonValido -> {
                _stato.update { it.copy(errore = r.motivo) }
                return
            }
        }
        if (s.codice.length != 8) {
            _stato.update { it.copy(errore = "Il codice ha 8 cifre: lo prepara il server di casa.") }
            return
        }
        _stato.update { it.copy(inCorso = true, errore = null) }
        viewModelScope.launch {
            when (val e = abbina(server, s.codice, nome())) {
                is Esito.Ok -> {
                    // Il codice l'ha appena accettato proprio questo server: la sua identità vale.
                    // v1.3: l'identità arriva con il token; un server più vecchio la dà solo in /api/salute.
                    val id = e.dato.serverId ?: serverId(server)
                    prima(server, id)
                    // L'indirizzo da fuori casa lo sceglie il server (contratto 15.5): nessuno scritto qui.
                    val fuori = IndirizzoServer.fuoriCasaDalServer(e.dato.indirizzoFuoriCasa)
                    deposito.salva(Configurazione(server, e.dato.token, e.dato.dispositivoId, id, fuori))
                    _stato.update { it.copy(inCorso = false, fatto = true) }
                }
                else -> _stato.update { it.copy(inCorso = false, errore = e.messaggio) }
            }
        }
    }
}

/** Primo avvio (aspetto.md §10): simbolo, indirizzo (vuoto, con un modello), codice di 8 cifre, Collega. */
@Composable
fun AbbinamentoScreen(vm: AbbinamentoViewModel, perso: Boolean, onAnnulla: (() -> Unit)? = null) {
    val s by vm.stato.collectAsStateWithLifecycle()
    val c = L.colori
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().imePadding().padding(start = MARGINE, end = MARGINE, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (onAnnulla != null) PulsanteTondo(Icone.Indietro, "Indietro", onAnnulla)
        SimboloLectio(56.dp)
        Text("Collega Lectio al server di casa", style = L.testi.titoloPagina, color = c.testo)
        if (perso) RigaAvviso("Il server non riconosce più questo telefono: serve un codice nuovo.")
        CampoConEtichetta("Indirizzo del server") {
            Campo(s.indirizzo, vm::cambiaIndirizzo, segnaposto = IndirizzoServer.ESEMPIO, tastiera = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        CampoConEtichetta("Codice di 8 cifre") {
            Campo(s.codice, vm::cambiaCodice, segnaposto = "12345678", tastiera = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        }
        s.errore?.let { RigaErrore(it) }
        PulsantePieno(if (s.inCorso) "Collego…" else "Collega", vm::collega, abilitato = !s.inCorso && s.codice.length == 8)
    }
}
