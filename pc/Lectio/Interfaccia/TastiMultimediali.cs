using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace Lectio.Interfaccia;

/// <summary>
/// I tasti multimediali della tastiera (play/pausa, brano avanti, brano indietro) per il lettore, anche con la
/// finestra in secondo piano. Si accendono solo mentre c'è una lezione nel lettore e si spengono quando la si
/// chiude: con <c>RegisterHotKey</c>, niente permessi speciali. Se un altro programma li ha già presi, non si
/// accendono e basta (restano la barra spaziatrice e i pulsanti).
/// </summary>
public sealed class TastiMultimediali : NativeWindow, IDisposable
{
    private const int WmHotkey = 0x0312;
    private const uint ModNoRepeat = 0x4000;

    /// <summary>Codice virtuale del tasto → cosa fa nel lettore.</summary>
    internal static readonly (int Id, uint Tasto, string Azione)[] Tasti =
    {
        (1, 0xB3, "play"),     // VK_MEDIA_PLAY_PAUSE
        (2, 0xB0, "avanti"),   // VK_MEDIA_NEXT_TRACK
        (3, 0xB1, "indietro"), // VK_MEDIA_PREV_TRACK
    };

    private readonly HashSet<int> registrati = new();

    public TastiMultimediali()
    {
        CreateHandle(new CreateParams());
    }

    public event Action<string>? Premuto;

    public bool Attivi => registrati.Count > 0;

    public void Attiva()
    {
        if (Handle == IntPtr.Zero) return;
        foreach (var (id, tasto, _) in Tasti)
        {
            if (registrati.Contains(id)) continue;
            try
            {
                if (RegisterHotKey(Handle, id, ModNoRepeat, tasto)) registrati.Add(id);
            }
            catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
            {
                return;
            }
        }
    }

    public void Disattiva()
    {
        if (Handle == IntPtr.Zero) return;
        foreach (var id in registrati.ToList())
        {
            try
            {
                UnregisterHotKey(Handle, id);
            }
            catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
            {
            }
            registrati.Remove(id);
        }
    }

    internal static string? AzionePer(int id) => Tasti.Where(t => t.Id == id).Select(t => t.Azione).FirstOrDefault();

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmHotkey && AzionePer(m.WParam.ToInt32()) is string azione)
        {
            Premuto?.Invoke(azione);
            return;
        }
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        Disattiva();
        DestroyHandle();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
