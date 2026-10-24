using System.Net;
using Manette.Core.Protocol;
using Manette.Core.Server;
using Manette.Core.Sessions;
using Xunit;
using static Manette.Core.Tests.Vectors;

namespace Manette.Core.Tests;

public class ServerTests
{
    private static readonly IPEndPoint VectorPhoneAddress = new(IPAddress.Parse("192.168.1.50"), 50000);

    /// <summary>Replays the vector pairing HELLO through the real server so session 42 / session_key line up.</summary>
    private static (TestRig rig, TestPhone phone) ConnectVectorPhone(SessionOptions? options = null)
    {
        var rig = new TestRig(options ?? new SessionOptions { RumbleHzMax = 30 });
        rig.OpenPairing(SecretBytes);
        rig.Random.Enqueue(BitConverter.GetBytes(SessionId));   // session id draw
        rig.Random.Enqueue(ServerNonce);                        // server nonce draw

        var hello = Hex.Parse(Str(Sect("handshake").GetProperty("hello_pairing"), "packet_hex"));
        rig.Send(hello, VectorPhoneAddress);

        var phone = new TestPhone
        {
            DeviceId = new DeviceId(DeviceIdBytes),
            DeviceKey = DeviceKey,
            ClientNonce = ClientNonce,
            SessionId = SessionId,
            SessionKey = SessionKey,
            Address = VectorPhoneAddress,
        };
        return (rig, phone);
    }

    private static FakePad OnlyPad(TestRig rig) => rig.Pads.Created.Single();

    // ------------------------------------------------------------ handshake

    [Fact]
    public void PairingHelloProducesTheExactVectorWelcome()
    {
        var (rig, _) = ConnectVectorPhone();

        var welcome = rig.Sender.OfType(MessageType.Welcome).Single();
        Assert.Equal(Str(Sect("handshake").GetProperty("welcome_paired_now"), "packet_hex"), Hex.Of(welcome));
        Assert.Equal(VectorPhoneAddress, rig.Sender.Sent.Single().To);

        Assert.Single(rig.Sessions.Snapshot());
        Assert.True(rig.Store.TryGet(new DeviceId(DeviceIdBytes), out var trusted));
        Assert.Equal("Pixel 7", trusted.Name);
        Assert.Equal(Hex.Of(DeviceKey), Hex.Of(trusted.DeviceKey));
        Assert.False(rig.Pairing.IsOpen, "pairing secret is single-use");
    }

    [Fact]
    public void TrustedDeviceReconnectsWithoutPairingAndResumesItsPad()
    {
        var rig = new TestRig();
        var phone = rig.PairPhone(1);
        uint firstId = phone.SessionId;
        var pad = OnlyPad(rig);

        phone.ClientNonce[0] ^= 0xFF;                       // a fresh HELLO uses a fresh nonce
        rig.Send(phone.Hello(pairing: false), phone.Address);

        var welcome = rig.Sender.OfType(MessageType.Welcome).Single();
        Assert.True(PacketCodec.TryParseWelcome(welcome, out var w));
        Assert.True((w!.Flags & WelcomeFlags.Resumed) != 0);
        Assert.True((w.Flags & WelcomeFlags.PairedNow) == 0);
        Assert.True(phone.AcceptWelcome(welcome));
        Assert.True(phone.SessionId != firstId, "a resumed session gets a new session id");
        Assert.Single(rig.Pads.Created);                    // same pad, not a second one
        Assert.False(pad.Disposed);

        // The old session id/key no longer works; the new one does.
        rig.Send(phone.Input(1, new GamepadState(GamepadButtons.A, 0, 0, 0, 0, 0, 0)), phone.Address);
        Assert.Equal(GamepadButtons.A, pad.Last.Buttons);
    }

