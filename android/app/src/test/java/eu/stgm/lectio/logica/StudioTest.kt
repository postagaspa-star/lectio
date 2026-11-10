package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.BloccoSchema
import eu.stgm.lectio.dati.CartaLampo
import eu.stgm.lectio.dati.CopiaSimulata
import eu.stgm.lectio.dati.DepositoStudio
import eu.stgm.lectio.dati.DomandaSimulata
import eu.stgm.lectio.dati.EsitoDomandaSimulata
import eu.stgm.lectio.dati.EsitoSimulata
import eu.stgm.lectio.dati.Fonte
import eu.stgm.lectio.dati.RiassuntoBreve
import eu.stgm.lectio.dati.RispostaLampo
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Simulata
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.ui.tema.NOMI_COLORI
import eu.stgm.lectio.ui.tema.ORDINE_COLORI
import eu.stgm.lectio.ui.tema.coloreMateria
import eu.stgm.lectio.ui.tema.nomeColore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTest {
    private val libro = Fonte(1, "Libro", "libro", 42, "Libro, pag. 42")
    private val verifica = Fonte(2, "Verifica", "verifica", null, "Verifica del 12/10")
    private val appunti = Fonte(3, "Appunti", "appunti", null, "Appunti del 28/10")

    @Test
    fun grassettoSoloTraDoppiAsterischi() {
        assertEquals(
            listOf(PezzoTesto("Il ", false), PezzoTesto("Discobolo", true), PezzoTesto(" è bronzo.", false)),
            Grassetto.pezzi("Il **Discobolo** è bronzo."),
        )
        assertEquals(listOf(PezzoTesto("niente", false)), Grassetto.pezzi("niente"))
        // Due asterischi spaiati non fanno mai diventare grassetto tutto il resto, né si vedono.
        assertEquals(listOf(PezzoTesto("a ", false), PezzoTesto("b", false)), Grassetto.pezzi("a **b"))
        assertEquals("Il Discobolo", Grassetto.togli("Il **Discobolo**"))
    }

    @Test
    fun segniDiFonte() {
        val fonti = mapOf("B1" to libro, "V1" to verifica, "A1" to appunti)
        val tre = SegniFonte.etichette(listOf("B1", "V1", "A1", "ZZ", "B1"), fonti)
        assertEquals(3, tre.size)
        assertEquals("Libro, pag. 42 · Verifica del 12/10 +1", SegniFonte.riga(tre))
        assertEquals("Libro, pag. 42", SegniFonte.riga(tre.take(1)))
        assertNull(SegniFonte.riga(emptyList()))
        assertEquals("3 fonti", SegniFonte.pillola(tre))
        assertEquals("Libro, pag. 42", SegniFonte.pillola(tre.take(1)))
        assertNull(SegniFonte.pillola(emptyList()))
    }

    @Test
    fun conPiuBlocchiUgualiIlSegnoStaSoloSullUltimo() {
        val blocchi = listOf(
            BloccoSchema("paragrafo", fonti = listOf("B1")),
            BloccoSchema("punti", fonti = listOf("B1")),
            BloccoSchema("definizione", fonti = listOf("V1")),
            BloccoSchema("tabella", fonti = emptyList()),
        )
        assertEquals(setOf(1, 2), SegniFonte.visibili(blocchi))
    }

    @Test
    fun ITestiDelloSchema() {
        assertEquals("A capitoli · Completo", TestiSchema.sottoRiga(Schema(stato = "pronto", forma = "capitoli", livello = "completo")))
        // `auto` non si scrive (aspetto.md §31.2).
        assertEquals("Completo", TestiSchema.sottoRiga(Schema(stato = "pronto", forma = "auto", livello = "completo")))
        assertEquals("In coda", TestiSchema.sottoRiga(Schema(stato = "in_coda")))
        assertEquals("Scrivo la parte 2", TestiSchema.sottoRiga(Schema(stato = "in_preparazione", frase = "Scrivo la parte 2")))
        assertEquals("Non è riuscito", TestiSchema.sottoRiga(Schema(stato = "errore")))
    }

    @Test
    fun iRiassunti() {
        assertEquals(FormaRiassunto.PUNTI, FormaRiassunto.di("punti"))
        assertEquals(FormaRiassunto.MISTO, FormaRiassunto.di("sconosciuta"))
        assertEquals("Pronto", TestiRiassunto.statoForma("pronto"))
        assertEquals("Lo sto scrivendo", TestiRiassunto.statoForma("in_coda"))
        assertEquals("Si è fermato", TestiRiassunto.statoForma("errore"))
        assertNull(TestiRiassunto.statoForma(null))
        assertEquals("punti", TestiRiassunto.ultimo(listOf(RiassuntoBreve(1, "misto", "pronto"), RiassuntoBreve(2, "punti", "pronto")))?.forma)
        assertNull(TestiRiassunto.ultimo(emptyList()))
        assertEquals("480 parole · 3 min di lettura", TestiRiassunto.sottoTitolo(480))
        assertEquals(1, TestiRiassunto.minuti(10))
    }

    @Test
    fun ilLampoContaVentiSecondiAScheda() {
        assertEquals(3, TestiLampo.minuti(8))
        assertEquals(1, TestiLampo.minuti(1))
        assertEquals("8 schede · circa 3 min", TestiLampo.riga(8, 3))
        assertEquals("1 scheda · circa 1 min", TestiLampo.riga(1, 1))
        assertEquals("Domani 4 schede", TestiLampo.domani(4))
        assertEquals("12 schede in 3 minuti", TestiLampo.durata(12, 170_000))
        assertEquals("1 scheda in 1 minuto", TestiLampo.durata(1, 5_000))
    }

    @Test
    fun ilTempoDellaVerificaSoloMinuti() {
        assertEquals("50 min", TempoSimulata.etichetta(50, 0))
        assertEquals("48 min", TempoSimulata.etichetta(50, 120))
        assertEquals("meno di 1 min", TempoSimulata.etichetta(50, 50 * 60 - 30))
        assertEquals("+1 min", TempoSimulata.etichetta(50, 50 * 60 + 10))
        assertEquals("+2 min", TempoSimulata.etichetta(50, 50 * 60 + 130))
        assertFalse(TempoSimulata.tempoDiAvviso(50, 44 * 60))
        assertTrue(TempoSimulata.tempoDiAvviso(50, 45 * 60))
        assertFalse(TempoSimulata.tempoDiAvviso(50, 50 * 60))
        assertTrue(TempoSimulata.finito(50, 50 * 60))
        assertEquals("Consegnata dopo 56 minuti su 50", TempoSimulata.oltre(50, 56 * 60))
        assertNull(TempoSimulata.oltre(50, 40 * 60))
        // Senza secondi contati sul telefono (fatta su carta o altrove) la riga non c'è.
        assertNull(TempoSimulata.oltre(50, null))
    }

    @Test
    fun iTestiDellaSimulata() {
        assertEquals("Pronta · 50 min", TestiSimulata.sottoRiga(Simulata(stato = "pronta", minuti = 50)))
        assertEquals("A metà", TestiSimulata.sottoRiga(Simulata(stato = "in_corso")))
        assertEquals("La sto correggendo", TestiSimulata.sottoRiga(Simulata(stato = "in_correzione")))
        assertEquals("Corretta", TestiSimulata.sottoRiga(Simulata(stato = "corretta")))
        assertEquals("Corretta · stima circa 6,5", TestiSimulata.sottoRiga(Simulata(stato = "corretta", esito = EsitoSimulata(votoStimato = 6.5))))
        val domande = listOf(DomandaSimulata(1, punti = 3.0), DomandaSimulata(2, punti = 7.0))
        assertEquals("2 domande · 10 punti · 50 minuti", TestiSimulata.intestazione(Simulata(minuti = 50, domande = domande)))
        assertEquals("1 scritta su 2 · 1 vuota", TestiSimulata.rigaConsegna(domande, mapOf("1" to "x", "2" to "  ")))
        assertEquals("2 scritte su 2", TestiSimulata.rigaConsegna(domande, mapOf("1" to "x", "2" to "y")))
        assertEquals("62 punti su 100 · la sufficienza è a 58", TestiSimulata.rigaPunti(EsitoSimulata(62.0, 100.0, soglia = 58.0)))
        assertEquals("62 punti su 100", TestiSimulata.rigaPunti(EsitoSimulata(62.0, 100.0)))
        assertEquals("2,5 su 3", TestiSimulata.puntiDomanda(EsitoDomandaSimulata(1, "parziale", 2.5), domande[0]))
        // Una domanda che non si è potuta correggere non ha punti, nemmeno zero.
        assertNull(TestiSimulata.puntiDomanda(EsitoDomandaSimulata(1, "non_corretta"), domande[0]))
        assertEquals("Confronta tu", TestiSimulata.parolaEsito("non_corretta"))
        assertEquals("Quasi", TestiSimulata.parolaEsito("parziale"))
    }

    @Test
    fun leRisposteDelTelefonoNonSiPerdono() {
        val server = mapOf("1" to "vecchia", "2" to "sul server")
        val locali = mapOf("1" to "nuova")
        assertEquals(mapOf("1" to "nuova", "2" to "sul server"), TestiSimulata.unisci(server, locali, daInviare = true))
        assertEquals(server, TestiSimulata.unisci(server, locali, daInviare = false))
        assertEquals(locali, TestiSimulata.unisci(emptyMap(), locali, daInviare = false))
        assertEquals(server, TestiSimulata.unisci(server, null, daInviare = true))
    }

    @Test
    fun iNumeriSenzaZeri() {
        assertEquals("6,5", numeroBreve(6.5))
        assertEquals("6", numeroBreve(6.0))
        assertEquals("10", numeroBreve(10.04))
    }

    @Test
    fun iSediciColori() {
        assertEquals(16, NOMI_COLORI.size)
        assertEquals(16, NOMI_COLORI.toSet().size)
        assertEquals(NOMI_COLORI.toSet(), ORDINE_COLORI.toSet())
        // Il primo libero parte dai colori di sempre, sullo schermo stanno per tinta.
        assertEquals(listOf("ambra", "rosa", "petrolio", "viola", "blu", "verde", "arancio", "oliva"), NOMI_COLORI.take(8))
        assertEquals("rosa", ORDINE_COLORI.first())
        assertEquals("prugna", ORDINE_COLORI.last())
        assertEquals("Giada", nomeColore("giada"))
        assertEquals(16, NOMI_COLORI.map { coloreMateria(it, false).pieno }.toSet().size)
        NOMI_COLORI.forEach { assertNotEquals(it, coloreMateria(it, false).tenue, coloreMateria(it, true).tenue) }
        // Un nome sconosciuto diventa ambra (aspetto.md §37).
        assertEquals(coloreMateria("ambra", false), coloreMateria("fucsia", false))
        assertEquals(coloreMateria("ambra", false), coloreMateria(null, false))
    }
}

