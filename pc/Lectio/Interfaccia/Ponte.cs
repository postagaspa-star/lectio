using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Lectio.Motore;
using Lectio.Nucleo;

namespace Lectio.Interfaccia;

/// <summary>
/// L'accordo fra la pagina (pc/ui) e il programma: le richieste della pagina a <c>https://lectio.locale/locale/*</c>
/// e <c>/server/*</c> arrivano qui, dentro WebView2. Niente porte aperte sul computer, e la pagina non vede mai
/// il token. Tutte le risposte sono JSON.
/// </summary>
public sealed class Ponte
{
    public const string Host = "lectio.locale";
    public const string Origine = "https://" + Host;

    /// <summary>L'unico sito che la pagina può aprire nel browser (§9, "Apri Gemini").</summary>
    public const string IndirizzoGemini = "https://gemini.google.com/app";

    private static readonly Regex RottaLezione = new(@"^lezioni/(\d{1,12})(/scarica|/scaricamento)?$", RegexOptions.CultureInvariant);

    private readonly Motore.Motore motore;

    public Ponte(Motore.Motore motore) => this.motore = motore;

    /// <summary>Copia un testo negli appunti di Windows (sul filo della finestra). false se Windows non lo permette.</summary>
    public Func<string, bool>? Copia { get; set; }

    /// <summary>Apre un indirizzo nel browser predefinito (già controllato con <see cref="ApribileFuori"/>).</summary>
    public Action<string>? ApriNelBrowser { get; set; }

    /// <summary>Accende o spegne i tasti multimediali della tastiera (c'è una lezione nel lettore).</summary>
    public Action<bool>? Tasti { get; set; }

    /// <summary>"Aggiorna adesso": Lectio si chiude, installa la versione pronta e si riapre.</summary>
    public Action? AggiornaAdesso { get; set; }

    /// <summary>Il tema scelto nelle impostazioni (null = come Windows).</summary>
    public string? TemaScelto => motore.TemaScelto;

    public event Action? TemaCambiato
    {
        add => motore.TemaCambiato += value;
        remove => motore.TemaCambiato -= value;
    }

    public static bool ÈInterno(string? indirizzo)
    {
        if (string.IsNullOrEmpty(indirizzo)) return false;
        if (indirizzo == "about:blank") return true;
        return Uri.TryCreate(indirizzo, UriKind.Absolute, out var u)
               && u.Scheme == Uri.UriSchemeHttps
               && string.Equals(u.Host, Host, StringComparison.OrdinalIgnoreCase)
               && u.IsDefaultPort;
    }

