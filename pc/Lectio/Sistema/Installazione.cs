namespace Lectio.Sistema;

/// <summary>
/// Lectio non si installa: gira dalla cartella dove lo zip è stato scompattato. Non si copia da nessuna
/// parte, non si avvia da solo con Windows e non scrive nel registro. I suoi dati stanno in
/// <c>%LOCALAPPDATA%\Lectio</c>.
/// </summary>
public static class Installazione
{
    /// <summary>
    /// true quando gira dal pacchetto pubblicato (self-contained): accanto all'exe c'è il runtime .NET.
    /// Una build di sviluppo e i test no: non cercano versioni nuove.
    /// </summary>
    public static bool ÈPacchetto => File.Exists(Path.Combine(AppContext.BaseDirectory, "coreclr.dll"));

    /// <summary>Se accanto all'exe manca l'interfaccia (cancellata per sbaglio), la tira fuori da quella incorporata.</summary>
    public static void AssicuraUi(string cartellaExe)
    {
        var ui = Path.Combine(cartellaExe, "ui");
        if (File.Exists(Path.Combine(ui, "index.html"))) return;
        var assembly = typeof(Installazione).Assembly;
        foreach (var nome in assembly.GetManifestResourceNames().Where(n => n.StartsWith("ui/", StringComparison.Ordinal)))
        {
            var relativo = nome["ui/".Length..].Replace('\\', Path.DirectorySeparatorChar).Replace('/', Path.DirectorySeparatorChar);
            if (relativo.Contains("..", StringComparison.Ordinal)) continue;
            var file = Path.Combine(ui, relativo);
            Directory.CreateDirectory(Path.GetDirectoryName(file)!);
            using var sorgente = assembly.GetManifestResourceStream(nome)!;
            using var uscita = File.Create(file);
            sorgente.CopyTo(uscita);
        }
    }
}
