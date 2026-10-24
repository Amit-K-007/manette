namespace Manette.Core.Protocol;

/// <summary>Bit values deliberately match the Windows XInput wButtons layout.</summary>
[Flags]
public enum GamepadButtons : ushort
{
    None = 0,
    DpadUp = 0x0001,
    DpadDown = 0x0002,
    DpadLeft = 0x0004,
    DpadRight = 0x0008,
    Start = 0x0010,
    Back = 0x0020,
    LeftThumb = 0x0040,
    RightThumb = 0x0080,
    LeftShoulder = 0x0100,
    RightShoulder = 0x0200,
    Guide = 0x0400,
    A = 0x1000,
    B = 0x2000,
    X = 0x4000,
    Y = 0x8000,
}

/// <summary>
/// The canonical Xbox-style controller state. Positive X is right, positive Y is UP.
/// Every future layout (NES, PS1, ...) maps onto this same struct.
/// </summary>
public readonly record struct GamepadState(
    GamepadButtons Buttons,
    byte LeftTrigger,
    byte RightTrigger,
    short LeftX,
    short LeftY,
    short RightX,
    short RightY)
{
    public static GamepadState Neutral => default;

    /// <summary>
    /// Screen Y grows downward, wire Y grows upward: wire = clamp(-screen, -32768, 32767).
    /// The clamp matters because -(-32768) does not fit in an i16.
    /// </summary>
    public static short ScreenYToWire(int screenY) =>
        (short)Math.Clamp(-(long)screenY, short.MinValue, short.MaxValue);
}
