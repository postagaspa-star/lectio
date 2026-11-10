package eu.stgm.lectio.ui.materie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.Materiale
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.MettiQui
import eu.stgm.lectio.logica.SceltaMateriale
import eu.stgm.lectio.logica.TestiCartelle
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.CampoBordato
import eu.stgm.lectio.ui.Conferma
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.FoglioFermo
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.MARGINE
import eu.stgm.lectio.ui.MenuPuntini
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.PulsanteTesto
import eu.stgm.lectio.ui.PulsanteTondo
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaConferma
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.Scheda
import eu.stgm.lectio.ui.TitoloSezione
import eu.stgm.lectio.ui.VoceMenu
import eu.stgm.lectio.ui.ZonaPulsanteFisso
import eu.stgm.lectio.ui.comuni.PilloleStato
import eu.stgm.lectio.ui.lezione.RigaCasella
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.ui.tema.materia
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

// --- Le cartelle (v1.6, contratto §27, aspetto.md §42) ---------------------------------------------

/** Il foglio "Metti dei file qui" aperto (aspetto.md §42.4). */
data class StatoMetti(
    val scelti: Set<Long> = emptySet(),
    /** I proposti del server non sono ancora arrivati: caselle spente per un attimo. */
    val inAttesa: Boolean = true,
    val cerca: String = "",
    val inCorso: Boolean = false,
    val errore: String? = null,
)

data class StatoCartella(
    val cartella: Cartella? = null,
    val materia: Materia? = null,
    /** Tutte le cartelle della materia (i nomi nel foglio Metti). */
    val cartelle: List<Cartella> = emptyList(),
    /** Tutto il materiale della materia: dentro e fuori da questa cartella. */
    val materiale: List<Materiale> = emptyList(),
    val caricata: Boolean = false,
    val errore: String? = null,
    /** L'errore di un'azione (Rinomina, Elimina), per 4 s. */
    val messaggio: String? = null,
    /** La riga conferma per 4 s ("Fatto: 3 file in Arte greca"). */
    val conferma: String? = null,
    /** Dopo "Elimina": la frase da mostrare nella pagina della materia. */
    val eliminata: String? = null,
    val metti: StatoMetti? = null,
    val oggi: LocalDate = LocalDate.now(),
) {
    val nome: String get() = cartella?.nome.orEmpty()

    /** I file di questa cartella, nell'ordine della materia (più recente prima). */
    val dentro: List<Materiale> get() = cartella?.let { c -> materiale.filter { it.cartellaId == c.id } }.orEmpty()

    /** Quanti file ha: dal materiale se c'è, altrimenti dal conteggio del server. */
    val quanti: Int get() = if (caricata) dentro.size else cartella?.file ?: 0

    /** Quello che si può mettere qui (fuori o in altre cartelle). */
    val candidati: List<Materiale> get() = cartella?.let { MettiQui.candidati(materiale, it.id) }.orEmpty()
}

