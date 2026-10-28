using System.Globalization;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Lectio.Nucleo;
using Lectio.Rete;
using Lectio.Sistema;

namespace Lectio.Motore;

/// <summary>Una risposta per la pagina; <see cref="DaMemoriaDel"/> = dati vecchi perché il server non risponde.</summary>
public sealed record RispostaPonte(int Stato, string Json, DateTimeOffset? DaMemoriaDel = null);

/// <summary>Un avviso di Windows (fumetto dall'icona vicino all'orologio); <see cref="Frammento"/> = cosa apre il clic.</summary>
public sealed record Notifica(string Titolo, string Testo, string? Frammento = null);

/// <summary>
/// Il cuore del programma, senza finestre: collegamento al server (abbinamento, token protetto), richieste della
/// pagina girate al server col token, audio delle lezioni sul computer, novità e avvisi mentre una lezione è in
/// preparazione (una domanda ogni 20 secondi, §11), versioni nuove. Si prova senza Windows Forms.
///
/// Collegamento (§15.5): su un indirizzo https il token parte sempre; su un indirizzo di casa in chiaro (http) parte
/// SOLO dopo aver visto con <c>GET /api/salute</c> (senza token) che <c>server_id</c> è quello salvato
/// all'abbinamento. Se la casa non risponde o non coincide si usa l'indirizzo da fuori casa (https, Tailscale);
/// se non c'è, è come senza rete. Mai l'abbinamento cancellato per un server diverso.
/// </summary>
public sealed class Motore : IDisposable
{
    public static readonly TimeSpan IntervalloPreparazione = TimeSpan.FromSeconds(20);
    public static readonly TimeSpan IntervalloVersione = TimeSpan.FromHours(12);
    public static readonly TimeSpan TempoAbbinamento = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan TempoCaricamento = TimeSpan.FromMinutes(3);

    /// <summary>La correzione di una risposta scritta passa da un modello: può metterci, si aspetta fino a 200 s.</summary>
    public static readonly TimeSpan TempoCorrezione = TimeSpan.FromSeconds(200);

    /// <summary>Quanto vale la verifica di <c>server_id</c> su un indirizzo di casa in chiaro.</summary>
    public static readonly TimeSpan DurataVerificaCasa = TimeSpan.FromMinutes(5);

    /// <summary>Le novità più vecchie di così non diventano avvisi (per esempio dopo giorni a computer spento).</summary>
    public static readonly TimeSpan EtaMassimaAvvisi = TimeSpan.FromHours(1);

    /// <summary>§6: al massimo 20 MB a file.</summary>
    public const long MassimoByteFile = 20L * 1024 * 1024;

    /// <summary>§6: i formati che il server legge (HEIC lo convertono le app del telefono, qui non arriva).</summary>
    public static readonly string[] Estensioni = { ".txt", ".md", ".pdf", ".docx", ".odt", ".rtf", ".jpg", ".jpeg", ".png", ".webp" };

    private static readonly Regex PercorsoAmmesso = new(@"^api/[A-Za-z0-9_\-/]*$", RegexOptions.CultureInvariant);
    private static readonly Regex PercorsoNuovaLezione = new(@"^api/lezioni/?$", RegexOptions.CultureInvariant);
    private static readonly Regex PercorsoRiprovaLezione = new(@"^api/lezioni/\d+/riprova/?$", RegexOptions.CultureInvariant);
    private static readonly Regex PercorsoRisposta = new(@"^api/lezioni/\d+/domande/\d+/risposta/?$", RegexOptions.CultureInvariant);
    private static readonly Regex ServerIdValido = new(@"^[0-9a-fA-F]{32}$", RegexOptions.CultureInvariant);
    private static readonly HashSet<string> TipiMateriale = new(StringComparer.Ordinal) { "verifica", "interrogazione", "appunti", "libro", "altro" };

    private readonly Percorsi percorsi;
    private readonly ClientLectio client;
    private readonly bool clientNostro;
    private readonly Func<DateTimeOffset> orologio;
    private readonly Memoria memoria;
    private readonly object stato = new();
    private readonly SemaphoreSlim sveglia = new(0, 1);
    private readonly SemaphoreSlim giro = new(1, 1);
    private CancellationTokenSource? fine;
    private Task? ciclo;
    private Configurazione config;
    private string? token;
    private int svegliaChiesta;
    private int scaricamentoInCorso;
    private EsitoScaricamento? ultimoScaricamento;
    private bool inPreparazione;
    private bool primoGiroFatto;
    private DateTimeOffset? casaVerificataFino;

    public Motore(Percorsi percorsi, ClientLectio? client = null, Func<DateTimeOffset>? orologio = null)
    {
        this.percorsi = percorsi;
        clientNostro = client == null;
        this.client = client ?? new ClientLectio();
        this.orologio = orologio ?? (() => DateTimeOffset.Now);
        memoria = new Memoria(percorsi.Memoria);
        config = Archivio.LeggiJson<Configurazione>(percorsi.Config) ?? new Configurazione();
        token = Dpapi.Svela(config.TokenProtetto);
        if (token == null && config.Server != null)
        {
            // Il token non si decifra (copiato da un altro utente o computer): si ricomincia dall'abbinamento.
            config = new Configurazione { TutorialVisto = config.TutorialVisto, Tema = config.Tema, IndirizzoFuori = config.IndirizzoFuori };
        }
        Audio = new CacheAudio(percorsi.Lezioni, this.client);
        Aggiornatore = new Aggiornatore(this.client, Versione.Codice);
    }