    /// <summary>Solo https e solo Gemini: nient'altro, nemmeno da un link finito per sbaglio nella pagina.</summary>
    public static bool ApribileFuori(string? indirizzo)
    {
        if (!Uri.TryCreate(indirizzo, UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(u.UserInfo) || !u.IsDefaultPort) return false;
        return string.Equals(u.Host, "gemini.google.com", StringComparison.OrdinalIgnoreCase);
    }

    public static bool ÈApi(Uri uri) =>
        uri.AbsolutePath.StartsWith("/locale/", StringComparison.Ordinal)
        || uri.AbsolutePath.StartsWith("/server/", StringComparison.Ordinal);

    /// <summary>
    /// Il file della cartella ui per un percorso della pagina ("/" = index.html), oppure null se il
    /// percorso prova a uscire dalla cartella o non è un nome di file pulito.
    /// </summary>
    public static string? FileDellInterfaccia(string cartellaUi, string percorsoUrl)
    {
        string relativo;
        try
        {
            relativo = Uri.UnescapeDataString(percorsoUrl).TrimStart('/');
        }
        catch (UriFormatException)
        {
            return null;
        }
        if (relativo.Length == 0) relativo = "index.html";
        if (relativo.Contains('\\') || relativo.Contains(':') || relativo.Contains('\0')) return null;
        if (relativo.Split('/').Any(p => p is "" or "." or "..")) return null;
        var radice = Path.GetFullPath(cartellaUi).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var completo = Path.GetFullPath(Path.Combine(radice, relativo.Replace('/', Path.DirectorySeparatorChar)));
        return completo.StartsWith(radice, StringComparison.OrdinalIgnoreCase) ? completo : null;
    }

    public static string TipoContenuto(string file) => Path.GetExtension(file).ToLowerInvariant() switch
    {
        ".html" or ".htm" => "text/html; charset=utf-8",
        ".js" or ".mjs" => "text/javascript; charset=utf-8",
        ".css" => "text/css; charset=utf-8",
        ".json" => "application/json; charset=utf-8",
        ".svg" => "image/svg+xml",
        ".png" => "image/png",
        ".ico" => "image/x-icon",
        ".woff2" => "font/woff2",
        ".woff" => "font/woff",
        ".ttf" => "font/ttf",
        ".txt" => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    };

    public async Task<RispostaPonte> GestisciAsync(string metodo, Uri uri, byte[]? corpo)
    {
        metodo = metodo.ToUpperInvariant();
        var percorso = uri.AbsolutePath;
        if (percorso.StartsWith("/locale/", StringComparison.Ordinal))
        {
            var nome = percorso["/locale/".Length..].TrimEnd('/');
            if (nome == "carica")
            {
                if (metodo != "POST") return Rifiuto(405);
                var q = Query(uri.Query);
                long? materia = long.TryParse(q.GetValueOrDefault("materia_id"), NumberStyles.None, CultureInfo.InvariantCulture, out var m) ? m : null;
                return await motore.CaricaFileAsync(materia, Vuoto(q.GetValueOrDefault("tipo")), Vuoto(q.GetValueOrDefault("titolo")),
                    Vuoto(q.GetValueOrDefault("data")), q.GetValueOrDefault("nome"), corpo).ConfigureAwait(false);
            }
            if (nome == "diagnosi-corpo" && motore.Diagnosi)
            {
                // --diagnosi: la pagina manda dei byte come farebbe con un file trascinato; qui si conta cosa arriva.
                Log.Info("diagnosi: corpo binario arrivato, " + (corpo?.Length ?? -1).ToString(CultureInfo.InvariantCulture) + " byte");
                return new RispostaPonte(200, new JsonObject { ["byte"] = corpo?.Length ?? -1 }.ToJsonString());
            }
            var testo = corpo == null ? null : Encoding.UTF8.GetString(corpo);
            var (stato, o) = await LocaleAsync(metodo, nome, testo).ConfigureAwait(false);
            return new RispostaPonte(stato, o.ToJsonString());
        }
        if (percorso.StartsWith("/server/", StringComparison.Ordinal))
        {
            var testo = corpo == null || corpo.Length == 0 ? null : Encoding.UTF8.GetString(corpo);
            return await motore.InoltraAsync(metodo, percorso["/server/".Length..], uri.Query, testo).ConfigureAwait(false);
        }
        return Rifiuto(404);
    }

    private static string? Vuoto(string? s) => string.IsNullOrWhiteSpace(s) ? null : s;

    private static RispostaPonte Rifiuto(int stato) =>
        new(stato, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = stato == 405 ? "non_valido" : "non_trovato" }.ToJsonString());

    /// <summary>I parametri della query (<c>?a=1&amp;b=due</c>), già decodificati. Un nome ripetuto vale l'ultimo.</summary>
    public static Dictionary<string, string> Query(string? query)
    {
        var d = new Dictionary<string, string>(StringComparer.Ordinal);
        if (string.IsNullOrEmpty(query)) return d;
        foreach (var parte in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var i = parte.IndexOf('=');
            var k = i < 0 ? parte : parte[..i];
            var v = i < 0 ? "" : parte[(i + 1)..];
            try
            {
                d[Uri.UnescapeDataString(k.Replace('+', ' '))] = Uri.UnescapeDataString(v.Replace('+', ' '));
            }
            catch (UriFormatException)
            {
            }
        }
        return d;
    }

