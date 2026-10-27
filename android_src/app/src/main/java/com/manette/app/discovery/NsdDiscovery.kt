package com.manette.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.manette.app.transport.ConnectionProvider
import com.manette.app.transport.DiscoveryListener
import com.manette.app.transport.PcEndpoint
import java.net.Inet4Address

/**
 * Finds PCs advertising `_manette._udp` using Android's NsdManager (the system's mDNS), so no multicast lock or
 * extra permission is needed. Android resolves one service at a time, hence the queue.
 * NOTE: not compiled, and the PC's multicast mDNS answers have not yet been tested against a real phone.
 */
class NsdDiscovery(context: Context) : ConnectionProvider {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    override val id: String = "udp-nsd"
    override val displayName: String = "Find on this network"

    override fun startDiscovery(listener: DiscoveryListener): AutoCloseable {
        val queue = OneAtATimeQueue<NsdServiceInfo> { info, done -> resolve(info, listener, done) }
        val callback = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = queue.enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = listener.onLost(info.serviceName)
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        return AutoCloseable {
            try { nsd.stopServiceDiscovery(callback) } catch (_: IllegalArgumentException) { /* already stopped */ }
        }
    }

    @Suppress("DEPRECATION")   // resolveService is deprecated on Android 14+ but still works, and is the one call that exists on API 29+
    private fun resolve(info: NsdServiceInfo, listener: DiscoveryListener, done: () -> Unit) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                try {
                    val host = resolved.host as? Inet4Address
                    val txt = PcTxt.parse(resolved.attributes)
                    if (host != null && txt != null) {
                        listener.onFound(PcEndpoint(resolved.serviceName, host.hostAddress ?: return, resolved.port, txt.installId, txt.pairingOpen))
                    }
                } finally {
                    done()
                }
            }
        })
    }

    private companion object {
        const val SERVICE_TYPE = "_manette._udp"
    }
}