    [Fact]
    public void RetriedPairingHelloAfterTheWindowClosedStillWorksForTheSamePhone()
    {
        // The first pairing HELLO succeeded (and closed the window), but its WELCOME was lost, so the phone retries
        // with a fresh nonce and the PAIRING flag still set.
        var rig = new TestRig();
        var phone = rig.PairPhone(1);
        Assert.False(rig.Pairing.IsOpen);

        phone.ClientNonce[0] ^= 0x5A;
        rig.Sender.Clear();
        rig.Send(phone.Hello(pairing: true), phone.Address);

        var welcome = rig.Sender.OfType(MessageType.Welcome).Single();
        Assert.True(PacketCodec.TryParseWelcome(welcome, out var w));
        Assert.True((w!.Flags & WelcomeFlags.PairedNow) == 0, "it is not a new pairing");
        Assert.True((w.Flags & WelcomeFlags.Resumed) != 0);
        Assert.True(phone.AcceptWelcome(welcome));
        Assert.Single(rig.Pads.Created);
        Assert.Single(rig.Store.List());
    }

    [Fact]
    public void ARetriedPairingHelloFromAnUnpairedPhoneIsStillRejected()
    {
        var rig = new TestRig();
        rig.PairPhone(1);                                    // closes the window
        var stranger = new TestPhone { DeviceId = DeviceId.NewRandom(), DeviceKey = new byte[32], ClientNonce = new byte[8] };
        rig.Sender.Clear();
        rig.Send(stranger.Hello(pairing: true), stranger.Address);
        Assert.Equal(RejectReason.PairingNotOpen, rig.Sender.LastReject());
        Assert.Single(rig.Pads.Created);
    }

    [Fact]
    public void UnknownDeviceWithoutPairingIsToldItIsNotPaired()
    {
        var rig = new TestRig();
        var stranger = new TestPhone { DeviceId = DeviceId.NewRandom(), DeviceKey = new byte[32], ClientNonce = new byte[8] };
        rig.Send(stranger.Hello(pairing: false), stranger.Address);

        Assert.Equal(RejectReason.NotPaired, rig.Sender.LastReject());
        Assert.Empty(rig.Pads.Created);
    }

    [Fact]
    public void PairingHelloWithNoOpenWindowIsRejected()
    {
        var rig = new TestRig();
        var phone = new TestPhone { DeviceId = DeviceId.NewRandom(), DeviceKey = new byte[32], ClientNonce = new byte[8] };
        rig.Send(phone.Hello(pairing: true), phone.Address);

        Assert.Equal(RejectReason.PairingNotOpen, rig.Sender.LastReject());
        Assert.Empty(rig.Pads.Created);
    }

    [Fact]
    public void WrongSecretIsDroppedSilentlyAndCreatesNothing()
    {
        var rig = new TestRig();
        rig.OpenPairing(Enumerable.Range(0, 16).Select(i => (byte)i).ToArray());
        var id = DeviceId.NewRandom();
        var wrongKey = ManetteCrypto.DeriveDeviceKey(Enumerable.Repeat((byte)9, 16).ToArray(), id.ToArray());
        var phone = new TestPhone { DeviceId = id, DeviceKey = wrongKey, ClientNonce = new byte[8] };

        rig.Send(phone.Hello(pairing: true), phone.Address);

        Assert.Empty(rig.Sender.Sent);                      // no reply, so no oracle
        Assert.Empty(rig.Pads.Created);
        Assert.Empty(rig.Store.List());
        Assert.True(rig.Pairing.IsOpen, "a failed attempt must not burn the window");
    }

    [Fact]
    public void PairingWindowExpiresAfterFiveMinutes()
    {
        var rig = new TestRig();
        var secret = rig.OpenPairing(Enumerable.Range(0, 16).Select(i => (byte)(i + 3)).ToArray());
        var id = DeviceId.NewRandom();
        var phone = new TestPhone
        {
            DeviceId = id, ClientNonce = new byte[8],
            DeviceKey = ManetteCrypto.DeriveDeviceKey(secret, id.ToArray()),
        };

        rig.Time.Advance(TimeSpan.FromMinutes(5) + TimeSpan.FromSeconds(1));
        rig.Send(phone.Hello(pairing: true), phone.Address);

        Assert.Equal(RejectReason.PairingNotOpen, rig.Sender.LastReject());
        Assert.Empty(rig.Pads.Created);
    }

