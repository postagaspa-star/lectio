package eu.stgm.lectio.lavori

import eu.stgm.lectio.dati.CorpoSalvaSimulata
import eu.stgm.lectio.dati.DepositoStudio
import eu.stgm.lectio.dati.Riassunto
import eu.stgm.lectio.dati.Schema
import eu.stgm.lectio.dati.Simulata
import eu.stgm.lectio.dati.Sorgente
import eu.stgm.lectio.logica.CodaLampo
import eu.stgm.lectio.rete.Esito
import eu.stgm.lectio.rete.LectioClient
import eu.stgm.lectio.rete.daRitentare

/** Chi riceve gli avvisi di schemi, riassunti e verifiche simulate (nei test: una lista). */
interface AvvisiStudio {
    fun avanzamento(titolo: String, frase: String, frazione: Float?)
    fun schema(testo: String, schema: Schema)
    fun simulata(testo: String, simulata: Simulata)
    fun riassunto(testo: String, riassunto: Riassunto)
}

/**
 * Un giro su quello che si sta preparando sul server oltre alle lezioni. Segue solo
 * quello che è stato chiesto da questo telefono ([DepositoStudio.seguiti]): quando un
 * lavoro finisce si avvisa una volta e si smette di seguirlo.
 */
class GiroStudio(
    private val deposito: DepositoStudio,
    private val schema: suspend (Long) -> Esito<Schema>,
    private val simulata: suspend (Long) -> Esito<Simulata>,
    private val riassunto: suspend (Long) -> Esito<Riassunto>,
    private val nomeMateria: suspend (Long) -> String?,
    private val avvisi: AvvisiStudio,
) {
    private class Lavoro(val titolo: String, val frase: String, val frazione: Float?)

    private var inCorso = false
    private var senzaRete = false
    private var lavoro: Lavoro? = null

    /** [mostraAvanzamento] = la notifica fissa è libera (nessuna lezione in preparazione). */
    suspend fun giro(mostraAvanzamento: Boolean = true): Giro {
        inCorso = false
        senzaRete = false
        lavoro = null
        val s = deposito.seguiti()
        for (id in s.schemi) controlla(schema(id)) { x -> schema(id, x) }.also { if (it) deposito.smettiSchema(id) }
        for (id in s.simulate) controlla(simulata(id)) { x -> simulata(x) }.also { if (it) deposito.smettiSimulata(id) }
        for (id in s.riassunti) controlla(riassunto(id)) { x -> riassunto(x) }.also { if (it) deposito.smettiRiassunto(id) }
        val l = lavoro
        if (mostraAvanzamento && l != null) avvisi.avanzamento(l.titolo, l.frase, l.frazione)
        return when {
            senzaRete -> Giro.SENZA_RETE
            inCorso -> Giro.IN_CORSO
            else -> Giro.FINITO
        }
    }

    /** true = non serve più seguirlo. */
    private suspend fun <T> controlla(e: Esito<T>, finito: suspend (T) -> Boolean): Boolean = when (e) {
        is Esito.Ok -> finito(e.dato)
        Esito.Irraggiungibile -> {
            senzaRete = true
            false
        }
        is Esito.Errore -> when {
            LectioClient.eIrraggiungibile(e) -> {
                senzaRete = true
                false
            }
            // Cancellato da un altro dispositivo.
            e.stato == 404 -> true
            else -> {
                inCorso = true
                false
            }
        }
    }

    private fun di(nome: String?) = if (nome != null) " di $nome" else ""

    private fun inLavoro(titolo: String, frase: String?, ripiego: String, fatto: Int, totale: Int) {
        inCorso = true
        if (lavoro == null) lavoro = Lavoro(titolo, frase?.takeIf { it.isNotBlank() } ?: ripiego, if (totale > 0) (fatto.toFloat() / totale).coerceIn(0f, 1f) else null)
    }

    private suspend fun schema(@Suppress("UNUSED_PARAMETER") id: Long, x: Schema): Boolean {
        val materia = di(nomeMateria(x.materiaId))
        return when {
            x.inLavoro -> {
                inLavoro("Preparo lo schema$materia", x.frase, "Controllo a che punto è", x.fatto, x.totale)
                false
            }
            x.pronto -> {
                avvisi.schema("Lo schema$materia è pronto", x)
                true
            }
            x.fallito -> {
                avvisi.schema("Lo schema$materia non è riuscito", x)
                true
            }
            else -> true
        }
    }

    private suspend fun simulata(x: Simulata): Boolean {
        val materia = di(nomeMateria(x.materiaId))
        return when {
            x.inLavoro -> {
                inLavoro("Preparo la verifica simulata$materia", x.frase, "Controllo a che punto è", x.fatto, x.totale)
                false
            }
            x.inCorrezione -> {
                inLavoro("Correggo la verifica simulata$materia", null, "La sto correggendo", x.fatto, x.totale)
                false
            }
            x.pronta -> {
                avvisi.simulata("La verifica simulata$materia è pronta", x)
                true
            }
            x.corretta -> {
                avvisi.simulata("La verifica simulata$materia è corretta", x)
                true
            }
            x.fallita -> {
                avvisi.simulata("La verifica simulata$materia non è riuscita", x)
                true
            }
            else -> true
        }
    }

    private suspend fun riassunto(x: Riassunto): Boolean {
        val materia = di(nomeMateria(x.materiaId))
        return when {
            x.inLavoro -> {
                inLavoro("Scrivo il riassunto$materia", x.frase, "Controllo a che punto è", 0, 0)
                false
            }
            x.pronto -> {
                avvisi.riassunto("Il riassunto$materia è pronto", x)
                true
            }
            x.fallito -> {
                avvisi.riassunto("Il riassunto$materia non è riuscito", x)
                true
            }
            else -> true
        }
    }
}

/**
 * Quello che è rimasto sul telefono: gli esiti del Ripasso lampo e le risposte delle
 * verifiche simulate. true = non resta niente da riprovare.
 */
suspend fun inviaStudio(sorgente: Sorgente): Boolean {
    val deposito = sorgente.deposito
    var fatto = CodaLampo(deposito, sorgente::esitoLampo).invia().finito
    for ((id, copia) in deposito.copieDaInviare()) {
        val e = sorgente.salvaSimulata(id, CorpoSalvaSimulata(copia.risposte, iniziata = true))
        if (e.daRitentare) {
            fatto = false
        } else {
            // Rifiutata vuol dire già consegnata o cancellata altrove: riprovare non serve.
            deposito.scriviCopia(id, copia.copy(daInviare = false))
        }
    }
    return fatto
}
