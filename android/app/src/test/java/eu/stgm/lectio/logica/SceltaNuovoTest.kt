package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il foglio "Cosa vuoi fare?" del pulsante Nuovo (aspetto.md §43.2, §43.3). */
class SceltaNuovoTest {

    private fun riga(righe: List<RigaNuovo>, v: VoceNuovo) = righe.single { it.voce == v }

    @Test
    fun cinqueRigheNellOrdineDellaGuida() {
        val righe = SceltaNuovo.righe(puoCreare = true, schemiPronti = 1, schede = 5, schedeSalvate = false)
        assertEquals(
            listOf("Lezione da ascoltare", "Schema", "Riassunto", "Verifica simulata", "Ripasso lampo"),
            righe.map { it.voce.nome },
        )
        assertTrue(righe.all { it.accesa })
        assertEquals("Te la spiego a voce, con le domande lungo la strada", riga(righe, VoceNuovo.LEZIONE).frase)
        assertEquals("Da leggere: i punti chiave, con le immagini", riga(righe, VoceNuovo.SCHEMA).frase)
        assertEquals("Da uno schema già pronto: per punti o discorsivo", riga(righe, VoceNuovo.RIASSUNTO).frase)
        assertEquals("Come quella del prof, poi la correggo", riga(righe, VoceNuovo.SIMULATA).frase)
        // Come la scheda di Da ripassare: 20 secondi a scheda, in su.
        assertEquals("5 schede · circa 2 min", riga(righe, VoceNuovo.LAMPO).frase)
        assertTrue(SceltaNuovo.pulsanteAcceso(righe))
        // Niente punti esclamativi né frasi lunghe.
        righe.forEach { assertFalse(it.frase, it.frase.contains('!')) }
    }

    @Test
    fun senzaSchedeRipassoLampoDiceNientePerOggi() {
        val righe = SceltaNuovo.righe(puoCreare = true, schemiPronti = 1, schede = 0, schedeSalvate = false)
        assertEquals(RigaNuovo(VoceNuovo.LAMPO, "Niente per oggi", false), riga(righe, VoceNuovo.LAMPO))
        assertTrue("il resto si può fare", SceltaNuovo.pulsanteAcceso(righe))
    }

    @Test
    fun senzaSchemiProntiNienteRiassunto() {
        val online = SceltaNuovo.righe(puoCreare = true, schemiPronti = 0, schede = 3, schedeSalvate = false)
        assertEquals(RigaNuovo(VoceNuovo.RIASSUNTO, "Prima serve uno schema pronto", false), riga(online, VoceNuovo.RIASSUNTO))
        // Anche senza rete si dice la cosa che manca davvero.
        val offline = SceltaNuovo.righe(puoCreare = false, schemiPronti = 0, schede = 3, schedeSalvate = true)
        assertEquals("Prima serve uno schema pronto", riga(offline, VoceNuovo.RIASSUNTO).frase)
    }

    @Test
    fun senzaReteResteAccesoSoloIlRipassoConLeSchedeSalvate() {
        val righe = SceltaNuovo.righe(puoCreare = false, schemiPronti = 2, schede = 4, schedeSalvate = true)
        listOf(VoceNuovo.LEZIONE, VoceNuovo.SCHEMA, VoceNuovo.RIASSUNTO, VoceNuovo.SIMULATA).forEach { v ->
            assertEquals(RigaNuovo(v, "Serve la rete", false), riga(righe, v))
        }
        assertEquals(RigaNuovo(VoceNuovo.LAMPO, "4 schede · circa 2 min", true), riga(righe, VoceNuovo.LAMPO))
        assertTrue(SceltaNuovo.pulsanteAcceso(righe))
    }

    @Test
    fun senzaReteESenzaSchedeSalvateIlPulsanteSiSpegne() {
        val righe = SceltaNuovo.righe(puoCreare = false, schemiPronti = 2, schede = 4, schedeSalvate = false)
        assertEquals(RigaNuovo(VoceNuovo.LAMPO, "Serve la rete", false), riga(righe, VoceNuovo.LAMPO))
        assertFalse(SceltaNuovo.pulsanteAcceso(righe))
        // Senza rete e con 0 schede: anche qui niente da fare.
        assertFalse(SceltaNuovo.pulsanteAcceso(SceltaNuovo.righe(false, 0, 0, true)))
    }

    @Test
    fun gliSchemiDelPasso2SonoSoloIProntiIPiuRecentiPrima() {
        val vecchio = Schema(id = 1, materiaId = 1, titolo = "Vecchio", stato = "pronto", prontoIl = "2026-10-01T09:00:00Z")
        val nuovo = Schema(id = 2, materiaId = 1, titolo = "Nuovo", stato = "pronto", prontoIl = "2026-11-02T09:00:00Z")
        val inLavoro = Schema(id = 3, materiaId = 1, titolo = "In lavoro", stato = "in_preparazione", creatoIl = "2026-11-09T09:00:00Z")
        val fallito = Schema(id = 4, materiaId = 1, titolo = "Fallito", stato = "errore")
        val senzaData = Schema(id = 5, materiaId = 1, titolo = "Senza data", stato = "pronto", creatoIl = "2026-10-15T09:00:00Z")
        assertEquals(
            listOf("Nuovo", "Senza data", "Vecchio"),
            SceltaNuovo.schemiPronti(listOf(vecchio, inLavoro, nuovo, fallito, senzaData)).map { it.nome },
        )
    }
}
