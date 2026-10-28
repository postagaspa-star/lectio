package eu.stgm.lectio

import eu.stgm.lectio.dati.Domanda
import eu.stgm.lectio.rete.Configurazione
import eu.stgm.lectio.rete.LectioClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Un server Lectio finto (MockWebServer): ogni "METODO /percorso" ha la sua
 * risposta (o una coda che si consuma in ordine, l'ultima resta). Registra tutte
 * le richieste, per contare per esempio che la modalità esempio non ne fa nessuna.
 */
class ServerFinto : AutoCloseable {
    private val server = MockWebServer()
    private val risposte = mutableMapOf<String, ArrayDeque<MockResponse>>()
    val richieste = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                richieste += request
                val chiave = "${request.method} ${request.path}"
                val coda = synchronized(risposte) {
                    risposte[chiave] ?: risposte["${request.method} ${request.path?.substringBefore('?')}"]
                } ?: return MockResponse().setResponseCode(404).setBody("""{"errore":"Non trovato (finto)","codice":"non_trovato"}""")
                return synchronized(risposte) { if (coda.size > 1) coda.removeFirst() else coda.first() }
            }
        }
        server.start()
    }

    val url: String get() = server.url("").toString().trimEnd('/')

    fun rispondi(metodoPercorso: String, corpo: String, codice: Int = 200) =
        rispondi(metodoPercorso, MockResponse().setResponseCode(codice).setBody(corpo).setHeader("Content-Type", "application/json"))

    fun rispondi(metodoPercorso: String, vararg risposta: MockResponse) {
        synchronized(risposte) { risposte[metodoPercorso] = ArrayDeque(risposta.toList()) }
    }

    fun richiesteA(metodoPercorso: String): List<RecordedRequest> =
        richieste.filter { "${it.method} ${it.path}" == metodoPercorso || "${it.method} ${it.path?.substringBefore('?')}" == metodoPercorso }

    fun client(token: String = "token-finto-di-prova", alNonAutorizzato: suspend () -> Unit = {}): LectioClient =
        LectioClient({ Configurazione(url, token, 3) }, alNonAutorizzato)

    fun spegni() = server.shutdown()

    override fun close() {
        try {
            server.shutdown()
        } catch (e: Exception) {
            // già spento
        }
    }
}

/** Main = Unconfined vero: le chiamate di rete finiscono su thread di OkHttp. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegolaMain : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(Dispatchers.Unconfined)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** Aspetta (al massimo 5 s) che [condizione] diventi vera. */
fun aspetta(messaggio: String = "condizione mai vera", condizione: () -> Boolean) = runBlocking {
    try {
        withTimeout(5_000) {
            while (!condizione()) delay(10)
        }
    } catch (e: Exception) {
        throw AssertionError("Tempo scaduto: $messaggio", e)
    }
}

/** Una domanda finta (Prof. Rossi): domanda 280,4-286,9, silenzio fino a 289,4, risposta fino a 301. */
fun domanda(
    id: Long = 31, n: Int = 1, di: Int = 6, inizio: Double = 280.4, fineDomanda: Double = 286.9,
    inizioRisposta: Double = 289.4, fine: Double = 301.0, tipo: String = "blocco",
) = Domanda(id, tipo, n, di, "Perché Augusto non si fece mai chiamare re?", "Perché a Roma la parola re ricordava la tirannia.", inizio, fineDomanda, inizioRisposta, fine)

/** Gli esempi del contratto (docs/contratto-api.md), con soli dati finti. */
object Esempi {
    const val MATERIA = """{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra","conteggi":{"verifiche":4,"interrogazioni":1,"appunti":3,"lezioni":2},"centratura":{"previste_uscite":10,"uscite_totali":16,"percentuale":63},"da_ripassare":3}"""

    const val LEZIONE = """{"id": 12, "materia_id": 1, "evento_id": 3, "argomento": "L'Impero romano da Augusto a Nerone",
 "durata_min": 20, "durata_s": 1187, "voce": "mistral:voice_482", "stato": "pronta",
 "avanzamento": {"fase": "voce", "fatto": 3, "totale": 7, "frase": "Registro la voce · 3 di 7"},
 "costo_eur": 0.24, "creata_il": "2026-10-27T18:00:00+01:00", "pronta_il": "2026-10-27T18:09:00+01:00", "errore": null, "nota": null,
 "ascolto": {"posizione_s": 462, "finita": false, "aggiornato_il": "2026-10-27T20:00:00+01:00"},
 "audio": {"byte": 14234112, "sha256": "abc"}, "domande_saltabili": false}"""

    const val DETTAGLIO = """{"id": 12, "materia_id": 1, "evento_id": 3, "argomento": "L'Impero romano da Augusto a Nerone",
 "durata_min": 20, "durata_s": 1187, "voce": "mistral:voice_482", "stato": "pronta", "costo_eur": 0.24,
 "capitoli": [{"n": 1, "titolo": "Augusto e il principato", "inizio_s": 0, "fine_s": 301}],
 "domande": [{"id": 31, "tipo": "blocco", "n": 1, "di": 6, "testo": "Perché Augusto non si fece mai chiamare re?",
              "risposta": "Perché a Roma la parola re ricordava la tirannia…",
              "inizio_s": 280.4, "fine_domanda_s": 286.9, "inizio_risposta_s": 289.4, "fine_s": 301.0,
              "mia_risposta": {"testo": "Per paura", "esito": "parziale", "commento": "Ci sei in parte.", "mancava": ["la tirannia"]}}],
 "testo": [{"capitolo": 1, "paragrafi": ["Primo.", "Secondo."]}]}"""

    const val OGGI = """{"oggi": "2026-10-28",
 "prossima": {"evento": {"id":3,"materia_id":1,"tipo":"scritta","argomento":"Impero romano","data":"2026-10-30","esito":null,"lezione_id":12},
              "materia": {"id":1,"nome":"Storia","colore":"ambra"}, "lezione": $LEZIONE, "tra_giorni": 2},
 "riprendi": null,
 "da_raccontare": [{"evento": {"id":4,"materia_id":3,"tipo":"scritta","argomento":"Present perfect","data":"2026-10-27","esito":null,"lezione_id":7}, "materia": {"id":3,"nome":"Inglese","colore":"verde"}}],
 "piu_avanti": [{"evento": {"id":5,"materia_id":4,"tipo":"orale","argomento":"Sviluppo","data":"2026-11-03","esito":null,"lezione_id":null}, "materia": {"id":4,"nome":"Scienze umane","colore":"viola"}, "tra_giorni": 6}],
 "spesa": {"speso_eur": 0.80, "tetto_eur": 2.00, "percentuale": 40, "si_azzera_il": "2026-11-01", "avviso": false},
 "modalita_prova": false}"""

    const val STIMA = """{"caratteri_stimati": 18300, "costo_testo_eur": 0.02, "costo_voce_eur": 0.23, "costo_eur": 0.25,
 "spesa_mese_eur": 0.80, "tetto_eur": 2.00, "dopo_eur": 1.05, "consentita": true,
 "motivo": null, "voce_gratis_caratteri_rimasti": 412000, "si_azzera_il": "2026-11-01"}"""
}
