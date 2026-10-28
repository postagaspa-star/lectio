namespace Lectio.Nucleo;

// Le forme del contratto (docs/contratto-api.md, v1.0) che il programma usa davvero in C#.
// Tutto il resto passa dal ponte così com'è: la pagina lo legge direttamente.

public sealed record ErroreServer
{
    public string? Errore { get; init; }
    public string? Codice { get; init; }
}

/// <summary>§2 e §15.5: <c>POST /api/abbina</c>.</summary>
public sealed record RispostaAbbina
{
    public string? Token { get; init; }
    public long? DispositivoId { get; init; }
    public string? ServerId { get; init; }

    /// <summary>§15.5: l'indirizzo da fuori casa scelto sul server (o null).</summary>
    public string? IndirizzoFuoriCasa { get; init; }
}

/// <summary>§3 e §15.5: <c>GET /api/salute</c> senza token: dice chi è il server (32 caratteri esadecimali fissi).</summary>
public sealed record SaluteBreve
{
    public bool Ok { get; init; }
    public string? ServerId { get; init; }

    /// <summary>§15.5: solo con token; l'indirizzo da fuori casa scelto sul server (o null).</summary>
    public string? IndirizzoFuoriCasa { get; init; }
}

/// <summary>§3: una voce di <c>GET /api/versione</c>.</summary>
public sealed record VersioneApp
{
    public int Codice { get; init; }
    public string? Nome { get; init; }
    public string? Sha256 { get; init; }
    public long? Byte { get; init; }

    /// <summary>§15.5: il server ha anche la firma dello zip (<c>/scarica/lectio-pc.zip.sig</c>). null = server vecchio.</summary>
    public bool? Firma { get; init; }
}

public sealed record InfoVersione
{
    public string? Server { get; init; }
    public VersioneApp? Android { get; init; }
    public VersioneApp? Pc { get; init; }
    public string? Nota { get; init; }
}

/// <summary>§9: <c>audio</c> di una lezione pronta.</summary>
public sealed record InfoAudio
{
    public long? Byte { get; init; }
    public string? Sha256 { get; init; }
}

/// <summary>§9: la parte di <c>Lezione</c> che serve al programma (stato, materia, audio).</summary>
public sealed record LezioneBreve
{
    public long Id { get; init; }
    public long? MateriaId { get; init; }
    public string? Argomento { get; init; }
    public string? Stato { get; init; }
    public InfoAudio? Audio { get; init; }

    public bool InLavorazione => Stato is "in_coda" or "in_preparazione";
}

/// <summary>§11: una voce di <c>GET /api/novita</c>.</summary>
public sealed record VoceNovita
{
    public long Id { get; init; }
    public string? Tipo { get; init; }
    public string? Testo { get; init; }
    public long? LezioneId { get; init; }
    public string? Quando { get; init; }
}
