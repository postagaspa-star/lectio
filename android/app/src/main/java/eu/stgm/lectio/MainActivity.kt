package eu.stgm.lectio

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.stgm.lectio.aggiornamento.Aggiornatore
import eu.stgm.lectio.aggiornamento.AppInPrimoPiano
import eu.stgm.lectio.aggiornamento.EsitoAggiornamento
import eu.stgm.lectio.aggiornamento.testo
import eu.stgm.lectio.dati.Impostazioni
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.dati.TemaScelto
import eu.stgm.lectio.lavori.PreparazioneWorker
import eu.stgm.lectio.lavori.scaricaLezione
import eu.stgm.lectio.lettore.CentroAscolto
import eu.stgm.lectio.lettore.Lettore
import eu.stgm.lectio.lettore.LettoreEsempio
import eu.stgm.lectio.logica.Condiviso
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.notifiche.Notificatore
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.FogliOspitati
import eu.stgm.lectio.ui.IconeBarraDiStato
import eu.stgm.lectio.ui.LocalOspiteFogli
import eu.stgm.lectio.ui.Messaggio
import eu.stgm.lectio.ui.Navigazione
import eu.stgm.lectio.ui.OspiteFogli
import eu.stgm.lectio.ui.Pagina
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.abbinamento.AbbinamentoScreen
import eu.stgm.lectio.ui.abbinamento.AbbinamentoViewModel
import eu.stgm.lectio.ui.altre.ComeAndataScreen
import eu.stgm.lectio.ui.altre.ComeAndataViewModel
import eu.stgm.lectio.ui.altre.ModificaSchedaScreen
import eu.stgm.lectio.ui.altre.ModificaSchedaViewModel
import eu.stgm.lectio.ui.altre.ProssimeScreen
import eu.stgm.lectio.ui.altre.ProssimeViewModel
import eu.stgm.lectio.ui.altre.TestoMaterialeScreen
import eu.stgm.lectio.ui.altre.TestoMaterialeViewModel
import eu.stgm.lectio.ui.comuni.BarraSchede
import eu.stgm.lectio.ui.gemini.GeminiScreen
import eu.stgm.lectio.ui.gemini.GeminiViewModel
import eu.stgm.lectio.ui.impostazioni.AzioniImpostazioni
import eu.stgm.lectio.ui.impostazioni.ImpostazioniScreen
import eu.stgm.lectio.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.lectio.ui.lettore.AzioniLettore
import eu.stgm.lectio.ui.lettore.LettoreScreen
import eu.stgm.lectio.ui.lettore.LettoreViewModel
import eu.stgm.lectio.ui.lettore.MiniLettore
import eu.stgm.lectio.ui.lezione.NuovaLezioneScreen
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.lezione.PreparazioneScreen
import eu.stgm.lectio.ui.lezione.PreparazioneViewModel
import eu.stgm.lectio.ui.lezioni.AzioniLezioni
import eu.stgm.lectio.ui.lezioni.LezioniScreen
import eu.stgm.lectio.ui.lezioni.LezioniViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.FoglioAggiungi
import eu.stgm.lectio.ui.materiale.PassoAggiunta
import eu.stgm.lectio.ui.materiale.PreparatoreFile
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materie.AzioniMateria
import eu.stgm.lectio.ui.materie.MateriaScreen
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.materie.MaterieScreen
import eu.stgm.lectio.ui.materie.MaterieViewModel
import eu.stgm.lectio.ui.oggi.AzioniOggi
import eu.stgm.lectio.ui.oggi.OggiScreen
import eu.stgm.lectio.ui.oggi.OggiViewModel
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.LectioTema
import eu.stgm.lectio.ui.tema.animazioniDiSistema
import eu.stgm.lectio.ui.visita.StratoVisita
import eu.stgm.lectio.visita.LocalVisita
import eu.stgm.lectio.visita.SchermataVisita
import eu.stgm.lectio.visita.Visita
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Quello che arriva da fuori (notifica o "Condividi"): l'app lo consuma una volta. */
    private val richiesta = mutableStateOf<Arrivo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val daiRecenti = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !daiRecenti) richiesta.value = intent?.let(::leggiArrivo)
        val app = application as LectioApp
        setContent {
            val tema by app.archivio.tema.collectAsState(initial = TemaScelto.SISTEMA)
            val meno by app.archivio.menoAnimazioni.collectAsState(initial = false)
            val scuro = when (tema) {
                TemaScelto.SISTEMA -> isSystemInDarkTheme()
                TemaScelto.CHIARO -> false
                TemaScelto.SCURO -> true
            }
            LectioTema(scuro = scuro, movimenti = animazioniDiSistema() && !meno) {
                IconeBarraDiStato(scure = !scuro)
                Radice(app, tema, meno, richiesta.value, { richiesta.value = null }, ::ricomincia)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppInPrimoPiano.visibile = true
    }

    override fun onStop() {
        AppInPrimoPiano.visibile = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        richiesta.value = leggiArrivo(intent)
    }

    private fun leggiArrivo(intent: Intent): Arrivo? {
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val file = if (intent.action == Intent.ACTION_SEND) {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                } else {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                }
                val testo = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { file.isEmpty() && it.isNotBlank() }
                val oggetto = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
                if (file.isEmpty() && testo == null) return null
                return Arrivo.Condivisione(Condiviso(testo, oggetto, file.map { it.toString() to intent.type }))
            }
        }
        if (intent.hasExtra(EXTRA_LEZIONE_LETTORE)) return Arrivo.Lettore(intent.getLongExtra(EXTRA_LEZIONE_LETTORE, -1))
        if (intent.hasExtra(EXTRA_LEZIONE_PREPARAZIONE)) return Arrivo.Preparazione(intent.getLongExtra(EXTRA_LEZIONE_PREPARAZIONE, -1))
        if (intent.getBooleanExtra(EXTRA_APRI_LETTORE, false)) return Arrivo.LettoreAperto
        return null
    }

    /** Un altro abbinamento o lo scollegamento: l'app riparte da zero. */
    private fun ricomincia() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    companion object {
        const val EXTRA_APRI_LETTORE = "apri_lettore"
        const val EXTRA_LEZIONE_LETTORE = "lezione_lettore"
        const val EXTRA_LEZIONE_PREPARAZIONE = "lezione_preparazione"
    }
}

