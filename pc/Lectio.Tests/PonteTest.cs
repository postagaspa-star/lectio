using System.Text;
using Lectio.Interfaccia;
using Lectio.Nucleo;

namespace Lectio.Tests;

/// <summary>Il ponte fra la pagina e il programma: solo la cartella ui, solo /locale e /server, solo Gemini fuori.</summary>
public class PonteTest
{
    private static readonly Uri Base = new(Ponte.Origine + "/");

    [Theory]
    [InlineData("/", "index.html")]
    [InlineData("/stile.css", "stile.css")]
    [InlineData("/font/Figtree.ttf", "font\\Figtree.ttf")]
    public void File_dell_interfaccia(string url, string atteso)
    {
        var f = Ponte.FileDellInterfaccia(@"C:\ui", url);
        Assert.Equal(Path.Combine(@"C:\ui", atteso), f);
    }

    [Theory]
    [InlineData("/../segreto.txt")]
    [InlineData("/%2e%2e/segreto.txt")]
    [InlineData("/font/../../x")]
    [InlineData("/c:/windows/win.ini")]
    [InlineData("/a\\b")]
    public void Niente_fuori_dalla_cartella_ui(string url)
    {
        Assert.Null(Ponte.FileDellInterfaccia(@"C:\ui", url));
    }

    [Theory]
    [InlineData("https://gemini.google.com/app", true)]
    [InlineData("https://GEMINI.google.com/app/123", true)]
    [InlineData("http://gemini.google.com/app", false)]
    [InlineData("https://gemini.google.com.esempio.it/app", false)]
    [InlineData("https://utente@gemini.google.com/app", false)]
    [InlineData("https://accounts.google.com/", false)]
    [InlineData("file:///C:/x", false)]
    public void Fuori_si_apre_solo_Gemini(string url, bool apribile)
    {
        Assert.Equal(apribile, Ponte.ApribileFuori(url));
    }

    [Theory]
    [InlineData("https://lectio.locale/index.html#oggi", true)]
    [InlineData("about:blank", true)]
    [InlineData("https://audio.lectio.locale/lezione-1-x.mp3", false)]
    [InlineData("https://lectio.locale:8443/", false)]
    [InlineData("http://lectio.locale/", false)]
    public void La_finestra_resta_su_lectio_locale(string url, bool interno)
    {
        Assert.Equal(interno, Ponte.ÈInterno(url));
    }

    [Theory]
    [InlineData("oggi", true)]
    [InlineData("impostazioni", true)]
    [InlineData("lezione/12", true)]
    [InlineData("materia/3", true)]
    [InlineData("materia/3/schemi", true)]
    [InlineData("materia/3/simulate", true)]
    [InlineData("materia/3/altro", false)]
    [InlineData("schema/4", true)]
    [InlineData("simulata/5", true)]
    [InlineData("riassunto/6", true)]
    [InlineData("schema/4/x", false)]
    [InlineData("lezione/12');alert(1);//", false)]
    [InlineData("javascript:alert(1)", false)]
    [InlineData("", false)]
    public void Frammenti_che_un_avviso_puo_aprire(string f, bool valido)
    {
        Assert.Equal(valido, FinestraLectio.FrammentoValido(f));
    }

    [Fact]
    public void Query_decodificata()
    {
        var q = Ponte.Query("?materia_id=1&titolo=Verifica%2012%20ottobre&nome=verifica+storia.pdf&vuoto");
        Assert.Equal("1", q["materia_id"]);
        Assert.Equal("Verifica 12 ottobre", q["titolo"]);
        Assert.Equal("verifica storia.pdf", q["nome"]);
        Assert.Equal("", q["vuoto"]);
    }

