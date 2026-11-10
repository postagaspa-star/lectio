package eu.stgm.lectio.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import eu.stgm.lectio.ui.tema.Colori
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.ui.tema.morbido

/** Dentro un foglio bianco campi e pillole usano la superficie 2 (prototipo Materie). */
val LocalDentroFoglio = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Margini laterali del telefono (§1: 20 px). */
val MARGINE = 20.dp

/** Il contenuto delle pagine con la barra in basso: 16 sopra, 20 ai lati. */
val PaddingPagina = PaddingValues(start = MARGINE, end = MARGINE, top = 16.dp, bottom = 24.dp)

// --- Ombre (solo al chiaro: al buio superfici più chiare, §1) ---------------------------------------

@Composable
fun Modifier.ombra(forma: Shape, rilievo: Boolean = false): Modifier =
    if (L.colori.scuro) {
        this
    } else if (rilievo) {
        shadow(12.dp, forma, clip = false, ambientColor = Color(0x22141420), spotColor = Color(0x33141420))
    } else {
        shadow(1.dp, forma, clip = false, ambientColor = Color(0x14141420), spotColor = Color(0x1F141420))
    }

/** `0 8px 20px` del colore del pulsante al 28%. */
@Composable
fun Modifier.ombraColore(forma: Shape, colore: Color, attiva: Boolean = true): Modifier =
    if (L.colori.scuro || !attiva) this else shadow(8.dp, forma, clip = false, ambientColor = colore.copy(alpha = 0.28f), spotColor = colore.copy(alpha = 0.45f))

/** Le icone della barra di stato: scure al chiaro, chiare al buio, anche col tema scelto nell'app. */
@Composable
fun IconeBarraDiStato(scure: Boolean) {
    val vista = LocalView.current
    DisposableEffect(scure) {
        val finestra = (vista.context as? Activity)?.window
        if (finestra != null && !vista.isInEditMode) {
            val controllo = WindowCompat.getInsetsController(finestra, vista)
            controllo.isAppearanceLightStatusBars = scure
            controllo.isAppearanceLightNavigationBars = scure
        }
        onDispose {}
    }
}

// --- Contenitori --------------------------------------------------------------------------------

/** La scheda bianca (raggio 22); [rilievo] = ombra lunga (la prossima, Per Gemini). */
@Composable
fun Scheda(
    modifier: Modifier = Modifier,
    rilievo: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    spazio: Dp = 12.dp,
    fondo: Color = L.colori.superficie,
    onClick: (() -> Unit)? = null,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val forma = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().ombra(forma, rilievo).clip(forma).background(fondo)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spazio),
        content = contenuto,
    )
}

/** "LA PROSSIMA": 12 / 700, maiuscolo, tenue. */
@Composable
fun Etichetta(testo: String, modifier: Modifier = Modifier, colore: Color = L.colori.tenue) {
    Text(testo.uppercase(), style = L.testi.etichetta, color = colore, modifier = modifier)
}

@Composable
fun TitoloPagina(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = L.testi.titoloPagina, color = L.colori.testo, modifier = modifier.semantics { heading() })
}

@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = L.testi.titoloSezione, color = L.colori.testo, modifier = modifier.semantics { heading() })
}

/** Una riga sottile tra le voci di un elenco dentro una scheda. */
@Composable
fun Riga() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(L.colori.riga))
}

@Composable
fun Testo(testo: String, stile: TextStyle, colore: Color = L.colori.testo, modifier: Modifier = Modifier, righe: Int = Int.MAX_VALUE) {
    Text(testo, style = stile, color = colore, modifier = modifier, maxLines = righe, overflow = TextOverflow.Ellipsis)
}

// --- Pulsanti -----------------------------------------------------------------------------------

/**
 * Il pulsante principale: alto 58, raggio 18, accento pieno, 17 / 700 bianco, con
 * l'ombra del suo colore (§1).
 */
