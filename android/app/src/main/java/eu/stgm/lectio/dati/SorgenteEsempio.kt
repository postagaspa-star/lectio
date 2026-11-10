package eu.stgm.lectio.dati

import eu.stgm.lectio.logica.Costi
import eu.stgm.lectio.logica.TestiLampo

import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.FileDaCaricare
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.ceil

/**
 * La modalità esempio della visita guidata (aspetto.md §12): stesse schermate,
 * dati finti in memoria, nessuna chiamata al server. Nel repo pubblico solo nomi
 * di fantasia (Prof. Rossi, Prof. Bianchi). Le scritture cambiano i dati in
 * memoria, così quello che si fa nella visita si vede.
 */
class SorgenteEsempio(private val oggi: LocalDate = LocalDate.now()) : Sorgente {
    override val esempio = true
    override val senzaRete: StateFlow<Boolean> = MutableStateFlow(false)

    /** Solo in memoria: la visita non lascia niente sul telefono. */
    override val deposito = DepositoStudio()

    private fun giorno(dopo: Long): String = oggi.plusDays(dopo).toString()
    private val adessoIso: String = OffsetDateTime.of(oggi.atTime(9, 0), ZoneOffset.ofHours(1)).toString()
    private val primoDelMese: String = oggi.withDayOfMonth(1).plusMonths(1).toString()

    // --- Materie ------------------------------------------------------------------------------

    private val storia = Materia(1, "Storia", "Rossi", "ambra", Conteggi(4, 1, 3, 2, schemi = 1, lampo = 3, simulate = 2), Centratura(10, 16, 63), daRipassare = 2)
    private val diritto = Materia(2, "Diritto ed economia", "Bianchi", "petrolio", Conteggi(2, 0, 2, 1, lampo = 1), Centratura(4, 7, 57))
    private val inglese = Materia(3, "Inglese", "Bianchi", "verde", Conteggi(3, 1, 1, 1), null)
    private val scienzeUmane = Materia(4, "Scienze umane", "Rossi", "viola", Conteggi(1, 2, 4, 0), null)
    private val matematica = Materia(5, "Matematica", "Rossi", "blu", Conteggi(2, 0, 0, 0), null)

    private val materie = mutableListOf(diritto, inglese, matematica, scienzeUmane, storia)

    private fun breve(m: Materia) = MateriaBreve(m.id, m.nome, m.colore)

    // --- Eventi -------------------------------------------------------------------------------

    private val eventi = mutableListOf(
        Evento(3, 1, "scritta", "L'Impero romano", giorno(2), null, LEZIONE_STORIA),
        Evento(4, 3, "scritta", "Present perfect", giorno(-1), null, 7),
        Evento(5, 4, "orale", "La psicologia dello sviluppo", giorno(6), null, null),
        Evento(6, 5, "scritta", "Equazioni di secondo grado", giorno(8), null, null),
        Evento(7, 2, "scritta", "Le fonti del diritto", giorno(13), null, 9),
    )

    // --- Lezioni ------------------------------------------------------------------------------

    private val capitoliStoria = listOf(
        Capitolo(1, "Augusto e il principato", 0.0, 301.0),
        Capitolo(2, "Tiberio e i processi", 301.0, 589.0),
        Capitolo(3, "Caligola e Claudio", 589.0, 901.0),
        Capitolo(4, "Interrogazione", 901.0, 1187.0),
    )

    private val domandeStoria = listOf(
        Domanda(31, "blocco", 1, 6, "Perché Augusto non si fece mai chiamare re?",
            "Perché a Roma la parola re ricordava la tirannia: Augusto preferì il titolo di princeps, il primo dei cittadini.",
            280.4, 286.9, 289.4, 301.0),
        Domanda(32, "blocco", 2, 6, "Perché Tiberio passò gli ultimi anni a Capri, lontano da Roma?",
            "Perché non si fidava del senato e temeva le congiure: lasciò Roma a Seiano, il capo dei pretoriani, che però tramò contro di lui.",
            561.2, 567.8, 570.3, 589.0),
        Domanda(33, "blocco", 3, 6, "Quale provincia conquistò Claudio?",
            "La Britannia, nel 43 dopo Cristo: fu la conquista più importante del suo regno.",
            880.1, 884.6, 887.1, 901.0),
        Domanda(34, "interrogazione", 4, 6, "Che cosa erano i pretoriani?",
            "Erano la guardia del corpo dell'imperatore, di stanza a Roma.",
            960.0, 964.2, 966.7, 990.0),
        Domanda(35, "interrogazione", 5, 6, "Spiega la differenza tra principato e monarchia.",
            "Nel principato le istituzioni della repubblica restano in piedi, ma il potere vero è nelle mani del principe.",
            1030.0, 1035.5, 1038.0, 1080.0),
        Domanda(36, "interrogazione", 6, 6, "In che anno morì Augusto?",
            "Nel 14 dopo Cristo, a Nola.",
            1110.0, 1113.4, 1115.9, 1130.0),
    )

    private val testoStoria = listOf(
        TestoCapitolo(1, listOf(
            "Immagina Roma dopo anni di guerre civili. Tutti vogliono pace, e Ottaviano la promette.",
            "Ottaviano non si fa chiamare re. Prende il titolo di Augusto e quello di princeps, il primo dei cittadini.",
            "Le istituzioni della repubblica restano: il senato, i consoli. Ma le decisioni importanti le prende lui.",
        )),
        TestoCapitolo(2, listOf(
            "Alla morte di Augusto, nel 14 dopo Cristo, il potere passa a Tiberio, suo figlio adottivo.",
            "Tiberio governa con prudenza, ma si fida poco del senato. I processi per lesa maestà si moltiplicano.",
        )),
        TestoCapitolo(3, listOf(
            "Caligola regna solo quattro anni e lascia il ricordo di un principe capriccioso.",
            "Claudio, invece, organizza l'amministrazione e conquista la Britannia.",
        )),
        TestoCapitolo(4, listOf("Adesso tocca a te. Ti faccio le domande come le farebbe il prof Rossi.")),
    )

    private val lezioneStoria = Lezione(
        id = LEZIONE_STORIA, materiaId = 1, eventoId = 3, argomento = "L'Impero romano da Augusto a Nerone",
        durataMin = 20, durataS = 1187.0, voce = "mistral:voice_482", stato = "pronta",
        costoEur = 0.24, creataIl = adessoIso, prontaIl = adessoIso,
        ascolto = Ascolto(462.0, false, adessoIso), audio = InfoAudio(9_500_000, null),
        capitoli = capitoliStoria, domande = domandeStoria, testo = testoStoria,
    )