    public CacheAudio Audio { get; }
    public Aggiornatore Aggiornatore { get; }

    /// <summary>Il controllo delle versioni nuove vale solo per il programma scompattato dallo zip.</summary>
    public bool AggiornamentiAttivi { get; set; }

    /// <summary>Dove salvare lo zip della versione nuova (la cartella Download; nei test una temporanea).</summary>
    public string CartellaDownload { get; set; } = Cartelle.Download();

    /// <summary>Avviato con <c>--diagnosi</c>: la pagina prova audio e invio dei file e scrive l'esito nel diario.</summary>
    public bool Diagnosi { get; set; }

    /// <summary>Solo nelle prove: un tempo massimo uguale per tutte le richieste girate al server.</summary>
    internal TimeSpan? TempoForzato { get; set; }

    public event Action<Notifica>? NuovaNotifica;

    /// <summary>Novità dal server (§11) da passare alla pagina, già in JSON.</summary>
    public event Action<string>? Novita;

    public event Action? StatoCambiato;
    public event Action? TemaCambiato;
    public event Action<VersioneNuova>? AggiornamentoDisponibile;
    public event Action<EsitoScaricamento>? ScaricamentoFinito;

    public bool Abbinato
    {
        get { lock (stato) return config.Server != null && token != null; }
    }

    /// <summary>Il server non riconosce più questo computer (401).</summary>
    public bool NonRiconosciuto { get; private set; }

    /// <summary>null finché non si è provato; poi l'esito dell'ultima richiesta.</summary>
    public bool? ServerRaggiungibile { get; private set; }

    /// <summary>C'è una lezione in coda o in preparazione: si chiede al server ogni 20 secondi.</summary>
    public bool InPreparazione
    {
        get { lock (stato) return inPreparazione; }
    }

    public int GiriFatti { get; private set; }

    /// <summary>
    /// L'indirizzo da fuori casa (https): si usa quando quello di casa non risponde o non è il nostro server. Quello
    /// scritto a mano vale sopra quello mandato dal server; null = nessuno (come senza rete).
    /// </summary>
    public string? IndirizzoFuori
    {
        get { lock (stato) return config.IndirizzoFuori ?? config.IndirizzoFuoriServer; }
    }

    /// <summary>§15.5: l'indirizzo da fuori casa mandato dal server (abbinamento, salute). Solo https; null lo toglie.</summary>
    private void AdottaFuoriCasaDelServer(string? indirizzo)
    {
        string? nuovo = null;
        if (!string.IsNullOrWhiteSpace(indirizzo) && Indirizzo.Normalizza(indirizzo, out var base_, out _) && !InChiaro(base_)) nuovo = base_;
        else if (!string.IsNullOrWhiteSpace(indirizzo)) return; // un indirizzo non valido o in chiaro non si adotta
        lock (stato)
        {
            if (config.Server == null || string.Equals(config.IndirizzoFuoriServer, nuovo, StringComparison.OrdinalIgnoreCase)) return;
            config.IndirizzoFuoriServer = nuovo;
            SalvaConfig();
        }
    }

    private (string? Server, string? Token) Credenziali()
    {
        lock (stato) return (config.Server, token);
    }

    // ---------------- Collegamento: quale indirizzo, e quando il token può partire ----------------

    /// <summary>Un 502/503/504 senza la frase di Lectio viene da Tailscale o da un proxy: il server non c'è, come senza rete.</summary>
    public static bool ComeSenzaRete(Risposta r)
    {
        if (r.Rete) return true;
        if (r.Stato is not (502 or 503 or 504)) return false;
        var corpo = r.Corpo.TrimStart();
        return !corpo.StartsWith('{') || string.IsNullOrEmpty(Json.Leggi<ErroreServer>(corpo)?.Codice);
    }

    /// <summary>Il tempo massimo di una richiesta della pagina: le correzioni delle risposte possono metterci di più.</summary>
    public static TimeSpan TempoPer(string metodo, string percorso) =>
        metodo.Equals("POST", StringComparison.OrdinalIgnoreCase) && PercorsoRisposta.IsMatch(percorso.TrimStart('/').Split('?')[0])
            ? TempoCorrezione
            : ClientLectio.TempoMassimo;

    private static bool InChiaro(string? server) => server != null && server.StartsWith("http://", StringComparison.OrdinalIgnoreCase);

    /// <summary>Il <c>server_id</c> letto da <c>GET /api/salute</c> SENZA token (null se non risponde o non lo dice).</summary>
    private async Task<string?> LeggiServerIdAsync(string server, CancellationToken annulla = default)
    {
        var r = await client.InviaAsync("GET", server, "api/salute", null, null, TimeSpan.FromSeconds(6), annulla).ConfigureAwait(false);
        if (!r.Ok) return null;
        var id = Json.Leggi<SaluteBreve>(r.Corpo)?.ServerId;
        return id != null && ServerIdValido.IsMatch(id) ? id.ToLowerInvariant() : null;
    }

