package eu.stgm.lectio.ui.gemini

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TitoloPagina
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.tema.L
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GeminiViewModel(private val sorgente: Sorgente) : ViewModel() {
    private val _testo = MutableStateFlow<String?>(null)
    val testo: StateFlow<String?> = _testo.asStateFlow()

    /** Dal server, con la copia salvata per quando non c'è rete; al primo avvio senza rete quella del contratto. */
    fun carica() {
        viewModelScope.launch { _testo.value = sorgente.gemini().dato?.testo?.takeIf { it.isNotBlank() } ?: SorgenteEsempio.TESTO_GEMINI }
    }
}

/** Copia negli appunti; false se Android non lo permette. */
fun copia(context: Context, testo: String): Boolean = try {
    val appunti = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    appunti.setPrimaryClip(ClipData.newPlainText("Testo per Gemini", testo))
    true
} catch (e: Exception) {
    false
}

/** L'app Gemini se c'è, altrimenti gemini.google.com (aspetto.md §9). */
fun apriGemini(context: Context): Boolean {
    val app = context.packageManager.getLaunchIntentForPackage(PACCHETTO_GEMINI)
    val intento = app ?: Intent(Intent.ACTION_VIEW, Uri.parse(SITO_GEMINI))
    return try {
        context.startActivity(intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

const val PACCHETTO_GEMINI = "com.google.android.apps.bard"
const val SITO_GEMINI = "https://gemini.google.com/app"

/** Per Gemini (aspetto.md §9, prototipo Gemini.dc.html): tre passi grandi e il testo intero. */
@Composable
fun GeminiScreen(vm: GeminiViewModel, onScegliFile: () -> Unit, esempio: Boolean, senzaRete: Boolean, copiaTesto: (String) -> Boolean? = { null }) {
    val testo by vm.testo.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val c = L.colori
    val contesto = LocalContext.current
    var copiato by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf(false) }
    var gemini by remember { mutableStateOf(true) }
    LaunchedEffect(copiato) {
        if (copiato) {
            delay(2_000)
            copiato = false
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        PilloleStato(esempio, senzaRete)
        TitoloPagina("Per Gemini", Modifier.padding(top = 8.dp))
        Scheda(rilievo = true, padding = PaddingValues(18.dp), spazio = 18.dp) {
            Passo(1, "Copia il testo", pieno = true) {
                PulsantePieno(
                    if (copiato) "Copiato" else "Copia il testo per Gemini",
                    {
                        val t = testo ?: SorgenteEsempio.TESTO_GEMINI
                        val ok = copiaTesto(t) ?: copia(contesto, t)
                        copiato = ok
                        errore = !ok
                    },
                    icona = if (copiato) Icone.Spunta else null,
                    colore = if (copiato) c.fatto else c.accento,
                )
                if (errore) Text("Non sono riuscito a copiarlo: tieni premuto sul testo qui sotto.", style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.erroreTesto)
            }
            Passo(2, "Apri Gemini e manda la foto della verifica") {
                PulsanteSecondario("Apri Gemini", { gemini = apriGemini(contesto) }, Modifier.fillMaxWidth(), altezza = 50.dp, raggio = 16.dp, stile = L.testi.forte)
                if (!gemini) Text("Non riesco ad aprire Gemini: cercalo tra le app o apri gemini.google.com.", style = L.testi.secondario, color = c.erroreTesto)
            }
            Passo(3, "Condividi il file a Lectio") {
                PulsanteSecondario("Scegli il file", onScegliFile, Modifier.fillMaxWidth(), altezza = 50.dp, raggio = 16.dp, stile = L.testi.forte, abilitato = !senzaRete)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Etichetta("Il testo")
            Scheda(padding = PaddingValues(18.dp)) {
                SelectionContainer {
                    Text(testo ?: "", style = L.testi.testoMedio.copy(lineHeight = L.testi.testoMedio.fontSize * 1.55), color = c.testo)
                }
            }
        }
    }
}

@Composable
private fun Passo(n: Int, titolo: String, pieno: Boolean = false, contenuto: @Composable () -> Unit) {
    val c = L.colori
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (pieno) c.accento else c.accentoTenue),
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = L.testi.pulsantePiccolo.copy(fontWeight = FontWeight.ExtraBold), color = if (pieno) c.suAccento else c.accentoTesto)
            }
            Text(titolo, style = L.testi.forteGrande, color = c.testo)
        }
        contenuto()
    }
}
