package com.manette.app.transport

import com.manette.app.core.GamepadState
import com.manette.app.protocol.DeviceId
import com.manette.app.protocol.ManetteCrypto
import com.manette.app.protocol.MessageType
import com.manette.app.protocol.PacketCodec
import com.manette.app.protocol.Protocol
import com.manette.app.protocol.RejectReason
import com.manette.app.protocol.SequenceNumber
import com.manette.app.protocol.WelcomeFlags
import com.manette.app.transport.udp.PacketSocket
import com.manette.app.transport.udp.UdpTransport
import java.io.IOException
import java.security.SecureRandom

class ManualClock : MonotonicClock {
    var now = 1_000L
    fun advance(ms: Long) { now += ms }
    override fun nowMs(): Long = now
}

/** Runs queued work only when the test says so, against a clock the test controls. */
class ManualScheduler(private val clock: ManualClock) : Scheduler {
    private class Item(val due: Long, val order: Long, val task: Runnable)

    private val items = ArrayList<Item>()
    private var counter = 0L
    private var periodic: Runnable? = null
    var isShutdown = false

    override fun post(task: Runnable) { items.add(Item(clock.now, counter++, task)) }
    override fun after(delayMs: Long, task: Runnable) { items.add(Item(clock.now + delayMs, counter++, task)) }
    override fun every(periodMs: Long, task: Runnable) { periodic = task }
    override fun shutdown() { isShutdown = true; items.clear(); periodic = null }

    /** Run everything that is due now, including work that gets queued while running. */
    fun runDue() {
        while (true) {
            val next = items.filter { it.due <= clock.now }.minWithOrNull(compareBy({ it.due }, { it.order })) ?: return
            items.remove(next)
            next.task.run()
        }
    }

    /** One periodic tick, like the real scheduler would fire. */
    fun fireTick() { runDue(); periodic?.run(); runDue() }
}

class FakePacketSocket : PacketSocket {
    val sent = ArrayList<ByteArray>()
    var failSends = false
    var closed = false

    override fun send(data: ByteArray, length: Int) {
        if (failSends) throw IOException("network unreachable")
        sent.add(data.copyOf(length))
    }

    override fun receive(buffer: ByteArray): Int = error("tests feed datagrams directly")
    override fun close() { closed = true }
    fun drain(): List<ByteArray> = ArrayList(sent).also { sent.clear() }
}

class Recorder : TransportListener {
    val states = ArrayList<ConnectionState>()
    val paired = ArrayList<ByteArray>()
    val rejected = ArrayList<RejectReason>()
    val errors = ArrayList<String>()
    val rumbles = ArrayList<Pair<Int, Int>>()
    val latencies = ArrayList<Int>()

    override fun onStateChanged(state: ConnectionState) { states.add(state) }
    override fun onPaired(deviceKey: ByteArray) { paired.add(deviceKey) }
    override fun onRejected(reason: RejectReason) { rejected.add(reason) }
    override fun onError(message: String) { errors.add(message) }
    override fun onRumble(large: Int, small: Int) { rumbles.add(large to small) }
    override fun onLatency(rttMs: Int) { latencies.add(rttMs) }
}

/**
 * The PC side of the protocol, written independently of PacketCodec's phone-side functions so the transport is
 * tested against a second implementation of the spec. Its encoders are themselves checked against the shared vectors.
 */
object TestPcCodec {
    class Hello(val flags: Int, val deviceId: DeviceId, val nonce: ByteArray, val name: String)
    class InputView(val sessionId: Long, val seq: Int, val state: GamepadState)

    private fun header(type: Int, sessionId: Long) = byteArrayOf(
        Protocol.MAGIC0.toByte(), Protocol.MAGIC1.toByte(), Protocol.VERSION.toByte(), type.toByte(),
        sessionId.toByte(), (sessionId ushr 8).toByte(), (sessionId ushr 16).toByte(), (sessionId ushr 24).toByte(),
    )

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun i16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun signed(body: ByteArray, key: ByteArray, tagLen: Int, extra: ByteArray = ByteArray(0)) =
        body + ManetteCrypto.computeTag(key, body, body.size, tagLen, extra)

