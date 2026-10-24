package com.manette.app.transport

/** A PC the user can connect to, as shown in the UI. */
data class PcEndpoint(val name: String, val host: String, val port: Int)

/**
 * Finds or describes PCs for one kind of transport, so the connect screen can offer several ways in without
 * knowing how each works. Wi-Fi discovery (NsdManager) and Bluetooth scanning will be providers too.
 */
interface ConnectionProvider {
    /** Stable id such as "udp-manual". */
    val id: String
    val displayName: String

    /**
     * Starts looking for PCs and reports each one found. Returns a handle that stops the search when closed.
     * A provider that cannot discover anything (manual entry) simply never calls back.
     */
    fun startDiscovery(onFound: (PcEndpoint) -> Unit): AutoCloseable
}
