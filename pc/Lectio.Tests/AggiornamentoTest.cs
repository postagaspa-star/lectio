using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using Lectio.Motore;
using Lectio.Rete;
using Lectio.Sistema;

namespace Lectio.Tests;

/// <summary>
/// L'aggiornamento che si installa da solo: lo zip firmato si scompatta solo dentro la sua cartella, solo se è più
/// nuovo; la copia nella cartella del programma mette da parte i file vecchi e torna com'era se qualcosa va storto.
/// </summary>
public class AggiornamentoTest
{
    private static readonly DateTimeOffset Adesso = new(2026, 10, 28, 20, 0, 0, TimeSpan.FromHours(1));

    /// <summary>Uno zip come quello di crea-pacchetto.ps1 (percorsi con la barra rovesciata, come Compress-Archive 5.1).</summary>
    private static byte[] Zip(params (string Nome, string Testo)[] voci)
    {
        using var m = new MemoryStream();
        using (var z = new ZipArchive(m, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var (nome, testo) in voci)
            {
                using var s = z.CreateEntry(nome).Open();
                s.Write(Encoding.UTF8.GetBytes(testo));
            }
        }
        return m.ToArray();
    }

    private static byte[] PacchettoBuono(int codice = 3, string nome = "1.0.2") => Zip(
        (@"Lectio\Lectio.exe", "exe nuovo"),
        (@"Lectio\ui\index.html", "<html>nuova</html>"),
        (@"Lectio\versione.json", "{\"codice\": " + codice + ", \"nome\": \"" + nome + "\"}"));

    [Fact]
    public async Task La_versione_nuova_firmata_si_scarica_si_scompatta_e_resta_pronta()
    {
        var zip = PacchettoBuono();
        var sha = Convert.ToHexString(SHA256.HashData(zip));
        using var chiave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var firma = Encoding.ASCII.GetBytes(Convert.ToBase64String(chiave.SignData(zip, HashAlgorithmName.SHA256)));
        var finto = new HandlerFinto(_ => (200, "{\"pc\":{\"codice\":3,\"nome\":\"1.0.2\",\"sha256\":\"" + sha + "\",\"byte\":" + zip.Length + "}}"));
        finto.RispondeByte = r => r.Percorso == "/scarica/lectio-pc.zip" ? zip : r.Percorso == "/scarica/lectio-pc.zip.sig" ? firma : null;
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, 2, Convert.ToBase64String(chiave.ExportSubjectPublicKeyInfo()));
        await a.ControllaAsync("https://lectio.esempio.ts.net");
        using var c = new CartellaTemporanea();

        var esito = await a.PreparaAsync(c.Percorso, Adesso);

