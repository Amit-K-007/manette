using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text.Json;
using Manette.Core.Pairing;
using Manette.Core.Protocol;

namespace Manette.Companion;

/// <summary>Encrypts and decrypts a secret for storage on this machine.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] plain);
    byte[] Unprotect(byte[] protectedBytes);
}

/// <summary>No protection. Only for non-Windows development; the Windows apps use <see cref="DpapiProtector"/>.</summary>
public sealed class PassthroughProtector : ISecretProtector
{
    public byte[] Protect(byte[] plain) => (byte[])plain.Clone();
    public byte[] Unprotect(byte[] protectedBytes) => (byte[])protectedBytes.Clone();
}

/// <summary>
/// Windows DPAPI, current-user scope: only the same Windows user on the same machine can decrypt, so a copied
/// trusted-devices file is useless elsewhere. Calls crypt32 directly so no extra package is needed.
/// NOTE: written without Windows to run it on; verify on first use (a pair, restart, reconnect cycle).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiProtector : ISecretProtector
{
    private const int UiForbidden = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob { public int cbData; public IntPtr pbData; }

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(ref DataBlob dataIn, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob dataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(ref DataBlob dataIn, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out DataBlob dataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);

    public byte[] Protect(byte[] plain) => Run(plain, protect: true);
    public byte[] Unprotect(byte[] protectedBytes) => Run(protectedBytes, protect: false);

    private static byte[] Run(byte[] input, bool protect)
    {
        var inBlob = new DataBlob { cbData = input.Length, pbData = Marshal.AllocHGlobal(Math.Max(1, input.Length)) };
        try
        {
            Marshal.Copy(input, 0, inBlob.pbData, input.Length);
            bool ok = protect
                ? CryptProtectData(ref inBlob, "Manette", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, UiForbidden, out var outBlob)
                : CryptUnprotectData(ref inBlob, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, UiForbidden, out outBlob);
            if (!ok) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            try
            {
                var result = new byte[outBlob.cbData];
                Marshal.Copy(outBlob.pbData, result, 0, outBlob.cbData);
                return result;
            }
            finally { LocalFree(outBlob.pbData); }
        }
        finally { Marshal.FreeHGlobal(inBlob.pbData); }
    }
}

/// <summary>
/// Trusted phones on disk with their device keys encrypted. A file written by the older plain-text store
/// (<c>trusted-devices.json</c>) is imported once and then deleted. A row that cannot be decrypted (copied from another
/// user or machine) is skipped and counted in <see cref="SkippedOnLoad"/> rather than breaking the app.
/// </summary>
public sealed class ProtectedTrustedDeviceStore : ITrustedDeviceStore
{
    private sealed record Row(string Id, string Name, DateTimeOffset PairedAt, string Key);
    private sealed record FileModel(int Version, List<Row> Devices);
    private sealed record LegacyRow(string DeviceId, string DeviceKey, string Name, DateTimeOffset PairedAt);

    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };

    private readonly object _gate = new();
    private readonly string _path;
    private readonly ISecretProtector _protector;
    private readonly Dictionary<DeviceId, TrustedDevice> _devices = new();

    public int SkippedOnLoad { get; private set; }

    public ProtectedTrustedDeviceStore(string path, ISecretProtector protector, string? legacyPlainPath = null)
    {
        _path = path;
        _protector = protector;
        if (File.Exists(path)) Load();
        else if (legacyPlainPath is not null && File.Exists(legacyPlainPath)) Import(legacyPlainPath);
    }

    public bool TryGet(DeviceId id, out TrustedDevice device)
    {
        lock (_gate)
        {
            if (_devices.TryGetValue(id, out var d)) { device = d; return true; }
            device = null!;
            return false;
        }
    }

    public void Upsert(TrustedDevice device)
    {
        lock (_gate) { _devices[device.DeviceId] = device; Save(); }
    }

    public bool Remove(DeviceId id)
    {
        lock (_gate)
        {
            bool removed = _devices.Remove(id);
            if (removed) Save();
            return removed;
        }
    }

    public IReadOnlyList<TrustedDevice> List()
    {
        lock (_gate) return _devices.Values.OrderBy(d => d.PairedAt).ToArray();
    }

    private void Load()
    {
        try
        {
            var model = JsonSerializer.Deserialize<FileModel>(File.ReadAllText(_path), Options);
            foreach (var r in model?.Devices ?? [])
            {
                try
                {
                    var id = new DeviceId(Convert.FromHexString(r.Id));
                    var key = _protector.Unprotect(Convert.FromBase64String(r.Key));
                    if (key.Length != ProtocolConstants.KeyLength) throw new FormatException();
                    _devices[id] = new TrustedDevice(id, key, r.Name, r.PairedAt);
                }
                catch (Exception) { SkippedOnLoad++; }
            }
        }
        catch (Exception) { /* unreadable file: start empty; phones simply pair again */ }
    }

    private void Import(string legacyPath)
    {
        try
        {
            foreach (var r in JsonSerializer.Deserialize<List<LegacyRow>>(File.ReadAllText(legacyPath), Options) ?? [])
            {
                try
                {
                    var id = new DeviceId(Convert.FromHexString(r.DeviceId));
                    var key = Convert.FromHexString(r.DeviceKey);
                    if (key.Length == ProtocolConstants.KeyLength) _devices[id] = new TrustedDevice(id, key, r.Name, r.PairedAt);
                }
                catch (Exception) { SkippedOnLoad++; }
            }
            Save();
            File.Delete(legacyPath);                       // only reached if the protected copy was written
        }
        catch (Exception) { /* keep the old file if anything went wrong, so nothing is lost */ }
    }

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var rows = _devices.Values.Select(d => new Row(
            d.DeviceId.ToString(), d.Name, d.PairedAt, Convert.ToBase64String(_protector.Protect(d.DeviceKey)))).ToList();
        AtomicFile.Write(_path, JsonSerializer.Serialize(new FileModel(2, rows), Options));
    }
}