@Composable
fun PulsantePieno(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    abilitato: Boolean = true,
    colore: Color = L.colori.accento,
    altezza: Dp = 58.dp,
    /** Il colore del testo; null = quello sull'accento. */
    coloreTesto: Color? = null,
    raggio: Dp = 18.dp,
) {
    val c = L.colori
    val forma = RoundedCornerShape(raggio)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = altezza)
            .ombraColore(forma, colore, abilitato && coloreTesto == null)
            .clip(forma)
            .background(if (abilitato) colore else c.superficie2)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val inchiostro = if (abilitato) coloreTesto ?: c.suAccento else c.moltoTenue
        if (icona != null) {
            Icon(icona, contentDescription = null, tint = inchiostro, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        // Mai troncato (aspetto.md §38): se non ci sta va a capo tra le parole.
        Text(testo, style = L.testi.pulsante, color = inchiostro, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Il pulsante secondario: accento tenue, testo accento (Racconta, Aggiungi, Apri Gemini). */
@Composable
fun PulsanteSecondario(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    altezza: Dp = 44.dp,
    raggio: Dp = 14.dp,
    stile: TextStyle = L.testi.pulsantePiccolo,
    abilitato: Boolean = true,
    icona: ImageVector? = null,
) {
    val c = L.colori
    val forma = RoundedCornerShape(raggio)
    Row(
        modifier
            .heightIn(min = altezza)
            .clip(forma)
            .background(c.accentoTenue)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .alpha(if (abilitato) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icona != null) {
            Icon(icona, contentDescription = null, tint = c.accentoTesto, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(testo, style = stile, color = c.accentoTesto, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Un pulsante di testo in accento ("Modifica", "Ripeti la domanda"), alto almeno 44. */
@Composable
fun PulsanteTesto(testo: String, onClick: () -> Unit, modifier: Modifier = Modifier, colore: Color = L.colori.accentoTesto, abilitato: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .alpha(if (abilitato) 1f else 0.45f)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = L.testi.pulsantePiccolo, color = colore)
    }
}

/** Il pulsante tondo grigio da 44 (indietro, chiudi, ingranaggio). */
@Composable
fun PulsanteTondo(
    icona: ImageVector,
    descrizione: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fondo: Color = L.colori.superficie2,
    tinta: Color = L.colori.testo,
    dimensione: Dp = 44.dp,
    dimensioneIcona: Dp = 20.dp,
) {
    Box(
        modifier
            .size(dimensione)
            .clip(CircleShape)
            .background(fondo)
            .clickable(role = Role.Button, onClickLabel = descrizione, onClick = onClick)
            .semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icona, contentDescription = null, tint = tinta, modifier = Modifier.size(dimensioneIcona))
    }
}

// --- Materie: pallini, pillole, piastrelle ----------------------------------------------------

@Composable
fun Pallino(colore: Color, dimensione: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(dimensione).clip(CircleShape).background(colore))
}

/** La pillola della materia: fondo tenue, pallino pieno, nome nel testo scuro della materia. */
@Composable
fun PillolaMateria(nome: String, colore: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val m = materia(colore)
    Row(
        modifier
            .clip(CircleShape)
            .background(m.tenue)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pallino(m.pieno, 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(nome, style = L.testi.pillola, color = m.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Il simbolo dentro le piastrelle: tre barrette di altezza diversa (pagine che diventano suono). */
@Composable
fun Barrette(colore: Color, larghezza: Dp = 3.dp, altezze: List<Dp> = listOf(10.dp, 18.dp, 13.dp), spazio: Dp = 3.dp, inBasso: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(spazio), verticalAlignment = if (inBasso) Alignment.Bottom else Alignment.CenterVertically) {
        altezze.forEach { h -> Box(Modifier.width(larghezza).height(h).clip(RoundedCornerShape(2.dp)).background(colore)) }
    }
}

/** La piastrella della lezione: quadrato tenue della materia con le barrette piene (44, raggio 14). */
@Composable
fun Piastrella(colore: String?, modifier: Modifier = Modifier, dimensione: Dp = 44.dp) {
    val m = materia(colore)
    Box(modifier.size(dimensione).clip(RoundedCornerShape(dimensione * 0.32f)).background(m.tenue), contentAlignment = Alignment.Center) {
        val scala = dimensione / 44.dp
        Barrette(m.pieno, larghezza = 3.dp * scala, altezze = listOf(10.dp * scala, 18.dp * scala, 13.dp * scala), spazio = 3.dp * scala)
    }
}

/** Il simbolo di Lectio: quadrato arrotondato accento con tre barrette bianche. */
@Composable
fun SimboloLectio(dimensione: Dp = 30.dp) {
    val scala = dimensione / 30.dp
    Box(
        Modifier.size(dimensione).clip(RoundedCornerShape(9.dp * scala)).background(Color(0xFF3D4ED8)),
        contentAlignment = Alignment.Center,
    ) {
        Barrette(Color.White, larghezza = 3.dp * scala, altezze = listOf(9.dp * scala, 16.dp * scala, 12.dp * scala), spazio = 3.dp * scala)
    }
}

/** Le barrette del capitolo in corso: si muovono piano, ferme con meno animazioni (§7, §13). */
@Composable
fun BarretteInCorso(colore: Color) {
    val muove = LocalMovimenti.current
    val altezze = if (muove) {
        val t = rememberInfiniteTransition(label = "barrette")
        listOf(
            t.animateFloat(8f, 14f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value,
            t.animateFloat(16f, 9f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "b").value,
            t.animateFloat(11f, 16f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "c").value,
        )
    } else {
        listOf(8f, 16f, 11f)
    }
    Box(Modifier.width(20.dp).height(16.dp), contentAlignment = Alignment.BottomCenter) {
        Barrette(colore, larghezza = 3.dp, altezze = altezze.map { it.dp }, spazio = 2.dp, inBasso = true)
    }
}

// --- Barre --------------------------------------------------------------------------------------

/** Una barra che si riempie (con animazione). */
@Composable
fun Barra(frazione: Float, modifier: Modifier = Modifier, altezza: Dp = 6.dp, colore: Color = L.colori.accento, binario: Color = L.colori.superficie2, durataMs: Int = 350) {
    val f by animateFloatAsState(frazione.coerceIn(0f, 1f), morbido(durataMs), label = "barra")
    Box(modifier.fillMaxWidth().height(altezza).clip(CircleShape).background(binario)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(CircleShape).background(colore))
    }
}

/** La barra a due toni del mese: speso e questa lezione (§6). */
@Composable
fun BarraDueToni(speso: Float, questa: Float, modifier: Modifier = Modifier, altezza: Dp = 8.dp) {
    val c = L.colori
    val a by animateFloatAsState(speso.coerceIn(0f, 1f), morbido(350), label = "speso")
    val b by animateFloatAsState(questa.coerceIn(0f, 1f - a), morbido(350), label = "questa")
    Row(modifier.fillMaxWidth().height(altezza).clip(CircleShape).background(c.superficie2)) {
        if (a > 0f) Box(Modifier.weight(a).fillMaxHeight().background(c.accento))
        if (b > 0f) Box(Modifier.weight(b).fillMaxHeight().background(c.accentoChiaro))
        if (1f - a - b > 0.001f) Spacer(Modifier.weight(1f - a - b))
    }
}

// --- Scelte -------------------------------------------------------------------------------------

/**
 * Il binario a segmenti (Durata, Tema): fondo superficie 2, il segmento scelto bianco
 * con una piccola ombra (prototipo NuovaLezione).
 */
@Composable
fun Segmenti(opzioni: List<String>, scelta: Int, onScegli: (Int) -> Unit, modifier: Modifier = Modifier, abilitato: Boolean = true) {
    val c = L.colori
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.superficie2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        opzioni.forEachIndexed { i, testo ->
            val attivo = i == scelta
            val forma = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(if (attivo && !c.scuro) Modifier.shadow(2.dp, forma, clip = false, ambientColor = Color(0x1F141420), spotColor = Color(0x1F141420)) else Modifier)
                    .clip(forma)
                    .background(if (attivo) (if (c.scuro) Color(0xFF3A3A43) else c.superficie) else Color.Transparent)
                    .clickable(enabled = abilitato, role = Role.RadioButton) { onScegli(i) }
                    .semantics { contentDescription = testo + if (attivo) ", scelto" else "" },
                contentAlignment = Alignment.Center,
            ) {
                Text(testo, style = L.testi.pulsantePiccolo, color = if (attivo) c.testo else c.tenue, maxLines = 1)
            }
        }
    }
}

/** Una pillola che si accende (materiale da usare, filtro delle lezioni, velocità). */
@Composable
fun PillolaScelta(testo: String, accesa: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icona: ImageVector? = null) {
    val c = L.colori
    val forma = CircleShape
    val dentro = LocalDentroFoglio.current
    Row(
        modifier
            .heightIn(min = 40.dp)
            .then(if (!accesa && !dentro) Modifier.ombra(forma) else Modifier)
            .clip(forma)
            .background(if (accesa) c.accentoTenue else if (dentro) c.superficie2 else c.superficie)
            .then(if (accesa) Modifier.border(1.5.dp, c.accento.copy(alpha = 0.5f), forma) else Modifier)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icona != null) {
            Icon(icona, contentDescription = null, tint = if (accesa) c.accentoTesto else c.tenue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(testo, style = L.testi.pulsantePiccolo, color = if (accesa) c.accentoTesto else c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Interruttore(acceso: Boolean, onCambia: (Boolean) -> Unit, abilitato: Boolean = true, descrizione: String? = null) {
    val c = L.colori
    Switch(
        checked = acceso,
        onCheckedChange = onCambia,
        enabled = abilitato,
        modifier = if (descrizione != null) Modifier.semantics { contentDescription = descrizione } else Modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.suAccento,
            checkedTrackColor = c.accento,
            uncheckedThumbColor = c.tenue,
            uncheckedTrackColor = c.superficie2,
            uncheckedBorderColor = c.moltoTenue,
        ),
    )
}

/** Un campo di testo: alto 52, raggio 14, superficie bianca, 16 / 500 (prototipo NuovaLezione). */
@Composable
fun Campo(
    valore: String,
    onCambia: (String) -> Unit,
    modifier: Modifier = Modifier,
    segnaposto: String? = null,
    righe: Int = 1,
    tastiera: KeyboardOptions = KeyboardOptions.Default,
    abilitato: Boolean = true,
    errore: Boolean = false,
) {
    val c = L.colori
    val forma = RoundedCornerShape(14.dp)
    val dentro = LocalDentroFoglio.current
    val fondo = if (dentro) c.superficie2 else c.superficie
    TextField(
        value = valore,
        onValueChange = onCambia,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (dentro) Modifier else Modifier.ombra(forma)).then(if (errore) Modifier.border(1.5.dp, c.errore, forma) else Modifier),
        enabled = abilitato,
        singleLine = righe == 1,
        minLines = righe,
        shape = forma,
        textStyle = L.testi.testo.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = c.testo),
        placeholder = segnaposto?.let { { Text(it, style = L.testi.testo, color = c.moltoTenue) } },
        keyboardOptions = tastiera,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fondo,
            unfocusedContainerColor = fondo,
            disabledContainerColor = fondo,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = c.accento,
            focusedTextColor = c.testo,
            unfocusedTextColor = c.testo,
        ),
    )
}

/** Un campo con l'etichetta sopra ("ARGOMENTO"). */
@Composable
fun CampoConEtichetta(etichetta: String, contenuto: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Etichetta(etichetta)
        contenuto()
    }
}

// --- Fogli e messaggi ---------------------------------------------------------------------------

/**
 * Chi ospita i fogli dal basso: stanno nella stessa finestra dell'app (non in una
 * finestra di sistema), così la visita guidata li illumina e ci si tocca dentro.
 */
class OspiteFogli {
    internal val voci = androidx.compose.runtime.mutableStateListOf<Pair<Any, @Composable () -> Unit>>()

    internal fun metti(chiave: Any, corpo: @Composable () -> Unit) {
        val i = voci.indexOfFirst { it.first === chiave }
        if (i >= 0) voci[i] = chiave to corpo else voci.add(chiave to corpo)
    }

    internal fun togli(chiave: Any) {
        voci.removeAll { it.first === chiave }
    }
}

val LocalOspiteFogli = androidx.compose.runtime.staticCompositionLocalOf<OspiteFogli?> { null }

/** Dove si disegnano i fogli: sopra a tutto, nella radice dell'app. */
@Composable
fun FogliOspitati(ospite: OspiteFogli) {
    ospite.voci.toList().forEach { (chiave, corpo) -> androidx.compose.runtime.key(chiave) { corpo() } }
}

/** Il foglio dal basso: superficie bianca, raggio 28, maniglia 40×5, fondo che si scurisce. */
@Composable
fun Foglio(onChiudi: () -> Unit, titolo: String? = null, contenuto: @Composable ColumnScope.() -> Unit) {
    val ospite = LocalOspiteFogli.current
    val chiave = androidx.compose.runtime.remember { Any() }
    val chiudi by androidx.compose.runtime.rememberUpdatedState(onChiudi)
    val corpo: @Composable () -> Unit = { CorpoFoglio(chiudi, titolo, contenuto) }
    if (ospite == null) {
        corpo()
        return
    }
    androidx.compose.runtime.SideEffect { ospite.metti(chiave, corpo) }
    DisposableEffect(chiave) { onDispose { ospite.togli(chiave) } }
}

@Composable
private fun CorpoFoglio(onChiudi: () -> Unit, titolo: String?, contenuto: @Composable ColumnScope.() -> Unit) {
    val c = L.colori
    val muove = LocalMovimenti.current
    val visibile = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(!muove).apply { targetState = true } }
    var trascinato by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val densita = androidx.compose.ui.platform.LocalDensity.current
    androidx.activity.compose.BackHandler(onBack = onChiudi)
    Box(Modifier.fillMaxSize()) {
        // Il fondo che si scurisce: un tocco fuori chiude.
        Box(
            Modifier.fillMaxSize().background(c.velo)
                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onChiudi),
        )
        AnimatedVisibility(
            visibleState = visibile,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(if (muove) 300 else 0)) { it } + fadeIn(tween(if (muove) 200 else 0)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationY = trascinato.coerceAtLeast(0f) }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.superficie)
                    .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                // La maniglia: trascinando giù il foglio si chiude.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .androidx_draggable(
                            onDelta = { trascinato += it },
                            onFine = {
                                if (trascinato > with(densita) { 120.dp.toPx() }) onChiudi()
                                trascinato = 0f
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(c.superficie2))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = MARGINE, end = MARGINE, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (titolo != null) {
                        Text(titolo, style = L.testi.titoloFoglio, color = c.testo, modifier = Modifier.semantics { heading() })
                    }
                    androidx.compose.runtime.CompositionLocalProvider(LocalDentroFoglio provides true) { contenuto() }
                }
            }
        }
    }
}

