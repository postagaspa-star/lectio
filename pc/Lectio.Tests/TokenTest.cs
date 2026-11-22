using Lectio.Motore;
using Lectio.Nucleo;
using Lectio.Sistema;

namespace Lectio.Tests;

/// <summary>Il token del server di casa: salvato solo cifrato con DPAPI dell'utente, in %LOCALAPPDATA%\Lectio.</summary>
public class TokenTest
{
    [Fact]
    public void Dpapi_cifra_e_decifra_e_rifiuta_i_dati_rovinati()
    {
        var protetto = Dpapi.Proteggi("segreto-di-prova");
        Assert.NotEqual("segreto-di-prova", protetto);
        Assert.DoesNotContain("segreto", protetto);
        Assert.Equal("segreto-di-prova", Dpapi.Svela(protetto));
        Assert.Null(Dpapi.Svela("non-base64!"));
        Assert.Null(Dpapi.Svela(Convert.ToBase64String(new byte[] { 1, 2, 3, 4 })));
        Assert.Null(Dpapi.Svela(null));
    }

    [Fact]
    public void Il_motore_legge_il_token_cifrato_e_lo_usa()
    {
        using var b = new Banco(_ => (200, "[]"));
        Assert.True(b.Motore.Abbinato);
        var testo = File.ReadAllText(b.Percorsi.Config);
        Assert.DoesNotContain(Banco.Token, testo);
        Assert.Contains("token_protetto", testo);
    }

    [Fact]
    public void Un_token_che_non_si_decifra_riporta_all_abbinamento_ma_tiene_tema_e_visita()
    {
        using var c = new CartellaTemporanea();
        var percorsi = new Percorsi(c.Percorso);
        Archivio.ScriviJson(percorsi.Config, new Configurazione
        {
            Server = "https://lectio.esempio.ts.net",
            TokenProtetto = Convert.ToBase64String(new byte[] { 9, 9, 9, 9, 9 }), // di un altro utente o computer
            TutorialVisto = true,
            Tema = "scuro",
        });
        using var m = new Motore.Motore(percorsi, new Rete.ClientLectio(new HandlerFinto(_ => (200, "{}"))));
        Assert.False(m.Abbinato);
        Assert.Equal("scuro", m.TemaScelto);
        Assert.True(Json.Booleano(m.Stato()["tutorial_visto"]));
    }

    [Fact]
    public void La_cartella_dei_dati_e_in_localappdata_lectio()
    {
        Assert.Equal(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Lectio"), Percorsi.Predefinita);
        var p = new Percorsi(Path.Combine(Path.GetTempPath(), "lectio-percorsi-" + Guid.NewGuid().ToString("N")));
        try
        {
            Assert.Equal(Path.Combine(p.Radice, "lezioni"), p.Lezioni);
            Assert.Equal(Path.Combine(p.Radice, "config.json"), p.Config);
        }
        finally
        {
            Directory.Delete(p.Radice, true);
        }
    }

    [Fact]
    public void Senza_cartella_dati_txt_i_dati_restano_in_localappdata()
    {
        using var c = new CartellaTemporanea();
        Assert.Equal(Percorsi.Predefinita, Percorsi.Scelta(c.Percorso));
        File.WriteAllText(c.File(Percorsi.FileCartellaDati), "\r\n   \r\n");
        Assert.Equal(Percorsi.Predefinita, Percorsi.Scelta(c.Percorso));
    }

    [Fact]
    public void Cartella_dati_txt_sceglie_una_cartella_relativa_o_assoluta()
    {
        using var c = new CartellaTemporanea();
        var programma = Path.Combine(c.Percorso, "Programma");
        Directory.CreateDirectory(programma);
        var file = Path.Combine(programma, Percorsi.FileCartellaDati);

        File.WriteAllText(file, "\r\n..\\Dati\r\naltro\r\n");
        Assert.Equal(Path.Combine(c.Percorso, "Dati"), Percorsi.Scelta(programma));

        var assoluta = Path.Combine(c.Percorso, "Altrove", "Lectio");
        File.WriteAllText(file, "\"" + assoluta + "\"");
        Assert.Equal(assoluta, Percorsi.Scelta(programma));
        Assert.True(Percorsi.Stessa(assoluta + "\\", assoluta.ToUpperInvariant()));
        Assert.False(Percorsi.Stessa(assoluta, Percorsi.Predefinita));
    }

    [Fact]
    public void Un_percorso_non_valido_in_cartella_dati_txt_lascia_la_cartella_di_sempre()
    {
        using var c = new CartellaTemporanea();
        File.WriteAllText(c.File(Percorsi.FileCartellaDati), "D:\\Dati\0rotti");
        Assert.Equal(Percorsi.Predefinita, Percorsi.Scelta(c.Percorso));
    }

    [Fact]
    public void Tema_e_visita_sono_di_questo_computer_e_restano()
    {
        using var b = new Banco(_ => (200, "{}"));
        Assert.Equal("sistema", Json.Testo(b.Motore.Stato()["tema"]));
        Assert.NotNull(b.Motore.ImpostaTema("viola")["errore"]);
        b.Motore.ImpostaTema("scuro");
        b.Motore.SegnaTutorialVisto();
        using var m2 = new Motore.Motore(b.Percorsi, new Rete.ClientLectio(new HandlerFinto(_ => (200, "{}"))));
        Assert.Equal("scuro", m2.TemaScelto);
        Assert.True(Json.Booleano(m2.Stato()["tutorial_visto"]));
        b.Motore.ImpostaTema("sistema");
        Assert.Null(b.Motore.TemaScelto);
    }
}
