package io.github.yuroyami.kitetorrent.session.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** JVM/desktop interface enumeration via [NetworkInterface]. */
actual fun enumerateLocalInterfaces(): List<LocalInterface> {
    val out = ArrayList<LocalInterface>()
    val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
    for (iface in Collections.list(ifaces)) {
        for (addr in Collections.list(iface.inetAddresses)) {
            val host = addr.hostAddress ?: continue
            out.add(
                LocalInterface(
                    name = iface.name,
                    address = host.substringBefore('%'), // strip IPv6 scope id
                    isLoopback = addr.isLoopbackAddress,
                    isIpv4 = addr is Inet4Address,
                ),
            )
        }
    }
    return out
}
