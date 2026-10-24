using System.Security.Cryptography;
using Manette.Core.Protocol;

namespace Manette.Core.Pairing;

public sealed record PairingOffer(byte[] Secret, string Base32, string TypedCode, DateTimeOffset ExpiresAt);

/// <summary>
/// Owns the short-lived, single-use pairing secret (docs/protocol.md section 4.4).
/// Thread-safe. The window closes after the first successful pairing, on expiry, or on Close().
/// </summary>
public sealed class PairingManager
{
    private readonly object _gate = new();
    private readonly IRandomSource _random;
    private readonly TimeProvider _time;
    private readonly TimeSpan _window;

    private byte[]? _secret;
    private DateTimeOffset _expires;

    public event EventHandler? Changed;

    public PairingManager(IRandomSource? random = null, TimeProvider? time = null, TimeSpan? window = null)
    {
        _random = random ?? SecureRandomSource.Instance;
        _time = time ?? TimeProvider.System;
        _window = window ?? TimeSpan.FromMinutes(5);
    }

    public bool IsOpen
    {
        get { lock (_gate) return IsOpenLocked(); }
    }

    public DateTimeOffset? ExpiresAt
    {
        get { lock (_gate) return IsOpenLocked() ? _expires : null; }
    }

    /// <summary>Starts a new window, replacing any open one. The returned secret is a copy.</summary>
    public PairingOffer Open()
    {
        PairingOffer offer;
        lock (_gate)
        {
            ZeroLocked();
            _secret = new byte[ProtocolConstants.PairingSecretLength];
            _random.Fill(_secret);
            _expires = _time.GetUtcNow() + _window;
            var copy = (byte[])_secret.Clone();
            offer = new PairingOffer(copy, PairingCode.ToBase32(copy), PairingCode.ToTypedCode(copy), _expires);
        }
        Changed?.Invoke(this, EventArgs.Empty);
        return offer;
    }

    public void Close()
    {
        bool wasOpen;
        lock (_gate)
        {
            wasOpen = _secret is not null;
            ZeroLocked();
        }
        if (wasOpen) Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Derives the device key a phone would hold if it scanned the current secret. False if no window is open.</summary>
    public bool TryDeriveDeviceKey(DeviceId deviceId, out byte[] deviceKey)
    {
        lock (_gate)
        {
            if (!IsOpenLocked()) { deviceKey = []; return false; }
            Span<byte> id = stackalloc byte[ProtocolConstants.DeviceIdLength];
            deviceId.CopyTo(id);
            deviceKey = ManetteCrypto.DeriveDeviceKey(_secret!, id);
            return true;
        }
    }

    /// <summary>Called by the server after a pairing HELLO verified. Single use: closes the window.</summary>
    public void Complete() => Close();

    private bool IsOpenLocked()
    {
        if (_secret is null) return false;
        if (_time.GetUtcNow() >= _expires) { ZeroLocked(); return false; }
        return true;
    }

    private void ZeroLocked()
    {
        if (_secret is not null) CryptographicOperations.ZeroMemory(_secret);
        _secret = null;
    }
}