class SessioneLampoTest {
    private fun carta(id: Long) = CartaLampo(id, 1, "Storia", "ambra", "Domanda $id", "Risposta $id")
    private val tre = SessioneLampo(listOf(carta(1), carta(2), carta(3)))

    private fun SessioneLampo.dai(esito: String, alle: Long): Pair<SessioneLampo, List<EsitoDato>> = mostra(alle).esito(esito, alle + 400)

    @Test
    fun iTreEsitiAspettano300Millisecondi() {
        val aperta = tre.mostra(1000)
        assertFalse(aperta.esitiAttivi(1299))
        assertTrue(aperta.esitiAttivi(1300))
        assertEquals(aperta to emptyList<EsitoDato>(), aperta.esito("sapevo", 1100))
        // Senza risposta aperta niente esito.
        assertEquals(tre to emptyList<EsitoDato>(), tre.esito("sapevo", 5000))
    }

    @Test
    fun lUltimoEsitoAspettaIlProssimoPrimaDiPartire() {
        val (s1, mandati1) = tre.dai("sapevo", 0)
        assertTrue(mandati1.isEmpty())
        assertEquals(EsitoDato(1, "sapevo"), s1.inSospeso)
        assertTrue(s1.puoAnnullare)
        assertEquals("2 di 3", s1.conteggio)

        val (s2, mandati2) = s1.dai("quasi", 0)
        assertEquals(listOf(EsitoDato(1, "sapevo")), mandati2)
        assertEquals(EsitoDato(2, "quasi"), s2.inSospeso)

        val (fine, mandati3) = s2.dai("non_sapevo", 0)
        assertEquals(listOf(EsitoDato(2, "quasi"), EsitoDato(3, "non_sapevo")), mandati3)
        assertTrue(fine.finita)
        assertNull(fine.inSospeso)
        assertEquals(1, fine.sapevo)
        assertEquals(1, fine.quasi)
        assertEquals(1, fine.nonSapevo)
        assertEquals(3, fine.fatte)
    }

