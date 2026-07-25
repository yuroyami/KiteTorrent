package io.github.yuroyami.kitetorrent.session.net

/**
 * One local network interface address. This is the slice of libtorrent's `ip_interface`
 * (enum_net.hpp) a client actually uses: pick a bind address, learn the LAN address
 * for UPnP, skip loopback.
 */
data class LocalInterface(
    val name: String,
    val address: String,
    val isLoopback: Boolean,
    val isIpv4: Boolean,
)

/**
 * Enumerate local interface addresses: a port of `enum_net()` (enum_net.cpp). This is
 * an `expect`/`actual` declaration because listing interfaces is a per-OS syscall with
 * no portable KMP API (`NetworkInterface` on JVM/Android, `getifaddrs` on Apple).
 */
expect fun enumerateLocalInterfaces(): List<LocalInterface>

/** The non-loopback IPv4 addresses: the usual candidates to advertise/bind. */
fun routableIpv4Addresses(): List<String> =
    enumerateLocalInterfaces().filter { it.isIpv4 && !it.isLoopback }.map { it.address }
