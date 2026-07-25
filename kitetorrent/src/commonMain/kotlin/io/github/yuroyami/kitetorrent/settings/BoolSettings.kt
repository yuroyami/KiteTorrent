package io.github.yuroyami.kitetorrent.settings

/**
 * The boolean-typed session settings, the port of `settings_pack::bool_types`
 * (`settings_pack.hpp`) together with the `bool_settings` default-value table from
 * `settings_pack.cpp`.
 *
 * Each constant is a full 16-bit setting key (`bool_type_base | index`). The index
 * positions are kept byte-identical to libtorrent, **including every deprecated
 * slot** (e.g. the old disk-cache flags), because the C++ enum is positional and the
 * defaults table is indexed by that same position. Deprecated slots are not given a
 * named constant here, but they still occupy their index in
 * [BoolSettingDefaults.NAMES] / [BoolSettingDefaults.DEFAULTS].
 */
object BoolSetting {
    private const val B = SettingType.BOOL_TYPE_BASE

    /** Accept multiple connections from the same IP (not recommended). */
    const val ALLOW_MULTIPLE_CONNECTIONS_PER_IP = B + 0

    /** Send `have` messages even to peers that already have the piece. */
    const val SEND_REDUNDANT_HAVE = B + 2

    /** Use the DHT only as a fallback when all trackers fail. */
    const val USE_DHT_AS_FALLBACK = B + 4

    /** Ignore UPnP responses from devices not on our subnet. */
    const val UPNP_IGNORE_NONROUTERS = B + 5

    /** Restrict peers that fail hash checks to whole-piece downloads (parole). */
    const val USE_PAROLE_MODE = B + 6

    /** Prefer seeding torrents when assigning active slots. */
    const val AUTO_MANAGE_PREFER_SEEDS = B + 12

    /** Exempt torrents with no payload transfer from the active-seeds/downloads limits. */
    const val DONT_COUNT_SLOW_TORRENTS = B + 13

    /** Close connections where neither end has anything to gain. */
    const val CLOSE_REDUNDANT_CONNECTIONS = B + 14

    /** Pick partial pieces before rarer pieces. */
    const val PRIORITIZE_PARTIAL_PIECES = B + 15

    /** Drain estimated TCP/IP overhead from the rate limiters. */
    const val RATE_LIMIT_IP_OVERHEAD = B + 16

    /** Announce to all trackers in a tier in parallel. */
    const val ANNOUNCE_TO_ALL_TRACKERS = B + 17

    /** Announce to one tracker from every tier (uTorrent behaviour). */
    const val ANNOUNCE_TO_ALL_TIERS = B + 18

    /** Try UDP trackers before HTTP trackers for the same host. */
    const val PREFER_UDP_TRACKERS = B + 19

    /** Assume all downloaded data is correct (testing only). */
    const val DISABLE_HASH_CHECKS = B + 22

    /** Allow i2p torrents to also use non-anonymous peer sources. */
    const val ALLOW_I2P_MIXED = B + 23

    /** Pass `O_NOATIME` when opening files (Linux). */
    const val NO_ATIME_STORAGE = B + 27

    /** Start an auto-managed, paused torrent on an incoming connection. */
    const val INCOMING_STARTS_QUEUED_TORRENTS = B + 28

    /** Include redundant bytes in the `downloaded` figure reported to trackers. */
    const val REPORT_TRUE_DOWNLOADED = B + 29

    /** Only request a block twice once every remaining piece has a request. */
    const val STRICT_END_GAME_MODE = B + 30

    /** Enable outgoing uTP peer connections. */
    const val ENABLE_OUTGOING_UTP = B + 32

    /** Enable incoming uTP peer connections. */
    const val ENABLE_INCOMING_UTP = B + 33

    /** Enable outgoing TCP peer connections. */
    const val ENABLE_OUTGOING_TCP = B + 34

    /** Enable incoming TCP peer connections. */
    const val ENABLE_INCOMING_TCP = B + 35

    /** Skip full file checks when resume data is incomplete/missing. */
    const val NO_RECHECK_INCOMPLETE_RESUME = B + 37

    /** Hide the client's identity to some degree (generic UA, no IP to private trackers, …). */
    const val ANONYMOUS_MODE = B + 38

    /** Report web-seed downloads to the tracker and in stats. */
    const val REPORT_WEB_SEED_DOWNLOADS = B + 39

    /** Have seeding/finished torrents make outgoing connections. */
    const val SEEDING_OUTGOING_CONNECTIONS = B + 42

    /** Refuse to connect to peers on privileged ports (< 1024). */
    const val NO_CONNECT_PRIVILEGED_PORTS = B + 43

