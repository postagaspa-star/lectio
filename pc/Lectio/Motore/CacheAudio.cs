using System.Globalization;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Lectio.Nucleo;
using Lectio.Rete;

namespace Lectio.Motore;

/// <summary>Una lezione scaricata sul computer.</summary>
public sealed record VoceCache(long Id, string File, string Nome, long Byte);

/// <summary>Com'è andato lo scaricamento dell'audio di una lezione.</summary>
public sealed record EsitoAudio(bool Ok, string? Nome, long Byte, string? Errore, int Stato = 200)
{
    public string? Url => Nome == null ? null : CacheAudio.Url(Nome);
}

/// <summary>
/// Gli MP3 delle lezioni in <c>%LOCALAPPDATA%\Lectio\lezioni</c>. Il programma li scarica col token
/// (<c>GET /api/lezioni/{id}/audio</c>), ne controlla dimensione e impronta, e la pagina li ascolta da
/// <c>https://audio.lectio.locale/</c>, una cartella virtuale di WebView2: il token non entra mai nella pagina.
/// Il nome del file porta l'impronta: se il server rifà l'audio di una lezione, il file vecchio si toglie.
/// </summary>
public sealed class CacheAudio
{
    public const string Host = "audio.lectio.locale";
    public const string Origine = "https://" + Host;

    private static readonly Regex NomeValido = new(@"^lezione-(\d{1,12})-([0-9a-f]{16}|senza-impronta)\.mp3$",
        RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);

    private readonly string cartella;
    private readonly ClientLectio client;
    private readonly object blocco = new();
    private readonly Dictionary<long, Task<EsitoAudio>> inCorso = new();
    private readonly Dictionary<long, double> avanzamento = new();

    /// <summary>Quanto spazio al massimo per le lezioni scaricate: oltre, si tolgono le meno usate.</summary>
    public const long TettoPredefinito = 1L * 1024 * 1024 * 1024;

    public CacheAudio(string cartella, ClientLectio client)
    {
        this.cartella = cartella;
        this.client = client;
        PulisciParziali();
    }

    /// <summary>Tetto dello spazio (1 GB; nelle prove si abbassa).</summary>
    public long TettoByte { get; set; } = TettoPredefinito;

    /// <summary>All'avvio: i pezzi di scaricamenti interrotti (.part, .scaricamento) non servono più.</summary>
    public void PulisciParziali()
    {
        if (!Directory.Exists(cartella)) return;
        foreach (var f in Directory.EnumerateFiles(cartella).Where(f => f.EndsWith(".part", StringComparison.OrdinalIgnoreCase) || f.EndsWith(".scaricamento", StringComparison.OrdinalIgnoreCase)).ToList())
        {
            TryDelete(f);
        }
    }

    /// <summary>Toglie l'audio delle lezioni che il server non ha più (eliminate). Quelle in uso restano.</summary>
    public int TogliAssenti(IEnumerable<long> idSulServer)
    {
        var restano = new HashSet<long>(idSulServer);
        int tolte = 0;
        foreach (var v in Elenco().Where(v => !restano.Contains(v.Id)))
        {
            if (TryDelete(v.File)) tolte++;
        }
        if (tolte > 0) Log.Info($"tolte {tolte} lezioni scaricate che il server non ha più");
        return tolte;
    }

    /// <summary>Oltre il tetto si tolgono le lezioni usate meno di recente, mai quella appena chiesta.</summary>
    public void Pota(long? daTenere = null)
    {
        var voci = Elenco().Select(v => (v, quando: System.IO.File.GetLastWriteTimeUtc(v.File))).OrderBy(x => x.quando).ToList();
        long totale = voci.Sum(x => x.v.Byte);
        foreach (var (v, _) in voci)
        {
            if (totale <= TettoByte) break;
            if (v.Id == daTenere) continue;
            if (TryDelete(v.File)) totale -= v.Byte;
        }
    }

    public string Cartella => cartella;

    public static string NomeFile(long id, string? sha256)
    {
        var impronta = sha256 != null && sha256.Length == 64 && sha256.All(Uri.IsHexDigit)
            ? sha256[..16].ToLowerInvariant()
            : "senza-impronta";
        return $"lezione-{id.ToString(CultureInfo.InvariantCulture)}-{impronta}.mp3";
    }

    public static string Url(string nomeFile) => Origine + "/" + Uri.EscapeDataString(nomeFile);

    /// <summary>L'id della lezione dal nome del file, o null se il file non è una lezione di Lectio.</summary>
    public static long? IdDa(string nomeFile)
    {
        var m = NomeValido.Match(nomeFile);
        return m.Success && long.TryParse(m.Groups[1].Value, NumberStyles.None, CultureInfo.InvariantCulture, out var id) ? id : null;
    }

    public IReadOnlyList<VoceCache> Elenco()
    {
        if (!Directory.Exists(cartella)) return Array.Empty<VoceCache>();
        var voci = new List<VoceCache>();
        foreach (var f in new DirectoryInfo(cartella).GetFiles("lezione-*.mp3"))
        {
            if (IdDa(f.Name) is long id && f.Length > 0) voci.Add(new VoceCache(id, f.FullName, f.Name, f.Length));
        }
        return voci.OrderBy(v => v.Id).ToList();
    }

    public VoceCache? Di(long id) => Elenco().Where(v => v.Id == id).OrderByDescending(v => System.IO.File.GetLastWriteTimeUtc(v.File)).FirstOrDefault();

    public long Totale() => Elenco().Sum(v => v.Byte);

