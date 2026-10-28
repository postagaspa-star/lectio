package eu.stgm.lectio.ui.visita

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.visita.Visita
import kotlin.math.roundToInt

/**
 * Il velo col riflettore, il fumetto e la mano (aspetto.md §12). Sta sopra a tutta
 * l'app: velo nero al 55% (65% al buio) con un buco arrotondato che scivola sul
 * bersaglio, anello accento che pulsa piano; il fumetto vicino al buco.
 */
@Composable
fun StratoVisita(visita: Visita) {
    if (!visita.attiva) return
    val c = L.colori
    val densita = LocalDensity.current
    val muove = LocalMovimenti.current
    val passo = visita.corrente
    val margine = with(densita) { 8.dp.toPx() }
    val bersaglio = visita.rettangolo()?.let { Rect(it.left - margine, it.top - margine, it.right + margine, it.bottom + margine) }
    val fine = visita.ultimo
    val veloAlfa by animateFloatAsState(if (fine) 0f else 1f, tween(if (muove) 350 else 0), label = "velo")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val larghezza = with(densita) { maxWidth.toPx() }
        val altezza = with(densita) { maxHeight.toPx() }
        val vuoto = Rect(larghezza / 2, altezza / 2, larghezza / 2, altezza / 2)
        val buco by animateRectAsState(bersaglio ?: vuoto, tween(if (muove) 320 else 0), label = "riflettore")
        val pulsa = rememberInfiniteTransition(label = "anello")
        val alfaAnello = if (muove) pulsa.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alfa").value else 1f

        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = veloAlfa }) {
            drawRect(c.veloVisita)
            if (bersaglio != null) {
                val r = 18.dp.toPx()
                drawRoundRect(Color.Transparent, Offset(buco.left, buco.top), Size(buco.width, buco.height), CornerRadius(r), blendMode = BlendMode.Clear)
                drawRoundRect(c.accento.copy(alpha = alfaAnello), Offset(buco.left, buco.top), Size(buco.width, buco.height), CornerRadius(r), style = Stroke(3.dp.toPx()))
            }
        }

        // Fuori dal buco i tocchi si fermano qui (e il fumetto fa un piccolo scatto).
        // Dentro passano all'elemento vero, ma solo nei passi pratici.
        val blocca = Modifier.pointerInput(visita.indice) { detectTapGestures { visita.scatto++ } }
        if (bersaglio == null || !passo.pratico) {
            Box(Modifier.fillMaxSize().then(blocca))
        } else {
            val b = buco
            with(densita) {
                Box(Modifier.offset(0.dp, 0.dp).size(maxWidth, b.top.coerceAtLeast(0f).toDp()).then(blocca))
                Box(Modifier.offset(0.dp, b.bottom.toDp()).size(maxWidth, (altezza - b.bottom).coerceAtLeast(0f).toDp()).then(blocca))
                Box(Modifier.offset(0.dp, b.top.toDp()).size(b.left.coerceAtLeast(0f).toDp(), b.height.toDp()).then(blocca))
                Box(Modifier.offset(b.right.toDp(), b.top.toDp()).size((larghezza - b.right).coerceAtLeast(0f).toDp(), b.height.toDp()).then(blocca))
            }
        }

        // La mano: un dito che tocca, finché non lo fai tu (anello vuoto spostato verso l'angolo).
        if (bersaglio != null && passo.pratico) {
            val premi = if (muove) pulsa.animateFloat(1f, 0.8f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "mano").value else 1f
            val lato = with(densita) { 40.dp.toPx() }
            val dx = minOf(buco.width * 0.32f, with(densita) { 110.dp.toPx() })
            val dy = minOf(buco.height * 0.18f, with(densita) { 20.dp.toPx() })
            Box(
                Modifier
                    .offset { IntOffset((buco.center.x + dx - lato / 2).roundToInt(), (buco.center.y + dy - lato / 2).roundToInt()) }
                    .size(40.dp)
                    .graphicsLayer { scaleX = premi; scaleY = premi }
                    .border(3.dp, c.accento.copy(alpha = 0.85f), CircleShape)
                    .padding(3.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            )
        }

        // In alto: "Salta la visita" (all'ultimo passo non serve più).
        if (!fine) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.height(44.dp).clip(CircleShape).background(c.superficie).clickable(role = Role.Button) { visita.salta() }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Salta la visita", style = L.testi.pillola, color = c.testo) }
            }
        }

        // Il fumetto: sotto il buco se c'è posto, altrimenti sopra; al centro senza buco.
        val scatto = remember { Animatable(0f) }
        LaunchedEffect(visita.scatto) {
            if (visita.scatto > 0 && muove) {
                scatto.animateTo(10f, tween(60)); scatto.animateTo(-8f, tween(80)); scatto.animateTo(0f, tween(80))
            }
        }
        var altezzaFumetto by remember { mutableIntStateOf(with(densita) { 170.dp.roundToPx() }) }
        val spazio = with(densita) { 14.dp.toPx() }
        val y = when {
            bersaglio == null -> (altezza - altezzaFumetto) / 2
            buco.bottom + altezzaFumetto + spazio * 2 < altezza -> buco.bottom + spazio
            else -> (buco.top - altezzaFumetto - spazio).coerceAtLeast(with(densita) { 64.dp.toPx() })
        }
        Column(
            Modifier
                .offset { IntOffset(scatto.value.roundToInt(), y.roundToInt()) }
                .onSizeChanged { altezzaFumetto = it.height }
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .shadow(if (fine) 16.dp else 0.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(c.superficie)
                .padding(18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!fine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillolaStato(TipoPillola.ESEMPIO)
                    Spacer(Modifier.size(8.dp))
                    Text(visita.conteggio, style = L.testi.piccolo, color = c.tenue)
                }
            }
            Text(passo.titolo, style = L.testi.titoloSezione, color = c.testo)
            Text(passo.frase, style = L.testi.testo, color = c.tenue)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (visita.puoTornare) PulsanteTesto("Indietro", { visita.indietro() }, colore = c.tenue)
                Spacer(Modifier.weight(1f))
                if (visita.mostraAvanti) {
                    Row(
                        Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.accento).clickable(role = Role.Button) { visita.avanti() }.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (fine) "Inizia" else "Avanti", style = L.testi.pulsantePiccolo, color = c.suAccento)
                        if (!fine) Icon(Icone.Avanti, contentDescription = null, tint = c.suAccento, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                    }
                }
            }
        }
    }
}
