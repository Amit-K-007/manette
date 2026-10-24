using System.Net;
using System.Security.Cryptography;
using Manette.Core;
using Manette.Core.Pairing;
using Manette.Core.Protocol;
using Manette.Core.Server;
using Manette.Core.Sessions;
using Manette.Network.Mdns;

namespace Manette.Network;

public sealed record ManetteHostOptions
{
    public IPAddress BindAddress { get; init; } = IPAddress.Any;
    /// <summary>0 picks a free port (tests). Default is the protocol's well-known port.</summary>
    public int Port { get; init; } = ProtocolConstants.DefaultPort;
    public string PcName { get; init; } = Environment.MachineName;
    public bool EnableMdns { get; init; } = true;
    public TimeSpan TickInterval { get; init; } = TimeSpan.FromMilliseconds(50);
    public SessionOptions Sessions { get; init; } = new();
    public ServerOptions Server { get; init; } = new();
    /// <summary>Stable 8-hex id for this installation (persist it). Random per run if null.</summary>
    public string? InstallId { get; init; }
}

/// <summary>
/// Composition root for the companion's engine: socket + protocol server + tick loop + mDNS. UI projects
/// (CLI now, tray app later) create one of these, subscribe to its events, and call <see cref="OpenPairing"/>.
/// </summary>
public sealed class ManetteHost : IDisposable
{
    private readonly ManetteHostOptions _options;
    private readonly UdpEndpoint _endpoint;
    private readonly MdnsAdvertiser? _mdns;
    private readonly ManualResetEventSlim _stop = new(false);
    private Thread? _tickThread;

    public ManetteServer Server { get; }
    public PairingManager Pairing { get; }
    public SessionManager Sessions { get; }
    public ITrustedDeviceStore Store { get; }

    public event Action<string>? Log;

    public ManetteHost(
        IVirtualPadFactory pads, ManetteHostOptions? options = null, ITrustedDeviceStore? store = null)
    {
        _options = options ?? new ManetteHostOptions();
        Store = store ?? new InMemoryTrustedDeviceStore();
        Pairing = new PairingManager();
        Sessions = new SessionManager(pads, _options.Sessions);
        _endpoint = new UdpEndpoint(_options.BindAddress, _options.Port);
        _endpoint.Log += m => Log?.Invoke(m);
        Server = new ManetteServer(Store, Pairing, Sessions, _endpoint, options: _options.Server);

        if (_options.EnableMdns)
        {
            string id = _options.InstallId ?? Convert.ToHexString(RandomNumberGenerator.GetBytes(4)).ToLowerInvariant();
            _mdns = new MdnsAdvertiser(new MdnsServiceInfo
            {
                InstanceName = _options.PcName,
                Port = (ushort)_endpoint.LocalEndPoint.Port,
                Id = id,
                PairingOpen = () => Pairing.IsOpen,
            });
            _mdns.Log += m => Log?.Invoke(m);
            Pairing.Changed += (_, _) => _mdns.Announce();
        }
    }

    public int Port => _endpoint.LocalEndPoint.Port;
    public IPEndPoint LocalEndPoint => _endpoint.LocalEndPoint;
    public bool MdnsRunning => _mdns?.IsRunning ?? false;

    public void Start()
    {
        _endpoint.Start(Server.HandleDatagram);
        _tickThread = new Thread(TickLoop) { IsBackground = true, Name = "Manette tick" };
        _tickThread.Start();
        _mdns?.Start();
    }

    private void TickLoop()
    {
        while (!_stop.Wait(_options.TickInterval))
        {
            try { Server.Tick(); }
            catch (Exception ex) { Log?.Invoke($"Tick failed: {ex.GetType().Name}: {ex.Message}"); }
        }
    }

    /// <summary>Opens a pairing window and returns what to show: the QR link and the typed fallback code.</summary>
    public (PairingOffer Offer, string Link) OpenPairing()
    {
        var offer = Pairing.Open();
        string link = PairingCode.BuildLink(
            HostAddressProvider.GetCandidateHosts().Select(a => a.ToString()),
            (ushort)Port, offer.Secret, _options.PcName);
        return (offer, link);
    }

    public void Dispose()
    {
        _stop.Set();
        _mdns?.Dispose();
        _endpoint.Dispose();
        _tickThread?.Join(TimeSpan.FromSeconds(1));
        Sessions.Dispose();
    }
}