/** Cosa ha aperto l'app da fuori. */
sealed interface Arrivo {
    data class Condivisione(val condiviso: Condiviso) : Arrivo
    data class Lettore(val lezioneId: Long) : Arrivo
    data class Preparazione(val lezioneId: Long) : Arrivo
    data object LettoreAperto : Arrivo
}

private sealed interface Avvio {
    data object Lettura : Avvio
    data class Pronto(val configurazione: Configurazione?) : Avvio
}

@Composable
private fun Radice(app: LectioApp, tema: TemaScelto, meno: Boolean, arrivo: Arrivo?, consumato: () -> Unit, ricomincia: () -> Unit) {
    val avvio by remember { app.archivio.flusso.map<Configurazione?, Avvio> { Avvio.Pronto(it) } }.collectAsState(initial = Avvio.Lettura)
    val perso by app.archivio.abbinamentoPerso.collectAsState(initial = false)
    var eraAbbinato by remember { mutableStateOf(false) }
    LaunchedEffect(avvio) {
        val a = avvio as? Avvio.Pronto ?: return@LaunchedEffect
        if (a.configurazione != null) eraAbbinato = true else if (eraAbbinato) ricomincia()
    }
    when (val a = avvio) {
        Avvio.Lettura -> Box(Modifier.fillMaxSize().background(L.colori.fondo))
        is Avvio.Pronto -> if (a.configurazione == null) {
            Box(Modifier.fillMaxSize().background(L.colori.fondo)) {
                val vm: AbbinamentoViewModel = viewModel { AbbinamentoViewModel(app.archivio, prima = { server, id -> app.svuotaSeAltroServer(server, id) }) }
                LaunchedEffect(Unit) { vm.proponiIndirizzo(app.archivio.ultimoServer()) }
                AbbinamentoScreen(vm, perso)
            }
        } else {
            App(app, a.configurazione, tema, meno, arrivo, consumato, ricomincia)
        }
    }
}

