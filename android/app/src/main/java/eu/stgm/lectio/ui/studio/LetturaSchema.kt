package eu.stgm.lectio.ui.studio

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.BloccoSchema
import eu.stgm.lectio.dati.DocumentoPdf
import eu.stgm.lectio.dati.Fonte
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.SezioneSchema
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.FormaRiassunto
import eu.stgm.lectio.logica.SegniFonte
import eu.stgm.lectio.logica.TestiLampo
import eu.stgm.lectio.logica.TestiRiassunto
import eu.stgm.lectio.logica.TestiSchema
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.FoglioFermo
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MenuPuntini
import eu.stgm.lectio.ui.PillolaIcona
import eu.stgm.lectio.ui.PillolaMateria
import eu.stgm.lectio.ui.PillolaStato
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.SopraTutto
import eu.stgm.lectio.ui.TipoPillola
import eu.stgm.lectio.ui.VoceMenu
import eu.stgm.lectio.ui.fondoBlocco
import eu.stgm.lectio.ui.tema.ColoreMateria
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LocalMovimenti
import eu.stgm.lectio.ui.tema.materia
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FotoSchema(val immagine: ImageBitmap? = null, val errore: Boolean = false, val cerca: Boolean = false)

data class StatoSchema(
    val schema: Schema? = null,
    val materia: Materia? = null,
    val caricato: Boolean = false,
    val errore: String? = null,
    val inCorso: Boolean = false,
    val prova: Boolean = false,
    val foto: Map<Int, FotoSchema> = emptyMap(),
    val avviso: String? = null,
    val eliminato: Boolean = false,
    val erroreRiassunto: String? = null,
    val apriRiassunto: String? = null,
    /** Arrivati da "Riassunto" nel foglio Nuovo della materia: il foglio dei riassunti si apre da solo (§43.3). */
    val chiediRiassunti: Boolean = false,
)

class SchemaViewModel(private val sorgente: Sorgente, val schemaId: Long) : ViewModel() {
    private val _stato = MutableStateFlow(StatoSchema())
    val stato: StateFlow<StatoSchema> = _stato.asStateFlow()
    private val fotoInArrivo = mutableSetOf<Int>()

    /** Dove riprendere la lettura, senza dirlo (aspetto.md §33.8). */
    val parteLetta: Int? get() = sorgente.deposito.parteLetta(schemaId)
    fun ricordaParte(n: Int) = sorgente.deposito.ricordaParte(schemaId, n)

    fun carica() {
        viewModelScope.launch {
            val l = sorgente.schema(schemaId)
            val materie = l.dato?.let { sorgente.materie().dato }
            val prova = if (!_stato.value.caricato) (sorgente.salute() as? Esito.Ok)?.dato?.modalitaProva ?: false else _stato.value.prova
            _stato.update {
                it.copy(
                    schema = l.dato ?: it.schema,
                    materia = materie?.firstOrNull { m -> m.id == l.dato?.materiaId } ?: it.materia,
                    caricato = true,
                    errore = if (l.dato == null && it.schema == null) l.errore else null,
                    prova = prova,
                )
            }
        }
    }

    private fun azione(chiamata: suspend () -> Esito<*>) {
        _stato.update { it.copy(inCorso = true) }
        viewModelScope.launch {
            val e = chiamata()
            _stato.update { it.copy(inCorso = false, avviso = e.messaggio) }
            carica()
        }
    }

    fun annulla() = azione { sorgente.annullaSchema(schemaId) }
    fun riprova() = azione {
        val e = sorgente.riprovaSchema(schemaId)
        if (e is Esito.Ok) sorgente.deposito.seguiSchema(schemaId)
        e
    }

    fun elimina() {
        _stato.update { it.copy(inCorso = true) }
        viewModelScope.launch {
            val e = sorgente.eliminaSchema(schemaId)
            if (e is Esito.Ok) sorgente.deposito.smettiSchema(schemaId)
            _stato.update { it.copy(inCorso = false, eliminato = e is Esito.Ok, avviso = e.messaggio) }
        }
    }

    fun avvisoVisto() = _stato.update { it.copy(avviso = null) }

    // --- Foto ---------------------------------------------------------------------------------

    fun caricaFoto(n: Int, rinfresca: Boolean = false) {
        if (!rinfresca && _stato.value.foto[n]?.immagine != null) return
        if (!fotoInArrivo.add(n)) return
        viewModelScope.launch {
            val e = sorgente.immagineSchema(schemaId, n, rinfresca)
            val bitmap = (e as? Esito.Ok)?.dato?.let { decodifica(it) }
            fotoInArrivo.remove(n)
            _stato.update { it.copy(foto = it.foto + (n to FotoSchema(bitmap ?: it.foto[n]?.immagine, errore = bitmap == null && it.foto[n]?.immagine == null))) }
        }
    }

