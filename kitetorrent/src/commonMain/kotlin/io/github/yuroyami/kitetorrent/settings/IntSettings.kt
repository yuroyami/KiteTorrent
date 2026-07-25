package io.github.yuroyami.kitetorrent.settings

/**
 * The integer-typed session settings: a port of `settings_pack::int_types`
 * (`settings_pack.hpp`) together with the `int_settings` default-value table from
 * `settings_pack.cpp`.
 *
 * Each constant is a full 16-bit setting key (`int_type_base | index`). The index
 * positions are kept byte-identical to libtorrent, **including every deprecated slot**
 * (old disk-cache sizes, BitTyrant rates, half-open limit, …). Deprecated slots get
 * no named constant but still occupy their index in [IntSettingDefaults].
 *
 * Two platform-conditional defaults are resolved to their **non-Windows** values
 * (the portable default for KiteTorrent's multiplatform targets):
 *  - `disk_io_write_mode` -> [IoBufferMode.ENABLE_OS_CACHE] (Windows uses `write_through`)
 *  - `close_file_interval` -> 0 (Windows uses 240)
 */
object IntSetting {
    private const val B = SettingType.INT_TYPE_BASE

    /** Seconds before a tracker request is considered timed out (from sending). */
    const val TRACKER_COMPLETION_TIMEOUT = B + 0

    /** Seconds with no data from a tracker before it times out. */
    const val TRACKER_RECEIVE_TIMEOUT = B + 1

    /** Seconds to wait for a `stopped` message; 0 suppresses stop announces. */
    const val STOP_TRACKER_TIMEOUT = B + 2

    /** Max bytes (uncompressed) accepted in a tracker response. */
    const val TRACKER_MAXIMUM_RESPONSE_LENGTH = B + 3

    /** Seconds until a piece request times out with no response. */
    const val PIECE_TIMEOUT = B + 4

    /** Seconds one 16 KiB block is expected within before re-requesting elsewhere. */
    const val REQUEST_TIMEOUT = B + 5

    /** Target request-queue length expressed in seconds of transfer. */
    const val REQUEST_QUEUE_TIME = B + 6

    /** Max outstanding block requests a peer may queue in us. */
    const val MAX_ALLOWED_IN_REQUEST_QUEUE = B + 7

    /** Max outstanding requests we send to a peer (overrides [REQUEST_QUEUE_TIME]). */
    const val MAX_OUT_REQUEST_QUEUE = B + 8

    /** If a whole piece downloads within this many seconds, prefer whole-piece requests. */
    const val WHOLE_PIECES_THRESHOLD = B + 9

    /** Seconds of peer-connection inactivity before timing it out (protocol: 120). */
    const val PEER_TIMEOUT = B + 10

    /** Like [PEER_TIMEOUT] but for url-seeds (usually lower). */
    const val URLSEED_TIMEOUT = B + 11

    /** HTTP request pipelining depth for url/http seeds. */
    const val URLSEED_PIPELINE_SIZE = B + 12

    /** Default seconds before retrying an http-seed lacking a `retry-after` header. */
    const val URLSEED_WAIT_RETRY = B + 13

    /** Upper limit on simultaneously open files this session keeps. */
    const val FILE_POOL_SIZE = B + 14

    /** Max connection attempts to a peer before giving up. */
    const val MAX_FAILCOUNT = B + 15

    /** Seconds to wait before reconnecting (multiplied by failcount). */
    const val MIN_RECONNECT_TIME = B + 16

    /** Seconds after initiating a connection before it times out. */
    const val PEER_CONNECT_TIMEOUT = B + 17

    /** Connection attempts per second; 0 means no outgoing connections. */
    const val CONNECTION_SPEED = B + 18

    /** Seconds a mutually-uninterested peer stays before disconnect. */
    const val INACTIVITY_TIMEOUT = B + 19

    /** Seconds between choke/unchoke re-evaluation (protocol: 30). */
    const val UNCHOKE_INTERVAL = B + 20

    /** Seconds between optimistic unchokes. */
    const val OPTIMISTIC_UNCHOKE_INTERVAL = B + 21

    /** Peers requested from each tracker (`&num_want=`). */
    const val NUM_WANT = B + 22

    /** Pieces picked at random before switching to rarest-first. */
    const val INITIAL_PICKER_THRESHOLD = B + 23

    /** Number of allowed-fast pieces offered to fast-extension peers. */
    const val ALLOWED_FAST_SET_SIZE = B + 24

