using System.Drawing;

namespace Lectio.Interfaccia;

/// <summary>L'icona di Lectio (il simbolo di docs/aspetto.md): per la finestra e vicino all'orologio.</summary>
public static class IconaVassoio
{
    public static Icon Base(Size? dimensione = null)
    {
        using var flusso = typeof(IconaVassoio).Assembly.GetManifestResourceStream("Lectio.Risorse.lectio.ico");
        if (flusso == null) return (Icon)SystemIcons.Application.Clone();
        return dimensione is Size d ? new Icon(flusso, d) : new Icon(flusso);
    }

    /// <summary>Il testo che compare passando col mouse sull'icona.</summary>
    public static string Suggerimento(bool inPreparazione) =>
        inPreparazione ? "Lectio: sto preparando una lezione" : "Lectio";
}
