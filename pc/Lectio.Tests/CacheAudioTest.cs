using System.Security.Cryptography;
using Lectio.Motore;
using Lectio.Nucleo;

namespace Lectio.Tests;

/// <summary>La cache degli MP3 in %LOCALAPPDATA%\Lectio\lezioni: scaricati col token, controllati, serviti alla pagina.</summary>
public class CacheAudioTest
{
    private static readonly byte[] Mp3 = Enumerable.Range(0, 5000).Select(i => (byte)(i % 251)).ToArray();
    private static readonly string Sha = Convert.ToHexString(SHA256.HashData(Mp3)).ToLowerInvariant();

    private static string Lezione(string? sha = null, long? byteAudio = null, string stato = "pronta") =>
        "{\"id\":12,\"materia_id\":1,\"stato\":\"" + stato + "\",\"audio\":{\"byte\":" + (byteAudio ?? Mp3.Length) + ",\"sha256\":\"" + (sha ?? Sha) + "\"}}";

    [Fact]
    public async Task Scarica_col_token_controlla_e_restituisce_l_indirizzo_della_cartella_virtuale()
    {
        using var b = new Banco(_ => (200, Lezione()));
        b.Finto.RispondeByte = r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? Mp3 : null;
        var r = await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(200, r.Stato);
        var url = Json.Testo(Json.Nodo(r.Json)!["url"]);
        Assert.Equal("https://audio.lectio.locale/lezione-12-" + Sha[..16] + ".mp3", url);
        Assert.DoesNotContain(Banco.Token, r.Json); // il token non arriva mai alla pagina
        var audio = Assert.Single(b.Finto.Di("GET", "/api/lezioni/12/audio"));
        Assert.Equal("Bearer " + Banco.Token, audio.Autorizzazione);
        var voce = Assert.Single(b.Motore.Audio.Elenco());
        Assert.Equal(12, voce.Id);
        Assert.Equal(Mp3, File.ReadAllBytes(voce.File));
        Assert.StartsWith(b.Percorsi.Lezioni, voce.File);

        // La seconda volta c'è già: niente scaricamento.
        await b.Motore.ScaricaLezioneAsync(12);
        Assert.Single(b.Finto.Di("GET", "/api/lezioni/12/audio"));
    }

    [Theory]
    [InlineData("impronta")]
    [InlineData("dimensione")]
    public async Task Un_file_arrivato_diverso_da_quello_annunciato_si_scarta(string cosa)
    {
        var lezione = cosa == "impronta" ? Lezione(sha: new string('b', 64)) : Lezione(byteAudio: Mp3.Length + 1);
        using var b = new Banco(_ => (200, lezione));
        b.Finto.RispondeByte = r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? Mp3 : null;
        var r = await b.Motore.ScaricaLezioneAsync(12);
        Assert.NotEqual(200, r.Stato);
        Assert.Contains("rovinato", Json.Testo(Json.Nodo(r.Json)!["errore"]));
        Assert.Empty(b.Motore.Audio.Elenco());
        Assert.Empty(Directory.GetFiles(b.Percorsi.Lezioni));
    }

    [Fact]
    public async Task Se_il_server_rifa_l_audio_il_file_vecchio_si_toglie()
    {
        var altro = Mp3.Reverse().ToArray();
        var shaAltro = Convert.ToHexString(SHA256.HashData(altro)).ToLowerInvariant();
        string lezione = Lezione();
        byte[] dati = Mp3;
        using var b = new Banco(_ => (200, lezione));
        b.Finto.RispondeByte = r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? dati : null;
        await b.Motore.ScaricaLezioneAsync(12);
        lezione = Lezione(sha: shaAltro);
        dati = altro;
        await b.Motore.ScaricaLezioneAsync(12);
        var voce = Assert.Single(b.Motore.Audio.Elenco());
        Assert.Contains(shaAltro[..16], voce.Nome);
    }