    private val lezioneDiritto = Lezione(
        id = 9, materiaId = 2, eventoId = 7, argomento = "Le fonti del diritto", durataMin = 20, durataS = 1190.0,
        voce = "nas:giovanni", stato = "pronta", costoEur = 0.02, creataIl = adessoIso,
        ascolto = Ascolto(690.0, false, adessoIso),
        capitoli = listOf(
            Capitolo(1, "Che cos'è una fonte", 0.0, 300.0),
            Capitolo(2, "La Costituzione", 300.0, 600.0),
            Capitolo(3, "Leggi e regolamenti", 600.0, 900.0),
            Capitolo(4, "Interrogazione", 900.0, 1190.0),
        ),
        domande = listOf(
            Domanda(41, "blocco", 1, 5, "Che cosa vuol dire fonte del diritto?", "È l'atto o il fatto da cui nascono le norme.", 280.0, 284.0, 286.5, 300.0),
        ),
    )

    private val lezioni = mutableListOf(
        lezioneStoria,
        lezioneDiritto,
        Lezione(7, 3, 4, "Present perfect e past simple", 15, 905.0, "mistral:voice_482", "pronta", costoEur = 0.19, creataIl = adessoIso, ascolto = Ascolto(905.0, true, adessoIso)),
        Lezione(13, 4, 5, "La psicologia dello sviluppo", 30, null, "mistral:voice_482", "in_preparazione",
            Avanzamento("voce", 3, 7, "Registro la voce · 3 di 7"), 0.0, adessoIso),
    )

    private var prossimoId = 100L

    // --- Scheda, materiale ---------------------------------------------------------------------

    private val schede = mutableMapOf(
        1L to SchedaProf(
            materiaId = 1, aggiornataIl = adessoIso, basataSu = BasataSu(4, 1, 2),
            argomenti = listOf(
                Argomento("Cause e conseguenze", 4, "Perché cadde la Repubblica?"),
                Argomento("Date chiave", 3, "Quando morì Augusto?"),
                Argomento("Confronti tra imperatori", 2, "Confronta Tiberio e Claudio."),
            ),
            tipiDomanda = listOf(TipoDomanda("aperta", 0.6), TipoDomanda("definizione", 0.25), TipoDomanda("date", 0.15)),
            cosaCorregge = listOf("Vuole le date precise, non \"verso il\".", "Toglie punti se manca il perché di un fatto."),
            cosaPremia = listOf("I collegamenti tra fatti."),
            lunghezzaRisposte = "5-8 righe per le domande aperte",
            consigli = listOf("Parti dalla definizione."),
            noteMie = "", stato = "pronta",
        ),
    )

    /** Il materiale di Storia: appunti, foto, libro, una verifica e un'interrogazione (contratto §16). */
    private val materiale = mutableListOf(
        Materiale(21, 1, "verifica", "Verifica sull'età di Augusto", giorno(-16), "file", "verifica.txt", 5321, "pronto", creatoIl = adessoIso,
            titoloDa = "lectio",
            testo = "MATERIA: Storia | PROF: Rossi | DATA: ${giorno(-16)} | TIPO: scritta | VOTO: 7\n## Domanda 1 (punti: 2)\n" +
                "TESTO: Perché Augusto non si fece chiamare re?\n## Domanda 2 (punti: 3)\nTESTO: Come cambia l'impero romano con Augusto?\n" +
                "MIA RISPOSTA: L'impero romano diventa più stabile.\nCORREZIONI DEL PROF: Mancano le riforme dell'esercito."),
        Materiale(22, 1, "interrogazione", "Interrogazione su Tiberio", giorno(-8), "scritto", null, 812, "pronto", creatoIl = adessoIso,
            titoloDa = "utente", testo = "Mi ha chiesto chi era Seiano. Ho risposto che era il capo dei pretoriani.",
            cartellaId = CARTELLA_STORIA, cartellaDa = "utente"),
        Materiale(23, 1, "appunti", "Augusto e il principato", giorno(-6), "scritto", null, 2410, "pronto", creatoIl = adessoIso,
            titoloDa = "utente",
            testo = "Augusto: princeps, non re. Nasce l'impero romano. Pax augusta. Riforme dell'esercito. L'impero romano si allarga fino al Danubio."),
        Materiale(24, 1, "appunti", "L'Impero romano dopo Augusto", giorno(-4), "foto", "IMG_20261024_1712.jpg", 1976, "pronto", creatoIl = adessoIso,
            titoloDa = "lectio",
            testo = "Tiberio, figlio adottivo di Augusto, governa con prudenza ma si fida poco del senato.\n\n" +
                "Seiano, capo dei pretoriani, prende sempre più potere finché Tiberio lo fa arrestare.\n\n" +
                "Caligola regna quattro anni; Claudio conquista la Britannia; Nerone chiude la dinastia giulio-claudia.\n\n" +
                "Nel 69 d.C., l'anno dei quattro imperatori, vince Vespasiano: con lui comincia la dinastia flavia e si costruisce il Colosseo.",
            cartellaId = CARTELLA_STORIA, cartellaDa = "lectio"),
        Materiale(25, 1, "libro", "Libro, capitolo 5", giorno(-12), "file", "storia_capitolo_5.pdf", 18_400, "pronto", creatoIl = adessoIso,
            titoloDa = "file", testo = "La crisi della repubblica: i Gracchi, Mario e Silla, Cesare."),
        Materiale(26, 1, "appunti", "Le guerre puniche", giorno(-20), "foto", "IMG_20261008_0930.jpg", 1340, "pronto", creatoIl = adessoIso,
            titoloDa = "lectio", testo = "Roma contro Cartagine: tre guerre per il Mediterraneo."),
    )

    /** v1.6 §27 (aspetto.md §42.9): una cartella di Storia con due file, uno messo lì da Lectio. */
    private val cartelle = mutableListOf(Cartella(CARTELLA_STORIA, 1, "Imperatori", creataIl = adessoIso))

    private var spesoMese = 0.80
    private val tetto = 2.00

    private var impostazioni = Impostazioni(2.0, "mistral:voice_482", 20, true, 1.0, "Andrea")

    // --- Sorgente -----------------------------------------------------------------------------

    override suspend fun salute(): Esito<Salute> = Esito.Ok(
        Salute(true, "1.0.0", false, StatoServizio("pronto", "mistral"), StatoServizio("pronta", "mistral"), StatoServizio("pronta", "nas")),
    )

    override suspend fun oggi(): Lettura<Oggi> {
        val prossimo = eventi.filter { !it.haEsito && it.data >= oggi.toString() }.sortedBy { it.data }
        val prima = prossimo.firstOrNull()
        val giorni = { e: Evento -> java.time.temporal.ChronoUnit.DAYS.between(oggi, LocalDate.parse(e.data)).toInt() }
        val materia = { e: Evento -> breve(materie.first { it.id == e.materiaId }) }
        return Lettura(
            Oggi(
                oggi = oggi.toString(),
                prossima = prima?.let { e -> Prossima(e, materia(e), lezioni.firstOrNull { it.id == e.lezioneId }?.senzaDettaglio(), giorni(e)) },
                riprendi = lezioni.firstOrNull { it.id == 9L && it.ascolto?.finita == false }?.senzaDettaglio(),
                daRaccontare = eventi.filter { !it.haEsito && it.data < oggi.toString() && it.lezioneId != null }.map { DaRaccontare(it, materia(it)) },
                piuAvanti = prossimo.drop(1).take(4).map { PiuAvanti(it, materia(it), giorni(it)) },
                spesa = SpesaBreve(spesoMese, tetto, (spesoMese / tetto * 100).toInt(), primoDelMese, spesoMese >= tetto * 0.8),
                modalitaProva = false,
                lampo = LampoOggi(carteLampo.size, TestiLampo.minuti(carteLampo.size)),
            ),
        )
    }