    [Fact]
    public async Task Rotte_locali_stato_copia_gemini_tasti_e_sconosciute()
    {
        using var b = new Banco(_ => (200, "{}"));
        string? copiato = null;
        string? aperto = null;
        bool? tasti = null;
        var ponte = new Ponte(b.Motore) { Copia = t => { copiato = t; return true; }, ApriNelBrowser = u => aperto = u, Tasti = a => tasti = a };

        var stato = await ponte.GestisciAsync("GET", new Uri(Base, "locale/stato"), null);
        Assert.Equal(200, stato.Stato);
        Assert.Equal(true, Json.Booleano(Json.Nodo(stato.Json)!["abbinato"]));
        Assert.Equal(Versione.Nome, Json.Testo(Json.Nodo(stato.Json)!["versione"]));

        var copia = await ponte.GestisciAsync("POST", new Uri(Base, "locale/copia"), Encoding.UTF8.GetBytes("{\"testo\":\"Trascrivi questa verifica\"}"));
        Assert.Equal(200, copia.Stato);
        Assert.Equal("Trascrivi questa verifica", copiato);

        ponte.Copia = _ => false;
        var fallita = await ponte.GestisciAsync("POST", new Uri(Base, "locale/copia"), Encoding.UTF8.GetBytes("{\"testo\":\"x\"}"));
        Assert.Equal(422, fallita.Stato);
        Assert.Contains("Non sono riuscito a copiarlo", Json.Testo(Json.Nodo(fallita.Json)!["errore"]));

        await ponte.GestisciAsync("POST", new Uri(Base, "locale/apri-gemini"), Encoding.UTF8.GetBytes("{}"));
        Assert.Equal("https://gemini.google.com/app", aperto);

        await ponte.GestisciAsync("POST", new Uri(Base, "locale/tasti"), Encoding.UTF8.GetBytes("{\"attivi\":true}"));
        Assert.True(tasti);

        Assert.Equal(404, (await ponte.GestisciAsync("GET", new Uri(Base, "locale/qualcosa"), null)).Stato);
        Assert.Equal(405, (await ponte.GestisciAsync("GET", new Uri(Base, "locale/copia"), null)).Stato);
        // La diagnosi c'è solo con --diagnosi.
        Assert.Equal(404, (await ponte.GestisciAsync("POST", new Uri(Base, "locale/diagnosi"), Encoding.UTF8.GetBytes("{}"))).Stato);
    }

    [Fact]
    public async Task Carica_passa_i_byte_della_pagina_al_server_in_multipart()
    {
        using var b = new Banco(_ => (201, "{\"id\":41,\"stato\":\"in_lettura\"}"));
        var ponte = new Ponte(b.Motore);
        var byteFile = Encoding.UTF8.GetBytes("TESTO: la domanda parola per parola");
        var r = await ponte.GestisciAsync("POST", new Uri(Base, "locale/carica?materia_id=3&tipo=appunti&titolo=Augusto&data=2026-10-22&nome=augusto.md"), byteFile);
        Assert.Equal(201, r.Stato);
        var q = Assert.Single(b.Finto.Ricevute);
        Assert.Equal("/api/materie/3/materiale", q.Percorso);
        Assert.Contains("filename=augusto.md", q.Corpo);
        Assert.Contains("TESTO: la domanda parola per parola", q.Corpo);
        Assert.DoesNotContain("cartella_id", q.Corpo);
    }

    [Fact]
    public async Task Carica_porta_la_cartella_scelta_e_una_cartella_scritta_male_non_parte()
    {
        using var b = new Banco(_ => (201, "{\"id\":42,\"stato\":\"in_lettura\"}"));
        var ponte = new Ponte(b.Motore);
        var byteFile = Encoding.UTF8.GetBytes("Mirone e il Discobolo");
        var r = await ponte.GestisciAsync("POST", new Uri(Base, "locale/carica?materia_id=3&tipo=appunti&nome=arte.md&cartella_id=12"), byteFile);
        Assert.Equal(201, r.Stato);
        var q = Assert.Single(b.Finto.Ricevute);
        Assert.Contains("name=cartella_id", q.Corpo);
        Assert.Contains("12", q.Corpo);

        // Scritta male: non va da nessun'altra parte, si ferma prima di partire.
        var male = await ponte.GestisciAsync("POST", new Uri(Base, "locale/carica?materia_id=3&tipo=appunti&nome=arte.md&cartella_id=dodici"), byteFile);
        Assert.Equal(422, male.Stato);
        Assert.Single(b.Finto.Ricevute);
    }

    [Fact]
    public async Task Le_richieste_al_server_passano_dal_motore_col_metodo_e_la_query()
    {
        using var b = new Banco(_ => (200, "{\"testo\":\"…\"}"));
        var ponte = new Ponte(b.Motore);
        await ponte.GestisciAsync("GET", new Uri(Base, "server/api/eventi?da=2026-10-27"), null);
        await ponte.GestisciAsync("PUT", new Uri(Base, "server/api/lezioni/12/ascolto"), Encoding.UTF8.GetBytes("{\"posizione_s\":462,\"finita\":false}"));
        var r = b.Finto.Ricevute;
        Assert.Equal("/api/eventi?da=2026-10-27", r[0].Percorso);
        Assert.Equal("PUT", r[1].Metodo);
        Assert.Equal("{\"posizione_s\":462,\"finita\":false}", r[1].Corpo);
    }