    private async Task<(int, JsonObject)> LocaleAsync(string metodo, string nome, string? corpo)
    {
        var o = Json.Nodo(corpo) as JsonObject;
        var lezione = RottaLezione.Match(nome);
        if (lezione.Success)
        {
            var id = long.Parse(lezione.Groups[1].Value, CultureInfo.InvariantCulture);
            switch (lezione.Groups[2].Value, metodo)
            {
                case ("/scarica", "POST"):
                    var r = await motore.ScaricaLezioneAsync(id).ConfigureAwait(false);
                    return (r.Stato, Json.Nodo(r.Json) as JsonObject ?? new JsonObject());
                case ("/scaricamento", "GET"):
                    return (200, motore.StatoScaricamento(id));
                case ("", "DELETE"):
                    var tolta = motore.TogliLezione(id);
                    return (tolta["errore"] != null ? 409 : 200, tolta);
                default:
                    return (405, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = "non_valido" });
            }
        }

        (string Metodo, Func<Task<JsonObject>> Azione)? rotta = nome switch
        {
            "stato" => ("GET", () => Task.FromResult(motore.Stato())),
            "abbina" => ("POST", () => motore.AbbinaAsync(Json.Testo(o?["server"]), Json.Testo(o?["codice"]), Json.Testo(o?["nome"]))),
            "scollega" => ("POST", motore.ScollegaAsync),
            "tutorial-visto" => ("POST", () => Task.FromResult(motore.SegnaTutorialVisto())),
            "fuori-casa" => ("POST", () => Task.FromResult(motore.ImpostaFuoriCasa(Json.Testo(o?["indirizzo"])))),
            "tema" => ("POST", () => Task.FromResult(motore.ImpostaTema(Json.Testo(o?["tema"])))),
            "lezioni/scaricate" => ("GET", () => Task.FromResult(motore.Scaricate())),
            "aggiorna-adesso" => ("POST", () =>
            {
                motore.Sveglia();
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            "copia" => ("POST", () =>
            {
                var testo = Json.Testo(o?["testo"]);
                if (string.IsNullOrEmpty(testo)) return Task.FromResult(new JsonObject { ["errore"] = "Non c'è niente da copiare.", ["codice"] = "non_valido" });
                var ok = Copia?.Invoke(testo) ?? false;
                return Task.FromResult(ok
                    ? new JsonObject { ["ok"] = true }
                    : new JsonObject { ["errore"] = "Non sono riuscito a copiarlo: seleziona il testo qui sotto e copialo con Ctrl+C.", ["codice"] = "appunti" });
            }),
            "apri-gemini" => ("POST", () =>
            {
                if (ApriNelBrowser == null) return Task.FromResult(new JsonObject { ["errore"] = "Il browser non si apre da qui.", ["codice"] = "non_disponibile" });
                ApriNelBrowser(IndirizzoGemini);
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            "tasti" => ("POST", () =>
            {
                Tasti?.Invoke(Json.Booleano(o?["attivi"]) ?? false);
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            "scarica-aggiornamento" => ("POST", () => Task.FromResult(motore.AvviaPreparazione()
                ? new JsonObject { ["ok"] = true }
                : new JsonObject { ["errore"] = motore.Aggiornatore.Disponibile == null ? "Non c'è una versione nuova da scaricare." : "Lo scaricamento è già in corso.", ["codice"] = "non_disponibile" })),
            "aggiorna-e-riavvia" => ("POST", () =>
            {
                if (motore.Pronta == null || AggiornaAdesso == null)
                {
                    return Task.FromResult(new JsonObject { ["errore"] = "Non c'è una versione nuova pronta da installare.", ["codice"] = "non_disponibile" });
                }
                AggiornaAdesso();
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            // Solo con --diagnosi; altrimenti la rotta non c'è.
            "diagnosi" when motore.Diagnosi => ("POST", () =>
            {
                Log.Info("diagnosi: " + (corpo ?? "").Replace('\r', ' ').Replace('\n', ' '));
                return Task.FromResult(new JsonObject { ["ok"] = true });
            }),
            _ => null,
        };
        if (rotta == null) return (404, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = "non_trovato" });
        if (rotta.Value.Metodo != metodo) return (405, new JsonObject { ["errore"] = Frasi.RichiestaSconosciuta, ["codice"] = "non_valido" });
        var esito = await rotta.Value.Azione().ConfigureAwait(false);
        return (esito["errore"] != null ? 422 : 200, esito);
    }
}
