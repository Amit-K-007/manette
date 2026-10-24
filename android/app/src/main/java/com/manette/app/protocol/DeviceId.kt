package com.manette.app.protocol

import java.security.SecureRandom

/** The 16-byte random identifier this phone generates once and keeps forever. Value semantics, immutable. */
class DeviceId(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == Protocol.DEVICE_ID_LENGTH) { "DeviceId must be ${Protocol.DEVICE_ID_LENGTH} bytes" }
    }

    fun toByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is DeviceId && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = Hex.encode(value)

    companion object {
        fun random(random: SecureRandom = SecureRandom()): DeviceId =
            DeviceId(ByteArray(Protocol.DEVICE_ID_LENGTH).also { random.nextBytes(it) })

        fun fromHex(hex: String): DeviceId = DeviceId(Hex.decode(hex))
    }
}
