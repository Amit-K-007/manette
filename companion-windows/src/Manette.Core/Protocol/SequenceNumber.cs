namespace Manette.Core.Protocol;

/// <summary>u16 serial-number arithmetic: wraps safely, half the space is "newer".</summary>
public static class SequenceNumber
{
    public static bool IsNewer(ushort last, ushort candidate) =>
        unchecked((short)(candidate - last)) > 0;
}