    /** Suggest-message mode; see [SuggestMode]. */
    const val SUGGEST_MODE = B + 25

    /** Max bytes queued for the disk-write thread before peers stop reading sockets. */
    const val MAX_QUEUED_DISK_BYTES = B + 26

    /** Seconds to wait for a handshake response. */
    const val HANDSHAKE_TIMEOUT = B + 27

    /** Minimum send-buffer target size in bytes (initial window). */
    const val SEND_BUFFER_LOW_WATERMARK = B + 28

    /** Upper bound on the send-buffer watermark in bytes. */
    const val SEND_BUFFER_WATERMARK = B + 29

    /** Percentage factor applied to upload rate to compute the send-buffer watermark. */
    const val SEND_BUFFER_WATERMARK_FACTOR = B + 30

    /** Downloading-unchoke algorithm; see [ChokingAlgorithm]. */
    const val CHOKING_ALGORITHM = B + 31

    /** Seeding-unchoke algorithm; see [SeedChokingAlgorithm]. */
    const val SEED_CHOKING_ALGORITHM = B + 32

    /** How files are opened in read-only vs read/write mode; see [IoBufferMode]. */
    const val DISK_IO_WRITE_MODE = B + 36

    /** Read-mode file open behaviour; see [IoBufferMode]. */
    const val DISK_IO_READ_MODE = B + 37

    /** First port for binding outgoing connections (0 = OS-chosen). */
    const val OUTGOING_PORT = B + 38

    /** Size of the outgoing-port range starting at [OUTGOING_PORT]. */
    const val NUM_OUTGOING_PORTS = B + 39

    /** DSCP field of the IP header for packets to peers (0x01 = Lower Effort). */
    const val PEER_DSCP = B + 40

    /** Upper limit on active downloading torrents (auto-managed); -1 = unlimited. */
    const val ACTIVE_DOWNLOADS = B + 41

    /** Upper limit on active seeding torrents (auto-managed); -1 = unlimited. */
    const val ACTIVE_SEEDS = B + 42

    /** Limit on simultaneously checking torrents. */
    const val ACTIVE_CHECKING = B + 43

    /** Max torrents announced to the DHT. */
    const val ACTIVE_DHT_LIMIT = B + 44

    /** Max torrents announced to their trackers. */
    const val ACTIVE_TRACKER_LIMIT = B + 45

    /** Max torrents announced over local service discovery. */
    const val ACTIVE_LSD_LIMIT = B + 46

    /** Hard limit on active (auto-managed) torrents. */
    const val ACTIVE_LIMIT = B + 47

    /** Seconds between auto-manage queue updates/rotations. */
    const val AUTO_MANAGE_INTERVAL = B + 49

    /** Seconds of active seeding before the seed-time criterion is met. */
    const val SEED_TIME_LIMIT = B + 50

    /** Seconds between scrapes of queued torrents. */
    const val AUTO_SCRAPE_INTERVAL = B + 51

    /** Minimum seconds between any automatic scrape. */
    const val AUTO_SCRAPE_MIN_INTERVAL = B + 52

    /** Max known peers in the peer list (0 = unlimited). */
    const val MAX_PEERLIST_SIZE = B + 53

    /** Max peer-list size for paused torrents. */
    const val MAX_PAUSED_PEERLIST_SIZE = B + 54

    /** Minimum allowed tracker announce interval (sanity clamp), in seconds. */
    const val MIN_ANNOUNCE_INTERVAL = B + 55

    /** Seconds a freshly-started torrent stays considered active regardless of rate. */
    const val AUTO_MANAGE_STARTUP = B + 56

    /** Pieces to send a peer while seeding before rotating the unchoke set. */
    const val SEEDING_PIECE_QUOTA = B + 57

    /** Consecutive rejected requests from a choked peer before it's deemed abusive. */
    const val MAX_REJECTS = B + 58

    /** Socket receive-buffer size (`SO_RCVBUF`); 0 = OS default. */
    const val RECV_SOCKET_BUFFER_SIZE = B + 59

    /** Socket send-buffer size (`SO_SNDBUF`); 0 = OS default. */
    const val SEND_SOCKET_BUFFER_SIZE = B + 60

    /** Max bytes a single peer connection's receive buffer may grow to. */
    const val MAX_PEER_RECV_BUFFER_SIZE = B + 61

    /** Seconds from a disk write error until upload-only mode is retried. */
    const val OPTIMISTIC_DISK_RETRY = B + 65

    /** Max remembered suggested piece indices from a peer. */
    const val MAX_SUGGEST_PIECES = B + 66

