package eu.stgm.lectio.ui.materiale

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import eu.stgm.lectio.dati.Cartella
import eu.stgm.lectio.dati.JsonLectio
import eu.stgm.lectio.dati.Materia
import eu.stgm.lectio.dati.MaterialeScritto
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.Condivisione
import eu.stgm.lectio.logica.Costi
import eu.stgm.lectio.logica.Formati
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.FileDaCaricare
import eu.stgm.lectio.rete.messaggio
import eu.stgm.lectio.ui.Campo
import eu.stgm.lectio.ui.CampoConEtichetta
import eu.stgm.lectio.ui.Etichetta
import eu.stgm.lectio.ui.Foglio
import eu.stgm.lectio.ui.Icone
import eu.stgm.lectio.ui.PillolaScelta
import eu.stgm.lectio.ui.PulsantePieno
import eu.stgm.lectio.ui.PulsanteSecondario
import eu.stgm.lectio.ui.Riga
import eu.stgm.lectio.ui.RigaAvviso
import eu.stgm.lectio.ui.RigaErrore
import eu.stgm.lectio.ui.tema.L
import eu.stgm.lectio.visita.Bersagli
import eu.stgm.lectio.visita.bersaglio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Il passo del foglio "Aggiungi" (aspetto.md §5). */
enum class PassoAggiunta { SCEGLI, FILE, APPUNTI, INTERROGAZIONE, FOTO }

/** Chi apre il foglio e con cosa (dalla materia, dalla condivisione, da Per Gemini, da Com'è andata). */
@Serializable
data class RichiestaAggiunta(
    val materiaId: Long? = null,
    val passo: PassoAggiunta = PassoAggiunta.SCEGLI,
    /** "Che cos'è": file e foto partono da Appunti (contratto §16.4). */
    val tipo: TipoMateriale = TipoMateriale.APPUNTI,
    /**
     * Il tipo chiesto da chi apre il foglio ("Aggiungi una verifica", "Carica la verifica
     * corretta"): file e foto partono da quello invece che da Appunti.
     */
    val tipoChiesto: TipoMateriale? = null,
    val file: List<FileScelto> = emptyList(),
    /** Testo condiviso da un'altra app (Gemini): diventa un file .txt. */
    val testo: String? = null,
    val oggetto: String? = null,
    /** Arriva da "Condividi": si chiede "In quale materia?". */
    val condivisa: Boolean = false,
    /**
     * v1.6 §27 (aspetto.md §42.6): la cartella scelta; null = "Decide Lectio" (non si manda e
     * Lectio la sceglie dopo averlo letto). Dalla pagina di una cartella parte già quella.
     */
    val cartellaId: Long? = null,
)

/**
 * Quello che Andrea ha scritto o scelto nel foglio: sta nel ViewModel e nello stato
 * salvato, così una rotazione, il tema che cambia o Android che chiude l'app mentre
 * scatta una foto non lo perdono (punto 7).
 */
@Serializable
data class CampiAggiunta(
    val titolo: String = "",
    /** null = non scelta: per file e foto il server prende la data dal file (riga DATA di Gemini). */
    val data: String? = null,
    val testo: String = "",
    val chiesto: String = "",
    val risposto: String = "",
    val detto: String = "",
    /** La foto che la fotocamera sta scattando (il suo indirizzo nel FileProvider). */
    val fotoInCorso: String? = null,
)

@Serializable
private data class FoglioSalvato(val aperto: Boolean, val richiesta: RichiestaAggiunta, val campi: CampiAggiunta)

data class StatoAggiunta(
    val aperto: Boolean = false,
    val materie: List<Materia> = emptyList(),
    /** Le cartelle della materia scelta: senza, la riga CARTELLA non c'è. */
    val cartelle: List<Cartella> = emptyList(),
    val richiesta: RichiestaAggiunta = RichiestaAggiunta(),
    val campi: CampiAggiunta = CampiAggiunta(),
    val inCorso: Boolean = false,
    /** Le frasi dei file non andati (gli altri sono già partiti). */
    val errori: List<String> = emptyList(),
    val fatto: Boolean = false,
)

/** La data proposta: oggi per appunti e interrogazioni scritte ora, nessuna per file e foto (punto 16). */
fun dataIniziale(passo: PassoAggiunta, oggi: LocalDate = LocalDate.now()): String? = when (passo) {
    PassoAggiunta.APPUNTI, PassoAggiunta.INTERROGAZIONE -> oggi.toString()
    else -> null
}