    @Test
    fun annullaRiportaLaSchedaUnaVoltaSola() {
        val (s1, _) = tre.dai("quasi", 0)
        val indietro = s1.annulla()
        assertEquals(0, indietro.indice)
        assertTrue(indietro.rispostaAperta)
        assertEquals(0, indietro.quasi)
        assertNull(indietro.inSospeso)
        assertFalse(indietro.puoAnnullare)
        assertEquals(indietro, indietro.annulla())
    }

    @Test
    fun uscendoQuelloCheEraInSospesoParte() {
        val (s1, _) = tre.dai("sapevo", 0)
        val (dopo, sospeso) = s1.uscita()
        assertEquals(EsitoDato(1, "sapevo"), sospeso)
        assertNull(dopo.inSospeso)
        assertNull(tre.uscita().second)
    }

    @Test
    fun togliereUnaSchedaPassaAllaProssima() {
        val senza = tre.togliCorrente()
        assertEquals(listOf(2L, 3L), senza.carte.map { it.id })
        assertEquals(0, senza.indice)
    }

    @Test
    fun laCodaSopravviveSenzaReteEPoiParte() = runBlocking {
        val deposito = DepositoStudio()
        val mandati = mutableListOf<Pair<Long, String>>()
        var risposta: Esito<RispostaLampo> = Esito.Irraggiungibile
        val coda = CodaLampo(deposito) { id, esito ->
            if (risposta is Esito.Ok) mandati += id to esito
            risposta
        }
        coda.accoda(EsitoDato(1, "sapevo"))
        coda.accoda(EsitoDato(2, "quasi"))
        assertFalse(coda.invia().finito)
        assertEquals(2, deposito.codaLampo().size)

        risposta = Esito.Ok(RispostaLampo(2, "2026-11-14", 5))
        assertTrue(coda.invia().finito)
        assertEquals(listOf(1L to "sapevo", 2L to "quasi"), mandati)
        assertTrue(deposito.codaLampo().isEmpty())
    }

