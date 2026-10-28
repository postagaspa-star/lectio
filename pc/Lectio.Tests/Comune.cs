global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
global using System.Threading;
global using System.Threading.Tasks;
global using Xunit;

using System.Net;
using System.Net.Http;
using System.Runtime.CompilerServices;
using System.Text;
using Lectio.Motore;
using Lectio.Nucleo;
using Lectio.Rete;
using Lectio.Sistema;

namespace Lectio.Tests;

/// <summary>Una cartella temporanea che sparisce a fine test.</summary>
internal sealed class CartellaTemporanea : IDisposable
{
    public CartellaTemporanea()
    {
        Percorso = Path.Combine(Path.GetTempPath(), "lectio-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Percorso);
    }

    public string Percorso { get; }

    public string File(string nome) => Path.Combine(Percorso, nome);

    public void Dispose()
    {
        try
        {
            Directory.Delete(Percorso, recursive: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }
}

/// <summary>
/// Il server finto: un <see cref="HttpMessageHandler"/> che risponde con <see cref="Risponde"/> e tiene le richieste
/// ricevute, in ordine. Stato 0 = "il server non risponde" (eccezione di rete). <see cref="RispondeByte"/> per i file.
/// </summary>
internal sealed class HandlerFinto : HttpMessageHandler
{
    private readonly List<Richiesta> ricevute = new();

    public HandlerFinto(Func<Richiesta, (int Stato, string Corpo)> risponde)
    {
        Risponde = risponde;
    }

    /// <param name="Percorso">Con la query: <c>/api/novita?dopo_id=0</c>.</param>
    public sealed record Richiesta(string Metodo, string Percorso, string? Autorizzazione, string? Corpo, string? TipoCorpo, string? Agente, string Host = "");

    public Func<Richiesta, (int Stato, string Corpo)> Risponde { get; set; }

    /// <summary>Per gli scaricamenti: se restituisce dei byte, valgono al posto di <see cref="Risponde"/>.</summary>
    public Func<Richiesta, byte[]?>? RispondeByte { get; set; }

    /// <summary>Un'attesa prima di rispondere (per provare le richieste che si accavallano).</summary>
    public TimeSpan Ritardo { get; set; }

    public IReadOnlyList<Richiesta> Ricevute
    {
        get { lock (ricevute) return ricevute.ToList(); }
    }

    public IEnumerable<Richiesta> Di(string metodo, string inizio) =>
        Ricevute.Where(r => r.Metodo == metodo && r.Percorso.StartsWith(inizio, StringComparison.Ordinal));

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var corpo = request.Content == null ? null : await request.Content.ReadAsStringAsync(cancellationToken);
        var r = new Richiesta(request.Method.Method, request.RequestUri!.PathAndQuery, request.Headers.Authorization?.ToString(), corpo,
            request.Content?.Headers.ContentType?.MediaType, request.Headers.UserAgent.ToString(), request.RequestUri.GetLeftPart(UriPartial.Authority));
        lock (ricevute) ricevute.Add(r);
        if (Ritardo > TimeSpan.Zero) await Task.Delay(Ritardo, cancellationToken);
        if (RispondeByte?.Invoke(r) is byte[] dati)
        {
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(dati) };
        }
        var (stato, testo) = Risponde(r);
        if (stato == 0) throw new HttpRequestException("il server non risponde");
        return new HttpResponseMessage((HttpStatusCode)stato) { Content = new StringContent(testo, Encoding.UTF8, "application/json") };
    }
}

internal static class Sorgenti
{
    /// <summary>La cartella <c>pc</c> del repository, trovata dal percorso di questo file al momento della compilazione.</summary>
    public static string CartellaPc([CallerFilePath] string questoFile = "") =>
        Path.GetFullPath(Path.Combine(Path.GetDirectoryName(questoFile)!, ".."));
}

/// <summary>Un motore già collegato a un server finto, su una cartella temporanea.</summary>
internal sealed class Banco : IDisposable
{
    public const string Server = "https://lectio.esempio.ts.net";
    public const string Token = "token-finto-di-prova-0123456789abcdef";

    private readonly CartellaTemporanea cartella = new();
    private readonly ClientLectio client;

    public Banco(Func<HandlerFinto.Richiesta, (int, string)> risponde, bool abbinato = true, string server = Server, string? serverId = null, string? fuori = null, long? cursore = null)
    {
        Finto = new HandlerFinto(risponde);
        Percorsi = new Percorsi(cartella.Percorso);
        if (abbinato)
        {
            Archivio.ScriviJson(Percorsi.Config, new Configurazione
            {
                Server = server,
                TokenProtetto = Dpapi.Proteggi(Token),
                DispositivoId = 7,
                NomeDispositivo = "Computer di prova",
                ServerId = serverId,
                IndirizzoFuori = fuori,
                CursoreNovita = cursore,
            });
        }
        client = new ClientLectio(Finto);
        Motore = new Motore.Motore(Percorsi, client, () => Adesso);
    }

    public HandlerFinto Finto { get; }
    public Percorsi Percorsi { get; }
    public Motore.Motore Motore { get; }
    public DateTimeOffset Adesso { get; set; } = new(2026, 10, 27, 18, 0, 0, TimeSpan.FromHours(1));

    public void Dispose()
    {
        Motore.Dispose();
        client.Dispose();
        cartella.Dispose();
    }
}