    /** Seconds between local-network announces for a torrent. */
    const val LOCAL_SERVICE_ANNOUNCE_INTERVAL = B + 67

    /** Seconds between DHT announces. */
    const val DHT_ANNOUNCE_INTERVAL = B + 68

    /** Seconds UDP-tracker connection tokens are kept (protocol: 60). */
    const val UDP_TRACKER_TOKEN_EXPIRY = B + 69

    /** Number of optimistic-unchoke slots (0 = automatic, 20% of upload slots). */
    const val NUM_OPTIMISTIC_UNCHOKE_SLOTS = B + 71

    /** Max peers accepted from a single peer's PEX message. */
    const val MAX_PEX_PEERS = B + 75

    /** Milliseconds between internal ticks (bandwidth-quota distribution). */
    const val TICK_INTERVAL = B + 76

    /** Target share ratio for share-mode torrents (e.g. 3 = upload 3x download). */
    const val SHARE_MODE_TARGET = B + 77

    /** Session-global upload rate limit in bytes/s (0 = unlimited). */
    const val UPLOAD_RATE_LIMIT = B + 78

    /** Session-global download rate limit in bytes/s (0 = unlimited). */
    const val DOWNLOAD_RATE_LIMIT = B + 79

    /** Average bytes/s the DHT may send. */
    const val DHT_UPLOAD_RATE_LIMIT = B + 82

    /** Max unchoked peers in the session (-1 = unlimited). */
    const val UNCHOKE_SLOTS_LIMIT = B + 83

    /** Global limit on open connections (min 2 per torrent regardless). */
    const val CONNECTIONS_LIMIT = B + 85

    /** Incoming connections accepted above the limit to potentially replace existing ones. */
    const val CONNECTIONS_SLACK = B + 86

    /** Target uTP delay in milliseconds. */
    const val UTP_TARGET_DELAY = B + 87

    /** Max bytes the uTP congestion window can grow per RTT. */
    const val UTP_GAIN_FACTOR = B + 88

    /** Shortest allowed uTP socket timeout in milliseconds. */
    const val UTP_MIN_TIMEOUT = B + 89

    /** SYN packets sent (and timed out) before giving up a uTP connect. */
    const val UTP_SYN_RESENDS = B + 90

    /** FIN packets resent before giving up closing a uTP socket. */
    const val UTP_FIN_RESENDS = B + 91

    /** Times a uTP packet is resent before closing the connection. */
    const val UTP_NUM_RESENDS = B + 92

    /** Milliseconds timeout for the initial uTP SYN (doubles per timeout). */
    const val UTP_CONNECT_TIMEOUT = B + 93

    /** Percentage multiplier for cwnd on uTP packet loss (never > 100). */
    const val UTP_LOSS_MULTIPLIER = B + 95

    /** TCP-vs-uTP bandwidth balancing mode; see [MixedModeAlgorithm]. */
    const val MIXED_MODE_ALGORITHM = B + 96

    /** Backlog passed to `listen()` for the listen socket. */
    const val LISTEN_QUEUE_SIZE = B + 97

    /** Peers connected immediately on the first tracker response (<= 255). */
    const val TORRENT_CONNECT_BOOST = B + 98

    /** Max alerts queued internally before new alerts are dropped. */
    const val ALERT_QUEUE_SIZE = B + 99

    /** Max bytes accepted by the metadata (magnet) extension. */
    const val MAX_METADATA_SIZE = B + 100

    /** Disk I/O threads dedicated to piece-hash verification (full checks). */
    const val HASHING_THREADS = B + 101

    /** Outstanding 16 KiB blocks while checking torrents. */
    const val CHECKING_MEM_USAGE = B + 102

    /** Milliseconds in advance to announce pieces before completion (0 = off). */
    const val PREDICTIVE_PIECE_ANNOUNCE = B + 103

    /** Number of aio (disk) I/O threads. */
    const val AIO_THREADS = B + 104

    /** Aggressiveness of tracker retry back-off (the `x` in the back-off formula). */
    const val TRACKER_BACKOFF = B + 108

    /** Share-ratio (up/down) percentage at which a seed is considered done. */
    const val SHARE_RATIO_LIMIT = B + 109

    /** Seed-time-ratio (seed/leech seconds) percentage at which a seed is considered done. */
    const val SEED_TIME_RATIO_LIMIT = B + 110

    /** Percentage of peers to disconnect on each turnover interval. */
    const val PEER_TURNOVER = B + 111