/** Trascinamento verticale semplice (la maniglia dei fogli). */
private fun Modifier.androidx_draggable(onDelta: (Float) -> Unit, onFine: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = onFine,
            onDragCancel = onFine,
        ) { _, delta -> onDelta(delta) }
    },
)

/**
 * Una conferma prima di un gesto che non si annulla (Elimina, Scollega). Colori
 * neutri: il rosso è solo per gli errori (aspetto.md §0.10).
 */
@Composable
fun Conferma(titolo: String, testo: String, conferma: String, onConferma: () -> Unit, onAnnulla: () -> Unit, pericolosa: Boolean = true, annulla: String = "Annulla") {
    Foglio(onChiudi = onAnnulla, titolo = titolo) {
        Text(testo, style = L.testi.testo, color = L.colori.tenue)
        Spacer(Modifier.height(4.dp))
        if (pericolosa) {
            PulsantePieno(conferma, onConferma, colore = L.colori.superficie2, coloreTesto = L.colori.testo)
        } else {
            PulsantePieno(conferma, onConferma)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulsanteTesto(annulla, onAnnulla, colore = L.colori.tenue)
        }
    }
}

/** Il messaggio breve in basso ("Copiato", "Lezione eliminata"). */
@Composable
fun Messaggio(dati: SnackbarData) {
    Snackbar(
        dati,
        shape = RoundedCornerShape(16.dp),
        containerColor = L.colori.testo,
        contentColor = L.colori.fondo,
        actionColor = L.colori.accentoChiaro,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Un errore con la frase del server e cosa fare (§10: mai codici). */
@Composable
fun RigaErrore(testo: String, modifier: Modifier = Modifier, onRiprova: (() -> Unit)? = null) {
    val c = L.colori
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.erroreTenue).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(testo, style = L.testi.secondario, color = c.erroreTesto, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (onRiprova != null) PulsanteTesto("Riprova", onRiprova, colore = c.erroreTesto)
    }
}

/**
 * La riga conferma (aspetto.md §42.4-42.5): un esito andato bene, calmo, che resta qualche
 * secondo ("Fatto: 3 file in Arte greca", "Cartella eliminata"). Spunta nel verde "fatto".
 */
@Composable
fun RigaConferma(testo: String, modifier: Modifier = Modifier) {
    val c = L.colori
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.fattoTenue)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(testo, style = L.testi.secondario.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.testo, modifier = Modifier.weight(1f))
    }
}

