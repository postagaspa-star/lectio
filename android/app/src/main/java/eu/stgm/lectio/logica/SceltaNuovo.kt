package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Schema

/** Le cinque cose del foglio "Cosa vuoi fare?" (aspetto.md §43.3), nell'ordine in cui si vedono. */
enum class VoceNuovo(val nome: String, val frase: String) {
    LEZIONE("Lezione da ascoltare", "Te la spiego a voce, con le domande lungo la strada"),
    SCHEMA("Schema", "Da leggere: i punti chiave, con le immagini"),
    RIASSUNTO("Riassunto", "Da uno schema già pronto: per punti o discorsivo"),
    SIMULATA("Verifica simulata", "Come quella del prof, poi la correggo"),

    /** La frase dipende dalle schede di oggi: "5 schede · circa 2 min". */
    LAMPO("Ripasso lampo", ""),
}

/** Una riga del foglio: da spenta la [frase] dice perché. */
data class RigaNuovo(val voce: VoceNuovo, val frase: String, val accesa: Boolean)

/** Cosa si può fare dal pulsante Nuovo della materia (aspetto.md §43.2, §43.3). */
object SceltaNuovo {
    const val SERVE_RETE = "Serve la rete"
    const val SERVE_SCHEMA = "Prima serve uno schema pronto"
    const val NIENTE_OGGI = "Niente per oggi"

    /**
     * [puoCreare]: c'è la rete (o si è nella visita d'esempio); [schemiPronti]: gli schemi
     * pronti della materia; [schede]: le schede di Ripasso lampo di oggi; [schedeSalvate]:
     * sono già sul telefono, così il ripasso parte anche senza rete (§35.5).
     */
    fun righe(puoCreare: Boolean, schemiPronti: Int, schede: Int, schedeSalvate: Boolean): List<RigaNuovo> =
        VoceNuovo.entries.map { v ->
            when (v) {
                VoceNuovo.LAMPO -> when {
                    schede <= 0 -> RigaNuovo(v, NIENTE_OGGI, false)
                    !puoCreare && !schedeSalvate -> RigaNuovo(v, SERVE_RETE, false)
                    else -> RigaNuovo(v, TestiLampo.riga(schede, TestiLampo.minuti(schede)), true)
                }
                // Senza schemi pronti non serve neanche la rete: si dice la cosa che manca davvero.
                VoceNuovo.RIASSUNTO -> when {
                    schemiPronti <= 0 -> RigaNuovo(v, SERVE_SCHEMA, false)
                    !puoCreare -> RigaNuovo(v, SERVE_RETE, false)
                    else -> RigaNuovo(v, v.frase, true)
                }
                else -> if (puoCreare) RigaNuovo(v, v.frase, true) else RigaNuovo(v, SERVE_RETE, false)
            }
        }

    /** Il pulsante Nuovo si spegne solo se nel foglio non c'è niente da toccare. */
    fun pulsanteAcceso(righe: List<RigaNuovo>): Boolean = righe.any { it.accesa }

    /** Gli schemi da cui fare un riassunto: solo i pronti, i più recenti prima (passo 2). */
    fun schemiPronti(schemi: List<Schema>): List<Schema> =
        schemi.filter { it.pronto }
            .sortedWith(compareByDescending<Schema> { it.prontoIl ?: it.creatoIl ?: "" }.thenByDescending { it.id })
}
