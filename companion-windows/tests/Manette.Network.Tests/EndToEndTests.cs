using System.Net;
using System.Net.Sockets;
using Manette.Core.Protocol;
using Manette.Core.Sessions;
using Manette.FakeClient;
using Xunit;

namespace Manette.Network.Tests;

/// <summary>Real UDP sockets on loopback: the actual host, the actual server logic, the reference phone client.</summary>
public class EndToEndTests
{
    private static ManetteHost StartHost(FakePadFactory pads, SessionOptions? sessions = null)
    {
        var host = new ManetteHost(pads, new ManetteHostOptions
        {
            BindAddress = IPAddress.Loopback,
            Port = 0,
            EnableMdns = false,
            Sessions = sessions ?? new SessionOptions(),
        });
        host.Start();
        return host;
    }

    private static PhoneClient NewPhone(ManetteHost host, string name = "Phone") =>
        new(new PhoneIdentity(DeviceId.NewRandom(), null, name), host.LocalEndPoint);

    private static GamepadState Pressed(GamepadButtons b, short lx = 0) => new(b, 0, 0, lx, 0, 0, 0);

    [Fact]
    public async Task PairingThenInputReachesTheVirtualPad()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads);
        using var phone = NewPhone(host);

        var (offer, _) = host.OpenPairing();
        Assert.True(await phone.PairAsync(offer.Secret, TimeSpan.FromSeconds(3)));
        Assert.Equal(ClientState.Connected, phone.State);
        Assert.Equal((byte)0, phone.Slot);
        Assert.Equal(1, pads.Created.Count);

        phone.SetState(Pressed(GamepadButtons.A | GamepadButtons.RightShoulder, lx: 12345));
        Assert.True(await Wait.Until(() => pads.Created[0].Last == Pressed(GamepadButtons.A | GamepadButtons.RightShoulder, 12345)));

        phone.SetState(GamepadState.Neutral);
        Assert.True(await Wait.Until(() => pads.Created[0].Last == GamepadState.Neutral));
    }

    [Fact]
    public async Task WrongPairingSecretNeverConnects()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads);
        using var phone = NewPhone(host);

        host.OpenPairing();
        Assert.False(await phone.PairAsync(new byte[16], TimeSpan.FromMilliseconds(1200)));
        Assert.Equal(ClientState.Disconnected, phone.State);
        Assert.Equal(0, pads.Created.Count);
        Assert.True(host.Pairing.IsOpen, "a failed attempt must not burn the pairing window");
    }

    [Fact]
    public async Task PairingWindowIsSingleUse()
    {
        using var host = StartHost(new FakePadFactory());
        using var first = NewPhone(host, "first");
        using var second = NewPhone(host, "second");

        var (offer, _) = host.OpenPairing();
        Assert.True(await first.PairAsync(offer.Secret, TimeSpan.FromSeconds(3)));

        Assert.False(await second.PairAsync(offer.Secret, TimeSpan.FromSeconds(3)));
        Assert.Equal(RejectReason.PairingNotOpen, second.LastReject);
    }

    [Fact]
    public async Task UnpairedDeviceIsToldItIsNotPaired()
    {
        using var host = StartHost(new FakePadFactory());
        var stranger = new PhoneIdentity(DeviceId.NewRandom(), new byte[32], "stranger");
        using var phone = new PhoneClient(stranger, host.LocalEndPoint);

        Assert.False(await phone.ConnectAsync(TimeSpan.FromSeconds(3)));
        Assert.Equal(RejectReason.NotPaired, phone.LastReject);
    }

    [Fact]
    public async Task ByeThenReconnectKeepsTheSamePad()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads);
        using var phone = NewPhone(host);
        var (offer, _) = host.OpenPairing();
        Assert.True(await phone.PairAsync(offer.Secret, TimeSpan.FromSeconds(3)));

        phone.SetState(Pressed(GamepadButtons.B));
        Assert.True(await Wait.Until(() => pads.Created[0].Last.Buttons == GamepadButtons.B));
        phone.SendBye();
        Assert.True(await Wait.Until(() => pads.Created[0].Last == GamepadState.Neutral));

        Assert.True(await phone.ConnectAsync(TimeSpan.FromSeconds(3)));     // trusted: no pairing needed
        Assert.Equal(1, pads.Created.Count);                                // resumed, not recreated
        phone.SetState(Pressed(GamepadButtons.X));
        Assert.True(await Wait.Until(() => pads.Created[0].Last.Buttons == GamepadButtons.X));
    }

    [Fact]
    public async Task TwoPhonesAreTwoIndependentControllers()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads);
        using var p1 = NewPhone(host, "one");
        using var p2 = NewPhone(host, "two");

        Assert.True(await p1.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));
        Assert.True(await p2.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));
        Assert.Equal(2, pads.Created.Count);

        p1.SetState(Pressed(GamepadButtons.A));
        p2.SetState(Pressed(GamepadButtons.Y));
        Assert.True(await Wait.Until(() =>
            pads.Created[0].Last.Buttons == GamepadButtons.A && pads.Created[1].Last.Buttons == GamepadButtons.Y));
    }

    [Fact]
    public async Task SilentPhoneIsResetToNeutral()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads, new SessionOptions { StaleAfter = TimeSpan.FromMilliseconds(200) });
        var phone = NewPhone(host);
        Assert.True(await phone.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));
        phone.SetState(Pressed(GamepadButtons.A, lx: 20000));
        Assert.True(await Wait.Until(() => pads.Created[0].Last.Buttons == GamepadButtons.A));

        phone.Dispose();                                                    // vanishes without a BYE

        Assert.True(await Wait.Until(() => pads.Created[0].Last == GamepadState.Neutral, 2000), "held inputs must be released");
    }

    [Fact]
    public async Task RumbleFromAGameReachesThePhone()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads, new SessionOptions { RumbleHzMax = 30 });
        using var phone = NewPhone(host);
        (byte Large, byte Small)? got = null;
        phone.Rumble += (l, s) => got = (l, s);
        Assert.True(await phone.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));

        pads.Created[0].RaiseRumble(180, 40);

        Assert.True(await Wait.Until(() => got is not null));
        Assert.Equal(((byte)180, (byte)40), got!.Value);
    }

    [Fact]
    public async Task PhoneRecoversByItselfWhenThePcForgetsTheSession()
    {
        var pads = new FakePadFactory();
        using var host = StartHost(pads);
        using var phone = NewPhone(host);
        Assert.True(await phone.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));
        Assert.Equal(1, pads.Created.Count);

        // Simulate the PC dropping the session (restart, long outage): the phone's keys are now useless.
        Assert.True(host.Sessions.Disconnect(phone.Identity.DeviceId));
        Assert.True(pads.Created[0].Disposed);

        // The phone notices there are no PONGs (3 s) and sends a fresh HELLO on its own.
        Assert.True(await Wait.Until(() => pads.Created.Count == 2, 7000), "phone should re-HELLO after 3 s without PONG");
        phone.SetState(Pressed(GamepadButtons.Y));
        Assert.True(await Wait.Until(() => pads.Created[1].Last.Buttons == GamepadButtons.Y));
    }

    [Fact]
    public async Task PingIsAnsweredAndRttIsMeasured()
    {
        using var host = StartHost(new FakePadFactory());
        using var phone = NewPhone(host);
        Assert.True(await phone.PairAsync(host.OpenPairing().Offer.Secret, TimeSpan.FromSeconds(3)));

        // The first PING goes out about a second after connecting.
        Assert.True(await Wait.Until(() => phone.PongCount >= 1, 3000), "expected a PONG");
        Assert.True(phone.LastRttMs >= 0 && phone.LastRttMs < 500);
        Assert.True(host.Sessions.Snapshot().Single().PacketsAccepted > 10, "keepalives should be flowing at about 20 Hz");
    }

    // ------------------------------------------------------------------ UDP

    [Fact]
    public async Task UdpEndpointDeliversDatagramsWithTheirSourceAddress()
    {
        using var endpoint = new UdpEndpoint(IPAddress.Loopback, 0);
        byte[]? got = null;
        IPEndPoint? from = null;
        endpoint.Start((data, remote) => { got = data.ToArray(); from = remote; });

        using var client = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        client.Send(new byte[] { 1, 2, 3 }, 3, endpoint.LocalEndPoint);

        Assert.True(await Wait.Until(() => got is not null));
        Assert.Equal("010203", Convert.ToHexString(got!).ToLowerInvariant());
        Assert.Equal(((IPEndPoint)client.Client.LocalEndPoint!).Port, from!.Port);
    }

    [Fact]
    public async Task UdpEndpointSendsToAPeerAndSurvivesAFailingHandler()
    {
        using var endpoint = new UdpEndpoint(IPAddress.Loopback, 0);
        int calls = 0;
        endpoint.Start((data, remote) =>
        {
            calls++;
            if (calls == 1) throw new InvalidOperationException("boom");   // first datagram blows up
            endpoint.Send(data, remote);                                    // later ones are echoed
        });

        using var client = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        client.Client.ReceiveTimeout = 2000;
        client.Send(new byte[] { 9 }, 1, endpoint.LocalEndPoint);
        await Wait.Until(() => calls >= 1);
        client.Send(new byte[] { 7, 7 }, 2, endpoint.LocalEndPoint);

        var remote2 = new IPEndPoint(IPAddress.Any, 0);
        var reply = client.Receive(ref remote2);
        Assert.Equal("0707", Convert.ToHexString(reply));
    }

    [Fact]
    public void DisposingTheEndpointStopsItsThreadPromptly()
    {
        var endpoint = new UdpEndpoint(IPAddress.Loopback, 0);
        endpoint.Start((_, _) => { });
        var sw = System.Diagnostics.Stopwatch.StartNew();
        endpoint.Dispose();
        Assert.True(sw.ElapsedMilliseconds < 900, $"dispose took {sw.ElapsedMilliseconds} ms");
    }
}
