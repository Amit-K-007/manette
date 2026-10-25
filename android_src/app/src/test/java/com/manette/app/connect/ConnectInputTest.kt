package com.manette.app.connect

import com.manette.app.protocol.PairingCode
import com.manette.app.protocol.RejectReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectInputTest {
    private val secret = ByteArray(16) { it.toByte() }
    private val typed = PairingCode.toTypedCode(secret)
    private val link = "manette://pair?v=1&h=192.168.1.20,192.168.137.1&p=26770&s=${PairingCode.toBase32(secret)}&n=PC"

    private fun ready(r: ConnectInput) = r as ConnectInput.Ready
    private fun invalid(r: ConnectInput) = r as ConnectInput.Invalid

    @Test
    fun typedCodePlusHostIsReady() {
        val r = ready(ConnectInputParser.parse("192.168.1.20", typed))
        assertEquals("192.168.1.20", r.host)
        assertEquals(26760, r.port)
        assertArrayEquals(secret, r.secret)
    }

    @Test
    fun codeIsForgivingAboutCaseDashesAndSpaces() {
        for (c in listOf(typed.lowercase(), typed.replace("-", ""), "  $typed  ")) {
            assertArrayEquals(c, secret, ready(ConnectInputParser.parse("10.0.0.2", c)).secret)
        }
    }

    @Test
    fun pastedLinkNeedsNoHostAndUsesItsFirstAddressAndPort() {
        val r = ready(ConnectInputParser.parse("", link))
        assertEquals("192.168.1.20", r.host)
        assertEquals(26770, r.port)
        assertArrayEquals(secret, r.secret)
    }

    @Test
    fun aTypedHostOverridesTheLinkAddressAndKeepsItsPortByDefault() {
        val r = ready(ConnectInputParser.parse("192.168.137.1", link))
        assertEquals("192.168.137.1", r.host)
        assertEquals(26770, r.port)
        assertEquals(5000, ready(ConnectInputParser.parse("192.168.137.1:5000", link)).port)
    }

    @Test
    fun badInputGetsAHelpfulMessageNotACrash() {
        assertTrue(invalid(ConnectInputParser.parse("192.168.1.20", "nonsense")).message.contains("pairing code"))
        assertTrue(invalid(ConnectInputParser.parse("192.168.1.20", "")).message.contains("pairing code"))
        assertTrue(invalid(ConnectInputParser.parse("", typed)).message.contains("IP address"))
        assertTrue(invalid(ConnectInputParser.parse("not an address", typed)).message.contains("address"))
        assertTrue(invalid(ConnectInputParser.parse("999.1.1.1", typed)).message.contains("address"))
    }

    @Test
    fun everyRejectReasonHasAMessage() {
        for (r in RejectReason.entries) assertTrue(RejectMessages.describe(r).length > 10)
    }
}
