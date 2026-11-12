namespace Lectio;

/// <summary>
/// La versione del programma per il computer. <see cref="Codice"/> si confronta con
/// <c>pc.codice</c> di <c>GET /api/versione</c>: se quello del server è più alto, c'è un aggiornamento.
/// </summary>
public static class Versione
{
    public const string Nome = "1.3.3";
    public const int Codice = 9;
}
