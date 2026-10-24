using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using Manette.Core.Protocol;

namespace Manette.FakeClient;

public sealed record PhoneIdentity(DeviceId DeviceId, byte[]? DeviceKey, string Name);

public enum ClientState { Disconnected, Connecting, Connected }

/// <summary>
/// A phone as the protocol describes it (docs/protocol.md sections 4, 6, 7). Keep this readable: the Kotlin
/// client should follow the same structure. Threads: one receive loop, one timer for keepalive/ping/watchdog.
/// </summary>
public sealed class PhoneClient : IDisposable
{
    private static readonly TimeSpan KeepAlive = TimeSpan.FromMilliseconds(50);   // 20 Hz
    private static readonly TimeSpan PingEvery = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan PongTimeout = TimeSpan.FromSeconds(3);
    private static readonly TimeSpan HelloRetry = TimeSpan.FromMilliseconds(500);

    private readonly Socket _socket;
    private readonly IPEndPoint _server;
    private readonly object _gate = new();
    private readonly Action<string>? _log;
    private readonly Thread _receiveThread;
    private readonly Timer _timer;

    private PhoneIdentity _identity;
    private byte[] _clientNonce = new byte[ProtocolConstants.NonceLength];
    private TaskCompletionSource<bool>? _welcome;
    private uint _sessionId;
    private byte[] _sessionKey = [];
    private ushort _seq;
    private ushort _lastRumbleSeq;
    private bool _haveRumbleSeq;
    private GamepadState _state;
    private long _lastPongTicks;
    private long _lastPingTicks;
    private volatile bool _disposed;
    private int _reconnecting;

    public ClientState State { get; private set; } = ClientState.Disconnected;
    public double LastRttMs { get; private set; }
    public long PongCount { get; private set; }
    public byte? Slot { get; private set; }
    public RejectReason? LastReject { get; private set; }
    public PhoneIdentity Identity => _identity;

    public event Action<byte, byte>? Rumble;

    public PhoneClient(PhoneIdentity identity, IPEndPoint server, Action<string>? log = null)
    {
        _identity = identity;
        _server = server;
        _log = log;
        _socket = new Socket(server.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
        _socket.Bind(new IPEndPoint(server.AddressFamily == AddressFamily.InterNetworkV6 ? IPAddress.IPv6Any : IPAddress.Any, 0));
        _receiveThread = new Thread(ReceiveLoop) { IsBackground = true, Name = "FakeClient receive" };
        _receiveThread.Start();
        _timer = new Timer(_ => Tick(), null, KeepAlive, KeepAlive);
    }

    // ------------------------------------------------------------ handshake

    /// <summary>First-time pairing with a secret from the PC's QR code / typed code.</summary>
    public async Task<bool> PairAsync(byte[] pairingSecret, TimeSpan timeout)
    {
        var key = ManetteCrypto.DeriveDeviceKey(pairingSecret, _identity.DeviceId.ToArray());
        _identity = _identity with { DeviceKey = key };
        return await HandshakeAsync(HelloFlags.Pairing, timeout);
    }

    /// <summary>Reconnect as an already-paired device.</summary>
    public Task<bool> ConnectAsync(TimeSpan timeout)
    {
        if (_identity.DeviceKey is null) throw new InvalidOperationException("Not paired yet.");
        return HandshakeAsync(HelloFlags.None, timeout);
    }

    private async Task<bool> HandshakeAsync(HelloFlags flags, TimeSpan timeout)
    {
        State = ClientState.Connecting;
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline && !_disposed)
        {
            // A FRESH nonce per HELLO, and only the WELCOME for the newest nonce is accepted. That way a late or
            // replayed WELCOME from an earlier attempt can never install a stale session key.
            var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            lock (_gate)
            {
                RandomNumberGenerator.Fill(_clientNonce = new byte[ProtocolConstants.NonceLength]);
                _welcome = tcs;
            }

            var buf = new byte[ProtocolConstants.HelloMaxLength];
            int n = PacketCodec.WriteHello(buf, flags, _identity.DeviceId, _clientNonce, _identity.Name, _identity.DeviceKey!);
            _socket.SendTo(buf.AsSpan(0, n), _server);

            if (await Task.WhenAny(tcs.Task, Task.Delay(HelloRetry)) == tcs.Task && tcs.Task.Result)
            {
                lock (_gate)
                {
                    State = ClientState.Connected;
                    _lastPongTicks = _lastPingTicks = Environment.TickCount64;
                }
                SendState();                       // announce the current state straight away
                return true;
            }
            if (LastReject is RejectReason.PairingNotOpen or RejectReason.NotPaired or RejectReason.ServerFull
                or RejectReason.UnsupportedVersion)
                break;                             // retrying cannot help; the user has to act
        }
        State = ClientState.Disconnected;
        return false;
    }

