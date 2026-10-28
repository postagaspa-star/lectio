package eu.stgm.lectio.rete

import eu.stgm.lectio.dati.CorpoAscolto
import eu.stgm.lectio.dati.CorpoRisposta
import eu.stgm.lectio.dati.MaterialeScritto
import eu.stgm.lectio.dati.NuovaLezione
import eu.stgm.lectio.dati.NuovaMateria
import eu.stgm.lectio.dati.NuovoEvento
import eu.stgm.lectio.dati.RichiestaStima
import eu.stgm.lectio.logica.Costi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Il client contro il server VERO (in modalità prova, dati finti), solo se lo si
 * chiede: gradlew :app:testDebugUnitTest --tests '*ServerVeroTest*'
 *   -Dlectio.server=http://127.0.0.1:8765 -Dlectio.codice=12345678
 * (le proprietà passano dalle variabili LECTIO_SERVER e LECTIO_CODICE). Senza: saltato.
 */
class ServerVeroTest {

    @Test
    fun tuttoIlGiroControIlServerVero() = runBlocking {
        val server = System.getenv("LECTIO_SERVER")
        val codice = System.getenv("LECTIO_CODICE")
        assumeTrue("server vero non indicato", !server.isNullOrBlank() && !codice.isNullOrBlank())
        server!!

        val abbinato = LectioClient.abbina(server, codice!!, "Telefono di prova")
        assertTrue("abbinamento: $abbinato", abbinato is Esito.Ok)
        val token = (abbinato as Esito.Ok).dato.token
        val idServer = abbinato.dato.serverId ?: (LectioClient.serverIdDi(server) as? Esito.Ok)?.dato
        val c = LectioClient({ Configurazione(server, token, abbinato.dato.dispositivoId, serverId = idServer) })

        val salute = c.salute()
        assertTrue("salute: $salute", salute is Esito.Ok)
        println("salute: ${(salute as Esito.Ok).dato}")

        val nome = "Storia ${System.currentTimeMillis() % 100000}"
        val materia = c.creaMateria(NuovaMateria(nome, "Rossi", "ambra"))
        assertTrue("materia: $materia", materia is Esito.Ok)
        val m = (materia as Esito.Ok).dato
        val doppione = c.creaMateria(NuovaMateria(nome.uppercase(), "Rossi", null))
        println("doppione: $doppione")
        assertTrue(doppione is Esito.Errore && doppione.messaggio.isNotBlank())

        assertTrue(c.materie() is Esito.Ok)
        val scheda = c.scheda(m.id)
        assertTrue("scheda: $scheda", scheda is Esito.Ok)
        println("scheda: ${(scheda as Esito.Ok).dato.stato}")

        val appunti = c.caricaTesto(m.id, MaterialeScritto("appunti", "Augusto e il principato", LocalDate.now().toString(),
            "Augusto prese il titolo di princeps. Le istituzioni della repubblica restarono, ma il potere era suo. Tiberio gli succedette nel 14 dopo Cristo."))
        assertTrue("appunti: $appunti", appunti is Esito.Ok)

        val f = File.createTempFile("verifica", ".txt").apply {
            writeText("MATERIA: Storia | PROF: Rossi | DATA: 12/10 | TIPO: scritta | VOTO: 7\n## Domanda 1 (punti: 2)\nTESTO: Perché Augusto non si fece chiamare re?\nMIA RISPOSTA: Per prudenza\nCORREZIONI DEL PROF: manca il perché\nPUNTI PRESI: 1\n")
        }
        val verifica = c.caricaFile(m.id, FileDaCaricare(f, "verifica.txt", "text/plain"), "verifica", null, null)
        assertTrue("verifica: $verifica", verifica is Esito.Ok)
        f.delete()
        val elenco = c.materiale(m.id)
        assertTrue("materiale: $elenco", elenco is Esito.Ok && (elenco as Esito.Ok).dato.size == 2)
        val dettaglio = c.materialeDettaglio((verifica as Esito.Ok).dato.id)
        assertTrue("dettaglio: $dettaglio", dettaglio is Esito.Ok)

        val evento = c.creaEvento(m.id, NuovoEvento("scritta", "L'Impero romano", LocalDate.now().plusDays(2).toString()))
        assertTrue("evento: $evento", evento is Esito.Ok)
        val oggi = c.oggi()
        assertTrue("oggi: $oggi", oggi is Esito.Ok)
        println("oggi: prossima=${(oggi as Esito.Ok).dato.prossima?.materia?.nome} prova=${oggi.dato.modalitaProva}")

        val voci = c.voci()
        assertTrue("voci: $voci", voci is Esito.Ok)
        val disponibili = Costi.voci((voci as Esito.Ok).dato)
        println("voci: ${voci.dato.map { it.id + "/" + it.tipo + "/" + it.disponibile }}")
        // Sul PC senza espeak-ng nessuna voce è pronta: si prova lo stesso (la lezione finirà in errore).
        val voce = disponibili.prova ?: disponibili.gratis ?: disponibili.espressiva ?: voci.dato.lastOrNull()
        assertTrue("nessuna voce", voce != null)
        val stima = c.stima(RichiestaStima(m.id, 15, voce!!.id))
        assertTrue("stima: $stima", stima is Esito.Ok)
        println("stima: ${(stima as Esito.Ok).dato}")

        val imp = c.impostazioni()
        assertTrue("impostazioni: $imp", imp is Esito.Ok)
        println("impostazioni: ${(imp as Esito.Ok).dato}")
        val cambiate = c.modificaImpostazioni(buildJsonObject { put("domande_saltabili", true) })
        assertTrue("patch impostazioni: $cambiate", cambiate is Esito.Ok && (cambiate as Esito.Ok).dato.domandeSaltabili)
        c.modificaImpostazioni(buildJsonObject { put("domande_saltabili", false) })

        assertTrue(c.gemini() is Esito.Ok)
        assertTrue(c.spesa() is Esito.Ok)
        assertTrue(c.eventi() is Esito.Ok)

        val nuova = c.creaLezione(NuovaLezione(m.id, (evento as Esito.Ok).dato.id, "L'Impero romano", null, 15, voce.id, false))
        assertTrue("lezione: $nuova", nuova is Esito.Ok)
        val id = (nuova as Esito.Ok).dato.id
        // La si segue come fa l'app (qui ogni 2 s) fino a pronta o errore.
        var lezione = nuova.dato
        repeat(90) {
            if (!lezione.inLavoro) return@repeat
            delay(2_000)
            lezione = (c.lezione(id) as Esito.Ok).dato
            println("lezione: ${lezione.stato} ${lezione.avanzamento?.frase ?: ""} ${lezione.errore ?: ""}")
        }
        println("fine: ${lezione.stato} errore=${lezione.errore}")
        assertTrue("stato finale strano: ${lezione.stato}", lezione.pronta || lezione.fallita)
        if (lezione.pronta) {
            assertTrue(lezione.capitoli.isNotEmpty())
            assertTrue(lezione.domande.isNotEmpty())
            val d = lezione.domande.first()
            assertTrue(d.fineDomandaS > d.inizioS && d.inizioRispostaS > d.fineDomandaS)
            val audio = File.createTempFile("lezione", ".mp3")
            assertTrue(c.scaricaAudio(id, audio, lezione.audio?.sha256) is Esito.Ok)
            assertTrue(audio.length() > 0)
            audio.delete()
            assertTrue(c.salvaAscolto(id, CorpoAscolto(12.5, false, java.time.Instant.now().toString())) is Esito.Ok)
            val risposta = c.rispondi(id, d.id, CorpoRisposta("Perché la parola re ricordava la tirannia", false))
            println("risposta: $risposta")
            assertTrue(risposta is Esito.Ok)
            val saltata = c.rispondi(id, lezione.domande.last().id, CorpoRisposta("", true))
            assertEquals("saltata", (saltata as Esito.Ok).dato.esito)
            val ripasso = c.daRipassare(m.id)
            println("da ripassare: $ripasso")
            assertTrue(ripasso is Esito.Ok && (ripasso as Esito.Ok).dato.isNotEmpty())
            val dopo = (c.lezione(id) as Esito.Ok).dato
            assertTrue(dopo.ascolto != null)
            val patch = c.modificaLezione(id, buildJsonObject { put("domande_saltabili", true) })
            assertTrue("patch lezione: $patch", patch is Esito.Ok && (patch as Esito.Ok).dato.domandeSaltabili)
        }
        val novita = c.novita(0)
        println("novità: $novita")
        assertTrue(novita is Esito.Ok)
        assertTrue(c.eliminaMateria(m.id) is Esito.Ok)
    }
}
