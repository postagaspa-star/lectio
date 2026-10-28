using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Lectio.Motore;
using Lectio.Nucleo;
using Lectio.Rete;

namespace Lectio.Tests;

/// <summary>
/// Il pacchetto: a cartella (mai single-file), self-contained come Sol, con l'interfaccia, il carattere Figtree e la
/// sua licenza OFL, l'icona; l'interfaccia non chiama internet; lo script che fa lo zip. E l'aggiornamento controllato.
/// </summary>
public class PacchettoTest
{
    private static string Pc(params string[] parti) => Path.Combine(new[] { Sorgenti.CartellaPc() }.Concat(parti).ToArray());

    [Fact]
    public void Pubblicazione_a_cartella_self_contained_senza_single_file_ne_trimming()
    {
        var x = XDocument.Load(Pc("Lectio", "Pubblicazione.props"));
        string Valore(string nome) => x.Descendants(nome).Single().Value;
        Assert.Equal("true", Valore("SelfContained"));
        Assert.Equal("false", Valore("PublishSingleFile"));
        Assert.Equal("false", Valore("PublishTrimmed"));
        Assert.Equal("win-x64", Valore("RuntimeIdentifier"));
        var progetto = File.ReadAllText(Pc("Lectio", "Lectio.csproj"));
        Assert.Contains("<Version>1.0.0</Version>", progetto);
        Assert.Contains("<AssemblyName>Lectio</AssemblyName>", progetto);
        Assert.DoesNotContain("PublishSingleFile>true", progetto);
        Assert.Equal("1.0.0", Versione.Nome);
        Assert.Equal(1, Versione.Codice);
    }

    [Fact]
    public void Lo_script_del_pacchetto_fa_lectio_pc_zip_a_cartella_e_si_legge_in_powershell_5()
    {
        var file = Pc("prove", "crea-pacchetto.ps1");
        var byteFile = File.ReadAllBytes(file);
        Assert.True(byteFile.Length > 3 && byteFile[0] == 0xEF && byteFile[1] == 0xBB && byteFile[2] == 0xBF, "UTF-8 con BOM: PowerShell 5.1 legge i .ps1 senza BOM come ANSI");
        var testo = Encoding.UTF8.GetString(byteFile);
        Assert.Contains("lectio-pc.zip", testo);
        Assert.Contains("PubblicazioneLectio=true", testo);
        Assert.Contains("coreclr.dll", testo);
        Assert.Contains("OFL.txt", testo);
    }

    [Fact]
    public void L_interfaccia_c_e_tutta_col_carattere_e_la_licenza()
    {
        var ui = Pc("ui");
        var index = File.ReadAllText(Path.Combine(ui, "index.html"));
        foreach (Match m in Regex.Matches(index, "<script src=\"([^\"]+)\""))
        {
            Assert.True(File.Exists(Path.Combine(ui, m.Groups[1].Value)), "manca " + m.Groups[1].Value);
        }
        Assert.True(new FileInfo(Path.Combine(ui, "font", "Figtree.ttf")).Length > 10_000);
        Assert.Contains("SIL Open Font License", File.ReadAllText(Path.Combine(ui, "font", "OFL.txt")));
        Assert.Contains("font/Figtree.ttf", File.ReadAllText(Path.Combine(ui, "stile.css")));
    }

    [Fact]
    public void L_interfaccia_non_chiama_internet()
    {
        var ui = Pc("ui");
        var index = File.ReadAllText(Path.Combine(ui, "index.html"));
        Assert.Contains("default-src 'none'", index);
        Assert.Contains("connect-src 'self'", index);
        Assert.Contains("media-src https://audio.lectio.locale", index);
        // Nei file della pagina gli unici indirizzi sono quelli del programma e quello finto degli esempi: nessun indirizzo vero.
        var ammessi = new[] { "https://audio.lectio.locale", "https://lectio.locale", "https://lectio.esempio.ts.net", "http://192.168.0.20", "http://192.168.x.x" };
        foreach (var f in Directory.GetFiles(ui, "*.*", SearchOption.AllDirectories).Where(f => f.EndsWith(".js") || f.EndsWith(".html") || f.EndsWith(".css")))
        {
            foreach (Match m in Regex.Matches(File.ReadAllText(f), @"https?://[A-Za-z0-9.\-]+"))
            {
                if (m.Value.StartsWith("http://www.w3.org", StringComparison.Ordinal)) continue; // spazio dei nomi SVG, non una chiamata
                Assert.True(ammessi.Contains(m.Value), Path.GetFileName(f) + ": " + m.Value);
            }
        }
    }

    [Fact]
    public void Nessun_indirizzo_della_rete_di_Andrea_nel_codice()
    {
        // Il repository è pubblico: niente nome della sua rete Tailscale né indirizzo del NAS (i pezzi si uniscono qui
        // apposta, così questo file non trova sé stesso).
        var vietati = new[] { "tail" + "dbae63", "192.168." + "1.51" };
        var radice = Sorgenti.CartellaPc();
        var file = Directory.EnumerateFiles(radice, "*.*", SearchOption.AllDirectories)
            .Where(f => !Regex.IsMatch(f, @"[\\/](dist|bin|obj|node_modules)[\\/]"))
            .Where(f => new[] { ".cs", ".js", ".html", ".css", ".ps1", ".csproj", ".props", ".md", ".txt", ".json" }.Contains(Path.GetExtension(f).ToLowerInvariant()));
        foreach (var f in file)
        {
            var testo = File.ReadAllText(f);
            foreach (var v in vietati) Assert.False(testo.Contains(v, StringComparison.OrdinalIgnoreCase), Path.GetRelativePath(radice, f) + " contiene un indirizzo vero");
        }
    }

