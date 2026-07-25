package io.github.yuroyami.kitetorrent.session.net

/**
 * Apple interface enumeration.
 *
 * Full enumeration needs `getifaddrs(3)` from `<ifaddrs.h>`, which Kotlin/Native's
 * stock `platform.posix` does not expose. Adding it requires a small cinterop
 * `.def` (a build-config change) that has not been made. Until then we return just the
 * loopback address. This is benign for the engine: iOS clients bind to `0.0.0.0` and do
 * not rely on interface enumeration (it is used by libtorrent mainly for desktop/server
 * multi-homing and UPnP LAN-address discovery).
 */
actual fun enumerateLocalInterfaces(): List<LocalInterface> =
    listOf(LocalInterface(name = "lo0", address = "127.0.0.1", isLoopback = true, isIpv4 = true))
