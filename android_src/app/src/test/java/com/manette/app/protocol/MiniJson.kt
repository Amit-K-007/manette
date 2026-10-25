package com.manette.app.protocol

import java.io.File

/**
 * A tiny JSON reader for the shared test vectors. Android unit tests only get a stubbed org.json, and this
 * avoids adding a dependency just to read one file. Maps are LinkedHashMap, arrays are List, numbers are Long
 * or Double.
 */
object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { skipWs(); readValue().also { skipWs(); check(pos == text.length) { "trailing data" } } }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun readValue(): Any? {
            skipWs()
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else error("unexpected '$c' at $pos")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            check(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs(); check(s[pos] == ':') { "expected ':' at $pos" }; pos++
                map[key] = readValue()
                skipWs()
                when (s[pos++]) { ',' -> continue; '}' -> return map; else -> error("expected ',' or '}' at ${pos - 1}") }
            }
        }

        fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (s[pos] == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                when (s[pos++]) { ',' -> continue; ']' -> return list; else -> error("expected ',' or ']' at ${pos - 1}") }
            }
        }

        fun readString(): String {
            check(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> error("bad escape \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDouble()
        }
    }
}

/** Typed access to the parsed vectors. */
@Suppress("UNCHECKED_CAST")
class Node(private val v: Any?) {
    operator fun get(key: String): Node = Node((v as Map<String, Any?>).getValue(key))
    operator fun get(index: Int): Node = Node((v as List<Any?>)[index])
    fun has(key: String): Boolean = (v as Map<String, Any?>).containsKey(key)
    val items: List<Node> get() = (v as List<Any?>).map { Node(it) }
    val string: String get() = v as String
    val long: Long get() = v as Long
    val int: Int get() = (v as Long).toInt()
    val boolean: Boolean get() = v as Boolean
    val bytes: ByteArray get() = Hex.decode(string)
}

object Vectors {
    val root: Node by lazy { Node(MiniJson.parse(find().readText())) }

    /** Finds protocol/test-vectors.json by walking up from the working directory (Gradle runs tests in android/app). */
    private fun find(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/test-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("protocol/test-vectors.json not found above ${File("").absolutePath}")
    }

    val secret get() = root["fixed_inputs"]["pairing_secret"].bytes
    val deviceId get() = DeviceId(root["fixed_inputs"]["device_id"].bytes)
    val clientNonce get() = root["fixed_inputs"]["client_nonce"].bytes
    val serverNonce get() = root["fixed_inputs"]["server_nonce"].bytes
    val otherClientNonce get() = root["fixed_inputs"]["other_client_nonce"].bytes
    val sessionId: Long get() = root["fixed_inputs"]["session_id"].long
    val deviceKey get() = root["derived_keys"]["device_key"].bytes
    val sessionKey get() = root["derived_keys"]["session_key"].bytes
}
