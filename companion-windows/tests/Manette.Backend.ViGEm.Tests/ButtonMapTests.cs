using Manette.Backend.ViGEm;
using Manette.Core.Protocol;
using Xunit;

namespace Manette.Backend.ViGEm.Tests;

/// <summary>Needs no driver: only checks constants shipped inside the ViGEm client library.</summary>
public class ButtonMapTests
{
    [Fact]
    public void EveryWireButtonBitEqualsTheViGEmXbox360Button()
    {
        foreach (var (flag, button) in ViGEmVirtualPad.ButtonMap)
            Assert.True((ushort)flag == button.Value, $"{flag}: wire 0x{(ushort)flag:X4} vs ViGEm 0x{button.Value:X4}");
    }

    [Fact]
    public void EveryDefinedButtonIsMapped()
    {
        var mapped = ViGEmVirtualPad.ButtonMap.Aggregate((ushort)0, (acc, m) => (ushort)(acc | (ushort)m.Flag));
        var all = Enum.GetValues<GamepadButtons>().Aggregate((ushort)0, (acc, b) => (ushort)(acc | (ushort)b));
        Assert.Equal(all, mapped);
    }
}
