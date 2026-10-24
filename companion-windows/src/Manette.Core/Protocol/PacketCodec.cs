using System.Buffers.Binary;
using System.Text;
using System.Text.Unicode;
using static Manette.Core.Protocol.ProtocolConstants;

namespace Manette.Core.Protocol;

public readonly record struct PacketHeader(byte Version, MessageType Type, uint SessionId);

public sealed record HelloMessage(HelloFlags Flags, DeviceId DeviceId, byte[] ClientNonce, string Name);

public sealed record WelcomeMessage(
    uint SessionId, WelcomeFlags Flags, byte Slot, ushort RumbleHzMax, byte[] ServerNonce);

public readonly record struct RejectMessage(RejectReason Reason, byte MaxVersion);

public readonly record struct InputMessage(uint SessionId, ushort Seq, GamepadState State);

/// <summary>Used for both PING and PONG.</summary>
public readonly record struct PingMessage(uint SessionId, uint Timestamp);

public readonly record struct RumbleMessage(uint SessionId, ushort Seq, byte Large, byte Small);

/// <summary>
/// Byte-exact encoder/decoder for docs/protocol.md. Writers return the number of bytes written
/// (including the tag). Parsers are STRUCTURAL ONLY: they check length, header and field ranges but
/// never authenticate. Callers must verify the tag (ManetteCrypto.VerifyTag) before trusting a message.
/// </summary>
public static class PacketCodec
{
    // ---------------------------------------------------------------- header

    public static bool TryReadHeader(ReadOnlySpan<byte> packet, out PacketHeader header)
    {
        header = default;
        if (packet.Length < HeaderLength || packet[0] != Magic0 || packet[1] != Magic1) return false;
        header = new PacketHeader(
            packet[2], (MessageType)packet[3], BinaryPrimitives.ReadUInt32LittleEndian(packet[4..]));
        return true;
    }

    private static void WriteHeader(Span<byte> dst, MessageType type, uint sessionId)
    {
        dst[0] = Magic0;
        dst[1] = Magic1;
        dst[2] = ProtocolVersion;
        dst[3] = (byte)type;
        BinaryPrimitives.WriteUInt32LittleEndian(dst[4..], sessionId);
    }

    private static void Require(Span<byte> dst, int length)
    {
        if (dst.Length < length) throw new ArgumentException($"Destination needs at least {length} bytes.");
    }

    private static void Sign(
        Span<byte> dst, int bodyLength, ReadOnlySpan<byte> key, int tagLength, ReadOnlySpan<byte> extra = default) =>
        ManetteCrypto.ComputeTag(key, dst[..bodyLength], dst.Slice(bodyLength, tagLength), extra);

    // ----------------------------------------------------------------- HELLO

    public static int WriteHello(
        Span<byte> dst, HelloFlags flags, DeviceId deviceId, ReadOnlySpan<byte> clientNonce,
        string name, ReadOnlySpan<byte> deviceKey)
    {
        if (clientNonce.Length != NonceLength) throw new ArgumentException("client_nonce must be 8 bytes.");
        int nameBytes = Encoding.UTF8.GetByteCount(name);
        if (nameBytes > MaxNameBytes) throw new ArgumentException("Name exceeds 32 UTF-8 bytes.", nameof(name));
        int body = 34 + nameBytes;
        int total = body + HandshakeTagLength;
        Require(dst, total);

        WriteHeader(dst, MessageType.Hello, 0);
        dst[8] = (byte)flags;
        deviceId.CopyTo(dst.Slice(9, DeviceIdLength));
        clientNonce.CopyTo(dst.Slice(25, NonceLength));
        dst[33] = (byte)nameBytes;
        Encoding.UTF8.GetBytes(name, dst.Slice(34, nameBytes));
        Sign(dst, body, deviceKey, HandshakeTagLength);
        return total;
    }

    public static bool TryParseHello(ReadOnlySpan<byte> p, out HelloMessage? message)
    {
        message = null;
        if (p.Length < HelloMinLength || p.Length > HelloMaxLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Hello) return false;
        int nameLen = p[33];
        if (nameLen > MaxNameBytes || p.Length != 34 + nameLen + HandshakeTagLength) return false;
        var nameBytes = p.Slice(34, nameLen);
        if (!Utf8.IsValid(nameBytes)) return false;

        message = new HelloMessage(
            (HelloFlags)p[8],
            new DeviceId(p.Slice(9, DeviceIdLength)),
            p.Slice(25, NonceLength).ToArray(),
            Encoding.UTF8.GetString(nameBytes));
        return true;
    }

    // --------------------------------------------------------------- WELCOME

    /// <summary>The tag also covers the HELLO's client_nonce, binding this reply to that HELLO.</summary>
    public static int WriteWelcome(
        Span<byte> dst, uint sessionId, WelcomeFlags flags, byte slot, ushort rumbleHzMax,
        ReadOnlySpan<byte> serverNonce, ReadOnlySpan<byte> deviceKey, ReadOnlySpan<byte> clientNonce)
    {
        if (serverNonce.Length != NonceLength) throw new ArgumentException("server_nonce must be 8 bytes.");
        Require(dst, WelcomeLength);
        WriteHeader(dst, MessageType.Welcome, sessionId);
        dst[8] = (byte)flags;
        dst[9] = slot;
        BinaryPrimitives.WriteUInt16LittleEndian(dst[10..], rumbleHzMax);
        serverNonce.CopyTo(dst.Slice(12, NonceLength));
        Sign(dst, 20, deviceKey, HandshakeTagLength, clientNonce);
        return WelcomeLength;
    }