    /// <summary>
    /// Dove mandare le richieste col token. https: sempre l'indirizzo salvato. http (casa): solo se <c>server_id</c>
    /// coincide (verifica valida 5 minuti); altrimenti l'indirizzo da fuori casa; se non c'è, null = come senza rete.
    /// </summary>
    public async Task<(string? Base, string? Token)> CollegamentoAsync(CancellationToken annulla = default)
    {
        var (server, tok) = Credenziali();
        if (server == null || tok == null) return (null, null);
        if (!InChiaro(server)) return (server, tok);
        string? atteso;
        lock (stato)
        {
            if (casaVerificataFino is DateTimeOffset fino && orologio() < fino) return (server, tok);
            atteso = config.ServerId;
        }
        var visto = await LeggiServerIdAsync(server, annulla).ConfigureAwait(false);
        if (atteso != null && visto != null && string.Equals(atteso, visto, StringComparison.OrdinalIgnoreCase))
        {
            lock (stato) casaVerificataFino = orologio() + DurataVerificaCasa;
            return (server, tok);
        }
        if (visto != null) Log.Avviso("all'indirizzo di casa risponde un altro server: il token non parte");
        var fuori = IndirizzoFuori;
        return fuori == null || string.Equals(fuori, server, StringComparison.OrdinalIgnoreCase) || InChiaro(fuori) ? (null, tok) : (fuori, tok);
    }

    /// <summary>Dopo un problema di rete con l'indirizzo di casa la prossima richiesta ricontrolla chi risponde.</summary>
    private void DimenticaVerifica(string? base_)
    {
        if (!InChiaro(base_)) return;
        lock (stato) casaVerificataFino = null;
    }

    // ---------------- Ciclo in sottofondo ----------------

    public void Avvia()
    {
        if (ciclo != null) return;
        fine = new CancellationTokenSource();
        ciclo = Task.Run(() => CicloAsync(fine.Token));
    }

    /// <summary>Un giro subito (apertura della finestra, lezione appena chiesta). Più richieste insieme valgono una.</summary>
    public void Sveglia()
    {
        if (Interlocked.Exchange(ref svegliaChiesta, 1) == 1) return;
        try
        {
            sveglia.Release();
        }
        catch (SemaphoreFullException)
        {
        }
    }