    [Fact]
    public async Task La_foto_dello_schema_arriva_in_byte_e_resta_per_quando_manca_la_rete()
    {
        var rete = true;
        using var b = new Banco(_ => rete ? (200, "{}") : (0, ""));
        var foto = new byte[] { 0xFF, 0xD8, 0xFF, 1, 2, 3 };
        b.Finto.RispondeByte = q => rete && q.Percorso == "/api/schemi/3/immagini/1" ? foto : null;
        var ponte = new Ponte(b.Motore);

        var r = await ponte.GestisciAsync("GET", new Uri(Base, "server/api/schemi/3/immagini/1?v=2"), null);
        Assert.Equal(200, r.Stato);
        Assert.Equal(foto, r.Byte);
        Assert.Equal("image/jpeg", r.TipoContenuto);

        rete = false;
        var senzaRete = await ponte.GestisciAsync("GET", new Uri(Base, "server/api/schemi/3/immagini/1?v=2"), null);
        Assert.Equal(foto, senzaRete.Byte);
        Assert.Equal(503, (await ponte.GestisciAsync("GET", new Uri(Base, "server/api/schemi/3/immagini/2"), null)).Stato);

        // Foto tolta: la copia non vale più.
        rete = true;
        b.Finto.RispondeByte = null;
        await ponte.GestisciAsync("DELETE", new Uri(Base, "server/api/schemi/3/immagini/1"), null);
        rete = false;
        var dopo = await ponte.GestisciAsync("GET", new Uri(Base, "server/api/schemi/3/immagini/1"), null);
        Assert.Null(dopo.Byte);
        Assert.Equal(503, dopo.Stato);
    }

    [Fact]
    public async Task Il_pdf_si_scarica_prima_e_si_salva_dove_dice_Windows()
    {
        using var cartella = new CartellaTemporanea();
        using var b = new Banco(_ => (200, "{}"));
        var pdf = Encoding.ASCII.GetBytes("%PDF-1.7 prova");
        b.Finto.RispondeByte = q => q.Percorso.EndsWith("/pdf", StringComparison.Ordinal) ? pdf : null;
        string? propostoNome = null;
        string? aperto = null;
        var dove = cartella.File("uscita.pdf");
        var ponte = new Ponte(b.Motore) { ChiediDoveSalvare = n => { propostoNome = n; return Task.FromResult<string?>(dove); }, ApriFile = f => aperto = f };

        var r = await ponte.GestisciAsync("POST", new Uri(Base, "locale/salva-pdf"), Encoding.UTF8.GetBytes("{\"percorso\":\"api/riassunti/4/pdf\",\"nome\":\"La guerra: fredda?\"}"));
        Assert.Equal(200, r.Stato);
        Assert.Equal("La guerra- fredda-.pdf", propostoNome);
        Assert.Equal(pdf, File.ReadAllBytes(dove));
        Assert.Equal("/api/riassunti/4/pdf", Assert.Single(b.Finto.Ricevute).Percorso);

        var id = Json.Testo(Json.Nodo(r.Json)!["id"]);
        var apri = await ponte.GestisciAsync("POST", new Uri(Base, "locale/apri-pdf"), Encoding.UTF8.GetBytes("{\"id\":\"" + id + "\"}"));
        Assert.Equal(200, apri.Stato);
        Assert.Equal(dove, aperto);

        // Solo i file salvati adesso: mai un percorso qualsiasi.
        var altro = await ponte.GestisciAsync("POST", new Uri(Base, "locale/apri-pdf"), Encoding.UTF8.GetBytes("{\"id\":\"C:\\\\Windows\\\\notepad.exe\"}"));
        Assert.Equal(422, altro.Stato);
    }

    [Theory]
    [InlineData("api/lezioni/4/pdf")]
    [InlineData("api/schemi/4/immagini/1")]
    [InlineData("../api/schemi/4/pdf")]
    [InlineData("https://altro.it/api/schemi/4/pdf")]
    public async Task Il_pdf_si_chiede_solo_dai_percorsi_noti(string percorso)
    {
        using var b = new Banco(_ => (200, "{}"));
        var chiesto = false;
        var ponte = new Ponte(b.Motore) { ChiediDoveSalvare = _ => { chiesto = true; return Task.FromResult<string?>(null); } };
        var r = await ponte.GestisciAsync("POST", new Uri(Base, "locale/salva-pdf"), Encoding.UTF8.GetBytes(new System.Text.Json.Nodes.JsonObject { ["percorso"] = percorso }.ToJsonString()));
        Assert.Equal(422, r.Stato);
        Assert.False(chiesto);
        Assert.Empty(b.Finto.Ricevute);
    }

