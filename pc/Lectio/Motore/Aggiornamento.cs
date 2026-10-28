using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using Lectio.Nucleo;
using Lectio.Rete;

namespace Lectio.Motore;

/// <summary>Una versione del programma più nuova di questa, annunciata dal server (§3).</summary>
public sealed record VersioneNuova(int Codice, string Nome, string IndirizzoZip, string? Sha256, long? Byte, string IndirizzoFirma = "", bool? Firmata = null);

/// <summary>Com'è andato lo scaricamento della versione nuova.</summary>
public sealed record EsitoScaricamento(bool Ok, string? File, string? Errore);

/// <summary>Una versione nuova scaricata, controllata e scompattata in <see cref="Cartella"/>, pronta da installare.</summary>
public sealed record AggiornamentoPronto(int Codice, string Nome, string Cartella);

/// <summary>Com'è andata la preparazione (scaricamento, controlli, scompattamento).</summary>
public sealed record EsitoPreparazione(bool Ok, AggiornamentoPronto? Pronto, string? Errore);

/// <summary><c>versione.json</c> dentro lo zip (quindi coperto dalla firma); con la stessa forma i segni nella cartella dell'aggiornamento.</summary>
public sealed record VersioneNelPacchetto(int Codice, string Nome);

/// <summary>Il segno di un'installazione non riuscita: quella versione si riprova solo dopo un giorno.</summary>
public sealed record AggiornamentoFallito(int Codice, DateTimeOffset Quando);

/// <summary>Un pacchetto firmato che però non si può installare (la frase finisce nelle Impostazioni).</summary>
public sealed class PacchettoNonValido(string messaggio) : Exception(messaggio);

/// <summary>
/// L'aggiornamento si fa da solo. 20 secondi dopo l'avvio e poi ogni 12 ore si legge <c>GET /api/versione</c>; se
/// <c>pc.codice</c> è più alto del nostro, in sottofondo si scarica lo zip (<c>GET /scarica/lectio-pc.zip</c>) nella
/// cartella <c>aggiornamento</c> dei dati, se ne controllano impronta, dimensione e FIRMA (<c>GET
/// /scarica/lectio-pc.zip.sig</c>, ECDSA P-256: la può fare solo chi ha la chiave privata, che non sta né sul server
/// né nel repository) e lo si scompatta in <c>aggiornamento\nuova</c>. Dentro lo zip <c>versione.json</c> dice il
/// numero vero: se non è più alto del nostro il pacchetto si scarta (niente ritorni a versioni vecchie).
/// Si installa quando Lectio si chiude, o al primo avvio dopo (vedi <see cref="Sistema.Sostituzione"/>).
/// </summary>
public sealed class Aggiornatore
{
    public const string FileZip = "lectio-pc.zip";
    public const string FileFirma = "lectio-pc.zip.sig";
    public const string FileVersione = "versione.json";

    /// <summary>Nella cartella dell'aggiornamento: il programma nuovo scompattato, i file vecchi messi da parte, i segni.</summary>
    public const string CartellaNuova = "nuova";
    public const string CartellaVecchia = "vecchia";
    public const string FilePronta = "pronta.json";
    public const string FileFallita = "fallita.json";
    public const string FileFatto = "fatto.json";

    /// <summary>Dopo un'installazione non riuscita la stessa versione si riprova solo il giorno dopo.</summary>
    public static readonly TimeSpan AttesaDopoErrore = TimeSpan.FromHours(24);

    /// <summary>Lo zip scompattato non può superare questa dimensione (oggi sono circa 160 MB).</summary>
    public const long MassimoByteScompattati = 1024L * 1024 * 1024;

    /// <summary>La chiave pubblica (SubjectPublicKeyInfo, base64) che verifica le firme dei pacchetti.</summary>
    public const string ChiavePubblicaFirma =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8FPh2WTSTKLrWc3aySyZfpCJbMd1tAq4w7+JrFnF0NzqmEReREPrfDz/ygrV5oVmfXrDFFtbDsVuj6CUDBE7Fw==";

    private readonly ClientLectio client;
    private readonly int codiceMio;
    private readonly byte[] chiavePubblica;
    private int? giaSegnalata;

    public Aggiornatore(ClientLectio client, int codiceMio, string? chiavePubblicaBase64 = null)
    {
        this.client = client;
        this.codiceMio = codiceMio;
        chiavePubblica = Convert.FromBase64String(chiavePubblicaBase64 ?? ChiavePubblicaFirma);
    }