    /** Connection-fill percentage above which turnover disconnects kick in. */
    const val PEER_TURNOVER_CUTOFF = B + 112

    /** Seconds between optimistic turnover disconnects. */
    const val PEER_TURNOVER_INTERVAL = B + 113

    /** Every n:th connection attempt is given to a finished torrent. */
    const val CONNECT_SEED_EVERY_N_DOWNLOAD = B + 114

    /** Max bytes for an HTTP response when announcing or fetching a `.torrent`. */
    const val MAX_HTTP_RECV_BUFFER_SIZE = B + 115

    /** Times to retry a failed port bind (incrementing the port). */
    const val MAX_RETRY_PORT_BIND = B + 116

    /** Bitmask of `alert_category_t` flags selecting which alerts to receive. */
    const val ALERT_MASK = B + 117

    /** Outgoing-connection encryption policy; see [EncPolicy]. */
    const val OUT_ENC_POLICY = B + 118

    /** Incoming-connection encryption policy; see [EncPolicy]. */
    const val IN_ENC_POLICY = B + 119

    /** Allowed encryption level; see [EncLevel]. */
    const val ALLOWED_ENC_LEVEL = B + 120

    /** Download-rate threshold (bytes/s) under which a torrent counts as inactive. */
    const val INACTIVE_DOWN_RATE = B + 121

    /** Upload-rate threshold (bytes/s) under which a torrent counts as inactive. */
    const val INACTIVE_UP_RATE = B + 122

    /** Proxy type; see [ProxyType]. */
    const val PROXY_TYPE = B + 123

    /** Proxy server port. */
    const val PROXY_PORT = B + 124

    /** i2p SAM bridge port. */
    const val I2P_PORT = B + 125

    /** Largest sequential web-seed request in bytes. */
    const val URLSEED_MAX_REQUEST_BYTES = B + 127

    /** Seconds before retrying a web-seed name lookup. */
    const val WEB_SEED_NAME_LOOKUP_RETRY = B + 128

    /** Seconds between closing the longest-open file (0 = disabled; portable default). */
    const val CLOSE_FILE_INTERVAL = B + 129

    /** Milliseconds to hold the uTP cwnd reduction after packet loss. */
    const val UTP_CWND_REDUCE_TIMER = B + 130

    /** Max web seeds connected per torrent. */
    const val MAX_WEB_SEED_CONNECTIONS = B + 131

    /** Seconds before a host-name resolver cache entry is considered stale. */
    const val RESOLVER_CACHE_TIMEOUT = B + 132

    /** Not-sent low watermark for socket send buffers (`TCP_NOTSENT_LOWAT`). */
    const val SEND_NOT_SENT_LOW_WATERMARK = B + 133

    /** Start threshold for the rate-based choker. */
    const val RATE_CHOKER_INITIAL_THRESHOLD = B + 134

    /** UPnP port-mapping lease duration in seconds (0 = permanent). */
    const val UPNP_LEASE_DURATION = B + 135

    /** Max concurrent HTTP tracker announces. */
    const val MAX_CONCURRENT_HTTP_ANNOUNCES = B + 136

    /** Max peers returned in a DHT `get_peers` reply. */
    const val DHT_MAX_PEERS_REPLY = B + 137

    /** DHT search branching factor (alpha in the Kademlia paper). */
    const val DHT_SEARCH_BRANCHING = B + 138

    /** Max failed contact attempts before a DHT node is removed. */
    const val DHT_MAX_FAIL_COUNT = B + 139

    /** Upper limit on torrents tracked from the DHT. */
    const val DHT_MAX_TORRENTS = B + 140

    /** Max items the DHT will store. */
    const val DHT_MAX_DHT_ITEMS = B + 141

    /** Max peers stored per torrent in the DHT. */
    const val DHT_MAX_PEERS = B + 142

    /** Max torrents returned in a DHT torrent-search reply. */
    const val DHT_MAX_TORRENT_SEARCH_REPLY = B + 143

    /** Seconds a DHT node is banned after exceeding the rate limit. */
    const val DHT_BLOCK_TIMEOUT = B + 144

    /** Max packets/s a DHT node may send without being banned. */
    const val DHT_BLOCK_RATELIMIT = B + 145

    /** Seconds an (im)mutable DHT item lives (0 = never expires). */
    const val DHT_ITEM_LIFETIME = B + 146

    /** Info-hashes sample recomputation interval, in seconds (0..21600). */
    const val DHT_SAMPLE_INFOHASHES_INTERVAL = B + 147