    [Fact]
    public void Niente_emoji_nell_interfaccia()
    {
        foreach (var f in Directory.GetFiles(Pc("ui"), "*.*", SearchOption.TopDirectoryOnly))
        {
            var testo = File.ReadAllText(f);
            foreach (var r in testo.EnumerateRunes())
            {
                var v = r.Value;
                Assert.False(v is >= 0x1F300 and <= 0x1FAFF or >= 0x2600 and <= 0x27BF, Path.GetFileName(f) + ": emoji U+" + v.ToString("X"));
            }
        }
    }

    [Fact]
    public void L_icona_e_un_ico_con_le_dimensioni_fino_a_256()
    {
        var byteIco = File.ReadAllBytes(Pc("Lectio", "Risorse", "lectio.ico"));
        Assert.Equal(0, BitConverter.ToUInt16(byteIco, 0));
        Assert.Equal(1, BitConverter.ToUInt16(byteIco, 2));
        int n = BitConverter.ToUInt16(byteIco, 4);
        Assert.True(n >= 5);
        var lati = Enumerable.Range(0, n).Select(i => byteIco[6 + 16 * i] == 0 ? 256 : byteIco[6 + 16 * i]).ToList();
        Assert.Contains(16, lati);
        Assert.Contains(32, lati);
        Assert.Contains(256, lati);
        using var icona = Interfaccia.IconaVassoio.Base();
        Assert.True(icona.Width > 0);
    }

    [Fact]
    public void Il_programma_non_si_avvia_da_solo_e_non_si_copia()
    {
        foreach (var f in Directory.GetFiles(Pc("Lectio"), "*.cs", SearchOption.AllDirectories))
        {
            var testo = File.ReadAllText(f);
            Assert.DoesNotContain(@"CurrentVersion\Run", testo);
            Assert.DoesNotContain("File.Copy(Environment.ProcessPath", testo);
        }
    }

    [Fact]
    public void Diagnosi_un_secondo_di_silenzio_in_wav()
    {
        var wav = Diagnosi.Silenzio(1);
        Assert.Equal("RIFF", Encoding.ASCII.GetString(wav, 0, 4));
        Assert.Equal("WAVE", Encoding.ASCII.GetString(wav, 8, 4));
        Assert.Equal(44 + 16000, wav.Length);
    }

    // --- Aggiornamento: solo uno zip annunciato e controllato ------------------------------------------

    [Fact]
    public async Task Aggiornamento_annunciato_una_volta_e_scaricato_solo_se_l_impronta_coincide()
    {
        var zip = Encoding.UTF8.GetBytes("zip finto");
        var sha = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(zip));
        using var chiave = System.Security.Cryptography.ECDsa.Create(System.Security.Cryptography.ECCurve.NamedCurves.nistP256);
        var firma = Encoding.ASCII.GetBytes(Convert.ToBase64String(chiave.SignData(zip, System.Security.Cryptography.HashAlgorithmName.SHA256)));
        var finto = new HandlerFinto(_ => (200, "{\"server\":\"1.0.0\",\"pc\":{\"codice\":2,\"nome\":\"1.0.1\",\"sha256\":\"" + sha + "\",\"byte\":" + zip.Length + "}}"));
        finto.RispondeByte = r => r.Percorso == "/scarica/lectio-pc.zip" ? zip : r.Percorso == "/scarica/lectio-pc.zip.sig" ? firma : null;
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, Versione.Codice, Convert.ToBase64String(chiave.ExportSubjectPublicKeyInfo()));
        var nuova = await a.ControllaAsync("https://lectio.esempio.ts.net");
        Assert.NotNull(nuova);
        Assert.Equal("https://lectio.esempio.ts.net/scarica/lectio-pc.zip", nuova!.IndirizzoZip);
        Assert.Null(await a.ControllaAsync("https://lectio.esempio.ts.net")); // la notifica una volta sola
        using var c = new CartellaTemporanea();
        var esito = await a.ScaricaAsync(c.Percorso);
        Assert.True(esito.Ok, esito.Errore);
        Assert.Equal(zip, File.ReadAllBytes(esito.File!));
        Assert.Equal("lectio-pc-1.0.1.zip", Path.GetFileName(esito.File));
    }

    [Fact]
    public async Task Aggiornamento_con_impronta_sbagliata_si_scarta()
    {
        var finto = new HandlerFinto(_ => (200, "{\"pc\":{\"codice\":2,\"nome\":\"1.0.1\",\"sha256\":\"" + new string('a', 64) + "\"}}"));
        finto.RispondeByte = r => r.Percorso == "/scarica/lectio-pc.zip" ? new byte[] { 1, 2, 3 } : null;
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, Versione.Codice);
        await a.ControllaAsync("https://lectio.esempio.ts.net");
        using var c = new CartellaTemporanea();
        var esito = await a.ScaricaAsync(c.Percorso);
        Assert.False(esito.Ok);
        Assert.Contains("impronta", esito.Errore);
        Assert.Empty(Directory.GetFiles(c.Percorso));
    }

    [Fact]
    public void Stessa_versione_o_piu_vecchia_non_e_un_aggiornamento()
    {
        Assert.False(Aggiornatore.ÈPiùNuova(Json.Leggi<InfoVersione>("{\"pc\":{\"codice\":1}}"), 1));
        Assert.True(Aggiornatore.ÈPiùNuova(Json.Leggi<InfoVersione>("{\"pc\":{\"codice\":2}}"), 1));
        Assert.False(Aggiornatore.ÈPiùNuova(null, 1));
    }
}
