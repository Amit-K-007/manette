using System.Diagnostics;
using Manette.Backend.ViGEm;

namespace Manette.App;

internal static class Program
{
    public const string ViGEmReleases = "https://github.com/nefarius/ViGEmBus/releases";

    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, "Manette.SingleInstance", out bool first);
        if (!first)
        {
            MessageBox.Show("Manette is already running. Look for its icon near the clock.", "Manette");
            return;
        }

        ApplicationConfiguration.Initialize();

        // Find a missing driver now, with a clear message, rather than when the first phone connects.
        var pads = new ViGEmPadFactory();
        if (!pads.TryProbe(out var error))
        {
            var answer = MessageBox.Show(
                $"{error}\n\nManette needs the ViGEmBus driver to create virtual Xbox controllers.\nOpen the download page now?",
                "Manette: driver needed", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (answer == DialogResult.Yes) Process.Start(new ProcessStartInfo(ViGEmReleases) { UseShellExecute = true });
            return;
        }

        Application.Run(new TrayContext(pads));
    }
}
