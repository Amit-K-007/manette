namespace Manette.Core.Protocol;

/// <summary>Constants from docs/protocol.md. Keep this file in lockstep with the spec.</summary>
public static class ProtocolConstants
{
    public const byte ProtocolVersion = 1;
    public const ushort DefaultPort = 26760;

    public const byte Magic0 = 0x4D; // 'M'
    public const byte Magic1 = 0x4E; // 'N'

    public const int HeaderLength = 8;
    public const int HandshakeTagLength = 16;
    public const int SessionTagLength = 8;

    public const int DeviceIdLength = 16;
    public const int NonceLength = 8;
    public const int PairingSecretLength = 16;
    public const int KeyLength = 32;
    public const int MaxNameBytes = 32;

    /// <summary>Datagrams longer than this are dropped without parsing.</summary>
    public const int MaxDatagramLength = 128;

    public const int InputLength = 30;
    public const int PingLength = 20;
    public const int RumbleLength = 20;
    public const int ByeLength = 16;
    public const int WelcomeLength = 36;
    public const int RejectLength = 10;
    public const int HelloMinLength = 50;           // empty name
    public const int HelloMaxLength = 82;           // 32-byte name

    public const byte NoSlot = 0xFF;
}

public enum MessageType : byte
{
    Hello = 0x01,
    Welcome = 0x02,
    Reject = 0x03,
    Input = 0x10,
    Ping = 0x12,
    Pong = 0x13,
    Rumble = 0x20,
    Bye = 0x21,
}

public enum RejectReason : byte
{
    NotPaired = 1,
    UnsupportedVersion = 2,
    ServerFull = 3,
    PairingNotOpen = 4,
    RateLimited = 5,
}

[Flags]
public enum HelloFlags : byte
{
    None = 0,
    Pairing = 1,
}

[Flags]
public enum WelcomeFlags : byte
{
    None = 0,
    Resumed = 1,
    PairedNow = 2,
}
