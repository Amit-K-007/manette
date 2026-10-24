using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Manette.Network.Mdns;

namespace Manette.FakeClient;

internal static class Program
{
    private const string Usage = """
        fakeclient: a console "phone" for testing the Manette companion without the Android app.

          fakeclient discover [--seconds 3]
              Browse the LAN for Manette PCs over mDNS.

          fakeclient pair --host <ip> --code <typed code | manette:// link> [--port 26760] [--id <file>] [--name <name>] [--script <s>]
              Pair using the code shown by the companion, then play a script.

          fakeclient play --host <ip> [--port 26760] [--id <file>] [--script <s>] [--seconds N]
              Reconnect as an already-paired phone and play a script.

        Scripts: demo (default), buttons, sticks, triggers, idle
        --id selects the saved identity file (default fakeclient-1.json). Use different files to simulate several phones.
        """;

    private static async Task<int> Main(string[] args)
    {
        if (args.Length == 0 || args[0] is "-h" or "--help") { Console.WriteLine(Usage); return 0; }
        var opt = ParseOptions(args.Skip(1).ToArray());
        try
        {
            return args[0] switch
            {
                "discover" => await Discover(opt),
                "pair" => await Run(opt, pairing: true),
                "play" => await Run(opt, pairing: false),
                _ => Fail($"Unknown command '{args[0]}'.\n\n{Usage}"),
            };
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Error: {ex.Message}");
            return 1;
        }
    }

    // ------------------------------------------------------------- discover

    private static async Task<int> Discover(Dictionary<string, string> opt)
    {
        int seconds = opt.TryGetValue("seconds", out var s) ? int.Parse(s) : 3;
        using var sock = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        sock.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        sock.Bind(new IPEndPoint(IPAddress.Any, 0));
        sock.ReceiveTimeout = 250;

        var query = DnsResponseBuilder.BuildQuery("_manette._udp.local", DnsType.PTR);
        sock.SendTo(query, new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353));

