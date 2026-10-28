using System.Text.Json.Nodes;
using Lectio.Motore;
using Lectio.Nucleo;

namespace Lectio.Tests;

/// <summary>Il programma come client del contratto (docs/contratto-api.md): token, errori, memoria, novità, file.</summary>
public class ClientContrattoTest
{
    private static (int, string) Ok(string json) => (200, json);

    [Fact]
    public async Task Le_richieste_della_pagina_partono_col_token_e_le_letture_si_tengono_per_quando_manca_la_rete()
    {
        bool rete = true;
        using var b = new Banco(r => rete ? Ok("[{\"id\":1,\"nome\":\"Storia\"}]") : (0, ""));
        var r1 = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Equal(200, r1.Stato);
        var richiesta = b.Finto.Ricevute.Single();
        Assert.Equal("/api/materie", richiesta.Percorso);
        Assert.Equal("Bearer " + Banco.Token, richiesta.Autorizzazione);
        Assert.Null(richiesta.Corpo);
        Assert.StartsWith("Lectio-PC/" + Versione.Nome, richiesta.Agente);

        rete = false;
        var r2 = await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Assert.Equal(200, r2.Stato);
        Assert.Contains("Storia", r2.Json);
        Assert.Equal(b.Adesso, r2.DaMemoriaDel);
        Assert.False(b.Motore.ServerRaggiungibile);

        var r3 = await b.Motore.InoltraAsync("GET", "api/lezioni", "", null);
        Assert.Equal(503, r3.Stato);
        Assert.Equal("rete", Json.Testo(Json.Nodo(r3.Json)!["codice"]));
        Assert.Contains("server di casa", Json.Testo(Json.Nodo(r3.Json)!["errore"]));
    }

    [Fact]
    public async Task Gli_errori_arrivano_con_la_frase_del_server_e_il_codice_mai_con_numeri()
    {
        using var b = new Banco(_ => (402, "{\"errore\":\"Sforerebbe il tetto di 2 €: usa la voce gratis\",\"codice\":\"tetto_raggiunto\"}"));
        var r = await b.Motore.InoltraAsync("POST", "api/lezioni", "", "{\"materia_id\":1}");
        Assert.Equal(402, r.Stato);
        var o = Json.Nodo(r.Json)!.AsObject();
        Assert.Equal("Sforerebbe il tetto di 2 €: usa la voce gratis", Json.Testo(o["errore"]));
        Assert.Equal("tetto_raggiunto", Json.Testo(o["codice"]));
        Assert.False(b.Motore.InPreparazione);

        // Un errore senza frase (pagina HTML): una frase nostra in italiano.
        b.Finto.Risponde = _ => (500, "<html>Internal Server Error</html>");
        var r2 = await b.Motore.InoltraAsync("POST", "api/lezioni", "", "{}");
        Assert.Equal("Il server di casa ha avuto un problema. Riprova tra poco.", Json.Testo(Json.Nodo(r2.Json)!["errore"]));
        // 502 senza la frase di Lectio = Tailscale senza server: come senza rete (revisione, punto 7).
        b.Finto.Risponde = _ => (502, "<html>Bad gateway</html>");
        var r3 = await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        Assert.Equal("rete", Json.Testo(Json.Nodo(r3.Json)!["codice"]));
    }

    [Fact]
    public async Task Un_401_vuol_dire_computer_non_piu_riconosciuto()
    {
        using var b = new Banco(_ => (401, "{\"errore\":\"Non autorizzato.\",\"codice\":\"non_autorizzato\"}"));
        await b.Motore.InoltraAsync("GET", "api/oggi", "", null);
        Assert.True(b.Motore.NonRiconosciuto);
        Assert.True(Json.Booleano(b.Motore.Stato()["non_riconosciuto"]));
    }

    [Theory]
    [InlineData("api/abbina")]
    [InlineData("scarica/lectio-pc.zip")]
    [InlineData("api//materie")]
    [InlineData("api/../segreto")]
    public async Task La_pagina_non_raggiunge_niente_fuori_da_api_ne_l_abbinamento(string percorso)
    {
        using var b = new Banco(_ => Ok("{}"));
        var r = await b.Motore.InoltraAsync("GET", percorso, "", null);
        Assert.Equal(404, r.Stato);
        Assert.Empty(b.Finto.Ricevute);
    }

    [Fact]
    public async Task Get_senza_corpo_delete_col_corpo_solo_se_c_e_il_resto_sempre_json()
    {
        using var b = new Banco(_ => Ok("{\"ok\":true}"));
        await b.Motore.InoltraAsync("DELETE", "api/materie/1", "", "{\"conferma\":true}");
        await b.Motore.InoltraAsync("DELETE", "api/lezioni/12", "", null);
        await b.Motore.InoltraAsync("PATCH", "api/impostazioni", "", null);
        await b.Motore.InoltraAsync("GET", "api/eventi", "?da=2026-10-27", "{\"non\":\"va\"}");
        var r = b.Finto.Ricevute;
        Assert.Equal("{\"conferma\":true}", r[0].Corpo);
        Assert.Null(r[1].Corpo);
        Assert.Equal("{}", r[2].Corpo);
        Assert.Equal("/api/eventi?da=2026-10-27", r[3].Percorso);
        Assert.Null(r[3].Corpo);
    }

