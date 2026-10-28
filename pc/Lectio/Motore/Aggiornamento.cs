using System.Security.Cryptography;
using System.Text;
using Lectio.Nucleo;
using Lectio.Rete;

namespace Lectio.Motore;

/// <summary>Una versione del programma più nuova di questa, annunciata dal server (§3).</summary>
public sealed record VersioneNuova(int Codice, string Nome, string IndirizzoZip, string? Sha256, long? Byte, string IndirizzoFirma = "", bool? Firmata = null);

/// <summary>Com'è andato lo scaricamento della versione nuova.</summary>
public sealed record EsitoScaricamento(bool Ok, string? File, string? Errore);

/// <summary>
/// L'aggiornamento, come in Sol: ogni 12 ore si legge <c>GET /api/versione</c>; se <c>pc.codice</c> è più alto
/// del nostro, Lectio lo dice (notifica e Impostazioni). Col clic scarica lo zip servito dal server
/// (<c>GET /scarica/lectio-pc.zip</c>) nella cartella Download, ne controlla impronta e dimensione, e poi la
/// FIRMA (<c>GET /scarica/lectio-pc.zip.sig</c>, ECDSA P-256): l'impronta arriva dallo stesso server (anche in
/// http), la firma no, la può fare solo chi ha la chiave privata, che non sta né sul server né nel repository.
/// Se qualcosa non coincide lo zip si scarta; se va bene, si apre la cartella col file selezionato: lo
/// scompatta Andrea. Il programma non scompatta e non lancia eseguibili da solo.
/// </summary>
public sealed class Aggiornatore
{
    public const string FileZip = "lectio-pc.zip";
    public const string FileFirma = "lectio-pc.zip.sig";

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
            return new EsitoScaricamento(false, null, "Lectio non è riuscito a salvare il file nella cartella Download.");
        }
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