class AggiuntaViewModel(
    private val sorgente: Sorgente,
    private val preparatore: PreparatoreFile?,
    private val salvato: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val _stato = MutableStateFlow(StatoAggiunta())
    val stato: StateFlow<StatoAggiunta> = _stato.asStateFlow()

    init {
        // Dopo che Android ha chiuso l'app (per esempio durante la foto) il foglio torna com'era.
        val ripreso = salvato.get<String>(CHIAVE)?.let {
            try {
                JsonLectio.decodeFromString(FoglioSalvato.serializer(), it)
            } catch (e: Exception) {
                null
            }
        }
        if (ripreso != null && ripreso.aperto) {
            _stato.value = StatoAggiunta(aperto = true, richiesta = ripreso.richiesta, campi = ripreso.campi)
            caricaMaterie()
        }
    }

    private fun ricorda() {
        val s = _stato.value
        salvato[CHIAVE] = JsonLectio.encodeToString(FoglioSalvato.serializer(), FoglioSalvato(s.aperto, s.richiesta, s.campi))
    }

    private fun aggiorna(cambia: (StatoAggiunta) -> StatoAggiunta) {
        _stato.update(cambia)
        ricorda()
    }

    fun apri(r: RichiestaAggiunta) {
        // Un testo di Gemini condiviso parte già come verifica o interrogazione.
        val tipo = Condivisione.tipoDaGemini(r.testo) ?: r.tipo
        _stato.value = StatoAggiunta(aperto = true, richiesta = r.copy(tipo = tipo), campi = CampiAggiunta(data = dataIniziale(r.passo)))
        ricorda()
        caricaMaterie()
        rilevaTipoDaiFile()
    }

    /** Il tipo da cui partono file e foto: quello chiesto da chi ha aperto il foglio, altrimenti Appunti. */
    fun tipoPerFile(r: RichiestaAggiunta = _stato.value.richiesta): TipoMateriale = r.tipoChiesto ?: TipoMateriale.APPUNTI

    /** File e foto scelti: si aggiungono e, se uno è un testo di Gemini, "Che cos'è" lo dice. */
    fun scelti(passo: PassoAggiunta, file: List<FileScelto>, aggiungi: Boolean) {
        val r = _stato.value.richiesta
        cambia(r.copy(passo = passo, file = if (aggiungi) r.file + file else file, tipo = tipoPerFile(r)))
        rilevaTipoDaiFile()
    }

    /** Legge l'inizio dei file di testo: la riga MATERIA/TIPO di Gemini dice se è una verifica. */
    private fun rilevaTipoDaiFile() {
        val p = preparatore ?: return
        val file = _stato.value.richiesta.file
        if (file.none { it.genere == eu.stgm.lectio.logica.GenereFile.TESTO || it.genere == eu.stgm.lectio.logica.GenereFile.MARKDOWN }) return
        viewModelScope.launch {
            val trovato = file.firstNotNullOfOrNull { Condivisione.tipoDaGemini(p.inizio(it)) } ?: return@launch
            aggiorna { s -> if (s.richiesta.file == file) s.copy(richiesta = s.richiesta.copy(tipo = trovato)) else s }
        }
    }

    private fun caricaMaterie() {
        viewModelScope.launch {
            val materie = sorgente.materie().dato.orEmpty()
            aggiorna { s ->
                val proposta = s.richiesta.materiaId ?: Condivisione.materiaDalTesto(s.richiesta.testo, materie)
                s.copy(materie = materie, richiesta = s.richiesta.copy(materiaId = proposta))
            }
            caricaCartelle()
        }
    }

    private var giroCartelle = 0

    /**
     * Le cartelle della materia scelta (contratto §27). Se la lettura non riesce (server di
     * prima delle cartelle, niente rete) la cartella già scelta resta: la controlla il server.
     */
    private fun caricaCartelle() {
        val materiaId = _stato.value.richiesta.materiaId
        val giro = ++giroCartelle
        if (materiaId == null) {
            _stato.update { it.copy(cartelle = emptyList()) }
            return
        }
        viewModelScope.launch {
            val elenco = sorgente.cartelle(materiaId).dato ?: return@launch
            if (giro != giroCartelle) return@launch
            aggiorna { s ->
                if (s.richiesta.materiaId != materiaId) return@aggiorna s
                val scelta = s.richiesta.cartellaId?.takeIf { id -> elenco.any { it.id == id } }
                s.copy(cartelle = elenco, richiesta = s.richiesta.copy(cartellaId = scelta))
            }
        }
    }

    fun chiudi() = aggiorna { StatoAggiunta() }

    fun cambia(r: RichiestaAggiunta) {
        val primaMateria = _stato.value.richiesta.materiaId
        // Un'altra materia: le sue cartelle sono altre, si riparte da "Decide Lectio".
        val nuova = if (r.materiaId != primaMateria) r.copy(cartellaId = null) else r
        aggiorna { s ->
            val campi = if (nuova.passo != s.richiesta.passo) s.campi.copy(data = dataIniziale(nuova.passo)) else s.campi
            s.copy(richiesta = nuova, campi = campi, errori = emptyList(), cartelle = if (nuova.materiaId != primaMateria) emptyList() else s.cartelle)
        }
        if (nuova.materiaId != primaMateria) caricaCartelle()
    }

    /** CARTELLA: null = "Decide Lectio". */
    fun cartella(id: Long?) = aggiorna { it.copy(richiesta = it.richiesta.copy(cartellaId = id)) }

    fun campi(cambia: (CampiAggiunta) -> CampiAggiunta) = aggiorna { it.copy(campi = cambia(it.campi)) }

    /** Manda file, foto o testo condiviso. Quelli andati si tolgono dall'elenco. */
    fun inviaFile() {
        val r = _stato.value.richiesta
        val titolo = _stato.value.campi.titolo.takeIf { it.isNotBlank() }
        val data = _stato.value.campi.data
        val materiaId = r.materiaId ?: return
        _stato.update { it.copy(inCorso = true, errori = emptyList()) }
        viewModelScope.launch {
            val errori = mutableListOf<String>()
            val rimasti = mutableListOf<FileScelto>()
            if (r.testo != null) {
                val f = preparatore?.testo(r.testo, r.oggetto) ?: FileDaCaricare(java.io.File("esempio.txt"), Condivisione.nomeTesto(r.oggetto), "text/plain")
                val e = sorgente.caricaFile(materiaId, f, r.tipo.codice, titolo, data, r.cartellaId)
                if (e !is Esito.Ok) errori += e.messaggio ?: "Non è partito."
            }
            r.file.forEachIndexed { i, scelto ->
                val pronto = if (preparatore == null) {
                    FilePronto.Pronto(FileDaCaricare(java.io.File(scelto.nome ?: "file"), scelto.nome ?: "file", scelto.mime ?: "application/octet-stream"))
                } else {
                    preparatore.prepara(scelto, i)
                }
                when (pronto) {
                    is FilePronto.Errore -> {
                        errori += "${scelto.nome ?: "File"}: ${pronto.messaggio}"
                        rimasti += scelto
                    }
                    is FilePronto.Pronto -> {
                        // Con più foto insieme la cartella vale per tutte (aspetto.md §42.6).
                        val e = sorgente.caricaFile(materiaId, pronto.file, r.tipo.codice, if (r.file.size == 1) titolo else null, data, r.cartellaId)
                        if (e !is Esito.Ok) {
                            errori += "${scelto.nome ?: "File"}: ${e.messaggio}"
                            rimasti += scelto
                        }
                    }
                }
            }
            preparatore?.pulisci()
            aggiorna {
                it.copy(
                    inCorso = false,
                    errori = errori,
                    fatto = errori.isEmpty(),
                    richiesta = it.richiesta.copy(file = rimasti, testo = if (errori.isEmpty()) null else it.richiesta.testo),
                )
            }
        }
    }

    /** Appunti o interrogazione orale scritti nell'app (JSON, contratto §6). */
    fun inviaTesto(tipo: TipoMateriale, titolo: String?, testo: String) {
        val materiaId = _stato.value.richiesta.materiaId ?: return
        val data = _stato.value.campi.data
        val cartellaId = _stato.value.richiesta.cartellaId
        _stato.update { it.copy(inCorso = true, errori = emptyList()) }
        viewModelScope.launch {
            val e = sorgente.caricaTesto(materiaId, MaterialeScritto(tipo.codice, titolo?.takeIf { it.isNotBlank() }, data, testo.trim(), cartellaId))
            aggiorna { it.copy(inCorso = false, fatto = e is Esito.Ok, errori = listOfNotNull(e.messaggio)) }
        }
    }

    companion object {
        private const val CHIAVE = "foglio_aggiungi"
    }
}

