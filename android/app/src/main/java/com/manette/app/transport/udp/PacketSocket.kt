package com.manette.app.transport.udp

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

/** The little the transport needs from a socket, so tests can substitute a fake one. */
interface PacketSocket : AutoCloseable {
    /** Sends one datagram to the PC. May throw [IOException]. */
    fun send(data: ByteArray, length: Int)

    /** Blocks until a datagram arrives and returns its length, or -1 once the socket is closed. */
    fun receive(buffer: ByteArray): Int
}

/**
 * Real UDP. Unconnected on purpose: replies are authenticated by their HMAC, not by source address, so a PC that
 * answers from another interface still works. NOTE: building an [InetSocketAddress] from a host NAME does a DNS
 * lookup, so create it off the main thread (numeric IPs do not).
 */
class JvmUdpSocket(private val target: InetSocketAddress) : PacketSocket {
    private val socket = DatagramSocket()

    override fun send(data: ByteArray, length: Int) {
        socket.send(DatagramPacket(data, length, target))
    }

    override fun receive(buffer: ByteArray): Int {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.length
        } catch (e: SocketException) {
            if (socket.isClosed) -1 else throw e
        }
    }

    override fun close() = socket.close()
}
