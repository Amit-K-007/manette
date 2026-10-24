package com.manette.app.protocol

import com.manette.app.core.GamepadState

class Header(val version: Int, val type: Int, val sessionId: Long)

class Welcome(
    val sessionId: Long,
    val flags: Int,
    /** XInput slot the PC reports, or null if it does not know yet. Informational only. */
    val slot: Int?,
    val rumbleHzMax: Int,
    val serverNonce: ByteArray,
)

class Reject(val reason: RejectReason?, val maxVersion: Int)
class Ping(val sessionId: Long, val timestamp: Long)
class Rumble(val sessionId: Long, val seq: Int, val large: Int, val small: Int)

/**
 * Byte-exact encoder/decoder for docs/protocol.md, mirroring PacketCodec.cs. All integers little-endian.
 *
 * Writers fill a caller-supplied buffer (so the send path can reuse one) and return the length written,
 * tag included. Parsers are STRUCTURAL ONLY: they check length, header and field ranges but never
 * authenticate; call [ManetteCrypto.verifyTag] before trusting a message. Parsers take an explicit
 * [length] because receive buffers are larger than the datagram they hold.
 */
object PacketCodec {
    // ---------------------------------------------------------------- header

    fun readHeader(p: ByteArray, length: Int = p.size): Header? {
        if (length < Protocol.HEADER_LENGTH || length > p.size) return null
        if (u8(p, 0) != Protocol.MAGIC0 || u8(p, 1) != Protocol.MAGIC1) return null
        return Header(u8(p, 2), u8(p, 3), u32(p, 4))
    }

    private fun writeHeader(dst: ByteArray, type: Int, sessionId: Long) {
        require(sessionId in 0..0xFFFFFFFFL) { "sessionId out of range" }
        dst[0] = Protocol.MAGIC0.toByte()
        dst[1] = Protocol.MAGIC1.toByte()
        dst[2] = Protocol.VERSION.toByte()
        dst[3] = type.toByte()
        putU32(dst, 4, sessionId)
    }

    private fun requireRoom(dst: ByteArray, needed: Int) =
        require(dst.size >= needed) { "buffer needs at least $needed bytes" }

    private fun sign(dst: ByteArray, bodyLength: Int, key: ByteArray, tagLength: Int, extra: ByteArray = ByteArray(0)) {
        val tag = ManetteCrypto.computeTag(key, dst, bodyLength, tagLength, extra)
        System.arraycopy(tag, 0, dst, bodyLength, tagLength)
    }

    // ----------------------------------------------------------------- HELLO

    fun writeHello(
        dst: ByteArray, flags: Int, deviceId: DeviceId, clientNonce: ByteArray, name: String, deviceKey: ByteArray,
    ): Int {
        require(clientNonce.size == Protocol.NONCE_LENGTH) { "client_nonce must be 8 bytes" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= Protocol.MAX_NAME_BYTES) { "name exceeds 32 UTF-8 bytes" }
        val body = 34 + nameBytes.size
        val total = body + Protocol.HANDSHAKE_TAG_LENGTH
        requireRoom(dst, total)

        writeHeader(dst, MessageType.HELLO, 0)
        dst[8] = flags.toByte()
        System.arraycopy(deviceId.toByteArray(), 0, dst, 9, Protocol.DEVICE_ID_LENGTH)
        System.arraycopy(clientNonce, 0, dst, 25, Protocol.NONCE_LENGTH)
        dst[33] = nameBytes.size.toByte()
        System.arraycopy(nameBytes, 0, dst, 34, nameBytes.size)
        sign(dst, body, deviceKey, Protocol.HANDSHAKE_TAG_LENGTH)
        return total
    }

    // --------------------------------------------------------------- WELCOME

