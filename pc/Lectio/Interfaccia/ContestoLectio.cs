using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using Lectio.Nucleo;
using Lectio.Sistema;
using Log = Lectio.Motore.Log;
using MotoreLectio = Lectio.Motore.Motore;
using Notifica = Lectio.Motore.Notifica;

namespace Lectio.Interfaccia;

/// <summary>
/// Il programma quando gira: la finestra, l'icona vicino all'orologio, gli avvisi di Windows e i tasti multimediali.
/// Chiusa la finestra, Lectio si chiude; se però c'è una lezione in preparazione resta vicino all'orologio
/// finché non è pronta, per dire "La lezione di Storia è pronta" (§6: "Puoi uscire: ti avviso io").
/// </summary>
public sealed class ContestoLectio : ApplicationContext
{
    private static readonly TimeSpan IntervalloAvvisi = TimeSpan.FromSeconds(6);

    private readonly MotoreLectio motore;
    private readonly Ponte ponte;
    private readonly Opzioni opzioni;
    private readonly NotifyIcon icona;
    private readonly Icon iconaBase;
    private readonly Control invocatore;
    private readonly TastiMultimediali tasti;
    private readonly Queue<Notifica> coda = new();
    private readonly System.Windows.Forms.Timer timerAvvisi;
    private FinestraLectio? finestra;
    private Notifica? ultimaMostrata;
    private bool avvisatoCheResta;
    private bool uscito;

    /// <summary>Windows si sta spegnendo (o si esce dall'account): la versione nuova si installa al prossimo avvio.</summary>
    public bool FineSessione { get; private set; }

    /// <summary>"Aggiorna adesso" nelle Impostazioni: dopo l'installazione Lectio si riapre.</summary>
    public bool RiapriDopo { get; private set; }

