using System.Net;

namespace Manette.Core.Server;

/// <summary>Implemented by Manette.Network (a UDP socket). Core only produces bytes and destinations.</summary>
public interface IDatagramSender
{
    void Send(ReadOnlySpan<byte> datagram, IPEndPoint destination);
}