@Composable
private fun App(
    app: LectioApp,
    configurazione: Configurazione,
    tema: TemaScelto,
    meno: Boolean,
    arrivo: Arrivo?,
    consumato: () -> Unit,
    ricomincia: () -> Unit,
) {
    val contesto = LocalContext.current
    val ambito = rememberCoroutineScope()
    var codice by rememberSaveable { mutableStateOf(Navigazione().codifica()) }
    val nav = remember(codice) { Navigazione.decodifica(codice) }
    var direzioneAvanti by remember { mutableStateOf(true) }
    val vai: (Navigazione) -> Unit = { nuova ->
        direzioneAvanti = nuova.profondita >= nav.profondita
        codice = nuova.codifica()
    }
    val messaggi = remember { SnackbarHostState() }
    val ospite = remember { OspiteFogli() }
    val preparatore = remember { PreparatoreFile(contesto) }

    // --- La visita guidata in modalità esempio ------------------------------------------------
    val visita = remember { Visita() }
    val esempio = visita.attiva
    val esempioDati = remember(visita.giro) { SorgenteEsempio() }
    val lettoreEsempio = remember(visita.giro) { LettoreEsempio(correttore = esempioDati::rispondi) }
    val sorgente: Sorgente = if (esempio) esempioDati else app.sorgente
    val lettore: Lettore = if (esempio) lettoreEsempio else app.lettore
    val chiave = if (esempio) "esempio-${visita.giro}" else "vero"
    var nuovaMateria by remember { mutableStateOf(false) }
    val aggiuntaVera: AggiuntaViewModel = viewModel(key = "aggiunta-vero") { AggiuntaViewModel(app.sorgente, preparatore, createSavedStateHandle()) }
    val aggiuntaEsempio: AggiuntaViewModel = viewModel(key = "aggiunta-esempio-${visita.giro}") { AggiuntaViewModel(esempioDati, null) }
    val aggiunta = if (esempio) aggiuntaEsempio else aggiuntaVera
    val statoAggiunta by aggiunta.stato.collectAsStateWithLifecycle()

    visita.vaiA = { schermata ->
        val storia = SorgenteEsempio.MATERIA_STORIA
        if (schermata == SchermataVisita.FOGLIO_AGGIUNGI) aggiuntaEsempio.apri(RichiestaAggiunta(storia)) else aggiuntaEsempio.chiudi()
        when (schermata) {
            SchermataVisita.OGGI -> vai(Navigazione())
            SchermataVisita.MATERIA, SchermataVisita.FOGLIO_AGGIUNGI -> vai(Navigazione(Scheda.OGGI, listOf(Pagina.Materia(storia))))
            SchermataVisita.NUOVA_LEZIONE -> vai(Navigazione(Scheda.OGGI, listOf(Pagina.Materia(storia), Pagina.NuovaLezione(storia, 3))))
            SchermataVisita.LETTORE -> {
                val l = esempioDati.lezioneCompleta()
                // Al passo 8 (pratico) si riparte sempre fermi alla domanda; all'ultimo passo no,
                // così resta la correzione appena vista.
                if (l != null && (visita.corrente.pratico || lettoreEsempio.stato.value.lezione == null)) {
                    lettoreEsempio.carica(l, esempioDati.materiaBreve(l.materiaId), 0.0, false)
                    lettoreEsempio.fermaAllaDomanda(2)
                }
                vai(Navigazione(Scheda.OGGI, listOf(Pagina.Lettore(SorgenteEsempio.LEZIONE_STORIA))))
            }
        }
    }
    visita.allaFine = {
        lettoreEsempio.ferma()
        aggiuntaEsempio.chiudi()
        vai(Navigazione())
        ambito.launch { app.archivio.segnaVisitaVista() }
    }
    // Al primo avvio dopo l'abbinamento parte da sola, una volta (aspetto.md §12).
    val vista by app.archivio.visitaVista.collectAsState(initial = true)
    LaunchedEffect(vista) {
        if (!vista) {
            app.archivio.segnaVisitaVista()
            visita.inizia()
        }
    }

    // --- All'apertura: invii rimasti, lezioni in preparazione, novità ---------------------------
    LaunchedEffect(Unit) {
        app.pianificaInvii()
        val l = app.client.lezioni()
        if (l is Esito.Ok && l.dato.any { it.inLavoro }) PreparazioneWorker.pianifica(contesto)
        val imp = app.sorgente.impostazioni().dato
        if (imp != null) ricordaImpostazioni(app, imp)
        // La salute con il token porta l'indirizzo da fuori casa scelto sul server.
        app.sorgente.salute()
        val nuove = eu.stgm.lectio.lavori.novitaDaMostrare({ app.client.novita(it) }, app.archivio)
        nuove.lastOrNull()?.let { messaggi.showSnackbar(it.testo) }
    }

    // --- Arrivi da fuori ------------------------------------------------------------------------
    LaunchedEffect(arrivo) {
        val a = arrivo ?: return@LaunchedEffect
        consumato()
        if (visita.attiva) visita.salta()
        when (a) {
            is Arrivo.Condivisione -> aggiuntaVera.apri(RichiestaAggiunta(
                materiaId = null,
                passo = PassoAggiunta.FILE,
                tipo = TipoMateriale.VERIFICA,
                file = a.condiviso.file.map { (u, tipo) -> preparatore.leggi(Uri.parse(u), tipo) },
                testo = a.condiviso.testo,
                oggetto = a.condiviso.oggetto,
                condivisa = true,
            ))
            is Arrivo.Lettore -> if (a.lezioneId >= 0) vai(Navigazione(nav.scheda).apri(Pagina.Lettore(a.lezioneId)))
            is Arrivo.Preparazione -> if (a.lezioneId >= 0) vai(Navigazione(nav.scheda).apri(Pagina.Preparazione(a.lezioneId)))
            Arrivo.LettoreAperto -> CentroAscolto.stato.value.lezione?.let { vai(nav.apri(Pagina.Lettore(it.id))) }
        }
    }

    val dopoIndietro = nav.indietro()
    BackHandler(enabled = dopoIndietro != null && !visita.attiva) { dopoIndietro?.let(vai) }
    // Durante la visita il tasto indietro di Android fa "Indietro" (o la chiude dal primo passo).
    BackHandler(enabled = visita.attiva) { if (visita.puoTornare) visita.indietro() else visita.salta() }
    val indietro: () -> Unit = { vai(dopoIndietro ?: Navigazione()) }

    val senzaRete by app.sorgente.senzaRete.collectAsStateWithLifecycle()
    val offline = senzaRete && !esempio
    val scaricate by app.audio.scaricate.collectAsStateWithLifecycle()
    val inScaricamento by app.audio.inScaricamento.collectAsStateWithLifecycle()
    val statoLettore by lettore.stato.collectAsStateWithLifecycle()

    val scarica: (Long) -> Unit = { id ->
        ambito.launch {
            val e = scaricaLezione(app, id)
            if (e !is Esito.Ok) messaggi.showSnackbar(e.messaggio ?: "Non sono riuscito a scaricarla.")
        }
    }
    val togli: (Long) -> Unit = { id ->
        ambito.launch {
            app.lettore.liberaFile(id, elimina = false)
            app.audio.togli(id)
        }
    }
    /** Lezioni che spariscono anche dal server (Elimina, Elimina materia): via audio e testi dal telefono. */
    val eliminaLocale: suspend (List<Long>) -> Unit = { ids ->
        ids.forEach { id ->
            app.lettore.liberaFile(id, elimina = true)
            app.audio.togli(id)
            app.cache.cancella("lezione-$id")
        }
    }
    val apriLettore: (Long) -> Unit = { id -> vai(nav.apri(Pagina.Lettore(id))) }
    val nuovaLezioneDa: (Long, Long?) -> Unit = { m, e -> vai(nav.apri(Pagina.NuovaLezione(m, e))) }
    val sceltaFileGemini = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            aggiuntaVera.apri(RichiestaAggiunta(passo = PassoAggiunta.FILE, file = uris.map { preparatore.leggi(it) }, condivisa = true))
        }
    }

    CompositionLocalProvider(LocalVisita provides visita, LocalOspiteFogli provides ospite) {
        Box(Modifier.fillMaxSize().background(L.colori.fondo)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).fillMaxSize().then(if (nav.inCima != null) Modifier.navigationBarsPadding() else Modifier),
                ) {
                    val muove = eu.stgm.lectio.ui.tema.LocalMovimenti.current
                    AnimatedContent(
                        targetState = codice,
                        transitionSpec = { transizione(muove, direzioneAvanti) },
                        label = "pagine",
                    ) { c ->
                        val n = Navigazione.decodifica(c)
                        Pagine(
                            n, app, sorgente, lettore, chiave, esempio, offline, scaricate, inScaricamento, tema, meno, configurazione,
                            vai = vai, indietro = indietro, apriLettore = apriLettore, nuovaLezioneDa = nuovaLezioneDa,
                            aggiungi = { r -> aggiunta.apri(r) }, scarica = scarica, togli = togli, elimina = eliminaLocale,
                            sceltaFile = { sceltaFileGemini.launch(eu.stgm.lectio.logica.Condivisione.TIPI_SELETTORE) },
                            nuovaMateria = nuovaMateria, nuovaMateriaAperta = { nuovaMateria = false },
                            chiediNuovaMateria = { nuovaMateria = true; vai(Navigazione(Scheda.MATERIE)) },
                            visita = visita, ricomincia = ricomincia,
                        )
                    }
                }
                if (nav.inCima == null) {
                    if (statoLettore.lezione != null) {
                        MiniLettore(statoLettore, { statoLettore.lezione?.id?.let(apriLettore) }, {
                            if (eu.stgm.lectio.ui.lettore.playDelMiniLettoreApre(statoLettore)) statoLettore.lezione?.id?.let(apriLettore) else lettore.playPausa()
                        })
                    }
                    BarraSchede(nav.scheda) { vai(nav.scegli(it)) }
                }
            }
            SnackbarHost(messaggi, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)) { Messaggio(it) }

            if (statoAggiunta.aperto) {
                FoglioAggiungi(
                    aggiunta, if (esempio) null else preparatore,
                    onChiudi = { aggiunta.chiudi() },
                    onGemini = { if (!visita.attiva) { aggiunta.chiudi(); vai(Navigazione(Scheda.GEMINI)) } },
                    onFatto = { materiaId ->
                        aggiunta.chiudi()
                        vai(Navigazione(nav.scheda, nav.pagine.filterNot { it is Pagina.Materia } + Pagina.Materia(materiaId)))
                        ambito.launch { messaggi.showSnackbar("Aggiunto: il server lo sta leggendo") }
                    },
                    senzaRete = offline,
                )
            }
            FogliOspitati(ospite)
            StratoVisita(visita)
        }
    }

    // La richiesta degli avvisi aspetta la fine della visita: una cosa alla volta.
    if (vista && !visita.attiva) RichiestaNotifiche(app)
}