    [Fact]
    public void ServerFullRejectsWithoutBurningThePairingWindow()
    {
        var rig = new TestRig(new SessionOptions { MaxSessions = 1 });
        rig.PairPhone(1);

        var secret = rig.OpenPairing(Enumerable.Range(0, 16).Select(i => (byte)(i + 40)).ToArray());
        var id = DeviceId.NewRandom();
        var second = new TestPhone
        {
            DeviceId = id, ClientNonce = new byte[8],
            DeviceKey = ManetteCrypto.DeriveDeviceKey(secret, id.ToArray()),
            Address = new IPEndPoint(IPAddress.Parse("192.168.1.99"), 50001),
        };
        rig.Sender.Clear();
        rig.Send(second.Hello(pairing: true), second.Address);

        Assert.Equal(RejectReason.ServerFull, rig.Sender.LastReject());
        Assert.True(rig.Pairing.IsOpen);
        Assert.False(rig.Store.TryGet(id, out _), "a rejected phone must not become trusted");
    }

    [Fact]
    public void PadFactoryFailureAlsoRejectsAsServerFull()
    {
        var rig = new TestRig();
        rig.Pads.Limit = 0;
        var secret = rig.OpenPairing(Enumerable.Range(0, 16).Select(i => (byte)i).ToArray());
        var id = DeviceId.NewRandom();
        var phone = new TestPhone
        {
            DeviceId = id, ClientNonce = new byte[8],
            DeviceKey = ManetteCrypto.DeriveDeviceKey(secret, id.ToArray()),
        };
        rig.Send(phone.Hello(pairing: true), phone.Address);
        Assert.Equal(RejectReason.ServerFull, rig.Sender.LastReject());
    }

    [Fact]
    public void UnsupportedVersionHelloGetsRejectWithMaxVersion()
    {
        var rig = new TestRig();
        var phone = new TestPhone { DeviceId = DeviceId.NewRandom(), DeviceKey = new byte[32], ClientNonce = new byte[8] };
        var hello = phone.Hello(pairing: false);
        hello[2] = 2;                                       // claim protocol version 2

        rig.Send(hello, phone.Address);

        var reject = rig.Sender.OfType(MessageType.Reject).Single();
        Assert.True(PacketCodec.TryParseReject(reject, out var r));
        Assert.Equal(RejectReason.UnsupportedVersion, r.Reason);
        Assert.Equal(ProtocolConstants.ProtocolVersion, r.MaxVersion);
    }

    [Fact]
    public void NonHelloFromUnsupportedVersionIsDroppedSilently()
    {
        var (rig, phone) = ConnectVectorPhone();
        rig.Sender.Clear();
        var input = phone.Input(1, new GamepadState(GamepadButtons.A, 0, 0, 0, 0, 0, 0));
        input[2] = 2;
        rig.Send(input, phone.Address);
        Assert.Empty(rig.Sender.Sent);
        Assert.Empty(OnlyPad(rig).Updates);
    }

    [Fact]
    public void HelloFloodIsRateLimitedPerSourceAddress()
    {
        var rig = new TestRig(serverOptions: new ServerOptions { HelloBurst = 5 });
        var stranger = new TestPhone { DeviceId = DeviceId.NewRandom(), DeviceKey = new byte[32], ClientNonce = new byte[8] };
        var hello = stranger.Hello(pairing: false);

        for (int i = 0; i < 5; i++) rig.Send(hello, stranger.Address);
        Assert.Equal(RejectReason.NotPaired, rig.Sender.LastReject());

        rig.Send(hello, stranger.Address);
        Assert.Equal(RejectReason.RateLimited, rig.Sender.LastReject());

        // A different source is unaffected, and the original recovers after the window.
        var other = new IPEndPoint(IPAddress.Parse("10.0.0.9"), 1234);
        rig.Send(hello, other);
        Assert.Equal(RejectReason.NotPaired, rig.Sender.LastReject());

        rig.Time.Advance(TimeSpan.FromSeconds(1.1));
        rig.Send(hello, stranger.Address);
        Assert.Equal(RejectReason.NotPaired, rig.Sender.LastReject());
    }

    // ---------------------------------------------------------------- input