/** Un avviso calmo (80% del tetto, dati vecchi). */
@Composable
fun RigaAvviso(testo: String, modifier: Modifier = Modifier) {
    val c = L.colori
    Text(
        testo,
        style = L.testi.secondario,
        color = c.avvisoTesto,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.avvisoTenue).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Stato vuoto: una frase e un pulsante (§10). */
@Composable
fun StatoVuoto(frase: String, pulsante: String?, onClick: () -> Unit, modifier: Modifier = Modifier, icona: ImageVector? = null) {
    Scheda(modifier, padding = PaddingValues(20.dp), spazio = 16.dp) {
        Text(frase, style = L.testi.forteGrande, color = L.colori.testo)
        if (pulsante != null) PulsantePieno(pulsante, onClick, icona = icona)
    }
}

enum class TipoPillola { ESEMPIO, PROVA, SENZA_RETE, SENZA_RETE_BREVE }

/** Le pillole in alto: "Esempio" (visita), "Prova" (server senza chiavi), "Senza rete". */
@Composable
fun PillolaStato(tipo: TipoPillola, modifier: Modifier = Modifier) {
    val c = L.colori
    val (testo, fondo, colore) = when (tipo) {
        TipoPillola.ESEMPIO -> Triple("Esempio", c.avvisoTenue, c.avvisoTesto)
        TipoPillola.PROVA -> Triple("Prova", c.avvisoTenue, c.avvisoTesto)
        TipoPillola.SENZA_RETE -> Triple(eu.stgm.lectio.rete.Testi.SENZA_RETE, c.superficie2, c.testo)
        TipoPillola.SENZA_RETE_BREVE -> Triple(eu.stgm.lectio.rete.Testi.SENZA_RETE_BREVE, c.superficie2, c.testo)
    }
    Text(
        testo,
        style = L.testi.pillola,
        color = colore,
        modifier = modifier.clip(CircleShape).background(fondo).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Un segnaposto che pulsa piano (materiale in lettura, scheda in aggiornamento). */
@Composable
fun Segnaposto(modifier: Modifier = Modifier, altezza: Dp = 14.dp, larghezza: Float = 1f) {
    val muove = LocalMovimenti.current
    val alfa = if (muove) {
        rememberInfiniteTransition(label = "pulsa").animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alfa").value
    } else {
        0.7f
    }
    Box(modifier.fillMaxWidth(larghezza).height(altezza).graphicsLayer { this.alpha = alfa }.clip(RoundedCornerShape(7.dp)).background(L.colori.superficie2))
}

/**
 * La zona del pulsante fisso in basso (Nuova lezione, Prepara, Nuova materia): il
 * contenuto che scorre sotto sfuma nel fondo, così il pulsante non copre testo a metà.
 */
@Composable
fun ZonaPulsanteFisso(modifier: Modifier = Modifier, verticale: Dp = 14.dp, contenuto: @Composable () -> Unit) {
    val f = L.colori.fondo
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(20.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(f.copy(alpha = 0f), f))))
        Box(Modifier.fillMaxWidth().background(f).padding(start = MARGINE, end = MARGINE, top = 0.dp, bottom = verticale)) { contenuto() }
    }
}