private fun transizione(muove: Boolean, avanti: Boolean): ContentTransform {
    if (!muove) return EnterTransition.None togetherWith ExitTransition.None
    val verso = if (avanti) 1 else -1
    return (slideInHorizontally(tween(300)) { it / 6 * verso } + fadeIn(tween(250))) togetherWith
        (slideOutHorizontally(tween(250)) { -it / 8 * verso } + fadeOut(tween(200)))
}

/** Le copie locali delle impostazioni che servono al lettore anche senza rete. */
suspend fun ricordaImpostazioni(app: LectioApp, imp: Impostazioni) {
    app.archivio.salvaFermate(imp.fermatiAlleDomande)
    app.archivio.salvaRisposteScritte(imp.risposteScritte)
    CentroAscolto.fermateAttive = imp.fermatiAlleDomande
    CentroAscolto.risposteScritte = imp.risposteScritte
    CentroAscolto.servizio?.cambiaFermate(imp.fermatiAlleDomande)
    if (app.archivio.velocita() == null) CentroAscolto.velocita = imp.velocita.toFloat()
}

@Composable
private fun Pagine(
    nav: Navigazione,
    app: LectioApp,
    sorgente: Sorgente,
    lettore: Lettore,
    chiave: String,
    esempio: Boolean,
    offline: Boolean,
    scaricate: Set<Long>,
    inScaricamento: Map<Long, Float>,
    tema: TemaScelto,
    meno: Boolean,
    configurazione: Configurazione,
    vai: (Navigazione) -> Unit,
    indietro: () -> Unit,
    apriLettore: (Long) -> Unit,
    nuovaLezioneDa: (Long, Long?) -> Unit,
    aggiungi: (RichiestaAggiunta) -> Unit,
    scarica: (Long) -> Unit,
    togli: (Long) -> Unit,
    elimina: suspend (List<Long>) -> Unit,
    sceltaFile: () -> Unit,
    nuovaMateria: Boolean,
    nuovaMateriaAperta: () -> Unit,
    chiediNuovaMateria: () -> Unit,
    visita: Visita,
    ricomincia: () -> Unit,
) {
    val contesto = LocalContext.current
    val ambito = rememberCoroutineScope()
    when (val p = nav.inCima) {
        is Pagina.Materia -> {
            val vm: MateriaViewModel = viewModel(key = "materia-${p.id}-$chiave") {
                MateriaViewModel(sorgente, p.id, togliLezioniLocali = { ids -> if (!esempio) elimina(ids) })
            }
            MateriaScreen(
                vm,
                AzioniMateria(
                    indietro = indietro,
                    modificaScheda = { vai(nav.apri(Pagina.ModificaScheda(p.id))) },
                    aggiungi = { tipo -> aggiungi(RichiestaAggiunta(p.id, if (tipo != null) PassoAggiunta.SCEGLI else PassoAggiunta.SCEGLI, tipo ?: TipoMateriale.VERIFICA)) },
                    apriMateriale = { vai(nav.apri(Pagina.TestoMateriale(it))) },
                    nuovaLezione = { e -> nuovaLezioneDa(p.id, e) },
                    apriLezione = { l -> if (l.pronta) apriLettore(l.id) else vai(nav.apri(Pagina.Preparazione(l.id))) },
                    ripassa = { r -> vai(nav.apri(Pagina.Lettore(r.lezioneId, r.domandaId))) },
                ),
                esempio, offline, scaricate,
            )
        }
        is Pagina.ModificaScheda -> {
            val vm: ModificaSchedaViewModel = viewModel(key = "scheda-${p.materiaId}-$chiave") { ModificaSchedaViewModel(sorgente, p.materiaId) }
            ModificaSchedaScreen(vm, indietro)
        }
        is Pagina.TestoMateriale -> {
            val vm: TestoMaterialeViewModel = viewModel(key = "materiale-${p.id}-$chiave") { TestoMaterialeViewModel(sorgente, p.id) }
            TestoMaterialeScreen(vm, indietro, offline)
        }
        is Pagina.NuovaLezione -> {
            val vm: NuovaLezioneViewModel = viewModel(key = "nuova-${p.materiaId}-${p.eventoId}-$chiave") { NuovaLezioneViewModel(sorgente, p.materiaId, p.eventoId) }
            NuovaLezioneScreen(vm, indietro, onCreata = { l ->
                if (!esempio) PreparazioneWorker.pianifica(contesto)
                vai(nav.sostituisci(Pagina.Preparazione(l.id)))
            }, esempio, offline)
        }
        is Pagina.Preparazione -> {
            val vm: PreparazioneViewModel = viewModel(key = "prep-${p.lezioneId}-$chiave") { PreparazioneViewModel(sorgente, p.lezioneId) }
            PreparazioneScreen(vm, indietro, onAscolta = { vai(nav.sostituisci(Pagina.Lettore(it))) }, onInLavoro = { if (!esempio) PreparazioneWorker.pianifica(contesto) })
        }
        is Pagina.Lettore -> {
            val vm: LettoreViewModel = viewModel(key = "lettore-$chiave") {
                LettoreViewModel(sorgente, lettore, { app.posizioni.inSospeso(it) }, { app.posizioni.dimentica(it) }, createSavedStateHandle())
            }
            // Un id per QUESTA apertura della pagina, salvato con la pagina: dopo una
            // rotazione o la chiusura da parte di Android è lo stesso, e il lettore sa
            // che non deve ripartire da capo né avviarsi da solo (punto 3).
            val idPagina = rememberSaveable(p) { java.util.UUID.randomUUID().toString() }
            LaunchedEffect(p) {
                vm.apriPagina(p.lezioneId, p.domandaId, idPagina)
                // Su una rete senza consumo (Wi-Fi di casa) si scarica da sola, per ascoltarla in giro.
                if (!esempio && p.lezioneId !in scaricate) eu.stgm.lectio.lavori.scaricaSeWifi(contesto, p.lezioneId)
            }
            LettoreScreen(
                vm,
                AzioniLettore(chiudi = indietro, scarica = scarica, togli = togli, comeAndata = { vai(nav.apri(Pagina.ComeAndata(it))) }),
                scaricata = p.lezioneId in scaricate,
                scaricamento = inScaricamento[p.lezioneId],
                esempio = esempio,
                senzaRete = offline,
            )
        }
        is Pagina.ComeAndata -> {
            val vm: ComeAndataViewModel = viewModel(key = "esito-${p.eventoId}-$chiave") { ComeAndataViewModel(sorgente, p.eventoId) }
            ComeAndataScreen(vm, indietro, onCaricaVerifica = { m -> aggiungi(RichiestaAggiunta(m, PassoAggiunta.SCEGLI, TipoMateriale.VERIFICA)) }, offline)
        }
        Pagina.Prossime -> {
            val vm: ProssimeViewModel = viewModel(key = "prossime-$chiave") { ProssimeViewModel(sorgente) }
            ProssimeScreen(vm, indietro) { vai(nav.apri(Pagina.Materia(it))) }
        }
        Pagina.Impostazioni -> {
            val vm: ImpostazioniViewModel = viewModel(key = "impostazioni-$chiave") {
                ImpostazioniViewModel(
                    sorgente,
                    versioneNuova = { if (esempio) null else Aggiornatore(contesto).versioneDisponibile()?.nome },
                    aggiorna = { if (esempio) "" else Aggiornatore(contesto).controlla(forza = true).testo() },
                    ricordaLocale = { if (!esempio) ricordaImpostazioni(app, it) },
                    cambiaVelocitaLocale = { v ->
                        if (!esempio) {
                            app.archivio.salvaVelocita(v)
                            CentroAscolto.velocita = v
                        }
                    },
                )
            }
            ImpostazioniScreen(
                vm,
                AzioniImpostazioni(
                    indietro = indietro,
                    tema = { ambito.launch { app.archivio.cambiaTema(it) } },
                    menoAnimazioni = { ambito.launch { app.archivio.cambiaMenoAnimazioni(it) } },
                    visita = { visita.inizia() },
                    scollega = {
                        ambito.launch {
                            app.lettore.ferma()
                            app.archivio.dimentica()
                            app.svuotaDatiLocali()
                            ricomincia()
                        }
                    },
                    licenza = { runCatching { contesto.assets.open("licenze/figtree-OFL.txt").bufferedReader().readText() }.getOrDefault("") },
                    cambiaFuoriCasa = { testo ->
                        if (testo.isBlank()) {
                            // Vuoto: si torna a quello che manda il server.
                            ambito.launch { app.archivio.cambiaFuoriCasa(null) }
                            null
                        } else when (val r = eu.stgm.lectio.rete.IndirizzoServer.normalizza(testo)) {
                            is eu.stgm.lectio.rete.IndirizzoServer.Risultato.NonValido -> r.motivo
                            is eu.stgm.lectio.rete.IndirizzoServer.Risultato.Valido ->
                                if (!r.url.startsWith("https://")) {
                                    "Da fuori casa serve l'indirizzo https di Tailscale."
                                } else {
                                    ambito.launch { app.archivio.cambiaFuoriCasa(r.url) }
                                    null
                                }
                        }
                    },
                ),
                server = configurazione.server, fuoriCasa = configurazione.fuoriCasa, tema = tema, menoAnimazioni = meno, versione = BuildConfig.VERSION_NAME, senzaRete = offline,
            )
        }
        Pagina.Abbinamento -> {
            val vm: AbbinamentoViewModel = viewModel(key = "abbina") { AbbinamentoViewModel(app.archivio, prima = { server, id -> app.svuotaSeAltroServer(server, id) }) }
            AbbinamentoScreen(vm, false, onAnnulla = indietro)
        }
        null -> when (nav.scheda) {
            Scheda.OGGI -> {
                val vm: OggiViewModel = viewModel(key = "oggi-$chiave") { OggiViewModel(sorgente) }
                OggiScreen(
                    vm,
                    AzioniOggi(
                        impostazioni = { vai(nav.apri(Pagina.Impostazioni)) },
                        apriMateria = { vai(nav.apri(Pagina.Materia(it))) },
                        ascolta = apriLettore,
                        preparazione = { vai(nav.apri(Pagina.Preparazione(it))) },
                        prepara = nuovaLezioneDa,
                        racconta = { vai(nav.apri(Pagina.ComeAndata(it))) },
                        vediTutte = { vai(nav.apri(Pagina.Prossime)) },
                        nuovaMateria = chiediNuovaMateria,
                        apriMaterie = { vai(Navigazione(Scheda.MATERIE)) },
                        visita = { visita.inizia() },
                    ),
                    esempio, offline, scaricate,
                )
            }
            Scheda.MATERIE -> {
                val vm: MaterieViewModel = viewModel(key = "materie-$chiave") { MaterieViewModel(sorgente) }
                MaterieScreen(vm, { vai(nav.apri(Pagina.Materia(it))) }, esempio, offline, nuovaMateria, nuovaMateriaAperta)
            }
            Scheda.LEZIONI -> {
                val vm: LezioniViewModel = viewModel(key = "lezioni-$chiave") { LezioniViewModel(sorgente) }
                LezioniScreen(
                    vm,
                    AzioniLezioni(
                        apri = { l -> if (l.pronta) apriLettore(l.id) else vai(nav.apri(Pagina.Preparazione(l.id))) },
                        scarica = scarica, togli = togli, elimina = { id -> ambito.launch { elimina(listOf(id)) } },
                        nuova = { vai(Navigazione(Scheda.MATERIE)) },
                    ),
                    scaricate, esempio, offline,
                )
            }
            Scheda.GEMINI -> {
                val vm: GeminiViewModel = viewModel(key = "gemini-$chiave") { GeminiViewModel(sorgente) }
                GeminiScreen(vm, sceltaFile, esempio, offline)
            }
        }
    }
}

/**
 * Android 13 e oltre: prima una spiegazione, poi la richiesta di sistema. Una volta
 * sola; senza permesso la lezione pronta si vede comunque aprendo l'app.
 */
@Composable
private fun RichiestaNotifiche(app: LectioApp) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val contesto = LocalContext.current
    val ambito = rememberCoroutineScope()
    val fatta by app.archivio.richiestaNotificheFatta.collectAsState(initial = null)
    var mostra by rememberSaveable { mutableStateOf(false) }
    val lancio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(fatta) {
        if (fatta == false && !Notificatore.puoNotificare(contesto)) mostra = true
    }
    if (!mostra) return
    val chiudi = {
        mostra = false
        ambito.launch { app.archivio.registraRichiestaNotificheFatta() }
        Unit
    }
    Conferma(
        titolo = "Avvisi di Lectio",
        testo = "Lectio ti avvisa quando una lezione è pronta e ti mostra i comandi del lettore a schermo spento. Android ora ti chiede il permesso.",
        conferma = "Va bene",
        onConferma = {
            chiudi()
            lancio.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onAnnulla = chiudi,
        pericolosa = false,
    )
}