    [Fact]
    public async Task Pdf_annullato_o_errore_del_server_non_scrivono_niente()
    {
        using var b = new Banco(_ => (409, "{\"errore\":\"Non è ancora pronto.\",\"codice\":\"conflitto\"}"));
        var chiesto = false;
        var ponte = new Ponte(b.Motore) { ChiediDoveSalvare = _ => { chiesto = true; return Task.FromResult<string?>(null); } };
        var corpo = Encoding.UTF8.GetBytes("{\"percorso\":\"api/simulate/3/pdf\",\"nome\":\"x\"}");
        var errore = await ponte.GestisciAsync("POST", new Uri(Base, "locale/salva-pdf"), corpo);
        Assert.Equal(422, errore.Stato);
        Assert.False(chiesto);

        b.Finto.RispondeByte = _ => new byte[] { 1 };
        var annullato = await ponte.GestisciAsync("POST", new Uri(Base, "locale/salva-pdf"), corpo);
        Assert.Equal(200, annullato.Stato);
        Assert.True(chiesto);
        Assert.Equal(true, Json.Booleano(Json.Nodo(annullato.Json)!["annullato"]));
    }

    [Theory]
    [InlineData(null, "Lectio.pdf")]
    [InlineData("  ", "Lectio.pdf")]
    [InlineData("Storia.PDF", "Storia.pdf")]
    [InlineData("a/b\\c", "a-b-c.pdf")]
    public void Nome_del_pdf_pulito(string? nome, string atteso)
    {
        Assert.Equal(atteso, Ponte.NomePdf(nome));
    }

    [Fact]
    public async Task La_visita_delle_novita_si_ricorda_a_parte()
    {
        using var b = new Banco(_ => (200, "{}"));
        var ponte = new Ponte(b.Motore);
        var prima = await ponte.GestisciAsync("GET", new Uri(Base, "locale/stato"), null);
        Assert.Equal(false, Json.Booleano(Json.Nodo(prima.Json)!["tutorial_novita_visto"]));
        Assert.Equal(200, (await ponte.GestisciAsync("POST", new Uri(Base, "locale/tutorial-novita-visto"), Encoding.UTF8.GetBytes("{}"))).Stato);
        var dopo = await ponte.GestisciAsync("GET", new Uri(Base, "locale/stato"), null);
        Assert.Equal(true, Json.Booleano(Json.Nodo(dopo.Json)!["tutorial_novita_visto"]));
        Assert.Equal(false, Json.Booleano(Json.Nodo(dopo.Json)!["tutorial_visto"]));
    }

    [Fact]
    public async Task Chi_aveva_visto_le_novita_vede_una_volta_solo_quelle_del_giro_nuovo()
    {
        using var b = new Banco(_ => (200, "{}"), config: c => c.TutorialNovitaVisto = true);
        var ponte = new Ponte(b.Motore);
        var prima = Json.Nodo((await ponte.GestisciAsync("GET", new Uri(Base, "locale/stato"), null)).Json)!;
        Assert.Equal(true, Json.Booleano(prima["tutorial_novita_visto"]));
        Assert.Equal(true, Json.Booleano(prima["novita_nuove"]));
        Assert.Equal(200, (await ponte.GestisciAsync("POST", new Uri(Base, "locale/tutorial-novita-visto"), Encoding.UTF8.GetBytes("{}"))).Stato);
        var dopo = Json.Nodo((await ponte.GestisciAsync("GET", new Uri(Base, "locale/stato"), null)).Json)!;
        Assert.Equal(false, Json.Booleano(dopo["novita_nuove"]));
        Assert.Equal(Lectio.Motore.Configurazione.GiroNovita, Archivio.LeggiJson<Lectio.Motore.Configurazione>(b.Percorsi.Config)!.NovitaViste);
    }

    [Fact]
    public async Task Chi_non_ha_mai_visto_le_novita_non_ha_il_giro_nuovo_a_parte()
    {
        using var b = new Banco(_ => (200, "{}"));
        var stato = Json.Nodo((await new Ponte(b.Motore).GestisciAsync("GET", new Uri(Base, "locale/stato"), null)).Json)!;
        Assert.Equal(false, Json.Booleano(stato["novita_nuove"]));
    }

