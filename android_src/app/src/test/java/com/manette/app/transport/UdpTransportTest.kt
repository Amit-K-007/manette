package com.manette.app.transport

import com.manette.app.core.Buttons
import com.manette.app.core.GamepadState
import com.manette.app.protocol.Hex
import com.manette.app.protocol.ManetteCrypto
import com.manette.app.protocol.MessageType
import com.manette.app.protocol.RejectReason
import com.manette.app.protocol.Vectors
import com.manette.app.protocol.WelcomeFlags
import com.manette.app.transport.udp.UdpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Drives the real UdpTransport against a fake PC with manual time: no sockets, no sleeping, no flakiness. */
class UdpTransportTest {
    private fun a() = GamepadState(buttons = Buttons.A)
    private fun pressed(mask: Int) = GamepadState(buttons = mask)

    private fun connected(): Rig = Rig().also {
        it.pairAndConnect()
        assertEquals(ConnectionState.CONNECTED, it.transport.state)
    }

    private fun sentTypes(d: List<ByteArray>) = d.map { TestPcCodec.type(it) }

    // ------------------------------------------------------------ handshake

    @Test
    fun pairingHandshakeConnectsAndHandsBackTheDeviceKey() {
        val rig = Rig()
        rig.transport.pair(Rig.SECRET)
        assertEquals(ConnectionState.CONNECTING, rig.transport.state)
        rig.scheduler.runDue()

        val hello = TestPcCodec.parseHello(rig.socket.sent.single())!!
        assertEquals(1, hello.flags)                       // PAIRING flag
        assertEquals("Test phone", hello.name)
        assertEquals(Rig.DEVICE_ID, hello.deviceId)

        rig.pump()
        assertEquals(ConnectionState.CONNECTED, rig.transport.state)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), rig.events.states)
        assertEquals(0, rig.transport.slot)
        assertEquals(1, rig.events.paired.size)
        assertEquals(Hex.encode(rig.deviceKey()), Hex.encode(rig.events.paired.single()))
        assertEquals("the current state is announced on connect", 1, rig.pc.inputs.size)
    }

    @Test
    fun helloIsRetriedEvery500msWithAFreshNonceAndAStaleWelcomeIsIgnored() {
        val rig = Rig()
        rig.transport.pair(Rig.SECRET)
        rig.scheduler.runDue()
        val hello1 = rig.socket.drain().single()

        rig.clock.advance(499); rig.scheduler.fireTick()
        assertTrue("no retry before 500 ms", rig.socket.sent.isEmpty())
        rig.clock.advance(1); rig.scheduler.fireTick()
        val hello2 = rig.socket.drain().single()
        assertFalse("each HELLO uses a fresh client_nonce",
            TestPcCodec.parseHello(hello1)!!.nonce.contentEquals(TestPcCodec.parseHello(hello2)!!.nonce))

        // The PC answers BOTH; the first WELCOME arrives last, as it can on a real network.
        val welcome1 = rig.pc.handle(hello1).single()
        val welcome2 = rig.pc.handle(hello2).single()
        rig.transport.onDatagram(welcome1, welcome1.size)
        assertEquals("a WELCOME for an older nonce must not connect", ConnectionState.CONNECTING, rig.transport.state)
        rig.transport.onDatagram(welcome2, welcome2.size)
        assertEquals(ConnectionState.CONNECTED, rig.transport.state)

        // And the session key matches the PC's NEWEST session, so input is accepted.
        rig.transport.send(a()); rig.scheduler.runDue(); rig.pump()
        assertEquals(a(), rig.pc.inputs.last())
    }

    @Test
    fun aForgedWelcomeIsIgnored() {
        val rig = Rig()
        rig.transport.pair(Rig.SECRET)
        rig.scheduler.runDue()
        val hello = TestPcCodec.parseHello(rig.socket.drain().single())!!

        val forged = TestPcCodec.writeWelcome(7, 0, 0, 0, ByteArray(8), ByteArray(32) { 9 }, hello.nonce)
        rig.transport.onDatagram(forged, forged.size)

        assertEquals(ConnectionState.CONNECTING, rig.transport.state)
        assertTrue(rig.events.paired.isEmpty())
    }

    @Test
    fun aRejectIsOnlyAHintAndDoesNotStopTheRetries() {
        val rig = Rig()
        rig.pc.pairingOpen = false
        rig.transport.pair(Rig.SECRET)
        rig.scheduler.runDue(); rig.pump()
        assertEquals(ConnectionState.CONNECTING, rig.transport.state)

        repeat(11) { rig.tick(500) }                        // runs past the 5 s deadline

        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)
        assertEquals(listOf(RejectReason.PAIRING_NOT_OPEN), rig.events.rejected)
        assertTrue("kept retrying while waiting: ${rig.pc.helloCount} HELLOs", rig.pc.helloCount >= 5)
        assertTrue(rig.events.errors.isEmpty())
    }

    @Test
    fun noAnswerAtAllEndsWithAnErrorNotAReject() {
        val rig = Rig()
        rig.transport.pair(Rig.SECRET)
        rig.scheduler.runDue()
        repeat(11) { rig.tickSilently(500) }

        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)
        assertEquals(1, rig.events.errors.size)
        assertTrue(rig.events.rejected.isEmpty())
    }

    @Test
    fun aWrongPairingSecretNeverConnects() {
        val rig = Rig()
        rig.transport.pair(ByteArray(16) { 0x55 })           // phone scanned a different secret than the PC holds
        rig.scheduler.runDue()
        repeat(11) { rig.tick(500) }
        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)
        assertTrue(rig.events.paired.isEmpty())
        assertEquals("the PC stays silent on a bad tag", 0, rig.pc.inputs.size)
    }

    @Test
    fun pairOrConnectWhileBusyIsRefused() {
        val rig = Rig()
        rig.transport.pair(Rig.SECRET)
        try {
            rig.transport.pair(Rig.SECRET)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    // ---------------------------------------------------------------- sending

    @Test
    fun inputCarriesTheStateAndSequenceNumbersCount() {
        val rig = connected()
        rig.transport.send(a()); rig.scheduler.runDue(); rig.pump()
        rig.transport.send(pressed(Buttons.A or Buttons.B)); rig.scheduler.runDue(); rig.pump()

        assertEquals(listOf(GamepadState.NEUTRAL, a(), pressed(Buttons.A or Buttons.B)), rig.pc.inputs)
        assertEquals(listOf(1, 2, 3), rig.pc.inputSeqs)
    }

    @Test
    fun stateSetBeforeConnectingIsHeldAndSentOnConnect() {
        val rig = Rig()
        rig.transport.send(a())
        rig.scheduler.runDue()
        assertTrue("nothing is sent while disconnected", rig.socket.sent.isEmpty())

        rig.pairAndConnect()
        assertEquals(a(), rig.pc.inputs.first())
    }

    @Test
    fun aBurstOfTouchEventsIsCoalescedIntoOnePacketOfTheNewestState() {
        val rig = connected()
        rig.socket.drain()

        val masks = listOf(Buttons.A, Buttons.A or Buttons.B, Buttons.A or Buttons.B or Buttons.X, Buttons.A or Buttons.B or Buttons.X or Buttons.Y)
        masks.forEach { rig.transport.send(pressed(it)) }     // no releases among these
        rig.scheduler.runDue()

        assertEquals(1, rig.socket.sent.size)
        rig.pump()
        assertEquals(pressed(masks.last()), rig.pc.inputs.last())
    }

    @Test
    fun aReleaseIsRepeatedOnceAboutTwoMillisecondsLater() {
        val rig = connected()
        rig.transport.send(a()); rig.scheduler.runDue(); rig.pump()
        rig.socket.drain()

        rig.transport.send(GamepadState.NEUTRAL)
        rig.scheduler.runDue()
        assertEquals("immediate packet", 1, rig.socket.sent.size)
        rig.clock.advance(1); rig.scheduler.runDue()
        assertEquals("not yet", 1, rig.socket.sent.size)
        rig.clock.advance(1); rig.scheduler.runDue()
        assertEquals("the repeat", 2, rig.socket.sent.size)

        rig.pump()
        assertEquals(GamepadState.NEUTRAL, rig.pc.inputs.last())
    }

    @Test
    fun aPressIsNotRepeated() {
        val rig = connected()
        rig.socket.drain()
        rig.transport.send(a()); rig.scheduler.runDue()
        rig.clock.advance(10); rig.scheduler.runDue()
        assertEquals(1, rig.socket.sent.size)
    }

    @Test
    fun idleKeepaliveSendsTheFullStateEveryTick() {
        val rig = connected()
        rig.transport.send(a()); rig.scheduler.runDue(); rig.pump()
        val before = rig.pc.inputs.size

        repeat(5) { rig.tick() }

        assertEquals(before + 5, rig.pc.inputs.size)
        assertTrue(rig.pc.inputs.takeLast(5).all { it == a() })
    }

    @Test
    fun aFailingSocketNeverCrashesTheTransport() {
        val rig = connected()
        rig.socket.failSends = true
        rig.transport.send(a())
        rig.scheduler.runDue()
        rig.tickSilently()
        assertEquals(ConnectionState.CONNECTED, rig.transport.state)
    }

    // ------------------------------------------------------------ ping & watchdog

    @Test
    fun pingGoesOutEverySecondAndThePongGivesTheRoundTripTime() {
        val rig = connected()
        var rtt = -1
        for (i in 0 until 40) {
            rig.clock.advance(50); rig.scheduler.fireTick()
            val out = rig.socket.drain()
            val ping = out.any { TestPcCodec.type(it) == MessageType.PING }
            if (ping) rig.clock.advance(12)                  // the network takes 12 ms
            for (d in out) for (r in rig.pc.handle(d)) rig.transport.onDatagram(r, r.size)
            if (ping) { rtt = rig.transport.rttMs; break }
        }
        assertEquals(12, rtt)
        assertEquals(listOf(12), rig.events.latencies)
    }

    @Test
    fun threeSecondsWithoutAPongStartsReconnectingAndResumesTheSession() {
        val rig = connected()
        rig.transport.send(a()); rig.scheduler.runDue(); rig.pump()

        var guard = 0
        while (rig.transport.state == ConnectionState.CONNECTED && guard++ < 100) rig.tickSilently()
        assertEquals(ConnectionState.RECONNECTING, rig.transport.state)
        assertTrue("took about 3 s", guard in 55..65)

        // The network returns. The HELLO sent at the moment of loss was dropped, so the next retry (500 ms) gets through
        // and the PC resumes the session on the same pad.
        rig.tick(500)
        assertEquals(ConnectionState.CONNECTED, rig.transport.state)
        assertEquals("a trusted HELLO, no pairing flag", 0, rig.pc.helloFlags.last() and 1)
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING, ConnectionState.CONNECTED),
            rig.events.states,
        )
        assertEquals("pairing is reported once", 1, rig.events.paired.size)

        rig.transport.send(pressed(Buttons.Y)); rig.scheduler.runDue(); rig.pump()
        assertEquals("input is signed with the NEW session key", pressed(Buttons.Y), rig.pc.inputs.last())
    }

    @Test
    fun reconnectingGivesUpAfterAMinute() {
        val rig = connected()
        var guard = 0
        while (rig.transport.state == ConnectionState.CONNECTED && guard++ < 100) rig.tickSilently()
        assertEquals(ConnectionState.RECONNECTING, rig.transport.state)

        repeat(125) { rig.tickSilently(500) }

        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)
        assertEquals(1, rig.events.errors.size)
    }

    @Test
    fun aForgedPongCannotKeepASessionAlive() {
        val rig = connected()
        var guard = 0
        while (rig.transport.state == ConnectionState.CONNECTED && guard++ < 100) {
            rig.tickSilently()
            val s = rig.pc.currentSession
            val forged = TestPcCodec.writePong(s.sessionId, rig.clock.now and 0xFFFFFFFFL, ByteArray(32) { 1 })
            rig.transport.onDatagram(forged, forged.size)
        }
        assertEquals(ConnectionState.RECONNECTING, rig.transport.state)
    }

    // ---------------------------------------------------------------- rumble

    @Test
    fun rumbleIsVerifiedOrderedAndResetsOnANewSession() {
        val rig = connected()
        val s = rig.pc.currentSession
        fun deliver(seq: Int, l: Int, sm: Int, key: ByteArray = s.key) {
            val p = TestPcCodec.writeRumble(s.sessionId, seq, l, sm, key)
            rig.transport.onDatagram(p, p.size)
        }

        deliver(1, 200, 50)
        deliver(1, 10, 10)                                   // duplicate sequence
        deliver(0, 11, 11)                                   // older
        deliver(2, 0, 0, key = ByteArray(32) { 3 })          // forged
        deliver(2, 0, 0)                                     // legitimate stop

        assertEquals(listOf(200 to 50, 0 to 0), rig.events.rumbles)
    }

    // ---------------------------------------------------------------- ending

    @Test
    fun disconnectSendsByeAndStopsTraffic() {
        val rig = connected()
        rig.transport.disconnect()
        rig.scheduler.runDue(); rig.pump()

        assertEquals(1, rig.pc.byeCount)
        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)

        rig.transport.send(a()); rig.scheduler.runDue(); rig.tick()
        assertTrue("silent after disconnect", rig.socket.sent.isEmpty())
    }

    @Test
    fun aPairedPhoneCanConnectAgainWithJustTheKey() {
        val rig = connected()
        val key = rig.events.paired.single()
        rig.transport.disconnect(); rig.scheduler.runDue(); rig.pump()

        rig.transport.connect(key)
        rig.scheduler.runDue(); rig.pump()

        assertEquals(ConnectionState.CONNECTED, rig.transport.state)
        assertEquals("a reconnect is not a new pairing", 1, rig.events.paired.size)
        assertEquals(0, rig.pc.helloFlags.last() and 1)
    }

    @Test
    fun anUnknownPhoneConnectingWithAKeyIsToldItIsNotPaired() {
        val rig = Rig()
        rig.transport.connect(ByteArray(32) { 4 })
        rig.scheduler.runDue()
        repeat(11) { rig.tick(500) }
        assertEquals(listOf(RejectReason.NOT_PAIRED), rig.events.rejected)
    }

    @Test
    fun closeReleasesEverythingAndIsIdempotent() {
        val rig = connected()
        rig.transport.close()
        rig.transport.close()
        assertTrue(rig.socket.closed)
        assertTrue(rig.scheduler.isShutdown)
        assertEquals(ConnectionState.DISCONNECTED, rig.transport.state)
        rig.transport.send(a())                              // ignored, no exception
        assertEquals(1, rig.pc.let { rig.socket.drain().count { TestPcCodec.type(it) == MessageType.BYE } })
    }

    @Test
    fun aThrowingListenerDoesNotBreakTheOthers() {
        val rig = Rig()
        rig.transport.removeListener(rig.events)
        rig.transport.addListener(object : TransportListener {
            override fun onStateChanged(state: ConnectionState) = error("boom")
        })
        rig.transport.addListener(rig.events)
        rig.pairAndConnect()
        assertEquals(ConnectionState.CONNECTED, rig.transport.state)
        assertEquals(ConnectionState.CONNECTED, rig.events.states.last())
    }

    // ----------------------------------------------------- fake PC fidelity

    @Test
    fun theFakePcEncodersMatchTheSharedVectors() {
        val v = Vectors
        val hs = v.root["handshake"]
        val welcome = TestPcCodec.writeWelcome(
            v.sessionId, WelcomeFlags.PAIRED_NOW, 0, 30, v.serverNonce, v.deviceKey, v.clientNonce)
        assertEquals(hs["welcome_paired_now"]["packet_hex"].string, Hex.encode(welcome))

        val resumed = TestPcCodec.writeWelcome(
            v.sessionId, WelcomeFlags.RESUMED, 2, 0, v.serverNonce, v.deviceKey, v.clientNonce)
        assertEquals(hs["welcome_resumed"]["packet_hex"].string, Hex.encode(resumed))

        assertEquals(hs["reject_server_full"]["packet_hex"].string, Hex.encode(TestPcCodec.writeReject(3, 1)))

        val sm = v.root["session_messages"]
        assertEquals(sm["pong"]["packet_hex"].string, Hex.encode(TestPcCodec.writePong(v.sessionId, 123456, v.sessionKey)))
        assertEquals(sm["rumble"]["packet_hex"].string, Hex.encode(TestPcCodec.writeRumble(v.sessionId, 7, 200, 50, v.sessionKey)))

        // And the fake PC's parsers read what the real codec writes.
        val hello = Hex.decode(hs["hello_pairing"]["packet_hex"].string)
        val parsed = TestPcCodec.parseHello(hello)
        assertNotNull(parsed)
        assertEquals("Pixel 7", parsed!!.name)
        assertNull(TestPcCodec.parseHello(hello.copyOf(hello.size - 1)))
        assertTrue(ManetteCrypto.verifyTag(v.deviceKey, hello, hello.size, 16))
    }
}
