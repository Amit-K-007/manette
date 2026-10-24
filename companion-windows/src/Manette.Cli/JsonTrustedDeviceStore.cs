using System.Text.Json;
using Manette.Core.Pairing;
using Manette.Core.Protocol;

namespace Manette.Cli;

/// <summary>
/// Persists trusted devices as JSON. NOTE: device keys are credentials and are stored in PLAIN TEXT here.
/// Fine for development; the real app should protect them (for example Windows DPAPI) before release.
/// </summary>
internal sealed class JsonTrustedDeviceStore : ITrustedDeviceStore
{
    private sealed record Row(string DeviceId, string DeviceKey, string Name, DateTimeOffset PairedAt);

    private readonly object _gate = new();
    private readonly string _path;
    private readonly Dictionary<DeviceId, TrustedDevice> _devices = new();

    public JsonTrustedDeviceStore(string path)
    {
        _path = path;
        if (!File.Exists(path)) return;
        foreach (var r in JsonSerializer.Deserialize<List<Row>>(File.ReadAllText(path)) ?? new())
        {
            var id = new DeviceId(Convert.FromHexString(r.DeviceId));
            _devices[id] = new TrustedDevice(id, Convert.FromHexString(r.DeviceKey), r.Name, r.PairedAt);
        }
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
        lock (_gate) return _devices.Values.ToArray();
    }

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        var rows = _devices.Values.Select(d => new Row(
            d.DeviceId.ToString(), Convert.ToHexString(d.DeviceKey).ToLowerInvariant(), d.Name, d.PairedAt));
        File.WriteAllText(_path, JsonSerializer.Serialize(rows, new JsonSerializerOptions { WriteIndented = true }));
    }
}
