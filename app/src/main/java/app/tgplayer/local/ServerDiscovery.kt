package app.tgplayer.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/** A file server announcing itself on the Wi-Fi. [address] is "ip" or "ip:port" when the port isn't the usual one. */
data class FoundServer(val name: String, val address: String, val protocol: String)

/**
 * Finds file servers that announce themselves over Bonjour/mDNS (NAS boxes, Macs, Linux with Avahi)
 * using Android's own NSD. Windows PCs don't announce SMB this way, so they still need typing.
 * [onChange] runs on the main thread. Call [stop] when done.
 */
class ServerDiscovery(context: Context, private val onChange: (List<FoundServer>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val found = LinkedHashMap<String, FoundServer>()
    // The pre-Android-14 resolver handles one service at a time.
    private val pending = ArrayDeque<Pair<NsdServiceInfo, String>>()
    private var resolving = false
    private var stopped = false

    fun start() {
        for ((type, protocol) in TYPES) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { enqueue(info, protocol) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { if (found.remove("$protocol/${info.serviceName}") != null && !stopped) onChange(found.values.toList()) }
                }

                override fun onDiscoveryStarted(type: String) {}
                override fun onDiscoveryStopped(type: String) {}
                override fun onStartDiscoveryFailed(type: String, code: Int) {}
                override fun onStopDiscoveryFailed(type: String, code: Int) {}
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { listeners += listener }
        }
    }

    fun stop() {
        stopped = true
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
    }

    private fun enqueue(info: NsdServiceInfo, protocol: String) {
        if (stopped || found.containsKey("$protocol/${info.serviceName}")) return
        pending.addLast(info to protocol)
        resolveNext()
    }

    @Suppress("DEPRECATION") // resolveService still works everywhere; its replacement needs Android 14
    private fun resolveNext() {
        if (resolving || stopped) return
        val (info, protocol) = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post {
                    ipv4(resolved)?.let { ip ->
                        val port = resolved.port.takeIf { it > 0 && it != DEFAULT_PORT[protocol] }
                        found["$protocol/${resolved.serviceName}"] = FoundServer(resolved.serviceName, if (port != null) "$ip:$port" else ip, protocol)
                        if (!stopped) onChange(found.values.toList())
                    }
                    resolving = false
                    resolveNext()
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                main.post {
                    resolving = false
                    resolveNext()
                }
            }
        })
    }

    /** IPv4 only: IPv6 link-local addresses need a scope id that doesn't survive into a drive address. */
    @Suppress("DEPRECATION")
    private fun ipv4(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
    }

    private companion object {
        val TYPES = listOf(
            "_smb._tcp." to NetworkDrive.SMB,
            "_sftp-ssh._tcp." to NetworkDrive.SFTP,
            "_ftp._tcp." to NetworkDrive.FTP,
        )
        val DEFAULT_PORT = mapOf(NetworkDrive.SMB to 445, NetworkDrive.SFTP to 22, NetworkDrive.FTP to 21)
    }
}