    private fun Lezione.senzaDettaglio() = copy(capitoli = emptyList(), domande = emptyList(), testo = emptyList())

    override suspend fun materie(): Lettura<List<Materia>> = Lettura(materie.sortedBy { it.nome })

    override suspend fun creaMateria(nuova: NuovaMateria): Esito<Materia> {
        val m = Materia(prossimoId++, nuova.nome, nuova.prof, nuova.colore ?: "rosa")
        materie += m
        return Esito.Ok(m)
    }

    override suspend fun modificaMateria(id: Long, cambi: JsonObject): Esito<Materia> {
        val i = materie.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questa materia.")
        val m = materie[i]
        val nuova = m.copy(
            nome = cambi["nome"]?.jsonPrimitive?.contentOrNull ?: m.nome,
            prof = cambi["prof"]?.jsonPrimitive?.contentOrNull ?: m.prof,
            colore = cambi["colore"]?.jsonPrimitive?.contentOrNull ?: m.colore,
        )
        materie[i] = nuova
        return Esito.Ok(nuova)
    }

    override suspend fun eliminaMateria(id: Long): Esito<Unit> {
        materie.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun scheda(materiaId: Long): Lettura<SchedaProf> =
        Lettura(schede[materiaId] ?: SchedaProf(materiaId = materiaId, stato = "vuota"))

    override suspend fun salvaScheda(materiaId: Long, modifica: ModificaScheda): Esito<SchedaProf> {
        val s = (schede[materiaId] ?: SchedaProf(materiaId = materiaId)).copy(
            argomenti = modifica.argomenti, tipiDomanda = modifica.tipiDomanda, cosaCorregge = modifica.cosaCorregge,
            cosaPremia = modifica.cosaPremia, lunghezzaRisposte = modifica.lunghezzaRisposte, consigli = modifica.consigli,
            noteMie = modifica.noteMie, stato = "pronta",
        )
        schede[materiaId] = s
        return Esito.Ok(s)
    }

    override suspend fun aggiornaScheda(materiaId: Long): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun materiale(materiaId: Long): Lettura<List<Materiale>> =
        Lettura(materiale.filter { it.materiaId == materiaId }.sortedByDescending { it.data })

    override suspend fun materialeDettaglio(id: Long): Esito<Materiale> =
        materiale.firstOrNull { it.id == id }?.let { Esito.Ok(it) } ?: Esito.Errore("Non trovo più questo materiale.")

    override suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?, cartellaId: Long?): Esito<Materiale> {
        if (cartellaId != null && cartelle.none { it.id == cartellaId && it.materiaId == materiaId }) return cartellaNonValida()
        val scritto = titolo?.takeIf { it.isNotBlank() }
        val m = Materiale(prossimoId++, materiaId, tipo, scritto ?: file.nome.substringBeforeLast('.'),
            data ?: oggi.toString(), "file", file.nome, 1200, "pronto", creatoIl = adessoIso, testo = "Testo di esempio.",
            titoloDa = if (scritto != null) "utente" else "file",
            cartellaId = cartellaId, cartellaDa = cartellaId?.let { "utente" })
        materiale += m
        return Esito.Ok(m)
    }