    private suspend fun decodifica(b: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val misure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, misure)
        var riduci = 1
        while (misure.outWidth / (riduci * 2) >= LARGHEZZA_MASSIMA) riduci *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = riduci })?.asImageBitmap()
    }

    fun cambiaFoto(n: Int) {
        _stato.update { it.copy(foto = it.foto + (n to (it.foto[n] ?: FotoSchema()).copy(cerca = true))) }
        viewModelScope.launch {
            val e = sorgente.cambiaImmagine(schemaId, n)
            if (e is Esito.Ok) {
                _stato.update { it.copy(schema = e.dato, foto = it.foto + (n to (it.foto[n] ?: FotoSchema()).copy(cerca = false))) }
                caricaFoto(n, rinfresca = true)
            } else {
                val frase = if (e is Esito.Errore && e.stato == 404) "Non ne ho trovate altre" else e.messaggio
                _stato.update { it.copy(avviso = frase, foto = it.foto + (n to (it.foto[n] ?: FotoSchema()).copy(cerca = false))) }
            }
        }
    }

    fun togliFoto(n: Int) {
        viewModelScope.launch {
            val e = sorgente.togliImmagine(schemaId, n)
            if (e is Esito.Ok) _stato.update { it.copy(schema = e.dato, foto = it.foto - n) } else _stato.update { it.copy(avviso = e.messaggio) }
        }
    }

    // --- Riassunti (aspetto.md §41.2) -----------------------------------------------------------

    /** Un tocco crea subito il riassunto e apre la sua pagina; se esiste già, la apre e basta. */
    fun scegliForma(forma: FormaRiassunto) {
        val fatti = _stato.value.schema?.riassunti?.fatti.orEmpty()
        if (fatti.any { it.forma == forma.codice }) {
            _stato.update { it.copy(apriRiassunto = forma.codice, erroreRiassunto = null) }
            return
        }
        viewModelScope.launch {
            val e = sorgente.creaRiassunto(schemaId, forma.codice)
            if (e is Esito.Ok) {
                sorgente.deposito.seguiRiassunto(e.dato.id)
                _stato.update { it.copy(apriRiassunto = forma.codice, erroreRiassunto = null) }
            } else {
                // Tetto finito: il foglio resta aperto e sotto le righe c'è la frase del server.
                _stato.update { it.copy(erroreRiassunto = e.messaggio) }
            }
        }
    }

    fun riassuntoAperto() = _stato.update { it.copy(apriRiassunto = null, erroreRiassunto = null) }
    fun erroreRiassuntoVisto() = _stato.update { it.copy(erroreRiassunto = null) }

    /** Apri la lettura col foglio "Che riassunto vuoi?" già aperto (aspetto.md §43.3). */
    fun chiediRiassunti() = _stato.update { it.copy(chiediRiassunti = true) }

    /** Il foglio è aperto, o la pagina è stata chiusa prima: la richiesta non vale più. */
    fun riassuntiChiesti() = _stato.update { it.copy(chiediRiassunti = false) }

    private companion object {
        const val LARGHEZZA_MASSIMA = 1400
    }
}

data class AzioniSchema(
    val indietro: () -> Unit = {},
    val apriMateriale: (Long) -> Unit = {},
    val aggiungiMateriale: (Long) -> Unit = {},
    /** "Ascolta questa parte": Nuova lezione con titolo della parte e materiali dello schema. */
    val ascolta: (Schema, SezioneSchema) -> Unit = { _, _ -> },
    val scegliAltro: (Schema) -> Unit = {},
    val ripassa: (Long) -> Unit = {},
    val apriRiassunto: (String) -> Unit = {},
    val inLavoro: () -> Unit = {},
)

