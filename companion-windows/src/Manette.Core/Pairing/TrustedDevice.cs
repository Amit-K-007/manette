using System.Collections.Concurrent;
using Manette.Core.Protocol;

namespace Manette.Core.Pairing;

public sealed record TrustedDevice(DeviceId DeviceId, byte[] DeviceKey, string Name, DateTimeOffset PairedAt);

/// <summary>
/// Persistence seam. Core ships an in-memory store; the App project supplies a disk-backed one
/// (device keys are credentials, so it should protect them, e.g. with Windows DPAPI).
/// </summary>
public interface ITrustedDeviceStore
{
    bool TryGet(DeviceId id, out TrustedDevice device);
    void Upsert(TrustedDevice device);
    bool Remove(DeviceId id);
    IReadOnlyList<TrustedDevice> List();
}

public sealed class InMemoryTrustedDeviceStore : ITrustedDeviceStore
{
    private readonly ConcurrentDictionary<DeviceId, TrustedDevice> _devices = new();

    public bool TryGet(DeviceId id, out TrustedDevice device)
    {
        if (_devices.TryGetValue(id, out var d)) { device = d; return true; }
        device = null!;
        return false;
    }

    public void Upsert(TrustedDevice device) => _devices[device.DeviceId] = device;
    public bool Remove(DeviceId id) => _devices.TryRemove(id, out _);
    public IReadOnlyList<TrustedDevice> List() => _devices.Values.ToArray();
}