    /// <summary>La firma (base64 di r||s) è dello zip, fatta con la chiave privata di Lectio?</summary>
    public static bool FirmaValida(string file, string? firmaBase64, byte[] chiavePubblica)
    {
        if (string.IsNullOrWhiteSpace(firmaBase64)) return false;
        try
        {
            var firma = Convert.FromBase64String(firmaBase64.Trim());
            using var ecdsa = ECDsa.Create();
            ecdsa.ImportSubjectPublicKeyInfo(chiavePubblica, out _);
            if (ecdsa.KeySize != 256) return false;
            using var flusso = File.OpenRead(file);
            return ecdsa.VerifyData(flusso, firma, HashAlgorithmName.SHA256);
        }
        catch (Exception e) when (e is FormatException or CryptographicException or IOException)
        {
            return false;
        }
    }

    /// <summary>L'ultima versione nuova vista (null se siamo già all'ultima).</summary>
    public VersioneNuova? Disponibile { get; private set; }

    public static bool ÈPiùNuova(InfoVersione? v, int codiceMio) => v?.Pc != null && v.Pc.Codice > codiceMio;

    public static string IndirizzoDelloZip(string server) => server.TrimEnd('/') + "/scarica/" + FileZip;

    public static string IndirizzoDellaFirma(string server) => server.TrimEnd('/') + "/scarica/" + FileFirma;

    /// <summary>Restituisce la versione nuova solo la prima volta che la si vede (per non ripetere la notifica).</summary>
    public async Task<VersioneNuova?> ControllaAsync(string server, CancellationToken annulla = default)
    {
        var r = await client.InviaAsync("GET", server, "api/versione", null, null, annulla: annulla).ConfigureAwait(false);
        if (!r.Ok) return null;
        var info = Json.Leggi<InfoVersione>(r.Corpo);
        if (!ÈPiùNuova(info, codiceMio))
        {
            Disponibile = null;
            return null;
        }
        var pc = info!.Pc!;
        Disponibile = new VersioneNuova(pc.Codice, pc.Nome ?? pc.Codice.ToString(System.Globalization.CultureInfo.InvariantCulture),
            IndirizzoDelloZip(server), string.IsNullOrWhiteSpace(pc.Sha256) ? null : pc.Sha256.Trim(), pc.Byte, IndirizzoDellaFirma(server), pc.Firma);
        if (giaSegnalata == pc.Codice) return null;
        giaSegnalata = pc.Codice;
        Log.Info($"versione nuova disponibile: {Disponibile.Nome} (codice {pc.Codice})");
        return Disponibile;
    }