    [Theory]
    [InlineData("POST", "api/chiarimenti", 65)]
    [InlineData("POST", "api/schemi/3/immagini/1/cambia", 120)]
    [InlineData("POST", "api/lezioni/3/domande/1/risposta", 200)]
    [InlineData("GET", "api/schemi/3/immagini/1", 30)]
    [InlineData("POST", "api/schemi", 30)]
    public void Tempo_massimo_delle_richieste(string metodo, string percorso, int secondi)
    {
        Assert.Equal(TimeSpan.FromSeconds(secondi), Motore.Motore.TempoPer(metodo, percorso));
    }

    [Theory]
    [InlineData("schema_pronto", "materia/2/schemi")]
    [InlineData("schema_errore", "materia/2/schemi")]
    [InlineData("riassunto_pronto", "materia/2/schemi")]
    [InlineData("riassunto_errore", "materia/2/schemi")]
    [InlineData("simulata_pronta", "materia/2/simulate")]
    [InlineData("simulata_corretta", "materia/2/simulate")]
    [InlineData("simulata_errore", "materia/2/simulate")]
    public void Gli_avvisi_dello_studio_aprono_la_materia(string tipo, string frammento)
    {
        var n = Motore.Motore.NotificaPer(new VoceNovita { Id = 1, Tipo = tipo, Testo = "Fatto", MateriaId = 2 });
        Assert.NotNull(n);
        Assert.Equal(frammento, n!.Frammento);
        Assert.True(FinestraLectio.FrammentoValido(n.Frammento));
    }

    [Fact]
    public async Task Il_giro_resta_acceso_finche_uno_schema_o_una_verifica_sono_in_lavorazione()
    {
        var statoSchema = "in_preparazione";
        var statoSimulata = "pronta";
        using var b = new Banco(q => q.Percorso switch
        {
            "/api/schemi" => (200, "[{\"id\":3,\"stato\":\"" + statoSchema + "\"}]"),
            "/api/simulate" => (200, "[{\"id\":4,\"stato\":\"" + statoSimulata + "\"}]"),
            "/api/lezioni" => (200, "[]"),
            _ => (200, "[]"),
        }, cursore: 0);
        await b.Motore.GiroAsync();
        Assert.True(b.Motore.InPreparazione);

        statoSchema = "pronto";
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);

        statoSimulata = "in_correzione";
        await b.Motore.GiroAsync();
        Assert.True(b.Motore.InPreparazione);