    [Fact]
    public async Task Chiedere_una_lezione_accende_il_giro_ogni_20_secondi()
    {
        using var b = new Banco(_ => (201, "{\"id\":12,\"stato\":\"in_coda\"}"));
        Assert.False(b.Motore.InPreparazione);
        await b.Motore.InoltraAsync("POST", "api/lezioni", "", "{\"materia_id\":1}");
        Assert.True(b.Motore.InPreparazione);
        Assert.Equal(TimeSpan.FromSeconds(20), Motore.Motore.IntervalloPreparazione);
    }

    [Fact]
    public async Task Abbinamento_codice_di_8_cifre_tipo_pc_e_token_salvato_solo_cifrato()
    {
        using var b = new Banco(r => r.Percorso == "/api/abbina" ? Ok("{\"token\":\"" + new string('t', 64) + "\",\"dispositivo_id\":3}") : Ok("[]"), abbinato: false);
        Assert.False(b.Motore.Abbinato);
        var corto = await b.Motore.AbbinaAsync("https://lectio.esempio.ts.net", "1234 567", null);
        Assert.Equal("codice", Json.Testo(corto["codice"]));
        Assert.Empty(b.Finto.Ricevute);

        var ok = await b.Motore.AbbinaAsync("lectio.esempio.ts.net/api/", "1234 5678", null);
        Assert.True(Json.Booleano(ok["ok"]));
        var richiesta = b.Finto.Ricevute.Single(r => r.Percorso == "/api/abbina");
        Assert.Equal("POST", richiesta.Metodo);
        Assert.Null(richiesta.Autorizzazione);
        var corpo = Json.Nodo(richiesta.Corpo)!.AsObject();
        Assert.Equal("12345678", Json.Testo(corpo["codice"]));
        Assert.Equal("pc", Json.Testo(corpo["tipo"]));
        Assert.True(b.Motore.Abbinato);
        Assert.Equal("https://lectio.esempio.ts.net", Json.Testo(b.Motore.Stato()["server"]));
        // Il token non c'è in chiaro da nessuna parte nel file di configurazione.
        Assert.DoesNotContain(new string('t', 64), File.ReadAllText(b.Percorsi.Config));
    }

    [Theory]
    [InlineData(401, "Il codice non è giusto")]
    [InlineData(429, "Troppi codici sbagliati")]
    [InlineData(0, "Non riesco a raggiungere il server")]
    public async Task Abbinamento_non_riuscito_dice_cosa_fare(int stato, string frase)
    {
        using var b = new Banco(_ => (stato, "{}"), abbinato: false);
        var r = await b.Motore.AbbinaAsync("https://lectio.esempio.ts.net", "12345678", "PC");
        Assert.StartsWith(frase, Json.Testo(r["errore"]));
        Assert.False(b.Motore.Abbinato);
    }

    [Theory]
    [InlineData("lectio.esempio.ts.net", true, "https://lectio.esempio.ts.net")]
    [InlineData("https://lectio.esempio.ts.net/api", true, "https://lectio.esempio.ts.net")]
    [InlineData("192.168.0.20:8500", true, "http://192.168.0.20:8500")]
    [InlineData("http://lectio.esempio.it", false, "")]
    [InlineData("", false, "")]
    public void Indirizzo_del_server(string scritto, bool valido, string atteso)
    {
        Assert.Equal(valido, Indirizzo.Normalizza(scritto, out var base_, out var errore));
        if (valido) Assert.Equal(atteso, base_);
        else Assert.False(string.IsNullOrEmpty(errore));
        Assert.Equal("http://192.168.x.x:8500", Indirizzo.Esempio);
    }

    [Fact]
    public async Task Scollega_revoca_sul_server_e_dimentica_token_dati_e_lezioni_scaricate()
    {
        using var b = new Banco(_ => Ok("{\"ok\":true}"));
        await b.Motore.InoltraAsync("GET", "api/materie", "", null);
        Directory.CreateDirectory(b.Percorsi.Lezioni);
        File.WriteAllBytes(Path.Combine(b.Percorsi.Lezioni, CacheAudio.NomeFile(12, null)), new byte[] { 1, 2, 3 });
        var r = await b.Motore.ScollegaAsync();
        Assert.True(Json.Booleano(r["revocato_sul_server"]));
        Assert.Single(b.Finto.Di("DELETE", "/api/dispositivi/7"));
        Assert.False(b.Motore.Abbinato);
        Assert.Empty(b.Motore.Audio.Elenco());
        Assert.False(Directory.Exists(b.Percorsi.Memoria));
    }

