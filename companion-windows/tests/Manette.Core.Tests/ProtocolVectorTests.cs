using System.Net;
using System.Text.Json;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Xunit;
using static Manette.Core.Tests.Vectors;

namespace Manette.Core.Tests;

/// <summary>Byte-exact checks of the codec and crypto against protocol/test-vectors.json.</summary>
public class ProtocolVectorTests
{
    [Fact]
    public void DerivedKeysMatchVectors()
    {
        var deviceKey = ManetteCrypto.DeriveDeviceKey(SecretBytes, DeviceIdBytes);
        Assert.Equal(Hex.Of(DeviceKey), Hex.Of(deviceKey));

        var sessionKey = ManetteCrypto.DeriveSessionKey(deviceKey, ClientNonce, ServerNonce);
        Assert.Equal(Hex.Of(SessionKey), Hex.Of(sessionKey));
    }

    [Fact]
    public void InputPacketsEncodeToVectorsAndRoundTrip()
    {
        int count = 0;
        foreach (var v in Sect("input_packets").EnumerateArray())
        {
            string name = Str(v, "name");
            var state = State(v.GetProperty("state"));
            ushort seq = v.GetProperty("seq").GetUInt16();

            var buf = new byte[ProtocolConstants.InputLength];
            int n = PacketCodec.WriteInput(buf, SessionId, seq, state, SessionKey);
            Assert.Equal(ProtocolConstants.InputLength, n);
            Assert.Equal(Str(v, "packet_hex"), Hex.Of(buf));

            Assert.True(PacketCodec.TryParseInput(buf, out var parsed), name);
            Assert.Equal(SessionId, parsed.SessionId);
            Assert.Equal(seq, parsed.Seq);
            Assert.Equal(state, parsed.State);
            Assert.True(ManetteCrypto.VerifyTag(SessionKey, buf, ProtocolConstants.SessionTagLength), name);
            count++;
        }
        Assert.True(count >= 25, "expected the full set of input vectors");
    }