class CartellaViewModel(
    private val sorgente: Sorgente,
    val cartellaId: Long,
    val materiaId: Long,
    private val oggi: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoCartella())
    val stato: StateFlow<StatoCartella> = _stato.asStateFlow()
    private var giroMetti = 0

    fun carica() {
        viewModelScope.launch {
            val materie = sorgente.materie()
            val cartelle = sorgente.cartelle(materiaId)
            val materiale = sorgente.materiale(materiaId)
            _stato.update {
                val elenco = cartelle.dato
                val questa = elenco?.firstOrNull { c -> c.id == cartellaId }
                it.copy(
                    materia = materie.dato?.firstOrNull { m -> m.id == materiaId } ?: it.materia,
                    cartelle = elenco ?: it.cartelle,
                    cartella = if (elenco != null) questa else it.cartella,
                    materiale = materiale.dato ?: it.materiale,
                    caricata = it.caricata || (materiale.dato != null && elenco != null),
                    errore = when {
                        elenco != null && questa == null -> "Questa cartella non c'è più."
                        else -> listOf(cartelle, materiale).firstNotNullOfOrNull { l -> l.errore.takeIf { l.dato == null } }
                    },
                    oggi = oggi(),
                )
            }
        }
    }

    /** Mentre il server legge un file di questa cartella si ricontrolla ogni 4 s (come la materia). */
    val inAttesa: Boolean get() = _stato.value.dentro.any { it.stato == "in_lettura" }

    fun riprovaMateriale(id: Long) {
        viewModelScope.launch {
            val e = sorgente.riprovaMateriale(id)
            _stato.update { it.copy(messaggio = e.messaggio) }
            carica()
        }
    }

    /** Rinomina: [fatto] riceve l'errore del server da mostrare sotto il campo (null = riuscita). */
    fun rinomina(nome: String, fatto: (String?) -> Unit) {
        viewModelScope.launch {
            val e = sorgente.rinominaCartella(cartellaId, TestiCartelle.pulisciNome(nome))
            if (e is Esito.Ok) {
                _stato.update { s -> s.copy(cartella = e.dato) }
                carica()
            }
            fatto(e.messaggio)
        }
    }

    /** Elimina la cartella; con [conIFile] anche il suo materiale (contratto §27). */
    fun elimina(conIFile: Boolean) {
        viewModelScope.launch {
            when (val e = sorgente.eliminaCartella(cartellaId, conIFile)) {
                is Esito.Ok -> _stato.update { it.copy(eliminata = TestiCartelle.eliminata(e.dato.fileEliminati)) }
                else -> _stato.update { it.copy(messaggio = e.messaggio) }
            }
        }
    }

    fun messaggioVisto() = _stato.update { it.copy(messaggio = null) }
    fun confermaVista() = _stato.update { it.copy(conferma = null) }

    // --- Metti dei file qui ----------------------------------------------------------------------

    /** Apre il foglio: le caselle partono dai proposti del server (§27.4); finché arrivano sono spente. */
    fun apriMetti() {
        if (_stato.value.cartella == null) return
        val giro = ++giroMetti
        _stato.update { it.copy(metti = StatoMetti()) }
        viewModelScope.launch {
            // Un server che non sa proporre (o non risponde): nessuna casella spuntata, si sceglie a mano.
            val proposti = (sorgente.proposti(cartellaId) as? Esito.Ok)?.dato.orEmpty()
            if (giro != giroMetti) return@launch
            _stato.update { s ->
                val m = s.metti ?: return@update s
                s.copy(metti = m.copy(scelti = MettiQui.iniziali(proposti, s.candidati), inAttesa = false))
            }
        }
    }

    fun toccaMetti(id: Long) = _stato.update { s ->
        val m = s.metti ?: return@update s
        if (m.inAttesa || m.inCorso) return@update s
        s.copy(metti = m.copy(scelti = if (id in m.scelti) m.scelti - id else m.scelti + id, errore = null))
    }

    fun cercaMetti(t: String) = _stato.update { s -> s.metti?.let { s.copy(metti = it.copy(cerca = t.take(80))) } ?: s }

    fun chiudiMetti() {
        giroMetti++
        _stato.update { it.copy(metti = null) }
    }

    /** "Metti qui N file": sposta, chiude il foglio, rilegge e mostra la riga conferma per 4 s. */
    fun metti() {
        val s = _stato.value
        val m = s.metti ?: return
        val ids = MettiQui.daMandare(s.candidati, s.cartelle, m.scelti)
        if (ids.isEmpty() || m.inCorso || m.inAttesa) return
        _stato.update { it.copy(metti = m.copy(inCorso = true, errore = null)) }
        viewModelScope.launch {
            when (val e = sorgente.sposta(materiaId, ids, cartellaId)) {
                is Esito.Ok -> {
                    giroMetti++
                    _stato.update { it.copy(metti = null, conferma = TestiCartelle.fatto(e.dato.spostati, it.nome)) }
                    carica()
                }
                else -> _stato.update { st -> st.copy(metti = st.metti?.copy(inCorso = false, errore = e.messaggio)) }
            }
        }
    }
}

data class AzioniCartella(
    val indietro: () -> Unit = {},
    /** Il foglio Aggiungi con questa cartella già scelta (aspetto.md §42.6). */
    val aggiungi: () -> Unit = {},
    val apriMateriale: (Long) -> Unit = {},
    /** Cartella eliminata: si torna alla materia con questa frase. */
    val eliminata: (String) -> Unit = {},
)