/** La lettura dello schema (aspetto.md §33). */
@Composable
fun LetturaSchemaScreen(
    vm: SchemaViewModel,
    capito: NonHoCapitoViewModel,
    azioni: AzioniSchema,
    sorgente: Sorgente,
    esempio: Boolean,
    senzaRete: Boolean,
    mini: @Composable () -> Unit = {},
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.carica() }
    val sc = s.schema
    LaunchedEffect(sc?.stato) {
        if (sc?.inLavoro == true) {
            azioni.inLavoro()
            while (true) {
                delay(4_000)
                vm.carica()
            }
        }
    }
    LaunchedEffect(s.eliminato) { if (s.eliminato) azioni.indietro() }
    LaunchedEffect(s.apriRiassunto) {
        s.apriRiassunto?.let {
            vm.riassuntoAperto()
            azioni.apriRiassunto(it)
        }
    }
    // Se si esce prima che lo schema si apra, la prossima volta si parte normale.
    DisposableEffect(vm) { onDispose { vm.riassuntiChiesti() } }
    val c = L.colori
    val m = materia(s.materia?.colore)
    val offline = senzaRete && !esempio
    var conferma by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.fondo)) {
        when {
            sc != null && sc.pronto && sc.contenuto?.sezioni?.isNotEmpty() == true -> Lettura(sc, s, vm, capito, azioni, sorgente, m, esempio, offline, mini) { conferma = true }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, dimensione = 48.dp)
                    s.materia?.let { PillolaMateria(it.nome, it.colore, Modifier.weight(1f, fill = false)) }
                }
                if (sc != null) Text(sc.nome, style = L.testi.titoloPagina.copy(fontSize = 26.sp, lineHeight = 32.sp), color = c.testo)
                when {
                    !s.caricato -> TreSegnaposto()
                    sc == null -> {
                        Text("Si apre quando torna la rete.", style = L.testi.testo, color = c.tenue)
                        s.errore?.takeIf { !offline }?.let { RigaErrore(it) }
                        PulsantePieno("Riprova", vm::carica)
                    }
                    sc.inLavoro -> VistaInLavoro(sc.frase?.takeIf { it.isNotBlank() } ?: if (sc.stato == "in_coda") "In coda" else "Lo sto preparando", sc.fatto, sc.totale, vm::annulla.takeIf { !offline })
                    sc.fallito -> {
                        RigaErrore(sc.errore ?: "Non è riuscito.")
                        PulsantePieno("Riprova", vm::riprova, abilitato = !s.inCorso && !offline)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PulsanteTesto("Elimina", { conferma = true }, colore = c.tenue, abilitato = !offline) }
                    }
                    sc.annullato -> {
                        Text("Annullato.", style = L.testi.testo, color = c.tenue)
                        PulsantePieno("Riprova", vm::riprova, abilitato = !s.inCorso && !offline)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PulsanteTesto("Elimina", { conferma = true }, colore = c.tenue, abilitato = !offline) }
                    }
                    else -> {
                        Text("Nei documenti scelti non ho trovato abbastanza per uno schema.", style = L.testi.testo, color = c.testo)
                        PulsantePieno("Scegli altro materiale", { azioni.scegliAltro(sc) }, abilitato = !offline)
                    }
                }
                s.avviso?.let { RigaErrore(it) }
            }
        }
    }
    if (conferma) {
        Conferma("Eliminare lo schema?", "Sparisce anche dal server di casa. Non si può tornare indietro.", "Elimina", { conferma = false; vm.elimina() }, { conferma = false })
    }
    FoglioNonHoCapito(capito, offline, azioni.aggiungiMateriale, { f -> azioni.apriMateriale(f.materialeId) })
}

private data class Selezione(val sezione: Int, val blocco: Int)

