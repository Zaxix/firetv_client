package com.auroratv.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.auroratv.core.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * Reaching a home-network server while a VPN app is on.
 *
 * Full-tunnel VPN apps (the kind people run on a Fire TV) send everything into the tunnel, including
 * traffic for the TV's own home network, so a server at 192.168.x.x stops answering. Android lets an
 * app open a connection on the physical network (Wi-Fi/Ethernet) instead, when the VPN permits it.
 * For a server address that is on that physical network's own subnet, connections go that way;
 * everything else (internet servers, a WireGuard/Tailscale route to a *different* home network)
 * still goes through the VPN as before. When the VPN doesn't permit it, nothing changes.
 */
object LanRoute {
    @Volatile private var connectivity: ConnectivityManager? = null
    /** Per destination: whether going round the VPN worked (checked once per network). */
    private val verdicts = ConcurrentHashMap<String, Boolean>()

    fun attach(context: Context) {
        connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /** True when a VPN is carrying this device's traffic. */
    fun vpnActive(): Boolean {
        val cm = connectivity ?: return false
        @Suppress("DEPRECATION")
        return runCatching { cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } }.getOrDefault(false)
    }

    /** The physical network to use for [dest], or null to connect normally. */
    fun networkFor(dest: InetSocketAddress, timeoutMs: Int): Network? {
        val cm = connectivity ?: return null
        val address = dest.address ?: return null
        if (!(address.isSiteLocalAddress || address.isLinkLocalAddress)) return null
        if (!vpnActive()) return null
        @Suppress("DEPRECATION")
        val physical = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                cm.getLinkProperties(n)?.linkAddresses?.any { la -> sameSubnet(la.address, address, la.prefixLength) } == true
        } ?: return null
        val key = "$physical/${address.hostAddress}:${dest.port}"
        val ok = verdicts.getOrPut(key) {
            // Once per network and server: can this app go round the VPN, and does the server answer there?
            runCatching {
                Socket().use { probe ->
                    physical.bindSocket(probe)
                    probe.connect(dest, timeoutMs.coerceIn(500, 2_000))
                }
            }.fold(
                { Log.i("LanRoute", "VPN on: reaching ${address.hostAddress} over the local network directly"); true },
                { Log.i("LanRoute", "VPN on: can't go round it for ${address.hostAddress} (${it.javaClass.simpleName}); using the VPN"); false },
            )
        }
        return if (ok) physical else null
    }

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address; val y = b.address
        if (x.size != y.size) return false
        var bits = prefix
        for (i in x.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    /** OkHttp creates unconnected sockets; this one picks its network when it learns where it's going. */
    val socketFactory: SocketFactory = object : SocketFactory() {
        private val plain = getDefault()
        override fun createSocket(): Socket = LanAwareSocket()
        override fun createSocket(host: String, port: Int): Socket = LanAwareSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = plain.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = LanAwareSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = plain.createSocket(address, port, localAddress, localPort)
    }

    private class LanAwareSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            (endpoint as? InetSocketAddress)?.let { dest ->
                runCatching { networkFor(dest, if (timeout > 0) timeout else 2_000)?.bindSocket(this) }
            }
            super.connect(endpoint, timeout)
        }
    }
}