    @Test
    fun unEsitoRifiutatoSiRiprovaUnaVoltaEPoiBasta() = runBlocking {
        val deposito = DepositoStudio()
        val coda = CodaLampo(deposito) { _, _ -> Esito.Errore("No", "non_valido", 422) }
        coda.accoda(EsitoDato(1, "sapevo"))
        val prima = coda.invia()
        assertTrue(prima.rifiutato)
        assertFalse(prima.finito)
        val seconda = coda.invia()
        assertTrue(seconda.finito)
        assertTrue(deposito.codaLampo().isEmpty())
    }

    @Test
    fun ilDepositoTieneRisposteESeguiti() {
        val d = DepositoStudio()
        d.scriviCopia(9, CopiaSimulata(mapOf("1" to "ciao"), secondi = 120, daInviare = true))
        d.scriviCopia(10, CopiaSimulata(mapOf("1" to "fatta"), daInviare = false))
        assertEquals("ciao", d.copia(9)?.risposte?.get("1"))
        assertEquals(listOf(9L), d.copieDaInviare().map { it.first })
        d.togliCopia(9)
        assertNull(d.copia(9))

        assertTrue(d.seguiti().vuoti)
        d.seguiSchema(1)
        d.seguiSimulata(2)
        d.seguiRiassunto(3)
        assertEquals(setOf(1L), d.seguiti().schemi)
        d.smettiSchema(1)
        d.smettiSimulata(2)
        d.smettiRiassunto(3)
        assertTrue(d.seguiti().vuoti)

        d.ricordaParte(4, 3)
        assertEquals(3, d.parteLetta(4))
        d.tempoNascosto = true
        assertTrue(d.tempoNascosto)
    }
}
