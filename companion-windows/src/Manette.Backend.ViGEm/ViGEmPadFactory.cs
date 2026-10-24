using Manette.Core;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;

namespace Manette.Backend.ViGEm;

/// <summary>
/// Creates virtual Xbox 360 pads. One shared ViGEmClient (a handle to the bus driver) serves all pads.
/// If the driver is missing or unreachable, TryCreate returns null and <see cref="LastError"/> says why,
/// which the server reports to phones as "server full". The UI should surface LastError to the user.
/// </summary>
public sealed class ViGEmPadFactory : IVirtualPadFactory, IDisposable
{
    private readonly object _gate = new();
    private readonly Action<string>? _log;
    private ViGEmClient? _client;

    public ViGEmPadFactory(Action<string>? log = null) => _log = log;

    /// <summary>Why the last TryCreate failed, or null if it succeeded.</summary>
    public string? LastError { get; private set; }

    /// <summary>Opens the driver connection now, so a missing driver is found at startup rather than on the first phone.</summary>
    public bool TryProbe(out string? error)
    {
        lock (_gate)
        {
            bool ok = EnsureClient();
            error = ok ? null : LastError;
            return ok;
        }
    }

    public IVirtualPad? TryCreate()
    {
        lock (_gate)
        {
            if (!EnsureClient()) return null;
            try
            {
                var pad = new ViGEmVirtualPad(_client!.CreateXbox360Controller(), _log);
                LastError = null;
                return pad;
            }
            catch (VigemNoFreeSlotException)
            {
                return Fail("The ViGEm driver has no free controller slot.");
            }
            catch (VigemBusNotFoundException)
            {
                DropClient();
                return Fail("The ViGEmBus driver is not installed or was disabled.");
            }
            catch (Exception ex)
            {
                return Fail($"Could not create a virtual controller: {ex.GetType().Name}: {ex.Message}");
            }
        }
    }

    private bool EnsureClient()
    {
        if (_client is not null) return true;
        try
        {
            _client = new ViGEmClient();
            LastError = null;
            return true;
        }
        catch (VigemBusNotFoundException)
        {
            Fail("The ViGEmBus driver is not installed. Install it and try again.");
        }
        catch (VigemBusAccessFailedException)
        {
            Fail("Windows denied access to the ViGEmBus driver.");
        }
        catch (VigemBusVersionMismatchException)
        {
            Fail("The installed ViGEmBus driver version is not compatible with this build.");
        }
        catch (Exception ex)
        {
            Fail($"Could not connect to the ViGEmBus driver: {ex.GetType().Name}: {ex.Message}");
        }
        return false;
    }

    private IVirtualPad? Fail(string message)
    {
        LastError = message;
        _log?.Invoke(message);
        return null;
    }

    private void DropClient()
    {
        _client?.Dispose();
        _client = null;
    }

    public void Dispose()
    {
        lock (_gate) DropClient();
    }
}
