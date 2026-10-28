namespace Lectio.Nucleo;

/// <summary>
/// I testi in italiano semplice che il programma mostra quando qualcosa non va. Mai codici o
/// termini tecnici: se il server manda già una frase (<c>errore</c>), vale la sua.
/// </summary>
public static class Frasi
{
    public const string ServerIrraggiungibile = "Lectio non riesce a raggiungere il server di casa. Controlla la connessione a internet e riprova.";
    public const string NonCollegato = "Questo computer non è ancora collegato al server di casa.";
    public const string NonRiconosciuto = "Il server di casa non riconosce più questo computer. Collegalo di nuovo con un codice nuovo.";
    public const string RichiestaSconosciuta = "Il programma ha chiesto una cosa che il server non conosce. Forse uno dei due va aggiornato.";

    /// <summary>La frase per una risposta non riuscita: quella del server se c'è, altrimenti una nostra.</summary>
    public static string PerRisposta(int stato, string? corpo)
    {
        if (stato == 0) return ServerIrraggiungibile;
        var dalServer = Json.Leggi<ErroreServer>(SoloOggetto(corpo))?.Errore;
        if (!string.IsNullOrWhiteSpace(dalServer)) return dalServer!;
        return stato switch
        {
            401 => NonRiconosciuto,
            402 => "Questa lezione sforerebbe il tetto del mese: usa la voce gratis.",
            403 => "Il server di casa non permette questa operazione.",
            404 => "Quello che cerchi non c'è più sul server: forse è stato tolto nel frattempo.",
            409 => "Non si può fare adesso: la situazione è cambiata nel frattempo. Ricarica e riprova.",
            413 => "Il file è troppo grande: il massimo è 20 MB.",
            422 => "Il server non ha accettato i dati: controlla i campi e riprova.",
            429 => "Troppi tentativi in poco tempo. Aspetta qualche minuto e riprova.",
            502 or 503 or 504 => "Il server di casa in questo momento non risponde bene. Riprova tra poco.",
            >= 500 => "Il server di casa ha avuto un problema. Riprova tra poco.",
            _ => "L'operazione non è riuscita. Riprova tra poco.",
        };
    }

    /// <summary>Solo un oggetto JSON conta come corpo d'errore del server (non una pagina HTML di un proxy).</summary>
    private static string? SoloOggetto(string? corpo) =>
        corpo != null && corpo.TrimStart().StartsWith('{') ? corpo : null;

    /// <summary>Accorcia un testo per una notifica di Windows, che ha spazio limitato.</summary>
    public static string Accorcia(string? testo, int massimo)
    {
        if (string.IsNullOrEmpty(testo)) return "";
        var pulito = string.Join(' ', testo.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        return pulito.Length <= massimo ? pulito : pulito[..(massimo - 1)].TrimEnd() + "…";
    }
}
