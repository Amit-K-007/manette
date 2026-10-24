using Manette.Core;
using Manette.Core.Protocol;

namespace Manette.Cli;

/// <summary>Stand-in for the ViGEm backend (--fake-pads): prints state changes instead of driving a driver. Works on any OS.</summary>
internal sealed class ConsolePadFactory : IVirtualPadFactory
{
    private int _next;
    public IVirtualPad? TryCreate() => new ConsolePad((byte)Math.Min(_next++, 3));

    private sealed class ConsolePad : IVirtualPad
    {
        private readonly int _n;
        public ConsolePad(byte slot) { Slot = slot; _n = slot + 1; Console.WriteLine($"[pad {_n}] created"); }
        public byte? Slot { get; }
        public event Action<byte, byte>? RumbleReceived { add { } remove { } }
        public void Update(in GamepadState s) =>
            Console.WriteLine($"[pad {_n}] {s.Buttons,-14} LT={s.LeftTrigger,3} RT={s.RightTrigger,3} L=({s.LeftX,6},{s.LeftY,6}) R=({s.RightX,6},{s.RightY,6})");
        public void Dispose() => Console.WriteLine($"[pad {_n}] removed");
    }
}