    /** Max elements in the sampled info-hashes subset. */
    const val DHT_MAX_INFOHASHES_SAMPLE_COUNT = B + 148

    /** Max pieces in metadata received via magnet links. */
    const val MAX_PIECE_COUNT = B + 149

    /** Max bencoded tokens parsed when receiving metadata from peers. */
    const val METADATA_TOKEN_LIMIT = B + 150

    /** mmap-vs-pwrite disk-write mode (mmap_disk_io only); see [MmapWriteMode]. */
    const val DISK_WRITE_MODE = B + 151

    /** Files smaller than this many 16 KiB blocks are not memory-mapped. */
    const val MMAP_FILE_SIZE_CUTOFF = B + 152

    /** i2p inbound tunnel quantity [1..16]. */
    const val I2P_INBOUND_QUANTITY = B + 153

    /** i2p outbound tunnel quantity [1..16]. */
    const val I2P_OUTBOUND_QUANTITY = B + 154

    /** i2p inbound tunnel hop count [0..7]. */
    const val I2P_INBOUND_LENGTH = B + 155

    /** i2p outbound tunnel hop count [0..7]. */
    const val I2P_OUTBOUND_LENGTH = B + 156

    /** `port` parameter sent to remote trackers (0 = use the listening port). */
    const val ANNOUNCE_PORT = B + 157

    /** Variance for i2p inbound tunnel length [-7..7]. */
    const val I2P_INBOUND_LENGTH_VARIANCE = B + 158

    /** Variance for i2p outbound tunnel length [-7..7]. */
    const val I2P_OUTBOUND_LENGTH_VARIANCE = B + 159

    /** NAT-PMP / PCP port-mapping lease duration in seconds. */
    const val NATPMP_LEASE_DURATION = B + 160
}

/**
 * The serialisation names and default values for the integer settings, indexed by the
 * 14-bit index (`key and SettingType.INDEX_MASK`). Mirrors the `int_settings` array in
 * `settings_pack.cpp` one-to-one, deprecated slots included.
 */
