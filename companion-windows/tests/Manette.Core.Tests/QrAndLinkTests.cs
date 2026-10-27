using Manette.Core.Pairing;
using Manette.QrCode;
using Xunit;

namespace Manette.Core.Tests;

public class QrAndLinkTests
{
    private static readonly byte[] Secret = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();

    [Fact]
    public void TheInstallIdTravelsInTheLinkAndOldLinksStillParse()
    {
        string link = PairingCode.BuildLink(["192.168.1.20"], 26760, Secret, "PC", "a1b2c3d4");
        Assert.True(link.EndsWith("&i=a1b2c3d4"));
        Assert.True(PairingCode.TryParseLink(link, out var parsed));
        Assert.Equal("a1b2c3d4", parsed!.InstallId);
        Assert.Equal("PC", parsed.Name);

        string without = PairingCode.BuildLink(["192.168.1.20"], 26760, Secret, "PC");
        Assert.False(without.Contains("&i="));
        Assert.True(PairingCode.TryParseLink(without, out var old));
        Assert.Null(old!.InstallId);
    }

    [Fact]
    public void QrRenderHasTheExpectedShapeAndPlainModeHasNoEscapeCodes()
    {
        string link = PairingCode.BuildLink(["192.168.1.20", "192.168.137.1"], 26760, Secret, "DESKTOP-GAMING", "a1b2c3d4");
        int modules = QrConsole.ModuleCount(link);
        var plain = QrConsole.Render(link, ansi: false);
        Assert.Equal((modules + 4 + 1) / 2, plain.Count);                  // two QR rows per text row, 2-module border each side
        foreach (var l in plain)
        {
            Assert.Equal(modules + 4, l.Length);
            Assert.False(l.Contains('\u001b'));
        }
        Assert.True(modules <= 57, "the pairing link should fit a modest QR code (version <= 10)");

        var ansi = QrConsole.Render(link);
        foreach (var l in ansi)
        {
            Assert.True(l.StartsWith("\u001b[30m"));
            Assert.True(l.EndsWith("\u001b[0m"));
        }
    }

    [Fact]
    public void TheSameTextAlwaysRendersTheSameCode()
    {
        Assert.Equal(QrConsole.Render("hello", ansi: false), QrConsole.Render("hello", ansi: false));
        Assert.False(QrConsole.Render("hello", ansi: false).SequenceEqual(QrConsole.Render("hellp", ansi: false)));
    }
}
