using System.Net;
using System.Net.Sockets;
using Manette.Core.Server;

namespace Manette.Network;

public delegate void DatagramHandler(ReadOnlySpan<byte> data, IPEndPoint remote);

/// <summary>
/// The UDP socket. A dedicated high-priority thread does blocking receives (lowest latency: no thread-pool
/// hop) and hands each datagram straight to the handler. Also implements <see cref="IDatagramSender"/>.
/// Handler exceptions are logged and never stop the loop.
/// </summary>
public sealed class UdpEndpoint : IDatagramSender, IDisposable
{
    private const int ReceiveBufferSize = 2048;   // larger than any valid datagram so oversize ones are seen and dropped

    private readonly Socket _socket;
    private Thread? _thread;
    private volatile bool _stopping;

    public event Action<string>? Log;

    public UdpEndpoint(IPAddress bindAddress, int port)
    {
        _socket = new Socket(bindAddress.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
        if (OperatingSystem.IsWindows())
        {
            // Without this, an ICMP "port unreachable" from a phone that vanished makes the NEXT receive throw
            // WSAECONNRESET on Windows. Disable that behaviour (SIO_UDP_CONNRESET).
            const int SIO_UDP_CONNRESET = -1744830452;
            _socket.IOControl(SIO_UDP_CONNRESET, new byte[4], null);
        }
        _socket.Bind(new IPEndPoint(bindAddress, port));
    }

    /// <summary>The actual bound address (useful when port 0 was requested).</summary>
    public IPEndPoint LocalEndPoint => (IPEndPoint)_socket.LocalEndPoint!;

    public void Start(DatagramHandler handler)
    {
        if (_thread is not null) throw new InvalidOperationException("Already started.");
        _thread = new Thread(() => ReceiveLoop(handler))
        {
            IsBackground = true,
            Name = "Manette UDP receive",
            Priority = ThreadPriority.AboveNormal,
        };
        _thread.Start();
    }

    private void ReceiveLoop(DatagramHandler handler)
    {
        var buffer = new byte[ReceiveBufferSize];
        EndPoint any = _socket.AddressFamily == AddressFamily.InterNetworkV6
            ? new IPEndPoint(IPAddress.IPv6Any, 0)
            : new IPEndPoint(IPAddress.Any, 0);

        while (!_stopping)
        {
            try
            {
                EndPoint remote = any;
                int n = _socket.ReceiveFrom(buffer, SocketFlags.None, ref remote);
                handler(buffer.AsSpan(0, n), (IPEndPoint)remote);
            }
            catch (SocketException ex) when (_stopping || ex.SocketErrorCode is SocketError.Interrupted or SocketError.OperationAborted)
            {
                break;
            }
            catch (SocketException ex) when (ex.SocketErrorCode is SocketError.ConnectionReset or SocketError.MessageSize)
            {
                // ICMP unreachable from an earlier send, or an oversize datagram: neither is fatal.
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException ex)
            {
                Log?.Invoke($"UDP receive failed ({ex.SocketErrorCode}); stopping the receive loop.");
                break;
            }
            catch (Exception ex)
            {
                Log?.Invoke($"Error handling a datagram: {ex.GetType().Name}: {ex.Message}");
            }
        }
    }

    public void Send(ReadOnlySpan<byte> datagram, IPEndPoint destination)
    {
        try
        {
            _socket.SendTo(datagram, SocketFlags.None, destination);
        }
        catch (ObjectDisposedException) { }
        catch (SocketException ex)
        {
            Log?.Invoke($"UDP send to {destination} failed: {ex.SocketErrorCode}");
        }
    }

    public void Dispose()
    {
        _stopping = true;
        _socket.Dispose();
        _thread?.Join(TimeSpan.FromSeconds(1));
    }
}