    /** Spread connection attempts over time when near the connection limit. */
    const val SMOOTH_CONNECTS = B + 44

    /** Send the user-agent on every web-seed request, not just the first. */
    const val ALWAYS_SEND_USER_AGENT = B + 45

    /** Apply the IP filter to trackers as well as peers. */
    const val APPLY_IP_FILTER_TO_TRACKERS = B + 46

    /** Ban web seeds that send bad data. */
    const val BAN_WEB_SEEDS = B + 50

    /** Advertise share-mode support to peers. */
    const val SUPPORT_SHARE_MODE = B + 53

    /** Send the number of redundant bytes to the tracker. */
    const val REPORT_REDUNDANT_BYTES = B + 55

    /** Fall back to an OS-chosen listen port (bind to 0) on failure. */
    const val LISTEN_SYSTEM_PORT_FALLBACK = B + 56

    /** Include `&supportcrypt=1` in HTTP tracker announces. */
    const val ANNOUNCE_CRYPTO_SUPPORT = B + 58

    /** Start/stop the UPnP port-mapping service. */
    const val ENABLE_UPNP = B + 59

    /** Start/stop the NAT-PMP port-mapping service. */
    const val ENABLE_NATPMP = B + 60

    /** Start/stop Local Service Discovery. */
    const val ENABLE_LSD = B + 61

    /** Start/stop the DHT node. */
    const val ENABLE_DHT = B + 62

    /** Prefer RC4 when both encryption methods are offered. */
    const val PREFER_RC4 = B + 63

    /** Resolve hostnames through the configured proxy (SOCKS5/HTTP). */
    const val PROXY_HOSTNAMES = B + 64

    /** Route peer connections (incl. web seeds) through the proxy. */
    const val PROXY_PEER_CONNECTIONS = B + 65

    /** Download high-availability torrents sequentially. */
    const val AUTO_SEQUENTIAL = B + 66

    /** Route tracker connections through the proxy. */
    const val PROXY_TRACKER_CONNECTIONS = B + 67

    /** Run the internal IP-route-change notifier. */
    const val ENABLE_IP_NOTIFIER = B + 68

    /** Prefer BEP-42-verified node IDs in the DHT routing table. */
    const val DHT_PREFER_VERIFIED_NODE_IDS = B + 69

    /** Restrict DHT routing-table entries to one per close-CIDR IP. */
    const val DHT_RESTRICT_ROUTING_IPS = B + 70

    /** Prevent DHT searches from adding very-close-CIDR IPs. */
    const val DHT_RESTRICT_SEARCH_IPS = B + 71

    /** Make the first DHT buckets larger (128/64/32/16). */
    const val DHT_EXTENDED_ROUTING_TABLE = B + 72

    /** Keep `branch_factor` outstanding requests to the closest nodes. */
    const val DHT_AGGRESSIVE_LOOKUPS = B + 73

    /** Perform privacy-preserving (more expensive) DHT lookups. */
    const val DHT_PRIVACY_LOOKUPS = B + 74

    /** Reject DHT queries from nodes whose IDs aren't derived from their IP. */
    const val DHT_ENFORCE_NODE_ID = B + 75

    /** Ignore DHT traffic from parts of the internet we wouldn't expect. */
    const val DHT_IGNORE_DARK_INTERNET = B + 76

    /** Run the DHT in read-only mode (ephemeral/low-power devices). */
    const val DHT_READ_ONLY = B + 77

    /** Create an affinity for downloading 4 MiB extents of adjacent pieces. */
    const val PIECE_EXTENT_AFFINITY = B + 78

    /** Validate HTTPS tracker/web-seed certificates against the system store. */
    const val VALIDATE_HTTPS_TRACKERS = B + 79

    /** Apply SSRF mitigations to tracker and web-seed requests. */
    const val SSRF_MITIGATION = B + 80

    /** Allow IDNA (internationalised) hostnames for trackers/web seeds. */
    const val ALLOW_IDNA = B + 81

    /** Use `SetFileValidData()` to pre-allocate disk space (Windows, admin only). */
    const val ENABLE_SET_FILE_VALID_DATA = B + 82

    /** Include the local endpoint in the SOCKS5 `UDP ASSOCIATE` command. */
    const val SOCKS5_UDP_SEND_LOCAL_EP = B + 83

    /** Send the target hostname in the HTTP-proxy `CONNECT` command. */
    const val PROXY_SEND_HOST_IN_CONNECT = B + 84

    /** Set the no-copy-on-write flag (`FS_NOCOW_FL`) on downloaded files (Linux). */
    const val DISK_DISABLE_COPY_ON_WRITE = B + 85

    /** Accept multiple connections from the same peer-id. */
    const val ALLOW_MULTIPLE_CONNECTIONS_PER_PID = B + 86
}

