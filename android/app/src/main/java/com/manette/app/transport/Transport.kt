package com.manette.app.transport

import com.manette.app.core.GamepadState
import com.manette.app.protocol.RejectReason

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** Was connected, lost contact with the PC, and is trying again on its own. */
    RECONNECTING,
}

/**
 * Callbacks from a transport. They arrive on transport-owned threads, never the main thread, so a UI layer
 * must hop to the main thread itself (for example by writing into a MutableStateFlow). Every method has an
 * empty default so a listener overrides only what it needs.
 */
interface TransportListener {
    fun onStateChanged(state: ConnectionState) {}

    /** First-time pairing finished. Persist [deviceKey] (it is a credential) so the next connect needs no code. */
    fun onPaired(deviceKey: ByteArray) {}

    /** The attempt to connect failed because the PC said no (not paired, server full, wrong version...). */
    fun onRejected(reason: RejectReason) {}

    /** The attempt to connect failed with no explanation (PC off, wrong address, firewall). */
    fun onError(message: String) {}

    /** The game asked this controller to vibrate. Both motors are 0..255. */
    fun onRumble(large: Int, small: Int) {}

    /** Round-trip time in milliseconds, about once a second while connected. */
    fun onLatency(rttMs: Int) {}
}

/**
 * The seam between the controller UI and the way bytes reach the PC. The UI and layout code only ever see this
 * interface and [GamepadState]; nothing above it knows about IP addresses or sockets. Wi-Fi/USB-tether UDP is
 * [com.manette.app.transport.udp.UdpTransport]; a future Bluetooth HID transport is a second implementation.
 *
 * How a transport is created and connected differs per kind (UDP needs an address and a pairing secret, Bluetooth
 * needs a paired device), so that part lives on the concrete class and in a [ConnectionProvider], not here.
 */
interface Transport : AutoCloseable {
    val state: ConnectionState

    /** XInput slot the PC reports for this controller, or null if unknown (it is informational only). */
    val slot: Int?

    /** Last measured round-trip time in ms, or -1 if none yet. */
    val rttMs: Int

    fun addListener(listener: TransportListener)
    fun removeListener(listener: TransportListener)

    /**
     * Report the controller's current state. Safe to call from the touch/UI thread at any rate: it never blocks
     * and never touches the network on the calling thread (Android forbids that), and bursts are coalesced so
     * only the newest state is sent. If not connected yet, the state is held and sent as soon as it connects.
     */
    fun send(gamepad: GamepadState)

    /** Politely ends the session (the PC resets the controller to neutral) but keeps the transport reusable. */
    fun disconnect()

    /** Releases every thread and socket. The transport cannot be used afterwards. */
    override fun close()
}
