using System.Net;
using Manette.Core.Protocol;

namespace Manette.Core.Sessions;

public enum OpenError { None, ServerFull }

public readonly record struct OpenResult(Session? Session, bool Resumed, byte[] ServerNonce, OpenError Error);

/// <summary>
/// One session per phone: owns session keys, per-session sequence tracking, the virtual pads, and the
/// Active/Stale/Grace lifecycle (docs/protocol.md section 8). Thread-safe via a single lock; with at most
/// four sessions contention is a non-issue. Events are raised AFTER the lock is released.
/// Call <see cref="Tick"/> periodically (every ~50 ms) to drive timeouts.
/// </summary>
public sealed class SessionManager : IDisposable
{
    private readonly object _gate = new();
    private readonly Dictionary<uint, Session> _byId = new();
    private readonly Dictionary<DeviceId, Session> _byDevice = new();
    private readonly List<Session> _scratch = new();
    private readonly IVirtualPadFactory _factory;
    private readonly IRandomSource _random;
    private readonly TimeProvider _time;
    private readonly SessionOptions _options;

    public event EventHandler? SessionsChanged;

    /// <summary>A game asked this session's pad to vibrate. Raised on the pad's callback thread.</summary>
    public event Action<Session, byte, byte>? RumbleRequested;

    public SessionManager(
        IVirtualPadFactory factory, SessionOptions? options = null,
        IRandomSource? random = null, TimeProvider? time = null)
    {
        _factory = factory;
        _options = options ?? new SessionOptions();
        _random = random ?? SecureRandomSource.Instance;
        _time = time ?? TimeProvider.System;
    }

    public SessionOptions Options => _options;

    // ------------------------------------------------------------------ open

    /// <summary>
    /// Creates a session for an already-authenticated HELLO, or resumes the device's existing one
    /// (same pad, new session id and key). Random draw order: session id (4 bytes), then server nonce (8 bytes).
    /// </summary>
    public OpenResult Open(
        DeviceId deviceId, string name, byte[] deviceKey, ReadOnlySpan<byte> clientNonce, IPEndPoint remote)
    {
        Session session;
        bool resumed;
        byte[] serverNonce = new byte[ProtocolConstants.NonceLength];

        lock (_gate)
        {
            long now = _time.GetTimestamp();
            if (_byDevice.TryGetValue(deviceId, out var existing))
            {
                session = existing;
                resumed = true;
                _byId.Remove(existing.SessionId);
                ResetPad(existing);
            }
            else
            {
                if (_byDevice.Count >= _options.MaxSessions)
                    return new OpenResult(null, false, serverNonce, OpenError.ServerFull);
                var pad = _factory.TryCreate();
                if (pad is null)
                    return new OpenResult(null, false, serverNonce, OpenError.ServerFull);
                session = new Session(deviceId, pad);
                resumed = false;
                _byDevice[deviceId] = session;
                HookRumble(session);
            }

            uint id = NewSessionId();
            _random.Fill(serverNonce);

            session.SessionId = id;
            session.SessionKey = ManetteCrypto.DeriveSessionKey(deviceKey, clientNonce, serverNonce);
            session.Name = name;
            session.Remote = CopyEndPoint(remote);
            session.State = SessionState.Active;
            session.HasSeq = false;
            session.LastSeq = 0;
            session.LastActivity = now;
            session.StateSince = now;
            session.RumbleSeq = 0;
            session.LastRumbleSent = 0;
            _byId[id] = session;
        }

        RaiseChanged();
        return new OpenResult(session, resumed, serverNonce, OpenError.None);
    }

    private void HookRumble(Session session) =>
        session.Pad.RumbleReceived += (large, small) => RumbleRequested?.Invoke(session, large, small);

    private uint NewSessionId()
    {
        Span<byte> b = stackalloc byte[4];
        uint id;
        do
        {
            _random.Fill(b);
            id = BitConverter.ToUInt32(b);
        } while (id == 0 || _byId.ContainsKey(id));
        return id;
    }

    // ---------------------------------------------------- authenticated input

    /// <summary>Verifies the session tag, enforces sequence order, and applies the state to the pad.</summary>
    public bool ReceiveInput(
        ReadOnlySpan<byte> packet, uint sessionId, ushort seq, in GamepadState state, IPEndPoint remote)
    {
        bool changed;
        lock (_gate)
        {
            var s = FindVerified(sessionId, packet);
            if (s is null || s.State == SessionState.Grace) return false;
            if (s.HasSeq && !SequenceNumber.IsNewer(s.LastSeq, seq)) return false;

            s.HasSeq = true;
            s.LastSeq = seq;
            s.Accepted++;
            changed = Touch(s, remote);

            // Keepalives repeat the same state; skip the driver call when nothing changed.
            if (state != s.LastState)
            {
                s.Pad.Update(state);
                s.LastState = state;
            }
        }
        if (changed) RaiseChanged();
        return true;
    }

    /// <summary>Verifies a PING and counts it as activity. Returns the session so the caller can answer with a PONG.</summary>
    public Session? ReceivePing(ReadOnlySpan<byte> packet, uint sessionId, IPEndPoint remote)
    {
        bool changed;
        Session? s;
        lock (_gate)
        {
            s = FindVerified(sessionId, packet);
            if (s is null || s.State == SessionState.Grace) return null;
            changed = Touch(s, remote);
        }
        if (changed) RaiseChanged();
        return s;
    }