/** La pagina di una cartella (aspetto.md §42.2). [ricarica] come in [MateriaScreen]. */
@Composable
fun CartellaScreen(vm: CartellaViewModel, azioni: AzioniCartella, esempio: Boolean, senzaRete: Boolean, ricarica: Int = 0) {
    val s by vm.stato.collectAsStateWithLifecycle()
    LaunchedEffect(vm, ricarica) { vm.carica() }
    LaunchedEffect(s.materiale) {
        while (vm.inAttesa) {
            delay(4_000)
            vm.carica()
        }
    }
    LaunchedEffect(s.messaggio) {
        if (s.messaggio != null) {
            delay(4_000)
            vm.messaggioVisto()
        }
    }
    LaunchedEffect(s.conferma) {
        if (s.conferma != null) {
            delay(4_000)
            vm.confermaVista()
        }
    }
    LaunchedEffect(s.eliminata) { s.eliminata?.let(azioni.eliminata) }
    val c = L.colori
    val colori = materia(s.materia?.colore)
    // Senza rete (e fuori dall'esempio) Aggiungi, Metti, Rinomina ed Elimina sono spenti.
    val attivo = !senzaRete || esempio
    val esiste = s.cartella != null
    var foglioRinomina by remember { mutableStateOf(false) }
    var foglioElimina by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
            // Testata col fondo tenue della materia, senza i tre numeri.
            Column(
                Modifier.fillMaxWidth().background(colori.tenue).statusBarsPadding().padding(start = MARGINE, end = MARGINE, top = 12.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsanteTondo(Icone.Indietro, "Indietro", azioni.indietro, fondo = c.superficie.copy(alpha = 0.7f), tinta = colori.testo)
                    Spacer(Modifier.weight(1f))
                    PilloleStato(esempio, senzaRete)
                    Spacer(Modifier.width(8.dp))
                    MenuPuntini(
                        listOf(
                            VoceMenu("Rinomina", attivo && esiste) { foglioRinomina = true },
                            VoceMenu("Elimina cartella", attivo && esiste) { foglioElimina = true },
                        ),
                        descrizione = "Altre azioni",
                        fondo = c.superficie.copy(alpha = 0.7f),
                        tinta = colori.testo,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icone.Cartella, contentDescription = null, tint = colori.testo, modifier = Modifier.size(28.dp))
                    Text(s.nome, style = L.testi.titoloPagina, color = colori.testo, modifier = Modifier.semantics { heading() })
                    Text(
                        TestiCartelle.sottoTestata(s.materia?.nome, s.quanti),
                        style = L.testi.testoMedio.copy(fontWeight = FontWeight.SemiBold),
                        color = colori.testo,
                    )
                }
            }

            Column(Modifier.padding(MARGINE), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                s.errore?.let { RigaErrore(it, onRiprova = vm::carica) }
                s.messaggio?.let { RigaErrore(it) }
                s.conferma?.let { RigaConferma(it) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TitoloSezione("File", Modifier.weight(1f))
                        PulsanteSecondario("Aggiungi", azioni.aggiungi, altezza = 40.dp, raggio = 12.dp, abilitato = attivo && esiste)
                    }
                    when {
                        s.dentro.isNotEmpty() -> Scheda(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), spazio = 0.dp) {
                            s.dentro.forEachIndexed { i, mat ->
                                if (i > 0) Riga()
                                RigaMateriale(
                                    mat, colori.testo, s.oggi, { azioni.apriMateriale(mat.id) }, { vm.riprovaMateriale(mat.id) },
                                    nota = if (mat.cartellaDa == "lectio") TestiCartelle.MESSO_DA_LECTIO else null,
                                )
                            }
                        }
                        s.caricata -> Text(
                            "Ancora nessun file in questa cartella.",
                            style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium),
                            color = c.tenue,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        ZonaPulsanteFisso(Modifier.align(Alignment.BottomCenter)) {
            PulsantePieno("Metti dei file qui", vm::apriMetti, icona = Icone.Piu, abilitato = attivo && esiste && s.candidati.isNotEmpty())
        }
    }

    s.metti?.let { m -> FoglioMettiQui(s, m, vm, attivo) }
    if (foglioRinomina) {
        FoglioNomeCartella(
            titolo = "Rinomina cartella",
            iniziale = s.nome,
            pulsante = "Salva",
            onSalva = { nome, fatto ->
                vm.rinomina(nome) { e ->
                    fatto(e)
                    if (e == null) foglioRinomina = false
                }
            },
            onChiudi = { foglioRinomina = false },
        )
    }
    if (foglioElimina) {
        val n = s.dentro.size
        if (n == 0) {
            Conferma(TestiCartelle.titoloElimina(s.nome), "È vuota.", "Elimina", { foglioElimina = false; vm.elimina(false) }, { foglioElimina = false })
        } else {
            FoglioEliminaCartella(
                s.nome, n,
                onSolo = { foglioElimina = false; vm.elimina(false) },
                onAnche = { foglioElimina = false; vm.elimina(true) },
                onAnnulla = { foglioElimina = false },
            )
        }
    }
}

