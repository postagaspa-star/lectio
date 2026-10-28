package eu.stgm.lectio.logica

import eu.stgm.lectio.dati.Stima
import eu.stgm.lectio.dati.Voce

/** Le due voci tra cui si sceglie (aspetto.md §6), più quella di prova del server senza chiavi. */
data class VociDisponibili(val espressiva: Voce?, val gratis: Voce?, val prova: Voce?) {
    val soloProva: Boolean get() = prova != null && espressiva == null && gratis == null
}

enum class TipoVoce { ESPRESSIVA, GRATIS, PROVA }

object Costi {
    /** "circa 0,4 cent a foto" (OCR Mistral, aspetto.md §5). */
    const val COSTO_FOTO_EUR = 0.004

    /** L'avviso calmo arriva all'80% del tetto (aspetto.md §0.6). */
    const val SOGLIA_AVVISO = 0.8

    fun voci(elenco: List<Voce>): VociDisponibili {
        val espressiva = elenco.firstOrNull { it.tipo == "espressiva" && it.disponibile }
        val gratis = elenco.firstOrNull { it.tipo == "gratis" && it.disponibile }
        val prova = elenco.firstOrNull { it.tipo == "prova" && it.disponibile }
        return VociDisponibili(espressiva, gratis, prova)
    }

    /** La voce da proporre: la predefinita delle impostazioni se c'è, altrimenti l'espressiva. */
    fun voceIniziale(voci: VociDisponibili, predefinita: String?): TipoVoce = when {
        voci.soloProva -> TipoVoce.PROVA
        predefinita != null && voci.gratis?.id == predefinita -> TipoVoce.GRATIS
        voci.espressiva != null -> TipoVoce.ESPRESSIVA
        voci.gratis != null -> TipoVoce.GRATIS
        else -> TipoVoce.PROVA
    }

    fun avviso(speso: Double, tetto: Double): Boolean = tetto > 0 && speso >= tetto * SOGLIA_AVVISO

    fun costoFoto(quante: Int): Double = quante * COSTO_FOTO_EUR
}

/**
 * Quello che la pagina Nuova lezione mostra dei soldi, calcolato dalle due stime
 * (una per voce). Tutte le cifre vengono dal server: qui solo scelte e frasi.
 */
data class VistaCosto(
    /** Il costo della voce espressiva per questa durata (scheda "Lorenzo"). */
    val costoVoceEspressiva: Double?,
    /** La voce espressiva sforerebbe il tetto: scheda spenta. */
    val espressivaSpenta: Boolean,
    /** "Sforerebbe il tetto di 2 €". */
    val motivoSpenta: String?,
    /** La voce che parte davvero (se l'espressiva è spenta, la gratis). */
    val voceScelta: TipoVoce,
    /** Il costo della lezione con la voce scelta (pulsante). */
    val costoScelto: Double?,
    val spesoMese: Double,
    val tetto: Double,
    val dopo: Double?,
    /** Le due parti della barra: già speso, questa lezione (frazioni del tetto). */
    val frazioneSpeso: Float,
    val frazioneQuesta: Float,
    val siAzzeraIl: String?,
    val caratteriGratisRimasti: Long?,
    /** Si può preparare: c'è una stima consentita. */
    val consentita: Boolean,
    /** Perché non si può (nessuna voce sta nel tetto). */
    val blocco: String?,
    /** La parte di [spesoMese] ancora in preparazione (contratto v1.3). */
    val impegnato: Double = 0.0,
) {
    val etichettaPulsante: String
        get() = if (costoScelto == null) "Prepara la lezione" else "Prepara la lezione · ${Formati.euro(costoScelto)}"

    /** "Questo mese 0,80 €": il già speso, senza il lavoro ancora in corso. */
    val rigaMese: String get() = "Questo mese ${Formati.euro((spesoMese - impegnato).coerceAtLeast(0.0))}"

    /** "+ 0,25 € in preparazione" (contratto v1.3), null se non c'è lavoro in corso. */
    val rigaImpegnato: String? get() = if (impegnato > 0.0) "+ ${Formati.euro(impegnato)} in preparazione" else null

    /** "dopo questa 1,05 € di 2 €". */
    val rigaDopo: String
        get() = if (dopo == null) "di ${Formati.euro(tetto)}" else "dopo questa ${Formati.euro(dopo)} di ${Formati.euro(tetto)}"

    companion object {
        fun calcola(espressiva: Stima?, gratis: Stima?, prova: Stima?, scelta: TipoVoce): VistaCosto {
            val base = prova ?: espressiva ?: gratis
            val spenta = espressiva != null && !espressiva.consentita
            val vera = when {
                scelta == TipoVoce.PROVA || (espressiva == null && gratis == null) -> TipoVoce.PROVA
                scelta == TipoVoce.ESPRESSIVA && (spenta || espressiva == null) -> if (gratis != null) TipoVoce.GRATIS else TipoVoce.ESPRESSIVA
                scelta == TipoVoce.GRATIS && gratis == null -> TipoVoce.ESPRESSIVA
                else -> scelta
            }
            val stimaScelta = when (vera) {
                TipoVoce.ESPRESSIVA -> espressiva
                TipoVoce.GRATIS -> gratis
                TipoVoce.PROVA -> prova ?: gratis ?: espressiva
            }
            val tetto = base?.tettoEur ?: 2.0
            val speso = base?.spesaMeseEur ?: 0.0
            val costo = stimaScelta?.costoEur
            val consentita = stimaScelta?.consentita ?: false
            fun frazione(v: Double) = if (tetto <= 0) 0f else (v / tetto).toFloat().coerceIn(0f, 1f)
            val fSpeso = frazione(speso)
            return VistaCosto(
                costoVoceEspressiva = espressiva?.costoVoceEur,
                espressivaSpenta = spenta,
                motivoSpenta = if (spenta) "Sforerebbe il tetto di ${Formati.euro(espressiva!!.tettoEur)}" else null,
                voceScelta = vera,
                costoScelto = costo,
                spesoMese = speso,
                tetto = tetto,
                dopo = stimaScelta?.dopoEur,
                frazioneSpeso = fSpeso,
                frazioneQuesta = (frazione(speso + (costo ?: 0.0)) - fSpeso).coerceAtLeast(0f),
                siAzzeraIl = base?.siAzzeraIl,
                caratteriGratisRimasti = gratis?.voceGratisCaratteriRimasti ?: base?.voceGratisCaratteriRimasti,
                consentita = consentita,
                blocco = if (stimaScelta != null && !consentita) stimaScelta.motivo ?: "Sforerebbe il tetto di ${Formati.euro(tetto)}" else null,
                impegnato = base?.impegnatoEur ?: 0.0,
            )
        }
    }
}