/**
 * The serialisation names and default values for the boolean settings, indexed by
 * the 14-bit index (`key and SettingType.INDEX_MASK`). Mirrors the `bool_settings`
 * array in `settings_pack.cpp` one-to-one, deprecated slots included.
 */
object BoolSettingDefaults {
    /**
     * Names used for bencoded serialisation, in index order. Deprecated slots keep an
     * empty name (matching `DEPRECATED_SET` under the default ABI), so they are never
     * looked up by name but still hold their index.
     */
    val NAMES: List<String> = listOf(
        "allow_multiple_connections_per_ip", // 0
        "",                                  // 1  deprecated ignore_limits_on_local_network
        "send_redundant_have",               // 2
        "",                                  // 3  deprecated lazy_bitfields
        "use_dht_as_fallback",               // 4
        "upnp_ignore_nonrouters",            // 5
        "use_parole_mode",                   // 6
        "",                                  // 7  deprecated use_read_cache
        "",                                  // 8  deprecated use_write_cache
        "",                                  // 9  deprecated dont_flush_write_cache
        "",                                  // 10 deprecated coalesce_reads
        "",                                  // 11 deprecated coalesce_writes
        "auto_manage_prefer_seeds",          // 12
        "dont_count_slow_torrents",          // 13
        "close_redundant_connections",       // 14
        "prioritize_partial_pieces",         // 15
        "rate_limit_ip_overhead",            // 16
        "announce_to_all_trackers",          // 17
        "announce_to_all_tiers",             // 18
        "prefer_udp_trackers",               // 19
        "",                                  // 20 deprecated strict_super_seeding
        "",                                  // 21 deprecated lock_disk_cache
        "disable_hash_checks",               // 22
        "allow_i2p_mixed",                   // 23
        "",                                  // 24 deprecated low_prio_disk
        "",                                  // 25 deprecated volatile_read_cache
        "",                                  // 26 deprecated guided_read_cache
        "no_atime_storage",                  // 27
        "incoming_starts_queued_torrents",   // 28
        "report_true_downloaded",            // 29
        "strict_end_game_mode",              // 30
        "",                                  // 31 deprecated broadcast_lsd
        "enable_outgoing_utp",               // 32
        "enable_incoming_utp",               // 33
        "enable_outgoing_tcp",               // 34
        "enable_incoming_tcp",               // 35
        "",                                  // 36 deprecated ignore_resume_timestamps
        "no_recheck_incomplete_resume",      // 37
        "anonymous_mode",                    // 38
        "report_web_seed_downloads",         // 39
        "",                                  // 40 deprecated rate_limit_utp
        "",                                  // 41 deprecated announce_double_nat
        "seeding_outgoing_connections",      // 42
        "no_connect_privileged_ports",       // 43
        "smooth_connects",                   // 44
        "always_send_user_agent",            // 45
        "apply_ip_filter_to_trackers",       // 46
        "",                                  // 47 deprecated use_disk_read_ahead
        "",                                  // 48 deprecated lock_files
        "",                                  // 49 deprecated contiguous_recv_buffer
        "ban_web_seeds",                     // 50
        "",                                  // 51 deprecated allow_partial_disk_writes
        "",                                  // 52 deprecated force_proxy
        "support_share_mode",                // 53
        "",                                  // 54 deprecated support_merkle_torrents
        "report_redundant_bytes",            // 55
        "listen_system_port_fallback",       // 56
        "",                                  // 57 deprecated use_disk_cache_pool
        "announce_crypto_support",           // 58
        "enable_upnp",                       // 59
        "enable_natpmp",                     // 60
        "enable_lsd",                        // 61
        "enable_dht",                        // 62
        "prefer_rc4",                        // 63
        "proxy_hostnames",                   // 64
        "proxy_peer_connections",            // 65
        "auto_sequential",                   // 66
        "proxy_tracker_connections",         // 67
        "enable_ip_notifier",                // 68
        "dht_prefer_verified_node_ids",      // 69
        "dht_restrict_routing_ips",          // 70
        "dht_restrict_search_ips",           // 71
        "dht_extended_routing_table",        // 72
        "dht_aggressive_lookups",            // 73
        "dht_privacy_lookups",               // 74
        "dht_enforce_node_id",               // 75
        "dht_ignore_dark_internet",          // 76
        "dht_read_only",                     // 77
        "piece_extent_affinity",             // 78
        "validate_https_trackers",           // 79
        "ssrf_mitigation",                   // 80
        "allow_idna",                        // 81
        "enable_set_file_valid_data",        // 82
        "socks5_udp_send_local_ep",          // 83
        "proxy_send_host_in_connect",        // 84
        "disk_disable_copy_on_write",        // 85
        "allow_multiple_connections_per_pid",// 86
    )

