package eu.stgm.lectio.dati

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

    private fun giorno(dopo: Long): String = oggi.plusDays(dopo).toString()
    private val adessoIso: String = OffsetDateTime.of(oggi.atTime(9, 0), ZoneOffset.ofHours(1)).toString()
    private val primoDelMese: String = oggi.withDayOfMonth(1).plusMonths(1).toString()

    // --- Materie ------------------------------------------------------------------------------

    private val storia = Materia(1, "Storia", "Rossi", "ambra", Conteggi(4, 1, 3, 2), Centratura(10, 16, 63), daRipassare = 2)
    private val diritto = Materia(2, "Diritto ed economia", "Bianchi", "petrolio", Conteggi(2, 0, 2, 1), Centratura(4, 7, 57))
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
        voce = "azure:it-IT-IsabellaMultilingualNeural", stato = "pronta", costoEur = 0.02, creataIl = adessoIso,
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

    private val materiale = mutableListOf(
        Materiale(21, 1, "verifica", "Verifica corretta", giorno(-16), "file", "verifica.txt", 5321, "pronto", creatoIl = adessoIso,
            testo = "MATERIA: Storia | PROF: Rossi | DATA: ${giorno(-16)} | TIPO: scritta | VOTO: 7\n## Domanda 1 (punti: 2)\nTESTO: Perché Augusto non si fece chiamare re?"),
        Materiale(22, 1, "interrogazione", "Interrogazione", giorno(-8), "scritto", null, 812, "pronto", creatoIl = adessoIso,
            testo = "Mi ha chiesto chi era Seiano. Ho risposto che era il capo dei pretoriani."),
        Materiale(23, 1, "appunti", "Appunti: Augusto e il principato", giorno(-6), "scritto", null, 2410, "pronto", creatoIl = adessoIso,
            testo = "Augusto: princeps, non re. Pax augusta. Riforme dell'esercito."),
    )

    private var spesoMese = 0.80
    private val tetto = 2.00

    private var impostazioni = Impostazioni(2.0, "mistral:voice_482", 20, true, 1.0, "Andrea")

    // --- Sorgente -----------------------------------------------------------------------------

    override suspend fun salute(): Esito<Salute> = Esito.Ok(
        Salute(true, "1.0.0", false, StatoServizio("pronto", "mistral"), StatoServizio("pronta", "mistral"), StatoServizio("pronta", "azure")),
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

    override suspend fun caricaFile(materiaId: Long, file: FileDaCaricare, tipo: String, titolo: String?, data: String?): Esito<Materiale> {
        val m = Materiale(prossimoId++, materiaId, tipo, titolo?.takeIf { it.isNotBlank() } ?: file.nome.substringBeforeLast('.'),
            data ?: oggi.toString(), "file", file.nome, 1200, "pronto", creatoIl = adessoIso, testo = "Testo di esempio.")
        materiale += m
        return Esito.Ok(m)
    }

    override suspend fun caricaTesto(materiaId: Long, scritto: MaterialeScritto): Esito<Materiale> {
        val m = Materiale(prossimoId++, materiaId, scritto.tipo, scritto.titolo?.takeIf { it.isNotBlank() } ?: "Appunti",
            scritto.data ?: oggi.toString(), "scritto", null, scritto.testo.length, "pronto", creatoIl = adessoIso, testo = scritto.testo)
        materiale += m
        return Esito.Ok(m)
    }

    override suspend fun eliminaMateriale(id: Long): Esito<Unit> {
        materiale.removeAll { it.id == id }
        return Esito.Ok(Unit)
    }

    override suspend fun riprovaMateriale(id: Long): Esito<Unit> = Esito.Ok(Unit)

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

    override suspend fun voci(): Esito<List<Voce>> = Esito.Ok(
        listOf(
            Voce("mistral:voice_482", "Lorenzo", "espressiva", "mistral", 0.0126, true),
            Voce("azure:it-IT-IsabellaMultilingualNeural", "Isabella", "gratis", "azure", 0.0, true),
        ),
    )

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
        val dopo = spesoMese + costo
        val caratteri = (richiesta.durataMin * 145 * 6.3).toInt()
        return Esito.Ok(
            Stima(
                caratteri, testo, if (espressiva) totale - testo else 0.0, costo, spesoMese, tetto, arrotonda(dopo),
                dopo <= tetto, if (dopo <= tetto) null else "Sforerebbe il tetto di 2 €: usa la voce gratis",
                412_000, primoDelMese,
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
        val l = Lezione(prossimoId++, nuova.materiaId, nuova.eventoId, nuova.argomento, nuova.durataMin, null, nuova.voce, "in_preparazione",
            Avanzamento("lettura", 0, 0, "Leggo il materiale"), 0.0, adessoIso, domandeSaltabili = nuova.domandeSaltabili)
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

    /** La lezione d'esempio completa (per il lettore della visita). */
    fun lezioneCompleta(id: Long = LEZIONE_STORIA): Lezione? = lezioni.firstOrNull { it.id == id }

    fun materiaBreve(id: Long): MateriaBreve? = materie.firstOrNull { it.id == id }?.let(::breve)

    companion object {
        const val LEZIONE_STORIA = 12L
        const val MATERIA_STORIA = 1L

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