    fun type(p: ByteArray) = u8(p, 3)

    fun parseHello(p: ByteArray): Hello? {
        if (p.size < Protocol.HELLO_MIN_LENGTH || type(p) != MessageType.HELLO) return null
        val nameLen = u8(p, 33)
        if (p.size != 34 + nameLen + 16) return null
        return Hello(u8(p, 8), DeviceId(p.copyOfRange(9, 25)), p.copyOfRange(25, 33), String(p, 34, nameLen, Charsets.UTF_8))
    }

    fun parseInput(p: ByteArray): InputView? {
        if (p.size != Protocol.INPUT_LENGTH || type(p) != MessageType.INPUT) return null
        return InputView(
            u32(p, 4), u16(p, 8),
            GamepadState(u16(p, 10), u8(p, 12), u8(p, 13), i16(p, 14), i16(p, 16), i16(p, 18), i16(p, 20)),
        )
    }

    fun parsePingTimestamp(p: ByteArray): Long? =
        if (p.size == Protocol.PING_LENGTH && type(p) == MessageType.PING) u32(p, 8) else null

    fun writeWelcome(
        sessionId: Long, flags: Int, slot: Int, rumbleHz: Int, serverNonce: ByteArray, deviceKey: ByteArray, clientNonce: ByteArray,
    ) = signed(header(MessageType.WELCOME, sessionId) + byteArrayOf(flags.toByte(), slot.toByte()) + le16(rumbleHz) + serverNonce,
        deviceKey, 16, clientNonce)

    fun writeReject(reason: Int, maxVersion: Int = 1) =
        header(MessageType.REJECT, 0) + byteArrayOf(reason.toByte(), maxVersion.toByte())

    fun writePong(sessionId: Long, timestamp: Long, key: ByteArray) =
        signed(header(MessageType.PONG, sessionId) + le32(timestamp), key, 8)

    fun writeRumble(sessionId: Long, seq: Int, large: Int, small: Int, key: ByteArray) =
        signed(header(MessageType.RUMBLE, sessionId) + le16(seq) + byteArrayOf(large.toByte(), small.toByte()), key, 8)
}

class FakePc(private val pairingSecret: ByteArray?, val trusted: MutableMap<DeviceId, ByteArray> = HashMap()) {
    class PcSession(val deviceId: DeviceId, val sessionId: Long, val key: ByteArray) { var lastSeq = -1 }

    val sessions = HashMap<DeviceId, PcSession>()
    val inputs = ArrayList<GamepadState>()
    val inputSeqs = ArrayList<Int>()
    val helloFlags = ArrayList<Int>()
    var helloCount = 0
    var byeCount = 0
    var pairingOpen = true
    var slot = 0
    var rumbleHz = 30
    private var nextSessionId = 1000L

    val currentSession: PcSession get() = sessions.values.single()

    /** Processes one datagram from the phone and returns the datagrams the PC sends back. */
    fun handle(datagram: ByteArray): List<ByteArray> {
        val h = PacketCodec.readHeader(datagram) ?: return emptyList()
        return when (h.type) {
            MessageType.HELLO -> hello(datagram)
            MessageType.INPUT -> { input(datagram, h.sessionId); emptyList() }
            MessageType.PING -> ping(datagram, h.sessionId)
            MessageType.BYE -> { if (verified(datagram, h.sessionId, 8) != null) byeCount++; emptyList() }
            else -> emptyList()
        }
    }

    private fun verified(d: ByteArray, sessionId: Long, tagLen: Int): PcSession? {
        val s = sessions.values.firstOrNull { it.sessionId == sessionId } ?: return null
        return if (ManetteCrypto.verifyTag(s.key, d, d.size, tagLen)) s else null
    }

