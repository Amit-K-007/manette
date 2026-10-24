using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Manette.Core.Protocol;

/// <summary>16-byte random identifier a phone generates once. Value type, usable as a dictionary key.</summary>
public readonly struct DeviceId : IEquatable<DeviceId>
{
    private readonly ulong _lo;
    private readonly ulong _hi;

    public DeviceId(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != ProtocolConstants.DeviceIdLength)
            throw new ArgumentException("DeviceId must be 16 bytes.", nameof(bytes));
        _lo = BinaryPrimitives.ReadUInt64LittleEndian(bytes);
        _hi = BinaryPrimitives.ReadUInt64LittleEndian(bytes[8..]);
    }

    public static DeviceId NewRandom()
    {
        Span<byte> b = stackalloc byte[ProtocolConstants.DeviceIdLength];
        RandomNumberGenerator.Fill(b);
        return new DeviceId(b);
    }

    public void CopyTo(Span<byte> destination)
    {
        BinaryPrimitives.WriteUInt64LittleEndian(destination, _lo);
        BinaryPrimitives.WriteUInt64LittleEndian(destination[8..], _hi);
    }

    public byte[] ToArray()
    {
        var a = new byte[ProtocolConstants.DeviceIdLength];
        CopyTo(a);
        return a;
    }

    public override string ToString() => Convert.ToHexString(ToArray()).ToLowerInvariant();

    public bool Equals(DeviceId other) => _lo == other._lo && _hi == other._hi;
    public override bool Equals(object? obj) => obj is DeviceId d && Equals(d);
    public override int GetHashCode() => HashCode.Combine(_lo, _hi);
    public static bool operator ==(DeviceId a, DeviceId b) => a.Equals(b);
    public static bool operator !=(DeviceId a, DeviceId b) => !a.Equals(b);
}