@Composable
private fun Lettura(
    sc: Schema,
    s: StatoSchema,
    vm: SchemaViewModel,
    capito: NonHoCapitoViewModel,
    azioni: AzioniSchema,
    sorgente: Sorgente,
    m: ColoreMateria,
    esempio: Boolean,
    offline: Boolean,
    mini: @Composable () -> Unit,
    chiediElimina: () -> Unit,
) {
    val c = L.colori
    val contenuto = sc.contenuto ?: return
    val sezioni = contenuto.sezioni
    val lista = rememberLazyListState()
    val ambito = rememberCoroutineScope()
    val visita = LocalVisita.current
    val pdf = LocalGestorePdf.current
    val pdfInCorso = pdf?.inCorso?.contains(DocumentoPdf.DiSchema(sc.id)) == true
    val haNota = TestiSchema.haNote(sc)
    var selezione by remember { mutableStateOf<Selezione?>(null) }
    var foglioIndice by remember { mutableStateOf(false) }
    var foglioFonti by remember { mutableStateOf<List<Fonte>?>(null) }
    var foglioRiassunto by remember { mutableStateOf(false) }
    var tutteLeParti by remember { mutableStateOf(false) }
    var tornaA by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val indiceTesto = sezioni.size > 1
    val primoItem = 1 + (if (haNota) 1 else 0) + (if (indiceTesto) 1 else 0)

    val parteCorrente by remember(sezioni, primoItem) {
        derivedStateOf {
            val info = lista.layoutInfo
            val limite = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            val vista = info.visibleItemsInfo.filter { (it.key as? String)?.startsWith("s-") == true && it.offset <= limite }.lastOrNull()
                ?: info.visibleItemsInfo.firstOrNull { (it.key as? String)?.startsWith("s-") == true }
            (vista?.key as? String)?.removePrefix("s-")?.toIntOrNull() ?: sezioni.first().n
        }
    }
    LaunchedEffect(parteCorrente) { vm.ricordaParte(parteCorrente) }

    // Riaprendo si riparte dalla parte letta per ultima, senza dirlo.
    LaunchedEffect(sc.id) {
        val i = vm.parteLetta?.let { n -> sezioni.indexOfFirst { it.n == n } } ?: -1
        if (i > 0) lista.scrollToItem(primoItem + i)
    }

    // Con meno animazioni il salto è immediato (aspetto.md §33.8, §40).
    val muove = LocalMovimenti.current
    val portaA: (Int, Int) -> Unit = { i, o ->
        ambito.launch { if (muove) lista.animateScrollToItem(i, o) else lista.scrollToItem(i, o) }
    }
    val vaiAllaParte: (Int) -> Unit = { n ->
        val i = sezioni.indexOfFirst { it.n == n }
        if (i >= 0) {
            tornaA = lista.firstVisibleItemIndex to lista.firstVisibleItemScrollOffset
            portaA(primoItem + i, 0)
        }
    }
    LaunchedEffect(tornaA) {
        if (tornaA != null) {
            delay(5_000)
            tornaA = null
        }
    }

    // Visita guidata: un passo indietro richiude quello che il passo dopo aveva aperto.
    if (visita != null) {
        LaunchedEffect(visita.indice, visita.attiva) {
            if (!visita.attiva) return@LaunchedEffect
            when (visita.corrente.bersaglio) {
                Bersagli.BARRA_LETTURA -> {
                    capito.chiudi()
                    selezione = null
                }
                Bersagli.PARAGRAFO -> {
                    foglioIndice = false
                    capito.chiudi()
                    selezione = null
                    lista.scrollToItem(primoItem)
                }
                Bersagli.NON_HO_CAPITO -> capito.chiudi()
            }
        }
        DisposableEffect(foglioIndice) {
            if (foglioIndice && visita.attiva && visita.corrente.bersaglio == Bersagli.BARRA_LETTURA) visita.bersaglioTemporaneo = Bersagli.RIGA_INDICE
            onDispose { visita.bersaglioTemporaneo = null }
        }
    }

    val primoParagrafo = remember(sezioni) {
        sezioni.firstNotNullOfOrNull { se -> se.blocchi.indexOfFirst { it.tipo == "paragrafo" }.takeIf { it >= 0 }?.let { Selezione(se.n, it) } }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = lista,
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "testata") {
                    Colonna { Testata(sc, s, azioni, sorgente, esempio, offline, pdfInCorso, { foglioRiassunto = true }, chiediElimina) }
                }
                if (haNota) item(key = "nota") { Colonna { NotaMancanze(sc, offline, azioni) } }
                if (indiceTesto) {
                    item(key = "indice") {
                        Colonna {
                            Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), spazio = 0.dp) {
                                Etichetta("Indice", Modifier.padding(bottom = 4.dp))
                                val visibili = if (tutteLeParti || sezioni.size <= 6) sezioni else sezioni.take(6)
                                visibili.forEach { se ->
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { vaiAllaParte(se.n) },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(se.n.toString(), style = L.testi.pillola, color = m.testo)
                                        Text(se.titolo, style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold), color = c.testo, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (!tutteLeParti && sezioni.size > 6) PulsanteTesto("Mostra tutte (${sezioni.size})", { tutteLeParti = true })
                            }
                        }
                    }
                }
                items(sezioni, key = { "s-${it.n}" }) { se ->
                    Colonna {
                        SchedaSezione(
                            se, sc, s, vm, m, offline, contenuto.fonti, selezione?.takeIf { it.sezione == se.n }?.blocco,
                            primoParagrafo?.takeIf { it.sezione == se.n }?.blocco,
                            onTocca = { i, spiegabile ->
                                if (spiegabile) {
                                    if (se.n == primoParagrafo?.sezione && i == primoParagrafo.blocco) visita?.azione(Bersagli.PARAGRAFO)
                                    selezione = if (selezione == Selezione(se.n, i)) null else Selezione(se.n, i)
                                }
                            },
                            onNonHoCapito = { b ->
                                visita?.azione(Bersagli.NON_HO_CAPITO)
                                capito.apriDaSchema(sc.materiaId, sc.id, se.n, TestiSchema.passaggio(b))
                                if (esempio) capito.invia()
                            },
                            onFonti = { f ->
                                if (f.size == 1) azioni.apriMateriale(f[0].materialeId) else foglioFonti = f
                            },
                            onAscolta = { azioni.ascolta(sc, se) },
                        )
                    }
                }
                if (sc.carte > 0) {
                    item(key = "carte") {
                        Colonna {
                            Scheda(padding = PaddingValues(18.dp), spazio = 12.dp) {
                                Text("${TestiLampo.schede(sc.carte).replaceFirstChar { it.uppercase() }} ${if (sc.carte == 1) "è già" else "sono già"} in Ripasso lampo.", style = L.testi.forte, color = c.testo)
                                PulsanteSecondario("Ripassa adesso", { azioni.ripassa(sc.materiaId) }, altezza = 48.dp)
                            }
                        }
                    }
                }
                item(key = "riassunto") {
                    Colonna {
                        val ultimo = TestiRiassunto.ultimo(sc.riassunti?.fatti.orEmpty())
                        Scheda(padding = PaddingValues(18.dp), spazio = 12.dp) {
                            Text("Riassunto", style = L.testi.titoloSezione, color = c.testo)
                            PulsanteSecondario(
                                if (ultimo != null) "Apri il riassunto" else "Fai il riassunto",
                                { if (ultimo != null) azioni.apriRiassunto(ultimo.forma) else foglioRiassunto = true },
                                altezza = 48.dp,
                                abilitato = ultimo != null || !offline,
                            )
                        }
                    }
                }
            }
            tornaA?.let { (i, o) ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).heightIn(min = 44.dp).clip(CircleShape).background(c.accentoTenue)
                        .clickable(role = Role.Button) {
                            portaA(i, o)
                            tornaA = null
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icone.Riprendi, contentDescription = null, tint = c.accentoTesto, modifier = Modifier.size(16.dp))
                    Text("Torna dov'eri", style = L.testi.pillola, color = c.accentoTesto, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        mini()
        BarraLettura(sezioni, parteCorrente, m) { foglioIndice = true }
    }

    s.avviso?.let { a ->
        LaunchedEffect(a) {
            delay(3_500)
            vm.avvisoVisto()
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 76.dp), contentAlignment = Alignment.BottomCenter) {
            Text(a, style = L.testi.secondario, color = c.fondo, modifier = Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(14.dp)).background(c.testo).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }

    if (foglioIndice) {
        val scorre = rememberScrollState()
        val passo = with(LocalDensity.current) { 60.dp.toPx() }
        LaunchedEffect(Unit) { scorre.scrollTo((sezioni.indexOfFirst { it.n == parteCorrente }.coerceAtLeast(0) * passo).toInt()) }
        FoglioFermo(onChiudi = { foglioIndice = false }, titolo = "Indice", scorrimento = scorre) {
            sezioni.forEachIndexed { i, se ->
                val corrente = se.n == parteCorrente
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i == 0) Modifier.bersaglio(Bersagli.RIGA_INDICE) else Modifier)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (corrente) c.accentoTenue else Color.Transparent)
                        .clickable(role = Role.Button) {
                            visita?.azione(Bersagli.BARRA_LETTURA)
                            foglioIndice = false
                            vaiAllaParte(se.n)
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NumeroParte(se.n, m.tenue, m.testo)
                    Text(
                        se.titolo,
                        style = L.testi.testoMedio.copy(fontWeight = if (corrente) FontWeight.ExtraBold else FontWeight.SemiBold),
                        color = if (corrente) c.accentoTesto else c.testo,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    foglioFonti?.let { f ->
        FoglioFermo(onChiudi = { foglioFonti = null }, titolo = "Da dove viene") {
            f.forEach { x ->
                RigaFonte(x) {
                    foglioFonti = null
                    azioni.apriMateriale(it.materialeId)
                }
            }
        }
    }
    if (foglioRiassunto) {
        FoglioRiassunto(sc, s, m, offline, vm::scegliForma) {
            foglioRiassunto = false
            vm.erroreRiassuntoVisto()
        }
    }
    LaunchedEffect(s.apriRiassunto) { if (s.apriRiassunto != null) foglioRiassunto = false }
    LaunchedEffect(s.chiediRiassunti) {
        if (s.chiediRiassunti) {
            foglioRiassunto = true
            vm.riassuntiChiesti()
        }
    }
}

/** Il testo si ferma a 580 dp in orizzontale o su tablet (aspetto.md §33.1). */
@Composable
private fun Colonna(contenuto: @Composable () -> Unit) {
    Box(Modifier.widthIn(max = 604.dp).fillMaxWidth()) { contenuto() }
}

@Composable
private fun Testata(
    sc: Schema,
    s: StatoSchema,
    azioni: AzioniSchema,
    sorgente: Sorgente,
    esempio: Boolean,
    offline: Boolean,
    pdfInCorso: Boolean,
    apriRiassunto: () -> Unit,
    chiediElimina: () -> Unit,
) {
    val c = L.colori
    val pdf = LocalGestorePdf.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, dimensione = 48.dp)
            Box(Modifier.weight(1f)) { s.materia?.let { PillolaMateria(it.nome, it.colore) } }
            PulsanteTondo(Icone.Riassunto, "Riassunto", { if (!offline || sc.riassunti?.fatti?.isNotEmpty() == true) apriRiassunto() }, dimensione = 48.dp)
            if (pdfInCorso) {
                Text("Preparo il PDF…", style = L.testi.piccolo, color = c.tenue, maxLines = 1)
            } else {
                PulsanteTondo(Icone.Scarica, "Scarica PDF", { if (!offline) pdf?.scarica(sorgente, DocumentoPdf.DiSchema(sc.id), sc.nome) }, dimensione = 48.dp, tinta = if (offline) c.moltoTenue else c.testo)
            }
            MenuPuntini(listOf(VoceMenu("Elimina", !offline, chiediElimina)))
        }
        if (esempio) {
            PillolaStato(TipoPillola.ESEMPIO)
        } else if (offline) {
            PillolaStato(TipoPillola.SENZA_RETE_BREVE)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sc.nome, style = L.testi.titoloPagina.copy(fontSize = 26.sp, lineHeight = 32.sp), color = c.testo, modifier = Modifier.semantics { heading() })
            Text(TestiSchema.sottoTitolo(sc), style = L.testi.secondario, color = c.tenue)
        }
    }
}

