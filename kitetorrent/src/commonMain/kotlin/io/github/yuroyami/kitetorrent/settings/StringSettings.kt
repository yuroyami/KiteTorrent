package io.github.yuroyami.kitetorrent.settings

import io.github.yuroyami.kitetorrent.Version

/**
 * The string-typed session settings: port of `settings_pack::string_types`
 * (`settings_pack.hpp`) together with the `str_settings` default-value table from
 * `settings_pack.cpp`.
 *
 * Each constant is a full 16-bit setting key (`string_type_base | index`), so it can
 * be passed straight to [SettingsPack.getString] / [SettingsPack.setString]. The
 * index positions are kept byte-identical to libtorrent, **including the deprecated
 * `mmap_cache` slot**, so that the serialised names round-trip correctly.
 *
 * Defaults that are a C++ `nullptr` (no default) are represented as `null` in
 * [StringSettingDefaults.DEFAULTS]; libtorrent treats those as the empty string when
 * read.
 */
object StringSetting {
    private const val B = SettingType.STRING_TYPE_BASE

    /** Client identification sent to trackers and peers, e.g. `"libtorrent/2.0.12.0"`. */
    const val USER_AGENT = B + 0

    /** IP address passed to trackers as `&ip=`. Empty/unset omits the parameter. */
    const val ANNOUNCE_IP = B + 1

    /** Deprecated `mmap_cache` slot (kept to preserve indices). */
    const val DEPRECATED_MMAP_CACHE = B + 2

    /** Client name/version sent to peers in the BT handshake; falls back to [USER_AGENT] if empty. */
    const val HANDSHAKE_CLIENT_VERSION = B + 3

    /** Comma-separated IPs/interface names to bind outgoing TCP peer connections to. */
    const val OUTGOING_INTERFACES = B + 4

    /** Comma-separated (IP/device, port) listen endpoints for incoming uTP/TCP peers. */
    const val LISTEN_INTERFACES = B + 5

    /** Proxy hostname (see [Settings.PROXY_TYPE]). */
    const val PROXY_HOSTNAME = B + 6

    /** Proxy username. */
    const val PROXY_USERNAME = B + 7

    /** Proxy password. */
    const val PROXY_PASSWORD = B + 8

    /** i2p SAM bridge hostname (see [Settings.I2P_PORT]). */
    const val I2P_HOSTNAME = B + 9

    /** Peer-id fingerprint prefix; if >= 20 bytes it is truncated and used as the whole peer-id. */
    const val PEER_FINGERPRINT = B + 10

    /** Comma-separated host:port DHT bootstrap nodes. */
    const val DHT_BOOTSTRAP_NODES = B + 11

    /** Overrides the NAT-PMP gateway address. */
    const val NATPMP_GATEWAY = B + 12
}

/**
 * The serialisation names and default values for the string settings, indexed by the
 * 14-bit index (i.e. `key and SettingType.INDEX_MASK`). Mirrors the `str_settings`
 * array in `settings_pack.cpp` one-to-one.
 */
object StringSettingDefaults {
    /**
     * Names used for bencoded serialisation, in index order. Deprecated entries that
     * libtorrent compiles out keep an empty name (matching `DEPRECATED_SET_STR`).
     */
    val NAMES: List<String> = listOf(
        "user_agent",
        "announce_ip",
        "", // deprecated mmap_cache
        "handshake_client_version",
        "outgoing_interfaces",
        "listen_interfaces",
        "proxy_hostname",
        "proxy_username",
        "proxy_password",
        "i2p_hostname",
        "peer_fingerprint",
        "dht_bootstrap_nodes",
        "natpmp_gateway",
    )

    /**
     * Default values in index order. `null` means "no default" (libtorrent stores a
     * C++ `nullptr` and reports the empty string). [StringSetting.USER_AGENT] embeds
     * the tracked libtorrent version from [Version.LIBTORRENT_VERSION].
     */
    val DEFAULTS: List<String?> = listOf(
        "libtorrent/" + Version.LIBTORRENT_VERSION, // user_agent
        null,                                        // announce_ip
        null,                                        // deprecated mmap_cache
        null,                                        // handshake_client_version
        "",                                          // outgoing_interfaces
        "0.0.0.0:6881,[::]:6881",                    // listen_interfaces
        "",                                          // proxy_hostname
        "",                                          // proxy_username
        "",                                          // proxy_password
        "",                                          // i2p_hostname
        "-LT20C0-",                                  // peer_fingerprint (libtorrent 2.0, release C)
        "dht.libtorrent.org:25401",                  // dht_bootstrap_nodes
        "",                                          // natpmp_gateway
    )

    /** Number of string settings (= libtorrent's `num_string_settings`). */
    val COUNT: Int get() = NAMES.size
}
