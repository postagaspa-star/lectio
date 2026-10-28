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
        Assert.Equal("1.0.0", Json.Testo(Json.Nodo(stato.Json)!["versione"]));

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