/** Quando qualcosa non si è trovato: calmo, mai rosso (aspetto.md §33.7). */
@Composable
private fun NotaMancanze(sc: Schema, offline: Boolean, azioni: AzioniSchema) {
    val c = L.colori
    val (mancanti, altri) = TestiSchema.mancanti(sc.contenuto?.copertura?.mancanti.orEmpty())
    val medio = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.avvisoTenue).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icone.Domanda, contentDescription = null, tint = c.avvisoTesto, modifier = Modifier.size(20.dp))
            Text("Non ho trovato tutto", style = L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = c.avvisoTesto)
        }
        sc.nota?.takeIf { it.isNotBlank() }?.let { Text(it, style = medio, color = c.avvisoTesto) }
        if (mancanti.isNotEmpty()) {
            Text("Questi punti nei tuoi documenti sono solo nominati, non spiegati:", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold), color = c.avvisoTesto)
            mancanti.forEach { Text("· $it", style = medio, color = c.avvisoTesto) }
            altri?.let { Text(it, style = medio, color = c.avvisoTesto) }
        }
        sc.contenuto?.avvisi.orEmpty().forEach { Text(it, style = medio, color = c.avvisoTesto) }
        PulsanteSecondario("Aggiungi materiale", { azioni.aggiungiMateriale(sc.materiaId) }, altezza = 48.dp, abilitato = !offline)
    }
}

