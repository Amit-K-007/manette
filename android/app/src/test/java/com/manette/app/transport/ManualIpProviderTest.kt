package com.manette.app.transport

import com.manette.app.transport.udp.ManualIpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualIpProviderTest {
    @Test
    fun parsesAddressesWithAndWithoutPort() {
        assertEquals(PcEndpoint("192.168.1.20", "192.168.1.20", 26760), ManualIpProvider.parse("192.168.1.20"))
        assertEquals(PcEndpoint("10.0.0.5", "10.0.0.5", 5000), ManualIpProvider.parse("10.0.0.5:5000"))
        assertEquals("trimmed", "192.168.137.1", ManualIpProvider.parse("  192.168.137.1 ")!!.host)
        assertEquals("my-pc.local", ManualIpProvider.parse("my-pc.local")!!.host)
        assertEquals(1234, ManualIpProvider.parse("my-pc.local:1234")!!.port)
        assertEquals("custom default port", 9999, ManualIpProvider.parse("1.2.3.4", defaultPort = 9999)!!.port)
    }

    @Test
    fun rejectsThingsThatAreNotAddresses() {
        val bad = listOf(
            "", "   ", "256.1.1.1", "1.2.3", "1.2.3.4.5", "1..2.3", "host:0", "host:70000", "host:abc", "10.0.0.1:",
            "a b", "[::1]:1", "host:1:2", "http://x", "user@host", "-bad.local", "bad-.local", "x/y", ".local",
        )
        for (text in bad) assertNull("should reject '$text'", ManualIpProvider.parse(text))
    }

    @Test
    fun manualProviderHasNothingToDiscover() {
        val p = ManualIpProvider()
        var found = 0
        val handle = p.startDiscovery(object : DiscoveryListener {
            override fun onFound(pc: PcEndpoint) { found++ }
            override fun onLost(name: String) { found++ }
        })
        handle.close()
        assertEquals(0, found)
        assertNotNull(p.displayName)
    }
}
