package eu.stgm.lectio.logica

import kotlin.math.floor

/**
 * I tre tipi di lezione (contratto §16.1, aspetto.md §6): il codice del server, il
 * nome intero (riepilogo), quello corto del binario del telefono e la frase sotto.
 */
enum class TipoLezione(val codice: String, val nome: String, val nomeBreve: String, val frase: String) {
    SPIEGAZIONE("spiegazione", "Spiegazione", "Spiegazione", "Te lo spiego dall'inizio, con domande lungo la strada."),
    RIPASSO("ripasso", "Ripasso", "Ripasso", "Più veloce: i punti chiave e tante domande alla fine."),
    INTERROGAZIONE("interrogazione", "Interrogazione", "Interroga", "Ti interrogo come il prof, poi ti dico la risposta giusta."),
    ;

    companion object {
        fun da(codice: String?): TipoLezione = entries.firstOrNull { it.codice == codice } ?: SPIEGAZIONE
    }
}

/**
 * Com'è fatta una lezione, in parole (contratto §16.1): la stessa regola del server
 * (`misure.struttura`), solo per il riepilogo. `arrotonda` = per eccesso a metà.
 */
object Struttura {
    private val FINALI = mapOf(15 to 4, 20 to 5, 30 to 6, 45 to 8)

    /** Per eccesso a metà, come `Math.round` sui positivi: 2,5 → 3. */
    fun arrotonda(x: Double): Int = floor(x + 0.5).toInt()

    /** Le parti di una spiegazione: max(3, arrotonda(durata / 5)). */
    fun parti(durata: Int): Int = maxOf(3, arrotonda(durata / 5.0))

    /** Le domande alla fine di una spiegazione: {15:4, 20:5, 30:6, 45:8}, altrimenti 3 + arrotonda(durata / 10). */
    fun finali(durata: Int): Int = FINALI[durata] ?: (3 + arrotonda(durata / 10.0))

    /** Le domande in tutto: per il ripasso finali × 1,6, per l'interrogazione durata × 0,6. */
    fun domande(tipo: TipoLezione, durata: Int): Int = when (tipo) {
        TipoLezione.SPIEGAZIONE -> parti(durata) + finali(durata)
        TipoLezione.RIPASSO -> arrotonda(finali(durata) * 1.6)
        TipoLezione.INTERROGAZIONE -> arrotonda(durata * 0.6)
    }

    /** "4 parti, una domanda dopo ognuna e 5 alla fine". */
    fun frase(tipo: TipoLezione, durata: Int): String = when (tipo) {
        TipoLezione.SPIEGAZIONE -> "${parti(durata)} parti, una domanda dopo ognuna e ${finali(durata)} alla fine"
        TipoLezione.RIPASSO -> "I punti chiave in breve e ${domande(tipo, durata)} domande alla fine"
        TipoLezione.INTERROGAZIONE -> "${domande(tipo, durata)} domande come le fa il prof, con la risposta giusta dopo ognuna"
    }

    /** "Spiegazione di 20 minuti". */
    fun titolo(tipo: TipoLezione, durata: Int): String = "${tipo.nome} di $durata minuti"
}