/** Il testo dell'interrogazione orale: le tre parti, con un titolo ciascuna. */
fun testoInterrogazione(chiesto: String, risposto: String, detto: String): String = buildString {
    if (chiesto.isNotBlank()) append("Cosa mi ha chiesto il prof:\n").append(chiesto.trim()).append("\n\n")
    if (risposto.isNotBlank()) append("Cosa ho risposto:\n").append(risposto.trim()).append("\n\n")
    if (detto.isNotBlank()) append("Cosa ha detto il prof:\n").append(detto.trim())
}.trim()

/**
 * Il foglio "Aggiungi" (aspetto.md §5, prototipo Materia.dc.html): Testo per Gemini
 * in alto, poi File, Appunti, Interrogazione orale, Foto; dopo la scelta tipo,
 * titolo, data e "Aggiungi".
 */
@Composable
fun FoglioAggiungi(
    vm: AggiuntaViewModel,
    preparatore: PreparatoreFile?,
    onChiudi: () -> Unit,
    onGemini: () -> Unit,
    onFatto: (materiaId: Long) -> Unit,
    senzaRete: Boolean,
) {
    val s by vm.stato.collectAsStateWithLifecycle()
    val r = s.richiesta
    val c = L.colori
    val contesto = LocalContext.current
    LaunchedEffect(s.fatto) { if (s.fatto) r.materiaId?.let(onFatto) }

    val sceltaFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && preparatore != null) {
            vm.scelti(PassoAggiunta.FILE, uris.map { preparatore.leggi(it) }, aggiungi = false)
        }
    }
    val sceltaFoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty() && preparatore != null) {
            vm.scelti(PassoAggiunta.FOTO, uris.map { preparatore.leggi(it) }, aggiungi = true)
        }
    }
    val scatto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        // La foto in corso viene dal ViewModel: c'è anche se nel frattempo l'app è stata ricreata.
        val uri = vm.stato.value.campi.fotoInCorso?.let(Uri::parse)
        if (ok && uri != null && preparatore != null) {
            vm.scelti(PassoAggiunta.FOTO, listOf(preparatore.leggi(uri, "image/jpeg")), aggiungi = true)
        }
        vm.campi { it.copy(fotoInCorso = null) }
    }
    val scatta = {
        if (preparatore != null) {
            val f = preparatore.nuovaFoto()
            val uri = FileProvider.getUriForFile(contesto, contesto.packageName + ".file", f)
            vm.campi { it.copy(fotoInCorso = uri.toString()) }
            scatto.launch(uri)
        }
    }

    val materia = s.materie.firstOrNull { it.id == r.materiaId }
    val titoloFoglio = when {
        r.condivisa -> "Aggiungi a Lectio"
        materia != null -> "Aggiungi a ${materia.nome}"
        else -> "Aggiungi"
    }
    Foglio(onChiudi = onChiudi, titolo = titoloFoglio) {
        if (r.condivisa || r.materiaId == null) SceltaMateria(s.materie, r.materiaId) { vm.cambia(r.copy(materiaId = it)) }
        when (r.passo) {
            PassoAggiunta.SCEGLI -> {
                Row(
                    Modifier.fillMaxWidth().bersaglio(Bersagli.TESTO_GEMINI).clip(RoundedCornerShape(16.dp)).background(c.accentoTenue)
                        .clickable(role = Role.Button, onClick = onGemini).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icone.Gemini, contentDescription = null, tint = c.accentoTesto, modifier = Modifier.size(22.dp))
                    Text("Testo per Gemini", style = L.testi.forte, color = c.accentoTesto, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Icon(Icone.Avanti, contentDescription = null, tint = c.accentoTesto, modifier = Modifier.size(18.dp))
                }
                Column {
                    RigaScelta(Icone.File, "File", null) { sceltaFile.launch(Condivisione.TIPI_SELETTORE) }
                    Riga()
                    RigaScelta(Icone.Appunti, "Appunti", null) { vm.cambia(r.copy(passo = PassoAggiunta.APPUNTI, tipo = TipoMateriale.APPUNTI)) }
                    Riga()
                    RigaScelta(Icone.Interrogazione, "Interrogazione orale", null) { vm.cambia(r.copy(passo = PassoAggiunta.INTERROGAZIONE, tipo = TipoMateriale.INTERROGAZIONE)) }
                    Riga()
                    RigaScelta(Icone.Foto, "Foto", "circa ${Formati.cent(Costi.COSTO_FOTO_EUR)} a foto") { vm.cambia(r.copy(passo = PassoAggiunta.FOTO, tipo = vm.tipoPerFile(r))) }
                }
            }
            PassoAggiunta.FILE -> ModuloFile(s, vm, senzaRete)
            PassoAggiunta.FOTO -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulsanteSecondario("Scatta una foto", scatta, Modifier.weight(1f), altezza = 50.dp, icona = Icone.Foto)
                    PulsanteSecondario(
                        "Dalle foto",
                        { sceltaFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.weight(1f), altezza = 50.dp,
                    )
                }
                if (r.file.isNotEmpty()) {
                    val quante = r.file.size
                    RigaAvviso("Costo: circa ${Formati.cent(Costi.costoFoto(quante))} per $quante ${if (quante == 1) "foto" else "foto"}, per leggerle.")
                    ModuloFile(s, vm, senzaRete)
                }
            }
            PassoAggiunta.APPUNTI -> ModuloAppunti(s, vm, senzaRete)
            PassoAggiunta.INTERROGAZIONE -> ModuloInterrogazione(s, vm, senzaRete)
        }
        if (senzaRete) RigaAvviso("Senza rete non si può caricare niente: il server di casa non risponde.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SceltaMateria(materie: List<Materia>, scelta: Long?, onScegli: (Long) -> Unit) {
    CampoConEtichetta("In quale materia?") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            materie.forEach { m -> PillolaScelta(m.nome, m.id == scelta, { onScegli(m.id) }) }
        }
    }
}