    fun parseWelcome(p: ByteArray, length: Int = p.size): Welcome? {
        if (length != Protocol.WELCOME_LENGTH) return null
        val h = readHeader(p, length) ?: return null
        if (h.type != MessageType.WELCOME) return null
        val slot = u8(p, 9)
        return Welcome(
            sessionId = h.sessionId,
            flags = u8(p, 8),
            slot = if (slot == Protocol.NO_SLOT) null else slot,
            rumbleHzMax = u16(p, 10),
            serverNonce = p.copyOfRange(12, 12 + Protocol.NONCE_LENGTH),
        )
    }

    // ---------------------------------------------------------------- REJECT

    fun parseReject(p: ByteArray, length: Int = p.size): Reject? {
        if (length != Protocol.REJECT_LENGTH) return null
        val h = readHeader(p, length) ?: return null
        if (h.type != MessageType.REJECT) return null
        return Reject(RejectReason.fromCode(u8(p, 8)), u8(p, 9))
    }

    // ----------------------------------------------------------------- INPUT

    fun writeInput(dst: ByteArray, sessionId: Long, seq: Int, s: GamepadState, sessionKey: ByteArray): Int {
        require(seq in 0..0xFFFF) { "seq out of range" }
        requireRoom(dst, Protocol.INPUT_LENGTH)
        writeHeader(dst, MessageType.INPUT, sessionId)
        putU16(dst, 8, seq)
        putU16(dst, 10, s.buttons)
        dst[12] = s.leftTrigger.toByte()
        dst[13] = s.rightTrigger.toByte()
        putI16(dst, 14, s.leftX)
        putI16(dst, 16, s.leftY)
        putI16(dst, 18, s.rightX)
        putI16(dst, 20, s.rightY)
        sign(dst, 22, sessionKey, Protocol.SESSION_TAG_LENGTH)
        return Protocol.INPUT_LENGTH
    }

    // ------------------------------------------------------------ PING / PONG

    fun writePing(dst: ByteArray, sessionId: Long, timestamp: Long, sessionKey: ByteArray): Int {
        require(timestamp in 0..0xFFFFFFFFL) { "timestamp out of range" }
        requireRoom(dst, Protocol.PING_LENGTH)
        writeHeader(dst, MessageType.PING, sessionId)
        putU32(dst, 8, timestamp)
        sign(dst, 12, sessionKey, Protocol.SESSION_TAG_LENGTH)
        return Protocol.PING_LENGTH
    }

    fun parsePong(p: ByteArray, length: Int = p.size): Ping? {
        if (length != Protocol.PING_LENGTH) return null
        val h = readHeader(p, length) ?: return null
        if (h.type != MessageType.PONG) return null
        return Ping(h.sessionId, u32(p, 8))
    }

    // ---------------------------------------------------------------- RUMBLE

    fun parseRumble(p: ByteArray, length: Int = p.size): Rumble? {
        if (length != Protocol.RUMBLE_LENGTH) return null
        val h = readHeader(p, length) ?: return null
        if (h.type != MessageType.RUMBLE) return null
        return Rumble(h.sessionId, u16(p, 8), u8(p, 10), u8(p, 11))
    }

    // ------------------------------------------------------------------- BYE

    fun writeBye(dst: ByteArray, sessionId: Long, sessionKey: ByteArray): Int {
        requireRoom(dst, Protocol.BYE_LENGTH)
        writeHeader(dst, MessageType.BYE, sessionId)
        sign(dst, Protocol.HEADER_LENGTH, sessionKey, Protocol.SESSION_TAG_LENGTH)
        return Protocol.BYE_LENGTH
    }

    // ------------------------------------------------- little-endian helpers

    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong()) or (u8(b, o + 1).toLong() shl 8) or (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
    }

    /** Signed 16-bit: the low two bytes of the two's complement value, which is exactly what toByte() keeps. */
    private fun putI16(b: ByteArray, o: Int, v: Int) = putU16(b, o, v)

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = v.toByte()
        b[o + 1] = (v ushr 8).toByte()
        b[o + 2] = (v ushr 16).toByte()
        b[o + 3] = (v ushr 24).toByte()
    }
}
