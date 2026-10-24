using Manette.Core;
using Manette.Core.Protocol;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using Nefarius.ViGEm.Client.Targets.Xbox360.Exceptions;

namespace Manette.Backend.ViGEm;

/// <summary>
/// One virtual Xbox 360 controller backed by the ViGEmBus driver. Games see an ordinary XInput pad.
/// Update never throws: a driver failure marks the pad faulted and later updates are dropped, so one bad
/// pad cannot take down the packet loop.
/// </summary>
public sealed class ViGEmVirtualPad : IVirtualPad
{
    private readonly IXbox360Controller _pad;
    private readonly Action<string>? _log;
    private readonly object _gate = new();
    private bool _disposed;
    private bool _faulted;

    public event Action<byte, byte>? RumbleReceived;

    /// <summary>Connects immediately; throws if the driver refuses (the factory turns that into a null pad).</summary>
    internal ViGEmVirtualPad(IXbox360Controller pad, Action<string>? log)
    {
        _pad = pad;
        _log = log;
        _pad.AutoSubmitReport = false;                 // we set every field, then submit ONE report per update
        _pad.FeedbackReceived += OnFeedback;
        _pad.Connect();
    }

    /// <summary>
    /// The driver only reports the XInput slot after its first notification, so this can be null for a moment
    /// after creation. The protocol treats the slot as informational for exactly that reason.
    /// </summary>
    public byte? Slot
    {
        get
        {
            try
            {
                int i = _pad.UserIndex;
                return i is >= 0 and <= 3 ? (byte)i : null;
            }
            catch (Xbox360UserIndexNotReportedException)
            {
                return null;
            }
        }
    }

    public bool IsFaulted => _faulted;

    public void Update(in GamepadState s)
    {
        lock (_gate)
        {
            if (_disposed || _faulted) return;
            try
            {
                ushort mask = (ushort)s.Buttons;
                foreach (var (flag, button) in ButtonMap)
                    _pad.SetButtonState(button, (mask & (ushort)flag) != 0);

                _pad.SetSliderValue(Xbox360Slider.LeftTrigger, s.LeftTrigger);
                _pad.SetSliderValue(Xbox360Slider.RightTrigger, s.RightTrigger);
                _pad.SetAxisValue(Xbox360Axis.LeftThumbX, s.LeftX);
                _pad.SetAxisValue(Xbox360Axis.LeftThumbY, s.LeftY);
                _pad.SetAxisValue(Xbox360Axis.RightThumbX, s.RightX);
                _pad.SetAxisValue(Xbox360Axis.RightThumbY, s.RightY);
                _pad.SubmitReport();
            }
            catch (Exception ex)
            {
                _faulted = true;
                _log?.Invoke($"Virtual pad update failed, pad disabled: {ex.GetType().Name}: {ex.Message}");
            }
        }
    }

    private void OnFeedback(object sender, Xbox360FeedbackReceivedEventArgs e) =>
        RumbleReceived?.Invoke(e.LargeMotor, e.SmallMotor);

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
        }
        try
        {
            _pad.FeedbackReceived -= OnFeedback;
            _pad.Disconnect();
        }
        catch (Exception ex)
        {
            _log?.Invoke($"Virtual pad disconnect failed: {ex.GetType().Name}: {ex.Message}");
        }
    }

    /// <summary>Our wire bit values equal XInput's, but this table keeps the mapping explicit and testable.</summary>
    internal static readonly (GamepadButtons Flag, Xbox360Button Button)[] ButtonMap =
    {
        (GamepadButtons.DpadUp, Xbox360Button.Up),
        (GamepadButtons.DpadDown, Xbox360Button.Down),
        (GamepadButtons.DpadLeft, Xbox360Button.Left),
        (GamepadButtons.DpadRight, Xbox360Button.Right),
        (GamepadButtons.Start, Xbox360Button.Start),
        (GamepadButtons.Back, Xbox360Button.Back),
        (GamepadButtons.LeftThumb, Xbox360Button.LeftThumb),
        (GamepadButtons.RightThumb, Xbox360Button.RightThumb),
        (GamepadButtons.LeftShoulder, Xbox360Button.LeftShoulder),
        (GamepadButtons.RightShoulder, Xbox360Button.RightShoulder),
        (GamepadButtons.Guide, Xbox360Button.Guide),
        (GamepadButtons.A, Xbox360Button.A),
        (GamepadButtons.B, Xbox360Button.B),
        (GamepadButtons.X, Xbox360Button.X),
        (GamepadButtons.Y, Xbox360Button.Y),
    };
}
