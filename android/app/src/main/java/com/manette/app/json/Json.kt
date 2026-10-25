package com.manette.app.json

/** Dependency-free JSON reader/writer (presets are stored as JSON). Maps are LinkedHashMap, arrays List, numbers Long or Double. */
object Json {
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

    /** Writes Map/List/String/Number/Boolean/null as compact JSON. Non-finite numbers are rejected. */
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> appendString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v.toString())
            is Float -> { require(v.isFinite()) { "non-finite number" }; sb.append(v.toString()) }
            is Double -> { require(v.isFinite()) { "non-finite number" }; sb.append(v.toString()) }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendString(sb, k as String); sb.append(':'); append(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); append(sb, x) }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON type: ${v::class}")
        }
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
