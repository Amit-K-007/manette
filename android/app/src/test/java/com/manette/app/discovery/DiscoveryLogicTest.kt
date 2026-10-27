package com.manette.app.discovery

import com.manette.app.connect.HostPicker
import com.manette.app.transport.PcEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLogicTest {
    private fun txt(vararg kv: Pair<String, String>) = kv.associate { it.first to it.second.toByteArray() as ByteArray? }
    private fun pc(name: String, host: String, id: String? = null, pair: Boolean? = null) = PcEndpoint(name, host, 26760, id, pair)

    // ------------------------------------------------------------------ TXT

    @Test
    fun parsesTheTxtRecordTheCompanionPublishes() {
        val t = PcTxt.parse(txt("v" to "1", "id" to "a1b2c3d4", "pair" to "1"))!!
        assertEquals(1, t.version); assertEquals("a1b2c3d4", t.installId); assertEquals(true, t.pairingOpen)
        assertEquals(false, PcTxt.parse(txt("v" to "1", "pair" to "0"))!!.pairingOpen)
        assertNull(PcTxt.parse(txt("v" to "1", "pair" to "maybe"))!!.pairingOpen)
        assertNull("unknown extras are ignored, a bad id is dropped", PcTxt.parse(txt("v" to "1", "id" to "bad id", "x" to "y"))!!.installId)
    }

    @Test
    fun anythingWithoutAUsableVersionIsNotOurs() {
        assertNull(PcTxt.parse(emptyMap()))
        assertNull(PcTxt.parse(txt("id" to "a1b2c3d4")))
        assertNull(PcTxt.parse(txt("v" to "zero")))
        assertNull(PcTxt.parse(txt("v" to "0")))
        assertNull(PcTxt.parse(mapOf("v" to null)))
    }

    // ---------------------------------------------------------------- tracker

    @Test
    fun foundAndLostUpdateASortedList() {
        val t = DiscoveryTracker()
        t.onFound(pc("zeta", "10.0.0.2")); val list = t.onFound(pc("Alpha", "10.0.0.3"))
        assertEquals(listOf("Alpha", "zeta"), list.map { it.name })
        assertEquals(listOf("Alpha"), t.onLost("zeta").map { it.name })
        assertTrue(t.onLost("never-seen").size == 1)
    }

    @Test
    fun theSamePcSeenTwiceIsOneEntryAndAChangedFlagUpdatesIt() {
        val t = DiscoveryTracker()
        t.onFound(pc("PC (wifi)", "192.168.1.20", id = "a1b2c3d4", pair = false))
        val list = t.onFound(pc("PC (tether)", "192.168.137.1", id = "a1b2c3d4", pair = true))
        assertEquals(1, list.size)
        assertEquals("192.168.137.1", list[0].host)
        assertEquals(true, list[0].pairingOpen)
    }

    @Test
    fun aPcStaysListedUntilEveryNameForItIsLost() {
        val t = DiscoveryTracker()
        t.onFound(pc("one", "1.1.1.1", id = "same")); t.onFound(pc("two", "2.2.2.2", id = "same"))
        assertEquals(1, t.onLost("one").size)
        assertEquals(0, t.onLost("two").size)
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun itemsRunOneAtATimeInOrder() {
        val started = ArrayList<Int>()
        val dones = ArrayList<() -> Unit>()
        val q = OneAtATimeQueue<Int> { item, done -> started.add(item); dones.add(done) }
        q.enqueue(1); q.enqueue(2); q.enqueue(3)
        assertEquals(listOf(1), started)
        dones[0](); assertEquals(listOf(1, 2), started)
        dones[1](); assertEquals(listOf(1, 2, 3), started)
        dones[2]()
        q.enqueue(4); assertEquals(listOf(1, 2, 3, 4), started)
    }

    @Test
    fun aRepeatedDoneSignalCannotReleaseTheNextItemEarly() {
        val started = ArrayList<Int>()
        val dones = ArrayList<() -> Unit>()
        val q = OneAtATimeQueue<Int> { item, done -> started.add(item); dones.add(done) }
        q.enqueue(1); q.enqueue(2); q.enqueue(3)
        dones[0](); dones[0](); dones[0]()                   // item 1 reports done three times
        assertEquals("only item 2 may have started", listOf(1, 2), started)
    }

    @Test
    fun itemsThatFinishImmediatelyDoNotRecurseOrDeadlock() {
        var ran = 0
        val q = OneAtATimeQueue<Int> { _, done -> ran++; done() }
        repeat(5000) { q.enqueue(it) }
        assertEquals(5000, ran)
    }

    // ---------------------------------------------------------- address follow

    @Test
    fun aSavedPcIsFoundAgainAtItsNewAddress() {
        val pcs = listOf(pc("other", "10.0.0.9", id = "zzzz"), pc("mine", "192.168.1.88", id = "a1b2c3d4"))
        val moved = AddressFollower.newAddress("a1b2c3d4", "192.168.1.20", 26760, pcs)
        assertNotNull(moved); assertEquals("192.168.1.88", moved!!.host)
        assertNull("same address: nothing to do", AddressFollower.newAddress("a1b2c3d4", "192.168.1.88", 26760, pcs))
        assertNull("no saved id: never guess by name", AddressFollower.newAddress(null, "192.168.1.20", 26760, pcs))
        assertNull("unknown id", AddressFollower.newAddress("nope", "192.168.1.20", 26760, pcs))
    }

    @Test
    fun installIdIsLookedUpByAddress() {
        val pcs = listOf(pc("mine", "192.168.1.88", id = "a1b2c3d4"))
        assertEquals("a1b2c3d4", AddressFollower.installIdFor("192.168.1.88", 26760, pcs))
        assertNull(AddressFollower.installIdFor("192.168.1.99", 26760, pcs))
    }

    // ------------------------------------------------------------ host picker

    @Test
    fun hostPickerPrefersTheSameSubnetThenTheSame16ThenTheFirst() {
        val hosts = listOf("10.1.2.3", "192.168.1.20", "192.168.137.1")
        assertEquals("192.168.137.1", HostPicker.pick(hosts, listOf("192.168.137.50")))
        assertEquals("192.168.1.20", HostPicker.pick(hosts, listOf("10.9.9.9", "192.168.1.5")))
        assertEquals("same /16 beats nothing", "192.168.137.1", HostPicker.pick(listOf("10.1.2.3", "192.168.137.1"), listOf("192.168.5.5")))
        assertEquals("10.1.2.3", HostPicker.pick(hosts, emptyList()))
        assertEquals("10.1.2.3", HostPicker.pick(hosts, listOf("172.16.0.5")))
        assertEquals("junk entries are skipped", "192.168.1.20", HostPicker.pick(listOf("my-pc.local", "192.168.1.20"), listOf("192.168.1.5", "garbage")))
    }
}
