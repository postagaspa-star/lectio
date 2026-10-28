using System.Globalization;
using System.Windows.Forms;
using Lectio.Interfaccia;
using Lectio.Motore;
using Lectio.Sistema;

namespace Lectio;

/// <summary>Le opzioni della riga di comando: servono alle prove e allo sviluppo.</summary>
public sealed class Opzioni
{
    public string CartellaDati { get; private set; } = Percorsi.Predefinita;
    public bool CartellaDatiPersonale { get; private set; }
    public int? EsciDopoSecondi { get; private set; }

    /// <summary>Niente controllo delle versioni nuove (prove e sviluppo).</summary>
    public bool NonAggiornare { get; private set; }

    /// <summary>
    /// Prova del programma vero senza server: la finestra si apre fuori dallo schermo, la pagina prova
    /// l'audio dalla cartella virtuale e l'invio di byte al programma, e scrive l'esito nel diario.
    /// </summary>
    public bool Diagnosi { get; private set; }

    /// <summary>
    /// Avviato dal Lectio di prima per installare questa versione (<see cref="Sostituzione"/>): la cartella del
    /// programma da aggiornare, il processo da aspettare e se riaprire Lectio alla fine.
    /// </summary>
    public bool ApplicaAggiornamento { get; private set; }
    public string? Programma { get; private set; }
    public int? Attendi { get; private set; }
    public bool Riapri { get; private set; }

    public static Opzioni Da(string[] args)
    {
        var o = new Opzioni();
        for (int i = 0; i < args.Length; i++)
        {
            string Prossimo() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"manca il valore di {args[i]}");
            switch (args[i])
            {
                case "--dati":
                    o.CartellaDati = Path.GetFullPath(Prossimo());
                    o.CartellaDatiPersonale = true;
                    break;
                case "--esci-dopo": o.EsciDopoSecondi = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
                case "--no-aggiorna": o.NonAggiornare = true; break;
                case "--diagnosi": o.Diagnosi = true; break;
                case Sostituzione.Argomento: o.ApplicaAggiornamento = true; break;
                case "--programma": o.Programma = Prossimo(); break;
                case "--attendi": o.Attendi = int.Parse(Prossimo(), CultureInfo.InvariantCulture); break;
                case "--riapri": o.Riapri = true; break;
            }
        }
        return o;
    }

    /// <summary>Con una cartella dati di prova serve un'istanza a parte; su quella vera l'istanza resta una sola.</summary>
    public string SuffissoIstanza =>
        CartellaDatiPersonale && !string.Equals(Path.GetFullPath(CartellaDati).TrimEnd('\\'), Path.GetFullPath(Percorsi.Predefinita).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)
            ? "." + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Path.GetFullPath(CartellaDati).ToLowerInvariant())))[..12]
            : "";
}

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Opzioni opzioni;
        try
        {
            opzioni = Opzioni.Da(args);
        }
        catch (Exception e) when (e is ArgumentException or FormatException)
        {
            MessageBox.Show(e.Message, "Lectio", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 2;
        }

        // Il Lectio nuovo lanciato dal vecchio: si installa senza finestre ed esce.
        if (opzioni.ApplicaAggiornamento) return Sostituzione.Esegui(opzioni);

        ApplicationConfiguration.Initialize();
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => Log.Errore("eccezione non gestita nella finestra", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Errore("eccezione non gestita", e.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Log.Errore("eccezione in un compito", e.Exception);
            e.SetObserved();
        };

        using var istanza = new Istanza(opzioni.SuffissoIstanza);
        if (!istanza.Prima)
        {
            istanza.ChiediApertura();
            return 0;
        }
        istanza.Ascolta();

        var percorsi = new Percorsi(opzioni.CartellaDati);
        Log.Inizia(percorsi.Log);
        Log.Info($"Lectio {Versione.Nome} avviato");
        var aggiornamenti = !opzioni.NonAggiornare && !opzioni.Diagnosi && Installazione.ÈPacchetto;

        // Una versione nuova già pronta e non ancora installata (per esempio Lectio si è chiuso mentre Windows si
        // spegneva) si installa adesso, prima della finestra: Lectio si riapre da solo dopo pochi secondi.
        if (aggiornamenti && Aggiornatore.Pronta(percorsi.Aggiornamento, Versione.Codice, DateTimeOffset.Now) is { } pronta
            && Sostituzione.Avvia(pronta, AppContext.BaseDirectory, opzioni.CartellaDati, riapri: true))
        {
            return 0;
        }
        Installazione.AssicuraUi(AppContext.BaseDirectory);

        using var motore = new Motore.Motore(percorsi)
        {
            AggiornamentiAttivi = aggiornamenti,
            Diagnosi = opzioni.Diagnosi,
        };
        if (opzioni.Diagnosi) Diagnosi.PreparaAudio(percorsi.Lezioni);

        var contesto = new ContestoLectio(motore, opzioni, istanza);
        Application.Run(contesto);
        Log.Info("Lectio chiuso");

        // Chiuso Lectio, la versione nuova pronta si installa adesso. Mai mentre Windows si spegne: la copia
        // resterebbe a metà (in quel caso si installa al prossimo avvio, qui sopra).
        if (aggiornamenti && !contesto.FineSessione && Aggiornatore.Pronta(percorsi.Aggiornamento, Versione.Codice, DateTimeOffset.Now) is { } dopo)
        {
            Sostituzione.Avvia(dopo, AppContext.BaseDirectory, opzioni.CartellaDati, riapri: contesto.RiapriDopo);
        }
        return 0;
    }
}

/// <summary>Per <c>--diagnosi</c>: un secondo di silenzio in WAV nella cartella delle lezioni, che la pagina prova a leggere.</summary>
internal static class Diagnosi
{
    public const string FileAudio = "diagnosi.wav";

    public static void PreparaAudio(string cartella)
    {
        try
        {
            Directory.CreateDirectory(cartella);
            File.WriteAllBytes(Path.Combine(cartella, FileAudio), Silenzio(1));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("audio di diagnosi non scritto", e);
        }
    }

    /// <summary>Un WAV PCM 8 kHz mono 16 bit di <paramref name="secondi"/> secondi di silenzio.</summary>
    internal static byte[] Silenzio(int secondi)
    {
        const int campioni = 8000;
        int dati = campioni * 2 * secondi;
        using var m = new MemoryStream();
        using var w = new BinaryWriter(m);
        w.Write("RIFF"u8.ToArray());
        w.Write(36 + dati);
        w.Write("WAVE"u8.ToArray());
        w.Write("fmt "u8.ToArray());
        w.Write(16);
        w.Write((short)1);
        w.Write((short)1);
        w.Write(campioni);
        w.Write(campioni * 2);
        w.Write((short)2);
        w.Write((short)16);
        w.Write("data"u8.ToArray());
        w.Write(dati);
        w.Write(new byte[dati]);
        w.Flush();
        return m.ToArray();
    }
}
