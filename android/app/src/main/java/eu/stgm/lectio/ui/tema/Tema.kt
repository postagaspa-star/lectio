package eu.stgm.lectio.ui.tema

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.stgm.lectio.R

/**
 * I colori di Lectio (docs/aspetto.md §1 e il prototipo in docs/prototipo/): 60%
 * fondo neutro, 30% schede bianche, 10% accento inchiostro sulle azioni principali.
 */
@Immutable
data class Colori(
    val fondo: Color,
    val superficie: Color,
    val superficie2: Color,
    val testo: Color,
    val tenue: Color,
    val moltoTenue: Color,
    val riga: Color,
    val accento: Color,
    val suAccento: Color,
    val accentoTenue: Color,
    val accentoTesto: Color,
    /** La parte "questa lezione" della barra del mese (#A9B1F5 nel prototipo). */
    val accentoChiaro: Color,
    val fatto: Color,
    val fattoTenue: Color,
    val fattoTesto: Color,
    val avvisoTenue: Color,
    val avvisoTesto: Color,
    val errore: Color,
    val erroreTenue: Color,
    val erroreTesto: Color,
    /** Il fondo scuro sotto i fogli (rgba(15,15,25,.45)). */
    val velo: Color,
    /** Il velo della visita guidata (55% al chiaro, 65% al buio). */
    val veloVisita: Color,
    val scuro: Boolean,
)

private val Chiaro = Colori(
    fondo = Color(0xFFF5F4F0),
    superficie = Color(0xFFFFFFFF),
    superficie2 = Color(0xFFECEAE4),
    testo = Color(0xFF1A1A1F),
    tenue = Color(0xFF5E5E68),
    moltoTenue = Color(0xFF74747D),
    riga = Color(0xFFECEBE6),
    accento = Color(0xFF3D4ED8),
    suAccento = Color(0xFFFFFFFF),
    accentoTenue = Color(0xFFECEEFE),
    accentoTesto = Color(0xFF2F3CB0),
    accentoChiaro = Color(0xFFA9B1F5),
    fatto = Color(0xFF2B8A3E),
    fattoTenue = Color(0xFFEAF6EC),
    fattoTesto = Color(0xFF1F4D2A),
    avvisoTenue = Color(0xFFFFF4E0),
    avvisoTesto = Color(0xFF8A5506),
    errore = Color(0xFFC92A2A),
    erroreTenue = Color(0xFFFDEEEC),
    erroreTesto = Color(0xFF9B1C1C),
    velo = Color(0x730F0F19),
    veloVisita = Color(0x8C000000),
    scuro = false,
)

private val Scuro = Colori(
    fondo = Color(0xFF111114),
    superficie = Color(0xFF1B1B20),
    superficie2 = Color(0xFF26262C),
    testo = Color(0xFFF1F1F4),
    tenue = Color(0xFFA3A3AE),
    moltoTenue = Color(0xFF8C8C96),
    riga = Color(0xFF2B2B31),
    accento = Color(0xFF6F7CF2),
    suAccento = Color(0xFF0E0F2A),
    accentoTenue = Color(0xFF23264A),
    accentoTesto = Color(0xFFB4BBFF),
    accentoChiaro = Color(0xFF3B4385),
    fatto = Color(0xFF51CF66),
    fattoTenue = Color(0xFF1C3322),
    fattoTesto = Color(0xFFB2F2BB),
    avvisoTenue = Color(0xFF3A2C14),
    avvisoTesto = Color(0xFFFFC56B),
    errore = Color(0xFFE03131),
    erroreTenue = Color(0xFF3A1C1C),
    erroreTesto = Color(0xFFFFA8A8),
    velo = Color(0x99000000),
    veloVisita = Color(0xA6000000),
    scuro = true,
)

/** I colori di una materia (§1, "Colori delle materie"): pieno, tenue, testo, e il titolo scurissimo della piastrella. */
@Immutable
data class ColoreMateria(val pieno: Color, val tenue: Color, val testo: Color, val titolo: Color) {
    /** Le tinte della barra dei tipi di domanda: dal pieno verso il fondo. */
    fun sfumatura(i: Int, superficie: Color): Color = when (i) {
        0 -> pieno
        1 -> lerp(pieno, superficie, 0.42f)
        2 -> lerp(pieno, superficie, 0.70f)
        else -> lerp(pieno, superficie, 0.85f)
    }
}

/** Gli 8 colori del contratto (§4), nell'ordine del foglio "Aggiungi materia". */
val NOMI_COLORI = listOf("ambra", "rosa", "petrolio", "viola", "blu", "verde", "arancio", "oliva")

