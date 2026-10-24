using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Manette.Network.Mdns;

public sealed record MdnsServiceInfo
{
    public const string ServiceType = "_manette._udp";

    /// <summary>Shown to the user in the phone's PC list. Dots are replaced; max 63 UTF-8 bytes.</summary>
    public required string InstanceName { get; init; }
    public required ushort Port { get; init; }
    /// <summary>8 hex characters identifying this installation (the "id" TXT key).</summary>
    public required string Id { get; init; }
    /// <summary>Polled when building TXT so the phone can see whether a pairing window is open.</summary>
    public Func<bool> PairingOpen { get; init; } = () => false;
    public string HostLabel { get; init; } = DefaultHostLabel();

    public string ServiceName => ServiceType + ".local";
    public string InstanceFullName => SanitizeLabel(InstanceName) + "." + ServiceName;
    public string HostName => HostLabel + ".local";

    public static string SanitizeLabel(string label)
    {
        var s = label.Replace('.', '-').Trim();
        if (s.Length == 0) s = "Manette";
        while (Encoding.UTF8.GetByteCount(s) > 63) s = s[..^1];
        return s;
    }

    /// <summary>Not the plain machine name: the OS may already claim "machine.local" and mDNS names must be unique.</summary>
    public static string DefaultHostLabel()
    {
        var chars = Environment.MachineName.ToLowerInvariant().Where(c => char.IsAsciiLetterOrDigit(c) || c == '-').ToArray();
        var name = chars.Length == 0 ? "pc" : new string(chars);
        return (name.Length > 40 ? name[..40] : name) + "-manette";
    }
}