    [Fact]
    public void EveryInputVectorDrivesTheVirtualPad()
    {
        foreach (var v in Sect("input_packets").EnumerateArray())
        {
            string name = Str(v, "name");
            var (rig, _) = ConnectVectorPhone();
            var pad = OnlyPad(rig);
            var expected = State(v.GetProperty("state"));

            rig.Send(Hex.Parse(Str(v, "packet_hex")), VectorPhoneAddress);

            Assert.Equal(1, rig.Sessions.Snapshot().Single().PacketsAccepted);
            if (expected == GamepadState.Neutral)
                Assert.True(pad.Updates.Count == 0, name + ": neutral needs no driver call");
            else
                Assert.Equal(expected, pad.Last);
        }
    }

    [Fact]
    public void TamperedInputVectorsAreDropped()
    {
        foreach (var t in Sect("tamper_must_fail").EnumerateArray())
        {
            if (!Str(t, "name").StartsWith("input_") && Str(t, "name") != "truncated_input") continue;
            if (Str(t, "name") == "input_wrong_key") continue;   // a valid packet; wrong key only matters to the crypto test

            var (rig, _) = ConnectVectorPhone();
            rig.Sender.Clear();
            rig.Send(Hex.Parse(Str(t, "packet_hex")), VectorPhoneAddress);

            Assert.Equal(0, rig.Sessions.Snapshot().Single().PacketsAccepted);
            Assert.True(OnlyPad(rig).Updates.Count == 0, Str(t, "name"));
            Assert.Empty(rig.Sender.Sent);
        }
    }

    [Fact]
    public void TamperedHelloVectorsNeverProduceAWelcome()
    {
        foreach (var t in Sect("tamper_must_fail").EnumerateArray())
        {
            if (!Str(t, "name").StartsWith("hello_")) continue;

            var rig = new TestRig();
            rig.OpenPairing(SecretBytes);
            rig.Send(Hex.Parse(Str(t, "packet_hex")), VectorPhoneAddress);

            Assert.Empty(rig.Sender.OfType(MessageType.Welcome));
            Assert.Empty(rig.Pads.Created);
            Assert.Empty(rig.Sessions.Snapshot());
        }
    }

    [Fact]
    public void OldAndDuplicateSequenceNumbersAreIgnored()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        GamepadState A = new(GamepadButtons.A, 0, 0, 0, 0, 0, 0), B = new(GamepadButtons.B, 0, 0, 0, 0, 0, 0);

        rig.Send(phone.Input(5, A), VectorPhoneAddress);
        rig.Send(phone.Input(4, B), VectorPhoneAddress);    // older
        rig.Send(phone.Input(5, B), VectorPhoneAddress);    // duplicate seq, different state
        Assert.Equal(GamepadButtons.A, pad.Last.Buttons);
        Assert.Equal(1, rig.Sessions.Snapshot().Single().PacketsAccepted);

