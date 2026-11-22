namespace Lectio.Motore;

/// <summary>
/// Dove stanno i dati: <c>%LOCALAPPDATA%\Lectio\</c>, oppure la cartella scritta in <see cref="FileCartellaDati"/> accanto a
/// <c>Lectio.exe</c> (o quella di <c>--dati</c> per le prove).
/// </summary>
public sealed class Percorsi
{
    public Percorsi(string radice)
    {
        Radice = radice;
        Directory.CreateDirectory(radice);
    }

    public static string Predefinita =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lectio");

    /// <summary>
    /// Il file accanto a <c>Lectio.exe</c> che sceglie un'altra cartella per i dati: la prima riga non vuota, un percorso
    /// assoluto o relativo alla cartella del programma (per esempio <c>..\Dati</c>). Il pacchetto non lo contiene, quindi
    /// gli aggiornamenti lo lasciano dov'è.
    /// </summary>
    public const string FileCartellaDati = "cartella-dati.txt";

    /// <summary>La cartella dei dati del programma che sta in <paramref name="cartellaProgramma"/>.</summary>
    public static string Scelta(string cartellaProgramma)
    {
        try
        {
            var file = Path.Combine(cartellaProgramma, FileCartellaDati);
            if (File.Exists(file))
            {
                var riga = File.ReadLines(file).Select(r => r.Trim().Trim('"').Trim()).FirstOrDefault(r => r.Length > 0);
                if (riga != null) return Path.GetFullPath(Path.Combine(cartellaProgramma, riga));
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            // File illeggibile o percorso non valido: si resta nella cartella di sempre.
        }
        return Predefinita;
    }

    /// <summary>La cartella dei dati di questo Lectio, quando non si passa <c>--dati</c>.</summary>
    public static string PerQuestoProgramma => Scelta(AppContext.BaseDirectory);

    /// <summary>true se le due cartelle sono la stessa (maiuscole e barra finale non contano).</summary>
    public static bool Stessa(string a, string b) =>
        string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);

    public string Radice { get; }
    public string Config => Path.Combine(Radice, "config.json");
    public string Memoria => Path.Combine(Radice, "memoria");

    /// <summary>Le foto degli schemi già viste, per rileggere uno schema anche senza rete.</summary>
    public string Immagini => Path.Combine(Radice, "immagini");

    /// <summary>Gli MP3 delle lezioni scaricate: la pagina li ascolta da qui (cartella virtuale di WebView2).</summary>
    public string Lezioni => Path.Combine(Radice, "lezioni");

    public string Log => Path.Combine(Radice, "log");

    /// <summary>La versione nuova scaricata e scompattata, in attesa di installarsi (vedi <see cref="Aggiornatore"/>).</summary>
    public string Aggiornamento => Path.Combine(Radice, "aggiornamento");
    public string WebView => Path.Combine(Radice, "WebView2");
}

/// <summary><c>config.json</c>: server, token protetto con DPAPI, dispositivo, scelte di questo computer. Mai il token in chiaro.</summary>
public sealed class Configurazione
{
    public string? Server { get; set; }
    public string? TokenProtetto { get; set; }
    public long? DispositivoId { get; set; }
    public string? NomeDispositivo { get; set; }
    public DateTimeOffset? AbbinatoIl { get; set; }

    /// <summary>La visita guidata è stata vista (o saltata): al prossimo avvio non riparte da sola.</summary>
    public bool TutorialVisto { get; set; }

    /// <summary>La visita delle cose nuove (schemi, ripasso lampo, verifiche simulate) è stata vista o saltata.</summary>
    public bool TutorialNovitaVisto { get; set; }

    /// <summary>
    /// L'ultimo giro di novità visto (0 = solo il primo, prima che esistessero i giri). Chi ha già visto la visita delle
    /// cose nuove rivede solo i passi dei giri più recenti (1.3.6: Modifica; 1.3.7: Mappa). <see cref="GiroNovita"/> è quello attuale.
    /// </summary>
    public int NovitaViste { get; set; }

    /// <summary>Il giro di novità di questa versione: va alzato insieme a <c>giro</c> dei passi nuovi in visita.js.</summary>
    public const int GiroNovita = 3;

    /// <summary>Tema scelto nelle impostazioni: "chiaro", "scuro" oppure null (come Windows).</summary>
    public string? Tema { get; set; }

    /// <summary>L'id dell'ultima novità vista (§11): le notifiche partono solo per quelle dopo.</summary>
    public long? CursoreNovita { get; set; }

    /// <summary>§15.5: chi è il server (32 esadecimali), salvato all'abbinamento. Su http il token parte solo se coincide.</summary>
    public string? ServerId { get; set; }

    /// <summary>L'indirizzo da fuori casa scritto a mano da Andrea nelle Impostazioni (https): vale sopra quello del server.</summary>
    public string? IndirizzoFuori { get; set; }

    /// <summary>§15.5: l'indirizzo da fuori casa che manda il server (abbinamento e salute); si aggiorna da solo.</summary>
    public string? IndirizzoFuoriServer { get; set; }
}
