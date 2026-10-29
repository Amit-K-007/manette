using Manette.Companion;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Manette.QrCode;
using Xunit;

namespace Manette.Core.Tests;

public class CompanionTests
{
    /// <summary>A recognisably different encoding, so tests can tell "protected" from "plain".</summary>
    private sealed class ReversingProtector : ISecretProtector
    {
        public byte[] Protect(byte[] plain) => new byte[] { 0xA5 }.Concat(plain.Reverse()).ToArray();
        public byte[] Unprotect(byte[] p) => p.Length > 0 && p[0] == 0xA5 ? p.Skip(1).Reverse().ToArray() : throw new InvalidOperationException("not ours");
    }

    private sealed class RefusingProtector : ISecretProtector
    {
        public byte[] Protect(byte[] plain) => plain;
        public byte[] Unprotect(byte[] p) => throw new InvalidOperationException("different user");
    }

    private static string TempDir() => Directory.CreateTempSubdirectory("manette").FullName;

    private static TrustedDevice Device(byte seed, string name = "Phone") =>
        new(new DeviceId(Enumerable.Range(0, 16).Select(i => (byte)(seed + i)).ToArray()),
            Enumerable.Range(0, 32).Select(i => (byte)(seed * 3 + i)).ToArray(), name, new DateTimeOffset(2026, 10, 1, 12, 0, seed, TimeSpan.Zero));

    // -------------------------------------------------------------- settings

    [Fact]
    public void SettingsDefaultWhenMissingOrCorruptAndRoundTripWhenSaved()
    {
        string path = Path.Combine(TempDir(), "settings.json");
        Assert.Equal(26760, new SettingsStore(path).Load().Port);

        File.WriteAllText(path, "{ not json");
        Assert.Equal(26760, new SettingsStore(path).Load().Port);

        new SettingsStore(path).Save(new AppSettings { Port = 30000, PcName = "Gaming PC", EnableMdns = false });
        var back = new SettingsStore(path).Load();
        Assert.Equal(30000, back.Port);
        Assert.Equal("Gaming PC", back.PcName);
        Assert.False(back.EnableMdns);
    }