@Composable
private fun SchedaSezione(
    se: SezioneSchema,
    sc: Schema,
    s: StatoSchema,
    vm: SchemaViewModel,
    m: ColoreMateria,
    offline: Boolean,
    fonti: Map<String, Fonte>,
    aperto: Int?,
    indiceParagrafoVisita: Int?,
    onTocca: (Int, Boolean) -> Unit,
    onNonHoCapito: (BloccoSchema) -> Unit,
    onFonti: (List<Fonte>) -> Unit,
    onAscolta: () -> Unit,
) {
    val c = L.colori
    val conSegno = remember(se) { SegniFonte.visibili(se.blocchi) }
    val testaImmagine = if (se.campi.isNotEmpty()) se.blocchi.indexOfFirst { it.tipo == "immagine" }.takeIf { it >= 0 } else null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.superficie).padding(8.dp).animateContentSize(androidx.compose.animation.core.tween(220)),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 1.dp)) { NumeroParte(se.n, m.tenue, m.testo) }
                Text(se.titolo, style = L.testi.titoloParte, color = c.testo, modifier = Modifier.semantics { heading() })
            }
            PillolaIcona("Ascolta questa parte", Icone.Lezioni, onAscolta, abilitato = !offline)
        }
        // Scheda opera: la prima foto e i campi insieme, in testa (aspetto.md §33.3).
        if (se.campi.isNotEmpty()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                testaImmagine?.let { BloccoImmagine(se.blocchi[it], s.foto[se.blocchi[it].n], vm, offline) }
                se.campi.forEach { campo ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(campo.nome.uppercase(), style = L.testi.etichetta, color = c.tenue, modifier = Modifier.width(92.dp).padding(top = 3.dp))
                        Text(campo.valore, style = L.testi.forte.copy(fontWeight = FontWeight.SemiBold), color = c.testo, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        se.blocchi.forEachIndexed { i, b ->
            if (i == testaImmagine) return@forEachIndexed
            val prima = se.blocchi.getOrNull(i - 1)
            if (i > 0) Spacer(Modifier.height(if (prima?.tipo == "paragrafo" && b.tipo == "paragrafo") 0.dp else 4.dp))
            val spiegabile = b.tipo in TestiSchema.SPIEGABILI
            val selezionato = aperto == i
            val elenco = SegniFonte.etichette(b.fonti, fonti)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (i == indiceParagrafoVisita) Modifier.bersaglio(Bersagli.PARAGRAFO) else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selezionato) c.fondoBlocco else Color.Transparent)
                    .then(
                        if (spiegabile) {
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = "Mostra cosa puoi fare") { onTocca(i, true) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(8.dp),
            ) {
                ContenutoBlocco(b, s, vm, m, offline)
                if (i in conSegno) SegnoFonte(elenco)
                AnimatedVisibility(
                    visible = selezionato,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
                ) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillolaIcona(
                            "Non ho capito", Icone.Domanda, { onNonHoCapito(b) },
                            Modifier.bersaglio(Bersagli.NON_HO_CAPITO),
                            fondo = c.accentoTenue, colore = c.accentoTesto, abilitato = !offline,
                        )
                        SegniFonte.pillola(elenco)?.let { testo ->
                            BoxWithConstraints(Modifier.weight(1f)) {
                                val massimo = maxWidth
                                Text(
                                    testo,
                                    style = L.testi.pillola,
                                    color = c.testo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = massimo).heightIn(min = 48.dp).clip(CircleShape).background(c.superficie2)
                                        .clickable(role = Role.Button) { onFonti(elenco) }.padding(horizontal = 14.dp, vertical = 15.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegnoFonte(elenco: List<Fonte>) {
    SegniFonte.riga(elenco)?.let { Text(it, style = L.testi.fonte, color = L.colori.tenue, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun ContenutoBlocco(b: BloccoSchema, s: StatoSchema, vm: SchemaViewModel, m: ColoreMateria, offline: Boolean) {
    val c = L.colori
    when (b.tipo) {
        "paragrafo" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.titolo?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.titoloSezione, color = c.testo) }
            TestoRicco(b.testo.orEmpty(), L.testi.lettura)
        }
        "punti" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.titolo?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.forte, color = c.testo) }
            b.voci.forEach { VoceAPunti(it, m.pieno) }
        }
        "passaggi" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            b.titolo?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.forte, color = c.testo, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
            b.voci.forEachIndexed { i, v ->
                val ultima = i == b.voci.lastIndex
                TestoRicco(
                    v,
                    L.testi.forte.copy(fontWeight = if (ultima) FontWeight.ExtraBold else FontWeight.SemiBold, lineHeight = 22.sp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fondoBlocco).padding(horizontal = 14.dp, vertical = 10.dp),
                )
                if (!ultima) Icon(Icone.Giu, contentDescription = "poi", tint = m.testo, modifier = Modifier.padding(vertical = 4.dp).size(18.dp))
            }
        }
        "definizione" -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(m.tenue).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(b.termine.orEmpty(), style = L.testi.forte.copy(fontWeight = FontWeight.ExtraBold), color = m.testo)
            TestoRicco(b.testo.orEmpty(), L.testi.testo.copy(fontWeight = FontWeight.Medium), m.titolo)
        }
        "tabella" -> BloccoTabella(b)
        "prof" -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.avvisoTenue)
                .drawBehind { drawRect(c.avvisoTesto, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icone.Verifica, contentDescription = null, tint = c.avvisoTesto, modifier = Modifier.size(16.dp))
                    Etichetta("Dal prof", colore = c.avvisoTesto)
                }
                TestoRicco(b.testo.orEmpty(), L.testi.testo.copy(fontWeight = FontWeight.SemiBold), c.avvisoTesto)
            }
        }
        "immagine" -> BloccoImmagine(b, s.foto[b.n], vm, offline)
    }
}