/** Un elemento nuovo che entra dall'alto (§13). */
@Composable
fun Entra(visibile: Boolean, contenuto: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visibile,
        enter = if (LocalMovimenti.current) slideInVertically(tween(250)) { -it / 3 } + fadeIn(tween(250)) else fadeIn(tween(0)),
        exit = fadeOut(tween(if (LocalMovimenti.current) 150 else 0)),
    ) { contenuto() }
}

/** Area toccabile senza effetto (righe che hanno già i loro pulsanti). */
@Composable
fun Toccabile(onClick: () -> Unit, modifier: Modifier = Modifier, contenuto: @Composable BoxScope.() -> Unit) {
    Box(modifier.clickable(onClick = onClick), content = contenuto)
}

// --- Nuova lezione e materiale (v1.4, prototipi NuovaLezioneTel e MaterialePc) ------------------

/** Il bordo delle righe scelte (#A9B1F5 / scuro #3B4290) e il loro fondo (#F6F7FF / #1E2140). */
val Colori.bordoScelto: Color get() = if (scuro) Color(0xFF3B4290) else Color(0xFFA9B1F5)
val Colori.fondoScelto: Color get() = if (scuro) Color(0xFF1E2140) else Color(0xFFF6F7FF)

/** Il fondo delle schede spente o non scelte (#FAFAF8) e il loro bordo tratteggiato (#DCDAD3). */
val Colori.fondoSpento: Color get() = if (scuro) Color(0xFF17171B) else Color(0xFFFAFAF8)
val Colori.bordoSpento: Color get() = if (scuro) Color(0xFF3A3A42) else Color(0xFFDCDAD3)

/**
 * La pillola da scegliere dei prototipi v1.4: alta 38, 14 / 700; accesa = bordo 2 accento,
 * fondo accento tenue, testo accento; spenta = fondo come la pagina. Il tocco vale 48 dp
 * in altezza anche se la pillola ne disegna 38.
 */
