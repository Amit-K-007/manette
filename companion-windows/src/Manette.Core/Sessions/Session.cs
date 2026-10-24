using System.Net;
using Manette.Core.Protocol;

namespace Manette.Core.Sessions;

public enum SessionState
{
    /// <summary>Packets are arriving.</summary>
    Active,
    /// <summary>No packet for a while; pad is held at neutral.</summary>
    Stale,
    /// <summary>Gone (stale for long, or BYE); pad kept alive at neutral so a reconnect keeps its slot.</summary>
    Grace,
}

/// <summary>
/// One phone's connection and its virtual pad. Mutable fields are only touched while holding the
/// owning <see cref="SessionManager"/>'s lock; read via <see cref="SessionManager.Snapshot"/> for a safe view.
/// </summary>
public sealed class Session
{
    internal Session(DeviceId deviceId, IVirtualPad pad)
    {
        DeviceId = deviceId;
        Pad = pad;
        SessionKey = [];
        Name = "";
    }

    public DeviceId DeviceId { get; }
    public IVirtualPad Pad { get; }

    public uint SessionId { get; internal set; }
    public string Name { get; internal set; }
    public byte[] SessionKey { get; internal set; }
    public IPEndPoint? Remote { get; internal set; }
    public SessionState State { get; internal set; }
    public long Accepted { get; internal set; }

    internal bool HasSeq;
    internal ushort LastSeq;
    internal long LastActivity;
    internal long StateSince;
    internal GamepadState LastState;
    internal ushort RumbleSeq;
    internal long LastRumbleSent;
}

/// <summary>Immutable copy of a session's public state, safe to hand to a UI thread.</summary>
public sealed record SessionInfo(
    uint SessionId, DeviceId DeviceId, string Name, SessionState State,
    byte? Slot, IPEndPoint? Remote, long PacketsAccepted);

public sealed record SessionOptions
{
    /// <summary>XInput on Windows exposes at most 4 pads.</summary>
    public int MaxSessions { get; init; } = 4;
    public TimeSpan StaleAfter { get; init; } = TimeSpan.FromMilliseconds(500);
    public TimeSpan GraceAfterStale { get; init; } = TimeSpan.FromSeconds(10);
    public TimeSpan GraceDuration { get; init; } = TimeSpan.FromSeconds(10);
    /// <summary>0 disables rumble (the v1 default). Advertised to phones in WELCOME.</summary>
    public ushort RumbleHzMax { get; init; }
}
