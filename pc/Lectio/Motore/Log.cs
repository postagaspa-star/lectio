using System.Globalization;
using System.Text;

namespace Lectio.Motore;

/// <summary>
/// Il diario tecnico del programma (<c>log/lectio-AAAA-MM-GG.log</c>, 10 giorni). Qui non entrano mai
/// contenuti delle verifiche o delle lezioni: solo cosa ha fatto il programma e con che esito.
/// </summary>
public static class Log
{
    private static readonly object Blocco = new();
    private static string? cartella;

    public static void Inizia(string cartellaLog)
    {
        cartella = cartellaLog;
        try
        {
            Directory.CreateDirectory(cartellaLog);
            foreach (var f in Directory.EnumerateFiles(cartellaLog, "lectio-*.log"))
            {
                if (File.GetLastWriteTimeUtc(f) < DateTime.UtcNow.AddDays(-10)) File.Delete(f);
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }

    public static void Info(string messaggio) => Scrivi("INFO", messaggio);

    public static void Avviso(string messaggio) => Scrivi("AVVISO", messaggio);

    public static void Errore(string messaggio, Exception? e = null) =>
        Scrivi("ERRORE", e == null ? messaggio : $"{messaggio}: {e.GetType().Name}: {e.Message}");

    private static void Scrivi(string livello, string messaggio)
    {
        if (cartella == null) return;
        var adesso = DateTime.Now;
        var riga = $"{adesso.ToString("yyyy-MM-dd HH:mm:ss.fff", CultureInfo.InvariantCulture)} {livello} {messaggio}{Environment.NewLine}";
        lock (Blocco)
        {
            try
            {
                File.AppendAllText(Path.Combine(cartella, $"lectio-{adesso.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)}.log"), riga, Encoding.UTF8);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }
}
