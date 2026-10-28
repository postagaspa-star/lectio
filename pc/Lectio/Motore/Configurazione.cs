namespace Lectio.Motore;

/// <summary>Dove stanno i dati: <c>%LOCALAPPDATA%\Lectio\</c> (o la cartella di <c>--dati</c> per le prove).</summary>
public sealed class Percorsi
{
    public Percorsi(string radice)
    {
        Radice = radice;
        Directory.CreateDirectory(radice);
    }

    public static string Predefinita =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lectio");

    public string Radice { get; }
    public string Config => Path.Combine(Radice, "config.json");
    public string Memoria => Path.Combine(Radice, "memoria");

    /// <summary>Gli MP3 delle lezioni scaricate: la pagina li ascolta da qui (cartella virtuale di WebView2).</summary>
    public string Lezioni => Path.Combine(Radice, "lezioni");

    public string Log => Path.Combine(Radice, "log");
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
