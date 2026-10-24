using System.Net;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Manette.Core.Sessions;
using static Manette.Core.Protocol.ProtocolConstants;

namespace Manette.Core.Server;

public sealed record ServerOptions
{
    /// <summary>Max HELLOs accepted per source address per <see cref="HelloWindow"/>.</summary>
    public int HelloBurst { get; init; } = 5;
    public TimeSpan HelloWindow { get; init; } = TimeSpan.FromSeconds(1);
}

/// <summary>
/// The protocol brain: turns raw datagrams into session/pad actions and replies (docs/protocol.md).
/// It has no sockets: the network layer calls <see cref="HandleDatagram"/> and implements
/// <see cref="IDatagramSender"/>. Call <see cref="Tick"/> about every 50 ms.
/// Everything is verified before anything is allocated or applied; bad packets are dropped silently.
/// </summary>
public sealed class ManetteServer
{
    private readonly ITrustedDeviceStore _store;
    private readonly PairingManager _pairing;
    private readonly SessionManager _sessions;
    private readonly IDatagramSender _sender;
    private readonly TimeProvider _time;
    private readonly ServerOptions _options;

    private readonly object _rateGate = new();
    private readonly Dictionary<IPAddress, (long WindowStart, int Count)> _helloRate = new();
    private const int MaxTrackedSources = 4096;

    /// <summary>Raised after a phone completes pairing (for UI toasts).</summary>
    public event Action<TrustedDevice>? DevicePaired;

    public ManetteServer(
        ITrustedDeviceStore store, PairingManager pairing, SessionManager sessions,
        IDatagramSender sender, TimeProvider? time = null, ServerOptions? options = null)
    {
        _store = store;
        _pairing = pairing;
        _sessions = sessions;
        _sender = sender;
        _time = time ?? TimeProvider.System;
        _options = options ?? new ServerOptions();
        _sessions.RumbleRequested += OnRumbleRequested;
    }

    public SessionManager Sessions => _sessions;

    public void Tick()
    {
        _sessions.Tick();
        PruneRateLimiter();
    }

    // ------------------------------------------------------------- dispatch

    public void HandleDatagram(ReadOnlySpan<byte> data, IPEndPoint remote)
    {
        if (data.Length > MaxDatagramLength || !PacketCodec.TryReadHeader(data, out var header)) return;

        if (header.Version != ProtocolVersion)
        {
            // Only a HELLO is answered; everything else from a newer client is dropped.
            if (header.Type == MessageType.Hello) SendReject(remote, RejectReason.UnsupportedVersion);
            return;
        }

        switch (header.Type)
        {
            case MessageType.Hello: HandleHello(data, remote); break;
            case MessageType.Input: HandleInput(data, remote); break;
            case MessageType.Ping: HandlePing(data, remote); break;
            case MessageType.Bye: HandleBye(data); break;
            // Welcome/Reject/Pong/Rumble are server-to-phone; unknown types are dropped (spec section 3).
        }
    }

    // ---------------------------------------------------------------- HELLO

