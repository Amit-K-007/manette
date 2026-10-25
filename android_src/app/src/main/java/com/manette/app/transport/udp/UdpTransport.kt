package com.manette.app.transport.udp

import com.manette.app.core.GamepadState
import com.manette.app.protocol.DeviceId
import com.manette.app.protocol.HelloFlags
import com.manette.app.protocol.ManetteCrypto
import com.manette.app.protocol.MessageType
import com.manette.app.protocol.PacketCodec
import com.manette.app.protocol.Protocol
import com.manette.app.protocol.RejectReason
import com.manette.app.protocol.SequenceNumber
import com.manette.app.protocol.WelcomeFlags
import com.manette.app.transport.ConnectionState
import com.manette.app.transport.ExecutorScheduler
import com.manette.app.transport.MonotonicClock
import com.manette.app.transport.Scheduler
import com.manette.app.transport.SystemMonotonicClock
import com.manette.app.transport.Transport
import com.manette.app.transport.TransportListener
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi / USB-tether transport: the phone side of docs/protocol.md, ported from the reference PhoneClient.cs.
 *
 * Threads: one receive thread (blocking socket reads) and ONE scheduler thread that does all sending, so the
 * network is never touched from the UI thread. All shared state is guarded by [lock]; listeners are called after
 * the lock is released, so a listener may safely call back into the transport.
 *
 * Lifecycle: call [pair] (first time, with the code from the PC) or [connect] (already paired, with the stored
 * device key). Connection is asynchronous; watch [TransportListener.onStateChanged].
 *
 * Timing (all from the spec): INPUT on every change and at 20 Hz while idle, a repeat of any "release" 2 ms later,
 * PING every second, a fresh HELLO after 3 s without a PONG, HELLO retries every 500 ms with a fresh nonce.
 */