    override suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto): Esito<Materiale> {
        val cartellaId = scritto.cartellaId
        if (cartellaId != null && cartelle.none { it.id == cartellaId && it.materiaId == materiaId }) return cartellaNonValida()
        val m = Materiale(prossimoId++, materiaId, scritto.tipo, scritto.titolo?.takeIf { it.isNotBlank() } ?: "Appunti",
            scritto.data ?: oggi.toString(), "scritto", null, scritto.testo.length, "pronto", creatoIl = adessoIso, testo = scritto.testo,
            cartellaId = cartellaId, cartellaDa = cartellaId?.let { "utente" })
        materiale += m
        return Esito.Ok(m)
    }

    private fun cartellaNonValida() = Esito.Errore("Questa cartella non c'è nella materia.", "non_valido", 422)

    override suspend fun eliminaMateriale(id: Long): Esito<Unit> {
        materiale.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun riprovaMateriale(id: Long): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun modificaMateriale(id: Long, cambi: JsonObject): Esito<Materiale> {
        val i = materiale.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questo materiale.")
        val m = materiale[i]
        val titolo = cambi["titolo"]?.jsonPrimitive?.contentOrNull?.split(Regex("\\s+"))?.joinToString(" ")?.trim()
        if (titolo != null && titolo.isEmpty()) return Esito.Errore("Il titolo non può essere vuoto.", "non_valido", 422)
        // v1.6 §27: "cartella_id" presente = si sposta (null = fuori); assente = non cambia.
        val cambiaCartella = cambi.containsKey("cartella_id")
        val cartellaId = if (cambiaCartella) cambi["cartella_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() else m.cartellaId
        if (cambiaCartella && cartellaId != null && cartelle.none { it.id == cartellaId && it.materiaId == m.materiaId }) return cartellaNonValida()
        materiale[i] = m.copy(
            titolo = titolo ?: m.titolo,
            titoloDa = if (titolo != null) "utente" else m.titoloDa,
            tipo = cambi["tipo"]?.jsonPrimitive?.contentOrNull ?: m.tipo,
            cartellaId = cartellaId,
            cartellaDa = if (cambiaCartella) "utente" else m.cartellaDa,
        )
        return Esito.Ok(materiale[i].copy(testo = null))
    }

    /** Come il server (contratto 27.2): il nome della cartella conta come il titolo di ogni suo file. */
    override suspend fun suggeriti(materiaId: Long, argomento: String): Esito<Suggeriti> {
        val nomi = cartelle.associate { it.id to it.nome }
        val conCartella = materiale.filter { it.materiaId == materiaId }.map { m -> m.cartellaId?.let { nomi[it] }?.let { m.copy(titolo = "${m.titolo} $it") } ?: m }
        val ids = eu.stgm.lectio.logica.ParoleArgomento.suggeriti(argomento, conCartella)
        return Esito.Ok(Suggeriti(argomento, eu.stgm.lectio.logica.ParoleArgomento.parole(argomento), ids))
    }

    // --- Cartelle (contratto §27) ----------------------------------------------------------------

    private fun conConteggi(c: Cartella) = c.copy(
        file = materiale.count { it.cartellaId == c.id },
        messiDaLectio = materiale.count { it.cartellaId == c.id && it.cartellaDa == "lectio" },
    )

    override suspend fun cartelle(materiaId: Long): Lettura<List<Cartella>> =
        Lettura(cartelle.filter { it.materiaId == materiaId }.map(::conConteggi).sortedBy { it.nome.lowercase() })

    /** Le regole del server: 1-40 caratteri senza spazi in più, unico nella materia senza badare alle maiuscole. */
    private fun nomeValido(materiaId: Long, nome: String, escluso: Long? = null): Esito<String> {
        val pulito = nome.trim().split(Regex("\\s+")).joinToString(" ")
        if (pulito.isEmpty() || pulito.length > 40) {
            return Esito.Errore("Il nome della cartella non può essere vuoto né più lungo di 40 caratteri.", "non_valido", 422)
        }
        if (cartelle.any { it.materiaId == materiaId && it.id != escluso && it.nome.equals(pulito, ignoreCase = true) }) {
            return Esito.Errore("C'è già una cartella con questo nome in questa materia.", "conflitto", 409)
        }
        return Esito.Ok(pulito)
    }

    override suspend fun creaCartella(materiaId: Long, nome: String): Esito<Cartella> {
        val pulito = when (val n = nomeValido(materiaId, nome)) {
            is Esito.Ok -> n.dato
            is Esito.Errore -> return n
            Esito.Irraggiungibile -> return Esito.Irraggiungibile
        }
        val c = Cartella(prossimoId++, materiaId, pulito, creataIl = adessoIso)
        cartelle += c
        return Esito.Ok(c)
    }

    override suspend fun rinominaCartella(id: Long, nome: String): Esito<Cartella> {
        val i = cartelle.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Cartella non trovata.", "non_trovato", 404)
        val pulito = when (val n = nomeValido(cartelle[i].materiaId, nome, escluso = id)) {
            is Esito.Ok -> n.dato
            is Esito.Errore -> return n
            Esito.Irraggiungibile -> return Esito.Irraggiungibile
        }
        cartelle[i] = cartelle[i].copy(nome = pulito)
        return Esito.Ok(conConteggi(cartelle[i]))
    }

    override suspend fun eliminaCartella(id: Long, conIFile: Boolean): Esito<CartellaEliminata> {
        if (cartelle.none { it.id == id }) return Esito.Errore("Cartella non trovata.", "non_trovato", 404)
        val dentro = materiale.filter { it.cartellaId == id }
        if (conIFile) {
            materiale.removeAll { it.cartellaId == id }
        } else {
            materiale.replaceAll { if (it.cartellaId == id) it.copy(cartellaId = null, cartellaDa = "utente") else it }
        }
        cartelle.removeAll { it.id == id }
        return Esito.Ok(CartellaEliminata(true, if (conIFile) dentro.size else 0))
    }

    override suspend fun sposta(materiaId: Long, materiali: List<Long>, cartellaId: Long?): Esito<Spostati> {
        if (cartellaId != null && cartelle.none { it.id == cartellaId && it.materiaId == materiaId }) return cartellaNonValida()
        val ids = materiali.toSet()
        var n = 0
        materiale.replaceAll {
            if (it.id in ids && it.materiaId == materiaId) {
                n++
                it.copy(cartellaId = cartellaId, cartellaDa = "utente")
            } else {
                it
            }
        }
        return Esito.Ok(Spostati(n))
    }

    /** Il materiale pronto fuori dalle cartelle che parla di questa (le parole del nome). */
    override suspend fun proposti(cartellaId: Long): Esito<List<Long>> {
        val c = cartelle.firstOrNull { it.id == cartellaId } ?: return Esito.Errore("Cartella non trovata.", "non_trovato", 404)
        val fuori = materiale.filter { it.materiaId == c.materiaId && it.cartellaId == null }
        return Esito.Ok(eu.stgm.lectio.logica.ParoleArgomento.suggeriti(c.nome, fuori))
    }

    override suspend fun eventi(): Lettura<List<Evento>> = Lettura(eventi.sortedBy { it.data })

    override suspend fun creaEvento(materiaId: Long, nuovo: NuovoEvento): Esito<Evento> {
        val e = Evento(prossimoId++, materiaId, nuovo.tipo, nuovo.argomento, nuovo.data)
        eventi += e
        return Esito.Ok(e)
    }

    override suspend fun modificaEvento(id: Long, nuovo: NuovoEvento): Esito<Evento> {
        val i = eventi.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questa data.")
        eventi[i] = eventi[i].copy(tipo = nuovo.tipo, argomento = nuovo.argomento, data = nuovo.data)
        return Esito.Ok(eventi[i])
    }

    override suspend fun eliminaEvento(id: Long): Esito<Unit> {
        eventi.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun salvaEsito(eventoId: Long, corpo: CorpoEsito): Esito<RisultatoEsito> {
        val altre = corpo.altreDomande.lines().count { it.isNotBlank() }
        val previste = corpo.domandeUscite.size
        val totale = previste + altre
        val i = eventi.indexOfFirst { it.id == eventoId }
        if (i >= 0) eventi[i] = eventi[i].copy(esito = kotlinx.serialization.json.JsonPrimitive(true))
        return Esito.Ok(RisultatoEsito(previste, totale, if (totale == 0) 0 else previste * 100 / totale))
    }

    override suspend fun voci(): Esito<List<Voce>> = Esito.Ok(VOCI)

    /** Le cifre del prototipo (NuovaLezione.dc.html): voce espressiva e solo testo per durata. */
    override suspend fun stima(richiesta: RichiestaStima): Esito<Stima> {
        val (totale, testo) = when (richiesta.durataMin) {
            15 -> 0.21 to 0.02
            20 -> 0.27 to 0.02
            30 -> 0.41 to 0.03
            45 -> 0.61 to 0.04
            else -> (richiesta.durataMin * 0.0135) to 0.02
        }
        val espressiva = richiesta.voce.startsWith("mistral")
        val costo = if (espressiva) totale else testo
        // Le voci del NAS lavorano più piano della voce di Mistral (contratto: minuti_preparazione).
        val minuti = Costi.prontaIn(VOCI.firstOrNull { it.id == richiesta.voce }, richiesta.durataMin)
            ?: kotlin.math.floor(2 + richiesta.durataMin * 0.15 + 0.5).toInt()
        val dopo = spesoMese + costo
        val caratteri = (richiesta.durataMin * 145 * 6.3).toInt()
        return Esito.Ok(
            Stima(
                caratteri, testo, if (espressiva) totale - testo else 0.0, costo, spesoMese, tetto, arrotonda(dopo),
                dopo <= tetto, if (dopo <= tetto) null else "Sforerebbe il tetto di 2 €: usa la voce gratis",
                412_000, primoDelMese, minutiPreparazione = minuti,
            ),
        )
    }

    private fun arrotonda(v: Double) = ceil(v * 100 - 1e-9) / 100

    override suspend fun spesa(): Lettura<Spesa> = Lettura(
        Spesa(oggi.toString().take(7), spesoMese, tetto, (spesoMese / tetto * 100).toInt(), primoDelMese, VoceGratisMese(88_000, 500_000)),
    )

    override suspend fun lezioni(materiaId: Long?): Lettura<List<Lezione>> =
        Lettura(lezioni.filter { materiaId == null || it.materiaId == materiaId }.map { it.senzaDettaglio() })

    override suspend fun lezione(id: Long): Lettura<Lezione> =
        lezioni.firstOrNull { it.id == id }?.let { Lettura(it) } ?: Lettura(null, "Non trovo più questa lezione.")

    override suspend fun creaLezione(nuova: NuovaLezione): Esito<Lezione> {
        if (nuova.materiali?.isEmpty() == true) return Esito.Errore("Scegli almeno un appunto o una verifica", "non_valido", 422)
        val l = Lezione(prossimoId++, nuova.materiaId, nuova.eventoId, nuova.argomento, nuova.durataMin, null, nuova.voce, "in_preparazione",
            Avanzamento("lettura", 0, 0, "Leggo il materiale"), 0.0, adessoIso, domandeSaltabili = nuova.domandeSaltabili,
            tipo = nuova.tipo, indicazioni = nuova.indicazioni.ifBlank { null }, materiali = nuova.materiali.orEmpty())
        lezioni.add(0, l)
        return Esito.Ok(l)
    }

    override suspend fun annullaLezione(id: Long): Esito<Unit> {
        val i = lezioni.indexOfFirst { it.id == id }
        if (i >= 0) lezioni[i] = lezioni[i].copy(stato = "annullata")
        return Esito.Ok(Unit)
    }

    override suspend fun riprovaLezione(id: Long): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun modificaLezione(id: Long, cambi: JsonObject): Esito<Lezione> {
        val i = lezioni.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questa lezione.")
        val saltabili = cambi["domande_saltabili"]?.jsonPrimitive?.booleanOrNull ?: lezioni[i].domandeSaltabili
        lezioni[i] = lezioni[i].copy(domandeSaltabili = saltabili)
        return Esito.Ok(lezioni[i])
    }

    private val ripassare = mutableListOf(
        DaRipassare(33, LEZIONE_STORIA, "L'Impero romano da Augusto a Nerone", "Quale provincia conquistò Claudio?", "sbagliata",
            "Hai scritto la Gallia: era già romana da un secolo.", adessoIso),
        DaRipassare(36, LEZIONE_STORIA, "L'Impero romano da Augusto a Nerone", "In che anno morì Augusto?", "saltata", null, adessoIso),
    )

    /**
     * Il correttore finto (come la modalità prova del server): conta le parole
     * importanti della risposta giusta che ci sono nella risposta di Andrea.
     */
    override suspend fun rispondi(lezioneId: Long, domandaId: Long, corpo: CorpoRisposta): Esito<Correzione> {
        val d = lezioni.flatMap { it.domande }.firstOrNull { it.id == domandaId }
            ?: return Esito.Errore("Non trovo più questa domanda.")
        if (corpo.saltata) {
            ripassare.removeAll { it.domandaId == domandaId }
            ripassare.add(0, DaRipassare(domandaId, lezioneId, "", d.testo, "saltata", null, adessoIso))
            return Esito.Ok(Correzione(prossimoId++, domandaId, "saltata", 0.0, null, emptyList(), d.risposta, 0.0, adessoIso))
        }
        val c = if (domandaId == 32L) {
            Correzione(prossimoId++, domandaId, "parziale", 0.5, "Giusto: non si fidava del senato. Manca chi governava Roma al posto suo.",
                listOf("il ruolo di Seiano"), d.risposta, 0.0, adessoIso)
        } else {
            val importanti = d.risposta.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }.distinct()
            val scritte = corpo.testo.lowercase()
            val trovate = importanti.count { scritte.contains(it) }
            val quota = if (importanti.isEmpty()) 0.0 else trovate.toDouble() / importanti.size
            val esito = when {
                quota >= 0.6 -> "giusta"
                quota >= 0.25 -> "parziale"
                else -> "sbagliata"
            }
            val commento = when (esito) {
                "giusta" -> "Giusto: hai detto le cose importanti."
                "parziale" -> "Ci sei in parte. Confronta con la risposta qui sotto."
                else -> "Non ci siamo ancora. Ascolta la risposta e riprova più avanti."
            }
            Correzione(prossimoId++, domandaId, esito, quota, commento, importanti.filterNot { scritte.contains(it) }.take(3), d.risposta, 0.0, adessoIso)
        }
        return Esito.Ok(c)
    }

    override suspend fun daRipassare(materiaId: Long): Lettura<List<DaRipassare>> =
        Lettura(if (materiaId == MATERIA_STORIA) ripassare.toList() else emptyList())

    override suspend fun eliminaLezione(id: Long): Esito<Unit> {
        lezioni.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun gemini(): Lettura<TestoGemini> = Lettura(TestoGemini(TESTO_GEMINI, adessoIso))

    override suspend fun impostazioni(): Lettura<Impostazioni> = Lettura(impostazioni)

    override suspend fun modificaImpostazioni(cambi: JsonObject): Esito<Impostazioni> {
        impostazioni = impostazioni.copy(
            tettoEur = cambi["tetto_eur"]?.jsonPrimitive?.doubleOrNull ?: impostazioni.tettoEur,
            vocePredefinita = cambi["voce_predefinita"]?.jsonPrimitive?.contentOrNull ?: impostazioni.vocePredefinita,
            durataPredefinita = cambi["durata_predefinita"]?.jsonPrimitive?.intOrNull ?: impostazioni.durataPredefinita,
            fermatiAlleDomande = cambi["fermati_alle_domande"]?.jsonPrimitive?.booleanOrNull ?: impostazioni.fermatiAlleDomande,
            velocita = cambi["velocita"]?.jsonPrimitive?.doubleOrNull ?: impostazioni.velocita,
            risposteScritte = cambi["risposte_scritte"]?.jsonPrimitive?.booleanOrNull ?: impostazioni.risposteScritte,
            domandeSaltabili = cambi["domande_saltabili"]?.jsonPrimitive?.booleanOrNull ?: impostazioni.domandeSaltabili,
        )
        return Esito.Ok(impostazioni)
    }

    // --- Schemi, riassunti, "Non ho capito", Ripasso lampo, verifica simulata --------------------

    private val libro = Fonte(25, "Libro, capitolo 5", "libro", 42, "Libro, pag. 42")
    private val verifica = Fonte(21, "Verifica sull'età di Augusto", "verifica", null, "Verifica del 12/10")
    private val appunti = Fonte(23, "Augusto e il principato", "appunti", null, "Appunti del 28/10")

    private val contenutoSchema = ContenutoSchema(
        forma = "capitoli",
        fonti = mapOf("B1" to libro, "V1" to verifica, "A1" to appunti),
        copertura = Copertura(8, 7, listOf("Le riforme dell'esercito")),
        sezioni = listOf(
            SezioneSchema(
                1, "Augusto e il principato",
                blocchi = listOf(
                    BloccoSchema("paragrafo", testo = "Dopo anni di guerre civili Ottaviano non si fa chiamare re. Prende il titolo di **princeps**, il primo dei cittadini, e lascia in piedi il senato e i consoli.", fonti = listOf("B1")),
                    BloccoSchema("definizione", termine = "Principato", testo = "Il governo di uno solo che tiene in piedi, di facciata, le **istituzioni della repubblica**.", fonti = listOf("A1")),
                    BloccoSchema("passaggi", titolo = "Come arriva al potere", voci = listOf("Vince Antonio ad Azio", "Il senato gli dà il titolo di Augusto", "Tiene per sé esercito e province"), fonti = listOf("B1")),
                    BloccoSchema("prof", testo = "Il prof vuole le date precise: **14 d.C.**, non \"verso il\".", fonti = listOf("V1")),
                ),
            ),
            SezioneSchema(
                2, "Gli imperatori dopo Augusto",
                blocchi = listOf(
                    BloccoSchema("tabella", titolo = "Chi viene dopo", intestazione = listOf("Imperatore", "Regno", "Da ricordare"), righe = listOf(
                        listOf("Tiberio", "14-37", "Si fida poco del senato"),
                        listOf("Caligola", "37-41", "Quattro anni, molti capricci"),
                        listOf("Claudio", "41-54", "Conquista la Britannia"),
                    ), fonti = listOf("B1")),
                    BloccoSchema("immagine", n = 1, didascalia = "Una mappa dell'impero al tempo di Augusto", credito = "Immagine di esempio"),
                    BloccoSchema("punti", titolo = "Da non confondere", voci = listOf("Tiberio è il **figlio adottivo** di Augusto", "Seiano è il capo dei pretoriani"), fonti = listOf("A1")),
                ),
            ),
            SezioneSchema(
                3, "Perché l'impero regge",
                blocchi = listOf(
                    BloccoSchema("paragrafo", testo = "L'impero regge perché le decisioni sono di uno solo ma l'amministrazione resta ordinata: anche con un principe debole, la macchina va avanti.", fonti = listOf("B1", "A1")),
                ),
            ),
        ),
    )

    private val testoRiassunto = ContenutoRiassunto(
        listOf(
            ParteRiassunto("Augusto", paragrafi = listOf("Dopo le guerre civili Ottaviano diventa **Augusto**: non si fa chiamare re, ma tiene per sé esercito e province."), punti = listOf("Princeps: il primo dei cittadini", "Il senato resta, ma decide lui")),
            ParteRiassunto("Dopo di lui", paragrafi = listOf("Tiberio, Caligola e Claudio cambiano stile, ma il principato resta in piedi.")),
        ),
    )

    private val riassuntiEsempio = mutableListOf(
        Riassunto(51, SCHEMA_STORIA, 1, "misto", "L'età di Augusto", "pronto", prontoIl = adessoIso, creatoIl = adessoIso, parole = 180, contenuto = testoRiassunto),
    )

    private fun riassuntiDi(id: Long) = RiassuntiSchema(0.01, riassuntiEsempio.filter { it.schemaId == id }.map { RiassuntoBreve(it.id, it.forma, it.stato) })

    private val schemi = mutableListOf(
        Schema(
            SCHEMA_STORIA, 1, 3, "L'Impero romano da Augusto a Nerone", "L'età di Augusto", materiali = listOf(23, 24, 25), stato = "pronto",
            stimaEur = 0.08, creatoIl = adessoIso, prontoIl = adessoIso, carte = 3, contenuto = contenutoSchema,
        ),
    )

    private fun Schema.conRiassunti() = copy(riassunti = if (pronto) riassuntiDi(id) else null)

    private val carteLampo = listOf(
        CartaLampo(61, 1, "Storia", "ambra", "Perché Augusto non si fece mai chiamare re?", "Perché a Roma la parola re ricordava la **tirannia**: preferì il titolo di princeps.", listOf(libro), 1),
        CartaLampo(62, 1, "Storia", "ambra", "Che cosa erano i pretoriani?", "La guardia del corpo dell'imperatore, di stanza a Roma.", listOf(verifica), 2),
        CartaLampo(63, 1, "Storia", "ambra", "Quale provincia conquistò Claudio?", "La **Britannia**, nel 43 dopo Cristo.", listOf(libro), 1),
        CartaLampo(64, 2, "Diritto ed economia", "petrolio", "Che cosa vuol dire fonte del diritto?", "L'atto o il fatto da cui nascono le norme.", emptyList(), 1),
    )

    private val domandeSimulata = listOf(
        DomandaSimulata(1, "aperta", "Perché Augusto non si fece mai chiamare re?", 3.0, "Domande aperte"),
        DomandaSimulata(2, "aperta", "Spiega la differenza tra principato e monarchia.", 4.0, "Domande aperte"),
        DomandaSimulata(3, "definizione", "Che cosa erano i pretoriani?", 3.0, "Definizioni"),
        DomandaSimulata(4, "aperta", "Che cosa pensi del governo di Claudio?", 2.0, "Definizioni"),
    )

    private val esitoSimulata = EsitoSimulata(
        5.5, 10.0, 5.5, 6.0,
        listOf(
            EsitoDomandaSimulata(1, "giusta", 3.0, "Giusto: hai detto le cose importanti.", emptyList(), "Perché la parola re ricordava la tirannia: scelse il titolo di princeps.", listOf(libro)),
            EsitoDomandaSimulata(2, "parziale", 2.5, "Ci sei in parte: manca chi decide davvero.", listOf("il potere vero del principe"), "Nel principato le istituzioni restano, ma il potere vero è del principe.", listOf(libro, appunti)),
            EsitoDomandaSimulata(3, "sbagliata", 0.0, "Non erano soldati di frontiera.", emptyList(), "La guardia del corpo dell'imperatore, di stanza a Roma.", listOf(verifica)),
            EsitoDomandaSimulata(4, "non_corretta"),
        ),
        daRifare = 2,
    )

    private val simulate = mutableListOf(
        Simulata(
            SIMULATA_IN_CORSO, 1, 3, "L'Impero romano", "Verifica di allenamento", 50, listOf(21, 23, 24), stato = "in_corso", stimaEur = 0.03, creataIl = adessoIso, iniziataIl = adessoIso,
            domande = domandeSimulata, risposte = mapOf("1" to "Perché la parola re ricordava la tirannia."),
        ),
        Simulata(
            SIMULATA_CORRETTA, 1, null, "Augusto e Tiberio", "Augusto e Tiberio", 50, listOf(21, 23), stato = "corretta", stimaEur = 0.03, creataIl = adessoIso, iniziataIl = adessoIso, consegnataIl = adessoIso,
            domande = domandeSimulata,
            risposte = mapOf("1" to "Perché la parola re ricordava la tirannia.", "2" to "Nel principato restano il senato e i consoli.", "3" to "Erano soldati di frontiera."),
            esito = esitoSimulata,
        ),
    )

    private fun stimaStudio(costo: Double, minuti: Int, immagini: Int = 0): StimaStudio {
        val dopo = spesoMese + costo
        return StimaStudio(costo, spesoMese, 0.0, tetto, arrotonda(dopo), dopo <= tetto, null, primoDelMese, minuti, immagini)
    }

    override suspend fun stimaSchema(richiesta: RichiestaStimaSchema): Esito<StimaStudio> = Esito.Ok(stimaStudio(0.08, 4, 3))

    /** Nella visita lo schema è subito pronto: non si resta ad aspettare un lavoro finto. */
    override suspend fun creaSchema(nuovo: NuovoSchema): Esito<Schema> {
        val s = Schema(
            prossimoId++, nuovo.materiaId, nuovo.eventoId, nuovo.argomento, nuovo.argomento, nuovo.forma, nuovo.livello, nuovo.indicazioni, nuovo.materiali,
            "pronto", stimaEur = 0.08, creatoIl = adessoIso, prontoIl = adessoIso, contenuto = contenutoSchema,
        )
        schemi.add(0, s)
        return Esito.Ok(s.conRiassunti())
    }

    override suspend fun schemi(materiaId: Long): Lettura<List<Schema>> =
        Lettura(schemi.filter { it.materiaId == materiaId }.map { it.copy(contenuto = null).conRiassunti() })

    override suspend fun schema(id: Long): Lettura<Schema> =
        schemi.firstOrNull { it.id == id }?.let { Lettura(it.conRiassunti()) } ?: Lettura(null, "Non trovo più questo schema.")

    override suspend fun annullaSchema(id: Long): Esito<Unit> {
        val i = schemi.indexOfFirst { it.id == id }
        if (i >= 0) schemi[i] = schemi[i].copy(stato = "annullato")
        return Esito.Ok(Unit)
    }

    override suspend fun riprovaSchema(id: Long): Esito<Unit> {
        val i = schemi.indexOfFirst { it.id == id }
        if (i >= 0) schemi[i] = schemi[i].copy(stato = "pronto", contenuto = contenutoSchema)
        return Esito.Ok(Unit)
    }

    override suspend fun eliminaSchema(id: Long): Esito<Unit> {
        schemi.removeAll { it.id == id }
        riassuntiEsempio.removeAll { it.schemaId == id }
        return Esito.Ok(Unit)
    }

    /** Nella visita il PDF non si scarica: nessun file creato. */
    override suspend fun scaricaPdf(documento: DocumentoPdf, destinazione: File): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun immagineSchema(id: Long, n: Int, rinfresca: Boolean): Esito<ByteArray> =
        Esito.Ok(java.util.Base64.getDecoder().decode(FOTO_ESEMPIO))

    override suspend fun cambiaImmagine(id: Long, n: Int): Esito<Schema> =
        schemi.firstOrNull { it.id == id }?.let { Esito.Ok(it.conRiassunti()) } ?: Esito.Errore("Non trovo più questo schema.")

    override suspend fun togliImmagine(id: Long, n: Int): Esito<Schema> {
        val i = schemi.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questo schema.")
        val c = schemi[i].contenuto ?: return Esito.Errore("Non trovo più questa foto.")
        val senza = c.copy(sezioni = c.sezioni.map { s -> s.copy(blocchi = s.blocchi.filterNot { it.tipo == "immagine" && it.n == n }) })
        schemi[i] = schemi[i].copy(contenuto = senza)
        return Esito.Ok(schemi[i].conRiassunti())
    }

    override suspend fun creaRiassunto(schemaId: Long, forma: String): Esito<Riassunto> {
        riassuntiEsempio.firstOrNull { it.schemaId == schemaId && it.forma == forma }?.let { return Esito.Ok(it) }
        val r = Riassunto(prossimoId++, schemaId, 1, forma, "L'età di Augusto", "pronto", creatoIl = adessoIso, prontoIl = adessoIso, parole = 180, contenuto = testoRiassunto)
        riassuntiEsempio += r
        return Esito.Ok(r)
    }

    override suspend fun riassunti(schemaId: Long): Lettura<List<Riassunto>> = Lettura(riassuntiEsempio.filter { it.schemaId == schemaId })

    override suspend fun riassunto(id: Long): Lettura<Riassunto> =
        riassuntiEsempio.firstOrNull { it.id == id }?.let { Lettura(it) } ?: Lettura(null, "Non trovo più questo riassunto.")

    override suspend fun riprovaRiassunto(id: Long): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun eliminaRiassunto(id: Long): Esito<Unit> {
        riassuntiEsempio.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun chiarisci(corpo: CorpoChiarimento): Esito<Chiarimento> = Esito.Ok(
        Chiarimento(
            prossimoId++, corpo.passaggio, corpo.domanda.ifBlank { null },
            "Princeps vuol dire **il primo dei cittadini**. Augusto lo sceglie perché a Roma la parola re faceva paura: così il potere è suo, ma nessuno può accusarlo di essere un tiranno.",
            true, listOf(libro, appunti), 0.01, adessoIso,
        ),
    )

    /** Le schede restano in memoria anche dopo l'esito, così la visita si può rifare. */
    override suspend fun lampo(materiaId: Long?, limite: Int): Lettura<Lampo> {
        val carte = carteLampo.filter { materiaId == null || it.materiaId == materiaId }.take(limite)
        return Lettura(Lampo(carte, carte.size, 2))
    }

    override suspend fun esitoLampo(id: Long, esito: String): Esito<RispostaLampo> = Esito.Ok(RispostaLampo(2, giorno(3), carteLampo.size))

    override suspend fun togliCarta(id: Long): Esito<Unit> = Esito.Ok(Unit)

    override suspend fun stimaSimulata(richiesta: RichiestaStimaSimulata): Esito<StimaStudio> = Esito.Ok(stimaStudio(0.03, 2))

    override suspend fun creaSimulata(nuova: NuovaSimulata): Esito<Simulata> {
        val s = Simulata(
            prossimoId++, nuova.materiaId, nuova.eventoId, nuova.argomento, nuova.argomento, nuova.minuti, nuova.materiali, nuova.indicazioni,
            "pronta", stimaEur = 0.03, creataIl = adessoIso, domande = domandeSimulata,
        )
        simulate.add(0, s)
        return Esito.Ok(s)
    }

    override suspend fun simulate(materiaId: Long): Lettura<List<Simulata>> =
        Lettura(simulate.filter { it.materiaId == materiaId }.map { it.copy(domande = emptyList(), risposte = emptyMap(), esito = it.esito?.copy(domande = emptyList())) })

    override suspend fun simulata(id: Long): Lettura<Simulata> =
        simulate.firstOrNull { it.id == id }?.let { Lettura(it) } ?: Lettura(null, "Non trovo più questa verifica simulata.")

    override suspend fun annullaSimulata(id: Long): Esito<Unit> {
        val i = simulate.indexOfFirst { it.id == id }
        if (i >= 0) simulate[i] = simulate[i].copy(stato = "annullata")
        return Esito.Ok(Unit)
    }

    override suspend fun riprovaSimulata(id: Long): Esito<Unit> {
        val i = simulate.indexOfFirst { it.id == id }
        if (i >= 0) simulate[i] = simulate[i].copy(stato = "pronta", domande = domandeSimulata)
        return Esito.Ok(Unit)
    }

    override suspend fun eliminaSimulata(id: Long): Esito<Unit> {
        simulate.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun salvaSimulata(id: Long, corpo: CorpoSalvaSimulata): Esito<Simulata> {
        val i = simulate.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questa verifica simulata.")
        val s = simulate[i]
        simulate[i] = s.copy(risposte = s.risposte + corpo.risposte, stato = if (corpo.iniziata == true && s.pronta) "in_corso" else s.stato)
        return Esito.Ok(simulate[i])
    }

    override suspend fun consegnaSimulata(id: Long): Esito<Simulata> {
        val i = simulate.indexOfFirst { it.id == id }
        if (i < 0) return Esito.Errore("Non trovo più questa verifica simulata.")
        simulate[i] = simulate[i].copy(stato = "corretta", consegnataIl = adessoIso, esito = esitoSimulata)
        return Esito.Ok(simulate[i])
    }

    /** La lezione d'esempio completa (per il lettore della visita). */
    fun lezioneCompleta(id: Long = LEZIONE_STORIA): Lezione? = lezioni.firstOrNull { it.id == id }

    fun materiaBreve(id: Long): MateriaBreve? = materie.firstOrNull { it.id == id }?.let(::breve)

    companion object {
        /** Le voci d'esempio: Lorenzo (Mistral) e le tre voci gratis del NAS. */
        val VOCI = listOf(
            Voce("mistral:voice_482", "Lorenzo", "espressiva", "mistral", 0.0126, true),
            Voce("nas:giovanni", "Giovanni", "gratis", "nas", 0.0, true, 0.6),
            Voce("nas:giovanni_grande", "Giovanni grande", "gratis", "nas", 0.0, true, 1.3),
            Voce("nas:supertonic", "Supertonic", "gratis", "nas", 0.0, true, 1.1),
        )

        const val LEZIONE_STORIA = 12L
        const val MATERIA_STORIA = 1L
        const val SCHEMA_STORIA = 31L
        const val SIMULATA_IN_CORSO = 41L
        const val SIMULATA_CORRETTA = 42L
        /** La cartella "Imperatori" di Storia (aspetto.md §42.9). */
        const val CARTELLA_STORIA = 51L

        /** Una piccola immagine in PNG, per la foto dello schema d'esempio. */
        private const val FOTO_ESEMPIO = "iVBORw0KGgoAAAANSUhEUgAAACAAAAASCAIAAAC1qksFAAAD8ElEQVR42g3N4cpFMACA4e/+e39LKaWUwjo7jWUay05zFkKu6DvPDTx/dUKT0CaIhFeCTHgnqIQuoU/QCUOCSRgTbMKUMCe4hCXBJ3wSQsKa8E2ICVvCnnAknAlXwp3wJPzVKU1KmyJSXiky5Z2iUrqUPkWnDCkmZUyxKVPKnOJSlhSf8kkJKWvKNyWmbCl7ypFyplwpd8qT/oKMJqPNEBmvDJnxzlAZXUafoTOGDJMxZtiMKWPOcBlLhs/4ZISMNeObETO2jD3jyDgzrow748l+QU6T0+aInFeOzHnnqJwup8/ROUOOyRlzbM6UM+e4nCXH53xyQs6a882JOVvOnnPknDlXzp3z5L+goCloC0TBq0AWvAtUQVfQF+iCocAUjAW2YCqYC1zBUuALPgWhYC34FsSCrWAvOArOgqvgLniKX1DSlLQlouRVIkveJaqkK+lLdMlQYkrGElsylcwlrmQp8SWfklCylnxLYslWspccJWfJVXKXPOUvqGgq2gpR8aqQFe8KVdFV9BW6YqgwFWOFrZgq5gpXsVT4ik9FqFgrvhWxYqvYK46Ks+KquCue6hfUNDVtjah51ciad42q6Wr6Gl0z1JiascbWTDVzjatZanzNpybUrDXfmliz1ew1R81Zc9XcNU/9CwSNoBUIwUsgBW+BEnSCXqAFg8AIRoEVTIJZ4ASLwAs+giBYBV9BFGyCXXAITsEluAWP+AWSRtJKhOQlkZK3REk6SS/RkkFiJKPESibJLHGSReIlH0mQrJKvJEo2yS45JKfkktySR/4CRaNoFULxUkjFW6EUnaJXaMWgMIpRYRWTYlY4xaLwio8iKFbFVxEVm2JXHIpTcSluxaN+gabRtBqheWmk5q1Rmk7Ta7Rm0BjNqLGaSTNrnGbReM1HEzSr5quJmk2zaw7Nqbk0t+bRv8DQGFqDMLwM0vA2KENn6A3aMBiMYTRYw2SYDc6wGLzhYwiG1fA1RMNm2A2H4TRchtvwmF9gaSytRVheFml5W5Sls/QWbRksxjJarGWyzBZnWSze8rEEy2r5WqJls+yWw3JaLstteewvcDSO1iEcL4d0vB3K0Tl6h3YMDuMYHdYxOWaHcywO7/g4gmN1fB3RsTl2x+E4HZfjdjzuF3gaT+sRnpdHet4e5ek8vUd7Bo/xjB7rmTyzx3kWj/d8PMGzer6e6Nk8u+fwnJ7Lc3se/wsCTaANiMArIAPvgAp0gT6gA0PABMaADUyBOeACS8AHPoEQWAPfQAxsgT1wBM7AFbgDT/gFkSbSRkTkFZGRd0RFukgf0ZEhYiJjxEamyBxxkSXiI59IiKyRbyRGtsgeOSJn5IrckSfyD8eAbm9jjhFAAAAAAElFTkSuQmCC"

        /** Il testo per Gemini del contratto §12 (copia per la modalità esempio e senza rete). */
        val TESTO_GEMINI = """
            Trascrivi questa verifica in un file di testo, senza inventare niente. Usa sempre questo schema:
            MATERIA: … | PROF: … | DATA: … | TIPO: scritta/orale | VOTO: …
            Per ogni domanda:
            ## Domanda N (punti: …)
            TESTO: la domanda parola per parola
            MIA RISPOSTA: quello che ho scritto io
            CORREZIONI DEL PROF: tutto quello che ha segnato o scritto, anche a penna o a margine, indicando a quale punto si riferisce
            PUNTI PRESI: …
            Alla fine: COMMENTO DEL PROF: …
            Se una parte non si legge scrivi [illeggibile], non indovinare.
        """.trimIndent()
    }
}