    public ContestoLectio(MotoreLectio motore, Opzioni opzioni, Istanza istanza)
    {
        this.motore = motore;
        this.opzioni = opzioni;
        ponte = new Ponte(motore) { ApriNelBrowser = ApriNelBrowser };

        invocatore = new Control();
        invocatore.CreateControl();

        tasti = new TastiMultimediali();
        tasti.Premuto += t => finestra?.Tasto(t);
        ponte.Tasti = attivi => SulFiloGrafico(() =>
        {
            if (attivi && finestra != null) tasti.Attiva();
            else tasti.Disattiva();
        });

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("Apri Lectio", null, (_, _) => Apri(null)) { Font = new Font(SystemFonts.MenuFont ?? Control.DefaultFont, FontStyle.Bold) });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("Chiudi Lectio", null, async (_, _) => await EsciAsync()));

        iconaBase = IconaVassoio.Base(SystemInformation.SmallIconSize);
        icona = new NotifyIcon { Icon = iconaBase, Text = IconaVassoio.Suggerimento(false), ContextMenuStrip = menu, Visible = true };
        icona.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) Apri(null);
        };
        icona.BalloonTipClicked += (_, _) => SuClicAvviso();

        timerAvvisi = new System.Windows.Forms.Timer { Interval = (int)IntervalloAvvisi.TotalMilliseconds };
        timerAvvisi.Tick += (_, _) => ProssimoAvviso();

        motore.NuovaNotifica += n => SulFiloGrafico(() => Accoda(n));
        motore.Novita += json => SulFiloGrafico(() => finestra?.MandaNovita(json));
        motore.StatoCambiato += () => SulFiloGrafico(SuStatoCambiato);
        // Gli aggiornamenti si scaricano e si installano da soli (Program.Main, alla chiusura): qui solo il bottone
        // "Aggiorna adesso" e, al primo avvio dopo, l'avviso che è andata.
        ponte.AggiornaAdesso = () => SulFiloGrafico(() =>
        {
            RiapriDopo = true;
            _ = EsciAsync();
        });
        Microsoft.Win32.SystemEvents.SessionEnding += SuFineSessione;

        istanza.RichiestaApertura += () => SulFiloGrafico(() => Apri(null));
        istanza.SvuotaAperturaInSospeso();

        motore.Avvia();
        Apri(null);
        if (motore.AppenaAggiornato is string versione) Accoda(new Notifica("Lectio si è aggiornato", $"Adesso hai la versione {versione}."));

        if (opzioni.EsciDopoSecondi is int secondi)
        {
            var t = new System.Windows.Forms.Timer { Interval = secondi * 1000 };
            t.Tick += (_, _) =>
            {
                t.Stop();
                Esci();
            };
            t.Start();
        }
    }

    /// <summary>Apre un indirizzo nel browser predefinito di Windows (solo Gemini, già controllato dal ponte).</summary>
    public static void ApriNelBrowser(string indirizzo)
    {
        if (!Ponte.ApribileFuori(indirizzo)) return;
        try
        {
            Process.Start(new ProcessStartInfo(new Uri(indirizzo).AbsoluteUri) { UseShellExecute = true })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Errore("indirizzo esterno non aperto", e);
        }
    }

    private bool SulFiloGrafico(Action azione)
    {
        if (uscito || invocatore.IsDisposed) return false;
        try
        {
            invocatore.BeginInvoke(azione);
            return true;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    private void Apri(string? frammento)
    {
        if (uscito) return;
        if (finestra == null || finestra.IsDisposed)
        {
            finestra = new FinestraLectio(ponte, Path.Combine(AppContext.BaseDirectory, "ui"), Path.Combine(opzioni.CartellaDati, "WebView2"),
                motore.Audio.Cartella, IconaVassoio.Base(), frammento, fuoriSchermo: opzioni.Diagnosi);
            finestra.FormClosed += (_, _) => SuFinestraChiusa();
            finestra.Show();
        }
        else if (frammento != null)
        {
            finestra.Mostra(frammento);
        }
        if (finestra.WindowState == FormWindowState.Minimized) finestra.WindowState = FormWindowState.Normal;
        if (!opzioni.Diagnosi) finestra.Activate();
        // §11: all'apertura si guarda se ci sono novità.
        motore.Sveglia();
    }

    private void SuFinestraChiusa()
    {
        finestra = null;
        tasti.Disattiva();
        if (uscito) return;
        if (motore.InPreparazione && !avvisatoCheResta)
        {
            avvisatoCheResta = true;
            Accoda(new Notifica("Lectio resta qui vicino all'orologio", "Ti avviso quando la lezione è pronta."));
        }
        ForseEsci();
    }

    private void SuStatoCambiato()
    {
        if (uscito) return;
        icona.Text = IconaVassoio.Suggerimento(motore.InPreparazione);
        ForseEsci();
    }

    /// <summary>
    /// Lectio resta vicino all'orologio solo finché serve: con la finestra chiusa, nessuna lezione in preparazione e
    /// tutti gli avvisi già mostrati, si chiude da solo.
    /// </summary>
    internal static bool DeveUscire(bool finestraAperta, bool inPreparazione, int avvisiInCoda, bool avvisoSulloSchermo) =>
        !finestraAperta && !inPreparazione && avvisiInCoda == 0 && !avvisoSulloSchermo;

    private void ForseEsci()
    {
        if (uscito) return;
        if (DeveUscire(finestra != null && !finestra.IsDisposed, motore.InPreparazione, coda.Count, timerAvvisi.Enabled)) Esci();
    }

    // ---------------- Avvisi di Windows ----------------

    private void Accoda(Notifica n)
    {
        if (uscito) return;
        coda.Enqueue(n);
        if (!timerAvvisi.Enabled) ProssimoAvviso();
    }

    private void ProssimoAvviso()
    {
        if (coda.Count == 0)
        {
            timerAvvisi.Stop();
            ForseEsci();
            return;
        }
        var n = coda.Dequeue();
        ultimaMostrata = n;
        icona.ShowBalloonTip((int)IntervalloAvvisi.TotalMilliseconds, Frasi.Accorcia(n.Titolo, 60), Frasi.Accorcia(n.Testo, 200), ToolTipIcon.None);
        timerAvvisi.Start();
    }

    private void SuClicAvviso()
    {
        Apri(ultimaMostrata?.Frammento);
    }

    private void SuFineSessione(object? mittente, Microsoft.Win32.SessionEndingEventArgs e) => FineSessione = true;

    // ---------------- Chiusura ----------------

    private async Task EsciAsync()
    {
        if (uscito) return;
        if (finestra != null && !finestra.IsDisposed) await finestra.PreparaChiusuraAsync();
        Esci();
    }

    private void Esci()
    {
        if (uscito) return;
        uscito = true;
        Log.Info("Lectio si chiude");
        Microsoft.Win32.SystemEvents.SessionEnding -= SuFineSessione;
        timerAvvisi.Stop();
        finestra?.ChiudiSenzaChiedere();
        tasti.Dispose();
        icona.Visible = false;
        icona.Dispose();
        iconaBase.Dispose();
        motore.Dispose();
        ExitThread();
    }
}