    private async Task CicloAsync(CancellationToken annulla)
    {
        long prossimaVersione = Environment.TickCount64 + 20_000;
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(1), annulla).ConfigureAwait(false);
            while (!annulla.IsCancellationRequested)
            {
                try
                {
                    await GiroAsync(annulla).ConfigureAwait(false);
                    if (AggiornamentiAttivi && Environment.TickCount64 >= prossimaVersione && Credenziali().Server != null)
                    {
                        prossimaVersione = Environment.TickCount64 + (long)IntervalloVersione.TotalMilliseconds;
                        var (base_, _) = await CollegamentoAsync(annulla).ConfigureAwait(false);
                        // Lo zip non ha bisogno del token: se la casa non si verifica si prova lo stesso (lo protegge la firma).
                        var server = base_ ?? Credenziali().Server!;
                        var nuova = await Aggiornatore.ControllaAsync(server, annulla).ConfigureAwait(false);
                        if (nuova != null) AggiornamentoDisponibile?.Invoke(nuova);
                    }
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    Log.Errore("giro in sottofondo", e);
                }
                // §11: ogni 20 secondi mentre una lezione è in preparazione; altrimenti solo quando serve.
                var attesa = InPreparazione ? IntervalloPreparazione : TimeSpan.FromMinutes(30);
                await sveglia.WaitAsync(attesa, annulla).ConfigureAwait(false);
                Interlocked.Exchange(ref svegliaChiesta, 0);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    /// <summary>
    /// <c>GET /api/novita?dopo_id=</c> → avvisi per le lezioni pronte o non riuscite; poi <c>GET /api/lezioni</c> per
    /// sapere se ce n'è ancora una in preparazione (e togliere dal computer l'audio delle lezioni eliminate).
    /// Al primo giro dopo l'avvio, se non c'è ancora un cursore (o è 0), si prende solo il cursore: niente avvisi
    /// vecchi. Le novità più vecchie di un'ora non diventano avvisi.
    /// </summary>
    public async Task GiroAsync(CancellationToken annulla = default)
    {
        if (!Abbinato) return;
        if (!await giro.WaitAsync(0, annulla).ConfigureAwait(false)) return;
        try
        {
            GiriFatti++;
            var primo = !primoGiroFatto;
            var (base_, tok) = await CollegamentoAsync(annulla).ConfigureAwait(false);
            if (base_ == null || tok == null)
            {
                ServerRaggiungibile = false;
                return;
            }
            long? cursore;
            lock (stato) cursore = config.CursoreNovita;
            var percorso = "api/novita?dopo_id=" + (cursore ?? 0).ToString(CultureInfo.InvariantCulture);
            var r = await client.InviaAsync("GET", base_, percorso, tok, null, annulla: annulla).ConfigureAwait(false);
            Annota(r, base_);
            if (r.Ok && Json.Leggi<List<VoceNovita>>(r.Corpo) is List<VoceNovita> voci)
            {
                primoGiroFatto = true;
                var soloCursore = cursore == null || (cursore == 0 && primo);
                var massimo = voci.Count == 0 ? cursore ?? 0 : Math.Max(cursore ?? 0, voci.Max(v => v.Id));
                var nuove = soloCursore ? new List<VoceNovita>() : voci.Where(v => v.Id > cursore).OrderBy(v => v.Id).ToList();
                if (cursore == null || massimo != cursore)
                {
                    lock (stato)
                    {
                        config.CursoreNovita = massimo;
                        SalvaConfig();
                    }
                }
                var adesso = orologio();
                foreach (var v in nuove.Where(v => Recente(v, adesso)))
                {
                    if (NotificaPer(v) is Notifica n) NuovaNotifica?.Invoke(n);
                }
                if (nuove.Count > 0) Novita?.Invoke(Json.Scrivi(nuove));
            }
            if (ComeSenzaRete(r) || r.Stato == 401) return;

            var l = await client.InviaAsync("GET", base_, "api/lezioni", tok, null, annulla: annulla).ConfigureAwait(false);
            Annota(l, base_);
            if (l.Ok && Json.Leggi<List<LezioneBreve>>(l.Corpo) is List<LezioneBreve> lezioni)
            {
                memoria.Salva("GET api/lezioni", l.Corpo, orologio());
                ImpostaPreparazione(lezioni.Any(x => x.InLavorazione));
                Audio.TogliAssenti(lezioni.Select(x => x.Id));
            }
        }
        finally
        {
            giro.Release();
        }
    }

    /// <summary>Una novità è recente se ha meno di un'ora; senza data si considera recente.</summary>
    public static bool Recente(VoceNovita v, DateTimeOffset adesso) =>
        !DateTimeOffset.TryParse(v.Quando, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var quando) || adesso - quando <= EtaMassimaAvvisi;

    /// <summary>Le novità che diventano un avviso di Windows; le altre (scheda aggiornata, file letto) si vedono nella pagina.</summary>
    public static Notifica? NotificaPer(VoceNovita v)
    {
        var frammento = v.LezioneId is long id ? "lezione/" + id.ToString(CultureInfo.InvariantCulture) : null;
        return v.Tipo switch
        {
            "lezione_pronta" => new Notifica(Testo(v.Testo, "La lezione è pronta"), "Fai clic per ascoltarla.", frammento),
            "lezione_errore" => new Notifica(Testo(v.Testo, "La lezione non è riuscita"), "Fai clic per vedere cosa è successo.", frammento),
            "materiale_errore" => new Notifica(Testo(v.Testo, "Un file non si è letto"), "Fai clic per vedere cosa è successo.", null),
            "tetto_vicino" => new Notifica(Testo(v.Testo, "Hai quasi raggiunto il tetto del mese"), "Con la voce gratis le lezioni costano solo il testo.", "impostazioni"),
            _ => null,
        };

        static string Testo(string? t, string predefinito) => string.IsNullOrWhiteSpace(t) ? predefinito : t.Trim();
    }

    private void ImpostaPreparazione(bool valore)
    {
        bool cambiato;
        lock (stato)
        {
            cambiato = inPreparazione != valore;
            inPreparazione = valore;
        }
        if (cambiato) StatoCambiato?.Invoke();
    }

    private void Annota(Risposta r, string? base_)
    {
        if (ComeSenzaRete(r))
        {
            ServerRaggiungibile = false;
            DimenticaVerifica(base_);
            return;
        }
        ServerRaggiungibile = true;
        var prima = NonRiconosciuto;
        NonRiconosciuto = r.Stato == 401;
        if (prima != NonRiconosciuto) StatoCambiato?.Invoke();
    }

    // ---------------- Stato e abbinamento ----------------

    public JsonObject Stato()
    {
        Configurazione c;
        lock (stato) c = config;
        var nuova = Aggiornatore.Disponibile;
        var scaricato = ultimoScaricamento;
        var scaricate = Audio.Elenco();
        return new JsonObject
        {
            ["abbinato"] = Abbinato,
            ["server"] = c.Server,
            ["esempio_indirizzo"] = Indirizzo.Esempio,
            ["indirizzo_fuori"] = c.IndirizzoFuori ?? c.IndirizzoFuoriServer,
            ["indirizzo_fuori_a_mano"] = c.IndirizzoFuori != null,
            ["casa_in_chiaro"] = InChiaro(c.Server),
            ["nome_dispositivo"] = c.NomeDispositivo,
            ["nome_suggerito"] = NomeSuggerito(),
            ["non_riconosciuto"] = NonRiconosciuto,
            ["raggiungibile"] = ServerRaggiungibile,
            ["in_preparazione"] = InPreparazione,
            ["versione"] = Versione.Nome,
            ["tutorial_visto"] = c.TutorialVisto,
            ["tema"] = c.Tema ?? "sistema",
            ["diagnosi"] = Diagnosi,
            ["scaricate"] = new JsonArray(scaricate.Select(v => (JsonNode)JsonValue.Create(v.Id)).ToArray()),
            ["byte_scaricate"] = scaricate.Sum(v => v.Byte),
            ["aggiornamento"] = new JsonObject
            {
                ["attivo"] = AggiornamentiAttivi,
                ["disponibile"] = nuova?.Nome,
                ["scaricamento_in_corso"] = Volatile.Read(ref scaricamentoInCorso) == 1,
                ["scaricato"] = scaricato?.Ok == true ? scaricato.File : null,
                ["errore_scaricamento"] = scaricato?.Ok == false ? scaricato.Errore : null,
            },
        };
    }

    public static string NomeSuggerito()
    {
        var macchina = Environment.MachineName;
        return string.IsNullOrWhiteSpace(macchina) ? "Computer di Andrea" : "Computer " + macchina;
    }

    /// <summary><c>POST /api/abbina</c> con il codice a 8 cifre; il token si salva cifrato con DPAPI, e con lui <c>server_id</c>.</summary>
    public async Task<JsonObject> AbbinaAsync(string? indirizzo, string? codice, string? nome)
    {
        if (!Indirizzo.Normalizza(indirizzo, out var server, out var erroreIndirizzo)) return Errore(erroreIndirizzo!, "indirizzo");
        var cifre = new string((codice ?? "").Where(char.IsAsciiDigit).ToArray());
        if (cifre.Length != 8) return Errore("Il codice ha 8 cifre: controlla di averle scritte tutte.", "codice");
        var nomeDispositivo = string.IsNullOrWhiteSpace(nome) ? NomeSuggerito() : nome.Trim();
        if (nomeDispositivo.Length > 60) nomeDispositivo = nomeDispositivo[..60];

        var corpo = new JsonObject { ["codice"] = cifre, ["nome"] = nomeDispositivo, ["tipo"] = "pc" }.ToJsonString();
        var r = await client.InviaAsync("POST", server, "api/abbina", null, corpo, TempoAbbinamento).ConfigureAwait(false);
        if (ComeSenzaRete(r)) return Errore("Non riesco a raggiungere il server a questo indirizzo. Controlla l'indirizzo e la connessione a internet, poi riprova.", "rete");
        if (r.Stato == 401) return Errore("Il codice non è giusto oppure è scaduto (vale 15 minuti). Creane uno nuovo e riprova.", "codice");
        if (r.Stato == 429) return Errore("Troppi codici sbagliati di fila. Aspetta un quarto d'ora, crea un codice nuovo e riprova.", "codice");
        if (!r.Ok) return Errore(Frasi.PerRisposta(r.Stato, r.Corpo), "server");
        var risposta = Json.Leggi<RispostaAbbina>(r.Corpo);
        if (string.IsNullOrWhiteSpace(risposta?.Token)) return Errore("Il server ha risposto in modo strano: riprova, e se succede ancora va controllato il server.", "server");
        var serverId = risposta!.ServerId != null && ServerIdValido.IsMatch(risposta.ServerId) ? risposta.ServerId.ToLowerInvariant() : await LeggiServerIdAsync(server).ConfigureAwait(false);

        lock (stato)
        {
            // Tema, visita guidata e indirizzo da fuori casa sono di questo computer: restano anche ricollegandolo.
            config = new Configurazione
            {
                Server = server,
                TokenProtetto = Dpapi.Proteggi(risposta.Token!),
                DispositivoId = risposta.DispositivoId,
                NomeDispositivo = nomeDispositivo,
                AbbinatoIl = orologio(),
                ServerId = serverId,
                TutorialVisto = config.TutorialVisto,
                Tema = config.Tema,
                IndirizzoFuori = config.IndirizzoFuori,
            };
            token = risposta.Token;
            casaVerificataFino = InChiaro(server) && serverId != null ? orologio() + DurataVerificaCasa : null;
            primoGiroFatto = false;
            SalvaConfig();
        }
        AdottaFuoriCasaDelServer(risposta.IndirizzoFuoriCasa);
        memoria.Svuota();
        NonRiconosciuto = false;
        ServerRaggiungibile = true;
        Log.Info("computer collegato al server");
        StatoCambiato?.Invoke();
        Sveglia();
        return new JsonObject { ["ok"] = true };
    }

    /// <summary>
    /// Scollega questo computer: prova a revocare il token sul server, poi dimentica collegamento, dati e lezioni
    /// scaricate. Se qualche lezione è ancora in uso e non si toglie, lo dice.
    /// </summary>
    public async Task<JsonObject> ScollegaAsync()
    {
        long? id;
        lock (stato) id = config.DispositivoId;
        bool revocato = false;
        var (base_, tok) = await CollegamentoAsync().ConfigureAwait(false);
        if (base_ != null && tok != null && id != null)
        {
            var r = await client.InviaAsync("DELETE", base_, "api/dispositivi/" + id.Value.ToString(CultureInfo.InvariantCulture), tok, null, TimeSpan.FromSeconds(8)).ConfigureAwait(false);
            revocato = r.Ok || r.Stato is 401 or 404;
        }
        lock (stato)
        {
            config = new Configurazione { TutorialVisto = config.TutorialVisto, Tema = config.Tema, IndirizzoFuori = config.IndirizzoFuori };
            token = null;
            inPreparazione = false;
            casaVerificataFino = null;
            SalvaConfig();
        }
        memoria.Svuota();
        var rimaste = Audio.Svuota();
        NonRiconosciuto = false;
        Log.Info(revocato ? "computer scollegato" : "computer scollegato solo qui: il server non ha confermato");
        StatoCambiato?.Invoke();
        var esito = new JsonObject { ["ok"] = true, ["revocato_sul_server"] = revocato, ["lezioni_rimaste"] = rimaste };
        if (!revocato)
        {
            esito["avviso"] = "Questo computer è scollegato, ma il server di casa non ha risposto: lì risulta ancora collegato. Toglilo dal telefono, nelle impostazioni.";
        }
        else if (rimaste > 0)
        {
            esito["avviso"] = "Scollegato. Alcune lezioni scaricate non si sono tolte perché erano in uso: chiudi Lectio e riaprilo, si toglieranno dopo.";
        }
        return esito;
    }

    /// <summary>
    /// L'indirizzo da fuori casa scritto a mano (§15.5): deve essere https. Vuoto = si torna a quello che manda il server.
    /// </summary>
    public JsonObject ImpostaFuoriCasa(string? indirizzo)
    {
        if (string.IsNullOrWhiteSpace(indirizzo))
        {
            lock (stato)
            {
                config.IndirizzoFuori = null;
                SalvaConfig();
            }
            return new JsonObject { ["ok"] = true, ["indirizzo_fuori"] = IndirizzoFuori, ["indirizzo_fuori_a_mano"] = false };
        }
        if (!Indirizzo.Normalizza(indirizzo, out var base_, out var errore)) return Errore(errore!, "indirizzo");
        if (InChiaro(base_)) return Errore("L'indirizzo da fuori casa deve essere sicuro, cioè cominciare con https.", "indirizzo");
        lock (stato)
        {
            config.IndirizzoFuori = base_;
            SalvaConfig();
        }
        return new JsonObject { ["ok"] = true, ["indirizzo_fuori"] = base_, ["indirizzo_fuori_a_mano"] = true };
    }

    public JsonObject SegnaTutorialVisto()
    {
        lock (stato)
        {
            config.TutorialVisto = true;
            SalvaConfig();
        }
        return new JsonObject { ["ok"] = true };
    }

    /// <summary>Il tema scelto: null = come Windows.</summary>
    public string? TemaScelto
    {
        get { lock (stato) return config.Tema; }
    }

    public JsonObject ImpostaTema(string? tema)
    {
        if (tema is not ("sistema" or "chiaro" or "scuro")) return Errore("Il tema può essere come il sistema, chiaro o scuro.", "non_valido");
        lock (stato)
        {
            config.Tema = tema == "sistema" ? null : tema;
            SalvaConfig();
        }
        TemaCambiato?.Invoke();
        return new JsonObject { ["ok"] = true, ["tema"] = tema };
    }

    // ---------------- Le richieste della pagina ----------------

    /// <summary>
    /// Gira una richiesta della pagina al server, col token. Le letture riuscite si conservano; se il server non
    /// risponde (o risponde Tailscale al posto suo), una lettura riceve l'ultima copia con la sua data. Una modifica
    /// finita col tempo scaduto ha un esito sconosciuto (504, <c>esito_sconosciuto</c>): non si dice "senza rete",
    /// così la pagina controlla prima di rimandarla. Una lezione chiesta accende il giro ogni 20 secondi.
    /// </summary>
    public async Task<RispostaPonte> InoltraAsync(string metodo, string percorso, string? query, string? corpo)
    {
        metodo = metodo.ToUpperInvariant();
        percorso = percorso.TrimStart('/');
        if (!PercorsoAmmesso.IsMatch(percorso) || percorso.Contains("//", StringComparison.Ordinal)
            || percorso.StartsWith("api/abbina", StringComparison.OrdinalIgnoreCase))
        {
            return ErrorePonte(404, Frasi.RichiestaSconosciuta, "non_trovato");
        }
        if (metodo is not ("GET" or "POST" or "PUT" or "PATCH" or "DELETE")) return ErrorePonte(405, Frasi.RichiestaSconosciuta, "non_valido");
        if (!Abbinato) return ErrorePonte(401, Frasi.NonCollegato, "non_abbinato");

        var completo = percorso + (string.IsNullOrEmpty(query) || query == "?" ? "" : (query.StartsWith('?') ? query : "?" + query));
        var chiave = "GET " + completo;
        var (base_, tok) = await CollegamentoAsync().ConfigureAwait(false);
        Risposta r;
        if (base_ == null || tok == null)
        {
            r = Risposta.SenzaRete;
        }
        else
        {
            // GET senza corpo; DELETE col corpo solo se la pagina lo manda (§4: {"conferma":true}); il resto sempre JSON.
            var corpoDaMandare = metodo switch
            {
                "GET" => null,
                "DELETE" => string.IsNullOrWhiteSpace(corpo) ? null : corpo,
                _ => string.IsNullOrWhiteSpace(corpo) ? "{}" : corpo,
            };
            r = await client.InviaAsync(metodo, base_, completo, tok, corpoDaMandare, TempoForzato ?? TempoPer(metodo, percorso)).ConfigureAwait(false);
            Annota(r, base_);
        }
        if (r.Scaduta && metodo != "GET")
        {
            return ErrorePonte(504, "Il server ci sta mettendo troppo e non so se ha ricevuto la richiesta. Controllo prima di riprovare.", "esito_sconosciuto");
        }
        if (ComeSenzaRete(r))
        {
            if (metodo == "GET" && memoria.Leggi(chiave) is Memoria.Voce voce) return new RispostaPonte(200, voce.Corpo, voce.SalvataIl);
            return ErrorePonte(503, Frasi.ServerIrraggiungibile, "rete");
        }
        if (r.Ok)
        {
            var testo = string.IsNullOrWhiteSpace(r.Corpo) ? "{}" : r.Corpo;
            if (metodo == "GET") memoria.Salva(chiave, testo, orologio());
            // §15.5: ogni salute col token porta l'indirizzo da fuori casa scelto sul server (se Andrea non ne ha scritto uno).
            if (metodo == "GET" && percorso == "api/salute" && Json.Nodo(testo) is System.Text.Json.Nodes.JsonObject salute && salute.ContainsKey("indirizzo_fuori_casa"))
            {
                AdottaFuoriCasaDelServer(Json.Testo(salute["indirizzo_fuori_casa"]));
            }
            if (metodo == "GET" && completo == "api/lezioni" && Json.Leggi<List<LezioneBreve>>(testo) is List<LezioneBreve> lezioni)
            {
                Audio.TogliAssenti(lezioni.Select(x => x.Id));
            }
            if (metodo == "POST" && (PercorsoNuovaLezione.IsMatch(percorso) || PercorsoRiprovaLezione.IsMatch(percorso)))
            {
                ImpostaPreparazione(true);
                Sveglia();
            }
            return new RispostaPonte(r.Stato, testo);
        }
        return new RispostaPonte(r.Stato, ErroreLeggibile(r).ToJsonString());
    }

    /// <summary>
    /// Un file trascinato o scelto nella pagina → <c>POST /api/materie/{id}/materiale</c> in multipart (§6).
    /// La pagina manda i byte; il programma controlla formato e dimensione prima di spedire.
    /// </summary>
    public async Task<RispostaPonte> CaricaFileAsync(long? materiaId, string? tipo, string? titolo, string? data, string? nomeFile, byte[]? dati)
    {
        if (!Abbinato) return ErrorePonte(401, Frasi.NonCollegato, "non_abbinato");
        if (materiaId is not long id || id <= 0) return ErrorePonte(422, "Scegli prima la materia.", "non_valido");
        if (dati == null || dati.Length == 0) return ErrorePonte(422, "Il file è vuoto oppure non è arrivato al programma. Riprova.", "non_valido");
        if (dati.LongLength > MassimoByteFile) return ErrorePonte(413, "Il file è troppo grande: il massimo è 20 MB.", "troppo_grande");
        var nome = Path.GetFileName((nomeFile ?? "").Trim());
        var estensione = Path.GetExtension(nome).ToLowerInvariant();
        if (nome.Length == 0 || !Estensioni.Contains(estensione))
        {
            return ErrorePonte(422, "Questo tipo di file non lo leggo. Usa PDF, Word, ODT, RTF, testo o una foto JPG, PNG o WEBP.", "non_valido");
        }
        if (tipo != null && !TipiMateriale.Contains(tipo)) return ErrorePonte(422, "Scegli che cos'è: verifica, interrogazione, appunti o libro.", "non_valido");
        var (base_, tok) = await CollegamentoAsync().ConfigureAwait(false);
        if (base_ == null || tok == null) return ErrorePonte(503, "Il file non è arrivato al server di casa. Controlla la connessione e riprova.", "rete");

        var multipart = new MultipartFormDataContent();
        var file = new ByteArrayContent(dati);
        file.Headers.ContentType = new MediaTypeHeaderValue(TipoFile(estensione));
        multipart.Add(file, "file", nome);
        if (!string.IsNullOrWhiteSpace(tipo)) multipart.Add(new StringContent(tipo), "tipo");
        if (!string.IsNullOrWhiteSpace(titolo)) multipart.Add(new StringContent(titolo.Trim()), "titolo");
        if (!string.IsNullOrWhiteSpace(data)) multipart.Add(new StringContent(data.Trim()), "data");

        var percorso = "api/materie/" + id.ToString(CultureInfo.InvariantCulture) + "/materiale";
        var r = await client.InviaContenutoAsync("POST", base_, percorso, tok, multipart, TempoForzato ?? TempoCaricamento).ConfigureAwait(false);
        Annota(r, base_);
        if (r.Scaduta) return ErrorePonte(504, "Il server ci sta mettendo troppo: guarda tra poco nel materiale se il file è arrivato prima di rimandarlo.", "esito_sconosciuto");
        if (ComeSenzaRete(r)) return ErrorePonte(503, "Il file non è arrivato al server di casa. Controlla la connessione e riprova.", "rete");
        if (r.Ok) return new RispostaPonte(r.Stato, string.IsNullOrWhiteSpace(r.Corpo) ? "{}" : r.Corpo);
        return new RispostaPonte(r.Stato, ErroreLeggibile(r).ToJsonString());
    }

    public static string TipoFile(string estensione) => estensione switch
    {
        ".txt" => "text/plain",
        ".md" => "text/markdown",
        ".pdf" => "application/pdf",
        ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".odt" => "application/vnd.oasis.opendocument.text",
        ".rtf" => "application/rtf",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".webp" => "image/webp",
        _ => "application/octet-stream",
    };

    // ---------------- Audio delle lezioni ----------------

    /// <summary>
    /// L'audio di una lezione sul computer, pronto per il lettore: legge la lezione (e la tiene per quando non c'è
    /// rete), scarica l'MP3 col token se manca e restituisce l'indirizzo nella cartella virtuale. Senza rete (o con
    /// Tailscale che risponde al posto del server) va bene l'audio già scaricato.
    /// </summary>
    public async Task<RispostaPonte> ScaricaLezioneAsync(long id)
    {
        if (!Abbinato) return ErrorePonte(401, Frasi.NonCollegato, "non_abbinato");
        var percorso = "api/lezioni/" + id.ToString(CultureInfo.InvariantCulture);
        var (base_, tok) = await CollegamentoAsync().ConfigureAwait(false);
        var r = base_ == null || tok == null ? Risposta.SenzaRete : await client.InviaAsync("GET", base_, percorso, tok, null).ConfigureAwait(false);
        if (base_ != null) Annota(r, base_);
        if (ComeSenzaRete(r))
        {
            if (Audio.Di(id) is VoceCache gia) return Ok(gia.Nome, gia.Byte);
            return ErrorePonte(503, "Senza rete puoi ascoltare solo le lezioni già scaricate sul computer.", "rete");
        }
        if (!r.Ok) return new RispostaPonte(r.Stato, ErroreLeggibile(r).ToJsonString());
        memoria.Salva("GET " + percorso, r.Corpo, orologio());
        var lezione = Json.Leggi<LezioneBreve>(r.Corpo);
        if (lezione == null) return ErrorePonte(502, "Il server ha risposto in modo strano. Riprova tra poco.", "errore");
        if (lezione.Stato != "pronta") return ErrorePonte(409, "Questa lezione non è ancora pronta da ascoltare.", "conflitto");
        var esito = await Audio.ScaricaAsync(base_!, tok!, id, lezione.Audio).ConfigureAwait(false);
        if (!esito.Ok)
        {
            // L'audio non arriva (rete caduta a metà, Tailscale senza server) ma ce n'è una copia: si ascolta quella.
            if (esito.Stato is 502 or 503 or 504 && Audio.Di(id) is VoceCache vecchia) return Ok(vecchia.Nome, vecchia.Byte);
            return ErrorePonte(esito.Stato, esito.Errore ?? "Non riesco a scaricare la lezione. Riprova.", esito.Stato == 503 ? "rete" : "errore");
        }
        return Ok(esito.Nome!, esito.Byte);

        static RispostaPonte Ok(string nome, long byteFile) =>
            new(200, new JsonObject { ["ok"] = true, ["url"] = CacheAudio.Url(nome), ["byte"] = byteFile }.ToJsonString());
    }

    public JsonObject StatoScaricamento(long id)
    {
        var parte = Audio.Avanzamento(id);
        var gia = Audio.Di(id);
        return new JsonObject
        {
            ["in_corso"] = parte != null,
            ["percentuale"] = parte is double p ? (int)Math.Round(p * 100) : gia != null ? 100 : 0,
            ["scaricata"] = gia != null,
        };
    }

    public JsonObject Scaricate() => new()
    {
        ["lezioni"] = new JsonArray(Audio.Elenco().Select(v => (JsonNode)new JsonObject { ["id"] = v.Id, ["byte"] = v.Byte }).ToArray()),
        ["byte"] = Audio.Totale(),
    };

    public JsonObject TogliLezione(long id) => Audio.Togli(id)
        ? new JsonObject { ["ok"] = true }
        : Errore("Non riesco a toglierla adesso: chiudi la lezione nel lettore e riprova.", "in_uso");

    // ---------------- Aggiornamento ----------------

    /// <summary>Scarica in sottofondo lo zip della versione nuova; a fine lavoro <see cref="ScaricamentoFinito"/>.</summary>
    public bool AvviaScaricamento()
    {
        if (Aggiornatore.Disponibile == null) return false;
        if (Interlocked.Exchange(ref scaricamentoInCorso, 1) == 1) return false;
        ultimoScaricamento = null;
        _ = Task.Run(async () =>
        {
            EsitoScaricamento esito;
            try
            {
                esito = await Aggiornatore.ScaricaAsync(CartellaDownload).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                Log.Errore("scaricamento della versione nuova", e);
                esito = new EsitoScaricamento(false, null, "Lo scaricamento non è riuscito. Riprova più tardi.");
            }
            ultimoScaricamento = esito;
            Interlocked.Exchange(ref scaricamentoInCorso, 0);
            ScaricamentoFinito?.Invoke(esito);
        });
        return true;
    }

    // ---------------- Aiuti ----------------

    /// <summary>Gli errori arrivano alla pagina sempre nella stessa forma: la frase (mai un codice HTTP) e il <c>codice</c>.</summary>
    private static JsonObject ErroreLeggibile(Risposta r)
    {
        var codice = Json.Leggi<ErroreServer>(r.Corpo.TrimStart().StartsWith('{') ? r.Corpo : null)?.Codice;
        var o = new JsonObject { ["errore"] = Frasi.PerRisposta(r.Stato, r.Corpo), ["codice"] = codice ?? "errore" };
        if (Json.Nodo(r.Corpo) is JsonObject originale)
        {
            foreach (var (k, v) in originale)
            {
                if (k is "errore" or "codice") continue;
                o[k] = v?.DeepClone();
            }
        }
        return o;
    }

    private static JsonObject Errore(string frase, string codice) => new() { ["errore"] = frase, ["codice"] = codice };

    private static RispostaPonte ErrorePonte(int stato, string frase, string codice) => new(stato, Errore(frase, codice).ToJsonString());

    private void SalvaConfig()
    {
        try
        {
            Archivio.ScriviJson(percorsi.Config, config);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Log.Errore("configurazione non salvata", e);
        }
    }

    public void Dispose()
    {
        try
        {
            fine?.Cancel();
        }
        catch (ObjectDisposedException)
        {
        }
        fine?.Dispose();
        if (clientNostro) client.Dispose();
    }
}
