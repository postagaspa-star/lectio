using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace Lectio.Rete;

/// <summary>
/// Una risposta del server. <see cref="Stato"/> 0 = il server non ha risposto (rete, tempo scaduto).
/// <see cref="Scaduta"/> = la richiesta è partita ma il tempo è finito prima della risposta: per una modifica
/// (una risposta da correggere, una lezione) l'esito è sconosciuto, non "senza rete".
/// </summary>
public readonly record struct Risposta(int Stato, string Corpo, bool Scaduta = false)
{
    public bool Ok => Stato is >= 200 and < 300;
    public bool Rete => Stato == 0;
    public static Risposta SenzaRete => new(0, "");
    public static Risposta TempoScaduto => new(0, "", true);
}

/// <summary>Com'è andato uno scaricamento: <see cref="Stato"/> è lo stato HTTP (0 = rete).</summary>
public readonly record struct EsitoDownload(bool Ok, int Stato, long Byte);

/// <summary>
/// Il client HTTP verso il server di Lectio. Le letture riusano le connessioni; le modifiche
/// (POST, PUT, PATCH, DELETE) partono su una connessione nuova chiusa subito, così .NET non le
/// ripete da solo su una connessione riusata: una lezione a pagamento non deve mai partire due volte.
/// Nei test si passa un handler finto, che fa da server.
/// </summary>
public sealed class ClientLectio : IDisposable
{
    public static readonly TimeSpan TempoMassimo = TimeSpan.FromSeconds(30);

    private readonly HttpClient letture;
    private readonly HttpClient modifiche;
    private readonly bool stessoClient;

    public ClientLectio(HttpMessageHandler? handlerFinto = null)
    {
        if (handlerFinto != null)
        {
            letture = modifiche = new HttpClient(handlerFinto, disposeHandler: false) { Timeout = Timeout.InfiniteTimeSpan };
            stessoClient = true;
        }
        else
        {
            letture = new HttpClient(new SocketsHttpHandler
            {
                ConnectTimeout = TimeSpan.FromSeconds(10),
                PooledConnectionLifetime = TimeSpan.FromMinutes(5),
                PooledConnectionIdleTimeout = TimeSpan.FromSeconds(2),
            })
            { Timeout = Timeout.InfiniteTimeSpan };
            modifiche = new HttpClient(new SocketsHttpHandler
            {
                ConnectTimeout = TimeSpan.FromSeconds(10),
                PooledConnectionLifetime = TimeSpan.Zero,
            })
            { Timeout = Timeout.InfiniteTimeSpan };
        }
        foreach (var c in stessoClient ? new[] { letture } : new[] { letture, modifiche })
        {
            c.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("Lectio-PC", Versione.Nome));
        }
    }

    private static Uri? Indirizzo(string server, string percorso) =>
        Uri.TryCreate(server.TrimEnd('/') + "/" + percorso.TrimStart('/'), UriKind.Absolute, out var uri) ? uri : null;

    /// <summary>
    /// Una richiesta JSON: <paramref name="percorso"/> relativo alla base del server (es. <c>api/materie</c>).
    /// Mai eccezioni: un problema di rete è <see cref="Risposta.SenzaRete"/>.
    /// </summary>
    public Task<Risposta> InviaAsync(string metodo, string server, string percorso, string? token, string? corpoJson,
        TimeSpan? tempoMassimo = null, CancellationToken annulla = default) =>
        InviaContenutoAsync(metodo, server, percorso, token,
            corpoJson == null ? null : new StringContent(corpoJson, Encoding.UTF8, "application/json"), tempoMassimo, annulla);

    /// <summary>Come <see cref="InviaAsync"/>, con un corpo qualsiasi (per esempio un file in multipart).</summary>
    public async Task<Risposta> InviaContenutoAsync(string metodo, string server, string percorso, string? token, HttpContent? contenuto,
        TimeSpan? tempoMassimo = null, CancellationToken annulla = default)
    {
        using var corpo = contenuto;
        var uri = Indirizzo(server, percorso);
        if (uri == null) return Risposta.SenzaRete;
        var metodoHttp = new HttpMethod(metodo.ToUpperInvariant());
        using var richiesta = new HttpRequestMessage(metodoHttp, uri);
        bool modifica = metodoHttp != HttpMethod.Get && metodoHttp != HttpMethod.Head;
        if (modifica) richiesta.Headers.ConnectionClose = true;
        if (token != null) richiesta.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        richiesta.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (corpo != null) richiesta.Content = corpo;

        using var scadenza = CancellationTokenSource.CreateLinkedTokenSource(annulla);
        scadenza.CancelAfter(tempoMassimo ?? TempoMassimo);
        try
        {
            using var risposta = await (modifica ? modifiche : letture).SendAsync(richiesta, HttpCompletionOption.ResponseContentRead, scadenza.Token).ConfigureAwait(false);
            var testo = await risposta.Content.ReadAsStringAsync(scadenza.Token).ConfigureAwait(false);
            return new Risposta((int)risposta.StatusCode, testo);
        }
        catch (OperationCanceledException) when (!annulla.IsCancellationRequested)
        {
            return Risposta.TempoScaduto;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or IOException or ObjectDisposedException)
        {
            // ObjectDisposedException: il programma si sta chiudendo mentre una richiesta era in volo.
            return Risposta.SenzaRete;
        }
    }

    /// <summary>
    /// Scarica un file in <paramref name="destinazione"/> (prima in un <c>.part</c>, poi rinominato).
    /// <paramref name="progresso"/> riceve i byte arrivati e quelli attesi (se il server li dice).
    /// </summary>
    public async Task<EsitoDownload> ScaricaAsync(string url, string? token, string destinazione,
        Action<long, long?>? progresso = null, CancellationToken annulla = default)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return new EsitoDownload(false, 0, 0);
        using var scadenza = CancellationTokenSource.CreateLinkedTokenSource(annulla);
        scadenza.CancelAfter(TimeSpan.FromMinutes(15));
        var parziale = destinazione + ".part";
        try
        {
            using var richiesta = new HttpRequestMessage(HttpMethod.Get, uri);
            if (token != null) richiesta.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var risposta = await letture.SendAsync(richiesta, HttpCompletionOption.ResponseHeadersRead, scadenza.Token).ConfigureAwait(false);
            if (!risposta.IsSuccessStatusCode) return new EsitoDownload(false, (int)risposta.StatusCode, 0);
            var attesi = risposta.Content.Headers.ContentLength;
            Directory.CreateDirectory(Path.GetDirectoryName(destinazione)!);
            long arrivati = 0;
            await using (var file = new FileStream(parziale, FileMode.Create, FileAccess.Write, FileShare.None))
            await using (var flusso = await risposta.Content.ReadAsStreamAsync(scadenza.Token).ConfigureAwait(false))
            {
                var buffer = new byte[81920];
                int letti;
                while ((letti = await flusso.ReadAsync(buffer, scadenza.Token).ConfigureAwait(false)) > 0)
                {
                    await file.WriteAsync(buffer.AsMemory(0, letti), scadenza.Token).ConfigureAwait(false);
                    arrivati += letti;
                    progresso?.Invoke(arrivati, attesi);
                }
            }
            File.Move(parziale, destinazione, overwrite: true);
            return new EsitoDownload(true, (int)risposta.StatusCode, arrivati);
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException or IOException or UnauthorizedAccessException or ObjectDisposedException)
        {
            try { File.Delete(parziale); } catch (Exception) { }
            return new EsitoDownload(false, 0, 0);
        }
    }

    public void Dispose()
    {
        letture.Dispose();
        if (!stessoClient) modifiche.Dispose();
    }
}