        Console.WriteLine("Looking for Manette PCs...");
        var found = new HashSet<string>();
        var end = DateTime.UtcNow.AddSeconds(seconds);
        var buf = new byte[9000];
        while (DateTime.UtcNow < end)
        {
            try
            {
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                int n = sock.ReceiveFrom(buf, ref from);
                if (!DnsMessage.TryParse(buf.AsSpan(0, n), out var msg) || msg is null || !msg.IsResponse) continue;
                var all = msg.Answers.Concat(msg.Additionals).ToList();
                foreach (var ptr in all.Where(r => r.Type == DnsType.PTR && r.Name.Equals("_manette._udp.local", StringComparison.OrdinalIgnoreCase)))
                {
                    var srv = all.FirstOrDefault(r => r.Type == DnsType.SRV && r.Name.Equals(ptr.PtrTarget, StringComparison.OrdinalIgnoreCase));
                    var txt = all.FirstOrDefault(r => r.Type == DnsType.TXT && r.Name.Equals(ptr.PtrTarget, StringComparison.OrdinalIgnoreCase));
                    var addrs = all.Where(r => r.Type == DnsType.A).Select(r => r.Address).ToList();
                    string line = $"  {ptr.PtrTarget}  ->  {string.Join(", ", addrs)}:{srv?.SrvPort}  [{string.Join(" ", txt?.Txt ?? [])}]";
                    if (found.Add(line)) Console.WriteLine(line);
                }
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.TimedOut) { }
            await Task.Yield();
        }
        Console.WriteLine(found.Count == 0 ? "Nothing found. Check the firewall, or use --host with the IP." : $"{found.Count} result(s).");
        return found.Count == 0 ? 2 : 0;
    }

    // ----------------------------------------------------------------- play

    private static async Task<int> Run(Dictionary<string, string> opt, bool pairing)
    {
        if (!opt.TryGetValue("host", out var host)) return Fail("--host is required.");
        int port = opt.TryGetValue("port", out var p) ? int.Parse(p) : ProtocolConstants.DefaultPort;
        string idFile = opt.GetValueOrDefault("id", "fakeclient-1.json");
        string script = opt.GetValueOrDefault("script", "demo");

        var ip = (await Dns.GetHostAddressesAsync(host)).First(a => a.AddressFamily == AddressFamily.InterNetwork);
        var identity = LoadOrCreateIdentity(idFile, opt.GetValueOrDefault("name", "FakeClient"));
        using var client = new PhoneClient(identity, new IPEndPoint(ip, port), m => Console.WriteLine("  [client] " + m));
        client.Rumble += (l, s) => Console.WriteLine($"  [rumble] large={l} small={s}");

        bool ok;
        if (pairing)
        {
            if (!opt.TryGetValue("code", out var code)) return Fail("--code is required for pair.");
            if (!TryGetSecret(code, out var secret)) return Fail("That is not a valid pairing code or manette:// link.");
            ok = await client.PairAsync(secret, TimeSpan.FromSeconds(5));
            if (ok) SaveIdentity(idFile, client.Identity);
        }
        else
        {
            if (identity.DeviceKey is null) return Fail($"'{idFile}' is not paired yet. Run: fakeclient pair ...");
            ok = await client.ConnectAsync(TimeSpan.FromSeconds(5));
        }

        if (!ok)
        {
            Console.WriteLine($"Could not connect (server said: {client.LastReject?.ToString() ?? "nothing; is the companion running and the firewall open?"}).");
            return 2;
        }
        Console.WriteLine($"Connected as '{identity.Name}'. Slot: {(client.Slot is { } sl ? sl.ToString() : "not reported yet")}. Script: {script}");

        int seconds = opt.TryGetValue("seconds", out var sec) ? int.Parse(sec) : 0;
        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
        if (seconds > 0) cts.CancelAfter(TimeSpan.FromSeconds(seconds));

        var status = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try { await Task.Delay(2000, cts.Token); } catch (OperationCanceledException) { break; }
                Console.WriteLine($"  [status] {client.State}, RTT {client.LastRttMs:F0} ms");
            }
        });

        try { await RunScript(client, script, cts.Token); } catch (OperationCanceledException) { }
        client.SetState(GamepadState.Neutral);
        await Task.Delay(50);
        client.SendBye();
        Console.WriteLine("Disconnected.");
        return 0;
    }

    private static async Task RunScript(PhoneClient c, string script, CancellationToken ct)
    {
        static GamepadState B(GamepadButtons b) => new(b, 0, 0, 0, 0, 0, 0);

        switch (script)
        {
            case "idle":
                await Task.Delay(Timeout.Infinite, ct);
                break;

            case "buttons":
                while (true)
                    foreach (var b in Enum.GetValues<GamepadButtons>().Where(b => b != GamepadButtons.None))
                    {
                        c.SetState(B(b)); await Task.Delay(300, ct);
                        c.SetState(GamepadState.Neutral); await Task.Delay(150, ct);
                    }

            case "triggers":
                while (true)
                {
                    for (int v = 0; v <= 255; v += 5) { c.SetState(new(0, (byte)v, (byte)(255 - v), 0, 0, 0, 0)); await Task.Delay(15, ct); }
                    for (int v = 255; v >= 0; v -= 5) { c.SetState(new(0, (byte)v, (byte)(255 - v), 0, 0, 0, 0)); await Task.Delay(15, ct); }
                }

            case "sticks":
                for (double t = 0; ; t += 0.08)
                {
                    short x = (short)(Math.Cos(t) * 30000), y = (short)(Math.Sin(t) * 30000);
                    c.SetState(new(0, 0, 0, x, y, (short)-x, (short)-y));
                    await Task.Delay(16, ct);
                }

            default: // demo: face buttons, d-pad, then a stick circle with an A press, forever
                while (true)
                {
                    foreach (var b in new[] { GamepadButtons.A, GamepadButtons.B, GamepadButtons.X, GamepadButtons.Y,
                                              GamepadButtons.DpadUp, GamepadButtons.DpadRight, GamepadButtons.DpadDown, GamepadButtons.DpadLeft })
                    {
                        c.SetState(B(b)); await Task.Delay(250, ct);
                        c.SetState(GamepadState.Neutral); await Task.Delay(120, ct);
                    }
                    for (double t = 0; t < Math.PI * 2; t += 0.1)
                    {
                        c.SetState(new(GamepadButtons.A, (byte)(128 + 127 * Math.Sin(t)), 0,
                            (short)(Math.Cos(t) * 30000), (short)(Math.Sin(t) * 30000), 0, 0));
                        await Task.Delay(20, ct);
                    }
                    c.SetState(GamepadState.Neutral);
                    await Task.Delay(500, ct);
                }
        }
    }

    // ----------------------------------------------------------- identities

    private sealed record IdentityFile(string DeviceId, string? DeviceKey, string Name);

    private static PhoneIdentity LoadOrCreateIdentity(string path, string name)
    {
        if (File.Exists(path))
        {
            var f = JsonSerializer.Deserialize<IdentityFile>(File.ReadAllText(path))!;
            return new PhoneIdentity(
                new DeviceId(Convert.FromHexString(f.DeviceId)),
                f.DeviceKey is null ? null : Convert.FromHexString(f.DeviceKey),
                f.Name);
        }
        return new PhoneIdentity(DeviceId.NewRandom(), null, name);
    }

    private static void SaveIdentity(string path, PhoneIdentity id) =>
        File.WriteAllText(path, JsonSerializer.Serialize(new IdentityFile(
            id.DeviceId.ToString(), id.DeviceKey is null ? null : Convert.ToHexString(id.DeviceKey).ToLowerInvariant(), id.Name)));

    private static bool TryGetSecret(string input, out byte[] secret)
    {
        if (PairingCode.TryParseLink(input, out var link) && link is not null) { secret = link.Secret; return true; }
        return PairingCode.TryParseTypedCode(input, out secret);
    }

    // --------------------------------------------------------------- helpers

    private static Dictionary<string, string> ParseOptions(string[] args)
    {
        var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < args.Length; i++)
            if (args[i].StartsWith("--")) d[args[i][2..]] = i + 1 < args.Length && !args[i + 1].StartsWith("--") ? args[++i] : "true";
        return d;
    }

    private static int Fail(string message)
    {
        Console.Error.WriteLine(message);
        return 1;
    }
}
