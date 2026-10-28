package eu.stgm.lectio.rete

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * L'indirizzo del server scritto da Andrea, ripulito e controllato prima di
 * salvarlo. Il token viaggia in ogni richiesta: in chiaro (http) lo si lascia
 * andare solo dentro la rete privata di casa, mai su internet.
 */
object IndirizzoServer {

    /** Il modello da mostrare nel campo vuoto: nessun indirizzo vero nel codice (repo pubblico). */
    const val ESEMPIO = "http://192.168.x.x:8500"

    sealed interface Risultato {
        data class Valido(val url: String) : Risultato
        data class NonValido(val motivo: String) : Risultato
    }

    fun normalizza(grezzo: String): Risultato {
        val ripulito = grezzo.trim().trimEnd('/')
        if (ripulito.isEmpty()) return Risultato.NonValido("Scrivi l'indirizzo del server di casa.")
        val conSchema = if ("://" in ripulito) {
            ripulito
        } else {
            val host = ripulito.substringBefore('/').substringBefore(':')
            if (diCasa(host)) "http://$ripulito" else "https://$ripulito"
        }
        val url = conSchema.toHttpUrlOrNull()
            ?: return Risultato.NonValido("Questo indirizzo non sembra giusto. Per esempio: $ESEMPIO")
        if (url.scheme == "http" && !httpConsentito(url.host)) {
            return Risultato.NonValido(
                "Fuori dalla rete di casa l'indirizzo deve cominciare con https://. Usa quello di Tailscale.",
            )
        }
        // Solo schema, host, porta e percorso: query e credenziali incollate per sbaglio
        // renderebbero sbagliate tutte le richieste.
        val base = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .apply { url.pathSegments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
            .build()
        return Risultato.Valido(base.toString().trimEnd('/'))
    }

    /** 192.168.x.x, 10.x.x.x, 172.16-31.x.x. Niente nomi: un nome può portare ovunque. */
    fun diCasa(host: String): Boolean {
        val parti = host.split('.').map { it.toIntOrNull() ?: return false }
        if (parti.size != 4 || parti.any { it !in 0..255 }) return false
        val (a, b) = parti
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    /** Il telefono stesso (serve solo alle prove sul PC). */
    fun locale(host: String): Boolean = host == "localhost" || host.startsWith("127.")

    fun httpConsentito(host: String): Boolean = diCasa(host) || locale(host)

    /**
     * L'indirizzo da fuori casa mandato dal server (contratto 15.5, `indirizzo_fuori_casa`):
     * buono solo se è https, ripulito; altrimenti null.
     */
    fun fuoriCasaDalServer(grezzo: String?): String? {
        if (grezzo.isNullOrBlank()) return null
        val r = normalizza(grezzo) as? Risultato.Valido ?: return null
        return r.url.takeIf { it.startsWith("https://") }
    }
}