object IntSettingDefaults {
    /**
     * Names used for bencoded serialisation, in index order. Deprecated slots keep an
     * empty name (matching `DEPRECATED_SET` under the default ABI).
     */
    val NAMES: List<String> = listOf(
        "tracker_completion_timeout",     // 0
        "tracker_receive_timeout",        // 1
        "stop_tracker_timeout",           // 2
        "tracker_maximum_response_length",// 3
        "piece_timeout",                  // 4
        "request_timeout",                // 5
        "request_queue_time",             // 6
        "max_allowed_in_request_queue",   // 7
        "max_out_request_queue",          // 8
        "whole_pieces_threshold",         // 9
        "peer_timeout",                   // 10
        "urlseed_timeout",                // 11
        "urlseed_pipeline_size",          // 12
        "urlseed_wait_retry",             // 13
        "file_pool_size",                 // 14
        "max_failcount",                  // 15
        "min_reconnect_time",             // 16
        "peer_connect_timeout",           // 17
        "connection_speed",               // 18
        "inactivity_timeout",             // 19
        "unchoke_interval",               // 20
        "optimistic_unchoke_interval",    // 21
        "num_want",                       // 22
        "initial_picker_threshold",       // 23
        "allowed_fast_set_size",          // 24
        "suggest_mode",                   // 25
        "max_queued_disk_bytes",          // 26
        "handshake_timeout",              // 27
        "send_buffer_low_watermark",      // 28
        "send_buffer_watermark",          // 29
        "send_buffer_watermark_factor",   // 30
        "choking_algorithm",              // 31
        "seed_choking_algorithm",         // 32
        "",                               // 33 deprecated cache_size
        "",                               // 34 deprecated cache_buffer_chunk_size
        "",                               // 35 deprecated cache_expiry
        "disk_io_write_mode",             // 36
        "disk_io_read_mode",              // 37
        "outgoing_port",                  // 38
        "num_outgoing_ports",             // 39
        "peer_dscp",                      // 40
        "active_downloads",               // 41
        "active_seeds",                   // 42
        "active_checking",                // 43
        "active_dht_limit",               // 44
        "active_tracker_limit",           // 45
        "active_lsd_limit",               // 46
        "active_limit",                   // 47
        "",                               // 48 deprecated active_loaded_limit
        "auto_manage_interval",           // 49
        "seed_time_limit",                // 50
        "auto_scrape_interval",           // 51
        "auto_scrape_min_interval",       // 52
        "max_peerlist_size",              // 53
        "max_paused_peerlist_size",       // 54
        "min_announce_interval",          // 55
        "auto_manage_startup",            // 56
        "seeding_piece_quota",            // 57
        "max_rejects",                    // 58
        "recv_socket_buffer_size",        // 59
        "send_socket_buffer_size",        // 60
        "max_peer_recv_buffer_size",      // 61
        "",                               // 62 deprecated file_checks_delay_per_block
        "",                               // 63 deprecated read_cache_line_size
        "",                               // 64 deprecated write_cache_line_size
        "optimistic_disk_retry",          // 65
        "max_suggest_pieces",             // 66
        "local_service_announce_interval",// 67
        "dht_announce_interval",          // 68
        "udp_tracker_token_expiry",       // 69
        "",                               // 70 deprecated default_cache_min_age
        "num_optimistic_unchoke_slots",   // 71
        "",                               // 72 deprecated default_est_reciprocation_rate
        "",                               // 73 deprecated increase_est_reciprocation_rate
        "",                               // 74 deprecated decrease_est_reciprocation_rate
        "max_pex_peers",                  // 75
        "tick_interval",                  // 76
        "share_mode_target",              // 77
        "upload_rate_limit",              // 78
        "download_rate_limit",            // 79
        "",                               // 80 deprecated local_upload_rate_limit
        "",                               // 81 deprecated local_download_rate_limit
        "dht_upload_rate_limit",          // 82
        "unchoke_slots_limit",            // 83
        "",                               // 84 deprecated half_open_limit
        "connections_limit",              // 85
        "connections_slack",              // 86
        "utp_target_delay",               // 87
        "utp_gain_factor",                // 88
        "utp_min_timeout",                // 89
        "utp_syn_resends",                // 90
        "utp_fin_resends",                // 91
        "utp_num_resends",                // 92
        "utp_connect_timeout",            // 93
        "",                               // 94 deprecated utp_delayed_ack
        "utp_loss_multiplier",            // 95
        "mixed_mode_algorithm",           // 96
        "listen_queue_size",              // 97
        "torrent_connect_boost",          // 98
        "alert_queue_size",               // 99
        "max_metadata_size",              // 100
        "hashing_threads",                // 101
        "checking_mem_usage",             // 102
        "predictive_piece_announce",      // 103
        "aio_threads",                    // 104
        "",                               // 105 deprecated aio_max
        "",                               // 106 deprecated network_threads
        "",                               // 107 deprecated ssl_listen
        "tracker_backoff",                // 108
        "share_ratio_limit",              // 109
        "seed_time_ratio_limit",          // 110
        "peer_turnover",                  // 111
        "peer_turnover_cutoff",           // 112
        "peer_turnover_interval",         // 113
        "connect_seed_every_n_download",  // 114
        "max_http_recv_buffer_size",      // 115
        "max_retry_port_bind",            // 116
        "alert_mask",                     // 117
        "out_enc_policy",                 // 118
        "in_enc_policy",                  // 119
        "allowed_enc_level",              // 120
        "inactive_down_rate",             // 121
        "inactive_up_rate",               // 122
        "proxy_type",                     // 123
        "proxy_port",                     // 124
        "i2p_port",                       // 125
        "",                               // 126 deprecated cache_size_volatile
        "urlseed_max_request_bytes",      // 127
        "web_seed_name_lookup_retry",     // 128
        "close_file_interval",            // 129
        "utp_cwnd_reduce_timer",          // 130
        "max_web_seed_connections",       // 131
        "resolver_cache_timeout",         // 132
        "send_not_sent_low_watermark",    // 133
        "rate_choker_initial_threshold",  // 134
        "upnp_lease_duration",            // 135
        "max_concurrent_http_announces",  // 136
        "dht_max_peers_reply",            // 137
        "dht_search_branching",           // 138
        "dht_max_fail_count",             // 139
        "dht_max_torrents",               // 140
        "dht_max_dht_items",              // 141
        "dht_max_peers",                  // 142
        "dht_max_torrent_search_reply",   // 143
        "dht_block_timeout",              // 144
        "dht_block_ratelimit",            // 145
        "dht_item_lifetime",              // 146
        "dht_sample_infohashes_interval", // 147
        "dht_max_infohashes_sample_count",// 148
        "max_piece_count",                // 149
        "metadata_token_limit",           // 150
        "disk_write_mode",                // 151
        "mmap_file_size_cutoff",          // 152
        "i2p_inbound_quantity",           // 153
        "i2p_outbound_quantity",          // 154
        "i2p_inbound_length",             // 155
        "i2p_outbound_length",            // 156
        "announce_port",                  // 157
        "i2p_inbound_length_variance",    // 158
        "i2p_outbound_length_variance",   // 159
        "natpmp_lease_duration",          // 160
    )