@Composable
fun PillolaOpzione(
    testo: String,
    accesa: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    abilitato: Boolean = true,
    ruolo: Role = Role.RadioButton,
    /** Un pezzo che non si taglia mai dopo il testo (" · 8"): se non ci sta, i puntini vanno sul testo. */
    dopo: String? = null,
) {
    val c = L.colori
    val interazione = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val fondo by animateColorAsState(if (accesa) c.accentoTenue else c.fondo, morbido(200), label = "fondo")
    val bordo by animateColorAsState(if (accesa) c.accento else Color.Transparent, morbido(200), label = "bordo")
    Box(
        modifier
            .heightIn(min = 48.dp)
            .androidx_selezionabile(accesa, interazione, abilitato, ruolo, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(fondo)
                .border(2.dp, bordo, CircleShape)
                .androidx_increspatura(interazione)
                .alpha(if (abilitato) 1f else 0.45f)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icona != null) {
                Icon(icona, contentDescription = null, tint = if (accesa) c.accentoTesto else c.tenue, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            val colore = if (accesa) c.accentoTesto else c.testo
            if (dopo == null) {
                Text(testo, style = L.testi.pillola, color = colore, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(testo, style = L.testi.pillola, color = colore, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(dopo, style = L.testi.pillola, color = colore, maxLines = 1)
            }
        }
    }
}

private fun Modifier.androidx_selezionabile(
    scelto: Boolean,
    interazione: androidx.compose.foundation.interaction.MutableInteractionSource,
    abilitato: Boolean,
    ruolo: Role,
    onClick: () -> Unit,
): Modifier = if (ruolo == Role.Checkbox) {
    this.toggleable(scelto, interazione, null, abilitato, ruolo) { onClick() }
} else {
    this.selectable(scelto, interazione, null, abilitato, ruolo, onClick)
}

/** L'increspatura del tocco solo sulla parte disegnata (la zona toccabile è più grande). */
@Composable
private fun Modifier.androidx_increspatura(interazione: androidx.compose.foundation.interaction.MutableInteractionSource): Modifier =
    this.indication(interazione, ripple())

/**
 * Il binario a segmenti dei prototipi v1.4 (tipo, durata, domande): fondo superficie 2,
 * raggio 14, il segmento scelto bianco con ombra leggera, 14 / 700. Ogni segmento è
 * toccabile per tutta l'altezza del binario. [pesi]: colonne di larghezza diversa.
 * Con i caratteri di sistema ingranditi i segmenti vanno in colonna (aspetto.md §38).
 */
@Composable
fun Binario(
    opzioni: List<String>,
    scelta: Int,
    onScegli: (Int) -> Unit,
    modifier: Modifier = Modifier,
    abilitato: Boolean = true,
    pesi: List<Float>? = null,
    altezza: Dp = 50.dp,
    modificatoreSegmento: (Int) -> Modifier = { Modifier },
) {
    val c = L.colori
    val ingrandito = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val segmento: @Composable (Int, Modifier) -> Unit = { i, m ->
        val attivo = i == scelta
        val forma = RoundedCornerShape(11.dp)
        val interazione = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            m
                .heightIn(min = altezza)
                .then(modificatoreSegmento(i))
                .selectable(attivo, interazione, null, abilitato, Role.RadioButton) { onScegli(i) }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .then(if (attivo && !c.scuro) Modifier.shadow(2.dp, forma, clip = false, ambientColor = Color(0x1F141420), spotColor = Color(0x1F141420)) else Modifier)
                    .clip(forma)
                    .background(if (attivo) (if (c.scuro) Color(0xFF3A3A43) else c.superficie) else Color.Transparent)
                    .androidx_increspatura(interazione),
            )
            Text(
                opzioni[i],
                style = L.testi.pillola,
                color = if (attivo) c.testo else c.tenue,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
    val fondo = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.superficie2)
    if (ingrandito) {
        Column(fondo.padding(vertical = 2.dp)) { opzioni.indices.forEach { i -> segmento(i, Modifier.fillMaxWidth()) } }
    } else {
        Row(fondo.padding(horizontal = 2.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            opzioni.indices.forEach { i -> segmento(i, Modifier.weight(pesi?.getOrNull(i) ?: 1f)) }
        }
    }
}

/**
 * Il campo con il bordo dei prototipi v1.4: bordo 2 `#ECEAE4` (accento quando si scrive),
 * raggio 14, fondo superficie. [righe] > 1 = area di testo.
 */
@Composable
fun CampoBordato(
    valore: String,
    onCambia: (String) -> Unit,
    modifier: Modifier = Modifier,
    segnaposto: String? = null,
    righe: Int = 1,
    stile: TextStyle = L.testi.testo.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    altezza: Dp = 52.dp,
    tastiera: KeyboardOptions = KeyboardOptions.Default,
    descrizione: String? = null,
    fondo: Color = L.colori.superficie,
    bordoSpento: Color = L.colori.superficie2,
    abilitato: Boolean = true,
    /** Il tasto Invio della tastiera (per esempio = il pulsante del foglio). */
    azioniTastiera: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    /** Il bordo resta di questo colore anche quando si scrive (un errore sotto il campo). */
    bordoFisso: Color? = null,
) {
    val c = L.colori
    val forma = RoundedCornerShape(14.dp)
    val interazione = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val acceso by interazione.collectIsFocusedAsState()
    val bordo by animateColorAsState(bordoFisso ?: if (acceso) c.accento else bordoSpento, morbido(200), label = "bordo")
    androidx.compose.foundation.text.BasicTextField(
        value = valore,
        onValueChange = onCambia,
        modifier = modifier
            .fillMaxWidth()
            .then(if (righe == 1) Modifier.height(altezza) else Modifier)
            .then(if (descrizione != null) Modifier.semantics { contentDescription = descrizione } else Modifier),
        enabled = abilitato,
        singleLine = righe == 1,
        minLines = righe,
        textStyle = stile.copy(color = c.testo),
        keyboardOptions = tastiera,
        keyboardActions = azioniTastiera,
        interactionSource = interazione,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accento),
        decorationBox = { campo ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (righe == 1) Modifier.fillMaxHeight() else Modifier)
                    .clip(forma)
                    .background(fondo)
                    .border(2.dp, bordo, forma)
                    .padding(horizontal = 14.dp, vertical = if (righe == 1) 0.dp else 12.dp),
                contentAlignment = if (righe == 1) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (valore.isEmpty() && segnaposto != null) {
                    Text(segnaposto, style = stile.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.moltoTenue, maxLines = righe, overflow = TextOverflow.Ellipsis)
                }
                campo()
            }
        },
    )
}

/** Il tondo col numero della sezione (28 dp, accento tenue, 13 / 800). */
@Composable
fun NumeroSezione(n: Int) {
    val c = L.colori
    Box(Modifier.size(28.dp).clip(CircleShape).background(c.accentoTenue), contentAlignment = Alignment.Center) {
        Text(n.toString(), style = L.testi.piccolo.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = c.accentoTesto)
    }
}

/**
 * Una sezione numerata (prototipo NuovaLezioneTel): scheda bianca raggio 20, padding 16,
 * in testa il numero e il titolo 18 / 800, a destra [destra] se c'è.
 */
@Composable
fun SezioneNumerata(
    n: Int,
    titolo: String,
    modifier: Modifier = Modifier,
    destra: (@Composable () -> Unit)? = null,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val c = L.colori
    val forma = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(forma).background(c.superficie).padding(16.dp).animateContentSize(morbido()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumeroSezione(n)
            Text(
                titolo,
                style = L.testi.titoloSezione.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = c.testo,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            destra?.invoke()
        }
        contenuto()
    }
}

/** Una riga di elenco dentro una scheda: icona o pallino, titolo, sotto, a destra. */
@Composable
fun RigaElenco(
    titolo: String,
    sotto: String?,
    modifier: Modifier = Modifier,
    inizio: (@Composable () -> Unit)? = null,
    fine: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    coloreTitolo: Color = L.colori.testo,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        inizio?.invoke()
        Column(Modifier.weight(1f)) {
            Text(titolo, style = L.testi.forte, color = coloreTitolo, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sotto.isNullOrBlank()) Text(sotto, style = L.testi.secondario, color = L.colori.tenue, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        fine?.invoke(this)
    }
}

// --- Schemi, simulate, ripasso (v1.5, aspetto.md §30-41) ------------------------------------------

/** Fondo blocco dentro una scheda bianca (§30.2). */
val Colori.fondoBlocco: Color get() = if (scuro) Color(0xFF26262C) else Color(0xFFF5F4F0)
val Colori.ardesiaTenue: Color get() = if (scuro) Color(0xFF2A2D31) else Color(0xFFEEF0F2)
val Colori.ardesiaTesto: Color get() = if (scuro) Color(0xFFC9CED4) else Color(0xFF343A40)
val Colori.rispostaFondo: Color get() = if (scuro) Color(0xFF1F3327) else Color(0xFFEAF6EC)
val Colori.rispostaTesto: Color get() = if (scuro) Color(0xFFC9F0D2) else Color(0xFF1F4D2A)

/** Qualcosa da disegnare sopra a tutta l'app (la foto a tutto schermo). */
@Composable
fun SopraTutto(contenuto: @Composable () -> Unit) {
    val ospite = LocalOspiteFogli.current
    val chiave = androidx.compose.runtime.remember { Any() }
    if (ospite == null) {
        contenuto()
        return
    }
    val attuale by androidx.compose.runtime.rememberUpdatedState(contenuto)
    val corpo: @Composable () -> Unit = { attuale() }
    androidx.compose.runtime.SideEffect { ospite.metti(chiave, corpo) }
    DisposableEffect(chiave) { onDispose { ospite.togli(chiave) } }
}

/**
 * Il foglio con la X sempre visibile e il piede fermo in basso (§30.1, §34): maniglia,
 * titolo 21 / 800, X 48; scorre solo il corpo; sale al massimo all'88% dello schermo.
 */
@Composable
fun FoglioFermo(
    onChiudi: () -> Unit,
    titolo: String,
    scorrimento: androidx.compose.foundation.ScrollState? = null,
    piede: (@Composable ColumnScope.() -> Unit)? = null,
    /** Quanto sale al massimo, in frazione dello schermo ("Metti dei file qui": 0,9). */
    altezzaMassima: Float = 0.88f,
    /** Qualcosa accanto al titolo, prima della X ("3 scelti"). */
    accanto: (@Composable () -> Unit)? = null,
    /** Qualcosa prima del titolo, in alto a sinistra (la freccia indietro di un secondo passo). */
    prima: (@Composable () -> Unit)? = null,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val chiudi by androidx.compose.runtime.rememberUpdatedState(onChiudi)
    SopraTutto { CorpoFoglioFermo(chiudi, titolo, scorrimento, piede, altezzaMassima, accanto, prima, contenuto) }
}

@Composable
private fun CorpoFoglioFermo(
    onChiudi: () -> Unit,
    titolo: String,
    scorrimento: androidx.compose.foundation.ScrollState?,
    piede: (@Composable ColumnScope.() -> Unit)?,
    altezzaMassima: Float,
    accanto: (@Composable () -> Unit)?,
    prima: (@Composable () -> Unit)?,
    contenuto: @Composable ColumnScope.() -> Unit,
) {
    val c = L.colori
    val muove = LocalMovimenti.current
    val visibile = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(!muove).apply { targetState = true } }
    var trascinato by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val densita = androidx.compose.ui.platform.LocalDensity.current
    val scorre = scorrimento ?: rememberScrollState()
    androidx.activity.compose.BackHandler(onBack = onChiudi)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val massimo = maxHeight * altezzaMassima
        Box(
            Modifier.fillMaxSize().background(c.velo)
                .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onChiudi),
        )
        AnimatedVisibility(
            visibleState = visibile,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(if (muove) 300 else 0)) { it } + fadeIn(tween(if (muove) 200 else 0)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = massimo)
                    .graphicsLayer { translationY = trascinato.coerceAtLeast(0f) }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.superficie)
                    .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                    .animateContentSize(morbido(250))
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(20.dp).androidx_draggable(
                        onDelta = { trascinato += it },
                        onFine = {
                            if (trascinato > with(densita) { 120.dp.toPx() }) onChiudi()
                            trascinato = 0f
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(40.dp).height(5.dp).clip(CircleShape).background(c.superficie2))
                }
                Row(Modifier.fillMaxWidth().padding(start = if (prima != null) 12.dp else MARGINE, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (prima != null) {
                        prima()
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(titolo, style = L.testi.titoloFoglio, color = c.testo, modifier = Modifier.weight(1f).semantics { heading() })
                    if (accanto != null) {
                        Spacer(Modifier.width(8.dp))
                        accanto()
                        Spacer(Modifier.width(8.dp))
                    }
                    PulsanteTondo(Icone.Chiudi, "Chiudi", onChiudi, dimensione = 48.dp, dimensioneIcona = 18.dp)
                }
                androidx.compose.runtime.CompositionLocalProvider(LocalDentroFoglio provides true) {
                    Column(
                        Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scorre)
                            .padding(start = MARGINE, end = MARGINE, bottom = if (piede == null) 24.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = contenuto,
                    )
                    if (piede != null) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = MARGINE, end = MARGINE, top = 4.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            content = piede,
                        )
                    }
                }
            }
        }
    }
}

