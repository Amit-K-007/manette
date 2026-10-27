package com.manette.app.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manette.app.discovery.DiscoveryTracker
import com.manette.app.discovery.NsdDiscovery
import com.manette.app.transport.DiscoveryListener
import com.manette.app.transport.PcEndpoint

/** Compose-observable list of PCs found on the network. Started and stopped with the activity. NOTE: not compiled. */
class DiscoveryState(context: Context) {
    private val provider = NsdDiscovery(context)
    private val tracker = DiscoveryTracker()
    private val main = Handler(Looper.getMainLooper())
    private var handle: AutoCloseable? = null

    var pcs by mutableStateOf<List<PcEndpoint>>(emptyList()); private set

    fun start() {
        if (handle != null) return
        handle = provider.startDiscovery(object : DiscoveryListener {
            override fun onFound(pc: PcEndpoint) { val l = tracker.onFound(pc); main.post { pcs = l } }
            override fun onLost(name: String) { val l = tracker.onLost(name); main.post { pcs = l } }
        })
    }

    fun stop() {
        handle?.close()
        handle = null
        tracker.clear()
        pcs = emptyList()
    }
}
