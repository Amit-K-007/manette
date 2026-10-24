using System.Net;
using Manette.Network.Mdns;
using Xunit;

namespace Manette.Network.Tests;

public class DnsTests
{
    private static MdnsServiceInfo Info() => new()
    {
        InstanceName = "Gaming PC", Port = 26760, Id = "a1b2c3d4", HostLabel = "gaming-pc-manette",
    };

    [Fact]
    public void ServiceResponseParsesBackToTheRecordsAPhoneNeeds()
    {
        var packet = MdnsAdvertiser.BuildResponse(Info(), IPAddress.Parse("192.168.1.20"), pairingOpen: true, goodbye: false);

        Assert.True(DnsMessage.TryParse(packet, out var msg));
        Assert.True(msg!.IsResponse);

        var ptr = msg.Answers.Single();
        Assert.Equal(DnsType.PTR, ptr.Type);
        Assert.Equal("_manette._udp.local", ptr.Name);
        Assert.Equal("Gaming PC._manette._udp.local", ptr.PtrTarget);

        var srv = msg.Additionals.Single(r => r.Type == DnsType.SRV);
        Assert.Equal("Gaming PC._manette._udp.local", srv.Name);
        Assert.Equal((ushort)26760, srv.SrvPort);
        Assert.Equal("gaming-pc-manette.local", srv.SrvTarget);

        var txt = msg.Additionals.Single(r => r.Type == DnsType.TXT);
        Assert.Equal(new[] { "v=1", "id=a1b2c3d4", "pair=1" }, txt.Txt!.ToArray());

        var a = msg.Additionals.Single(r => r.Type == DnsType.A);
        Assert.Equal("gaming-pc-manette.local", a.Name);
        Assert.Equal(IPAddress.Parse("192.168.1.20"), a.Address);
    }

    [Fact]
    public void PairingFlagAndGoodbyeTtlAreReflected()
    {
        var closed = MdnsAdvertiser.BuildResponse(Info(), IPAddress.Loopback, pairingOpen: false, goodbye: false);
        DnsMessage.TryParse(closed, out var m1);
        Assert.True(m1!.Additionals.Single(r => r.Type == DnsType.TXT).Txt!.Contains("pair=0"));
        Assert.True(m1.Answers.Single().Ttl > 0u);

        var bye = MdnsAdvertiser.BuildResponse(Info(), IPAddress.Loopback, pairingOpen: false, goodbye: true);
        DnsMessage.TryParse(bye, out var m2);
        Assert.Equal(0u, m2!.Answers.Single().Ttl);
        Assert.True(m2.Additionals.All(r => r.Ttl == 0));
    }

    [Fact]
    public void LegacyUnicastResponseEchoesTheQueryId()
    {
        var packet = MdnsAdvertiser.BuildResponse(Info(), IPAddress.Loopback, pairingOpen: false, goodbye: false, id: 0xBEEF);
        Assert.True(DnsMessage.TryParse(packet, out var msg));
        Assert.Equal((ushort)0xBEEF, msg!.Id);

        var multicast = MdnsAdvertiser.BuildResponse(Info(), IPAddress.Loopback, pairingOpen: false, goodbye: false);
        DnsMessage.TryParse(multicast, out var m2);
        Assert.Equal((ushort)0, m2!.Id);            // multicast responses carry id 0
    }

    [Fact]
    public void OnlyQueriesForOurServiceAreAnswered()
    {
        DnsMessage Q(string name, ushort type) { DnsMessage.TryParse(DnsResponseBuilder.BuildQuery(name, type), out var m); return m!; }
        var info = Info();

        Assert.True(MdnsAdvertiser.QueryMatches(Q("_manette._udp.local", DnsType.PTR), info));
        Assert.True(MdnsAdvertiser.QueryMatches(Q("_MANETTE._udp.local", DnsType.PTR), info));            // case-insensitive
        Assert.True(MdnsAdvertiser.QueryMatches(Q("Gaming PC._manette._udp.local", DnsType.SRV), info));
        Assert.True(MdnsAdvertiser.QueryMatches(Q("gaming-pc-manette.local", DnsType.A), info));

        Assert.False(MdnsAdvertiser.QueryMatches(Q("_http._tcp.local", DnsType.PTR), info));
        Assert.False(MdnsAdvertiser.QueryMatches(Q("_manette._udp.local", DnsType.A), info));              // wrong type for that name
        Assert.False(MdnsAdvertiser.QueryMatches(Q("someone-else.local", DnsType.A), info));
    }

    [Fact]
    public void ParserFollowsCompressionPointers()
    {
        // Two questions; the second's name is just a pointer to the first (offset 12).
        var name = DnsResponseBuilder.EncodeName("_manette._udp.local");
        var msg = new List<byte> { 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0 };
        msg.AddRange(name); msg.AddRange(new byte[] { 0, 12, 0, 1 });
        msg.AddRange(new byte[] { 0xC0, 12, 0, 12, 0, 1 });

        Assert.True(DnsMessage.TryParse(msg.ToArray(), out var parsed));
        Assert.Equal(2, parsed!.Questions.Count);
        Assert.Equal("_manette._udp.local", parsed.Questions[1].Name);
    }

    [Fact]
    public void ParserRejectsGarbageAndPointerLoopsWithoutThrowing()
    {
        Assert.False(DnsMessage.TryParse(new byte[5], out _));

        var loop = new byte[] { 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 12, 0, 1, 0, 1 };   // name pointer to itself
        Assert.False(DnsMessage.TryParse(loop, out _));

        var rng = new Random(1234);
        for (int i = 0; i < 2000; i++)
        {
            var junk = new byte[rng.Next(0, 80)];
            rng.NextBytes(junk);
            DnsMessage.TryParse(junk, out _);            // must simply return, never throw or hang
        }
    }

    [Fact]
    public void InstanceNamesAreMadeSafeForDns()
    {
        Assert.Equal("My-PC", MdnsServiceInfo.SanitizeLabel("My.PC"));
        Assert.Equal("Manette", MdnsServiceInfo.SanitizeLabel("   "));
        var longName = MdnsServiceInfo.SanitizeLabel(new string('x', 200));
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(longName) <= 63);
        Assert.True(MdnsServiceInfo.DefaultHostLabel().EndsWith("-manette"));
    }

    [Fact]
    public void HostAddressesAreUsableUnicastIPv4Only()
    {
        foreach (var a in HostAddressProvider.GetCandidateHosts())
        {
            Assert.True(a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
            Assert.False(IPAddress.IsLoopback(a));
            Assert.False(a.GetAddressBytes()[0] == 169 && a.GetAddressBytes()[1] == 254, "link-local should be excluded");
        }
    }
}