    /**
     * Default values in index order, matching `int_settings` in `settings_pack.cpp`.
     * Platform-conditional defaults use the non-Windows (portable) value.
     */
    val DEFAULTS: IntArray = intArrayOf(
        30,             // 0   tracker_completion_timeout
        10,             // 1   tracker_receive_timeout
        5,              // 2   stop_tracker_timeout
        1024 * 1024,    // 3   tracker_maximum_response_length
        20,             // 4   piece_timeout
        60,             // 5   request_timeout
        3,              // 6   request_queue_time
        2000,           // 7   max_allowed_in_request_queue
        500,            // 8   max_out_request_queue
        20,             // 9   whole_pieces_threshold
        120,            // 10  peer_timeout
        20,             // 11  urlseed_timeout
        5,              // 12  urlseed_pipeline_size
        30,             // 13  urlseed_wait_retry
        40,             // 14  file_pool_size
        3,              // 15  max_failcount
        60,             // 16  min_reconnect_time
        15,             // 17  peer_connect_timeout
        30,             // 18  connection_speed
        600,            // 19  inactivity_timeout
        15,             // 20  unchoke_interval
        30,             // 21  optimistic_unchoke_interval
        200,            // 22  num_want
        4,              // 23  initial_picker_threshold
        5,              // 24  allowed_fast_set_size
        SuggestMode.NO_PIECE_SUGGESTIONS, // 25 suggest_mode
        1024 * 1024,    // 26  max_queued_disk_bytes
        10,             // 27  handshake_timeout
        10 * 1024,      // 28  send_buffer_low_watermark
        500 * 1024,     // 29  send_buffer_watermark
        50,             // 30  send_buffer_watermark_factor
        ChokingAlgorithm.FIXED_SLOTS_CHOKER, // 31 choking_algorithm
        SeedChokingAlgorithm.ROUND_ROBIN,    // 32 seed_choking_algorithm
        2048,           // 33  cache_size (deprecated)
        0,              // 34  cache_buffer_chunk_size (deprecated)
        300,            // 35  cache_expiry (deprecated)
        IoBufferMode.ENABLE_OS_CACHE, // 36 disk_io_write_mode (non-Windows: enable_os_cache)
        IoBufferMode.ENABLE_OS_CACHE, // 37 disk_io_read_mode
        0,              // 38  outgoing_port
        0,              // 39  num_outgoing_ports
        0x01,           // 40  peer_dscp
        3,              // 41  active_downloads
        5,              // 42  active_seeds
        1,              // 43  active_checking
        88,             // 44  active_dht_limit
        1600,           // 45  active_tracker_limit
        60,             // 46  active_lsd_limit
        500,            // 47  active_limit
        0,              // 48  active_loaded_limit (deprecated)
        30,             // 49  auto_manage_interval
        24 * 60 * 60,   // 50  seed_time_limit
        1800,           // 51  auto_scrape_interval
        300,            // 52  auto_scrape_min_interval
        3000,           // 53  max_peerlist_size
        1000,           // 54  max_paused_peerlist_size
        5 * 60,         // 55  min_announce_interval
        60,             // 56  auto_manage_startup
        20,             // 57  seeding_piece_quota
        50,             // 58  max_rejects
        0,              // 59  recv_socket_buffer_size
        0,              // 60  send_socket_buffer_size
        2 * 1024 * 1024,// 61  max_peer_recv_buffer_size
        0,              // 62  file_checks_delay_per_block (deprecated)
        32,             // 63  read_cache_line_size (deprecated)
        16,             // 64  write_cache_line_size (deprecated)
        10 * 60,        // 65  optimistic_disk_retry
        16,             // 66  max_suggest_pieces
        5 * 60,         // 67  local_service_announce_interval
        15 * 60,        // 68  dht_announce_interval
        60,             // 69  udp_tracker_token_expiry
        1,              // 70  default_cache_min_age (deprecated)
        0,              // 71  num_optimistic_unchoke_slots
        16000,          // 72  default_est_reciprocation_rate (deprecated)
        20,             // 73  increase_est_reciprocation_rate (deprecated)
        3,              // 74  decrease_est_reciprocation_rate (deprecated)
        50,             // 75  max_pex_peers
        500,            // 76  tick_interval
        3,              // 77  share_mode_target
        0,              // 78  upload_rate_limit
        0,              // 79  download_rate_limit
        0,              // 80  local_upload_rate_limit (deprecated)
        0,              // 81  local_download_rate_limit (deprecated)
        8000,           // 82  dht_upload_rate_limit
        8,              // 83  unchoke_slots_limit
        0,              // 84  half_open_limit (deprecated)
        200,            // 85  connections_limit
        10,             // 86  connections_slack
        100,            // 87  utp_target_delay
        3000,           // 88  utp_gain_factor
        500,            // 89  utp_min_timeout
        2,              // 90  utp_syn_resends
        2,              // 91  utp_fin_resends
        3,              // 92  utp_num_resends
        3000,           // 93  utp_connect_timeout
        0,              // 94  utp_delayed_ack (deprecated)
        50,             // 95  utp_loss_multiplier
        MixedModeAlgorithm.PEER_PROPORTIONAL, // 96 mixed_mode_algorithm
        5,              // 97  listen_queue_size
        30,             // 98  torrent_connect_boost
        2000,           // 99  alert_queue_size
        3 * 1024 * 10240, // 100 max_metadata_size
        1,              // 101 hashing_threads
        256,            // 102 checking_mem_usage
        0,              // 103 predictive_piece_announce
        10,             // 104 aio_threads
        300,            // 105 aio_max (deprecated)
        0,              // 106 network_threads (deprecated)
        0,              // 107 ssl_listen (deprecated)
        250,            // 108 tracker_backoff
        200,            // 109 share_ratio_limit
        700,            // 110 seed_time_ratio_limit
        4,              // 111 peer_turnover
        90,             // 112 peer_turnover_cutoff
        300,            // 113 peer_turnover_interval
        10,             // 114 connect_seed_every_n_download
        4 * 1024 * 204, // 115 max_http_recv_buffer_size
        10,             // 116 max_retry_port_bind
        1,              // 117 alert_mask (alert_category::error == 1)
        EncPolicy.PE_ENABLED, // 118 out_enc_policy
        EncPolicy.PE_ENABLED, // 119 in_enc_policy
        EncLevel.PE_BOTH,     // 120 allowed_enc_level
        2048,           // 121 inactive_down_rate
        2048,           // 122 inactive_up_rate
        ProxyType.NONE, // 123 proxy_type
        0,              // 124 proxy_port
        0,              // 125 i2p_port
        256,            // 126 cache_size_volatile (deprecated)
        16 * 1024 * 1024, // 127 urlseed_max_request_bytes
        1800,           // 128 web_seed_name_lookup_retry
        0,              // 129 close_file_interval (non-Windows: 0)
        100,            // 130 utp_cwnd_reduce_timer
        3,              // 131 max_web_seed_connections
        1200,           // 132 resolver_cache_timeout
        16384,          // 133 send_not_sent_low_watermark
        1024,           // 134 rate_choker_initial_threshold
        3600,           // 135 upnp_lease_duration
        50,             // 136 max_concurrent_http_announces
        100,            // 137 dht_max_peers_reply
        5,              // 138 dht_search_branching
        20,             // 139 dht_max_fail_count
        2000,           // 140 dht_max_torrents
        700,            // 141 dht_max_dht_items
        500,            // 142 dht_max_peers
        20,             // 143 dht_max_torrent_search_reply
        5 * 60,         // 144 dht_block_timeout
        5,              // 145 dht_block_ratelimit
        0,              // 146 dht_item_lifetime
        21600,          // 147 dht_sample_infohashes_interval
        20,             // 148 dht_max_infohashes_sample_count
        0x200000,       // 149 max_piece_count
        2500000,        // 150 metadata_token_limit
        MmapWriteMode.AUTO_MMAP_WRITE, // 151 disk_write_mode
        40,             // 152 mmap_file_size_cutoff
        3,              // 153 i2p_inbound_quantity
        3,              // 154 i2p_outbound_quantity
        3,              // 155 i2p_inbound_length
        3,              // 156 i2p_outbound_length
        0,              // 157 announce_port
        0,              // 158 i2p_inbound_length_variance
        0,              // 159 i2p_outbound_length_variance
        3600,           // 160 natpmp_lease_duration
    )

    /** Number of int settings (= libtorrent's `num_int_settings`). */
    val COUNT: Int get() = DEFAULTS.size
}
