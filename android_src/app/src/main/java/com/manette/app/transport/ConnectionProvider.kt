package com.manette.app.transport

/** A PC the user can connect to, as shown in the UI. */
data class PcEndpoint(
    val name: String,
    val host: String,
    val port: Int,
    /** From discovery: identifies the PC installation across IP changes. Null if unknown. */
    val installId: String? = null,
    /** From discovery: true while the PC has a pairing window open. Null if unknown. */
    val pairingOpen: Boolean? = null,
)

interface DiscoveryListener {
    fun onFound(pc: PcEndpoint)
    fun onLost(name: String)
}

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
    fun startDiscovery(listener: DiscoveryListener): AutoCloseable
}
