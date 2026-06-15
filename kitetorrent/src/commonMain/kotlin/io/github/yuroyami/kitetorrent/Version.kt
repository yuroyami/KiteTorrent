package io.github.yuroyami.kitetorrent

/**
 * KiteTorrent version info, tracking the libtorrent release it is ported from
 * (version.hpp). The protocol-level peer-id prefix follows libtorrent's
 * convention of "-LT<major><minor><tiny>-" style fingerprints.
 */
object Version {
    /** The upstream libtorrent release this port tracks. */
    const val LIBTORRENT_VERSION = "2.0.12.0"

    const val MAJOR = 0
    const val MINOR = 0
    const val TINY = 1

    /** KiteTorrent's own version string. */
    const val VERSION = "0.0.1"
}
