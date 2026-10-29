using Manette.Backend.ViGEm;
using Manette.Companion;
using Manette.Core;
using Manette.Core.Sessions;
using Manette.Network;
using Manette.QrCode;

namespace Manette.Cli;

internal static class Program
{
    private const string Usage = """
        manette: headless Manette companion

          manette [--fake-pads] [--port 26760] [--name "My PC"] [--no-mdns] [--no-qr] [--rumble-hz 0] [--data-dir <dir>]

          --fake-pads   print controller state instead of creating real virtual Xbox pads (any OS, for testing)
          --no-qr       do not draw the QR code (use this if your terminal shows garbage)
          --rumble-hz   advertise rumble at this rate to phones (0 = off, the default)

        While running:  p = new pairing code   s = status   q = quit
        """;

    private static int Main(string[] args)
    {
        var opt = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] is "-h" or "--help") { Console.WriteLine(Usage); return 0; }
            if (args[i].StartsWith("--")) opt[args[i][2..]] = i + 1 < args.Length && !args[i + 1].StartsWith("--") ? args[++i] : "true";
        }

        Console.OutputEncoding = System.Text.Encoding.UTF8;
        bool showQr = !opt.ContainsKey("no-qr") && !Console.IsOutputRedirected && TerminalAnsi.Enable();

        string dataDir = opt.GetValueOrDefault("data-dir",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Manette"));
        Directory.CreateDirectory(dataDir);

        IVirtualPadFactory pads;
        ViGEmPadFactory? vigem = null;
        if (opt.ContainsKey("fake-pads"))
        {
            pads = new ConsolePadFactory();
            Console.WriteLine("Using fake pads (no driver).");
        }
        else
        {
            vigem = new ViGEmPadFactory(m => Console.WriteLine("[driver] " + m));
            if (!vigem.TryProbe(out var error))
            {
                Console.Error.WriteLine($"Cannot start: {error}");
                Console.Error.WriteLine("Install the ViGEmBus driver, or run with --fake-pads to test without it.");
                return 1;
            }
            pads = vigem;
        }

        var options = new ManetteHostOptions
        {
            Port = opt.TryGetValue("port", out var p) ? int.Parse(p) : 26760,
            PcName = opt.GetValueOrDefault("name", Environment.MachineName),
            EnableMdns = !opt.ContainsKey("no-mdns"),
            InstallId = InstallIdStore.GetOrCreate(Path.Combine(dataDir, "install-id")),
            Sessions = new SessionOptions { RumbleHzMax = opt.TryGetValue("rumble-hz", out var r) ? ushort.Parse(r) : (ushort)0 },
        };

        using var host = new ManetteHost(pads, options, new ProtectedTrustedDeviceStore(
            Path.Combine(dataDir, "trusted-devices.v2.json"), CreateProtector(), Path.Combine(dataDir, "trusted-devices.json")));
        host.Log += m => Console.WriteLine("[log] " + m);
        host.Server.DevicePaired += d => Console.WriteLine($"[paired] '{d.Name}' is now trusted.");
        host.Sessions.SessionsChanged += (_, _) => PrintSessions(host);
        host.Start();

        Console.WriteLine($"Listening on UDP {host.Port}. mDNS: {(host.MdnsRunning ? "on" : "off")}. Trusted devices: {host.Store.List().Count}.");
        ShowPairing(host, showQr);

        while (true)
        {
            string? line = Console.IsInputRedirected ? Console.ReadLine() : Console.ReadKey(true).KeyChar.ToString();
            if (line is null) { Thread.Sleep(Timeout.Infinite); }      // stdin closed (daemon): keep serving
            switch (line!.Trim().ToLowerInvariant())
            {
                case "p": ShowPairing(host, showQr); break;
                case "s": PrintSessions(host); break;
                case "q": vigem?.Dispose(); return 0;
            }
        }
    }

    private static void ShowPairing(ManetteHost host, bool showQr)
    {
        var (offer, link) = host.OpenPairing();
        Console.WriteLine();
        Console.WriteLine("Pair a phone (valid 5 minutes, single use):");
        Console.WriteLine($"  Typed code : {offer.TypedCode}");
        Console.WriteLine($"  Link       : {link}");
        Console.WriteLine();
        if (showQr)
        {
            Console.WriteLine("Scan with the Manette app:");
            foreach (var line in QrConsole.Render(link)) Console.WriteLine("  " + line);
            Console.WriteLine();
        }
    }

    private static void PrintSessions(ManetteHost host)
    {
        var list = host.Sessions.Snapshot();
        Console.WriteLine($"[sessions] {list.Count} connected");
        foreach (var s in list)
            Console.WriteLine($"   {s.Name,-16} {s.State,-7} slot {(s.Slot is { } sl ? sl.ToString() : "?")}  {s.Remote}  {s.PacketsAccepted} packets");
    }

    /// <summary>Windows DPAPI where available. Elsewhere (development only) keys are stored unprotected.</summary>
    private static ISecretProtector CreateProtector()
    {
        if (OperatingSystem.IsWindows()) return new DpapiProtector();
        Console.WriteLine("Note: not on Windows, so trusted-device keys are stored UNPROTECTED (development only).");
        return new PassthroughProtector();
    }
}
