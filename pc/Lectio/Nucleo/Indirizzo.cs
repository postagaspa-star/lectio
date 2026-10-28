using System.Net;
using System.Net.Sockets;

namespace Lectio.Nucleo;

/// <summary>L'indirizzo del server come lo scrive l'utente, ridotto alla base (<c>https://host[:porta]</c>).</summary>
public static class Indirizzo
{
    // Nessun indirizzo vero scritto nel codice (repository pubblico): quello di casa lo scrive Andrea
    // nell'abbinamento, quello da fuori casa lo manda il server (§15.5) o lo scrive lui nelle Impostazioni.

    /// <summary>Come si scrive un indirizzo di casa (segnaposto del campo e frasi d'errore).</summary>
    public const string Esempio = "http://192.168.x.x:8500";

    public const string FraseSoloHttps =
        "Fuori dalla rete di casa serve un indirizzo sicuro, che comincia con https: usa quello di Tailscale.";

    /// <summary>
    /// Toglie spazi, barre finali e un <c>/api</c> finale. Senza schema: <c>http://</c> per un indirizzo della rete
    /// di casa scritto in numeri (come <c>192.168.0.20:8500</c>), <c>https://</c> per tutto il resto.
    /// <c>http://</c> vale solo per gli indirizzi privati di casa: 192.168.x.x, 10.x.x.x, 172.16-31.x.x.
    /// </summary>
    public static bool Normalizza(string? scritto, out string baseServer, out string? errore)
    {
        baseServer = "";
        errore = null;
        var t = (scritto ?? "").Trim();
        if (t.Length == 0)
        {
            errore = "Scrivi l'indirizzo del server di casa, per esempio " + Esempio;
            return false;
        }
        if (!t.Contains("://", StringComparison.Ordinal))
        {
            var host = t.Split('/', 2)[0].Split(':')[0];
            t = (DiCasa(host) ? "http://" : "https://") + t;
        }
        if (!Uri.TryCreate(t, UriKind.Absolute, out var u) || (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps) || string.IsNullOrEmpty(u.Host)
            || !string.IsNullOrEmpty(u.Query) || !string.IsNullOrEmpty(u.Fragment) || !string.IsNullOrEmpty(u.UserInfo))
        {
            errore = "L'indirizzo non sembra giusto. Deve essere simile a " + Esempio + " oppure all'indirizzo https di Tailscale.";
            return false;
        }
        if (u.Scheme == Uri.UriSchemeHttp && !DiCasa(u.Host))
        {
            errore = FraseSoloHttps;
            return false;
        }
        var percorso = u.AbsolutePath.TrimEnd('/');
        if (percorso.EndsWith("/api", StringComparison.OrdinalIgnoreCase)) percorso = percorso[..^4];
        baseServer = u.GetLeftPart(UriPartial.Authority) + percorso;
        return true;
    }

    /// <summary>Un indirizzo IPv4 della rete privata di casa: 10.x.x.x, 172.16-31.x.x, 192.168.x.x.</summary>
    public static bool DiCasa(string? host)
    {
        if (!IPAddress.TryParse(host, out var ip) || ip.AddressFamily != AddressFamily.InterNetwork) return false;
        if (host!.Count(c => c == '.') != 3) return false;
        var b = ip.GetAddressBytes();
        return b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] >= 16 && b[1] <= 31);
    }
}
