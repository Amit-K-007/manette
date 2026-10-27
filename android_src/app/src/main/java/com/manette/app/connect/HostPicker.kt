package com.manette.app.connect

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * A QR code lists every address the PC has (Wi-Fi, Ethernet, a USB-tether adapter). The phone should use the one it
 * can actually reach, which is almost always the one on the same subnet as one of the phone's own addresses.
 */
object HostPicker {
    /** Prefers a host on the same /24 as one of [localIpv4], then the same /16, else the first. Never returns a host not in [hosts]. */
    fun pick(hosts: List<String>, localIpv4: List<String>): String {
        require(hosts.isNotEmpty()) { "no hosts" }
        val locals = localIpv4.mapNotNull(::octets)
        for (prefix in listOf(3, 2)) {
            for (h in hosts) {
                val o = octets(h) ?: continue
                if (locals.any { l -> (0 until prefix).all { l[it] == o[it] } }) return h
            }
        }
        return hosts.first()
    }

    private fun octets(ip: String): IntArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val n = parts.map { it.toIntOrNull() ?: return null }
        return if (n.all { it in 0..255 }) n.toIntArray() else null
    }
}

/** The phone's own IPv4 addresses (no network access, just a local lookup). */
object LocalAddresses {
    fun ipv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }
}