private val TAVOLOZZA = mapOf(
    "ambra" to Triple(0xFFC27C0E, 0xFFFBF1DE, 0xFF8A5506),
    "rosa" to Triple(0xFFD6336C, 0xFFFCE8EF, 0xFFA61E4D),
    "petrolio" to Triple(0xFF0C8599, 0xFFE3F6F8, 0xFF0B6474),
    "viola" to Triple(0xFF7048E8, 0xFFF1ECFE, 0xFF5532C9),
    "blu" to Triple(0xFF1C7ED6, 0xFFE7F1FC, 0xFF1864AB),
    "verde" to Triple(0xFF2B8A3E, 0xFFEAF6EC, 0xFF1F6A30),
    "arancio" to Triple(0xFFE8590C, 0xFFFDEDE3, 0xFFB4440A),
    "oliva" to Triple(0xFF5C940D, 0xFFEFF6E3, 0xFF466F0B),
)

/**
 * Nel tema scuro il tenue è il pieno al 22% sulla superficie scura e il testo è
 * una tinta chiara (§1). Un nome sconosciuto diventa ambra.
 */
fun coloreMateria(nome: String?, scuro: Boolean): ColoreMateria {
    val (p, t, x) = TAVOLOZZA[nome] ?: TAVOLOZZA.getValue("ambra")
    val pieno = Color(p)
    return if (!scuro) {
        ColoreMateria(pieno, Color(t), Color(x), lerp(Color(x), Color.Black, 0.57f))
    } else {
        val superficie = Color(0xFF1B1B20)
        ColoreMateria(lerp(pieno, Color.White, 0.08f), lerp(superficie, pieno, 0.22f), lerp(pieno, Color.White, 0.55f), lerp(pieno, Color.White, 0.82f))
    }
}

/** Gli stili di testo della guida (§1), in Figtree. */
@Immutable
data class Testi(
    /** 30 / 800, -0,6: "Oggi", "Storia". */
    val titoloPagina: TextStyle,
    /** 24 / 800, -0,4: "Verifica sull'Impero romano". */
    val titoloScheda: TextStyle,
    /** 22 / 800, interlinea 1,3: la domanda nel lettore. */
    val domanda: TextStyle,
    /** 19 / 800: "Cosa chiede il prof". */
    val titoloSezione: TextStyle,
    /** 21 / 800: titolo dei fogli. */
    val titoloFoglio: TextStyle,
    /** 17 / 800: nomi nelle schede delle voci, passi di Per Gemini. */
    val forteGrande: TextStyle,
    /** 16 / 700: titolo di una lezione in elenco. */
    val forte: TextStyle,
    /** 16 / 400, interlinea 1,5: testo della lezione, scheda del prof. */
    val testo: TextStyle,
    /** 15 / 400, interlinea 1,5: elenchi della scheda, testo per Gemini. */
    val testoMedio: TextStyle,
    /** 14 / 500: "giovedì 30 ottobre · tra 2 giorni". */
    val secondario: TextStyle,
    /** 13 / 600: legende, numeri piccoli. */
    val piccolo: TextStyle,
    /** 12 / 700 maiuscolo, 0,08em: "LA PROSSIMA". */
    val etichetta: TextStyle,
    /** 13 / 600, cifre tabellari: "07:42". */
    val numeri: TextStyle,
    /** 17 / 700: pulsanti pieni. */
    val pulsante: TextStyle,
    /** 15 / 700: pulsanti secondari e di testo. */
    val pulsantePiccolo: TextStyle,
    /** 14 / 700: pillole. */
    val pillola: TextStyle,
)