    // ---------------------------------------------------------------- input

    /// <summary>Update the controller state. Sends immediately (never waits for a poll tick).</summary>
    public void SetState(GamepadState next)
    {
        bool release;
        lock (_gate)
        {
            release = IsRelease(_state, next);
            _state = next;
        }
        SendState();
        if (release)
        {
            // A lost release is the one loss users notice (a stuck input), so send it twice, ~2 ms apart.
            _ = Task.Run(async () => { await Task.Delay(2); SendState(); });
        }
    }

    private static bool IsRelease(in GamepadState a, in GamepadState b) =>
        (a.Buttons & ~b.Buttons) != 0 ||
        (a.LeftTrigger > 0 && b.LeftTrigger == 0) || (a.RightTrigger > 0 && b.RightTrigger == 0) ||
        ((a.LeftX != 0 || a.LeftY != 0) && b.LeftX == 0 && b.LeftY == 0) ||
        ((a.RightX != 0 || a.RightY != 0) && b.RightX == 0 && b.RightY == 0);

    private void SendState()
    {
        Span<byte> buf = stackalloc byte[ProtocolConstants.InputLength];
        lock (_gate)
        {
            if (State != ClientState.Connected) return;
            _seq = unchecked((ushort)(_seq + 1));
            PacketCodec.WriteInput(buf, _sessionId, _seq, _state, _sessionKey);
        }
        Send(buf);
    }

    public void SendBye()
    {
        Span<byte> buf = stackalloc byte[ProtocolConstants.ByeLength];
        lock (_gate)
        {
            if (State != ClientState.Connected) return;
            PacketCodec.WriteBye(buf, _sessionId, _sessionKey);
            State = ClientState.Disconnected;
        }
        Send(buf);
    }

    private void Send(ReadOnlySpan<byte> data)
    {
        try { _socket.SendTo(data, _server); }
        catch (ObjectDisposedException) { }
        catch (SocketException ex) { _log?.Invoke($"send failed: {ex.SocketErrorCode}"); }
    }

    // ---------------------------------------------------- timer: keepalive etc.

    private void Tick()
    {
        if (_disposed) return;
        long now = Environment.TickCount64;
        bool ping = false, lost = false;
        lock (_gate)
        {
            if (State != ClientState.Connected) return;
            if (now - _lastPingTicks >= PingEvery.TotalMilliseconds) { _lastPingTicks = now; ping = true; }
            if (now - _lastPongTicks >= PongTimeout.TotalMilliseconds) lost = true;
        }

        SendState();                               // 20 Hz keepalive carrying the full current state
        if (ping)
        {
            Span<byte> buf = stackalloc byte[ProtocolConstants.PingLength];
            lock (_gate) PacketCodec.WritePing(buf, MessageType.Ping, _sessionId, (uint)now, _sessionKey);
            Send(buf);
        }
        if (lost && Interlocked.Exchange(ref _reconnecting, 1) == 0)
        {
            _log?.Invoke("No PONG for 3 s: sending a fresh HELLO.");
            _ = Task.Run(async () =>
            {
                try
                {
                    bool ok = await HandshakeAsync(HelloFlags.None, TimeSpan.FromSeconds(10));
                    _log?.Invoke(ok ? "Reconnected." : "Reconnect failed.");
                }
                finally { Interlocked.Exchange(ref _reconnecting, 0); }
            });
        }
    }

