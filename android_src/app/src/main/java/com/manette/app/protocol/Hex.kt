package com.manette.app.protocol

/** Tiny hex helpers (java.util.HexFormat needs Android 14, and minSdk is 29). */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray, length: Int = bytes.size): String {
        val sb = StringBuilder(length * 2)
        for (i in 0 until length) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "bad hex digit" }
            ((hi shl 4) or lo).toByte()
        }
    }
}