private fun testi(f: FontFamily) = Testi(
    titoloPagina = TextStyle(fontFamily = f, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
    titoloScheda = TextStyle(fontFamily = f, fontSize = 24.sp, lineHeight = 28.8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
    domanda = TextStyle(fontFamily = f, fontSize = 22.sp, lineHeight = 28.6.sp, fontWeight = FontWeight.ExtraBold),
    titoloSezione = TextStyle(fontFamily = f, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold),
    titoloFoglio = TextStyle(fontFamily = f, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold),
    forteGrande = TextStyle(fontFamily = f, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.ExtraBold),
    forte = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
    testo = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    testoMedio = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    secondario = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    piccolo = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
    etichetta = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
    numeri = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    pulsante = TextStyle(fontFamily = f, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    pulsantePiccolo = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    pillola = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
)

/**
 * Figtree (licenza SIL OFL 1.1, testo in `assets/licenze/figtree-OFL.txt`) è incluso
 * nell'app: un solo file variabile da cui si prendono i pesi della guida. Niente
 * richieste a Google durante l'uso.
 */
@OptIn(ExperimentalTextApi::class)
val Figtree: FontFamily = FontFamily(
    listOf(
        400 to FontWeight.Normal,
        500 to FontWeight.Medium,
        600 to FontWeight.SemiBold,
        700 to FontWeight.Bold,
        800 to FontWeight.ExtraBold,
    ).map { (peso, nome) ->
        Font(R.font.figtree, weight = nome, variationSettings = FontVariation.Settings(FontVariation.weight(peso)))
    },
)

val LocalColori = staticCompositionLocalOf { Chiaro }
val LocalTesti = staticCompositionLocalOf { testi(FontFamily.Default) }

/** false = meno animazioni (sistema o Impostazioni): tutto istantaneo (§13). */
val LocalMovimenti = staticCompositionLocalOf { true }

/** Scorciatoie: `L.colori.accento`, `L.testi.forte`. */
object L {
    val colori: Colori
        @Composable get() = LocalColori.current
    val testi: Testi
        @Composable get() = LocalTesti.current
}

@Composable
fun materia(nome: String?): ColoreMateria {
    val scuro = LocalColori.current.scuro
    return remember(nome, scuro) { coloreMateria(nome, scuro) }
}

/** Il sistema ha tolto le animazioni (Impostazioni sviluppatore o accessibilità). */
fun animazioniDiSistema(): Boolean = ValueAnimator.areAnimatorsEnabled()

/** 150-350 ms, curva morbida, niente rimbalzi; istantaneo con meno animazioni (§13). */
@Composable
fun <T> morbido(durataMs: Int = 250): FiniteAnimationSpec<T> =
    if (LocalMovimenti.current) tween(durataMs, easing = FastOutSlowInEasing) else snap()

@Composable
fun LectioTema(
    scuro: Boolean = isSystemInDarkTheme(),
    movimenti: Boolean = animazioniDiSistema(),
    contenuto: @Composable () -> Unit,
) {
    val colori = if (scuro) Scuro else Chiaro
    val stili = remember { testi(Figtree) }
    val schema = if (scuro) {
        darkColorScheme(
            primary = colori.accento, onPrimary = colori.suAccento,
            primaryContainer = colori.accentoTenue, onPrimaryContainer = colori.accentoTesto,
            secondary = colori.tenue, onSecondary = colori.fondo,
            background = colori.fondo, onBackground = colori.testo,
            surface = colori.superficie, onSurface = colori.testo,
            surfaceVariant = colori.superficie2, onSurfaceVariant = colori.tenue,
            outline = colori.moltoTenue, outlineVariant = colori.riga,
            error = colori.errore, errorContainer = colori.erroreTenue, onErrorContainer = colori.erroreTesto,
            surfaceContainerLow = colori.superficie, surfaceContainer = colori.superficie,
            surfaceContainerHigh = colori.superficie, surfaceContainerHighest = colori.superficie2,
        )
    } else {
        lightColorScheme(
            primary = colori.accento, onPrimary = colori.suAccento,
            primaryContainer = colori.accentoTenue, onPrimaryContainer = colori.accentoTesto,
            secondary = colori.tenue, onSecondary = Color.White,
            background = colori.fondo, onBackground = colori.testo,
            surface = colori.superficie, onSurface = colori.testo,
            surfaceVariant = colori.superficie2, onSurfaceVariant = colori.tenue,
            outline = colori.moltoTenue, outlineVariant = colori.riga,
            error = colori.errore, errorContainer = colori.erroreTenue, onErrorContainer = colori.erroreTesto,
            surfaceContainerLow = colori.superficie, surfaceContainer = colori.superficie,
            surfaceContainerHigh = colori.superficie, surfaceContainerHighest = colori.superficie2,
        )
    }
    val base = Typography()
    val tipografia = base.copy(
        bodyLarge = stili.testo,
        bodyMedium = stili.testoMedio,
        bodySmall = stili.secondario,
        titleLarge = stili.titoloSezione,
        titleMedium = stili.forteGrande,
        titleSmall = stili.forte,
        labelLarge = stili.pulsantePiccolo,
        labelMedium = stili.piccolo,
        labelSmall = stili.piccolo,
    )
    CompositionLocalProvider(
        LocalColori provides colori,
        LocalTesti provides stili,
        LocalMovimenti provides movimenti,
    ) {
        MaterialTheme(colorScheme = schema, typography = tipografia, content = contenuto)
    }
}
