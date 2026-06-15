package io.github.yuroyami.kitetorrent.settings

/**
 * The bit layout that distinguishes the three categories of session settings —
 * port of the `settings_pack::type_bases` enum in `settings_pack.hpp`.
 *
 * Setting names (indices) are 16-bit. The two most-significant bits encode the
 * value type (string / int / bool); the low 14 bits are the position within that
 * category's table. This is exactly the scheme libtorrent uses for serialising a
 * settings dictionary, so the numeric keys here are byte-compatible with the C++
 * library and with `setting_by_name` / `name_for_setting`.
 *
 * ```
 *  string keys: 0x0000 .. 0x3fff
 *  int    keys: 0x4000 .. 0x7fff
 *  bool   keys: 0x8000 .. 0xbfff
 * ```
 */
object SettingType {
    const val STRING_TYPE_BASE = 0x0000
    const val INT_TYPE_BASE = 0x4000
    const val BOOL_TYPE_BASE = 0x8000

    /** Mask selecting the two type bits. */
    const val TYPE_MASK = 0xc000

    /** Mask selecting the 14-bit index within a category. */
    const val INDEX_MASK = 0x3fff

    /** True if [name] is a string-typed setting key. */
    fun isString(name: Int): Boolean = (name and TYPE_MASK) == STRING_TYPE_BASE

    /** True if [name] is an int-typed setting key. */
    fun isInt(name: Int): Boolean = (name and TYPE_MASK) == INT_TYPE_BASE

    /** True if [name] is a bool-typed setting key. */
    fun isBool(name: Int): Boolean = (name and TYPE_MASK) == BOOL_TYPE_BASE

    /** The position of [name] within its category's table. */
    fun index(name: Int): Int = name and INDEX_MASK
}

/**
 * Enumerated values for the integer settings that select an algorithm or mode —
 * port of the small `enum`s nested in `settings_pack` (`settings_pack.hpp`). These
 * are plain `Int` constants because the settings store keeps everything as `Int`;
 * the values match libtorrent's enumerators exactly (note the deliberate gaps, e.g.
 * [ChokingAlgorithm.RATE_BASED_CHOKER] is 2, not 1).
 */
object SuggestMode {
    /** `suggest_mode_t::no_piece_suggestions` — never send suggest messages. */
    const val NO_PIECE_SUGGESTIONS = 0

    /** `suggest_mode_t::suggest_read_cache` — suggest the most recent read-cache pieces. */
    const val SUGGEST_READ_CACHE = 1
}

/** Port of `settings_pack::choking_algorithm_t` (for [Settings.CHOKING_ALGORITHM]). */
object ChokingAlgorithm {
    /** Fixed number of unchoke slots, as set by `unchoke_slots_limit`. */
    const val FIXED_SLOTS_CHOKER = 0

    /** Open unchoke slots based on achieved upload rate. */
    const val RATE_BASED_CHOKER = 2

    /** Deprecated BitTyrant choker (kept for value fidelity). */
    const val BITTYRANT_CHOKER = 3
}

/** Port of `settings_pack::seed_choking_algorithm_t` (for [Settings.SEED_CHOKING_ALGORITHM]). */
object SeedChokingAlgorithm {
    /** Round-robin the unchoked peers, distributing upload uniformly. */
    const val ROUND_ROBIN = 0

    /** Unchoke the peers we can send to fastest. */
    const val FASTEST_UPLOAD = 1

    /** Prioritise peers that just started or are about to finish. */
    const val ANTI_LEECH = 2
}

/**
 * Port of `settings_pack::io_buffer_mode_t` (for [Settings.DISK_IO_WRITE_MODE] /
 * [Settings.DISK_IO_READ_MODE]).
 */
object IoBufferMode {
    /** OS caches reads and writes normally. */
    const val ENABLE_OS_CACHE = 0

    /** No-cache mode: don't let file blocks linger in the OS cache. */
    const val DISABLE_OS_CACHE = 2

    /** Flush pieces to disk as they complete validation. */
    const val WRITE_THROUGH = 3
}

/** Port of `settings_pack::mmap_write_mode_t` (for [Settings.DISK_WRITE_MODE]). */
object MmapWriteMode {
    /** Always use normal `pwrite`, never mmap. */
    const val ALWAYS_PWRITE = 0

    /** Prefer memory-mapped writes (at least for large files). */
    const val ALWAYS_MMAP_WRITE = 1

    /** Decide between pwrite and mmap based on the backing storage. */
    const val AUTO_MMAP_WRITE = 2
}

/** Port of `settings_pack::bandwidth_mixed_algo_t` (for [Settings.MIXED_MODE_ALGORITHM]). */
object MixedModeAlgorithm {
    /** Disable the mixed-mode bandwidth balancing (favour TCP). */
    const val PREFER_TCP = 0

    /** Throttle TCP to the same proportion of throughput as there are TCP connections. */
    const val PEER_PROPORTIONAL = 1
}

/**
 * Port of `settings_pack::enc_policy` — the encryption policy for incoming /
 * outgoing connections ([Settings.OUT_ENC_POLICY] / [Settings.IN_ENC_POLICY]).
 */
object EncPolicy {
    /** Only encrypted connections are allowed. */
    const val PE_FORCED = 0

    /** Encryption preferred, but plaintext connections are accepted/attempted as fallback. */
    const val PE_ENABLED = 1

    /** Only non-encrypted connections are allowed. */
    const val PE_DISABLED = 2
}

/** Port of `settings_pack::enc_level` (for [Settings.ALLOWED_ENC_LEVEL]). Note: values start at 1. */
object EncLevel {
    /** Plain-text encryption only. */
    const val PE_PLAINTEXT = 1

    /** RC4 encryption only. */
    const val PE_RC4 = 2

    /** Allow both plaintext and RC4. */
    const val PE_BOTH = 3
}

/** Port of `settings_pack::proxy_type_t` (for [Settings.PROXY_TYPE]). */
object ProxyType {
    /** No proxy; all other proxy fields ignored. */
    const val NONE = 0

    /** SOCKS4 server (requires a username). */
    const val SOCKS4 = 1

    /** SOCKS5 server, no authentication. */
    const val SOCKS5 = 2

    /** SOCKS5 server with username/password authentication. */
    const val SOCKS5_PW = 3

    /** HTTP proxy, no authorization. */
    const val HTTP = 4

    /** HTTP proxy requiring username/password authorization. */
    const val HTTP_PW = 5

    /** Internal i2p SAM-bridge proxy. */
    const val I2P_PROXY = 6
}
