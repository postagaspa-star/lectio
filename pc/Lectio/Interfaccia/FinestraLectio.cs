using System.Drawing;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;
using Lectio.Motore;
using Lectio.Sistema;

namespace Lectio.Interfaccia;

/// <summary>
/// La finestra di Lectio: una WebView2 che mostra l'interfaccia HTML da <c>https://lectio.locale/</c> (cartella
/// <c>ui</c>) e ascolta gli MP3 da <c>https://audio.lectio.locale/</c> (cartella delle lezioni scaricate, mappata
/// da WebView2). Non può andare altrove. Chiusa, si libera del tutto.
/// </summary>
public sealed class FinestraLectio : Form
{
    private static readonly Regex FrammentoLezione = new(@"^lezione/\d{1,12}$", RegexOptions.CultureInvariant);

    /// <summary>Le pagine che si possono aprire da fuori (clic su un avviso di Windows).</summary>
    private static readonly HashSet<string> Sezioni = new(StringComparer.Ordinal) { "oggi", "materie", "lezioni", "gemini", "impostazioni" };

    private readonly WebView2 vista;
    private readonly Ponte ponte;
    private readonly string cartellaUi;
    private readonly string cartellaDatiWebView;
    private readonly string cartellaAudio;
    private readonly bool fuoriSchermo;
    private CoreWebView2Environment? ambiente;
    private string? frammentoDaAprire;
    private bool chiusuraConfermata;
    private bool chiusuraInCorso;

