using Manette.Core.Protocol;

namespace Manette.Core;

/// <summary>
/// One virtual controller as the OS sees it. Implemented by Manette.Backend.ViGEm; Core never
/// references a driver. Implementations must make Update fast (it is on the input hot path).
/// </summary>
public interface IVirtualPad : IDisposable
{
    /// <summary>XInput slot (0-3) if the OS has reported one yet; otherwise null.</summary>
    byte? Slot { get; }

    /// <summary>Raised when a game requests vibration: (largeMotor, smallMotor), each 0-255. May fire on any thread.</summary>
    event Action<byte, byte>? RumbleReceived;

    void Update(in GamepadState state);
}

public interface IVirtualPadFactory
{
    /// <summary>Creates and connects a new virtual pad, or returns null if that is not possible (no slot, driver error).</summary>
    IVirtualPad? TryCreate();
}
