package eu.stgm.lectio.ui.materie

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.logica.RigaNuovo
import eu.stgm.lectio.logica.TestiSchema
import eu.stgm.lectio.logica.VoceNuovo
import eu.stgm.lectio.ui.FoglioFermo
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio

/**
 * Il foglio "Cosa vuoi fare?" del pulsante Nuovo (aspetto.md §43.3): cinque righe, poi per
 * Riassunto il passo 2 "Da quale schema?" nello stesso foglio, con la freccia indietro.
 * [schemi]: quelli pronti, già in ordine (i più recenti prima).
 */
@Composable
internal fun FoglioNuovo(
    materia: Materia?,
    righe: List<RigaNuovo>,
    schemi: List<Schema>,
    passo2: Boolean,
    onPasso2: (Boolean) -> Unit,
    onScegli: (VoceNuovo) -> Unit,
    onSchema: (Schema) -> Unit,
    onChiudi: () -> Unit,
) {
    // Sotto i 400 dp la pillola toglierebbe spazio al titolo, che andrebbe a capo (§43.3):
    // la materia si vede già dietro il foglio.
    val largo = LocalConfiguration.current.screenWidthDp >= 400
    FoglioFermo(
        onChiudi = onChiudi,
        titolo = if (passo2) "Da quale schema?" else "Cosa vuoi fare?",
        accanto = if (passo2 || materia == null || !largo) null else {
            { PillolaMateria(materia.nome, materia.colore, Modifier.widthIn(max = 128.dp)) }
        },
        prima = if (!passo2) null else {
            { PulsanteTondo(Icone.Indietro, "Indietro", { onPasso2(false) }, dimensione = 48.dp, dimensioneIcona = 18.dp) }
        },
    ) {
        // Al passo 2 il tasto indietro di Android torna alle cinque righe.
        BackHandler(enabled = passo2) { onPasso2(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (passo2) {
                schemi.forEach { s ->
                    RigaScelta(Icone.Testo, materia?.colore, s.nome, TestiSchema.sottoRiga(s), accesa = true, onClick = { onSchema(s) })
                }
            } else {
                righe.forEach { r ->
                    RigaScelta(
                        iconaDi(r.voce), materia?.colore, r.voce.nome, r.frase, r.accesa,
                        onClick = { onScegli(r.voce) },
                        modifier = when (r.voce) {
                            VoceNuovo.LEZIONE -> Modifier.bersaglio(Bersagli.RIGA_LEZIONE)
                            VoceNuovo.SCHEMA -> Modifier.bersaglio(Bersagli.RIGA_SCHEMA)
                            else -> Modifier
                        },
                    )
                }
            }
        }
    }
}

private fun iconaDi(v: VoceNuovo): ImageVector = when (v) {
    VoceNuovo.LEZIONE -> Icone.Lezioni
    VoceNuovo.SCHEMA -> Icone.Testo
    VoceNuovo.RIASSUNTO -> Icone.Riassunto
    VoceNuovo.SIMULATA -> Icone.Verifica
    VoceNuovo.LAMPO -> Icone.Lampo
}

/**
 * Una riga del foglio: Superficie 2, raggio 16, alta almeno 72; piastrella 44 col fondo
 * tenue della materia, nome 17/700, frase 14/500 tenue, freccia a destra. Spenta: 0,55.
 */
@Composable
private fun RigaScelta(
    icona: ImageVector,
    colore: String?,
    titolo: String,
    frase: String,
    accesa: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = L.colori
    val m = materia(colore)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.superficie2)
            .clickable(enabled = accesa, role = Role.Button, onClick = onClick)
            .alpha(if (accesa) 1f else 0.55f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(m.tenue), contentAlignment = Alignment.Center) {
            Icon(icona, contentDescription = null, tint = m.testo, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titolo, style = L.testi.forteGrande.copy(fontWeight = FontWeight.Bold), color = c.testo, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (frase.isNotBlank()) Text(frase, style = L.testi.secondario, color = c.tenue)
        }
        Icon(Icone.Avanti, contentDescription = null, tint = c.tenue, modifier = Modifier.size(20.dp))
    }
}
