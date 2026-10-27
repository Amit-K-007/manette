package com.manette.app.transport.udp

import com.manette.app.protocol.Protocol
import com.manette.app.transport.ConnectionProvider
import com.manette.app.transport.DiscoveryListener
import com.manette.app.transport.PcEndpoint

/**
 * "Connect by IP": the always-works fallback. It also covers USB tethering (the PC is usually the phone's
 * gateway address on that link) and networks that block multicast discovery.
 */
class ManualIpProvider : ConnectionProvider {
    override val id: String = "udp-manual"
    override val displayName: String = "Enter IP address"

    /** Manual entry has nothing to discover. */
    override fun startDiscovery(listener: DiscoveryListener): AutoCloseable = AutoCloseable { }

    companion object {
        /**
         * Parses `192.168.1.20`, `192.168.1.20:26760` or a host name such as `my-pc.local` (optionally with a port).
         * Returns null for anything that is clearly not an address. Does no network access.
         */
        fun parse(text: String, defaultPort: Int = Protocol.DEFAULT_PORT): PcEndpoint? {
            val t = text.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() } || t.contains('/') || t.contains('@')) return null
            val colon = t.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon >= 0) {
                if (t.indexOf(':') != colon) return null              // IPv6 and similar are not supported in v1
                host = t.substring(0, colon)
                port = t.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = t
                port = defaultPort
            }
            if (port !in 1..65535 || host.isEmpty() || host.length > 253) return null
            if (host.all { it.isDigit() || it == '.' }) {
                // Looks numeric, so it must be a valid dotted IPv4 address.
                val parts = host.split('.')
                if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) return null
            } else if (!host.matches(Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*"))) {
                return null
            }
            return PcEndpoint(name = host, host = host, port = port)
        }
    }
}