@Composable
private fun BloccoTabella(b: BloccoSchema) {
    val c = L.colori
    val colonne = maxOf(b.intestazione.size, b.righe.maxOfOrNull { it.size } ?: 0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        b.titolo?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.forte, color = c.testo) }
        BoxWithConstraints {
            if (maxWidth < 480.dp && colonne >= 4) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.righe.forEach { r ->
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fondoBlocco).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TestoRicco(r.firstOrNull().orEmpty(), L.testi.forte)
                            r.drop(1).forEachIndexed { i, v ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(b.intestazione.getOrNull(i + 1).orEmpty().uppercase(), style = L.testi.etichetta, color = c.tenue, modifier = Modifier.padding(top = 3.dp).width(92.dp))
                                    TestoRicco(v, L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            } else {
                Column {
                    if (b.intestazione.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth()) {
                            b.intestazione.forEach { Text(it.uppercase(), style = L.testi.etichetta, color = c.tenue, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) }
                        }
                    }
                    b.righe.forEach { r ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
                        Row(Modifier.fillMaxWidth()) {
                            for (i in 0 until colonne) {
                                TestoRicco(
                                    r.getOrNull(i).orEmpty(),
                                    L.testi.testoMedio.copy(fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium),
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BloccoImmagine(b: BloccoSchema, foto: FotoSchema?, vm: SchemaViewModel, offline: Boolean) {
    val c = L.colori
    val muove = LocalMovimenti.current
    LaunchedEffect(b.n) { vm.caricaFoto(b.n) }
    var intera by remember { mutableStateOf(false) }
    var conferma by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bitmap = foto?.immagine
        when {
            bitmap != null -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fondoBlocco), contentAlignment = Alignment.Center) {
                val alfa = if (foto.cerca && muove) {
                    rememberInfiniteTransition(label = "cerca").animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
                } else {
                    if (foto.cerca) 0.6f else 1f
                }
                // La foto nuova di "Cambia foto" entra in dissolvenza (aspetto.md §33.6).
                Crossfade(bitmap, animationSpec = tween(if (muove) 250 else 0), label = "foto") { quadro ->
                    Image(
                        quadro, b.didascalia,
                        Modifier.heightIn(max = 300.dp).aspectRatio(quadro.width.toFloat() / quadro.height.coerceAtLeast(1)).alpha(alfa)
                            .clickable(role = Role.Image, onClickLabel = "Apri la foto a tutto schermo") { intera = true },
                        contentScale = ContentScale.Fit,
                    )
                }
                if (foto.cerca) {
                    Text("Cerco un'altra foto", style = L.testi.pillola, color = Color.White, modifier = Modifier.clip(CircleShape).background(Color(0x8C14141E)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.Button, enabled = !foto.cerca) { menu = true }.semantics { contentDescription = "Altre azioni sulla foto" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x8C14141E)), contentAlignment = Alignment.Center) {
                            Icon(Icone.AltreAzioni, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Cambia foto", style = L.testi.testoMedio) }, enabled = !offline, onClick = { menu = false; vm.cambiaFoto(b.n) })
                        DropdownMenuItem(text = { Text("Togli foto", style = L.testi.testoMedio) }, enabled = !offline, onClick = { menu = false; conferma = true })
                    }
                }
            }
            foto?.errore == true -> Column(
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)).background(c.fondoBlocco),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Foto non disponibile", style = L.testi.secondario, color = c.tenue)
                PulsanteTesto("Riprova", { vm.caricaFoto(b.n, rinfresca = true) })
            }
            else -> SegnapostoAltezza(160)
        }
        b.didascalia?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold), color = c.testo) }
        b.credito?.takeIf { it.isNotBlank() }?.let { Text(it, style = L.testi.etichetta.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp), color = c.tenue) }
        if (conferma) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Toglierla?", style = L.testi.secondario.copy(fontWeight = FontWeight.Bold), color = c.testo, modifier = Modifier.weight(1f))
                PulsanteTesto("Togli", { conferma = false; vm.togliFoto(b.n) }, colore = c.testo)
                PulsanteTesto("Lascia", { conferma = false }, colore = c.tenue)
            }
        }
    }
    val bitmap = foto?.immagine
    if (intera && bitmap != null) FotoIntera(bitmap, b.didascalia) { intera = false }
}