    [Fact]
    public async Task Novita_il_primo_giro_prende_solo_il_cursore_poi_avvisa_per_le_lezioni_pronte()
    {
        long ultimo = 50;
        string lezioni = "[{\"id\":12,\"stato\":\"in_preparazione\"}]";
        using var b = new Banco(r =>
        {
            if (r.Percorso.StartsWith("/api/novita", StringComparison.Ordinal))
            {
                return Ok(ultimo == 50
                    ? "[{\"id\":50,\"tipo\":\"lezione_pronta\",\"testo\":\"La lezione di Storia è pronta\",\"lezione_id\":11}]"
                    : "[{\"id\":51,\"tipo\":\"scheda_aggiornata\",\"testo\":\"Scheda aggiornata\"},{\"id\":52,\"tipo\":\"lezione_pronta\",\"testo\":\"La lezione di Storia è pronta\",\"lezione_id\":12}]");
            }
            return Ok(lezioni);
        });
        var avvisi = new List<Notifica>();
        var novita = new List<string>();
        b.Motore.NuovaNotifica += avvisi.Add;
        b.Motore.Novita += novita.Add;

        await b.Motore.GiroAsync();
        Assert.Empty(avvisi); // il primo giro: solo il cursore, niente avvisi vecchi
        Assert.Contains(b.Finto.Ricevute, r => r.Percorso == "/api/novita?dopo_id=0");
        Assert.True(b.Motore.InPreparazione);

        ultimo = 52;
        lezioni = "[{\"id\":12,\"stato\":\"pronta\"}]";
        await b.Motore.GiroAsync();
        Assert.Contains(b.Finto.Ricevute, r => r.Percorso == "/api/novita?dopo_id=50");
        var avviso = Assert.Single(avvisi);
        Assert.Equal("La lezione di Storia è pronta", avviso.Titolo);
        Assert.Equal("lezione/12", avviso.Frammento);
        Assert.Single(novita);
        Assert.False(b.Motore.InPreparazione);
        // Il cursore resta salvato: un nuovo programma riparte da lì.
        Assert.Contains("\"cursore_novita\": 52", File.ReadAllText(b.Percorsi.Config));
    }

    [Theory]
    [InlineData("lezione_pronta", true)]
    [InlineData("lezione_errore", true)]
    [InlineData("materiale_errore", true)]
    [InlineData("tetto_vicino", true)]
    [InlineData("scheda_aggiornata", false)]
    [InlineData("materiale_letto", false)]
    public void Quali_novita_diventano_un_avviso_di_windows(string tipo, bool avviso)
    {
        var n = Motore.Motore.NotificaPer(new VoceNovita { Id = 1, Tipo = tipo, Testo = "x", LezioneId = 3 });
        Assert.Equal(avviso, n != null);
    }

    [Fact]
    public async Task Un_file_va_al_server_in_multipart_con_tipo_titolo_e_data()
    {
        using var b = new Banco(_ => (201, "{\"id\":40,\"stato\":\"in_lettura\"}"));
        var r = await b.Motore.CaricaFileAsync(1, "verifica", "Verifica 12 ottobre", "2026-10-12", "verifica.txt", System.Text.Encoding.UTF8.GetBytes("MATERIA: Storia | PROF: Rossi"));
        Assert.Equal(201, r.Stato);
        var q = b.Finto.Ricevute.Single();
        Assert.Equal("/api/materie/1/materiale", q.Percorso);
        Assert.Equal("multipart/form-data", q.TipoCorpo);
        Assert.Contains("filename=verifica.txt", q.Corpo);
        Assert.Contains("MATERIA: Storia | PROF: Rossi", q.Corpo);
        Assert.Contains("name=tipo", q.Corpo);
        Assert.Contains("Verifica 12 ottobre", q.Corpo);
        Assert.Contains("2026-10-12", q.Corpo);
    }

    [Theory]
    [InlineData("video.mp4", 10, 422)]
    [InlineData("foto.heic", 10, 422)]
    [InlineData("vuoto.pdf", 0, 422)]
    public async Task File_che_il_server_non_legge_si_fermano_prima(string nome, int byteFile, int stato)
    {
        using var b = new Banco(_ => Ok("{}"));
        var r = await b.Motore.CaricaFileAsync(1, "verifica", null, null, nome, new byte[byteFile]);
        Assert.Equal(stato, r.Stato);
        Assert.Empty(b.Finto.Ricevute);
    }

    [Fact]
    public async Task Un_file_oltre_20_mb_non_parte()
    {
        using var b = new Banco(_ => Ok("{}"));
        var r = await b.Motore.CaricaFileAsync(1, "libro", null, null, "libro.pdf", new byte[Motore.Motore.MassimoByteFile + 1]);
        Assert.Equal(413, r.Stato);
        Assert.Contains("20 MB", Json.Testo(Json.Nodo(r.Json)!["errore"]));
        Assert.Empty(b.Finto.Ricevute);
    }
}