@Composable
private fun RigaScelta(icona: ImageVector, titolo: String, sotto: String?, onClick: () -> Unit) {
    val c = L.colori
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icona, contentDescription = null, tint = c.testo, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(titolo, style = L.testi.forteGrande.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.testo)
            if (sotto != null) Text(sotto, style = L.testi.secondario, color = c.tenue)
        }
    }
}

/** CARTELLA (aspetto.md §42.6): "Decide Lectio" e una pillola per cartella; niente senza cartelle. */
@Composable
private fun SceltaCartellaAggiungi(s: StatoAggiunta, vm: AggiuntaViewModel) {
    SceltaCartella(s.cartelle, s.richiesta.cartellaId, "Decide Lectio", vm::cartella, abilitato = !s.inCorso)
}

/** "Che cos'è" nel foglio (contratto §16.4): le due famiglie, una sotto l'altra. */
@Composable
private fun SceltaTipo(scelto: TipoMateriale, onScegli: (TipoMateriale) -> Unit) {
    SceltaCheCosE(scelto, onScegli)
}

@Composable
private fun ModuloFile(s: StatoAggiunta, vm: AggiuntaViewModel, senzaRete: Boolean) {
    val r = s.richiesta
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r.testo != null) RigaFile(Icone.Testo, Condivisione.nomeTesto(r.oggetto), null)
        r.file.forEach { f ->
            RigaFile(if (f.genere?.immagine == true) Icone.Foto else Icone.File, f.nome ?: "File", if (f.genere == null) Condivisione.NON_LEGGIBILE else null)
        }
    }
    SceltaTipo(r.tipo) { vm.cambia(r.copy(tipo = it)) }
    SceltaCartellaAggiungi(s, vm)
    if (r.file.size <= 1) {
        // Facoltativo: se resta vuoto non si manda e il titolo lo scrive Lectio (contratto §16.3).
        val cosa = if (r.passo == PassoAggiunta.FOTO) "la foto" else "il file"
        CampoConEtichetta("Titolo") {
            Campo(s.campi.titolo, { t -> vm.campi { it.copy(titolo = t.take(120)) } }, segnaposto = "Lo scrive Lectio leggendo $cosa", tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
    }
    // Senza data scelta il server la prende dal file (la riga DATA del testo di Gemini).
    CampoData(s.campi.data, segnaposto = "La prendo dal file") { d -> vm.campi { it.copy(data = d) } }
    s.errori.forEach { RigaErrore(it) }
    val pronti = r.materiaId != null && (r.file.any { it.genere != null } || r.testo != null)
    PulsantePieno(
        if (s.inCorso) "Invio in corso" else "Aggiungi",
        { vm.inviaFile() },
        abilitato = pronti && !s.inCorso && !senzaRete,
    )
}

@Composable
private fun RigaFile(icona: ImageVector, nome: String, errore: String?) {
    val c = L.colori
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(icona, contentDescription = null, tint = c.tenue, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(nome, style = L.testi.forte, color = c.testo, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (errore != null) Text(errore, style = L.testi.secondario, color = c.erroreTesto)
        }
    }
}

@Composable
private fun ModuloAppunti(s: StatoAggiunta, vm: AggiuntaViewModel, senzaRete: Boolean) {
    val k = s.campi
    SceltaTipo(s.richiesta.tipo) { vm.cambia(s.richiesta.copy(tipo = it)) }
    SceltaCartellaAggiungi(s, vm)
    CampoConEtichetta("Titolo") { Campo(k.titolo, { t -> vm.campi { it.copy(titolo = t.take(120)) } }, segnaposto = "Per esempio Augusto e il principato") }
    CampoConEtichetta("Testo") { Campo(k.testo, { t -> vm.campi { it.copy(testo = t) } }, righe = 6, segnaposto = "Scrivi o incolla") }
    CampoData(k.data) { d -> vm.campi { it.copy(data = d) } }
    s.errori.forEach { RigaErrore(it) }
    PulsantePieno(
        if (s.inCorso) "Invio in corso" else "Aggiungi",
        { vm.inviaTesto(s.richiesta.tipo, k.titolo, k.testo) },
        abilitato = k.testo.isNotBlank() && s.richiesta.materiaId != null && !s.inCorso && !senzaRete,
    )
}

@Composable
private fun ModuloInterrogazione(s: StatoAggiunta, vm: AggiuntaViewModel, senzaRete: Boolean) {
    val k = s.campi
    // Qui "Che cos'è" non c'è: la cartella va in cima, dove negli altri moduli sta il tipo.
    SceltaCartellaAggiungi(s, vm)
    CampoConEtichetta("Cosa ti ha chiesto") { Campo(k.chiesto, { t -> vm.campi { it.copy(chiesto = t) } }, righe = 3) }
    CampoConEtichetta("Cosa hai risposto") { Campo(k.risposto, { t -> vm.campi { it.copy(risposto = t) } }, righe = 3) }
    CampoConEtichetta("Cosa ha detto") { Campo(k.detto, { t -> vm.campi { it.copy(detto = t) } }, righe = 3) }
    CampoData(k.data) { d -> vm.campi { it.copy(data = d) } }
    s.errori.forEach { RigaErrore(it) }
    PulsantePieno(
        if (s.inCorso) "Invio in corso" else "Aggiungi",
        { vm.inviaTesto(TipoMateriale.INTERROGAZIONE, "Interrogazione", testoInterrogazione(k.chiesto, k.risposto, k.detto)) },
        abilitato = k.chiesto.isNotBlank() && s.richiesta.materiaId != null && !s.inCorso && !senzaRete,
    )
}

/** La data: tocco = calendario. Salvata come "2026-10-28"; null = non scelta ([segnaposto]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampoData(data: String?, etichetta: String = "Data", segnaposto: String = "Scegli la data", onCambia: (String) -> Unit) {
    val c = L.colori
    var apri by remember { mutableStateOf(false) }
    val scritta = Formati.giornoOVuoto(data)
    CampoConEtichetta(etichetta) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(c.superficie2)
                .clickable(role = Role.Button) { apri = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icone.Calendario, contentDescription = null, tint = c.tenue, modifier = Modifier.size(20.dp))
            Text(scritta.ifEmpty { segnaposto }, style = L.testi.testo, color = if (scritta.isEmpty()) c.moltoTenue else c.testo, modifier = Modifier.padding(start = 10.dp))
        }
    }
    if (apri) {
        val iniziale = (Formati.data(data) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val stato = rememberDatePickerState(initialSelectedDateMillis = iniziale)
        DatePickerDialog(
            onDismissRequest = { apri = false },
            confirmButton = {
                eu.stgm.lectio.ui.PulsanteTesto("Va bene", {
                    stato.selectedDateMillis?.let { onCambia(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    apri = false
                })
            },
            dismissButton = { eu.stgm.lectio.ui.PulsanteTesto("Annulla", { apri = false }, colore = c.tenue) },
        ) { DatePicker(stato) }
    }
}
