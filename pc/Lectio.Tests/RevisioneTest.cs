using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Lectio.Interfaccia;
using Lectio.Motore;
using Lectio.Nucleo;
using Lectio.Rete;

namespace Lectio.Tests;

/// <summary>I difetti trovati dalla revisione del programma per PC (1.0.0): ogni test falliva col programma di prima.</summary>
public class RevisioneTest
{
    private static readonly string IdCasa = new('a', 32);
    private const string Casa = "http://192.168.0.20:8500";
    private const string Fuori = "https://lectio-fuori.esempio.ts.net";

    private static string Pc(params string[] parti) => Path.Combine(new[] { Sorgenti.CartellaPc() }.Concat(parti).ToArray());

    // --- 3. La finestra: niente F5/Ricarica, niente menu del tasto destro ---------------------------------

    [Fact]
    public void Finestra_senza_ricarica_e_senza_menu_del_tasto_destro()
    {
        var sorgente = File.ReadAllText(Pc("Lectio", "Interfaccia", "FinestraLectio.cs"));
        Assert.Contains("AreBrowserAcceleratorKeysEnabled = false", sorgente);
        Assert.Contains("AreDefaultContextMenusEnabled = false", sorgente);
        Assert.DoesNotContain("AreDefaultContextMenusEnabled = true", sorgente);
    }

    // --- 6. Correzione lenta: esito sconosciuto, non senza rete ---------------------------------------------

    [Fact]
    public void La_correzione_di_una_risposta_aspetta_fino_a_200_secondi()
    {
        Assert.Equal(TimeSpan.FromSeconds(200), Motore.Motore.TempoPer("POST", "api/lezioni/12/domande/32/risposta"));
        Assert.Equal(ClientLectio.TempoMassimo, Motore.Motore.TempoPer("GET", "api/lezioni/12"));
        Assert.Equal(ClientLectio.TempoMassimo, Motore.Motore.TempoPer("POST", "api/lezioni"));
    }

    [Fact]
    public async Task Tempo_scaduto_su_una_modifica_e_esito_sconosciuto_su_una_lettura_vale_la_copia()
    {
        using var b = new Banco(_ => (200, "{\"id\":88,\"esito\":\"giusta\"}"));
        await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        b.Finto.Ritardo = TimeSpan.FromMilliseconds(500);
        b.Motore.TempoForzato = TimeSpan.FromMilliseconds(100);
        var r = await b.Motore.InoltraAsync("POST", "api/lezioni/12/domande/32/risposta", "", "{\"testo\":\"Seiano\",\"saltata\":false}");
        Assert.Equal(504, r.Stato);
        Assert.Equal("esito_sconosciuto", Json.Testo(Json.Nodo(r.Json)!["codice"]));
        var lettura = await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        Assert.Equal(200, lettura.Stato);
        Assert.NotNull(lettura.DaMemoriaDel);
    }

    // --- 7. Server giù con Tailscale su (502/503/504 senza la frase di Lectio) = senza rete -----------------

    [Fact]
    public async Task Un_502_di_Tailscale_vale_come_senza_rete_per_le_copie_e_le_modifiche()
    {
        var giu = false;
        using var b = new Banco(_ => giu ? (502, "<html>Bad Gateway</html>") : (200, "[{\"id\":1,\"nome\":\"Storia\"}]"));
        await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        giu = true;
        var r = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Equal(200, r.Stato);
        Assert.NotNull(r.DaMemoriaDel);
        var m = await b.Motore.InoltraAsync("POST", "api/materie", "", "{\"nome\":\"Italiano\"}");
        Assert.Equal(503, m.Stato);
        Assert.Equal("rete", Json.Testo(Json.Nodo(m.Json)!["codice"]));
        Assert.False(b.Motore.ServerRaggiungibile);
    }

    [Fact]
    public async Task Un_503_con_la_frase_di_Lectio_resta_un_errore_vero()
    {
        using var b = new Banco(_ => (503, "{\"errore\":\"La voce non risponde adesso: riprova tra poco.\",\"codice\":\"fornitore_non_disponibile\"}"));
        var r = await b.Motore.InoltraAsync("POST", "api/lezioni", "", "{}");
        Assert.Equal(503, r.Stato);
        Assert.Equal("fornitore_non_disponibile", Json.Testo(Json.Nodo(r.Json)!["codice"]));
        Assert.True(b.Motore.ServerRaggiungibile);
    }