/**
 * Una riga cartella nella pagina della materia (aspetto.md §42.1): icona 22 nel colore della
 * materia, nome su una riga, "8 file · 2 messi ora da Lectio", freccia.
 */
@Composable
internal fun RigaCartella(cartella: Cartella, colore: Color, onApri: () -> Unit) {
    val c = L.colori
    val sotto = buildAnnotatedString {
        append(TestiCartelle.quantiFile(cartella.file))
        TestiCartelle.messiDaLectio(cartella.messiDaLectio)?.let { messi ->
            append(" · ")
            withStyle(SpanStyle(color = c.accentoTesto, fontWeight = FontWeight.SemiBold)) { append(messi) }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onApri).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icone.Cartella, contentDescription = null, tint = colore, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(cartella.nome, style = L.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sotto, style = L.testi.secondario, color = c.tenue)
        }
        Icon(Icone.Avanti, contentDescription = null, tint = c.tenue, modifier = Modifier.size(20.dp))
    }
}

/** La riga "Nuova cartella" (aspetto.md §42.1): icona più e testo in accento, alta 48. */
@Composable
internal fun RigaNuovaCartella(abilitato: Boolean, onClick: () -> Unit) {
    val c = L.colori
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = abilitato, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val colore = if (abilitato) c.accentoTesto else c.moltoTenue
        // Larga 22 come l'icona delle righe sotto, così i testi stanno in colonna.
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Icon(Icone.Piu, contentDescription = null, tint = colore, modifier = Modifier.size(20.dp))
        }
        Text("Nuova cartella", style = L.testi.forte.copy(fontWeight = FontWeight.Bold), color = colore)
    }
}

/**
 * Nuova cartella e Rinomina (aspetto.md §42.3): campo NOME, l'errore del server sotto, il
 * pulsante spento finché il nome è vuoto o uguale a prima. Invio = il pulsante.
 */
@Composable
fun FoglioNomeCartella(
    titolo: String,
    iniziale: String,
    pulsante: String,
    onSalva: (nome: String, fatto: (String?) -> Unit) -> Unit,
    onChiudi: () -> Unit,
) {
    val c = L.colori
    var nome by rememberSaveable { mutableStateOf(iniziale) }
    var errore by remember { mutableStateOf<String?>(null) }
    var inCorso by remember { mutableStateOf(false) }
    val pulito = TestiCartelle.pulisciNome(nome)
    val pronto = pulito.isNotEmpty() && pulito != TestiCartelle.pulisciNome(iniziale) && !inCorso
    val salva: () -> Unit = {
        if (pronto) {
            inCorso = true
            errore = null
            onSalva(pulito) { e ->
                inCorso = false
                errore = e
            }
        }
    }
    val fuoco = remember { FocusRequester() }
    FoglioFermo(
        onChiudi = onChiudi,
        titolo = titolo,
        piede = { PulsantePieno(pulsante, salva, abilitato = pronto) },
    ) {
        Etichetta("Nome")
        CampoBordato(
            nome,
            { t ->
                nome = t.take(TestiCartelle.NOME_MASSIMO)
                errore = null
            },
            Modifier.focusRequester(fuoco),
            segnaposto = "Per esempio Arte greca",
            stile = L.testi.forteGrande.copy(fontWeight = FontWeight.SemiBold),
            altezza = 52.dp,
            tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            descrizione = "Nome della cartella",
            azioniTastiera = KeyboardActions(onDone = { salva() }),
            bordoFisso = if (errore != null) c.erroreTesto else null,
        )
        errore?.let { Text(it, style = L.testi.secondario, color = c.erroreTesto) }
        LaunchedEffect(Unit) {
            // La tastiera si apre da sola sul campo; se non si può (nessuna finestra) non succede niente.
            runCatching { fuoco.requestFocus() }
        }
    }
}