    /// <summary>Quanto è arrivato dello scaricamento in corso (0-1), o null se non se ne sta scaricando una.</summary>
    public double? Avanzamento(long id)
    {
        lock (blocco) return inCorso.ContainsKey(id) ? avanzamento.GetValueOrDefault(id) : null;
    }

    /// <summary>
    /// L'audio della lezione sul computer: se c'è già (stessa impronta e dimensione) subito, altrimenti lo scarica.
    /// Due richieste per la stessa lezione aspettano lo stesso scaricamento.
    /// </summary>
    public Task<EsitoAudio> ScaricaAsync(string server, string token, long id, InfoAudio? atteso, CancellationToken annulla = default)
    {
        var nome = NomeFile(id, atteso?.Sha256);
        var file = Path.Combine(cartella, nome);
        try
        {
            var info = new FileInfo(file);
            if (info.Exists && info.Length > 0 && (atteso?.Byte is not long attesi || attesi == info.Length))
            {
                // Usata adesso: è tra le ultime che il tetto dello spazio toglierebbe.
                try { System.IO.File.SetLastWriteTimeUtc(file, DateTime.UtcNow); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
                return Task.FromResult(new EsitoAudio(true, nome, info.Length, null));
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
        lock (blocco)
        {
            if (inCorso.TryGetValue(id, out var gia)) return gia;
            avanzamento[id] = 0;
            var compito = EseguiAsync(server, token, id, atteso, nome, annulla);
            inCorso[id] = compito;
            return compito;
        }
    }

    private async Task<EsitoAudio> EseguiAsync(string server, string token, long id, InfoAudio? atteso, string nome, CancellationToken annulla)
    {
        await Task.Yield();
        var file = Path.Combine(cartella, nome);
        var provvisorio = file + ".scaricamento";
        try
        {
            Directory.CreateDirectory(cartella);
            var url = server.TrimEnd('/') + "/api/lezioni/" + id.ToString(CultureInfo.InvariantCulture) + "/audio";
            var esito = await client.ScaricaAsync(url, token, provvisorio, (arrivati, totale) =>
            {
                var t = totale ?? atteso?.Byte;
                lock (blocco) avanzamento[id] = t is long n && n > 0 ? Math.Min(1.0, arrivati / (double)n) : 0;
            }, annulla).ConfigureAwait(false);
            if (!esito.Ok)
            {
                var frase = esito.Stato switch
                {
                    401 => Frasi.NonRiconosciuto,
                    404 => "L'audio di questa lezione non c'è sul server: forse la lezione non è ancora pronta o è stata tolta.",
                    0 => "Non riesco a scaricare la lezione: controlla la connessione a internet e riprova.",
                    _ => Frasi.PerRisposta(esito.Stato, null),
                };
                return new EsitoAudio(false, null, 0, frase, esito.Stato == 0 ? 503 : esito.Stato);
            }
            var controllo = Controlla(provvisorio, atteso);
            if (controllo != null)
            {
                TryDelete(provvisorio);
                Log.Avviso($"audio della lezione {id} scartato: {controllo}");
                return new EsitoAudio(false, null, 0, "Il file della lezione è arrivato rovinato, così non lo tengo. Riprova.", 502);
            }
            File.Move(provvisorio, file, overwrite: true);
            foreach (var vecchia in Elenco().Where(v => v.Id == id && !string.Equals(v.Nome, nome, StringComparison.OrdinalIgnoreCase)))
            {
                TryDelete(vecchia.File);
            }
            var byteFile = new FileInfo(file).Length;
            Log.Info($"audio della lezione {id} scaricato ({byteFile} byte)");
            Pota(id);
            return new EsitoAudio(true, nome, byteFile, null);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ObjectDisposedException)
        {
            TryDelete(provvisorio);
            Log.Errore($"audio della lezione {id} non salvato", e);
            return new EsitoAudio(false, null, 0, "Non riesco a salvare la lezione sul computer. Controlla che ci sia spazio sul disco.", 500);
        }
        finally
        {
            lock (blocco)
            {
                inCorso.Remove(id);
                avanzamento.Remove(id);
            }
        }
    }

    /// <summary>null se il file è quello annunciato dal server, altrimenti cosa non torna.</summary>
    public static string? Controlla(string file, InfoAudio? atteso)
    {
        var info = new FileInfo(file);
        if (!info.Exists || info.Length == 0) return "il file è vuoto";
        if (atteso?.Byte is long byteAttesi && info.Length != byteAttesi) return "la dimensione non coincide";
        if (atteso?.Sha256 is string sha && sha.Length == 64)
        {
            using var flusso = File.OpenRead(file);
            var impronta = Convert.ToHexString(SHA256.HashData(flusso));
            if (!string.Equals(impronta, sha, StringComparison.OrdinalIgnoreCase)) return "l'impronta non coincide";
        }
        return null;
    }

    /// <summary>Toglie dal computer l'audio di una lezione. false se un file è in uso (la lezione è aperta nel lettore).</summary>
    public bool Togli(long id)
    {
        bool tutto = true;
        foreach (var v in Elenco().Where(v => v.Id == id))
        {
            if (!TryDelete(v.File)) tutto = false;
        }
        return tutto;
    }

    /// <summary>Toglie tutte le lezioni scaricate (scollegando il computer). Restituisce quante non si sono tolte (in uso).</summary>
    public int Svuota()
    {
        int rimaste = 0;
        foreach (var v in Elenco())
        {
            if (!TryDelete(v.File)) rimaste++;
        }
        PulisciParziali();
        return rimaste;
    }

    private static bool TryDelete(string file)
    {
        try
        {
            if (System.IO.File.Exists(file)) System.IO.File.Delete(file);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