        Assert.True(esito.Ok, esito.Errore);
        Assert.Equal("1.0.2", esito.Pronto!.Nome);
        Assert.Equal("exe nuovo", File.ReadAllText(Path.Combine(c.Percorso, "nuova", "Lectio", "Lectio.exe")));
        Assert.Equal("<html>nuova</html>", File.ReadAllText(Path.Combine(c.Percorso, "nuova", "Lectio", "ui", "index.html")));
        Assert.Empty(Directory.GetFiles(c.Percorso, "*.zip*")); // lo zip non resta
        var pronta = Aggiornatore.Pronta(c.Percorso, 2, Adesso);
        Assert.NotNull(pronta);
        Assert.Equal(3, pronta!.Codice);
        // Già pronta: un secondo giro non riscarica niente.
        var prima = finto.Di("GET", "/scarica/").Count();
        Assert.True((await a.PreparaAsync(c.Percorso, Adesso)).Ok);
        Assert.Equal(prima, finto.Di("GET", "/scarica/").Count());
    }

    [Fact]
    public async Task Una_versione_nuova_con_la_firma_sbagliata_non_arriva_mai_a_scompattarsi()
    {
        var zip = PacchettoBuono();
        var sha = Convert.ToHexString(SHA256.HashData(zip));
        using var chiave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var altra = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var firma = Encoding.ASCII.GetBytes(Convert.ToBase64String(altra.SignData(zip, HashAlgorithmName.SHA256)));
        var finto = new HandlerFinto(_ => (200, "{\"pc\":{\"codice\":3,\"nome\":\"1.0.2\",\"sha256\":\"" + sha + "\",\"byte\":" + zip.Length + "}}"));
        finto.RispondeByte = r => r.Percorso == "/scarica/lectio-pc.zip" ? zip : r.Percorso == "/scarica/lectio-pc.zip.sig" ? firma : null;
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, 2, Convert.ToBase64String(chiave.ExportSubjectPublicKeyInfo()));
        await a.ControllaAsync("https://lectio.esempio.ts.net");
        using var c = new CartellaTemporanea();

        var esito = await a.PreparaAsync(c.Percorso, Adesso);

        Assert.False(esito.Ok);
        Assert.Contains("firma", esito.Errore);
        Assert.False(Directory.Exists(Path.Combine(c.Percorso, "nuova")));
        Assert.Null(Aggiornatore.Pronta(c.Percorso, 2, Adesso));
    }

    [Theory]
    [InlineData(@"Lectio\..\..\fuori.txt")]
    [InlineData(@"altro\Lectio.exe")]
    public void Un_file_fuori_dalla_cartella_Lectio_scarta_tutto_il_pacchetto(string nome)
    {
        using var c = new CartellaTemporanea();
        var zip = c.File("p.zip");
        File.WriteAllBytes(zip, Zip((@"Lectio\Lectio.exe", "x"), (@"Lectio\versione.json", "{\"codice\":3,\"nome\":\"1.0.2\"}"), (nome, "cattivo")));
        var dati = Path.Combine(c.Percorso, "dati");
        Assert.Throws<PacchettoNonValido>(() => Aggiornatore.Estrai(zip, dati, 2));
        Assert.False(File.Exists(Path.Combine(c.Percorso, "fuori.txt")));
        Assert.False(Directory.Exists(Path.Combine(dati, "nuova")));
        Assert.Null(Aggiornatore.Pronta(dati, 2, Adesso));
    }

    [Theory]
    [InlineData("{\"codice\":2,\"nome\":\"1.0.1\"}")] // uguale a questa
    [InlineData("{\"codice\":1,\"nome\":\"1.0.0\"}")] // più vecchia: niente ritorni indietro
    [InlineData(null)]                                 // senza versione.json (come lo zip della 1.0.0)
    public void Un_pacchetto_non_piu_nuovo_di_questo_si_scarta(string? versione)
    {
        using var c = new CartellaTemporanea();
        var zip = c.File("p.zip");
        var voci = new List<(string, string)> { (@"Lectio\Lectio.exe", "x") };
        if (versione != null) voci.Add((@"Lectio\versione.json", versione));
        File.WriteAllBytes(zip, Zip(voci.ToArray()));
        Assert.Throws<PacchettoNonValido>(() => Aggiornatore.Estrai(zip, c.Percorso, 2));
        Assert.Null(Aggiornatore.Pronta(c.Percorso, 2, Adesso));
    }

    [Fact]
    public void Dopo_un_installazione_non_riuscita_la_stessa_versione_aspetta_un_giorno()
    {
        using var c = new CartellaTemporanea();
        var zip = c.File("p.zip");
        File.WriteAllBytes(zip, PacchettoBuono());
        Aggiornatore.Estrai(zip, c.Percorso, 2);
        Aggiornatore.SegnaFallita(c.Percorso, 3, Adesso);
        Assert.Null(Aggiornatore.Pronta(c.Percorso, 2, Adesso.AddHours(1)));
        Assert.NotNull(Aggiornatore.Pronta(c.Percorso, 2, Adesso.AddHours(1), ancheSeFallita: true)); // non si riscarica
        Assert.NotNull(Aggiornatore.Pronta(c.Percorso, 2, Adesso.AddHours(25)));
        // Una volta installata (il programma ora è la 3) non è più "pronta".
        Assert.Null(Aggiornatore.Pronta(c.Percorso, 3, Adesso.AddHours(25)));
    }

    [Fact]
    public void La_pulizia_toglie_la_versione_gia_installata_e_tiene_quella_ancora_da_installare()
    {
        using var c = new CartellaTemporanea();
        var zip = c.File("p.zip");
        File.WriteAllBytes(zip, PacchettoBuono());
        Aggiornatore.Estrai(zip, c.Percorso, 2);
        Directory.CreateDirectory(c.File("vecchia"));
        File.WriteAllText(c.File("lectio-pc-1.0.2.zip.scaricamento"), "a metà");

        Aggiornatore.Pulisci(c.Percorso, 2); // la 3 è ancora da installare
        Assert.NotNull(Aggiornatore.Pronta(c.Percorso, 2, Adesso));
        Assert.False(Directory.Exists(c.File("vecchia")));
        Assert.False(File.Exists(c.File("lectio-pc-1.0.2.zip.scaricamento")));

        Aggiornatore.Pulisci(c.Percorso, 3); // installata
        Assert.False(Directory.Exists(c.File("nuova")));
        Assert.False(File.Exists(c.File("pronta.json")));
    }

    [Fact]
    public void Il_segno_dell_installazione_riuscita_si_legge_una_volta_sola()
    {
        using var c = new CartellaTemporanea();
        Nucleo.Archivio.ScriviJson(c.File("fatto.json"), new VersioneNelPacchetto(3, "1.0.2"));
        Assert.Equal("1.0.2", Aggiornatore.LeggiFatto(c.Percorso));
        Assert.Null(Aggiornatore.LeggiFatto(c.Percorso));
    }

    [Fact]
    public void La_copia_sostituisce_i_file_e_mette_da_parte_i_vecchi()
    {
        using var c = new CartellaTemporanea();
        var nuova = Directory.CreateDirectory(c.File("nuova")).FullName;
        var programma = Directory.CreateDirectory(c.File("programma")).FullName;
        File.WriteAllText(Path.Combine(nuova, "Lectio.exe"), "nuovo");
        Directory.CreateDirectory(Path.Combine(nuova, "ui"));
        File.WriteAllText(Path.Combine(nuova, "ui", "index.html"), "pagina nuova");
        File.WriteAllText(Path.Combine(programma, "Lectio.exe"), "vecchio");
        File.WriteAllText(Path.Combine(programma, "solo-vecchio.dll"), "resta");

        Assert.True(Sostituzione.Copia(nuova, programma, c.File("vecchia")));

        Assert.Equal("nuovo", File.ReadAllText(Path.Combine(programma, "Lectio.exe")));
        Assert.Equal("pagina nuova", File.ReadAllText(Path.Combine(programma, "ui", "index.html")));
        Assert.Equal("resta", File.ReadAllText(Path.Combine(programma, "solo-vecchio.dll")));
        Assert.Equal("vecchio", File.ReadAllText(Path.Combine(c.File("vecchia"), "Lectio.exe")));
    }

    [Fact]
    public void Se_un_file_non_si_puo_sostituire_si_torna_com_era()
    {
        using var c = new CartellaTemporanea();
        var nuova = Directory.CreateDirectory(c.File("nuova")).FullName;
        var programma = Directory.CreateDirectory(c.File("programma")).FullName;
        File.WriteAllText(Path.Combine(nuova, "Lectio.exe"), "nuovo");
        Directory.CreateDirectory(Path.Combine(nuova, "ui"));
        File.WriteAllText(Path.Combine(nuova, "ui", "index.html"), "pagina nuova");
        File.WriteAllText(Path.Combine(nuova, "ui", "solo-nuovo.js"), "nuovo");
        File.WriteAllText(Path.Combine(programma, "Lectio.exe"), "vecchio");
        Directory.CreateDirectory(Path.Combine(programma, "ui"));
        File.WriteAllText(Path.Combine(programma, "ui", "index.html"), "pagina vecchia");

        // Un file della pagina tenuto aperto da un altro programma: non si può spostare.
        using (new FileStream(Path.Combine(programma, "ui", "index.html"), FileMode.Open, FileAccess.Read, FileShare.None))
        {
            Assert.False(Sostituzione.Copia(nuova, programma, c.File("vecchia")));
        }

        Assert.Equal("vecchio", File.ReadAllText(Path.Combine(programma, "Lectio.exe")));
        Assert.Equal("pagina vecchia", File.ReadAllText(Path.Combine(programma, "ui", "index.html")));
        Assert.False(File.Exists(Path.Combine(programma, "ui", "solo-nuovo.js")));
    }

    [Fact]
    public void L_installazione_parte_solo_dalla_cartella_dell_aggiornamento_verso_un_Lectio_vero()
    {
        // I test girano da bin\: non è la cartella "aggiornamento\nuova" dei dati, quindi niente copia.
        using var c = new CartellaTemporanea();
        var programma = Directory.CreateDirectory(c.File("programma")).FullName;
        File.WriteAllText(Path.Combine(programma, "Lectio.exe"), "vecchio");
        var o = Opzioni.Da(new[] { Sostituzione.Argomento, "--programma", programma, "--attendi", "999999", "--dati", c.File("dati"), "--riapri" });
        Assert.True(o.ApplicaAggiornamento);
        Assert.Equal(programma, o.Programma);
        Assert.Equal(999999, o.Attendi);
        Assert.True(o.Riapri);
        Assert.Equal(2, Sostituzione.Esegui(o));
        Assert.Equal("vecchio", File.ReadAllText(Path.Combine(programma, "Lectio.exe")));
    }

    [Fact]
    public void Il_giro_non_dorme_oltre_il_controllo_delle_versioni()
    {
        // Prima (1.0.0) il giro dormiva mezz'ora e il controllo dei 20 secondi dopo l'avvio non partiva quasi mai.
        Assert.Equal(TimeSpan.FromSeconds(19), Motore.Motore.AttesaGiro(false, true, 1_000, 20_000));
        Assert.Equal(TimeSpan.FromMinutes(30), Motore.Motore.AttesaGiro(false, true, 0, (long)TimeSpan.FromHours(12).TotalMilliseconds));
        Assert.Equal(TimeSpan.FromSeconds(20), Motore.Motore.AttesaGiro(true, true, 0, 60_000));
        // Mai un giro a vuoto senza pausa, e senza aggiornamenti (o senza abbinamento) conta solo il §11.
        Assert.Equal(TimeSpan.FromSeconds(1), Motore.Motore.AttesaGiro(false, true, 50_000, 20_000));
        Assert.Equal(TimeSpan.FromMinutes(30), Motore.Motore.AttesaGiro(false, false, 1_000, 20_000));
    }

    [Fact]
    public void Lo_script_del_pacchetto_mette_versione_json_nello_zip()
    {
        var script = File.ReadAllText(Path.Combine(Sorgenti.CartellaPc(), "prove", "crea-pacchetto.ps1"));
        Assert.Contains("versione.json", script);
        Assert.Contains("Versione.cs", script);
    }
}
