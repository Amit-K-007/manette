package com.manette.app.session

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manette.app.connect.ConnectInput
import com.manette.app.connect.ConnectInputParser
import com.manette.app.connect.RejectMessages
import com.manette.app.core.GamepadState
import com.manette.app.protocol.DeviceId
import com.manette.app.protocol.Hex
import com.manette.app.protocol.RejectReason
import com.manette.app.transport.ConnectionState
import com.manette.app.transport.TransportListener
import com.manette.app.transport.udp.UdpTransport
import java.net.InetSocketAddress

class SavedPc(val host: String, val port: Int, val deviceKey: ByteArray)

/**
 * Owns the one [UdpTransport], remembers the paired PC, and exposes Compose state. Transport callbacks arrive on
 * background threads, so every state change is posted to the main thread. Keys are kept in plain SharedPreferences
 * for now (fine for testing; protect them before release).
 * NOTE: not compiled (no Android toolchain where it was written).
 */
class ConnectionController(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.applicationContext.getSharedPreferences("manette", Context.MODE_PRIVATE)
    private val deviceId: DeviceId = loadOrCreateDeviceId()

    var state by mutableStateOf(ConnectionState.DISCONNECTED); private set
    var message by mutableStateOf<String?>(null); private set
    var rttMs by mutableIntStateOf(-1); private set
    var savedPc by mutableStateOf(loadSaved()); private set

    @Volatile private var transport: UdpTransport? = null
    @Volatile private var attemptHost = ""
    @Volatile private var attemptPort = 0

    private val listener = object : TransportListener {
        override fun onStateChanged(state: ConnectionState) {
            main.post {
                this@ConnectionController.state = state
                if (state == ConnectionState.CONNECTED) message = null
                if (state != ConnectionState.CONNECTED) rttMs = -1
            }
        }
        override fun onPaired(deviceKey: ByteArray) {
            val pc = SavedPc(attemptHost, attemptPort, deviceKey)
            save(pc)
            main.post { savedPc = pc }
        }
        override fun onRejected(reason: RejectReason) { main.post { message = RejectMessages.describe(reason) } }
        override fun onError(message: String) { main.post { this@ConnectionController.message = message } }
        override fun onLatency(rttMs: Int) { main.post { this@ConnectionController.rttMs = rttMs } }
    }

    fun pair(hostText: String, codeText: String) {
        when (val r = ConnectInputParser.parse(hostText, codeText)) {
            is ConnectInput.Invalid -> message = r.message
            is ConnectInput.Ready -> start(r.host, r.port) { it.pair(r.secret) }
        }
    }

    fun reconnect() {
        val pc = savedPc ?: return
        start(pc.host, pc.port) { it.connect(pc.deviceKey) }
    }

    /** Call from the touch handler. Never blocks. */
    fun send(gamepad: GamepadState) { transport?.send(gamepad) }

    fun disconnect() { transport?.disconnect() }

    fun forgetPc() {
        prefs.edit().remove("pc_host").remove("pc_port").remove("device_key").apply()
        savedPc = null
    }

    fun close() { transport?.close(); transport = null }

    private fun start(host: String, port: Int, begin: (UdpTransport) -> Unit) {
        message = null
        state = ConnectionState.CONNECTING
        attemptHost = host
        attemptPort = port
        // Building the address and socket can touch the network, which Android forbids on the main thread.
        Thread {
            try {
                transport?.close()
                val t = UdpTransport.create(InetSocketAddress(host, port), deviceId, Build.MODEL ?: "Android")
                t.addListener(listener)
                transport = t
                begin(t)
            } catch (e: Exception) {
                main.post { state = ConnectionState.DISCONNECTED; message = "Could not reach $host: ${e.message}" }
            }
        }.start()
    }

    private fun loadOrCreateDeviceId(): DeviceId {
        prefs.getString("device_id", null)?.let { return DeviceId.fromHex(it) }
        return DeviceId.random().also { prefs.edit().putString("device_id", it.toString()).apply() }
    }

    private fun loadSaved(): SavedPc? {
        val host = prefs.getString("pc_host", null) ?: return null
        val key = prefs.getString("device_key", null) ?: return null
        return SavedPc(host, prefs.getInt("pc_port", 26760), Hex.decode(key))
    }

    private fun save(pc: SavedPc) {
        prefs.edit().putString("pc_host", pc.host).putInt("pc_port", pc.port).putString("device_key", Hex.encode(pc.deviceKey)).apply()
    }
}
