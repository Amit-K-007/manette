package com.manette.app.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    private fun throwsAny(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("expected an exception")
    }

    @Test
    fun writesAndReadsBackNestedValues() {
        val v = linkedMapOf("a" to 1, "b" to listOf(1.5, "x", true, null), "c" to linkedMapOf("d" to -3L))
        val back = Json.parse(Json.write(v)) as Map<*, *>
        assertEquals(1L, back["a"])
        assertEquals(listOf(1.5, "x", true, null), back["b"])
        assertEquals(-3L, (back["c"] as Map<*, *>)["d"])
    }

    @Test
    fun stringsAreEscapedAndSurviveARoundTrip() {
        val s = "He said \"hi\"\\ back\nline\ttab\u0001 日本語 é"
        val json = Json.write(mapOf("s" to s))
        assertTrue("no raw newline in output", !json.contains('\n'))
        assertEquals(s, (Json.parse(json) as Map<*, *>)["s"])
    }

    @Test
    fun floatsKeepTheirShortForm() {
        assertEquals("{\"x\":0.86}", Json.write(mapOf("x" to 0.86f)))
    }

    @Test
    fun badInputIsRejected() {
        throwsAny { Json.write(Double.NaN) }
        throwsAny { Json.write(Float.POSITIVE_INFINITY) }
        throwsAny { Json.write(Object()) }
        for (bad in listOf("", "{", "[1,", "{\"a\"}", "nope", "{\"a\":1} x", "\"unterminated")) throwsAny { Json.parse(bad) }
    }
}
