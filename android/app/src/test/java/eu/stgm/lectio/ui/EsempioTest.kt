package eu.stgm.lectio.ui

import eu.stgm.lectio.RegolaMain
import eu.stgm.lectio.ServerFinto
import eu.stgm.lectio.aspetta
import eu.stgm.lectio.dati.SorgenteEsempio
import eu.stgm.lectio.lettore.LettoreEsempio
import eu.stgm.lectio.logica.FaseDomanda
import eu.stgm.lectio.logica.StatoRisposta
import eu.stgm.lectio.logica.TipoMateriale
import eu.stgm.lectio.logica.TipoVoce
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.ui.altre.ComeAndataViewModel
import eu.stgm.lectio.ui.gemini.GeminiViewModel
import eu.stgm.lectio.ui.impostazioni.ImpostazioniViewModel
import eu.stgm.lectio.ui.lettore.LettoreViewModel
import eu.stgm.lectio.ui.lezione.NuovaLezioneViewModel
import eu.stgm.lectio.ui.lezioni.LezioniViewModel
import eu.stgm.lectio.ui.materiale.AggiuntaViewModel
import eu.stgm.lectio.ui.materiale.RichiestaAggiunta
import eu.stgm.lectio.ui.materie.MateriaViewModel
import eu.stgm.lectio.ui.materie.MaterieViewModel
import eu.stgm.lectio.ui.oggi.OggiViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * La modalità esempio della visita guidata (aspetto.md §12): stesse schermate,
 * dati finti in memoria, NESSUNA chiamata al server. Qui tutte le schermate
 * girano sui dati d'esempio con un server finto acceso, che non deve ricevere niente.
 */
class EsempioTest {
    @get:Rule
    val main = RegolaMain()

    private val server = ServerFinto()
    private val oggi = LocalDate.of(2026, 10, 28)
    private val esempio = SorgenteEsempio(oggi)
    private val ambito = MainScope()

    @After
    fun chiudi() {
        ambito.cancel()
        server.close()
    }

    @Test
    fun laSorgenteDEsempioNonHaStradePerLaRete() {
        val campi = SorgenteEsempio::class.java.declaredFields.map { it.type }
        assertFalse(campi.any { LectioClient::class.java.isAssignableFrom(it) || OkHttpClient::class.java.isAssignableFrom(it) })
        assertTrue(esempio.esempio)
    }

    @Test
    fun tutteLeSchermateSuiDatiDEsempioSenzaRichieste() {
        val oggiVm = OggiViewModel(esempio).apply { carica() }
        aspetta { oggiVm.stato.value.oggi != null }
        val o = oggiVm.stato.value.oggi!!
        assertEquals("Storia", o.prossima?.materia?.nome)
        assertEquals(2, o.prossima?.traGiorni)
        assertNotNull(oggiVm.stato.value.dettaglioProssima)

        val materie = MaterieViewModel(esempio).apply { carica() }
        aspetta { materie.stato.value.letta }
        assertTrue(materie.stato.value.materie.size >= 4)

        val materia = MateriaViewModel(esempio, SorgenteEsempio.MATERIA_STORIA) { oggi }.apply { carica() }
        aspetta { materia.stato.value.scheda != null }
        assertEquals("pronta", materia.stato.value.scheda?.stato)
        assertTrue(materia.stato.value.ripassare.isNotEmpty())

        val nuova = NuovaLezioneViewModel(esempio, SorgenteEsempio.MATERIA_STORIA, 3).apply { carica() }
        aspetta { nuova.stato.value.stimaEspressiva != null }
        assertEquals("Prepara la lezione · 0,27 €", nuova.stato.value.costo.etichettaPulsante)
        nuova.voce(TipoVoce.GRATIS)
        assertEquals("Prepara la lezione · 0,02 €", nuova.stato.value.costo.etichettaPulsante)
        nuova.durata(45)
        aspetta { nuova.stato.value.stimaGratis?.costoEur == 0.04 }
        nuova.prepara()
        aspetta { nuova.stato.value.creata != null }

        val aggiunta = AggiuntaViewModel(esempio, null)
        aggiunta.apri(RichiestaAggiunta(SorgenteEsempio.MATERIA_STORIA))
        aggiunta.campi { it.copy(data = "2026-10-27") }
        aggiunta.inviaTesto(TipoMateriale.APPUNTI, "Augusto", "Princeps, non re.")
        aspetta { aggiunta.stato.value.fatto }

        val lezioni = LezioniViewModel(esempio).apply { carica() }
        aspetta { lezioni.stato.value.letta }
        assertTrue(lezioni.stato.value.lezioni.any { it.inLavoro })

        val gemini = GeminiViewModel(esempio).apply { carica() }
        aspetta { gemini.testo.value != null }
        assertTrue(gemini.testo.value!!.startsWith("Trascrivi questa verifica"))

        val impostazioni = ImpostazioniViewModel(esempio).apply { carica() }
        aspetta { impostazioni.stato.value.impostazioni != null }
        impostazioni.cambia(buildJsonObject { put("domande_saltabili", true) })
        aspetta { impostazioni.stato.value.impostazioni?.domandeSaltabili == true }

        val esito = ComeAndataViewModel(esempio, 4).apply { carica() }
        aspetta { esito.stato.value.evento != null }
        esito.altre("Che cos'è il present perfect?")
        esito.salva()
        aspetta { esito.stato.value.risultato != null }

        val lettore = LettoreEsempio(ambito, correttore = esempio::rispondi, attesaCorrezioneMs = 0)
        val vm = LettoreViewModel(esempio, lettore)
        vm.apri(SorgenteEsempio.LEZIONE_STORIA, allaDomanda = 32)
        aspetta { lettore.stato.value.fermate.fase == FaseDomanda.ALLA_DOMANDA }
        vm.bozza(32, "Non si fidava del senato")
        vm.invia(32)
        aspetta { lettore.stato.value.risposta is StatoRisposta.Corretta }
        assertEquals("parziale", (lettore.stato.value.risposta as StatoRisposta.Corretta).correzione.esito)
        lettore.ferma()

        assertTrue("la modalità esempio ha chiamato il server: ${server.richieste.map { it.path }}", server.richieste.isEmpty())
    }

    @Test
    fun iDatiDEsempioSonoQuelliDelPrototipo() = kotlinx.coroutines.runBlocking {
        val o = esempio.oggi().dato!!
        assertEquals("L'Impero romano", o.prossima?.evento?.argomento)
        assertEquals("Le fonti del diritto", o.riprendi?.argomento)
        assertEquals("Inglese", o.daRaccontare.single().materia.nome)
        assertEquals(0.80, o.spesa.spesoEur, 0.0)
        val l = esempio.lezioneCompleta()!!
        assertEquals(4, l.capitoli.size)
        assertEquals(6, l.domande.size)
        assertEquals(1187.0, l.durataSecondi, 0.0)
        val nomi = esempio.materie().dato!!.map { it.prof }.toSet()
        assertTrue("solo nomi di fantasia: $nomi", nomi.all { it in setOf("Rossi", "Bianchi") })
    }
}