    private fun hello(d: ByteArray): List<ByteArray> {
        helloCount++
        val hello = TestPcCodec.parseHello(d) ?: return emptyList()
        helloFlags.add(hello.flags)
        val pairing = hello.flags and 1 != 0
        var newlyPairing = false
        val key: ByteArray = if (pairing) {
            if (pairingOpen && pairingSecret != null) {
                newlyPairing = true
                ManetteCrypto.deriveDeviceKey(pairingSecret, hello.deviceId)
            } else {
                // Window closed: only a phone that already paired may retry (as the real server does).
                trusted[hello.deviceId] ?: return listOf(TestPcCodec.writeReject(4))
            }
        } else {
            trusted[hello.deviceId] ?: return listOf(TestPcCodec.writeReject(1))
        }
        if (!ManetteCrypto.verifyTag(key, d, d.size, 16)) return emptyList()   // bad tag: silence

        if (newlyPairing) { trusted[hello.deviceId] = key; pairingOpen = false }
        val resumed = sessions.containsKey(hello.deviceId)
        val id = nextSessionId++
        val serverNonce = ByteArray(8) { (id + it).toByte() }
        sessions[hello.deviceId] = PcSession(hello.deviceId, id, ManetteCrypto.deriveSessionKey(key, hello.nonce, serverNonce))
        var flags = 0
        if (resumed) flags = flags or WelcomeFlags.RESUMED
        if (newlyPairing) flags = flags or WelcomeFlags.PAIRED_NOW
        return listOf(TestPcCodec.writeWelcome(id, flags, slot, rumbleHz, serverNonce, key, hello.nonce))
    }

    private fun input(d: ByteArray, sessionId: Long) {
        val s = verified(d, sessionId, 8) ?: return
        val m = TestPcCodec.parseInput(d) ?: return
        if (s.lastSeq >= 0 && !SequenceNumber.isNewer(s.lastSeq, m.seq)) return
        s.lastSeq = m.seq
        inputs.add(m.state)
        inputSeqs.add(m.seq)
    }

    private fun ping(d: ByteArray, sessionId: Long): List<ByteArray> {
        val s = verified(d, sessionId, 8) ?: return emptyList()
        val ts = TestPcCodec.parsePingTimestamp(d) ?: return emptyList()
        return listOf(TestPcCodec.writePong(s.sessionId, ts, s.key))
    }
}

/** A transport wired to fakes, with helpers to move packets between the phone and the fake PC. */
class Rig(secret: ByteArray? = SECRET) {
    companion object {
        val SECRET = ByteArray(16) { (it + 3).toByte() }
        val DEVICE_ID = DeviceId(ByteArray(16) { (it + 0x80).toByte() })
    }

    val clock = ManualClock()
    val scheduler = ManualScheduler(clock)
    val socket = FakePacketSocket()
    val pc = FakePc(secret)
    val events = Recorder()
    val transport = UdpTransport(socket, DEVICE_ID, "Test phone", clock, scheduler, SecureRandom(), startReceiveThread = false)

    init { transport.addListener(events) }

    /** Deliver everything the phone has sent to the PC, and the PC's answers back, until nothing is left. */
    fun pump() {
        repeat(20) {
            val out = socket.drain()
            if (out.isEmpty()) return
            for (d in out) for (r in pc.handle(d)) transport.onDatagram(r, r.size)
        }
    }

    /** Advance time and fire one periodic tick, then exchange packets. */
    fun tick(ms: Long = 50) { clock.advance(ms); scheduler.fireTick(); pump() }

    /** Advance time and fire a tick but DON'T deliver anything (the network is down). */
    fun tickSilently(ms: Long = 50) { clock.advance(ms); scheduler.fireTick(); socket.drain() }

    fun pairAndConnect() {
        transport.pair(SECRET)
        scheduler.runDue()
        pump()
    }

    fun deviceKey(): ByteArray = ManetteCrypto.deriveDeviceKey(SECRET, DEVICE_ID)
}