    // -------------------------------------------------------------- receive

    private void ReceiveLoop()
    {
        var buffer = new byte[256];
        EndPoint any = new IPEndPoint(IPAddress.Any, 0);
        while (!_disposed)
        {
            try
            {
                EndPoint from = any;
                int n = _socket.ReceiveFrom(buffer, SocketFlags.None, ref from);
                Handle(buffer.AsSpan(0, n));
            }
            catch (ObjectDisposedException) { break; }
            catch (SocketException ex) when (_disposed || ex.SocketErrorCode is SocketError.Interrupted or SocketError.OperationAborted) { break; }
            catch (SocketException) { /* ICMP unreachable while the PC is away: keep trying */ }
            catch (Exception ex) { _log?.Invoke($"receive error: {ex.Message}"); }
        }
    }

    private void Handle(ReadOnlySpan<byte> p)
    {
        if (!PacketCodec.TryReadHeader(p, out var h) || h.Version != ProtocolConstants.ProtocolVersion) return;

        switch (h.Type)
        {
            case MessageType.Welcome:
                HandleWelcome(p);
                break;
            case MessageType.Reject:
                // Unauthenticated: a UI hint only. It changes no keys and no state.
                if (PacketCodec.TryParseReject(p, out var r)) LastReject = r.Reason;
                break;
            case MessageType.Pong:
                lock (_gate)
                {
                    if (h.SessionId != _sessionId || !ManetteCrypto.VerifyTag(_sessionKey, p, ProtocolConstants.SessionTagLength)) return;
                    if (!PacketCodec.TryParsePing(p, MessageType.Pong, out var pong)) return;
                    _lastPongTicks = Environment.TickCount64;
                    PongCount++;
                    LastRttMs = unchecked((uint)Environment.TickCount64 - pong.Timestamp);
                }
                break;
            case MessageType.Rumble:
                HandleRumble(p, h.SessionId);
                break;
        }
    }

    private void HandleWelcome(ReadOnlySpan<byte> p)
    {
        TaskCompletionSource<bool>? tcs;
        lock (_gate)
        {
            tcs = _welcome;
            if (tcs is null || _identity.DeviceKey is null) return;
            if (!PacketCodec.TryParseWelcome(p, out var w) || w is null) return;
            // Bound to OUR latest HELLO via client_nonce; anything else (old, forged, replayed) fails here.
            if (!ManetteCrypto.VerifyTag(_identity.DeviceKey, p, ProtocolConstants.HandshakeTagLength, _clientNonce)) return;

            _sessionId = w.SessionId;
            _sessionKey = ManetteCrypto.DeriveSessionKey(_identity.DeviceKey, _clientNonce, w.ServerNonce);
            _seq = 0;
            _haveRumbleSeq = false;
            Slot = w.Slot == ProtocolConstants.NoSlot ? null : w.Slot;
            _welcome = null;
        }
        LastReject = null;
        tcs.TrySetResult(true);
    }

    private void HandleRumble(ReadOnlySpan<byte> p, uint sessionId)
    {
        lock (_gate)
        {
            if (sessionId != _sessionId || !ManetteCrypto.VerifyTag(_sessionKey, p, ProtocolConstants.SessionTagLength)) return;
            if (!PacketCodec.TryParseRumble(p, out var m)) return;
            if (_haveRumbleSeq && !SequenceNumber.IsNewer(_lastRumbleSeq, m.Seq)) return;
            _haveRumbleSeq = true;
            _lastRumbleSeq = m.Seq;
            Rumble?.Invoke(m.Large, m.Small);
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _timer.Dispose();
        _socket.Dispose();
        _receiveThread.Join(TimeSpan.FromSeconds(1));
    }
}