        rig.Send(phone.Input(6, B), VectorPhoneAddress);
        Assert.Equal(GamepadButtons.B, pad.Last.Buttons);
    }

    [Fact]
    public void SequenceWrapAroundIsAccepted()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        rig.Send(phone.Input(65535, new GamepadState(GamepadButtons.A, 0, 0, 0, 0, 0, 0)), VectorPhoneAddress);
        rig.Send(phone.Input(0, new GamepadState(GamepadButtons.B, 0, 0, 0, 0, 0, 0)), VectorPhoneAddress);
        Assert.Equal(GamepadButtons.B, pad.Last.Buttons);
    }

    [Fact]
    public void KeepalivesDoNotRepeatDriverUpdates()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        var state = new GamepadState(GamepadButtons.A, 0, 128, 100, 0, 0, 0);

        for (ushort seq = 1; seq <= 10; seq++) rig.Send(phone.Input(seq, state), VectorPhoneAddress);

        Assert.Single(pad.Updates);
        Assert.Equal(10, rig.Sessions.Snapshot().Single().PacketsAccepted);
    }

    [Fact]
    public void InputFromANewAddressMovesTheSession()
    {
        var (rig, phone) = ConnectVectorPhone();
        var tether = new IPEndPoint(IPAddress.Parse("192.168.137.2"), 40000);

        rig.Send(phone.Input(1, GamepadState.Neutral), tether);

        Assert.Equal(tether, rig.Sessions.Snapshot().Single().Remote);
    }

    [Fact]
    public void TwoPhonesGetSeparatePadsAndNeverCrossTalk()
    {
        var rig = new TestRig();
        var p1 = rig.PairPhone(1);
        var p2 = rig.PairPhone(2);
        Assert.Equal(2, rig.Pads.Created.Count);

        rig.Send(p1.Input(1, new GamepadState(GamepadButtons.A, 0, 0, 0, 0, 0, 0)), p1.Address);
        rig.Send(p2.Input(1, new GamepadState(GamepadButtons.B, 0, 0, 0, 0, 0, 0)), p2.Address);

        Assert.Equal(GamepadButtons.A, rig.Pads.Created[0].Last.Buttons);
        Assert.Equal(GamepadButtons.B, rig.Pads.Created[1].Last.Buttons);

        // A packet signed with phone 1's key but carrying phone 2's session id is rejected.
        var forged = new TestPhone { SessionId = p2.SessionId, SessionKey = p1.SessionKey }
            .Input(2, new GamepadState(GamepadButtons.X, 0, 0, 0, 0, 0, 0));
        rig.Send(forged, p1.Address);
        Assert.Equal(GamepadButtons.B, rig.Pads.Created[1].Last.Buttons);
    }

    // ------------------------------------------------------------ ping / bye

    [Fact]
    public void PingIsAnsweredWithAVerifiablePongEchoingTheTimestamp()
    {
        var (rig, phone) = ConnectVectorPhone();
        rig.Sender.Clear();

        rig.Send(phone.Ping(123456), VectorPhoneAddress);

        var pong = rig.Sender.OfType(MessageType.Pong).Single();
        Assert.Equal(Str(Sect("session_messages").GetProperty("pong"), "packet_hex"), Hex.Of(pong));
        Assert.True(ManetteCrypto.VerifyTag(SessionKey, pong, ProtocolConstants.SessionTagLength));
    }

    [Fact]
    public void ForgedPingGetsNoReply()
    {
        var (rig, phone) = ConnectVectorPhone();
        rig.Sender.Clear();
        var ping = phone.Ping(1);
        ping[9] ^= 1;
        rig.Send(ping, VectorPhoneAddress);
        Assert.Empty(rig.Sender.Sent);
    }

    [Fact]
    public void ByeResetsThePadAndStopsAcceptingInput()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        rig.Send(phone.Input(1, new GamepadState(GamepadButtons.A, 255, 0, 5000, 0, 0, 0)), VectorPhoneAddress);

        rig.Send(phone.Bye(), VectorPhoneAddress);

        Assert.Equal(GamepadState.Neutral, pad.Last);
        Assert.Equal(SessionState.Grace, rig.Sessions.Snapshot().Single().State);
        rig.Send(phone.Input(2, new GamepadState(GamepadButtons.B, 0, 0, 0, 0, 0, 0)), VectorPhoneAddress);
        Assert.Equal(GamepadState.Neutral, pad.Last);
    }

    // ------------------------------------------------------------ lifecycle

    [Fact]
    public void SilentPhoneGoesNeutralThenGraceThenIsRemoved()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        rig.Send(phone.Input(1, new GamepadState(GamepadButtons.A, 255, 255, 30000, -30000, 0, 0)), VectorPhoneAddress);

        rig.Time.Advance(TimeSpan.FromMilliseconds(499));
        rig.Server.Tick();
        Assert.Equal(SessionState.Active, rig.Sessions.Snapshot().Single().State);
        Assert.Equal(GamepadButtons.A, pad.Last.Buttons);

        rig.Time.Advance(TimeSpan.FromMilliseconds(2));
        rig.Server.Tick();
        Assert.Equal(SessionState.Stale, rig.Sessions.Snapshot().Single().State);
        Assert.Equal(GamepadState.Neutral, pad.Last);       // nothing may stay held

        rig.Time.Advance(TimeSpan.FromSeconds(10));
        rig.Server.Tick();
        Assert.Equal(SessionState.Grace, rig.Sessions.Snapshot().Single().State);
        Assert.False(pad.Disposed);

        rig.Time.Advance(TimeSpan.FromSeconds(9.9));
        rig.Server.Tick();
        Assert.False(pad.Disposed);

        rig.Time.Advance(TimeSpan.FromSeconds(0.2));
        rig.Server.Tick();
        Assert.True(pad.Disposed);
        Assert.Empty(rig.Sessions.Snapshot());
    }

    [Fact]
    public void StaleSessionWakesUpWhenPacketsReturn()
    {
        var (rig, phone) = ConnectVectorPhone();
        var pad = OnlyPad(rig);
        rig.Send(phone.Input(1, new GamepadState(GamepadButtons.A, 0, 0, 0, 0, 0, 0)), VectorPhoneAddress);
        rig.Time.Advance(TimeSpan.FromSeconds(1));
        rig.Server.Tick();
        Assert.Equal(SessionState.Stale, rig.Sessions.Snapshot().Single().State);

        rig.Send(phone.Input(2, new GamepadState(GamepadButtons.B, 0, 0, 0, 0, 0, 0)), VectorPhoneAddress);

        Assert.Equal(SessionState.Active, rig.Sessions.Snapshot().Single().State);
        Assert.Equal(GamepadButtons.B, pad.Last.Buttons);
    }

    [Fact]
    public void ReconnectDuringGraceKeepsTheSamePadAndSlot()
    {
        var rig = new TestRig();
        var phone = rig.PairPhone(3);
        var pad = OnlyPad(rig);
        rig.Send(phone.Bye(), phone.Address);                       // session enters Grace

        rig.Time.Advance(TimeSpan.FromSeconds(5));
        rig.Server.Tick();
        phone.ClientNonce[1] ^= 0x55;
        rig.Send(phone.Hello(pairing: false), phone.Address);

        var welcome = rig.Sender.OfType(MessageType.Welcome).Single();
        Assert.True(PacketCodec.TryParseWelcome(welcome, out var w));
        Assert.True((w!.Flags & WelcomeFlags.Resumed) != 0);
        Assert.Equal(pad.Slot, w.Slot);
        Assert.Single(rig.Pads.Created);
        Assert.False(pad.Disposed);
        Assert.Equal(SessionState.Active, rig.Sessions.Snapshot().Single().State);
    }

    // --------------------------------------------------------------- rumble

    [Fact]
    public void RumbleIsSignedThrottledAndAlwaysAllowsStop()
    {
        var (rig, _) = ConnectVectorPhone();      // RumbleHzMax = 30 => ~33 ms minimum gap
        var pad = OnlyPad(rig);
        rig.Sender.Clear();

        pad.RaiseRumble(200, 50);
        var first = rig.Sender.OfType(MessageType.Rumble).Single();
        Assert.True(ManetteCrypto.VerifyTag(SessionKey, first, ProtocolConstants.SessionTagLength));
        Assert.True(PacketCodec.TryParseRumble(first, out var m));
        Assert.Equal(SessionId, m.SessionId);
        Assert.Equal((byte)200, m.Large);
        Assert.Equal((ushort)1, m.Seq);

        pad.RaiseRumble(100, 100);                 // inside the gap: dropped
        Assert.Single(rig.Sender.OfType(MessageType.Rumble));

        pad.RaiseRumble(0, 0);                     // stop is never throttled
        Assert.Equal(2, rig.Sender.OfType(MessageType.Rumble).Count());

        rig.Time.Advance(TimeSpan.FromMilliseconds(40));
        pad.RaiseRumble(10, 10);
        Assert.Equal(3, rig.Sender.OfType(MessageType.Rumble).Count());
    }

    [Fact]
    public void RumbleIsOffByDefault()
    {
        var rig = new TestRig();                   // RumbleHzMax defaults to 0
        rig.PairPhone(1);
        rig.Pads.Created.Single().RaiseRumble(255, 255);
        Assert.Empty(rig.Sender.Sent);
    }
}