    [Fact]
    public async Task Una_lezione_scaricata_si_apre_col_server_giu_e_Tailscale_su()
    {
        var mp3 = Enumerable.Range(0, 3000).Select(i => (byte)i).ToArray();
        var sha = Convert.ToHexString(SHA256.HashData(mp3)).ToLowerInvariant();
        var giu = false;
        using var b = new Banco(_ => giu ? (503, "Service Unavailable") : (200, "{\"id\":12,\"stato\":\"pronta\",\"audio\":{\"byte\":3000,\"sha256\":\"" + sha + "\"}}"));
        b.Finto.RispondeByte = r => !giu && r.Percorso.EndsWith("/audio", StringComparison.Ordinal) ? mp3 : null;
        Assert.Equal(200, (await b.Motore.ScaricaLezioneAsync(12)).Stato);
        giu = true;
        var r = await b.Motore.ScaricaLezioneAsync(12);
        Assert.Equal(200, r.Stato);
        Assert.Contains("audio.lectio.locale", r.Json);
    }

    // --- 11. Vicino all'orologio solo finché serve -----------------------------------------------------------

    [Theory]
    [InlineData(false, false, 0, false, true)]
    [InlineData(true, false, 0, false, false)]
    [InlineData(false, true, 0, false, false)]
    [InlineData(false, false, 1, false, false)]
    [InlineData(false, false, 0, true, false)]
    public void Lectio_esce_con_finestra_chiusa_preparazione_finita_e_avvisi_mostrati(bool finestra, bool preparazione, int inCoda, bool sulloSchermo, bool esce)
    {
        Assert.Equal(esce, ContestoLectio.DeveUscire(finestra, preparazione, inCoda, sulloSchermo));
    }

    // --- 12. Niente avvisi vecchi all'avvio --------------------------------------------------------------------