class UdpTransport internal constructor(
    private val socket: PacketSocket,
    private val deviceId: DeviceId,
    private val deviceName: String,
    private val clock: MonotonicClock,
    private val scheduler: Scheduler,
    private val random: SecureRandom,
    startReceiveThread: Boolean,
) : Transport {

    companion object {
        const val TICK_MS = 50L
        const val HELLO_RETRY_MS = 500L
        const val PING_EVERY_MS = 1_000L
        const val PONG_TIMEOUT_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val RECONNECT_WINDOW_MS = 60_000L
        const val RELEASE_REPEAT_MS = 2L

        /** Real sockets and threads. Create this off the main thread if [address] was built from a host name. */
        fun create(address: InetSocketAddress, deviceId: DeviceId, deviceName: String): UdpTransport =
            UdpTransport(
                JvmUdpSocket(address), deviceId, deviceName, SystemMonotonicClock,
                ExecutorScheduler("Manette UDP send"), SecureRandom(), startReceiveThread = true,
            )
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<TransportListener>()
    private val pendingEvents = ArrayList<(TransportListener) -> Unit>()
    private val sendPending = AtomicBoolean(false)

    // ---- everything below is guarded by [lock] ----
    @Volatile private var currentState = ConnectionState.DISCONNECTED
    @Volatile private var currentSlot: Int? = null
    @Volatile private var currentRtt = -1

    private var closed = false
    private var deviceKey: ByteArray? = null
    private var helloFlags = HelloFlags.NONE
    private var pendingNonce: ByteArray? = null
    private var helloSentAt = 0L
    private var handshakeDeadline = 0L
    private var lastReject: RejectReason? = null

    private var sessionId = 0L
    private var sessionKey = ByteArray(0)
    private var seq = 0
    private var lastPongAt = 0L
    private var lastPingAt = 0L
    private var latest = GamepadState.NEUTRAL
    private var lastRumbleSeq = 0
    private var haveRumbleSeq = false

    private val txBuf = ByteArray(Protocol.MAX_DATAGRAM_LENGTH)
    private val rxBuf = ByteArray(256)
    private var receiveThread: Thread? = null

    init {
        scheduler.every(TICK_MS, Runnable { tick() })
        if (startReceiveThread) {
            receiveThread = Thread({ receiveLoop() }, "Manette UDP receive").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }
    }

    override val state: ConnectionState get() = currentState
    override val slot: Int? get() = currentSlot
    override val rttMs: Int get() = currentRtt

    override fun addListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------ connecting

    /** First-time pairing with the secret from the PC's QR code or typed code. */
    fun pair(secret: ByteArray) {
        require(secret.size == Protocol.PAIRING_SECRET_LENGTH) { "pairing secret must be 16 bytes" }
        synchronized(lock) {
            check(currentState == ConnectionState.DISCONNECTED) { "already connecting or connected" }
            deviceKey = com.manette.app.protocol.ManetteCrypto.deriveDeviceKey(secret, deviceId)
            helloFlags = HelloFlags.PAIRING
            beginHandshake(ConnectionState.CONNECTING, CONNECT_TIMEOUT_MS)
        }
        flush()
    }

    /** Connect as an already-paired phone using the device key saved at pairing time. */
    fun connect(deviceKey: ByteArray) {
        require(deviceKey.size == Protocol.KEY_LENGTH) { "device key must be 32 bytes" }
        synchronized(lock) {
            check(currentState == ConnectionState.DISCONNECTED) { "already connecting or connected" }
            this.deviceKey = deviceKey.copyOf()
            helloFlags = HelloFlags.NONE
            beginHandshake(ConnectionState.CONNECTING, CONNECT_TIMEOUT_MS)
        }
        flush()
    }

    private fun beginHandshake(newState: ConnectionState, timeoutMs: Long) {
        val now = clock.nowMs()
        lastReject = null
        pendingNonce = null
        handshakeDeadline = now + timeoutMs
        helloSentAt = now - HELLO_RETRY_MS          // due immediately
        setState(newState)
        scheduler.post(Runnable { tick() })         // send the first HELLO now, not at the next tick
    }

    // ---------------------------------------------------------------- sending

    override fun send(gamepad: GamepadState) {
        val release: Boolean
        synchronized(lock) {
            if (closed) return
            release = isRelease(latest, gamepad)
            latest = gamepad
            if (currentState != ConnectionState.CONNECTED) return   // held until we connect
        }
        // Coalesce bursts: many touch events while one send is queued collapse into a single packet of the newest state.
        if (sendPending.compareAndSet(false, true)) {
            scheduler.post(Runnable {
                sendPending.set(false)
                synchronized(lock) { if (currentState == ConnectionState.CONNECTED) sendInput() }
            })
        }
        if (release) {
            // A lost release is the one loss users notice (a stuck input), so repeat it once, about 2 ms later.
            scheduler.after(RELEASE_REPEAT_MS, Runnable {
                synchronized(lock) { if (currentState == ConnectionState.CONNECTED) sendInput() }
            })
        }
    }

    private fun isRelease(a: GamepadState, b: GamepadState): Boolean =
        (a.buttons and b.buttons.inv()) != 0 ||
            (a.leftTrigger > 0 && b.leftTrigger == 0) || (a.rightTrigger > 0 && b.rightTrigger == 0) ||
            ((a.leftX != 0 || a.leftY != 0) && b.leftX == 0 && b.leftY == 0) ||
            ((a.rightX != 0 || a.rightY != 0) && b.rightX == 0 && b.rightY == 0)

    // The rawSend/send* helpers below must only be called while holding [lock]: they share [txBuf].

    private fun rawSend(length: Int) {
        try {
            socket.send(txBuf, length)
        } catch (_: IOException) {
            // Network unreachable, interface down, and so on. The watchdog notices when nothing comes back.
        }
    }

    private fun sendInput() {
        seq = (seq + 1) and 0xFFFF
        val n = PacketCodec.writeInput(txBuf, sessionId, seq, latest, sessionKey)
        rawSend(n)
    }

    private fun sendPing(now: Long) {
        lastPingAt = now
        val n = PacketCodec.writePing(txBuf, sessionId, now and 0xFFFFFFFFL, sessionKey)
        rawSend(n)
    }

    private fun sendHello(now: Long) {
        // A FRESH nonce per HELLO, and only the WELCOME for the newest one is accepted. That way a late or
        // replayed WELCOME from an earlier attempt can never install a stale session key.
        val nonce = ByteArray(Protocol.NONCE_LENGTH).also { random.nextBytes(it) }
        pendingNonce = nonce
        helloSentAt = now
        val n = PacketCodec.writeHello(txBuf, helloFlags, deviceId, nonce, deviceName, deviceKey!!)
        rawSend(n)
    }

    // ------------------------------------------------------------------- tick

    /** Driven by the scheduler every [TICK_MS]; tests call it directly. */
    internal fun tick() {
        synchronized(lock) { if (!closed) tickLocked() }
        flush()
    }

    private fun tickLocked() {
        val now = clock.nowMs()
        when (currentState) {
            ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> {
                if (now >= handshakeDeadline) failHandshake()
                else if (now - helloSentAt >= HELLO_RETRY_MS) sendHello(now)
            }
            ConnectionState.CONNECTED -> {
                if (now - lastPongAt >= PONG_TIMEOUT_MS) {
                    startReconnect(now)
                } else {
                    sendInput()                                   // 20 Hz keepalive carrying the full current state
                    if (now - lastPingAt >= PING_EVERY_MS) sendPing(now)
                }
            }
            ConnectionState.DISCONNECTED -> Unit
        }
    }

    private fun startReconnect(now: Long) {
        helloFlags = HelloFlags.NONE
        beginHandshake(ConnectionState.RECONNECTING, RECONNECT_WINDOW_MS)
        sendHello(now)
    }

    private fun failHandshake() {
        // REJECT is unauthenticated, so it never ended the attempt early; it only explains the failure now.
        val reason = lastReject
        lastReject = null
        pendingNonce = null
        setState(ConnectionState.DISCONNECTED)
        if (reason != null) emit { it.onRejected(reason) } else emit { it.onError("The PC did not respond. Check the address, Wi-Fi and the firewall.") }
    }

    // ---------------------------------------------------------------- receive

    private fun receiveLoop() {
        while (true) {
            val n = try {
                socket.receive(rxBuf)
            } catch (e: IOException) {
                synchronized(lock) { if (closed) return }
                continue
            }
            if (n < 0) return
            onDatagram(rxBuf, n)
        }
    }

    /** Handles one received datagram. Called by the receive thread; tests call it directly. */
    internal fun onDatagram(buf: ByteArray, length: Int) {
        if (length > Protocol.MAX_DATAGRAM_LENGTH) return
        synchronized(lock) {
            if (closed) return
            val header = PacketCodec.readHeader(buf, length) ?: return
            if (header.version != Protocol.VERSION) return
            when (header.type) {
                MessageType.WELCOME -> onWelcome(buf, length)
                MessageType.REJECT -> onReject(buf, length)
                MessageType.PONG -> onPong(buf, length, header.sessionId)
                MessageType.RUMBLE -> onRumble(buf, length, header.sessionId)
            }
        }
        flush()
    }

    private fun onWelcome(buf: ByteArray, length: Int) {
        if (currentState != ConnectionState.CONNECTING && currentState != ConnectionState.RECONNECTING) return
        val nonce = pendingNonce ?: return
        val key = deviceKey ?: return
        // Bound to OUR newest HELLO via client_nonce: anything old, forged or replayed fails right here.
        if (!ManetteCrypto.verifyTag(key, buf, length, Protocol.HANDSHAKE_TAG_LENGTH, nonce)) return
        val w = PacketCodec.parseWelcome(buf, length) ?: return

        sessionId = w.sessionId
        sessionKey = ManetteCrypto.deriveSessionKey(key, nonce, w.serverNonce)
        seq = 0
        haveRumbleSeq = false
        currentSlot = w.slot
        pendingNonce = null
        lastReject = null
        val now = clock.nowMs()
        lastPongAt = now
        lastPingAt = now

        if (helloFlags and HelloFlags.PAIRING != 0) {
            helloFlags = HelloFlags.NONE                       // from now on this is a trusted device
            val keyCopy = key.copyOf()
            emit { it.onPaired(keyCopy) }
        }
        setState(ConnectionState.CONNECTED)
        sendInput()                                            // announce the current state straight away
    }

    private fun onReject(buf: ByteArray, length: Int) {
        if (currentState != ConnectionState.CONNECTING && currentState != ConnectionState.RECONNECTING) return
        // Unauthenticated: a hint for the error message only. It changes no keys and does not end the attempt.
        PacketCodec.parseReject(buf, length)?.reason?.let { lastReject = it }
    }

    private fun onPong(buf: ByteArray, length: Int, id: Long) {
        if (currentState != ConnectionState.CONNECTED || id != sessionId) return
        if (!ManetteCrypto.verifyTag(sessionKey, buf, length, Protocol.SESSION_TAG_LENGTH)) return
        val pong = PacketCodec.parsePong(buf, length) ?: return
        val now = clock.nowMs()
        lastPongAt = now
        val rtt = ((now and 0xFFFFFFFFL) - pong.timestamp) and 0xFFFFFFFFL
        if (rtt <= 60_000L) {
            currentRtt = rtt.toInt()
            emit { it.onLatency(rtt.toInt()) }
        }
    }

    private fun onRumble(buf: ByteArray, length: Int, id: Long) {
        if (currentState != ConnectionState.CONNECTED || id != sessionId) return
        if (!ManetteCrypto.verifyTag(sessionKey, buf, length, Protocol.SESSION_TAG_LENGTH)) return
        val m = PacketCodec.parseRumble(buf, length) ?: return
        if (haveRumbleSeq && !SequenceNumber.isNewer(lastRumbleSeq, m.seq)) return
        haveRumbleSeq = true
        lastRumbleSeq = m.seq
        emit { it.onRumble(m.large, m.small) }
    }

    // ------------------------------------------------------------------ ending

    override fun disconnect() {
        scheduler.post(Runnable {
            synchronized(lock) { if (!closed) disconnectLocked() }
            flush()
        })
    }

    private fun disconnectLocked() {
        if (currentState == ConnectionState.CONNECTED) {
            val n = PacketCodec.writeBye(txBuf, sessionId, sessionKey)
            rawSend(n)
        }
        pendingNonce = null
        setState(ConnectionState.DISCONNECTED)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            disconnectLocked()
            closed = true
        }
        flush()
        scheduler.shutdown()
        socket.close()
        receiveThread?.let { if (it !== Thread.currentThread()) it.join(1_000) }
    }

    // ------------------------------------------------------------------ events

    private fun setState(newState: ConnectionState) {
        if (currentState == newState) return
        currentState = newState
        emit { it.onStateChanged(newState) }
    }

    /** Queue a listener callback; it runs in [flush], after the lock has been released. */
    private fun emit(event: (TransportListener) -> Unit) {
        pendingEvents.add(event)
    }

    private fun flush() {
        val batch: List<(TransportListener) -> Unit>
        synchronized(lock) {
            if (pendingEvents.isEmpty()) return
            batch = ArrayList(pendingEvents)
            pendingEvents.clear()
        }
        for (event in batch) for (l in listeners) {
            try {
                event(l)
            } catch (_: Throwable) {
                // A misbehaving listener must not break the transport.
            }
        }
    }
}
