using Manette.Core;
using Manette.Core.Protocol;

namespace Manette.Network.Tests;

internal sealed class FakePad : IVirtualPad
{
    private readonly object _gate = new();
    private GamepadState _last;
    public bool Disposed { get; private set; }
    public byte? Slot { get; init; }
    public event Action<byte, byte>? RumbleReceived;

    public GamepadState Last { get { lock (_gate) return _last; } }
    public void Update(in GamepadState state) { lock (_gate) _last = state; }
    public void RaiseRumble(byte large, byte small) => RumbleReceived?.Invoke(large, small);
    public void Dispose() => Disposed = true;
}

internal sealed class FakePadFactory : IVirtualPadFactory
{
    private readonly List<FakePad> _created = new();
    public IReadOnlyList<FakePad> Created { get { lock (_created) return _created.ToArray(); } }

    public IVirtualPad? TryCreate()
    {
        lock (_created)
        {
            var pad = new FakePad { Slot = (byte)_created.Count };
            _created.Add(pad);
            return pad;
        }
    }
}

internal static class Wait
{
    /// <summary>Polls until the condition holds or the timeout passes. Returns the final value of the condition.</summary>
    public static async Task<bool> Until(Func<bool> condition, int timeoutMs = 3000)
    {
        var end = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < end)
        {
            if (condition()) return true;
            await Task.Delay(5);
        }
        return condition();
    }
}
