using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using Manette.Core;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Manette.Core.Server;
using Manette.Core.Sessions;

namespace Manette.Core.Tests;

internal static class Vectors
{
    public static readonly JsonElement Root =
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "test-vectors.json"))).RootElement;

    public static byte[] Bytes(JsonElement e, string name) => Convert.FromHexString(e.GetProperty(name).GetString()!);
    public static string Str(JsonElement e, string name) => e.GetProperty(name).GetString()!;
    public static JsonElement Sect(string name) => Root.GetProperty(name);

    public static byte[] SecretBytes => Bytes(Sect("fixed_inputs"), "pairing_secret");
    public static byte[] DeviceIdBytes => Bytes(Sect("fixed_inputs"), "device_id");
    public static byte[] ClientNonce => Bytes(Sect("fixed_inputs"), "client_nonce");
    public static byte[] ServerNonce => Bytes(Sect("fixed_inputs"), "server_nonce");
    public static byte[] OtherClientNonce => Bytes(Sect("fixed_inputs"), "other_client_nonce");
    public static uint SessionId => Sect("fixed_inputs").GetProperty("session_id").GetUInt32();
    public static byte[] DeviceKey => Bytes(Sect("derived_keys"), "device_key");
    public static byte[] SessionKey => Bytes(Sect("derived_keys"), "session_key");

    public static GamepadState State(JsonElement s) => new(
        (GamepadButtons)s.GetProperty("buttons").GetUInt16(),
        s.GetProperty("lt").GetByte(), s.GetProperty("rt").GetByte(),
        s.GetProperty("lx").GetInt16(), s.GetProperty("ly").GetInt16(),
        s.GetProperty("rx").GetInt16(), s.GetProperty("ry").GetInt16());
}

internal static class Hex
{
    public static string Of(ReadOnlySpan<byte> b) => Convert.ToHexString(b).ToLowerInvariant();
    public static byte[] Parse(string s) => Convert.FromHexString(s);
}

internal sealed class ManualTimeProvider : TimeProvider
{
    private long _ticks = 1;
    private readonly DateTimeOffset _start = new(2026, 10, 19, 12, 0, 0, TimeSpan.Zero);

    public override long TimestampFrequency => 1_000_000;
    public override long GetTimestamp() => _ticks;
    public override DateTimeOffset GetUtcNow() => _start + TimeSpan.FromSeconds((double)_ticks / TimestampFrequency);

    public void Advance(TimeSpan d) => _ticks += (long)(d.TotalSeconds * TimestampFrequency);
}

internal sealed class FakePad : IVirtualPad
{
    public List<GamepadState> Updates { get; } = new();
    public bool Disposed { get; private set; }
    public byte? Slot { get; init; }
    public event Action<byte, byte>? RumbleReceived;

    public GamepadState Last => Updates.Count == 0 ? GamepadState.Neutral : Updates[^1];
    public void Update(in GamepadState state) => Updates.Add(state);
    public void RaiseRumble(byte large, byte small) => RumbleReceived?.Invoke(large, small);
    public void Dispose() => Disposed = true;
}

internal sealed class FakePadFactory : IVirtualPadFactory
{
    public List<FakePad> Created { get; } = new();
    public int Limit { get; set; } = int.MaxValue;

    public IVirtualPad? TryCreate()
    {
        if (Created.Count >= Limit) return null;
        var pad = new FakePad { Slot = (byte)Created.Count };
        Created.Add(pad);
        return pad;
    }
}

/// <summary>Returns queued byte chunks first (so tests can reproduce vectors), then real randomness.</summary>
internal sealed class ScriptedRandom : IRandomSource
{
    private readonly Queue<byte[]> _queue = new();
    public void Enqueue(params byte[] chunk) => _queue.Enqueue(chunk);

    public void Fill(Span<byte> buffer)
    {
        if (_queue.Count > 0)
        {
            var chunk = _queue.Dequeue();
            if (chunk.Length != buffer.Length)
                throw new InvalidOperationException($"Scripted chunk is {chunk.Length} bytes but {buffer.Length} requested.");
            chunk.CopyTo(buffer);
        }
        else RandomNumberGenerator.Fill(buffer);
    }
}

internal sealed class RecordingSender : IDatagramSender
{
    public List<(byte[] Data, IPEndPoint To)> Sent { get; } = new();
    public void Send(ReadOnlySpan<byte> datagram, IPEndPoint destination) =>
        Sent.Add((datagram.ToArray(), new IPEndPoint(destination.Address, destination.Port)));

    public void Clear() => Sent.Clear();

    public IEnumerable<byte[]> OfType(MessageType type) =>
        Sent.Select(s => s.Data).Where(d => d.Length > 3 && d[3] == (byte)type);

