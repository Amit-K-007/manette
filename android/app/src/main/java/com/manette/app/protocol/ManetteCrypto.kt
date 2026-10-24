package com.manette.app.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Key derivation and message authentication (docs/protocol.md section 4). JDK/Android crypto only. */
object ManetteCrypto {
    private const val HMAC = "HmacSHA256"
    private val EMPTY = ByteArray(0)

    // Mac instances are not thread-safe and are slow to create, so keep one per thread.
    private val macs = object : ThreadLocal<Mac>() {
        override fun initialValue(): Mac = Mac.getInstance(HMAC)
    }

    private fun mac(key: ByteArray): Mac {
        require(key.isNotEmpty()) { "empty HMAC key" }
        return macs.get()!!.also { it.init(SecretKeySpec(key, HMAC)) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = mac(key).doFinal(data)

    /** RFC 5869 HKDF with SHA-256 (Android has no built-in HKDF). */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length" }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var previous = EMPTY
        var written = 0
        var counter = 1
        while (written < length) {
            val m = mac(prk)
            m.update(previous)
            m.update(info)
            m.update(counter.toByte())
            previous = m.doFinal()
            val n = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, n)
            written += n
            counter++
        }
        return out
    }

    /** device_key = HKDF-SHA256(ikm = pairing_secret, salt = "manette-v1-pair", info = device_id). */
    fun deriveDeviceKey(pairingSecret: ByteArray, deviceId: DeviceId): ByteArray =
        hkdfSha256(pairingSecret, Protocol.PAIR_SALT.toByteArray(Charsets.US_ASCII), deviceId.toByteArray(), Protocol.KEY_LENGTH)

    /** session_key = HKDF-SHA256(ikm = device_key, salt = client_nonce || server_nonce, info = "manette-v1-session"). */
    fun deriveSessionKey(deviceKey: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        hkdfSha256(deviceKey, clientNonce + serverNonce, Protocol.SESSION_INFO.toByteArray(Charsets.US_ASCII), Protocol.KEY_LENGTH)

    /**
     * First [tagLength] bytes of HMAC-SHA256(key, data[0 until length] || extra).
     * [extra] is only used by WELCOME, where the HELLO's client_nonce is appended.
     */
    fun computeTag(key: ByteArray, data: ByteArray, length: Int, tagLength: Int, extra: ByteArray = EMPTY): ByteArray {
        require(tagLength in 1..32) { "bad tag length" }
        val m = mac(key)
        m.update(data, 0, length)
        if (extra.isNotEmpty()) m.update(extra)
        return m.doFinal().copyOf(tagLength)
    }

    /**
     * Verifies the trailing [tagLength] bytes of the first [length] bytes of [packet], in constant time.
     * Returns false (never throws) for malformed input.
     */
    fun verifyTag(
        key: ByteArray, packet: ByteArray, length: Int, tagLength: Int, extra: ByteArray = EMPTY,
    ): Boolean {
        if (tagLength !in 1..32 || length <= tagLength || length > packet.size || length > 512) return false
        val bodyLength = length - tagLength
        val expected = computeTag(key, packet, bodyLength, tagLength, extra)
        val actual = packet.copyOfRange(bodyLength, length)
        return MessageDigest.isEqual(expected, actual)
    }
}