    [Fact]
    public void HandEditedSettingsAreRepairedNotRejected()
    {
        string path = Path.Combine(TempDir(), "settings.json");
        File.WriteAllText(path, "{\"port\": 99999, \"pcName\": \"   \", \"somethingNew\": 1}");
        var s = new SettingsStore(path).Load();
        Assert.Equal(26760, s.Port);
        Assert.Null(s.PcName);

        Assert.Equal(26760, new AppSettings { Port = 0 }.Sanitized().Port);
        string longName = new AppSettings { PcName = new string('x', 200) }.Sanitized().PcName!;
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(longName) <= 63);
    }

    [Fact]
    public void TheInstallIdIsStableValidAndRepairsAGarbledFile()
    {
        string path = Path.Combine(TempDir(), "install-id");
        string a = InstallIdStore.GetOrCreate(path);
        Assert.Equal(8, a.Length);
        Assert.True(a.All(Uri.IsHexDigit));
        Assert.Equal(a, InstallIdStore.GetOrCreate(path));

        File.WriteAllText(path, "not an id");
        string b = InstallIdStore.GetOrCreate(path);
        Assert.Equal(8, b.Length);
        Assert.Equal(b, InstallIdStore.GetOrCreate(path));
    }

    // ----------------------------------------------------------------- store

    [Fact]
    public void KeysSurviveAReloadButAreNotReadableInTheFile()
    {
        string path = Path.Combine(TempDir(), "trusted.json");
        var d = Device(1, "Pixel 7");
        new ProtectedTrustedDeviceStore(path, new ReversingProtector()).Upsert(d);

        string text = File.ReadAllText(path);
        Assert.False(text.Contains(Convert.ToBase64String(d.DeviceKey)), "the raw key must not be in the file");
        Assert.False(text.Contains(Convert.ToHexString(d.DeviceKey), StringComparison.OrdinalIgnoreCase));
        Assert.True(text.Contains("Pixel 7"));

        var reloaded = new ProtectedTrustedDeviceStore(path, new ReversingProtector());
        Assert.True(reloaded.TryGet(d.DeviceId, out var back));
        Assert.Equal(Convert.ToHexString(d.DeviceKey), Convert.ToHexString(back.DeviceKey));
        Assert.Equal("Pixel 7", back.Name);
        Assert.Equal(d.PairedAt, back.PairedAt);
    }

    [Fact]
    public void RemoveAndUpsertPersistAndListIsOrderedByPairingTime()
    {
        string path = Path.Combine(TempDir(), "trusted.json");
        var s = new ProtectedTrustedDeviceStore(path, new ReversingProtector());
        s.Upsert(Device(5, "later")); s.Upsert(Device(2, "earlier")); s.Upsert(Device(2, "renamed"));
        Assert.Equal(new[] { "renamed", "later" }, s.List().Select(x => x.Name).ToArray());
        Assert.True(s.Remove(Device(5).DeviceId));
        Assert.False(s.Remove(Device(5).DeviceId));
        Assert.Equal(new[] { "renamed" }, new ProtectedTrustedDeviceStore(path, new ReversingProtector()).List().Select(x => x.Name).ToArray());
    }

    [Fact]
    public void RowsThatCannotBeDecryptedAreSkippedNotFatal()
    {
        string path = Path.Combine(TempDir(), "trusted.json");
        new ProtectedTrustedDeviceStore(path, new ReversingProtector()).Upsert(Device(1));

        var other = new ProtectedTrustedDeviceStore(path, new RefusingProtector());   // as if copied to another Windows user
        Assert.Empty(other.List());
        Assert.Equal(1, other.SkippedOnLoad);
    }

    [Fact]
    public void ACorruptFileMeansAnEmptyStoreNotACrash()
    {
        string path = Path.Combine(TempDir(), "trusted.json");
        File.WriteAllText(path, "}{ garbage");
        var s = new ProtectedTrustedDeviceStore(path, new ReversingProtector());
        Assert.Empty(s.List());
        s.Upsert(Device(1));
        Assert.Single(new ProtectedTrustedDeviceStore(path, new ReversingProtector()).List());
    }

    [Fact]
    public void AnOldPlainTextFileIsImportedOnceThenDeleted()
    {
        string dir = TempDir();
        string legacy = Path.Combine(dir, "trusted-devices.json");
        string fresh = Path.Combine(dir, "trusted-devices.v2.json");
        var d = Device(7, "Old phone");
        File.WriteAllText(legacy, "[{\"DeviceId\":\"" + d.DeviceId + "\",\"DeviceKey\":\"" + Convert.ToHexString(d.DeviceKey).ToLowerInvariant() +
            "\",\"Name\":\"Old phone\",\"PairedAt\":\"2026-10-01T12:00:07+00:00\"}]");

        var s = new ProtectedTrustedDeviceStore(fresh, new ReversingProtector(), legacy);
        Assert.True(s.TryGet(d.DeviceId, out var got));
        Assert.Equal(Convert.ToHexString(d.DeviceKey), Convert.ToHexString(got.DeviceKey));
        Assert.False(File.Exists(legacy), "the plain-text copy must be gone");
        Assert.False(File.ReadAllText(fresh).Contains(Convert.ToHexString(d.DeviceKey), StringComparison.OrdinalIgnoreCase));
        Assert.Single(new ProtectedTrustedDeviceStore(fresh, new ReversingProtector(), legacy).List());   // and it stays migrated
    }

    // ---------------------------------------------------------------- QR matrix

    [Fact]
    public void QrMatrixHasTheStandardFinderPatternAndMatchesTheConsoleSize()
    {
        var m = QrMatrix.Encode("manette://pair?v=1");
        int n = m.GetLength(0);
        Assert.Equal(n, m.GetLength(1));
        Assert.Equal(QrConsole.ModuleCount("manette://pair?v=1"), n);
        for (int x = 0; x < 7; x++) Assert.True(m[x, 0]);                 // top edge of the top-left finder is solid
        Assert.True(m[0, 1]); Assert.False(m[1, 1]); Assert.True(m[2, 2]); // ring, gap, then the dark centre block
        Assert.True(m[n - 1, 0]); Assert.True(m[0, n - 1]);               // the other two finders' corners
    }
}
