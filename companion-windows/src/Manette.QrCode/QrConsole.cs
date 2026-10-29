using System.Text;
using Qr = Net.Codecrete.QrCodeGenerator.QrCode;

namespace Manette.QrCode;

/// <summary>Draws a QR code in a terminal using half-block characters (two QR rows per text row).</summary>
public static class QrConsole
{
    private const string Black = "\u001b[30m";
    private const string WhiteBg = "\u001b[47m";
    private const string Reset = "\u001b[0m";

    /// <summary>
    /// Returns the lines to print. Colours are set explicitly (black on white) so the code scans on both dark and
    /// light terminal themes; a plain-text fallback without escape codes is available via <paramref name="ansi"/>=false.
    /// A 2-module white border (the quiet zone) is included.
    /// </summary>
    public static IReadOnlyList<string> Render(string text, bool ansi = true, int border = 2)
    {
        var qr = Qr.EncodeText(text, Qr.Ecc.Medium);
        int size = qr.Size;
        bool Dark(int x, int y) => x >= 0 && y >= 0 && x < size && y < size && qr.GetModule(x, y);

        var lines = new List<string>();
        for (int y = -border; y < size + border; y += 2)
        {
            var sb = new StringBuilder();
            if (ansi) sb.Append(Black).Append(WhiteBg);
            for (int x = -border; x < size + border; x++)
            {
                bool top = Dark(x, y), bottom = Dark(x, y + 1);
                sb.Append(top ? (bottom ? '\u2588' : '\u2580') : (bottom ? '\u2584' : ' '));
            }
            if (ansi) sb.Append(Reset);
            lines.Add(sb.ToString());
        }
        return lines;
    }

    /// <summary>The number of QR modules per side for this text (useful for tests and layout).</summary>
    public static int ModuleCount(string text) => Qr.EncodeText(text, Qr.Ecc.Medium).Size;
}

/// <summary>The raw QR modules, for UIs that draw the code themselves (the tray app's pairing window).</summary>
public static class QrMatrix
{
    /// <summary>[x, y] = true for a dark module. No border: the caller adds the quiet zone.</summary>
    public static bool[,] Encode(string text)
    {
        var qr = Qr.EncodeText(text, Qr.Ecc.Medium);
        var m = new bool[qr.Size, qr.Size];
        for (int y = 0; y < qr.Size; y++)
            for (int x = 0; x < qr.Size; x++)
                m[x, y] = qr.GetModule(x, y);
        return m;
    }
}