/** Una voce del menu a puntini. */
data class VoceMenu(val testo: String, val abilitata: Boolean = true, val azione: () -> Unit)

/** Il tondo coi puntini che apre un menu (Scarica PDF, Elimina...). */
@Composable
fun MenuPuntini(
    voci: List<VoceMenu>,
    modifier: Modifier = Modifier,
    descrizione: String = "Altre azioni",
    fondo: Color = L.colori.superficie2,
    tinta: Color = L.colori.testo,
    dimensione: Dp = 48.dp,
) {
    if (voci.isEmpty()) return
    var aperto by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        PulsanteTondo(Icone.AltreAzioni, descrizione, { aperto = true }, fondo = fondo, tinta = tinta, dimensione = dimensione)
        androidx.compose.material3.DropdownMenu(expanded = aperto, onDismissRequest = { aperto = false }) {
            voci.forEach { v ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(v.testo, style = L.testi.testoMedio.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (v.abilitata) L.colori.testo else L.colori.moltoTenue) },
                    enabled = v.abilitata,
                    onClick = {
                        aperto = false
                        v.azione()
                    },
                )
            }
        }
    }
}

/** Pillola con icona (Ascolta questa parte, Non ho capito...): superficie 2, 14 / 700. */
@Composable
fun PillolaIcona(
    testo: String,
    icona: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fondo: Color = L.colori.superficie2,
    colore: Color = L.colori.testo,
    altezza: Dp = 48.dp,
    abilitato: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = altezza)
            .clip(CircleShape)
            .background(fondo)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .alpha(if (abilitato) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icona, contentDescription = null, tint = colore, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(testo, style = L.testi.pillola, color = colore, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** La piastrella con un'icona (righe degli schemi e delle simulate). */
@Composable
fun PiastrellaIcona(icona: ImageVector, colore: String?, modifier: Modifier = Modifier, dimensione: Dp = 44.dp) {
    val m = materia(colore)
    Box(modifier.size(dimensione).clip(RoundedCornerShape(dimensione * 0.32f)).background(m.tenue), contentAlignment = Alignment.Center) {
        Icon(icona, contentDescription = null, tint = m.pieno, modifier = Modifier.size(dimensione * 0.5f))
    }
}