    private void HandleHello(ReadOnlySpan<byte> data, IPEndPoint remote)
    {
        if (!PacketCodec.TryParseHello(data, out var hello) || hello is null) return;

        if (!AllowHello(remote.Address))
        {
            SendReject(remote, RejectReason.RateLimited);
            return;
        }

        bool pairingMode = (hello.Flags & HelloFlags.Pairing) != 0;
        byte[] deviceKey;
        if (pairingMode)
        {
            if (!_pairing.TryDeriveDeviceKey(hello.DeviceId, out deviceKey))
            {
                // The window is closed (it is single-use). If this phone ALREADY paired, this is a retry after
                // our WELCOME was lost: its tag will verify against the key we stored, so treat it as an ordinary
                // trusted HELLO. A phone we have never paired still gets PairingNotOpen.
                if (_store.TryGet(hello.DeviceId, out var already))
                {
                    deviceKey = already.DeviceKey;
                    pairingMode = false;
                }
                else
                {
                    SendReject(remote, RejectReason.PairingNotOpen);
                    return;
                }
            }
        }
        else if (_store.TryGet(hello.DeviceId, out var trusted))
        {
            deviceKey = trusted.DeviceKey;
        }
        else
        {
            SendReject(remote, RejectReason.NotPaired);
            return;
        }

        // Authenticate BEFORE allocating a pad or touching any state. A bad tag is dropped silently.
        if (!ManetteCrypto.VerifyTag(deviceKey, data, HandshakeTagLength)) return;

        var result = _sessions.Open(hello.DeviceId, hello.Name, deviceKey, hello.ClientNonce, remote);
        if (result.Session is null)
        {
            SendReject(remote, RejectReason.ServerFull);
            return;
        }

        var flags = WelcomeFlags.None;
        if (result.Resumed) flags |= WelcomeFlags.Resumed;

        // Pairing completes only once a session really opened, so a "server full" reply does not burn the QR.
        if (pairingMode)
        {
            var device = new TrustedDevice(hello.DeviceId, deviceKey, hello.Name, _time.GetUtcNow());
            _store.Upsert(device);
            _pairing.Complete();
            flags |= WelcomeFlags.PairedNow;
            DevicePaired?.Invoke(device);
        }

        Span<byte> buf = stackalloc byte[WelcomeLength];
        int n = PacketCodec.WriteWelcome(
            buf, result.Session.SessionId, flags,
            result.Session.Pad.Slot ?? NoSlot, _sessions.Options.RumbleHzMax,
            result.ServerNonce, deviceKey, hello.ClientNonce);
        _sender.Send(buf[..n], remote);
    }

    // -------------------------------------------------------- session traffic

    private void HandleInput(ReadOnlySpan<byte> data, IPEndPoint remote)
    {
        if (!PacketCodec.TryParseInput(data, out var m)) return;
        _sessions.ReceiveInput(data, m.SessionId, m.Seq, m.State, remote);
    }

    private void HandlePing(ReadOnlySpan<byte> data, IPEndPoint remote)
    {
        if (!PacketCodec.TryParsePing(data, MessageType.Ping, out var m)) return;
        var session = _sessions.ReceivePing(data, m.SessionId, remote);
        if (session is null) return;

        Span<byte> buf = stackalloc byte[PingLength];
        int n = PacketCodec.WritePing(buf, MessageType.Pong, session.SessionId, m.Timestamp, session.SessionKey);
        _sender.Send(buf[..n], remote);
    }

    private void HandleBye(ReadOnlySpan<byte> data)
    {
        if (!PacketCodec.TryParseBye(data, out var id)) return;
        _sessions.ReceiveBye(data, id);
    }

    // ---------------------------------------------------------------- rumble

    private void OnRumbleRequested(Session session, byte large, byte small)
    {
        Span<byte> buf = stackalloc byte[RumbleLength];
        int n = _sessions.TryBuildRumble(session, large, small, buf, out var remote);
        if (n > 0 && remote is not null) _sender.Send(buf[..n], remote);
    }

    // ------------------------------------------------------------ rate limit

    private bool AllowHello(IPAddress source)
    {
        long now = _time.GetTimestamp();
        lock (_rateGate)
        {
            if (_helloRate.Count > MaxTrackedSources) _helloRate.Clear();
            if (_helloRate.TryGetValue(source, out var entry) &&
                _time.GetElapsedTime(entry.WindowStart, now) < _options.HelloWindow)
            {
                if (entry.Count >= _options.HelloBurst) return false;
                _helloRate[source] = (entry.WindowStart, entry.Count + 1);
                return true;
            }
            _helloRate[source] = (now, 1);
            return true;
        }
    }

    private void PruneRateLimiter()
    {
        long now = _time.GetTimestamp();
        lock (_rateGate)
        {
            if (_helloRate.Count == 0) return;
            List<IPAddress>? stale = null;
            foreach (var (ip, entry) in _helloRate)
                if (_time.GetElapsedTime(entry.WindowStart, now) >= _options.HelloWindow)
                    (stale ??= new()).Add(ip);
            if (stale is not null)
                foreach (var ip in stale) _helloRate.Remove(ip);
        }
    }

    private void SendReject(IPEndPoint to, RejectReason reason)
    {
        Span<byte> buf = stackalloc byte[RejectLength];
        int n = PacketCodec.WriteReject(buf, reason, ProtocolVersion);
        _sender.Send(buf[..n], to);
    }
}
