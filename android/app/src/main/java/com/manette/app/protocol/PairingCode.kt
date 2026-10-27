package com.manette.app.protocol

/** What a scanned QR code (a manette:// link) tells the phone. */
class PairingLink(
    val version: Int,
    val hosts: List<String>,
    val port: Int,
    val secret: ByteArray,
    val name: String,
    /** Identifies the PC installation (also in its mDNS record), so the phone can find it again after an IP change. */
    val installId: String? = null,
)

/** Pairing secret presentation formats (docs/protocol.md section 4.3). */
object PairingCode {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val LINK_PREFIX = "manette://pair?"

    /** RFC 4648 Base32, no padding. A 16-byte secret becomes 26 characters. */
    fun toBase32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Case-insensitive; dashes, spaces and '=' are ignored. Null if any other character appears. */
    fun fromBase32(text: String): ByteArray? {
        val out = ArrayList<Byte>(Protocol.PAIRING_SECRET_LENGTH)
        var buffer = 0
        var bits = 0
        for (raw in text) {
            if (raw == '-' || raw == ' ' || raw == '=') continue
            val v = ALPHABET.indexOf(raw.uppercaseChar())
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer ushr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    /** The typed fallback: Base32 in groups of four, like AAAQ-EAYE-.... */
    fun toTypedCode(secret: ByteArray): String = toBase32(secret).chunked(4).joinToString("-")

    /** Parses a typed code into the 16-byte secret; null unless exactly 16 bytes result. */
    fun parseTypedCode(text: String): ByteArray? =
        fromBase32(text)?.takeIf { it.size == Protocol.PAIRING_SECRET_LENGTH }

    /** Parses `manette://pair?v=1&h=a,b&p=26760&s=BASE32&n=Name`. Null if anything required is missing or invalid. */
    fun parseLink(text: String): PairingLink? {
        if (!text.startsWith(LINK_PREFIX, ignoreCase = true)) return null
        val query = HashMap<String, String>()
        for (part in text.substring(LINK_PREFIX.length).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            query[part.substring(0, eq)] = percentDecode(part.substring(eq + 1)) ?: return null
        }
        val version = query["v"]?.toIntOrNull() ?: return null
        val port = query["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val secret = query["s"]?.let { parseTypedCode(it) } ?: return null
        val hosts = query["h"]?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
        val installId = query["i"]?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all { c -> c.isLetterOrDigit() } }
        return PairingLink(version, hosts, port, secret, query["n"] ?: "", installId)
    }

    /** Exact percent-decoding (no '+' to space), UTF-8. Null on a malformed escape. */
    private fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null   // needs two hex digits after '%'
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