    /// <summary>A verified BYE: pad goes neutral and the session enters its grace period immediately.</summary>
    public bool ReceiveBye(ReadOnlySpan<byte> packet, uint sessionId)
    {
        lock (_gate)
        {
            var s = FindVerified(sessionId, packet);
            if (s is null) return false;
            ResetPad(s);
            s.State = SessionState.Grace;
            s.StateSince = _time.GetTimestamp();
        }
        RaiseChanged();
        return true;
    }

    // ---------------------------------------------------------------- rumble

    /// <summary>
    /// Builds a signed RUMBLE for the session, honouring the advertised rate limit. Zero-intensity
    /// updates are never throttled so vibration cannot get stuck on. Returns 0 if nothing should be sent.
    /// </summary>
    public int TryBuildRumble(Session s, byte large, byte small, Span<byte> dst, out IPEndPoint? remote)
    {
        remote = null;
        if (_options.RumbleHzMax == 0) return 0;
        lock (_gate)
        {
            if (s.State != SessionState.Active || s.Remote is null) return 0;
            long now = _time.GetTimestamp();
            bool stop = large == 0 && small == 0;
            if (!stop && s.LastRumbleSent != 0)
            {
                var minGap = TimeSpan.FromSeconds(1.0 / _options.RumbleHzMax);
                if (_time.GetElapsedTime(s.LastRumbleSent, now) < minGap) return 0;
            }
            s.LastRumbleSent = now == 0 ? 1 : now;
            s.RumbleSeq = unchecked((ushort)(s.RumbleSeq + 1));
            remote = s.Remote;
            return PacketCodec.WriteRumble(dst, s.SessionId, s.RumbleSeq, large, small, s.SessionKey);
        }
    }

    // ------------------------------------------------------------------ tick

    /// <summary>Advances timeouts. One state step per session per call.</summary>
    public void Tick()
    {
        List<Session>? destroyed = null;
        bool changed = false;
        lock (_gate)
        {
            long now = _time.GetTimestamp();
            _scratch.Clear();
            _scratch.AddRange(_byDevice.Values);
            foreach (var s in _scratch)
            {
                switch (s.State)
                {
                    case SessionState.Active:
                        if (_time.GetElapsedTime(s.LastActivity, now) >= _options.StaleAfter)
                        {
                            ResetPad(s);
                            s.State = SessionState.Stale;
                            s.StateSince = now;
                            changed = true;
                        }
                        break;
                    case SessionState.Stale:
                        if (_time.GetElapsedTime(s.StateSince, now) >= _options.GraceAfterStale)
                        {
                            s.State = SessionState.Grace;
                            s.StateSince = now;
                            changed = true;
                        }
                        break;
                    case SessionState.Grace:
                        if (_time.GetElapsedTime(s.StateSince, now) >= _options.GraceDuration)
                        {
                            _byId.Remove(s.SessionId);
                            _byDevice.Remove(s.DeviceId);
                            (destroyed ??= new()).Add(s);
                            changed = true;
                        }
                        break;
                }
            }
        }

        if (destroyed is not null)
            foreach (var s in destroyed) s.Pad.Dispose();
        if (changed) RaiseChanged();
    }

    // -------------------------------------------------------------- queries

    public IReadOnlyList<SessionInfo> Snapshot()
    {
        lock (_gate)
        {
            return _byDevice.Values
                .Select(s => new SessionInfo(
                    s.SessionId, s.DeviceId, s.Name, s.State, s.Pad.Slot, s.Remote, s.Accepted))
                .ToArray();
        }
    }

    /// <summary>Removes a device's session immediately (for example the user clicks "disconnect").</summary>
    public bool Disconnect(DeviceId deviceId)
    {
        Session? s;
        lock (_gate)
        {
            if (!_byDevice.Remove(deviceId, out s)) return false;
            _byId.Remove(s.SessionId);
        }
        s.Pad.Dispose();
        RaiseChanged();
        return true;
    }

    public void Dispose()
    {
        List<Session> all;
        lock (_gate)
        {
            all = _byDevice.Values.ToList();
            _byDevice.Clear();
            _byId.Clear();
        }
        foreach (var s in all) s.Pad.Dispose();
    }

    // -------------------------------------------------------------- helpers

    private Session? FindVerified(uint sessionId, ReadOnlySpan<byte> packet)
    {
        if (!_byId.TryGetValue(sessionId, out var s)) return null;
        return ManetteCrypto.VerifyTag(s.SessionKey, packet, ProtocolConstants.SessionTagLength) ? s : null;
    }

    /// <summary>Marks activity, follows the phone if its address changed, and wakes a stale session. True if anything visible changed.</summary>
    private bool Touch(Session s, IPEndPoint remote)
    {
        bool changed = false;
        s.LastActivity = _time.GetTimestamp();
        if (s.State == SessionState.Stale)
        {
            s.State = SessionState.Active;
            s.StateSince = s.LastActivity;
            changed = true;
        }
        if (s.Remote is null || !s.Remote.Equals(remote))
        {
            s.Remote = CopyEndPoint(remote);
            changed = true;
        }
        return changed;
    }

    private static void ResetPad(Session s)
    {
        if (s.LastState != GamepadState.Neutral)
        {
            s.Pad.Update(GamepadState.Neutral);
            s.LastState = GamepadState.Neutral;
        }
    }

    private static IPEndPoint CopyEndPoint(IPEndPoint e) => new(e.Address, e.Port);

    private void RaiseChanged() => SessionsChanged?.Invoke(this, EventArgs.Empty);
}
