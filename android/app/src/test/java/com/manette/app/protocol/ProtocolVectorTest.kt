package com.manette.app.protocol

import com.manette.app.core.GamepadState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Byte-exact checks of the Kotlin codec and crypto against protocol/test-vectors.json (shared with the C# side). */
class ProtocolVectorTest {
    private val v = Vectors

    private fun hex(b: ByteArray, len: Int = b.size) = Hex.encode(b, len)

    private fun state(s: Node) = GamepadState(
        buttons = s["buttons"].int, leftTrigger = s["lt"].int, rightTrigger = s["rt"].int,
        leftX = s["lx"].int, leftY = s["ly"].int, rightX = s["rx"].int, rightY = s["ry"].int,
    )

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val r = v.root["hkdf_rfc5869_case1"]
        val okm = ManetteCrypto.hkdfSha256(r["ikm"].bytes, r["salt"].bytes, r["info"].bytes, r["length"].int)
        assertEquals(r["okm"].string, hex(okm))
    }

    @Test
    fun derivedKeysMatchVectors() {
        val deviceKey = ManetteCrypto.deriveDeviceKey(v.secret, v.deviceId)
        assertEquals(hex(v.deviceKey), hex(deviceKey))
        val sessionKey = ManetteCrypto.deriveSessionKey(deviceKey, v.clientNonce, v.serverNonce)
        assertEquals(hex(v.sessionKey), hex(sessionKey))
    }

    @Test
    fun inputPacketsEncodeToVectors() {
        var count = 0
        for (p in v.root["input_packets"].items) {
            val buf = ByteArray(Protocol.INPUT_LENGTH)
            val n = PacketCodec.writeInput(buf, v.sessionId, p["seq"].int, state(p["state"]), v.sessionKey)
            assertEquals(Protocol.INPUT_LENGTH, n)
            assertEquals(p["name"].string, p["packet_hex"].string, hex(buf))
            assertTrue(ManetteCrypto.verifyTag(v.sessionKey, buf, n, Protocol.SESSION_TAG_LENGTH))
            count++
        }
        assertTrue("expected the full set of input vectors", count >= 25)
    }

    @Test
    fun helloEncodesToVectors() {
        val buf = ByteArray(Protocol.HELLO_MAX_LENGTH)
        val hs = v.root["handshake"]

        var n = PacketCodec.writeHello(buf, HelloFlags.PAIRING, v.deviceId, v.clientNonce, "Pixel 7", v.deviceKey)
        assertEquals(hs["hello_pairing"]["packet_hex"].string, hex(buf, n))

        n = PacketCodec.writeHello(buf, HelloFlags.NONE, v.deviceId, v.clientNonce, "", v.deviceKey)
        assertEquals(hs["hello_trusted_empty_name"]["packet_hex"].string, hex(buf, n))
        assertEquals(Protocol.HELLO_MIN_LENGTH, n)

        // A non-ASCII name is counted in UTF-8 bytes, not characters: 16 x 2-byte chars is exactly 32 bytes.
        n = PacketCodec.writeHello(buf, HelloFlags.NONE, v.deviceId, v.clientNonce, "é".repeat(16), v.deviceKey)
        assertEquals(Protocol.HELLO_MAX_LENGTH, n)
    }

    @Test
    fun welcomeParsesAndVerifiesAndIsBoundToItsHello() {
        val hs = v.root["handshake"]
        val paired = hs["welcome_paired_now"]["packet_hex"].string.let(Hex::decode)
        val w = PacketCodec.parseWelcome(paired)
        assertNotNull(w)
        assertEquals(v.sessionId, w!!.sessionId)
        assertEquals(WelcomeFlags.PAIRED_NOW, w.flags)
        assertEquals(0, w.slot)
        assertEquals(30, w.rumbleHzMax)
        assertEquals(hex(v.serverNonce), hex(w.serverNonce))

        val tl = Protocol.HANDSHAKE_TAG_LENGTH
        assertTrue(ManetteCrypto.verifyTag(v.deviceKey, paired, paired.size, tl, v.clientNonce))
        // The same bytes against a different HELLO's nonce, or no nonce at all, must fail (replay binding).
        assertFalse(ManetteCrypto.verifyTag(v.deviceKey, paired, paired.size, tl, v.otherClientNonce))
        assertFalse(ManetteCrypto.verifyTag(v.deviceKey, paired, paired.size, tl))

        val resumed = PacketCodec.parseWelcome(Hex.decode(hs["welcome_resumed"]["packet_hex"].string))!!
        assertEquals(WelcomeFlags.RESUMED, resumed.flags)
        assertEquals(2, resumed.slot)
    }

    @Test
    fun welcomeSlotFFMeansUnknown() {
        val p = Hex.decode(v.root["handshake"]["welcome_paired_now"]["packet_hex"].string)
        p[9] = 0xFF.toByte() // structural parse does not look at the tag
        assertNull(PacketCodec.parseWelcome(p)!!.slot)
    }

    @Test
    fun rejectParses() {
        val r = PacketCodec.parseReject(Hex.decode(v.root["handshake"]["reject_server_full"]["packet_hex"].string))
        assertNotNull(r)
        assertEquals(RejectReason.SERVER_FULL, r!!.reason)
        assertEquals(1, r.maxVersion)
    }

    @Test
    fun sessionMessagesMatchVectors() {
        val sm = v.root["session_messages"]
        val buf = ByteArray(Protocol.PING_LENGTH)

        var n = PacketCodec.writePing(buf, v.sessionId, 123456, v.sessionKey)
        assertEquals(sm["ping"]["packet_hex"].string, hex(buf, n))

        val pongBytes = Hex.decode(sm["pong"]["packet_hex"].string)
        val pong = PacketCodec.parsePong(pongBytes)!!
        assertEquals(123456L, pong.timestamp)
        assertEquals(v.sessionId, pong.sessionId)
        assertTrue(ManetteCrypto.verifyTag(v.sessionKey, pongBytes, pongBytes.size, Protocol.SESSION_TAG_LENGTH))
        assertNull("a PING is not a PONG", PacketCodec.parsePong(Hex.decode(sm["ping"]["packet_hex"].string)))

        val rumbleBytes = Hex.decode(sm["rumble"]["packet_hex"].string)
        val rumble = PacketCodec.parseRumble(rumbleBytes)!!
        assertEquals(7, rumble.seq)
        assertEquals(200, rumble.large)
        assertEquals(50, rumble.small)
        assertTrue(ManetteCrypto.verifyTag(v.sessionKey, rumbleBytes, rumbleBytes.size, Protocol.SESSION_TAG_LENGTH))

        val bye = ByteArray(Protocol.BYE_LENGTH)
        n = PacketCodec.writeBye(bye, v.sessionId, v.sessionKey)
        assertEquals(sm["bye"]["packet_hex"].string, hex(bye, n))
    }

    @Test
    fun everyTamperVectorFailsVerification() {
        val keys = mapOf("session_key" to v.sessionKey, "device_key" to v.deviceKey)
        var count = 0
        for (t in v.root["tamper_must_fail"].items) {
            val name = t["name"].string
            val packet = t["packet_hex"].string.let(Hex::decode)
            val extra = if (t.has("extra_hmac_input_hex")) Hex.decode(t["extra_hmac_input_hex"].string) else ByteArray(0)
            val ok = ManetteCrypto.verifyTag(keys.getValue(t["key"].string), packet, packet.size, t["tag_len"].int, extra)
            assertFalse("tamper vector should fail: $name", ok)
            assertFalse(name, t["expected_valid"].boolean)
            count++
        }
        assertTrue(count >= 8)
    }

    @Test
    fun verifyTagHonoursTheLengthNotTheBufferSize() {
        // A receive buffer is bigger than the datagram: bytes past [length] must not matter.
        val input = Hex.decode(v.root["input_packets"][1]["packet_hex"].string)
        val big = input.copyOf(256).also { it[100] = 0x77 }
        assertTrue(ManetteCrypto.verifyTag(v.sessionKey, big, input.size, Protocol.SESSION_TAG_LENGTH))
        assertFalse(ManetteCrypto.verifyTag(v.sessionKey, big, input.size - 1, Protocol.SESSION_TAG_LENGTH))
        assertFalse(ManetteCrypto.verifyTag(v.sessionKey, input, 300, Protocol.SESSION_TAG_LENGTH))
    }

    @Test
    fun sequenceComparisonMatchesVectors() {
        for (c in v.root["seq_is_newer"]["cases"].items) {
            val last = c["last"].int
            val next = c["new"].int
            assertEquals("isNewer($last, $next)", c["newer"].boolean, SequenceNumber.isNewer(last, next))
        }
    }

    @Test
    fun screenYInversionMatchesVectors() {
        for (c in v.root["screen_to_wire_y"]["cases"].items) {
            assertEquals(c["wire_y"].int, GamepadState.screenYToWire(c["screen_y"].int))
        }
        assertEquals(32767, GamepadState.screenYToWire(Int.MIN_VALUE)) // clamps instead of wrapping
        assertEquals(-32768, GamepadState.screenYToWire(Int.MAX_VALUE))
    }

    @Test
    fun pairingCodeFormatsMatchVectors() {
        val pc = v.root["pairing_code"]
        assertEquals(pc["base32_unpadded"].string, PairingCode.toBase32(v.secret))
        assertEquals(pc["typed_code"].string, PairingCode.toTypedCode(v.secret))

        val typed = pc["typed_code"].string
        for (variant in listOf(typed, typed.lowercase(), typed.replace("-", ""), " $typed ")) {
            assertArrayEquals(variant, v.secret, PairingCode.parseTypedCode(variant))
        }
        assertNull(PairingCode.parseTypedCode("AAAQ-EAYE-1111-OCAJ-BIFQ-YDIO-B4")) // '1' is not Base32
        assertNull(PairingCode.parseTypedCode("AAAQ-EAYE"))                         // too short
    }

    @Test
    fun pairingLinkParsesTheVectorQrUri() {
        val link = PairingCode.parseLink(v.root["pairing_code"]["qr_uri_example"].string)
        assertNotNull(link)
        assertEquals(1, link!!.version)
        assertEquals(26760, link.port)
        assertEquals("DESKTOP-GAMING", link.name)
        assertEquals(listOf("192.168.1.20", "192.168.137.1"), link.hosts)
        assertArrayEquals(v.secret, link.secret)

        val escaped = PairingCode.parseLink("manette://pair?v=1&h=10.0.0.2&p=1&s=${PairingCode.toBase32(v.secret)}&n=Gaming%20PC%20%C3%A9%2B")
        assertEquals("Gaming PC é+", escaped!!.name)

        assertNull(PairingCode.parseLink("https://example.com"))
        assertNull(PairingCode.parseLink("manette://pair?v=1&p=26760"))                       // no secret, no host
        assertNull(PairingCode.parseLink("manette://pair?v=1&h=a&p=70000&s=${PairingCode.toBase32(v.secret)}")) // bad port
        assertNull(PairingCode.parseLink("manette://pair?v=1&h=a&p=1&s=${PairingCode.toBase32(v.secret)}&n=%G1")) // bad escape
        assertNull(PairingCode.parseLink("manette://pair?v=1&h=a&p=1&s=${PairingCode.toBase32(v.secret)}&n=%4"))  // truncated escape
    }

    @Test
    fun parsersRejectMalformedInput() {
        val input = Hex.decode(v.root["input_packets"][0]["packet_hex"].string)
        val welcome = Hex.decode(v.root["handshake"]["welcome_paired_now"]["packet_hex"].string)

        assertNull(PacketCodec.parseWelcome(welcome.copyOf(35)))
        assertNull(PacketCodec.parseWelcome(welcome + byteArrayOf(0)))
        assertNull(PacketCodec.parseWelcome(input))                                  // wrong type
        assertNull(PacketCodec.readHeader(ByteArray(7)))
        assertNull(PacketCodec.readHeader(input.copyOf().also { it[0] = 0 }))        // bad magic
        assertNull(PacketCodec.readHeader(input, input.size + 1))                    // length beyond the buffer
        assertEquals(Protocol.VERSION, PacketCodec.readHeader(input)!!.version)

        val big = welcome.copyOf(200)                                                // parse honours [length]
        assertNotNull(PacketCodec.parseWelcome(big, Protocol.WELCOME_LENGTH))
        assertNull(PacketCodec.parseWelcome(big))
    }

    @Test
    fun writersRejectBadArguments() {
        val buf = ByteArray(128)
        val tooLong = "x".repeat(33)
        expectIllegalArgument { PacketCodec.writeHello(buf, 0, v.deviceId, v.clientNonce, tooLong, v.deviceKey) }
        expectIllegalArgument { PacketCodec.writeHello(buf, 0, v.deviceId, ByteArray(7), "", v.deviceKey) }
        expectIllegalArgument { PacketCodec.writeInput(ByteArray(10), 1, 0, GamepadState.NEUTRAL, v.sessionKey) }
        expectIllegalArgument { PacketCodec.writeInput(buf, 1, 65536, GamepadState.NEUTRAL, v.sessionKey) }
        expectIllegalArgument { PacketCodec.writeInput(buf, -1, 0, GamepadState.NEUTRAL, v.sessionKey) }
        expectIllegalArgument { PacketCodec.writePing(buf, 1, 1L shl 32, v.sessionKey) }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