    /// <summary>Scarica lo zip e lo tiene solo se impronta SHA-256 e dimensione sono quelle annunciate.</summary>
    public async Task<EsitoScaricamento> ScaricaAsync(string cartella, CancellationToken annulla = default)
    {
        var v = Disponibile;
        if (v == null) return new EsitoScaricamento(false, null, "Non c'è una versione nuova da scaricare.");
        if (v.Firmata == false)
        {
            return new EsitoScaricamento(false, null, "Sul server la versione nuova non ha la firma di Lectio, quindi non la scarico. Va rifatto il pacchetto.");
        }
        if (v.Sha256 == null || v.Sha256.Length != 64)
        {
            return new EsitoScaricamento(false, null, "Il server non indica l'impronta della versione nuova, quindi Lectio non può controllare il file. Va aggiornato prima il server.");
        }
        var nome = "lectio-pc-" + string.Concat(v.Nome.Where(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-')) + ".zip";
        var file = Path.Combine(cartella, nome);
        var provvisorio = file + ".scaricamento";
        try
        {
            Directory.CreateDirectory(cartella);
            var esito = await client.ScaricaAsync(v.IndirizzoZip, null, provvisorio, null, annulla).ConfigureAwait(false);
            if (!esito.Ok)
            {
                return new EsitoScaricamento(false, null, "Lectio non è riuscito a scaricare la versione nuova dal server di casa. Riprova più tardi.");
            }
            var controllo = Controlla(provvisorio, v.Sha256, v.Byte);
            if (controllo != null)
            {
                File.Delete(provvisorio);
                Log.Avviso("zip della versione nuova scartato: " + controllo);
                return new EsitoScaricamento(false, null, "Il file scaricato non è quello giusto (" + controllo + "): Lectio l'ha scartato. Riprova più tardi.");
            }
            // La firma: senza, o sbagliata, lo zip si scarta anche se l'impronta coincide.
            var fileFirma = provvisorio + ".sig";
            string? firma = null;
            if (!string.IsNullOrEmpty(v.IndirizzoFirma) && (await client.ScaricaAsync(v.IndirizzoFirma, null, fileFirma, null, annulla).ConfigureAwait(false)).Ok)
            {
                firma = File.ReadAllText(fileFirma, Encoding.ASCII);
            }
            try { File.Delete(fileFirma); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
            if (!FirmaValida(provvisorio, firma, chiavePubblica))
            {
                File.Delete(provvisorio);
                Log.Avviso("zip della versione nuova scartato: firma " + (firma == null ? "mancante" : "non valida"));
                return new EsitoScaricamento(false, null, "La versione nuova non ha una firma valida di Lectio, quindi l'ho scartata. Non installarla da altre parti.");
            }
            File.Move(provvisorio, file, overwrite: true);
            Log.Info($"versione {v.Nome} scaricata e controllata");
            return new EsitoScaricamento(true, file, null);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            try { File.Delete(provvisorio); } catch (Exception) { }
            Log.Errore("zip della versione nuova non salvato", e);
            return new EsitoScaricamento(false, null, "Lectio non è riuscito a salvare la versione nuova sul computer.");
        }
    }

    /// <summary>
    /// Scarica, controlla e scompatta la versione nuova in <paramref name="cartella"/>. Se è già pronta non riscarica
    /// niente. Lo zip si cancella sempre alla fine: resta solo la cartella scompattata.
    /// </summary>
    public async Task<EsitoPreparazione> PreparaAsync(string cartella, DateTimeOffset adesso, CancellationToken annulla = default)
    {
        var v = Disponibile;
        if (v == null) return new EsitoPreparazione(false, null, "Non c'è una versione nuova da scaricare.");
        if (Pronta(cartella, codiceMio, adesso, ancheSeFallita: true) is { } gia && gia.Codice >= v.Codice) return new EsitoPreparazione(true, gia, null);
        var scaricato = await ScaricaAsync(cartella, annulla).ConfigureAwait(false);
        if (!scaricato.Ok || scaricato.File == null) return new EsitoPreparazione(false, null, scaricato.Errore);
        try
        {
            var pronto = Estrai(scaricato.File, cartella, codiceMio);
            Log.Info($"versione {pronto.Nome} pronta: si installa quando Lectio si chiude");
            return new EsitoPreparazione(true, pronto, null);
        }
        catch (PacchettoNonValido e)
        {
            Log.Avviso("versione nuova scartata: " + e.Message);
            return new EsitoPreparazione(false, null, "La versione nuova non si può installare (" + e.Message + "), quindi l'ho scartata.");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidDataException)
        {
            Log.Errore("versione nuova non scompattata", e);
            return new EsitoPreparazione(false, null, "Lectio non è riuscito a preparare la versione nuova. Riprova più tardi.");
        }
        finally
        {
            try { File.Delete(scaricato.File); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        }
    }

    /// <summary>
    /// Scompatta lo zip (già controllato) in <c>nuova</c>: solo file dentro la cartella <c>Lectio\</c> dello zip, con
    /// <c>Lectio.exe</c> e un <c>versione.json</c> più nuovo di <paramref name="codiceMio"/>. Poi scrive <c>pronta.json</c>.
    /// </summary>
    public static AggiornamentoPronto Estrai(string zip, string cartella, int codiceMio)
    {
        var nuova = Path.Combine(cartella, CartellaNuova);
        Directory.CreateDirectory(cartella);
        File.Delete(Path.Combine(cartella, FilePronta));
        if (Directory.Exists(nuova)) Directory.Delete(nuova, recursive: true);
        Directory.CreateDirectory(nuova);
        var radice = Path.GetFullPath(nuova).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        long totale = 0;
        try
        {
            using (var archivio = ZipFile.OpenRead(zip))
            {
                foreach (var voce in archivio.Entries)
                {
                    // Compress-Archive di PowerShell 5.1 scrive i percorsi con la barra rovesciata.
                    var nome = voce.FullName.Replace('\\', '/');
                    if (!nome.StartsWith("Lectio/", StringComparison.Ordinal)) throw new PacchettoNonValido("c'è un file fuori dalla cartella Lectio");
                    var destinazione = Path.GetFullPath(Path.Combine(nuova, nome));
                    if (!destinazione.StartsWith(radice, StringComparison.OrdinalIgnoreCase)) throw new PacchettoNonValido("c'è un file che esce dalla sua cartella");
                    totale += voce.Length;
                    if (totale > MassimoByteScompattati) throw new PacchettoNonValido("è troppo grande");
                    if (nome.EndsWith('/'))
                    {
                        Directory.CreateDirectory(destinazione);
                        continue;
                    }
                    Directory.CreateDirectory(Path.GetDirectoryName(destinazione)!);
                    voce.ExtractToFile(destinazione, overwrite: false);
                }
            }
            var programma = Path.Combine(nuova, "Lectio");
            if (!File.Exists(Path.Combine(programma, "Lectio.exe"))) throw new PacchettoNonValido("manca Lectio.exe");
            var v = Archivio.LeggiJson<VersioneNelPacchetto>(Path.Combine(programma, FileVersione));
            if (v == null || v.Codice <= 0 || string.IsNullOrWhiteSpace(v.Nome)) throw new PacchettoNonValido("manca il numero di versione");
            if (v.Codice <= codiceMio) throw new PacchettoNonValido("non è più nuova di questa");
            Archivio.ScriviJson(Path.Combine(cartella, FilePronta), v);
            return new AggiornamentoPronto(v.Codice, v.Nome, programma);
        }
        catch
        {
            try { Directory.Delete(nuova, recursive: true); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
            throw;
        }
    }

    /// <summary>
    /// La versione nuova già scompattata, se c'è, è più nuova di <paramref name="codiceMio"/> e non è appena fallita
    /// (in quel caso si riprova dopo <see cref="AttesaDopoErrore"/>; con <paramref name="ancheSeFallita"/> conta lo
    /// stesso, per non riscaricarla). Altrimenti null.
    /// </summary>
    public static AggiornamentoPronto? Pronta(string cartella, int codiceMio, DateTimeOffset adesso, bool ancheSeFallita = false)
    {
        var p = Archivio.LeggiJson<VersioneNelPacchetto>(Path.Combine(cartella, FilePronta));
        if (p == null || p.Codice <= codiceMio) return null;
        var programma = Path.Combine(cartella, CartellaNuova, "Lectio");
        if (!File.Exists(Path.Combine(programma, "Lectio.exe"))) return null;
        // La cartella deve dire la stessa versione del segno (nessuno l'ha cambiata a metà).
        if (Archivio.LeggiJson<VersioneNelPacchetto>(Path.Combine(programma, FileVersione))?.Codice != p.Codice) return null;
        var f = Archivio.LeggiJson<AggiornamentoFallito>(Path.Combine(cartella, FileFallita));
        if (!ancheSeFallita && f != null && f.Codice == p.Codice && adesso - f.Quando < AttesaDopoErrore) return null;
        return new AggiornamentoPronto(p.Codice, p.Nome, programma);
    }

    /// <summary>L'installazione di questa versione non è riuscita: non si riprova per un giorno.</summary>
    public static void SegnaFallita(string cartella, int codice, DateTimeOffset adesso)
    {
        try
        {
            Archivio.ScriviJson(Path.Combine(cartella, FileFallita), new AggiornamentoFallito(codice, adesso));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("segno dell'aggiornamento non riuscito non scritto", e);
        }
    }

    /// <summary>
    /// Dopo un aggiornamento (o con una versione preparata ma ormai vecchia) si toglie quello che resta: la cartella
    /// scompattata, i file messi da parte, gli zip lasciati a metà. Una versione pronta e più nuova resta.
    /// </summary>
    public static void Pulisci(string cartella, int codiceMio)
    {
        if (!Directory.Exists(cartella)) return;
        var p = Archivio.LeggiJson<VersioneNelPacchetto>(Path.Combine(cartella, FilePronta));
        var togli = new List<string> { Path.Combine(cartella, CartellaVecchia) };
        if (p == null || p.Codice <= codiceMio)
        {
            togli.AddRange(new[] { Path.Combine(cartella, CartellaNuova), Path.Combine(cartella, FilePronta), Path.Combine(cartella, FileFallita) });
        }
        togli.AddRange(Directory.GetFiles(cartella, "lectio-pc-*.zip*"));
        foreach (var x in togli)
        {
            try
            {
                if (Directory.Exists(x)) Directory.Delete(x, recursive: true);
                else if (File.Exists(x)) File.Delete(x);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                Log.Avviso("dopo l'aggiornamento non ho tolto " + Path.GetFileName(x) + ": " + e.Message);
            }
        }
    }

    /// <summary>Il segno lasciato da un'installazione riuscita: il nome della versione, letto (e tolto) al primo avvio.</summary>
    public static string? LeggiFatto(string cartella)
    {
        var file = Path.Combine(cartella, FileFatto);
        if (!File.Exists(file)) return null;
        var v = Archivio.LeggiJson<VersioneNelPacchetto>(file);
        try { File.Delete(file); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        return v?.Nome;
    }

    /// <summary>null se il file è quello annunciato, altrimenti cosa non torna (in parole semplici).</summary>
    public static string? Controlla(string file, string sha256Atteso, long? byteAttesi)
    {
        var info = new FileInfo(file);
        if (byteAttesi is long attesi && info.Length != attesi) return "la dimensione non coincide";
        using var flusso = File.OpenRead(file);
        var impronta = Convert.ToHexString(SHA256.HashData(flusso));
        return string.Equals(impronta, sha256Atteso, StringComparison.OrdinalIgnoreCase) ? null : "l'impronta non coincide";
    }
}
