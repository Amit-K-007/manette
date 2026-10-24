package com.manette.app.protocol

/** Constants from docs/protocol.md. Keep this file in lockstep with the spec and with ProtocolConstants.cs. */
object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 26760

    const val MAGIC0 = 0x4D // 'M'
    const val MAGIC1 = 0x4E // 'N'

    const val HEADER_LENGTH = 8
    const val HANDSHAKE_TAG_LENGTH = 16
    const val SESSION_TAG_LENGTH = 8

    const val DEVICE_ID_LENGTH = 16
    const val NONCE_LENGTH = 8
    const val PAIRING_SECRET_LENGTH = 16
    const val KEY_LENGTH = 32
    const val MAX_NAME_BYTES = 32
    const val MAX_DATAGRAM_LENGTH = 128

    const val INPUT_LENGTH = 30
    const val PING_LENGTH = 20
    const val RUMBLE_LENGTH = 20
    const val BYE_LENGTH = 16
    const val WELCOME_LENGTH = 36
    const val REJECT_LENGTH = 10
    const val HELLO_MIN_LENGTH = 50
    const val HELLO_MAX_LENGTH = 82

    const val NO_SLOT = 0xFF

    /** Salt/info strings for HKDF. Both sides must use exactly these. */
    const val PAIR_SALT = "manette-v1-pair"
    const val SESSION_INFO = "manette-v1-session"
}

object MessageType {
    const val HELLO = 0x01
    const val WELCOME = 0x02
    const val REJECT = 0x03
    const val INPUT = 0x10
    const val PING = 0x12
    const val PONG = 0x13
    const val RUMBLE = 0x20
    const val BYE = 0x21
}

enum class RejectReason(val code: Int) {
    NOT_PAIRED(1),
    UNSUPPORTED_VERSION(2),
    SERVER_FULL(3),
    PAIRING_NOT_OPEN(4),
    RATE_LIMITED(5);

    companion object {
        fun fromCode(code: Int): RejectReason? = entries.firstOrNull { it.code == code }
    }
}

object HelloFlags {
    const val NONE = 0
    const val PAIRING = 1
}

object WelcomeFlags {
    const val RESUMED = 1
    const val PAIRED_NOW = 2
}