    public static bool TryParseWelcome(ReadOnlySpan<byte> p, out WelcomeMessage? message)
    {
        message = null;
        if (p.Length != WelcomeLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Welcome) return false;
        message = new WelcomeMessage(
            h.SessionId, (WelcomeFlags)p[8], p[9],
            BinaryPrimitives.ReadUInt16LittleEndian(p[10..]), p.Slice(12, NonceLength).ToArray());
        return true;
    }

    // ---------------------------------------------------------------- REJECT

    public static int WriteReject(Span<byte> dst, RejectReason reason, byte maxVersion)
    {
        Require(dst, RejectLength);
        WriteHeader(dst, MessageType.Reject, 0);
        dst[8] = (byte)reason;
        dst[9] = maxVersion;
        return RejectLength;
    }

    public static bool TryParseReject(ReadOnlySpan<byte> p, out RejectMessage message)
    {
        message = default;
        if (p.Length != RejectLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Reject) return false;
        message = new RejectMessage((RejectReason)p[8], p[9]);
        return true;
    }

    // ----------------------------------------------------------------- INPUT

    public static int WriteInput(
        Span<byte> dst, uint sessionId, ushort seq, in GamepadState s, ReadOnlySpan<byte> sessionKey)
    {
        Require(dst, InputLength);
        WriteHeader(dst, MessageType.Input, sessionId);
        BinaryPrimitives.WriteUInt16LittleEndian(dst[8..], seq);
        BinaryPrimitives.WriteUInt16LittleEndian(dst[10..], (ushort)s.Buttons);
        dst[12] = s.LeftTrigger;
        dst[13] = s.RightTrigger;
        BinaryPrimitives.WriteInt16LittleEndian(dst[14..], s.LeftX);
        BinaryPrimitives.WriteInt16LittleEndian(dst[16..], s.LeftY);
        BinaryPrimitives.WriteInt16LittleEndian(dst[18..], s.RightX);
        BinaryPrimitives.WriteInt16LittleEndian(dst[20..], s.RightY);
        Sign(dst, 22, sessionKey, SessionTagLength);
        return InputLength;
    }

    public static bool TryParseInput(ReadOnlySpan<byte> p, out InputMessage message)
    {
        message = default;
        if (p.Length != InputLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Input) return false;
        var state = new GamepadState(
            (GamepadButtons)BinaryPrimitives.ReadUInt16LittleEndian(p[10..]),
            p[12], p[13],
            BinaryPrimitives.ReadInt16LittleEndian(p[14..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[16..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[18..]),
            BinaryPrimitives.ReadInt16LittleEndian(p[20..]));
        message = new InputMessage(h.SessionId, BinaryPrimitives.ReadUInt16LittleEndian(p[8..]), state);
        return true;
    }

    // ------------------------------------------------------------ PING / PONG

    public static int WritePing(
        Span<byte> dst, MessageType type, uint sessionId, uint timestamp, ReadOnlySpan<byte> sessionKey)
    {
        if (type is not (MessageType.Ping or MessageType.Pong))
            throw new ArgumentException("Type must be Ping or Pong.", nameof(type));
        Require(dst, PingLength);
        WriteHeader(dst, type, sessionId);
        BinaryPrimitives.WriteUInt32LittleEndian(dst[8..], timestamp);
        Sign(dst, 12, sessionKey, SessionTagLength);
        return PingLength;
    }

    public static bool TryParsePing(ReadOnlySpan<byte> p, MessageType expected, out PingMessage message)
    {
        message = default;
        if (p.Length != PingLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != expected) return false;
        message = new PingMessage(h.SessionId, BinaryPrimitives.ReadUInt32LittleEndian(p[8..]));
        return true;
    }

    // ---------------------------------------------------------------- RUMBLE

    public static int WriteRumble(
        Span<byte> dst, uint sessionId, ushort seq, byte large, byte small, ReadOnlySpan<byte> sessionKey)
    {
        Require(dst, RumbleLength);
        WriteHeader(dst, MessageType.Rumble, sessionId);
        BinaryPrimitives.WriteUInt16LittleEndian(dst[8..], seq);
        dst[10] = large;
        dst[11] = small;
        Sign(dst, 12, sessionKey, SessionTagLength);
        return RumbleLength;
    }

    public static bool TryParseRumble(ReadOnlySpan<byte> p, out RumbleMessage message)
    {
        message = default;
        if (p.Length != RumbleLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Rumble) return false;
        message = new RumbleMessage(h.SessionId, BinaryPrimitives.ReadUInt16LittleEndian(p[8..]), p[10], p[11]);
        return true;
    }

    // ------------------------------------------------------------------- BYE

    public static int WriteBye(Span<byte> dst, uint sessionId, ReadOnlySpan<byte> sessionKey)
    {
        Require(dst, ByeLength);
        WriteHeader(dst, MessageType.Bye, sessionId);
        Sign(dst, HeaderLength, sessionKey, SessionTagLength);
        return ByeLength;
    }

    public static bool TryParseBye(ReadOnlySpan<byte> p, out uint sessionId)
    {
        sessionId = 0;
        if (p.Length != ByeLength) return false;
        if (!TryReadHeader(p, out var h) || h.Type != MessageType.Bye) return false;
        sessionId = h.SessionId;
        return true;
    }
}