/// <summary>
/// Minimal mDNS/DNS-SD responder so phones can find the PC as "_manette._udp.local". Hand-written to avoid a
/// dependency; it answers PTR/SRV/TXT/A queries for our one service and announces on start, on change and
/// every minute. Failure to bind (another responder owns port 5353 exclusively) is non-fatal: manual IP and
/// the QR code still work.
/// </summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private static readonly IPAddress Group = IPAddress.Parse("224.0.0.251");
    private const int MdnsPort = 5353;
    private const uint Ttl = 120;

    private readonly MdnsServiceInfo _info;
    private readonly object _sendGate = new();
    private readonly HashSet<IPAddress> _joined = new();
    private Socket? _socket;
    private Thread? _thread;
    private Timer? _timer;
    private volatile bool _stopping;

    public event Action<string>? Log;

    public bool IsRunning => _socket is not null && !_stopping;

    public MdnsAdvertiser(MdnsServiceInfo info) => _info = info;

    public bool Start()
    {
        try
        {
            var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            s.Bind(new IPEndPoint(IPAddress.Any, MdnsPort));
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastLoopback, true);
            s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.PacketInformation, true);
            _socket = s;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"mDNS disabled (could not bind UDP 5353: {ex.Message}). Use the QR code or enter the IP manually.");
            return false;
        }

        RefreshMemberships();
        _thread = new Thread(ReceiveLoop) { IsBackground = true, Name = "Manette mDNS" };
        _thread.Start();

        Announce();
        // RFC 6762 asks for a second announcement a second later, then we simply repeat once a minute.
        _timer = new Timer(_ => Announce(), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(60));
        return true;
    }

    /// <summary>Sends an unsolicited response on every interface. Call when the pairing state changes.</summary>
    public void Announce() => SendAll(goodbye: false);

    // ------------------------------------------------------------- receive

    private void ReceiveLoop()
    {
        var buffer = new byte[9000];
        while (!_stopping)
        {
            try
            {
                EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                var flags = SocketFlags.None;
                int n = _socket!.ReceiveMessageFrom(buffer, 0, buffer.Length, ref flags, ref remote, out var pktInfo);
                if (!DnsMessage.TryParse(buffer.AsSpan(0, n), out var msg) || msg is null || msg.IsResponse) continue;
                if (!QueryMatches(msg, _info)) continue;
                RespondToQuery(msg, (IPEndPoint)remote, pktInfo.Interface);
            }
            catch (ObjectDisposedException) { break; }
            catch (SocketException ex) when (_stopping || ex.SocketErrorCode is SocketError.Interrupted or SocketError.OperationAborted) { break; }
            catch (SocketException ex) when (ex.SocketErrorCode is SocketError.ConnectionReset or SocketError.MessageSize) { }
            catch (Exception ex)
            {
                Log?.Invoke($"mDNS error: {ex.GetType().Name}: {ex.Message}");
            }
        }
    }

    /// <summary>True if any question asks for something this service answers.</summary>
    internal static bool QueryMatches(DnsMessage q, MdnsServiceInfo info)
    {
        foreach (var question in q.Questions)
        {
            if (question.Class != DnsResponseBuilder.ClassIN) continue;
            bool anyOrType(ushort t) => question.Type == DnsType.ANY || question.Type == t;
            if (Eq(question.Name, info.ServiceName) && anyOrType(DnsType.PTR)) return true;
            if (Eq(question.Name, info.InstanceFullName) && (anyOrType(DnsType.SRV) || anyOrType(DnsType.TXT))) return true;
            if (Eq(question.Name, info.HostName) && anyOrType(DnsType.A)) return true;
        }
        return false;
    }

    private static bool Eq(string a, string b) => string.Equals(a, b, StringComparison.OrdinalIgnoreCase);

    // ---------------------------------------------------------------- send

    /// <summary>The response a phone on <paramref name="address"/>'s network should see.</summary>
    internal static byte[] BuildResponse(
        MdnsServiceInfo info, IPAddress address, bool pairingOpen, bool goodbye, ushort id = 0)
    {
        uint ttl = goodbye ? 0u : Ttl;
        var txt = new[] { "v=1", "id=" + info.Id, "pair=" + (pairingOpen ? "1" : "0") };
        return new DnsResponseBuilder()
            .AddPtr(additional: false, info.ServiceName, info.InstanceFullName, ttl)
            .AddSrv(additional: true, info.InstanceFullName, info.HostName, info.Port, ttl)
            .AddTxt(additional: true, info.InstanceFullName, txt, ttl)
            .AddA(additional: true, info.HostName, address, ttl)
            .Build(id);
    }

    /// <summary>
    /// A querier on port 5353 listens to the multicast group, so we answer there. A querier on any other port
    /// (a simple resolver, our FakeClient) cannot hear multicast, so RFC 6762 section 6.7 says to reply by
    /// unicast to its address and port, echoing its query id.
    /// </summary>
    private void RespondToQuery(DnsMessage query, IPEndPoint from, int interfaceIndex)
    {
        var all = HostAddressProvider.GetInterfaces();
        var match = all.Where(i => i.InterfaceIndex == interfaceIndex).ToList();
        var targets = match.Count > 0 ? match : all;

        if (from.Port != MdnsPort)
        {
            var iface = targets.FirstOrDefault();
            if (iface is null) return;
            try
            {
                var packet = BuildResponse(_info, iface.Address, _info.PairingOpen(), goodbye: false, query.Id);
                lock (_sendGate) _socket?.SendTo(packet, from);
            }
            catch (ObjectDisposedException) { }
            catch (Exception ex) { Log?.Invoke($"mDNS unicast reply to {from} failed: {ex.Message}"); }
            return;
        }
        foreach (var iface in targets) SendOn(iface, goodbye: false);
    }

    private void SendAll(bool goodbye)
    {
        if (_socket is null) return;
        RefreshMemberships();
        foreach (var iface in HostAddressProvider.GetInterfaces()) SendOn(iface, goodbye);
    }

    private void SendOn(HostInterface iface, bool goodbye)
    {
        var s = _socket;
        if (s is null) return;
        try
        {
            var packet = BuildResponse(_info, iface.Address, _info.PairingOpen(), goodbye);
            lock (_sendGate)
            {
                s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, iface.Address.GetAddressBytes());
                s.SendTo(packet, new IPEndPoint(Group, MdnsPort));
            }
        }
        catch (ObjectDisposedException) { }
        catch (Exception ex)
        {
            Log?.Invoke($"mDNS send on {iface.Name} failed: {ex.Message}");
        }
    }

    private void RefreshMemberships()
    {
        var s = _socket;
        if (s is null) return;
        foreach (var iface in HostAddressProvider.GetInterfaces())
        {
            if (!_joined.Add(iface.Address)) continue;       // already joined this interface
            try
            {
                s.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.AddMembership, new MulticastOption(Group, iface.Address));
            }
            catch (Exception ex)
            {
                _joined.Remove(iface.Address);               // try again next refresh
                Log?.Invoke($"mDNS could not join the multicast group on {iface.Name}: {ex.Message}");
            }
        }
    }

    public void Dispose()
    {
        if (_socket is null) return;
        try { SendAll(goodbye: true); } catch { }
        _stopping = true;
        _timer?.Dispose();
        _socket.Dispose();
        _thread?.Join(TimeSpan.FromSeconds(1));
        _socket = null;
    }
}
