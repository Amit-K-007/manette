using System.Drawing.Drawing2D;

namespace Manette.App;

/// <summary>A small drawn gamepad, grey when idle and green when a phone is connected. Drawn in code so no icon file is needed.</summary>
internal static class TrayIcons
{
    public static readonly Icon Idle = Make(Color.FromArgb(120, 125, 135));
    public static readonly Icon Active = Make(Color.FromArgb(46, 160, 67));

    private static Icon Make(Color body)
    {
        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var bodyBrush = new SolidBrush(body);
            g.FillEllipse(bodyBrush, 1, 8, 14, 20);
            g.FillEllipse(bodyBrush, 17, 8, 14, 20);
            g.FillRectangle(bodyBrush, 8, 8, 16, 14);
            g.FillRectangle(Brushes.White, 6, 12, 8, 3);       // d-pad
            g.FillRectangle(Brushes.White, 8.5f, 9.5f, 3, 8);
            g.FillEllipse(Brushes.White, 20, 10, 4, 4);        // buttons
            g.FillEllipse(Brushes.White, 24, 14, 4, 4);
        }
        return Icon.FromHandle(bmp.GetHicon());                // two icons for the whole run: the small handle cost is fine
    }
}