    /**
     * Default values in index order, matching `bool_settings` in `settings_pack.cpp`.
     * Deprecated slots keep the historical default the C++ table records for them.
     */
    val DEFAULTS: BooleanArray = booleanArrayOf(
        false, // 0  allow_multiple_connections_per_ip
        true,  // 1  ignore_limits_on_local_network (deprecated)
        true,  // 2  send_redundant_have
        false, // 3  lazy_bitfields (deprecated)
        false, // 4  use_dht_as_fallback
        false, // 5  upnp_ignore_nonrouters
        true,  // 6  use_parole_mode
        true,  // 7  use_read_cache (deprecated)
        true,  // 8  use_write_cache (deprecated)
        false, // 9  dont_flush_write_cache (deprecated)
        false, // 10 coalesce_reads (deprecated)
        false, // 11 coalesce_writes (deprecated)
        false, // 12 auto_manage_prefer_seeds
        true,  // 13 dont_count_slow_torrents
        true,  // 14 close_redundant_connections
        false, // 15 prioritize_partial_pieces
        true,  // 16 rate_limit_ip_overhead
        false, // 17 announce_to_all_trackers
        false, // 18 announce_to_all_tiers
        true,  // 19 prefer_udp_trackers
        false, // 20 strict_super_seeding (deprecated)
        false, // 21 lock_disk_cache (deprecated)
        false, // 22 disable_hash_checks
        false, // 23 allow_i2p_mixed
        true,  // 24 low_prio_disk (deprecated)
        false, // 25 volatile_read_cache (deprecated)
        false, // 26 guided_read_cache (deprecated)
        true,  // 27 no_atime_storage
        false, // 28 incoming_starts_queued_torrents
        false, // 29 report_true_downloaded
        true,  // 30 strict_end_game_mode
        true,  // 31 broadcast_lsd (deprecated)
        true,  // 32 enable_outgoing_utp
        true,  // 33 enable_incoming_utp
        true,  // 34 enable_outgoing_tcp
        true,  // 35 enable_incoming_tcp
        false, // 36 ignore_resume_timestamps (deprecated)
        false, // 37 no_recheck_incomplete_resume
        false, // 38 anonymous_mode
        true,  // 39 report_web_seed_downloads
        true,  // 40 rate_limit_utp (deprecated)
        false, // 41 announce_double_nat (deprecated)
        true,  // 42 seeding_outgoing_connections
        false, // 43 no_connect_privileged_ports
        true,  // 44 smooth_connects
        false, // 45 always_send_user_agent
        true,  // 46 apply_ip_filter_to_trackers
        true,  // 47 use_disk_read_ahead (deprecated)
        false, // 48 lock_files (deprecated)
        true,  // 49 contiguous_recv_buffer (deprecated)
        true,  // 50 ban_web_seeds
        true,  // 51 allow_partial_disk_writes (deprecated)
        false, // 52 force_proxy (deprecated)
        true,  // 53 support_share_mode
        false, // 54 support_merkle_torrents (deprecated)
        true,  // 55 report_redundant_bytes
        true,  // 56 listen_system_port_fallback
        false, // 57 use_disk_cache_pool (deprecated)
        true,  // 58 announce_crypto_support
        true,  // 59 enable_upnp
        true,  // 60 enable_natpmp
        true,  // 61 enable_lsd
        true,  // 62 enable_dht
        false, // 63 prefer_rc4
        true,  // 64 proxy_hostnames
        true,  // 65 proxy_peer_connections
        true,  // 66 auto_sequential
        true,  // 67 proxy_tracker_connections
        true,  // 68 enable_ip_notifier
        true,  // 69 dht_prefer_verified_node_ids
        true,  // 70 dht_restrict_routing_ips
        true,  // 71 dht_restrict_search_ips
        true,  // 72 dht_extended_routing_table
        true,  // 73 dht_aggressive_lookups
        false, // 74 dht_privacy_lookups
        false, // 75 dht_enforce_node_id
        true,  // 76 dht_ignore_dark_internet
        false, // 77 dht_read_only
        false, // 78 piece_extent_affinity
        true,  // 79 validate_https_trackers
        true,  // 80 ssrf_mitigation
        false, // 81 allow_idna
        false, // 82 enable_set_file_valid_data
        false, // 83 socks5_udp_send_local_ep
        false, // 84 proxy_send_host_in_connect
        false, // 85 disk_disable_copy_on_write
        false, // 86 allow_multiple_connections_per_pid
    )

    /** Number of bool settings (= libtorrent's `num_bool_settings`). */
    val COUNT: Int get() = DEFAULTS.size
}
