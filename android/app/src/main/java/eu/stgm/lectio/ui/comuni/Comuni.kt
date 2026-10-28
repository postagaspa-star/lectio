package eu.stgm.lectio.ui.comuni

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.tema.L

/** Le pillole in alto: "Esempio" (visita), "Prova" (server senza chiavi), "Senza rete". */
@Composable
fun PilloleStato(esempio: Boolean, senzaRete: Boolean, prova: Boolean = false, modifier: Modifier = Modifier) {
    if (!esempio && !senzaRete && !prova) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (esempio) PillolaStato(TipoPillola.ESEMPIO)
        if (prova && !esempio) PillolaStato(TipoPillola.PROVA)
        if (senzaRete && !esempio) PillolaStato(TipoPillola.SENZA_RETE)
    }
}

/** La testata delle pagine sopra una scheda: indietro tondo, titolo, azioni a destra. */
@Composable
fun Testata(onIndietro: () -> Unit, modifier: Modifier = Modifier, icona: ImageVector = Icone.Indietro, descrizione: String = "Indietro", azioni: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        PulsanteTondo(icona, descrizione, onIndietro)
        Spacer(Modifier.weight(1f))
        azioni()
    }
}

private val ICONE = mapOf(
    eu.stgm.lectio.ui.Scheda.OGGI to Icone.Oggi,
    eu.stgm.lectio.ui.Scheda.MATERIE to Icone.Materie,
    eu.stgm.lectio.ui.Scheda.LEZIONI to Icone.Lezioni,
    eu.stgm.lectio.ui.Scheda.GEMINI to Icone.Gemini,
)

/**
 * La barra in basso a 4 voci (prototipo): fondo bianco con riga sopra, voce attiva
 * con la pillola accento tenue dietro l'icona e il testo nell'accento.
 */
@Composable
fun BarraSchede(scelta: eu.stgm.lectio.ui.Scheda, onScegli: (eu.stgm.lectio.ui.Scheda) -> Unit) {
    val c = L.colori
    Column(Modifier.fillMaxWidth().background(c.superficie)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(75.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            eu.stgm.lectio.ui.Scheda.entries.forEach { s ->
                val attiva = s == scelta
                val colore = if (attiva) c.accentoTesto else c.tenue
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple(bounded = false, radius = 40.dp)) { onScegli(s) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            selected = attiva
                            contentDescription = s.etichetta
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.width(56.dp).height(30.dp).clip(CircleShape).background(if (attiva) c.accentoTenue else c.superficie.copy(alpha = 0f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(ICONE.getValue(s), contentDescription = null, tint = colore, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        s.etichetta,
                        fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = if (attiva) FontWeight.Bold else FontWeight.SemiBold,
                        fontFamily = L.testi.etichetta.fontFamily,
                        color = colore, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Una sezione d'impostazioni o di dettaglio: titolo piccolo e scheda bianca. */
@Composable
fun SchedaConTitolo(titolo: String, contenuto: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        eu.stgm.lectio.ui.Etichetta(titolo)
        Scheda(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) { contenuto() }
    }
}
