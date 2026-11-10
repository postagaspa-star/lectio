package eu.stgm.lectio.ui.studio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import eu.stgm.lectio.dati.DocumentoPdf
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Grassetto
import eu.stgm.lectio.logica.TestiLampo
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Barra
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.Segnaposto
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** `**parola**` → peso 700, stesso colore (aspetto.md §33.3). */
fun testoRicco(testo: String): AnnotatedString = buildAnnotatedString {
    Grassetto.pezzi(testo).forEach { p ->
        if (p.forte) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.testo) } else append(p.testo)
    }
}

@Composable
fun TestoRicco(testo: String, stile: TextStyle, colore: Color = L.colori.testo, modifier: Modifier = Modifier, righe: Int = Int.MAX_VALUE) {
    Text(testoRicco(testo), style = stile, color = colore, modifier = modifier, maxLines = righe, overflow = TextOverflow.Ellipsis)
}

/** Una voce a punti: pallino 7 del colore della materia, rientro 18. */
@Composable
fun VoceAPunti(testo: String, colore: Color, stile: TextStyle = L.testi.lettura) {
    Row {
        Box(Modifier.padding(top = 10.dp).size(7.dp).clip(CircleShape).background(colore))
        TestoRicco(testo, stile, modifier = Modifier.padding(start = 11.dp))
    }
}

/** In caricamento: tre segnaposto che pulsano. */
@Composable
fun TreSegnaposto(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segnaposto(altezza = 18.dp)
        Segnaposto(altezza = 18.dp, larghezza = 0.9f)
        Segnaposto(altezza = 18.dp, larghezza = 0.6f)
    }
}

/** Lavoro in corso sul server: frase e barra `fatto`/`totale` (aspetto.md §33.9). */
@Composable
fun VistaInLavoro(frase: String, fatto: Int, totale: Int, onAnnulla: (() -> Unit)?, testoAnnulla: String = "Annulla") {
    val c = L.colori
    Scheda(rilievo = true, padding = PaddingValues(20.dp), spazio = 14.dp) {
        Text(frase, style = L.testi.forte, color = c.accentoTesto)
        if (totale > 0) Barra(fatto.toFloat() / totale, altezza = 8.dp) else Segnaposto(altezza = 8.dp)
        Text("Puoi uscire: ti avviso io.", style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
    }
    if (onAnnulla != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulsanteTesto(testoAnnulla, onAnnulla, colore = c.tenue)
        }
    }
}

/** Una fascia per "non si può fare senza rete" o altri avvisi calmi: frase semplice. */
@Composable
fun FraseTenue(testo: String, modifier: Modifier = Modifier) {
    Text(testo, style = L.testi.secondario, color = L.colori.tenue, modifier = modifier)
}

/** La scheda del Ripasso lampo in Oggi e in Materia (aspetto.md §31.1, §31.2). */
@Composable
fun SchedaLampo(daFare: Int, minuti: Int, puoIniziare: Boolean, vuoto: String, onInizia: () -> Unit, modifier: Modifier = Modifier, bersaglioInizia: Boolean = false) {
    val c = L.colori
    if (daFare <= 0) {
        Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icone.Spunta, contentDescription = null, tint = c.fatto, modifier = Modifier.size(20.dp))
            Text(vuoto, style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.tenue)
        }
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(c.superficie)
            .clickable(enabled = puoIniziare, role = Role.Button, onClick = onInizia)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.superficie2), contentAlignment = Alignment.Center) {
            Icon(Icone.Lampo, contentDescription = null, tint = c.testo, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Ripasso lampo", style = L.testi.forteGrande.copy(fontWeight = FontWeight.Bold), color = c.testo)
            Text(TestiLampo.riga(daFare, minuti), style = L.testi.secondario, color = c.tenue)
        }
        PulsanteSecondario(
            "Inizia", onInizia,
            if (bersaglioInizia) Modifier.bersaglio(Bersagli.LAMPO_OGGI) else Modifier,
            altezza = 48.dp, abilitato = puoIniziare,
        )
    }
}

/** La pillola della materia con il colore passato (lista materie già caricate o nome da una scheda). */
@Composable
fun coloreDi(nome: String?) = materia(nome)

/**
 * Scarica i PDF fatti dal server (la firma ce la mette lui, contratto §25) e li mette
 * nei Download: "PDF nei Download" con Apri, oppure la frase del server con Riprova.
 */
class GestorePdf(
    private val contesto: Context,
    private val ambito: CoroutineScope,
    private val messaggi: SnackbarHostState,
) {
    var inCorso by mutableStateOf(emptySet<DocumentoPdf>())
        private set

    fun scarica(sorgente: Sorgente, documento: DocumentoPdf, nome: String) {
        if (documento in inCorso) return
        if (sorgente.esempio) {
            ambito.launch { messaggi.showSnackbar("Nella visita il PDF non si scarica.") }
            return
        }
        inCorso = inCorso + documento
        ambito.launch {
            val temporaneo = File(File(contesto.cacheDir, "pdf"), "studio-${System.nanoTime()}.pdf")
            val esito = sorgente.scaricaPdf(documento, temporaneo)
            val indirizzo = if (esito is Esito.Ok) withContext(Dispatchers.IO) { nelleDownload(temporaneo, nomeFile(nome)) } else null
            temporaneo.delete()
            inCorso = inCorso - documento
            if (indirizzo != null) {
                val r = messaggi.showSnackbar("PDF nei Download", "Apri", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) apri(indirizzo)
            } else {
                val frase = if (esito is Esito.Ok) "Non sono riuscito a salvare il PDF." else esito.messaggio ?: "Non sono riuscito a scaricare il PDF."
                val r = messaggi.showSnackbar(frase, "Riprova", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) scarica(sorgente, documento, nome)
            }
        }
    }

    private fun nomeFile(titolo: String): String =
        "Lectio - " + titolo.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(80).ifEmpty { "documento" } + ".pdf"

    private fun nelleDownload(file: File, nome: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val risolutore = contesto.contentResolver
            val valori = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, nome)
                put(MediaStore.Downloads.MIME_TYPE, TIPO_PDF)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            risolutore.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valori)?.also { uri ->
                risolutore.openOutputStream(uri)?.use { uscita -> file.inputStream().use { it.copyTo(uscita) } }
                risolutore.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        } else {
            // Android 8 e 9: nella cartella Download dell'app, che non vuole permessi.
            val cartella = contesto.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            val copia = File(cartella.apply { mkdirs() }, nome)
            file.copyTo(copia, overwrite = true)
            FileProvider.getUriForFile(contesto, contesto.packageName + ".file", copia)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun apri(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, TIPO_PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            contesto.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            ambito.launch { messaggi.showSnackbar("Sul telefono non c'è un'app per aprire i PDF.") }
        }
    }

    private companion object {
        const val TIPO_PDF = "application/pdf"
    }
}

val LocalGestorePdf = staticCompositionLocalOf<GestorePdf?> { null }