/** La foto a tutto schermo: si ingrandisce con due dita, X per chiudere. */
@Composable
private fun FotoIntera(bitmap: ImageBitmap, descrizione: String?, onChiudi: () -> Unit) {
    SopraTutto {
        BackHandler(onBack = onChiudi)
        var scala by remember { mutableFloatStateOf(1f) }
        var spostamento by remember { mutableStateOf(Offset.Zero) }
        val stato = rememberTransformableState { zoom, sposta, _ ->
            scala = (scala * zoom).coerceIn(1f, 5f)
            spostamento = if (scala > 1f) spostamento + sposta else Offset.Zero
        }
        Box(Modifier.fillMaxSize().background(Color.Black).transformable(stato)) {
            Image(
                bitmap, descrizione,
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scala
                    scaleY = scala
                    translationX = spostamento.x
                    translationY = spostamento.y
                },
                contentScale = ContentScale.Fit,
            )
            PulsanteTondo(
                Icone.Chiudi, "Chiudi", onChiudi,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                fondo = Color(0x8C14141E), tinta = Color.White, dimensione = 48.dp, dimensioneIcona = 18.dp,
            )
        }
    }
}

@Composable
private fun BarraLettura(sezioni: List<SezioneSchema>, parte: Int, m: ColoreMateria, onApri: () -> Unit) {
    val c = L.colori
    val i = sezioni.indexOfFirst { it.n == parte }.coerceAtLeast(0)
    val titolo = sezioni.getOrNull(i)?.titolo.orEmpty()
    Column(Modifier.fillMaxWidth().background(c.superficie)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.riga))
        Row(
            Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(60.dp)
                .bersaglio(Bersagli.BARRA_LETTURA)
                .clickable(role = Role.Button, onClickLabel = "Apri l'indice", onClick = onApri)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icone.Indice, contentDescription = null, tint = c.testo, modifier = Modifier.size(20.dp))
            Text(
                "${i + 1} di ${sezioni.size}",
                style = L.testi.numeri.copy(fontWeight = FontWeight.Bold),
                color = m.testo,
                modifier = Modifier.clip(CircleShape).background(m.tenue).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(titolo, style = L.testi.testoMedio.copy(fontWeight = FontWeight.Bold), color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icone.Su, contentDescription = null, tint = c.tenue, modifier = Modifier.size(18.dp))
        }
    }
}

// --- Foglio "Che riassunto vuoi?" (aspetto.md §41.2) -----------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoglioRiassunto(
    sc: Schema,
    s: StatoSchema,
    m: ColoreMateria,
    offline: Boolean,
    onScegli: (FormaRiassunto) -> Unit,
    onChiudi: () -> Unit,
) {
    val c = L.colori
    val fatti = sc.riassunti?.fatti.orEmpty()
    FoglioFermo(onChiudi = onChiudi, titolo = "Che riassunto vuoi?") {
        FormaRiassunto.entries.forEach { f ->
            val esistente = fatti.firstOrNull { it.forma == f.codice }
            val attiva = esistente != null || !offline
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.superficie2)
                    .clickable(enabled = attiva, role = Role.Button) { onScegli(f) }
                    .alpha(if (attiva) 1f else 0.5f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Miniatura(f, m)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(f.nome, style = L.testi.forteGrande.copy(fontWeight = FontWeight.Bold), color = c.testo)
                    Text(f.frase, style = L.testi.secondario, color = c.tenue)
                }
                val destra = TestiRiassunto.statoForma(esistente?.stato)
                    ?: if (s.prova) "Prova" else Formati.euro(sc.riassunti?.stimaEur ?: 0.0)
                Text(destra, style = L.testi.piccolo.copy(fontWeight = FontWeight.Bold), color = c.tenue)
            }
        }
        s.erroreRiassunto?.let { RigaErrore(it) }
    }
}

/** Una miniatura 56×56 che fa vedere la forma: righe grige da 3 dp, pallini del colore della materia. */
@Composable
private fun Miniatura(f: FormaRiassunto, m: ColoreMateria) {
    val c = L.colori
    val grigio = c.tenue.copy(alpha = 0.45f)
    @Composable
    fun Linea(frazione: Float, pallino: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (pallino) Box(Modifier.size(4.dp).clip(CircleShape).background(m.pieno))
            Box(Modifier.fillMaxWidth(frazione).height(3.dp).clip(CircleShape).background(grigio))
        }
    }
    Column(
        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(c.fondoBlocco).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        when (f) {
            FormaRiassunto.PUNTI -> repeat(4) { i -> Linea(if (i % 2 == 0) 0.8f else 0.6f, true) }
            FormaRiassunto.DISCORSIVO -> {
                repeat(4) { Linea(1f, false) }
                Linea(0.55f, false)
            }
            FormaRiassunto.MISTO -> {
                repeat(2) { Linea(1f, false) }
                Linea(0.75f, true)
                Linea(0.6f, true)
            }
        }
    }
}