    [Fact]
    public async Task Senza_rete_si_ascolta_la_lezione_gia_scaricata_e_si_spiega_quella_che_manca()
    {
        bool rete = true;
        using var b = new Banco(_ => rete ? (200, Lezione()) : (0, ""));
        b.Finto.RispondeByte = r => rete && r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? Mp3 : null;
        await b.Motore.ScaricaLezioneAsync(12);
        rete = false;
        var ok = await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(200, ok.Stato);
        var manca = await b.Motore.ScaricaLezioneAsync(13);
        Assert.Equal(503, manca.Stato);
        Assert.Equal("Senza rete puoi ascoltare solo le lezioni già scaricate sul computer.", Json.Testo(Json.Nodo(manca.Json)!["errore"]));
    }

    [Fact]
    public async Task Una_lezione_non_ancora_pronta_non_si_scarica()
    {
        using var b = new Banco(_ => (200, Lezione(stato: "in_preparazione")));
        var r = await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(409, r.Stato);
        Assert.Empty(b.Finto.Di("GET", "/api/lezioni/12/audio"));
    }

    [Fact]
    public async Task Due_richieste_insieme_aspettano_lo_stesso_scaricamento()
    {
        using var b = new Banco(_ => (200, Lezione()));
        b.Finto.RispondeByte = r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? Mp3 : null;
        b.Finto.Ritardo = TimeSpan.FromMilliseconds(150);
        var info = new InfoAudio { Byte = Mp3.Length, Sha256 = Sha };
        var a = b.Motore.Audio.ScaricaAsync(Banco.Server, Banco.Token, 12, info);
        var c = b.Motore.Audio.ScaricaAsync(Banco.Server, Banco.Token, 12, info);
        Assert.NotNull(b.Motore.Audio.Avanzamento(12));
        var esiti = await Task.WhenAll(a, c);
        Assert.All(esiti, e => Assert.True(e.Ok));
        Assert.Single(b.Finto.Di("GET", "/api/lezioni/12/audio"));
        Assert.Null(b.Motore.Audio.Avanzamento(12));
    }

    [Fact]
    public async Task Il_404_dell_audio_si_spiega_in_italiano()
    {
        using var b = new Banco(r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? (404, "{}") : (200, Lezione()));
        var r = await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(404, r.Stato);
        Assert.Contains("non c'è sul server", Json.Testo(Json.Nodo(r.Json)!["errore"]));
    }

    [Fact]
    public async Task Togli_dal_computer()
    {
        using var b = new Banco(_ => (200, Lezione()));
        b.Finto.RispondeByte = r => r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? Mp3 : null;
        await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(Mp3.Length, Json.Intero(b.Motore.Stato()["byte_scaricate"]));
        Assert.True(Json.Booleano(b.Motore.TogliLezione(12)["ok"]));
        Assert.Empty(b.Motore.Audio.Elenco());
    }

    [Theory]
    [InlineData("lezione-12-0123456789abcdef.mp3", 12L)]
    [InlineData("lezione-7-senza-impronta.mp3", 7L)]
    [InlineData("diagnosi.wav", null)]
    [InlineData("lezione-12-0123.mp3", null)]
    [InlineData("..\\lezione-12-0123456789abcdef.mp3", null)]
    public void Solo_i_file_delle_lezioni_contano(string nome, long? id)
    {
        Assert.Equal(id, CacheAudio.IdDa(nome));
    }

    [Fact]
    public void Nome_del_file_con_l_impronta()
    {
        Assert.Equal("lezione-12-" + Sha[..16] + ".mp3", CacheAudio.NomeFile(12, Sha.ToUpperInvariant()));
        Assert.Equal("lezione-12-senza-impronta.mp3", CacheAudio.NomeFile(12, "…"));
        Assert.Equal("https://audio.lectio.locale/lezione-12-senza-impronta.mp3", CacheAudio.Url("lezione-12-senza-impronta.mp3"));
    }
}