    public RejectReason? LastReject()
    {
        var last = OfType(MessageType.Reject).LastOrDefault();
        return last is not null && PacketCodec.TryParseReject(last, out var r) ? r.Reason : null;
    }
}

/// <summary>A phone as the tests see it: keys and ids, plus helpers to build signed packets.</summary>
internal sealed class TestPhone
{
    public DeviceId DeviceId { get; init; }
    public byte[] DeviceKey { get; init; } = [];
    public byte[] ClientNonce { get; init; } = new byte[8];
    public uint SessionId { get; set; }
    public byte[] SessionKey { get; set; } = [];
    public IPEndPoint Address { get; set; } = new(IPAddress.Parse("192.168.1.50"), 50000);

    public byte[] Input(ushort seq, GamepadState s)
    {
        var buf = new byte[ProtocolConstants.InputLength];
        PacketCodec.WriteInput(buf, SessionId, seq, s, SessionKey);
        return buf;
    }

    public byte[] Ping(uint ts)
    {
        var buf = new byte[ProtocolConstants.PingLength];
        PacketCodec.WritePing(buf, MessageType.Ping, SessionId, ts, SessionKey);
        return buf;
    }

    public byte[] Bye()
    {
        var buf = new byte[ProtocolConstants.ByeLength];
        PacketCodec.WriteBye(buf, SessionId, SessionKey);
        return buf;
    }

    public byte[] Hello(bool pairing, string name = "Test phone")
    {
        var buf = new byte[ProtocolConstants.HelloMaxLength];
        int n = PacketCodec.WriteHello(
            buf, pairing ? HelloFlags.Pairing : HelloFlags.None, DeviceId, ClientNonce, name, DeviceKey);
        return buf[..n];
    }

    /// <summary>Client-side handling of a WELCOME: verify its tag and derive the session key.</summary>
    public bool AcceptWelcome(byte[] packet)
    {
        if (!PacketCodec.TryParseWelcome(packet, out var w) || w is null) return false;
        if (!ManetteCrypto.VerifyTag(DeviceKey, packet, ProtocolConstants.HandshakeTagLength, ClientNonce)) return false;
        SessionId = w.SessionId;
        SessionKey = ManetteCrypto.DeriveSessionKey(DeviceKey, ClientNonce, w.ServerNonce);
        return true;
    }
}

/// <summary>A fully wired server with fakes for the pad, clock, randomness and network.</summary>
internal sealed class TestRig
{
    public ManualTimeProvider Time { get; } = new();
    public ScriptedRandom Random { get; } = new();
    public FakePadFactory Pads { get; } = new();
    public RecordingSender Sender { get; } = new();
    public InMemoryTrustedDeviceStore Store { get; } = new();
    public PairingManager Pairing { get; }
    public SessionManager Sessions { get; }
    public ManetteServer Server { get; }

    public TestRig(SessionOptions? sessionOptions = null, ServerOptions? serverOptions = null)
    {
        Pairing = new PairingManager(Random, Time);
        Sessions = new SessionManager(Pads, sessionOptions, Random, Time);
        Server = new ManetteServer(Store, Pairing, Sessions, Sender, Time, serverOptions);
    }

    public void Send(byte[] datagram, IPEndPoint from) => Server.HandleDatagram(datagram, from);

    /// <summary>Opens a pairing window whose secret is scripted, so keys are predictable.</summary>
    public byte[] OpenPairing(byte[] secret)
    {
        Random.Enqueue(secret);
        Pairing.Open();
        return secret;
    }

    /// <summary>Pairs a brand-new phone through the real handshake and returns it ready to send INPUT.</summary>
    public TestPhone PairPhone(byte seed, string name = "Test phone")
    {
        var secret = Enumerable.Range(0, 16).Select(i => (byte)(seed + i)).ToArray();
        var id = new DeviceId(Enumerable.Range(0, 16).Select(i => (byte)(0x80 + seed + i)).ToArray());
        OpenPairing(secret);
        var phone = new TestPhone
        {
            DeviceId = id,
            DeviceKey = ManetteCrypto.DeriveDeviceKey(secret, id.ToArray()),
            ClientNonce = Enumerable.Range(0, 8).Select(i => (byte)(seed ^ (i * 17))).ToArray(),
            Address = new IPEndPoint(IPAddress.Parse("192.168.1." + (50 + seed)), 50000 + seed),
        };
        Sender.Clear();
        Send(phone.Hello(pairing: true, name), phone.Address);
        var welcome = Sender.OfType(MessageType.Welcome).Single();
        if (!phone.AcceptWelcome(welcome)) throw new InvalidOperationException("WELCOME failed verification.");
        Sender.Clear();
        return phone;
    }
}