    [Fact]
    public async Task All_avvio_con_cursore_zero_si_prende_solo_il_cursore()
    {
        var voci = "[{\"id\":5,\"tipo\":\"lezione_pronta\",\"testo\":\"La lezione di Storia è pronta\",\"lezione_id\":12}]";
        using var b = new Banco(r => r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal) ? (200, voci) : (200, "[]"), cursore: 0);
        var avvisi = new List<Notifica>();
        b.Motore.NuovaNotifica += avvisi.Add;
        await b.Motore.GiroAsync();
        Assert.Empty(avvisi);
        Assert.Contains("\"cursore_novita\": 5", File.ReadAllText(b.Percorsi.Config));
        // Dopo il primo giro le novità nuove avvisano.
        voci = "[{\"id\":6,\"tipo\":\"lezione_pronta\",\"testo\":\"La lezione di Diritto è pronta\",\"lezione_id\":13}]";
        await b.Motore.GiroAsync();
        Assert.Single(avvisi);
    }

    [Fact]
    public async Task Le_novita_piu_vecchie_di_un_ora_non_diventano_avvisi()
    {
        using var b = new Banco(r => r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal) ? (200, "[]") : (200, "[]"), cursore: 50);
        var adesso = b.Adesso;
        b.Finto.Risponde = r => r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal)
            ? (200, "[{\"id\":51,\"tipo\":\"lezione_pronta\",\"testo\":\"Vecchia\",\"lezione_id\":1,\"quando\":\"" + adesso.AddHours(-2).ToString("o") + "\"},"
                + "{\"id\":52,\"tipo\":\"lezione_pronta\",\"testo\":\"Nuova\",\"lezione_id\":2,\"quando\":\"" + adesso.AddMinutes(-10).ToString("o") + "\"}]")
            : (200, "[]");
        var avvisi = new List<Notifica>();
        b.Motore.NuovaNotifica += avvisi.Add;
        await b.Motore.GiroAsync();
        Assert.Equal("Nuova", Assert.Single(avvisi).Titolo);
    }

    // --- 13. Scollega con una lezione ancora aperta ------------------------------------------------------------

    [Fact]
    public async Task Scollega_dice_se_una_lezione_in_uso_non_si_e_tolta()
    {
        using var b = new Banco(_ => (200, "{}"));
        Directory.CreateDirectory(b.Percorsi.Lezioni);
        var file = Path.Combine(b.Percorsi.Lezioni, CacheAudio.NomeFile(12, null));
        File.WriteAllBytes(file, new byte[] { 1, 2, 3 });
        Dictionary<string, object?> esito;
        using (new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.None))
        {
            var r = await b.Motore.ScollegaAsync();
            esito = r.ToDictionary(k => k.Key, k => (object?)k.Value?.ToJsonString());
        }
        Assert.Equal("1", esito["lezioni_rimaste"]);
        Assert.Contains("in uso", (string)esito["avviso"]!);
    }

    // --- 15. Cache audio: lezioni eliminate, tetto, pezzi interrotti, programma che si chiude ---------------------

    [Fact]
    public async Task Il_giro_toglie_l_audio_delle_lezioni_che_il_server_non_ha_piu()
    {
        using var b = new Banco(r => r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal) ? (200, "[]") : (200, "[{\"id\":12,\"stato\":\"pronta\"}]"));
        Directory.CreateDirectory(b.Percorsi.Lezioni);
        File.WriteAllBytes(Path.Combine(b.Percorsi.Lezioni, CacheAudio.NomeFile(12, null)), new byte[] { 1 });
        File.WriteAllBytes(Path.Combine(b.Percorsi.Lezioni, CacheAudio.NomeFile(13, null)), new byte[] { 1 });
        await b.Motore.GiroAsync();
        Assert.Equal(new[] { 12L }, b.Motore.Audio.Elenco().Select(v => v.Id).ToArray());
    }

    [Fact]
    public void Oltre_il_tetto_si_tolgono_le_lezioni_usate_meno_di_recente_mai_quella_appena_chiesta()
    {
        using var c = new CartellaTemporanea();
        using var client = new ClientLectio(new HandlerFinto(_ => (200, "{}")));
        var cache = new CacheAudio(c.Percorso, client) { TettoByte = 2000 };
        var adesso = DateTime.UtcNow;
        foreach (var (id, giorni) in new[] { (1L, -3), (2L, -2), (3L, -1) })
        {
            var f = Path.Combine(c.Percorso, CacheAudio.NomeFile(id, null));
            File.WriteAllBytes(f, new byte[1000]);
            File.SetLastWriteTimeUtc(f, adesso.AddDays(giorni));
        }
        cache.Pota(daTenere: 1);
        Assert.Equal(new[] { 1L, 3L }, cache.Elenco().Select(v => v.Id).ToArray());
        Assert.Equal(CacheAudio.TettoPredefinito, new CacheAudio(c.Percorso, client).TettoByte);
        Assert.Equal(1024L * 1024 * 1024, CacheAudio.TettoPredefinito);
    }

    [Fact]
    public void I_pezzi_di_scaricamenti_interrotti_si_tolgono_all_avvio()
    {
        using var c = new CartellaTemporanea();
        var percorsi = new Percorsi(c.Percorso);
        Directory.CreateDirectory(percorsi.Lezioni);
        File.WriteAllBytes(Path.Combine(percorsi.Lezioni, "lezione-12-0123456789abcdef.mp3.scaricamento.part"), new byte[] { 1 });
        File.WriteAllBytes(Path.Combine(percorsi.Lezioni, "lezione-13-0123456789abcdef.mp3.scaricamento"), new byte[] { 1 });
        using var m = new Motore.Motore(percorsi, new ClientLectio(new HandlerFinto(_ => (200, "{}"))));
        Assert.Empty(Directory.GetFiles(percorsi.Lezioni));
    }

    [Fact]
    public async Task Un_client_gia_chiuso_non_lancia_eccezioni()
    {
        var client = new ClientLectio(new HandlerFinto(_ => (200, "{}")));
        client.Dispose();
        var r = await client.InviaAsync("GET", "https://lectio.esempio.ts.net", "api/oggi", null, null);
        Assert.True(r.Rete);
        using var c = new CartellaTemporanea();
        var d = await client.ScaricaAsync("https://lectio.esempio.ts.net/x", null, c.File("x.mp3"));
        Assert.False(d.Ok);
    }

    // --- 18. Aggiornamento firmato ----------------------------------------------------------------------------

    [Fact]
    public void La_chiave_pubblica_dentro_il_programma_e_ECDSA_P256()
    {
        using var k = ECDsa.Create();
        k.ImportSubjectPublicKeyInfo(Convert.FromBase64String(Aggiornatore.ChiavePubblicaFirma), out _);
        Assert.Equal(256, k.KeySize);
        Assert.DoesNotContain("PRIVATE", File.ReadAllText(Pc("Lectio", "Motore", "Aggiornamento.cs")));
    }

    [Fact]
    public void Firma_valida_solo_per_lo_zip_giusto_e_la_chiave_giusta()
    {
        using var c = new CartellaTemporanea();
        using var chiave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var altra = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var zip = c.File("lectio-pc.zip");
        File.WriteAllBytes(zip, Encoding.UTF8.GetBytes("pacchetto"));
        var firma = Convert.ToBase64String(chiave.SignData(File.ReadAllBytes(zip), HashAlgorithmName.SHA256));
        var pub = chiave.ExportSubjectPublicKeyInfo();
        Assert.True(Aggiornatore.FirmaValida(zip, firma, pub));
        Assert.False(Aggiornatore.FirmaValida(zip, firma, altra.ExportSubjectPublicKeyInfo()));
        Assert.False(Aggiornatore.FirmaValida(zip, null, pub));
        Assert.False(Aggiornatore.FirmaValida(zip, "non-base64", pub));
        File.WriteAllBytes(zip, Encoding.UTF8.GetBytes("pacchetto cambiato"));
        Assert.False(Aggiornatore.FirmaValida(zip, firma, pub));
    }

    [Theory]
    [InlineData("mancante")]
    [InlineData("altra-chiave")]
    public async Task Aggiornamento_senza_firma_valida_si_scarta_anche_con_l_impronta_giusta(string caso)
    {
        var zip = Encoding.UTF8.GetBytes("zip finto");
        var sha = Convert.ToHexString(SHA256.HashData(zip));
        using var chiave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var altra = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var firma = Encoding.ASCII.GetBytes(Convert.ToBase64String(altra.SignData(zip, HashAlgorithmName.SHA256)));
        var finto = new HandlerFinto(r => r.Percorso.EndsWith(".sig", StringComparison.Ordinal) ? (404, "{}")
            : (200, "{\"pc\":{\"codice\":2,\"nome\":\"1.0.1\",\"sha256\":\"" + sha + "\",\"byte\":" + zip.Length + "}}"));
        finto.RispondeByte = r => r.Percorso == "/scarica/lectio-pc.zip" ? zip : caso == "altra-chiave" && r.Percorso == "/scarica/lectio-pc.zip.sig" ? firma : null;
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, Versione.Codice, Convert.ToBase64String(chiave.ExportSubjectPublicKeyInfo()));
        await a.ControllaAsync("http://192.168.0.20:8500");
        using var c = new CartellaTemporanea();
        var esito = await a.ScaricaAsync(c.Percorso);
        Assert.False(esito.Ok);
        Assert.Contains("firma", esito.Errore);
        Assert.Empty(Directory.GetFiles(c.Percorso));
    }

    [Fact]
    public async Task Se_il_server_dice_che_la_versione_nuova_non_e_firmata_non_si_scarica_niente()
    {
        var finto = new HandlerFinto(_ => (200, "{\"pc\":{\"codice\":2,\"nome\":\"1.0.1\",\"sha256\":\"" + new string('a', 64) + "\",\"firma\":false}}"));
        using var client = new ClientLectio(finto);
        var a = new Aggiornatore(client, Versione.Codice);
        Assert.NotNull(await a.ControllaAsync("https://lectio.esempio.ts.net"));
        using var c = new CartellaTemporanea();
        var esito = await a.ScaricaAsync(c.Percorso);
        Assert.False(esito.Ok);
        Assert.Contains("firma", esito.Errore);
        Assert.DoesNotContain(finto.Ricevute, r => r.Percorso.StartsWith("/scarica/", StringComparison.Ordinal));
    }

    [Fact]
    public void Lo_script_del_pacchetto_firma_lo_zip_con_la_chiave_fuori_dal_repository()
    {
        var script = File.ReadAllText(Pc("prove", "crea-pacchetto.ps1"));
        Assert.Contains("lectio-pc.zip.sig", script);
        Assert.Contains(@"lectio-keys\pc-firma-privata.pem", script);
        Assert.False(Directory.EnumerateFiles(Sorgenti.CartellaPc(), "*.pem", SearchOption.AllDirectories).Any(), "nessuna chiave nel repository");
    }

    // --- 15.5. server_id: su http il token parte solo al server giusto --------------------------------------------

    private static (int, string) Server(HandlerFinto.Richiesta r, string idCasa, bool casaGiu = false, bool fuoriGiu = false)
    {
        var casa = r.Host.StartsWith("http://192.168.0.20", StringComparison.Ordinal);
        if (casa && casaGiu) return (0, "");
        if (!casa && fuoriGiu) return (0, "");
        if (r.Percorso == "/api/salute") return (200, "{\"ok\":true,\"versione\":\"1.0.0\",\"server_id\":\"" + (casa ? idCasa : IdCasa) + "\"}");
        return (200, "[{\"id\":1,\"nome\":\"Storia\",\"da\":\"" + (casa ? "casa" : "fuori") + "\"}]");
    }

    [Fact]
    public async Task Casa_in_chiaro_col_server_giusto_riceve_il_token_dopo_la_verifica()
    {
        using var b = new Banco(r => Server(r, IdCasa), server: Casa, serverId: IdCasa, fuori: Fuori);
        var r = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Contains("casa", r.Json);
        var richieste = b.Finto.Ricevute;
        Assert.Equal("/api/salute", richieste[0].Percorso);
        Assert.Null(richieste[0].Autorizzazione);
        Assert.Equal("Bearer " + Banco.Token, richieste[1].Autorizzazione);
        // La verifica vale qualche minuto: la richiesta dopo non la rifà.
        await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        Assert.Single(b.Finto.Di("GET", "/api/salute"));
    }

    [Fact]
    public async Task Casa_in_chiaro_con_un_altro_server_non_riceve_il_token_e_si_passa_a_fuori_casa()
    {
        using var b = new Banco(r => Server(r, new string('b', 32)), server: Casa, serverId: IdCasa, fuori: Fuori);
        var r = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Contains("fuori", r.Json);
        Assert.All(b.Finto.Ricevute.Where(x => x.Host.StartsWith("http://192.168.0.20", StringComparison.Ordinal)), x => Assert.Null(x.Autorizzazione));
        var fuori = Assert.Single(b.Finto.Ricevute, x => x.Host == Fuori);
        Assert.Equal("Bearer " + Banco.Token, fuori.Autorizzazione);
    }

    [Fact]
    public async Task Casa_che_non_risponde_si_usa_fuori_casa_se_anche_quello_manca_e_senza_rete_e_l_abbinamento_resta()
    {
        bool fuoriGiu = false;
        using var b = new Banco(r => Server(r, IdCasa, casaGiu: true, fuoriGiu: fuoriGiu), server: Casa, serverId: IdCasa, fuori: Fuori);
        Assert.Contains("fuori", (await b.Motore.InoltraAsync("GET", "api/materie", "", null)).Json);
        fuoriGiu = true;
        var r = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Equal(200, r.Stato);
        Assert.NotNull(r.DaMemoriaDel);
        Assert.True(b.Motore.Abbinato);
        // Un 401 dall'indirizzo da fuori casa non cancella niente.
        b.Finto.Risponde = x => x.Percorso == "/api/salute" ? (0, "") : (401, "{\"errore\":\"Non autorizzato.\",\"codice\":\"non_autorizzato\"}");
        await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        Assert.True(b.Motore.Abbinato);
    }

    [Fact]
    public async Task L_abbinamento_salva_server_id_dalla_risposta_o_da_salute()
    {
        using var b = new Banco(r => r.Percorso == "/api/abbina" ? (200, "{\"token\":\"" + new string('t', 64) + "\",\"dispositivo_id\":3}")
            : r.Percorso == "/api/salute" ? (200, "{\"ok\":true,\"server_id\":\"" + IdCasa + "\"}") : (200, "[]"), abbinato: false);
        Assert.True(Json.Booleano((await b.Motore.AbbinaAsync(Casa, "12345678", null))["ok"]));
        Assert.Contains("\"server_id\": \"" + IdCasa + "\"", File.ReadAllText(b.Percorsi.Config));
        Assert.Null(Assert.Single(b.Finto.Di("GET", "/api/salute")).Autorizzazione);
    }

    [Fact]
    public void L_indirizzo_da_fuori_casa_parte_vuoto_e_si_scrive_solo_https()
    {
        using var b = new Banco(_ => (200, "{}"));
        Assert.Null(Json.Testo(b.Motore.Stato()["indirizzo_fuori"])); // nessun indirizzo vero nel codice
        Assert.NotNull(b.Motore.ImpostaFuoriCasa("http://192.168.0.20:8500")["errore"]);
        Assert.True(Json.Booleano(b.Motore.ImpostaFuoriCasa("lectio-2.esempio.ts.net")["ok"]));
        Assert.Equal("https://lectio-2.esempio.ts.net", Json.Testo(b.Motore.Stato()["indirizzo_fuori"]));
        Assert.True(Json.Booleano(b.Motore.Stato()["indirizzo_fuori_a_mano"]));
        Assert.True(Json.Booleano(b.Motore.ImpostaFuoriCasa("")["ok"]));
        Assert.Null(Json.Testo(b.Motore.Stato()["indirizzo_fuori"]));
    }

    [Fact]
    public async Task Senza_indirizzo_da_fuori_casa_e_con_un_altro_server_a_casa_il_token_non_parte_e_si_e_senza_rete()
    {
        using var b = new Banco(r => Server(r, new string('b', 32)), server: Casa, serverId: IdCasa);
        var r = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Equal(503, r.Stato);
        Assert.All(b.Finto.Ricevute, x => Assert.Null(x.Autorizzazione));
        Assert.True(b.Motore.Abbinato);
    }

    [Fact]
    public async Task L_indirizzo_da_fuori_casa_arriva_dal_server_all_abbinamento_e_a_ogni_salute_se_non_e_scritto_a_mano()
    {
        string? delServer = "https://lectio-fuori.esempio.ts.net";
        using var b = new Banco(r => r.Percorso == "/api/abbina"
                ? (200, "{\"token\":\"" + new string('t', 64) + "\",\"dispositivo_id\":3,\"server_id\":\"" + IdCasa + "\",\"indirizzo_fuori_casa\":\"" + delServer + "\"}")
                : r.Percorso == "/api/salute" ? (200, "{\"ok\":true,\"server_id\":\"" + IdCasa + "\",\"indirizzo_fuori_casa\":" + (delServer == null ? "null" : "\"" + delServer + "\"") + "}")
                : (200, "[]"),
            abbinato: false);
        await b.Motore.AbbinaAsync(Casa, "12345678", null);
        Assert.Equal("https://lectio-fuori.esempio.ts.net", b.Motore.IndirizzoFuori);
        // Il server lo cambia: alla prossima salute col token si aggiorna da solo.
        delServer = "https://lectio-nuovo.esempio.ts.net";
        await b.Motore.InoltraAsync("GET", "api/salute", "", null);
        Assert.Equal("https://lectio-nuovo.esempio.ts.net", b.Motore.IndirizzoFuori);
        // Scritto a mano: vale il suo, anche se il server ne manda un altro; un indirizzo in chiaro dal server non si adotta.
        b.Motore.ImpostaFuoriCasa("https://mio.esempio.ts.net");
        delServer = "https://altro.esempio.ts.net";
        await b.Motore.InoltraAsync("GET", "api/salute", "", null);
        Assert.Equal("https://mio.esempio.ts.net", b.Motore.IndirizzoFuori);
        b.Motore.ImpostaFuoriCasa("");
        Assert.Equal("https://altro.esempio.ts.net", b.Motore.IndirizzoFuori);
        delServer = "http://192.168.0.20:8500";
        await b.Motore.InoltraAsync("GET", "api/salute", "", null);
        Assert.Equal("https://altro.esempio.ts.net", b.Motore.IndirizzoFuori);
        delServer = null;
        await b.Motore.InoltraAsync("GET", "api/salute", "", null);
        Assert.Null(b.Motore.IndirizzoFuori);
    }

    // --- 16 e 17. Rosso solo per gli errori; barra del lettore allineata -------------------------------------------

    private static IEnumerable<(string Selettore, string Corpo)> Regole(string css)
    {
        foreach (Match m in Regex.Matches(Regex.Replace(css, @"/\*.*?\*/", "", RegexOptions.Singleline), @"([^{}]+)\{([^{}]*)\}"))
        {
            yield return (m.Groups[1].Value.Trim(), m.Groups[2].Value);
        }
    }

    [Fact]
    public void Il_rosso_si_usa_solo_per_gli_errori()
    {
        var css = File.ReadAllText(Pc("ui", "stile.css"));
        foreach (var (sel, corpo) in Regole(css).Where(r => r.Corpo.Contains("var(--err", StringComparison.Ordinal)))
        {
            Assert.True(Regex.IsMatch(sel, @"errore|\.err\b"), "rosso fuori dagli errori: " + sel);
        }
        Assert.DoesNotContain(Regole(css), r => r.Selettore.Contains("pericolo", StringComparison.Ordinal) && r.Corpo.Contains("--err", StringComparison.Ordinal));
    }

    [Fact]
    public void La_barra_del_lettore_e_larga_quanto_il_contenuto()
    {
        var regola = Regole(File.ReadAllText(Pc("ui", "stile.css"))).First(r => r.Selettore == ".barra-lettore");
        Assert.Contains("max-width: 976px", regola.Corpo);
        Assert.Contains("margin: 0 auto", regola.Corpo);
    }
}