        statoSimulata = "corretta";
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);
    }

    [Fact]
    public async Task Un_riassunto_chiesto_tiene_acceso_il_giro_fino_alla_novita_o_alla_scadenza()
    {
        var novita = "[]";
        using var b = new Banco(q => q.Percorso.StartsWith("/api/novita", StringComparison.Ordinal) ? (200, novita)
            : q.Metodo == "POST" ? (201, "{\"id\":9,\"stato\":\"in_coda\"}") : (200, "[]"), cursore: 0);
        var ponte = new Ponte(b.Motore);
        await b.Motore.GiroAsync(); // Il primo giro mette solo il cursore.

        await ponte.GestisciAsync("POST", new Uri(Base, "server/api/schemi/3/riassunti"), Encoding.UTF8.GetBytes("{\"forma\":\"misto\"}"));
        Assert.True(b.Motore.InPreparazione);
        await b.Motore.GiroAsync();
        Assert.True(b.Motore.InPreparazione);

        novita = "[{\"id\":5,\"tipo\":\"riassunto_pronto\",\"testo\":\"Pronto\",\"materia_id\":2}]";
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);

        // Se la novità non arriva mai, dopo un po' si smette di guardare.
        novita = "[]";
        await ponte.GestisciAsync("POST", new Uri(Base, "server/api/schemi/3/riassunti"), Encoding.UTF8.GetBytes("{\"forma\":\"punti\"}"));
        b.Adesso += Motore.Motore.AttesaRiassunto + TimeSpan.FromSeconds(1);
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);
    }

    [Theory]
    [InlineData("modifica_pronta", null, "materia/2", "La modifica è pronta", "Fai clic per aprirla.")]
    [InlineData("modifica_errore", null, "materia/2", "La modifica non è riuscita", "Fai clic per vedere cosa è successo.")]
    [InlineData("modifica_pronta", 12L, "lezione/12", "La modifica è pronta", "Fai clic per aprirla.")]
    [InlineData("modifica_errore", 12L, "lezione/12", "La modifica non è riuscita", "Fai clic per vedere cosa è successo.")]
    public void Gli_avvisi_delle_modifiche_aprono_la_lezione_o_la_materia(string tipo, long? lezione, string frammento, string titolo, string testo)
    {
        // Senza testo del server valgono le frasi del programma; con il testo, quello del server.
        var n = Motore.Motore.NotificaPer(new VoceNovita { Id = 1, Tipo = tipo, Testo = null, MateriaId = 2, LezioneId = lezione });
        Assert.NotNull(n);
        Assert.Equal(frammento, n!.Frammento);
        Assert.Equal(titolo, n.Titolo);
        Assert.Equal(testo, n.Testo);
        Assert.True(FinestraLectio.FrammentoValido(n.Frammento));
        var conTesto = Motore.Motore.NotificaPer(new VoceNovita { Id = 2, Tipo = tipo, Testo = "Ho modificato lo schema «Le crociate»", MateriaId = 2 });
        Assert.Equal("Ho modificato lo schema «Le crociate»", conTesto!.Titolo);
    }

    [Fact]
    public async Task Il_giro_resta_acceso_finche_una_modifica_lavora_e_un_server_vecchio_vale_niente()
    {
        var modifiche = (200, "[{\"id\":4,\"tipo\":\"schema\",\"oggetto_id\":12,\"stato\":\"in_preparazione\"}]");
        using var b = new Banco(q => q.Percorso == "/api/modifiche?attive=1" ? modifiche : (200, "[]"), cursore: 0);
        await b.Motore.GiroAsync();
        Assert.True(b.Motore.InPreparazione);
        Assert.Contains(b.Finto.Ricevute, r => r.Metodo == "GET" && r.Percorso == "/api/modifiche?attive=1");

        modifiche = (200, "[]");
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);

        modifiche = (200, "[{\"id\":4,\"stato\":\"in_coda\"}]");
        await b.Motore.GiroAsync();
        Assert.True(b.Motore.InPreparazione);

        // Un server di prima della 1.7 non conosce l'indirizzo: niente in corso.
        modifiche = (404, "{\"errore\":\"Non trovato.\",\"codice\":\"non_trovato\"}");
        await b.Motore.GiroAsync();
        Assert.False(b.Motore.InPreparazione);
    }

    [Theory]
    [InlineData("server/api/modifiche", true)]
    [InlineData("server/api/modifiche/4/riprova", true)]
    [InlineData("server/api/modifiche/stima", false)]
    [InlineData("server/api/modifiche/4/annulla", false)]
    [InlineData("server/api/schemi/3/scambia-versione", false)]
    public async Task Chiedere_una_modifica_accende_il_giro_la_stima_no(string percorso, bool acceso)
    {
        using var b = new Banco(q => q.Metodo == "POST" ? (201, "{\"id\":4,\"stato\":\"in_coda\"}") : (200, "[]"));
        var ponte = new Ponte(b.Motore);
        Assert.False(b.Motore.InPreparazione);
        var r = await ponte.GestisciAsync("POST", new Uri(Base, percorso), Encoding.UTF8.GetBytes("{\"tipo\":\"schema\",\"id\":12,\"richiesta\":\"Aggiungi le date\",\"parti\":[2]}"));
        Assert.Equal(201, r.Stato);
        Assert.Equal(acceso, b.Motore.InPreparazione);
    }

    [Fact]
    public async Task La_correzione_a_mano_va_al_server_con_PUT_e_il_suo_corpo()
    {
        using var b = new Banco(_ => (200, "{\"id\":3,\"stato\":\"pronto\"}"));
        var ponte = new Ponte(b.Motore);
        var corpo = "{\"titolo\":\"Le crociate\",\"contenuto\":{\"parti\":[{\"titolo\":null,\"paragrafi\":[\"Uno\"],\"punti\":[]}]}}";
        var r = await ponte.GestisciAsync("PUT", new Uri(Base, "server/api/riassunti/4/testo"), Encoding.UTF8.GetBytes(corpo));
        Assert.Equal(200, r.Stato);
        var q = Assert.Single(b.Finto.Di("PUT", "/api/riassunti/4/testo"));
        Assert.Equal(corpo, q.Corpo);
        Assert.False(b.Motore.InPreparazione);
    }

    [Theory]
    [InlineData(1, "play")]
    [InlineData(2, "avanti")]
    [InlineData(3, "indietro")]
    [InlineData(9, null)]
    public void Tasti_multimediali(int id, string? azione)
    {
        Assert.Equal(azione, TastiMultimediali.AzionePer(id));
    }
}
