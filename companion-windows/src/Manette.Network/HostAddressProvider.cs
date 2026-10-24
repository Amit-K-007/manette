using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Manette.Network;

public sealed record HostInterface(string Name, IPAddress Address, int InterfaceIndex, bool HasGateway);

/// <summary>
/// Finds the PC's usable IPv4 addresses. Used for the QR code (so a phone can try each network, including a
/// USB-tether adapter) and to pick which interfaces mDNS answers on.
/// </summary>
public static class HostAddressProvider
{
    public static IReadOnlyList<HostInterface> GetInterfaces()
    {
        var result = new List<HostInterface>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;

                var props = ni.GetIPProperties();
                bool gateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork
                                                               && !g.Address.Equals(IPAddress.Any));
                int index;
                try { index = props.GetIPv4Properties()?.Index ?? 0; } catch { index = 0; }

                foreach (var ua in props.UnicastAddresses)
                {
                    var a = ua.Address;
                    if (a.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (IPAddress.IsLoopback(a) || IsLinkLocal(a)) continue;
                    result.Add(new HostInterface(ni.Name, a, index, gateway));
                }
            }
        }
        catch (NetworkInformationException) { }

        // Interfaces with a default gateway (real LAN) first; tether adapters usually have none, so they follow.
        return result.OrderByDescending(r => r.HasGateway).ToList();
    }

    public static IReadOnlyList<IPAddress> GetCandidateHosts() => GetInterfaces().Select(i => i.Address).ToList();

    private static bool IsLinkLocal(IPAddress a)
    {
        var b = a.GetAddressBytes();
        return b[0] == 169 && b[1] == 254;
    }
}