    public FinestraLectio(Ponte ponte, string cartellaUi, string cartellaDatiWebView, string cartellaAudio, Icon icona, string? frammento = null, bool fuoriSchermo = false)
    {
        this.ponte = ponte;
        this.cartellaUi = cartellaUi;
        this.cartellaDatiWebView = cartellaDatiWebView;
        this.cartellaAudio = cartellaAudio;
        this.fuoriSchermo = fuoriSchermo;
        frammentoDaAprire = frammento;

        Text = "Lectio";
        Icon = icona;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScaleDimensions = new SizeF(96F, 96F);
        ClientSize = new Size(1280, 820);
        MinimumSize = new Size(900, 600);
        if (fuoriSchermo)
        {
            // --diagnosi: la finestra c'è (la pagina carica come sempre) ma non si vede e non sta nella barra.
            StartPosition = FormStartPosition.Manual;
            Location = new Point(-32000, -32000);
            ShowInTaskbar = false;
        }
        else
        {
            StartPosition = FormStartPosition.CenterScreen;
        }
        var scuro = Tema.ScuroPer(ponte.TemaScelto, Tema.Scuro());
        BackColor = Tema.Fondo(scuro);

        vista = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = BackColor };
        Controls.Add(vista);
        Load += async (_, _) => await InizializzaAsync();
        HandleCreated += (_, _) => Tema.BarraDelTitolo(Handle, Tema.ScuroPer(ponte.TemaScelto, Tema.Scuro()));
        SystemEvents.UserPreferenceChanged += SuCambioTema;
        ponte.TemaCambiato += SuTemaScelto;
        ponte.Copia = CopiaNegliAppunti;
        FormClosed += (_, _) =>
        {
            SystemEvents.UserPreferenceChanged -= SuCambioTema;
            ponte.TemaCambiato -= SuTemaScelto;
            ponte.Copia = null;
        };
    }

    private void SuTemaScelto() => SuCambioTema(this, new UserPreferenceChangedEventArgs(UserPreferenceCategory.General));

    private void SuCambioTema(object? mittente, UserPreferenceChangedEventArgs e)
    {
        if (e.Category != UserPreferenceCategory.General || IsDisposed || !IsHandleCreated) return;
        try
        {
            BeginInvoke(() =>
            {
                if (IsDisposed) return;
                var scuro = Tema.ScuroPer(ponte.TemaScelto, Tema.Scuro());
                BackColor = Tema.Fondo(scuro);
                Tema.BarraDelTitolo(Handle, scuro);
            });
        }
        catch (InvalidOperationException)
        {
        }
    }

    /// <summary>
    /// Copia negli appunti di Windows sul filo della finestra (gli appunti vogliono quello). Se un altro programma
    /// li tiene occupati si riprova qualche volta; se non va, false: la pagina lo dice.
    /// </summary>
    private bool CopiaNegliAppunti(string testo)
    {
        if (IsDisposed || !IsHandleCreated) return false;
        bool ok = false;
        void Fai()
        {
            try
            {
                Clipboard.SetDataObject(testo, true, 5, 100);
                ok = true;
            }
            catch (Exception e) when (e is System.Runtime.InteropServices.ExternalException or ThreadStateException)
            {
                Log.Errore("appunti non scritti", e);
            }
        }
        try
        {
            if (InvokeRequired) Invoke(Fai);
            else Fai();
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
            return false;
        }
        return ok;
    }

    internal static bool FrammentoValido(string? f) =>
        !string.IsNullOrEmpty(f) && (Sezioni.Contains(f) || FrammentoLezione.IsMatch(f));

    /// <summary>L'indirizzo iniziale: solo frammenti controllati qui (pagine note o lezione/numero).</summary>
    public static string IndirizzoIniziale(string? frammento) =>
        Ponte.Origine + "/index.html" + (FrammentoValido(frammento) ? "#" + frammento : "");

    /// <summary>Porta la finestra su una pagina o una lezione (clic su un avviso). Se la pagina non c'è ancora, appena è pronta.</summary>
    public void Mostra(string? frammento)
    {
        if (!FrammentoValido(frammento)) return;
        var core = vista.CoreWebView2;
        if (core == null)
        {
            frammentoDaAprire = frammento;
            return;
        }
        Esegui("window.lectioApri ? window.lectioApri('" + frammento + "') : (location.hash = '#" + frammento + "');");
    }

    /// <summary>Le novità del server (§11) alla pagina, che aggiorna quello che mostra. <paramref name="json"/> viene dal nostro serializzatore.</summary>
    public void MandaNovita(string json) => Esegui("window.lectioNovita && window.lectioNovita(" + json + ");");

    /// <summary>Un tasto multimediale della tastiera: "play", "avanti", "indietro".</summary>
    public void Tasto(string tasto)
    {
        if (tasto is not ("play" or "avanti" or "indietro")) return;
        Esegui("window.lectioTasto && window.lectioTasto('" + tasto + "');");
    }

    private void Esegui(string script)
    {
        var core = vista.CoreWebView2;
        if (core == null || IsDisposed) return;
        try
        {
            _ = core.ExecuteScriptAsync(script);
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException or System.Runtime.InteropServices.COMException)
        {
            Log.Errore("script della finestra non eseguito", e);
        }
    }

    /// <summary>Chiudendo la finestra la pagina salva prima sul server il punto della lezione (al massimo 4 secondi).</summary>
    protected override async void OnFormClosing(FormClosingEventArgs e)
    {
        if (chiusuraConfermata || e.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing)
        {
            base.OnFormClosing(e);
            return;
        }
        e.Cancel = true;
        if (chiusuraInCorso) return;
        await PreparaChiusuraAsync();
        chiusuraConfermata = true;
        Close();
    }

    /// <summary>Chiede alla pagina di salvare il punto della lezione e aspetta (al massimo 4 secondi).</summary>
    public async Task PreparaChiusuraAsync()
    {
        var core = vista.CoreWebView2;
        if (core == null) return;
        chiusuraInCorso = true;
        try
        {
            var stato = await core.ExecuteScriptAsync("window.lectioPrimaDiChiudere ? window.lectioPrimaDiChiudere() : 'pulito'");
            for (int i = 0; i < 20 && stato.Contains("in_corso", StringComparison.Ordinal); i++)
            {
                await Task.Delay(200);
                stato = await core.ExecuteScriptAsync("window.lectioStatoChiusura ? window.lectioStatoChiusura() : 'pulito'");
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or ObjectDisposedException or System.Runtime.InteropServices.COMException)
        {
            Log.Errore("salvataggio prima della chiusura", ex);
        }
        finally
        {
            chiusuraInCorso = false;
        }
    }

    public void ChiudiSenzaChiedere()
    {
        chiusuraConfermata = true;
        Close();
    }

    private async Task InizializzaAsync()
    {
        try
        {
            ambiente = await CoreWebView2Environment.CreateAsync(null, cartellaDatiWebView);
            await vista.EnsureCoreWebView2Async(ambiente);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MostraMessaggio("Per aprire Lectio serve Microsoft Edge WebView2, che su Windows 11 c'è sempre. Prova ad aggiornare Windows.");
            Log.Errore("WebView2 non trovata");
            return;
        }
        catch (Exception e)
        {
            MostraMessaggio("La finestra di Lectio non si è aperta. Chiudi il programma e riaprilo.");
            Log.Errore("WebView2 non avviata", e);
            return;
        }

        var core = vista.CoreWebView2;
        await SvuotaCacheSeCambiataAsync(core);
        core.Settings.AreDevToolsEnabled = false;
        // Niente menu del tasto destro (Ricarica, Indietro…) e niente F5/Ctrl+R: ricaricare la pagina lascerebbe il
        // lettore a metà. Copia e incolla da tastiera nei campi restano (Ctrl+C, Ctrl+V).
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.AreBrowserAcceleratorKeysEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.IsWebMessageEnabled = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.Settings.IsZoomControlEnabled = true;

        // Gli MP3 delle lezioni: una cartella virtuale, così il lettore li legge (anche a pezzi, per saltare)
        // senza che il token passi mai dalla pagina. Sola lettura, solo quella cartella.
        Directory.CreateDirectory(cartellaAudio);
        core.SetVirtualHostNameToFolderMapping(CacheAudio.Host, cartellaAudio, CoreWebView2HostResourceAccessKind.DenyCors);

        // La pagina e le sue richieste: tutto https://lectio.locale/ passa da qui (/locale e /server al ponte, il resto è la cartella ui).
        core.AddWebResourceRequestedFilter(Ponte.Origine + "/*", CoreWebView2WebResourceContext.All);
        core.WebResourceRequested += SuRichiesta;
        core.NavigationStarting += SuNavigazione;
        core.FrameNavigationStarting += SuNavigazione;
        core.NewWindowRequested += SuNuovaFinestra;
        core.Navigate(IndirizzoIniziale(frammentoDaAprire));
        frammentoDaAprire = null;
    }

    /// <summary>Dopo un aggiornamento la WebView potrebbe servire i file vecchi dalla cache: se l'interfaccia è cambiata, la si svuota.</summary>
    private async Task SvuotaCacheSeCambiataAsync(CoreWebView2 core)
    {
        var fileImpronta = Path.Combine(cartellaDatiWebView, "impronta-ui.txt");
        var impronta = ImprontaUi(cartellaUi);
        string? precedente = null;
        try
        {
            if (File.Exists(fileImpronta)) precedente = File.ReadAllText(fileImpronta);
        }
        catch (IOException)
        {
        }
        if (precedente == impronta) return;
        try
        {
            await core.Profile.ClearBrowsingDataAsync(
                CoreWebView2BrowsingDataKinds.DiskCache | CoreWebView2BrowsingDataKinds.CacheStorage | CoreWebView2BrowsingDataKinds.ServiceWorkers);
            Nucleo.Archivio.ScriviTesto(fileImpronta, impronta);
        }
        catch (Exception e)
        {
            Log.Errore("cache della finestra non svuotata", e);
        }
    }

    public static string ImprontaUi(string cartella)
    {
        var testo = new StringBuilder(Versione.Nome);
        if (Directory.Exists(cartella))
        {
            foreach (var f in Directory.EnumerateFiles(cartella, "*", SearchOption.AllDirectories).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var info = new FileInfo(f);
                testo.Append('|').Append(Path.GetRelativePath(cartella, f)).Append(':').Append(info.Length).Append(':').Append(info.LastWriteTimeUtc.Ticks);
            }
        }
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(testo.ToString())));
    }

    private async void SuRichiesta(object? mittente, CoreWebView2WebResourceRequestedEventArgs e)
    {
        var rinvio = e.GetDeferral();
        try
        {
            var metodo = e.Request.Method;
            var uri = new Uri(e.Request.Uri);
            if (!Ponte.ÈApi(uri))
            {
                e.Response = FileInterfaccia(metodo, uri);
                return;
            }
            byte[]? corpo = null;
            if (e.Request.Content != null)
            {
                using var copia = new MemoryStream();
                await e.Request.Content.CopyToAsync(copia);
                corpo = copia.ToArray();
            }
            var risposta = await ponte.GestisciAsync(metodo, uri, corpo);
            e.Response = Risposta(risposta);
        }
        catch (Exception ex)
        {
            Log.Errore("richiesta dell'interfaccia", ex);
            e.Response = Risposta(new RispostaPonte(500, "{\"errore\":\"Qualcosa non ha funzionato nel programma. Riprova.\"}"));
        }
        finally
        {
            rinvio.Complete();
        }
    }

    private CoreWebView2WebResourceResponse FileInterfaccia(string metodo, Uri uri)
    {
        var file = metodo is "GET" or "HEAD" ? Ponte.FileDellInterfaccia(cartellaUi, uri.AbsolutePath) : null;
        if (file == null || !File.Exists(file))
        {
            return ambiente!.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes("non trovato")), 404, "Not Found",
                "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store");
        }
        return ambiente!.CreateWebResourceResponse(new MemoryStream(File.ReadAllBytes(file)), 200, "OK",
            $"Content-Type: {Ponte.TipoContenuto(file)}\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff");
    }

    private CoreWebView2WebResourceResponse Risposta(RispostaPonte r)
    {
        var intestazioni = "Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store";
        // Gli ultimi dati visti (server irraggiungibile): la pagina lo dice.
        if (r.DaMemoriaDel is DateTimeOffset quando) intestazioni += "\r\nX-Lectio-Memoria: " + quando.ToString("o", CultureInfo.InvariantCulture);
        return ambiente!.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes(r.Json)), r.Stato, r.Stato < 400 ? "OK" : "Error", intestazioni);
    }

    private void SuNavigazione(object? mittente, CoreWebView2NavigationStartingEventArgs e)
    {
        if (Ponte.ÈInterno(e.Uri)) return;
        e.Cancel = true;
        if (Ponte.ApribileFuori(e.Uri)) ContestoLectio.ApriNelBrowser(e.Uri);
    }

    private void SuNuovaFinestra(object? mittente, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        if (Ponte.ApribileFuori(e.Uri)) ContestoLectio.ApriNelBrowser(e.Uri);
    }

    private void MostraMessaggio(string testo)
    {
        Controls.Clear();
        Controls.Add(new Label
        {
            Text = testo,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 11F),
            Padding = new Padding(40),
        });
    }
}