    [Fact]
    public void HandshakeMessagesEncodeToVectors()
    {
        var hs = Sect("handshake");
        var buf = new byte[128];

        var hello = hs.GetProperty("hello_pairing");
        int n = PacketCodec.WriteHello(buf, HelloFlags.Pairing, new DeviceId(DeviceIdBytes), ClientNonce, "Pixel 7", DeviceKey);
        Assert.Equal(Str(hello, "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParseHello(buf.AsSpan(0, n), out var parsedHello));
        Assert.Equal("Pixel 7", parsedHello!.Name);
        Assert.Equal(HelloFlags.Pairing, parsedHello.Flags);
        Assert.Equal(Hex.Of(ClientNonce), Hex.Of(parsedHello.ClientNonce));

        var helloEmpty = hs.GetProperty("hello_trusted_empty_name");
        n = PacketCodec.WriteHello(buf, HelloFlags.None, new DeviceId(DeviceIdBytes), ClientNonce, "", DeviceKey);
        Assert.Equal(Str(helloEmpty, "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.Equal(ProtocolConstants.HelloMinLength, n);

        var welcome = hs.GetProperty("welcome_paired_now");
        n = PacketCodec.WriteWelcome(buf, SessionId, WelcomeFlags.PairedNow, 0, 30, ServerNonce, DeviceKey, ClientNonce);
        Assert.Equal(Str(welcome, "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParseWelcome(buf.AsSpan(0, n), out var w));
        Assert.Equal(WelcomeFlags.PairedNow, w!.Flags);
        Assert.Equal((ushort)30, w.RumbleHzMax);

        var resumed = hs.GetProperty("welcome_resumed");
        n = PacketCodec.WriteWelcome(buf, SessionId, WelcomeFlags.Resumed, 2, 0, ServerNonce, DeviceKey, ClientNonce);
        Assert.Equal(Str(resumed, "packet_hex"), Hex.Of(buf.AsSpan(0, n)));

        var reject = hs.GetProperty("reject_server_full");
        n = PacketCodec.WriteReject(buf, RejectReason.ServerFull, 1);
        Assert.Equal(Str(reject, "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParseReject(buf.AsSpan(0, n), out var r));
        Assert.Equal(RejectReason.ServerFull, r.Reason);
    }

    [Fact]
    public void SessionMessagesEncodeToVectors()
    {
        var sm = Sect("session_messages");
        var buf = new byte[64];

        int n = PacketCodec.WritePing(buf, MessageType.Ping, SessionId, 123456, SessionKey);
        Assert.Equal(Str(sm.GetProperty("ping"), "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParsePing(buf.AsSpan(0, n), MessageType.Ping, out var ping));
        Assert.Equal(123456u, ping.Timestamp);
        Assert.False(PacketCodec.TryParsePing(buf.AsSpan(0, n), MessageType.Pong, out _));

        n = PacketCodec.WritePing(buf, MessageType.Pong, SessionId, 123456, SessionKey);
        Assert.Equal(Str(sm.GetProperty("pong"), "packet_hex"), Hex.Of(buf.AsSpan(0, n)));

        n = PacketCodec.WriteRumble(buf, SessionId, 7, 200, 50, SessionKey);
        Assert.Equal(Str(sm.GetProperty("rumble"), "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParseRumble(buf.AsSpan(0, n), out var rumble));
        Assert.Equal((byte)200, rumble.Large);
        Assert.Equal((byte)50, rumble.Small);

        n = PacketCodec.WriteBye(buf, SessionId, SessionKey);
        Assert.Equal(Str(sm.GetProperty("bye"), "packet_hex"), Hex.Of(buf.AsSpan(0, n)));
        Assert.True(PacketCodec.TryParseBye(buf.AsSpan(0, n), out var byeId));
        Assert.Equal(SessionId, byeId);
    }

    [Fact]
    public void ValidTagsVerifyAndWelcomeIsBoundToItsHello()
    {
        var hs = Sect("handshake");
        var hello = Hex.Parse(Str(hs.GetProperty("hello_pairing"), "packet_hex"));
        Assert.True(ManetteCrypto.VerifyTag(DeviceKey, hello, ProtocolConstants.HandshakeTagLength));

        var welcome = Hex.Parse(Str(hs.GetProperty("welcome_paired_now"), "packet_hex"));
        Assert.True(ManetteCrypto.VerifyTag(DeviceKey, welcome, ProtocolConstants.HandshakeTagLength, ClientNonce));
        // Same bytes, but verified against a different HELLO's nonce: must fail (replay binding).
        Assert.False(ManetteCrypto.VerifyTag(DeviceKey, welcome, ProtocolConstants.HandshakeTagLength, OtherClientNonce));
        // And without any nonce at all.
        Assert.False(ManetteCrypto.VerifyTag(DeviceKey, welcome, ProtocolConstants.HandshakeTagLength));
    }

    [Fact]
    public void EveryTamperVectorFailsVerification()
    {
        var keys = new Dictionary<string, byte[]> { ["session_key"] = SessionKey, ["device_key"] = DeviceKey };
        int count = 0;
        foreach (var t in Sect("tamper_must_fail").EnumerateArray())
        {
            string name = Str(t, "name");
            var packet = Hex.Parse(Str(t, "packet_hex"));
            var extra = t.TryGetProperty("extra_hmac_input_hex", out var ex) ? Hex.Parse(ex.GetString()!) : [];
            bool ok = ManetteCrypto.VerifyTag(keys[Str(t, "key")], packet, t.GetProperty("tag_len").GetInt32(), extra);
            Assert.False(ok, "tamper vector should fail: " + name);
            Assert.False(t.GetProperty("expected_valid").GetBoolean(), name);
            count++;
        }
        Assert.True(count >= 8);
    }

    [Fact]
    public void SequenceComparisonMatchesVectors()
    {
        foreach (var c in Sect("seq_is_newer").GetProperty("cases").EnumerateArray())
        {
            ushort last = c.GetProperty("last").GetUInt16();
            ushort next = c.GetProperty("new").GetUInt16();
            Assert.True(
                c.GetProperty("newer").GetBoolean() == SequenceNumber.IsNewer(last, next),
                $"IsNewer({last}, {next})");
        }
    }

    [Fact]
    public void ScreenYInversionMatchesVectors()
    {
        foreach (var c in Sect("screen_to_wire_y").GetProperty("cases").EnumerateArray())
        {
            int screen = c.GetProperty("screen_y").GetInt32();
            Assert.Equal(c.GetProperty("wire_y").GetInt16(), GamepadState.ScreenYToWire(screen));
        }
        // Beyond the i16 range it still clamps rather than wrapping.
        Assert.Equal(short.MaxValue, GamepadState.ScreenYToWire(int.MinValue));
        Assert.Equal(short.MinValue, GamepadState.ScreenYToWire(int.MaxValue));
    }

    [Fact]
    public void PairingCodeFormatsMatchVectors()
    {
        var pc = Sect("pairing_code");
        Assert.Equal(Str(pc, "base32_unpadded"), PairingCode.ToBase32(SecretBytes));
        Assert.Equal(Str(pc, "typed_code"), PairingCode.ToTypedCode(SecretBytes));

        string typed = Str(pc, "typed_code");
        foreach (var variant in new[] { typed, typed.ToLowerInvariant(), typed.Replace("-", ""), " " + typed + " " })
        {
            Assert.True(PairingCode.TryParseTypedCode(variant, out var secret), variant);
            Assert.Equal(Hex.Of(SecretBytes), Hex.Of(secret));
        }

        Assert.False(PairingCode.TryParseTypedCode("AAAQ-EAYE-1111-OCAJ-BIFQ-YDIO-B4", out _)); // '1' is not Base32
        Assert.False(PairingCode.TryParseTypedCode("AAAQ-EAYE", out _));                         // too short
    }

    [Fact]
    public void PairingLinkRoundTripsAndMatchesVector()
    {
        string expected = Str(Sect("pairing_code"), "qr_uri_example");
        string built = PairingCode.BuildLink(
            ["192.168.1.20", "192.168.137.1"], 26760, SecretBytes, "DESKTOP-GAMING");
        Assert.Equal(expected, built);

        Assert.True(PairingCode.TryParseLink(expected, out var link));
        Assert.Equal((byte)1, link!.Version);
        Assert.Equal((ushort)26760, link.Port);
        Assert.Equal("DESKTOP-GAMING", link.Name);
        Assert.Equal(2, link.Hosts.Count);
        Assert.Equal("192.168.137.1", link.Hosts[1]);
        Assert.Equal(Hex.Of(SecretBytes), Hex.Of(link.Secret));

        Assert.False(PairingCode.TryParseLink("https://example.com", out _));
        Assert.False(PairingCode.TryParseLink("manette://pair?v=1&p=26760", out _)); // no secret
    }

    [Fact]
    public void ParsersRejectMalformedInput()
    {
        var input = Hex.Parse(Str(Sect("input_packets")[0], "packet_hex"));

        Assert.False(PacketCodec.TryParseInput(input.AsSpan(0, 29), out _));              // truncated
        Assert.False(PacketCodec.TryParseInput([..input, 0], out _));                      // too long
        var badMagic = (byte[])input.Clone(); badMagic[0] = 0;
        Assert.False(PacketCodec.TryParseInput(badMagic, out _));
        var wrongType = (byte[])input.Clone(); wrongType[3] = (byte)MessageType.Bye;
        Assert.False(PacketCodec.TryParseInput(wrongType, out _));
        Assert.False(PacketCodec.TryReadHeader(new byte[7], out _));

        var hello = Hex.Parse(Str(Sect("handshake").GetProperty("hello_pairing"), "packet_hex"));
        var badNameLen = (byte[])hello.Clone(); badNameLen[33] = 33;                      // over the 32-byte cap
        Assert.False(PacketCodec.TryParseHello(badNameLen, out _));
        var mismatch = (byte[])hello.Clone(); mismatch[33] = 3;                           // length disagrees with datagram size
        Assert.False(PacketCodec.TryParseHello(mismatch, out _));
        var badUtf8 = (byte[])hello.Clone(); badUtf8[34] = 0xFF;
        Assert.False(PacketCodec.TryParseHello(badUtf8, out _));

        Assert.Throws<ArgumentException>(() =>
            PacketCodec.WriteHello(new byte[128], HelloFlags.None, DeviceId.NewRandom(), new byte[8], new string('x', 33), DeviceKey));
    }
}