/** "Metti dei file qui" (aspetto.md §42.4). */
@Composable
private fun FoglioMettiQui(s: StatoCartella, m: StatoMetti, vm: CartellaViewModel, attivo: Boolean) {
    val c = L.colori
    val candidati = s.candidati
    val nomi = s.cartelle.associate { it.id to it.nome }
    val nomeCartella: (Materiale) -> String? = { x -> x.cartellaId?.let { nomi[it] } }
    val filtrati = SceltaMateriale.filtra(candidati, m.cerca, nomeCartella)
    val fuori = MettiQui.fuori(filtrati)
    val altrove = MettiQui.altrove(filtrati, s.cartelle)
    val n = MettiQui.daMandare(candidati, s.cartelle, m.scelti).size
    val vuoto = candidati.isEmpty()
    FoglioFermo(
        onChiudi = vm::chiudiMetti,
        titolo = TestiCartelle.titoloMetti(s.nome),
        altezzaMassima = 0.9f,
        accanto = if (vuoto) null else {
            {
                Text(
                    TestiCartelle.scelti(n),
                    style = L.testi.pillola,
                    color = c.tenue,
                    maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(c.superficie2).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        },
        piede = if (vuoto) null else {
            {
                m.errore?.let { RigaErrore(it) }
                PulsantePieno(
                    if (m.inCorso) "Sposto i file" else TestiCartelle.pulsanteMetti(n),
                    vm::metti,
                    abilitato = n > 0 && !m.inCorso && !m.inAttesa && attivo,
                )
            }
        },
    ) {
        if (vuoto) {
            Text("Tutto il materiale della materia è già qui.", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.tenue)
        } else {
            if (MettiQui.conCerca(candidati)) {
                CampoBordato(
                    m.cerca, vm::cercaMetti,
                    segnaposto = "Cerca nel materiale",
                    stile = L.testi.secondario.copy(fontWeight = FontWeight.SemiBold),
                    altezza = 48.dp,
                    fondo = c.fondo,
                    bordoSpento = c.fondo,
                    descrizione = "Cerca nel materiale",
                )
            }
            if (fuori.isEmpty() && altrove.isEmpty()) {
                Text("Niente con questo nome", style = L.testi.testoMedio.copy(fontWeight = FontWeight.Medium), color = c.tenue)
            }
            val abilitate = !m.inAttesa && !m.inCorso
            if (fuori.isNotEmpty()) {
                Etichetta("Fuori dalle cartelle")
                fuori.forEach { x ->
                    RigaCasella(
                        titolo = x.titolo.ifBlank { x.nomeFile ?: "Materiale" },
                        sotto = TestiCartelle.sottoScelta(x, null, s.oggi),
                        scelto = x.id in m.scelti,
                        onTocca = { vm.toccaMetti(x.id) },
                        abilitato = abilitate,
                        attenuata = false,
                    )
                }
            }
            if (altrove.isNotEmpty()) {
                Etichetta("In altre cartelle", Modifier.padding(top = if (fuori.isEmpty()) 0.dp else 8.dp))
                altrove.forEach { x ->
                    RigaCasella(
                        titolo = x.titolo.ifBlank { x.nomeFile ?: "Materiale" },
                        sotto = TestiCartelle.sottoScelta(x, nomeCartella(x), s.oggi),
                        scelto = x.id in m.scelti,
                        onTocca = { vm.toccaMetti(x.id) },
                        abilitato = abilitate,
                        attenuata = false,
                    )
                }
            }
        }
    }
}

/**
 * Eliminare una cartella con dei file (aspetto.md §42.5): tre pulsanti larghi, alti 52. Il
 * rosso è solo su "Elimina anche gli N file", l'unica cosa che non si può disfare.
 */
@Composable
private fun FoglioEliminaCartella(nome: String, n: Int, onSolo: () -> Unit, onAnche: () -> Unit, onAnnulla: () -> Unit) {
    val c = L.colori
    Foglio(onChiudi = onAnnulla, titolo = TestiCartelle.titoloElimina(nome)) {
        Text(TestiCartelle.dentro(n), style = L.testi.testo, color = c.tenue)
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PulsanteSecondario(
                "Elimina solo la cartella", onSolo, Modifier.fillMaxWidth(),
                altezza = 52.dp, raggio = 18.dp, stile = L.testi.pulsante,
            )
            Text(
                "I file restano nella materia",
                style = L.testi.piccolo.copy(fontWeight = FontWeight.Medium),
                color = c.tenue,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PulsantePieno(TestiCartelle.eliminaAnche(n), onAnche, colore = c.erroreTenue, coloreTesto = c.erroreTesto, altezza = 52.dp)
        PulsanteTesto("Annulla", onAnnulla, Modifier.fillMaxWidth().heightIn(min = 52.dp), colore = c.tenue)
    }
}
