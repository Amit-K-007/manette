package com.manette.app.discovery

import com.manette.app.transport.PcEndpoint

/** What a PC advertises in its mDNS TXT record: `v=1`, `id=<8 hex>`, `pair=0|1`. */
class PcTxt(val version: Int, val installId: String?, val pairingOpen: Boolean?) {
    companion object {
        /** Null if there is no usable `v`, i.e. this is not a Manette record. Unknown extra keys are ignored. */
        fun parse(attributes: Map<String, ByteArray?>): PcTxt? {
            fun text(key: String) = attributes[key]?.toString(Charsets.UTF_8)
            val version = text("v")?.toIntOrNull() ?: return null
            if (version < 1) return null
            val id = text("id")?.takeIf { it.isNotEmpty() && it.length <= 32 && it.all { c -> c.isLetterOrDigit() } }
            val pair = when (text("pair")) { "1" -> true; "0" -> false; else -> null }
            return PcTxt(version, id, pair)
        }
    }
}

/**
 * The live list of PCs found on the network. Discovery events arrive from several threads and may repeat (the same
 * PC on Wi-Fi and on a tether link), so entries are keyed by install id when known, else by service name.
 */
class DiscoveryTracker {
    private val found = LinkedHashMap<String, PcEndpoint>()
    private val nameToKey = HashMap<String, String>()

    @Synchronized fun onFound(pc: PcEndpoint): List<PcEndpoint> {
        val key = pc.installId ?: "name:${pc.name}"
        found[key] = pc
        nameToKey[pc.name] = key
        return snapshot()
    }

    @Synchronized fun onLost(name: String): List<PcEndpoint> {
        nameToKey.remove(name)?.let { key -> if (nameToKey.values.none { it == key }) found.remove(key) }
        return snapshot()
    }

    @Synchronized fun clear() { found.clear(); nameToKey.clear() }

    private fun snapshot() = found.values.sortedBy { it.name.lowercase() }
}

/** Android's resolver can only handle one request at a time; this runs queued items strictly one after another. */
class OneAtATimeQueue<T>(private val start: (T, () -> Unit) -> Unit) {
    private val pending = ArrayDeque<T>()
    private var busy = false
    private var generation = 0

    fun enqueue(item: T) {
        val next = synchronized(this) { pending.addLast(item); takeNext() } ?: return
        run(next.first, next.second)
    }

    private fun takeNext(): Pair<T, Int>? {
        if (busy || pending.isEmpty()) return null
        busy = true
        generation++
        return pending.removeFirst() to generation
    }

    private fun run(item: T, gen: Int) {
        start(item) {
            // Each item may signal "done" only once; a late or repeated signal must not release the next item early.
            val next = synchronized(this) { if (gen == generation && busy) { busy = false; takeNext() } else null }
            if (next != null) run(next.first, next.second)
        }
    }
}

/** Keeping a saved PC reachable when its IP address changes (DHCP), using the install id both sides know. */
object AddressFollower {
    /** If a discovered PC has the saved install id but a different address, returns it (else null). */
    fun newAddress(savedInstallId: String?, savedHost: String, savedPort: Int, pcs: List<PcEndpoint>): PcEndpoint? {
        if (savedInstallId == null) return null
        return pcs.firstOrNull { it.installId == savedInstallId && (it.host != savedHost || it.port != savedPort) }
    }

    /** The install id of the PC at host:port, if discovery has seen it. */
    fun installIdFor(host: String, port: Int, pcs: List<PcEndpoint>): String? =
        pcs.firstOrNull { it.host == host && it.port == port }?.installId
}
