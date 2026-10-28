package eu.stgm.lectio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Le icone a tratto del prototipo (docs/prototipo/), dagli stessi percorsi SVG
 * (riquadro 24×24, tratto 2 px, estremi tondi). Il colore lo dà chi le usa. Mai emoji.
 */
object Icone {
    private fun cerchio(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun rettangolo(x: Float, y: Float, l: Float, a: Float, r: Float) =
        "M${x + r},$y h${l - 2 * r} a$r,$r 0 0 1 $r,$r v${a - 2 * r} a$r,$r 0 0 1 ${-r},$r h${-(l - 2 * r)} " +
            "a$r,$r 0 0 1 ${-r},${-r} v${-(a - 2 * r)} a$r,$r 0 0 1 $r,${-r} z"

    private fun icona(nome: String, spessore: Float, tratti: List<String>, pieni: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(nome, 24.dp, 24.dp, 24f, 24f).apply {
            tratti.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = spessore,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            pieni.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    // Barra in basso.
    val Oggi = icona("oggi", 2f, listOf("M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"))
    val Materie = icona("materie", 2f, listOf("M4 5a2 2 0 0 1 2-2h12v16H6a2 2 0 0 0-2 2z", "M4 21V5", "M8 7h6"))
    val Lezioni = icona(
        "lezioni", 2f,
        listOf("M4 14v-2a8 8 0 0 1 16 0v2", rettangolo(3f, 14f, 5f, 7f, 2f), rettangolo(16f, 14f, 5f, 7f, 2f)),
    )
    val Gemini = icona("gemini", 2f, listOf(rettangolo(8f, 8f, 12f, 13f, 2f), "M16 8V5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h2"))

    val Ingranaggio = icona(
        "impostazioni", 2f,
        listOf(
            cerchio(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
        ),
    )

    val Indietro = icona("indietro", 2f, listOf("M15 18l-6-6 6-6"))
    val Avanti = icona("avanti", 2f, listOf("M9 18l6-6-6-6"))
    val Giu = icona("giu", 2f, listOf("M6 9l6 6 6-6"))
    val Scarica = icona("scarica", 2.2f, listOf("M12 3v12", "M7 10l5 5 5-5", "M5 21h14"))
    val Spunta = icona("spunta", 2.4f, listOf("M5 12l5 5L20 7"))
    val Piu = icona("piu", 2.4f, listOf("M12 5v14M5 12h14"))
    val Meno = icona("meno", 2.4f, listOf("M5 12h14"))

    /** "Quasi": un cerchio pieno a metà. */
    val Meta = icona("meta", 2f, listOf(cerchio(12f, 12f, 8f)), listOf("M12 4a8 8 0 0 1 0 16z"))
    val Chiudi = icona("chiudi", 2.4f, listOf("M6 6l12 12M18 6L6 18"))
    val AltreAzioni = icona("altre", 2f, emptyList(), listOf(cerchio(5.5f, 12f, 1.8f), cerchio(12f, 12f, 1.8f), cerchio(18.5f, 12f, 1.8f)))
    val Cestino = icona("cestino", 2f, listOf("M4 7h16", "M10 11v6M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3"))
    val Matita = icona("matita", 2f, listOf("M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"))
    val Riprendi = icona("torna", 2f, listOf("M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5"))

    // Materiale.
    val File = icona("file", 2f, listOf("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6"))
    val Verifica = icona("verifica", 2f, listOf("M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M14 3v6h6", "M9 15l2 2 4-4"))
    val Interrogazione = icona("interrogazione", 2f, listOf("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"))
    val Appunti = Matita
    val Libro = icona("libro", 2f, listOf("M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z", "M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z"))
    val Foto = icona("foto", 2f, listOf("M3 8a2 2 0 0 1 2-2h2l2-3h6l2 3h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", cerchio(12f, 13f, 4f)))
    val Testo = icona("testo", 2f, listOf("M5 6.5h14M5 11h14M5 15.5h9"))
    val Calendario = icona("calendario", 2f, listOf(rettangolo(3.5f, 5f, 17f, 15f, 3f), "M3.5 10h17M8 3v4M16 3v4"))
    val Apri = icona("apri", 2f, listOf("M14 4h6v6", "M10 14L20 4", "M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5"))

    // Lettore (pieni come nel prototipo).
    val Play = icona("play", 0f, emptyList(), listOf("M7 4.5v15a1 1 0 0 0 1.5.9l12-7.5a1 1 0 0 0 0-1.7l-12-7.5A1 1 0 0 0 7 4.5z"))
    val Pausa = icona("pausa", 0f, emptyList(), listOf(rettangolo(6f, 4.5f, 4f, 15f, 1.2f), rettangolo(14f, 4.5f, 4f, 15f, 1.2f)))
}
