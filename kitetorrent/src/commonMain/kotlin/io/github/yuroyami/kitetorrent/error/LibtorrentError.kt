package io.github.yuroyami.kitetorrent.error

/**
 * libtorrent's primary error enumeration, ported from `errors::error_code_enum`
 * (include/libtorrent/error_code.hpp) together with the human-readable strings
 * from `libtorrent_error_category::message()` (src/error_code.cpp).
 *
 * In libtorrent these codes live in the `libtorrent_category()` Boost.System
 * error category. KiteTorrent has no Boost.System, so this enum carries both the
 * stable integer [code] (matching libtorrent's on-the-wire / ABI value exactly)
 * and the [message] string that `libtorrent_category().message(code)` returns.
 *
 * The integer values are part of libtorrent's ABI and are referenced by other
 * subsystems, so they are preserved faithfully — including the gaps left by
 * deprecated NAT-PMP codes (120-124), the reserved ranges, and the version-1
 * ABI-only bdecode codes (190-196). Those gaps are represented here as
 * `RESERVED_n` / `DEPRECATED_n` placeholders so that [entries] stays a dense,
 * index-addressable table where `entries[code].code == code` for every code in
 * `0 until ENTRY_COUNT`.
 *
 * This corresponds to the non-deprecated build of libtorrent (i.e.
 * `TORRENT_ABI_VERSION != 1`): the version-1-only spellings such as
 * `unsupported_protocol_version` (120) or `expected_string` (190) collapse into
 * the `DEPRECATED_120` / `RESERVED_190` placeholders, but their numeric slots
 * and category messages are retained.
 *
 * @property code the stable integer error value used by libtorrent.
 * @property message the human-readable description, matching libtorrent's
 *   `libtorrent_category().message(code)`. May be empty for reserved/unused slots.
 */
enum class LibtorrentError(val code: Int, val message: String) {
    /** Not an error */
    NO_ERROR(0, "no error"),

    /** Two torrents has files which end up overwriting each other */
    FILE_COLLISION(1, "torrent file collides with file from another torrent"),

    /** A piece did not match its piece hash */
    FAILED_HASH_CHECK(2, "hash check failed"),

    /** The .torrent file does not contain a bencoded dictionary at its top level */
    TORRENT_IS_NO_DICT(3, "torrent file is not a dictionary"),

    /** The .torrent file does not have an `info` dictionary */
    TORRENT_MISSING_INFO(4, "missing or invalid 'info' section in torrent file"),

    /** The .torrent file's `info` entry is not a dictionary */
    TORRENT_INFO_NO_DICT(5, "'info' entry is not a dictionary"),

    /** The .torrent file does not have a `piece length` entry */
    TORRENT_MISSING_PIECE_LENGTH(6, "invalid or missing 'piece length' entry in torrent file"),

    /** The .torrent file does not have a `name` entry */
    TORRENT_MISSING_NAME(7, "missing name in torrent file"),

    /** The .torrent file's name entry is invalid */
    TORRENT_INVALID_NAME(8, "invalid 'name' of torrent (possible exploit attempt)"),

    /**
     * The length of a file, or of the whole .torrent file is invalid. Either negative or not an
     * integer
     */
    TORRENT_INVALID_LENGTH(9, "invalid length of torrent"),

    /** Failed to parse a file entry in the .torrent */
    TORRENT_FILE_PARSE_FAILED(10, "failed to parse files from torrent file"),

    /** The `pieces` field is missing or invalid in the .torrent file */
    TORRENT_MISSING_PIECES(11, "invalid or missing 'pieces' entry in torrent file"),

    /** The `pieces` string has incorrect length */
    TORRENT_INVALID_HASHES(12, "incorrect number of piece hashes in torrent file"),

    /** The .torrent file has more pieces than is supported by libtorrent */
    TOO_MANY_PIECES_IN_TORRENT(13, "too many pieces in torrent"),

    /**
     * The metadata (.torrent file) that was received from the swarm matched the info-hash, but
     * failed to be parsed
     */
    INVALID_SWARM_METADATA(14, "invalid metadata received from swarm"),

    /** The file or buffer is not correctly bencoded */
    INVALID_BENCODING(15, "invalid bencoding"),

    /** The .torrent file does not contain any files */
    NO_FILES_IN_TORRENT(16, "no files in torrent"),

    /** The string was not properly url-encoded as expected */
    INVALID_ESCAPED_STRING(17, "invalid escaped string"),

    /** Operation is not permitted since the session is shutting down */
    SESSION_IS_CLOSING(18, "session is closing"),

    /** There's already a torrent with that info-hash added to the session */
    DUPLICATE_TORRENT(19, "torrent already exists in session"),

    /** The supplied torrent_handle is not referring to a valid torrent */
    INVALID_TORRENT_HANDLE(20, "invalid torrent handle used"),

    /** The type requested from the entry did not match its type */
    INVALID_ENTRY_TYPE(21, "invalid type requested from entry"),

    /** The specified URI does not contain a valid info-hash */
    MISSING_INFO_HASH_IN_URI(22, "missing info-hash from URI"),

    /**
     * One of the files in the torrent was unexpectedly small. This might be caused by files being
     * changed by an external process
     */
    FILE_TOO_SHORT(23, "file too short"),

    /**
     * The URL used an unknown protocol. Currently `http` and `https` (if built with openssl
     * support) are recognized. For trackers `udp` is recognized as well.
     */
    UNSUPPORTED_URL_PROTOCOL(24, "unsupported URL protocol"),

    /** The URL did not conform to URL syntax and failed to be parsed */
    URL_PARSE_ERROR(25, "failed to parse URL"),

    /** The peer sent a piece message of length 0 */
    PEER_SENT_EMPTY_PIECE(26, "peer sent 0 length piece"),

    /** A bencoded structure was corrupt and failed to be parsed */
    PARSE_FAILED(27, "parse failed"),

    /** The fast resume file was missing or had an invalid file version tag */
    INVALID_FILE_TAG(28, "invalid file format tag"),

    /** The fast resume file was missing or had an invalid info-hash */
    MISSING_INFO_HASH(29, "missing info-hash"),

    /** The info-hash did not match the torrent */
    MISMATCHING_INFO_HASH(30, "mismatching info-hash"),

    /** The URL contained an invalid hostname */
    INVALID_HOSTNAME(31, "invalid hostname"),

    /** The URL had an invalid port */
    INVALID_PORT(32, "invalid port"),

    /** The port is blocked by the port-filter, and prevented the connection */
    PORT_BLOCKED(33, "port blocked by port-filter"),

    /** The IPv6 address was expected to end with "]" */
    EXPECTED_CLOSE_BRACKET_IN_ADDRESS(34, "expected closing ] for address"),

    /** The torrent is being destructed, preventing the operation to succeed */
    DESTRUCTING_TORRENT(35, "destructing torrent"),

    /** The connection timed out */
    TIMED_OUT(36, "timed out"),

    /** The peer is upload only, and we are upload only. There's no point in keeping the connection */
    UPLOAD_UPLOAD_CONNECTION(37, "upload to upload connection"),

    /**
     * The peer is upload only, and we're not interested in it. There's no point in keeping the
     * connection
     */
    UNINTERESTING_UPLOAD_PEER(38, "uninteresting upload-only peer"),

    /** The peer sent an unknown info-hash */
    INVALID_INFO_HASH(39, "invalid info-hash"),

    /** The torrent is paused, preventing the operation from succeeding */
    TORRENT_PAUSED(40, "torrent paused"),

    /**
     * The peer sent an invalid have message, either wrong size or referring to a piece that
     * doesn't exist in the torrent
     */
    INVALID_HAVE(41, "'have'-message with higher index than the number of pieces"),

    /** The bitfield message had the incorrect size */
    INVALID_BITFIELD_SIZE(42, "bitfield of invalid size"),

    /** The peer kept requesting pieces after it was choked, possible abuse attempt. */
    TOO_MANY_REQUESTS_WHEN_CHOKED(43, "too many piece requests while choked"),

    /** The peer sent a piece message that does not correspond to a piece request sent by the client */
    INVALID_PIECE(44, "invalid piece packet"),

    /** memory allocation failed */
    NO_MEMORY(45, "out of memory"),

    /** The torrent is aborted, preventing the operation to succeed */
    TORRENT_ABORTED(46, "torrent aborted"),

    /** The peer is a connection to ourself, no point in keeping it */
    SELF_CONNECTION(47, "connected to ourselves"),

    /** The peer sent a piece message with invalid size, either negative or greater than one block */
    INVALID_PIECE_SIZE(48, "invalid piece size"),

    /**
     * The peer has not been interesting or interested in us for too long, no point in keeping it
     * around
     */
    TIMED_OUT_NO_INTEREST(49, "timed out: no interest"),

    /** The peer has not said anything in a long time, possibly dead */
    TIMED_OUT_INACTIVITY(50, "timed out: inactivity"),

    /**
     * The peer did not send a handshake within a reasonable amount of time, it might not be a
     * bittorrent peer
     */
    TIMED_OUT_NO_HANDSHAKE(51, "timed out: no handshake"),

    /**
     * The peer has been unchoked for too long without requesting any data. It might be lying about
     * its interest in us
     */
    TIMED_OUT_NO_REQUEST(52, "timed out: no request"),

    /** The peer sent an invalid choke message */
    INVALID_CHOKE(53, "invalid choke message"),

    /** The peer send an invalid unchoke message */
    INVALID_UNCHOKE(54, "invalid unchoke message"),

    /** The peer sent an invalid interested message */
    INVALID_INTERESTED(55, "invalid interested message"),

    /** The peer sent an invalid not-interested message */
    INVALID_NOT_INTERESTED(56, "invalid not-interested message"),

    /** The peer sent an invalid piece request message */
    INVALID_REQUEST(57, "invalid request message"),

    /** The peer sent an invalid hash-list message (this is part of the merkle-torrent extension) */
    INVALID_HASH_LIST(58, "invalid hash list"),

    /** The peer sent an invalid hash-piece message (this is part of the merkle-torrent extension) */
    INVALID_HASH_PIECE(59, "invalid hash piece message"),

    /** The peer sent an invalid cancel message */
    INVALID_CANCEL(60, "invalid cancel message"),

    /** The peer sent an invalid DHT port-message */
    INVALID_DHT_PORT(61, "invalid dht-port message"),

    /** The peer sent an invalid suggest piece-message */
    INVALID_SUGGEST(62, "invalid suggest piece message"),

    /** The peer sent an invalid have all-message */
    INVALID_HAVE_ALL(63, "invalid have-all message"),

    /** The peer sent an invalid have none-message */
    INVALID_HAVE_NONE(64, "invalid have-none message"),

    /** The peer sent an invalid reject message */
    INVALID_REJECT(65, "invalid reject message"),

    /** The peer sent an invalid allow fast-message */
    INVALID_ALLOW_FAST(66, "invalid allow-fast message"),

    /** The peer sent an invalid extension message ID */
    INVALID_EXTENDED(67, "invalid extended message"),

    /** The peer sent an invalid message ID */
    INVALID_MESSAGE(68, "invalid message"),

    /** The synchronization hash was not found in the encrypted handshake */
    SYNC_HASH_NOT_FOUND(69, "sync hash not found"),

    /** The encryption constant in the handshake is invalid */
    INVALID_ENCRYPTION_CONSTANT(70, "unable to verify encryption constant"),

    /** The peer does not support plain text, which is the selected mode */
    NO_PLAINTEXT_MODE(71, "plaintext mode not provided"),

    /** The peer does not support RC4, which is the selected mode */
    NO_RC4_MODE(72, "rc4 mode not provided"),

    /** The peer does not support any of the encryption modes that the client supports */
    UNSUPPORTED_ENCRYPTION_MODE(73, "unsupported encryption mode"),

    /** The peer selected an encryption mode that the client did not advertise and does not support */
    UNSUPPORTED_ENCRYPTION_MODE_SELECTED(74, "peer selected unsupported encryption mode"),

    /** The pad size used in the encryption handshake is of invalid size */
    INVALID_PAD_SIZE(75, "invalid encryption pad size"),

    /** The encryption handshake is invalid */
    INVALID_ENCRYPT_HANDSHAKE(76, "invalid encryption handshake"),

    /**
     * The client is set to not support incoming encrypted connections and this is an encrypted
     * connection
     */
    NO_INCOMING_ENCRYPTED(77, "incoming encrypted connections disabled"),

    /**
     * The client is set to not support incoming regular bittorrent connections, and this is a
     * regular connection
     */
    NO_INCOMING_REGULAR(78, "incoming regular connections disabled"),

    /** The client is already connected to this peer-ID */
    DUPLICATE_PEER_ID(79, "duplicate peer-id"),

    /** Torrent was removed */
    TORRENT_REMOVED(80, "torrent removed"),

    /** The packet size exceeded the upper sanity check-limit */
    PACKET_TOO_LARGE(81, "packet too large"),

    RESERVED(82, ""),

    /** The web server responded with an error */
    HTTP_ERROR(83, "HTTP error"),

    /** The web server response is missing a location header */
    MISSING_LOCATION(84, "missing location header"),

    /** The web seed redirected to a path that no longer matches the .torrent directory structure */
    INVALID_REDIRECTION(85, "invalid redirection"),

    /** The connection was closed because it redirected to a different URL */
    REDIRECTING(86, "redirecting"),

    /** The HTTP range header is invalid */
    INVALID_RANGE(87, "invalid HTTP range"),

    /** The HTTP response did not have a content length */
    NO_CONTENT_LENGTH(88, "missing content-length"),

    /** The IP is blocked by the IP filter */
    BANNED_BY_IP_FILTER(89, "banned by IP filter"),

    /** At the connection limit */
    TOO_MANY_CONNECTIONS(90, "too many connections"),

    /** The peer is marked as banned */
    PEER_BANNED(91, "peer banned"),

    /** The torrent is stopping, causing the operation to fail */
    STOPPING_TORRENT(92, "stopping torrent"),

    /** The peer has sent too many corrupt pieces and is banned */
    TOO_MANY_CORRUPT_PIECES(93, "too many corrupt pieces"),

    /** The torrent is not ready to receive peers */
    TORRENT_NOT_READY(94, "torrent is not ready to accept peers"),

    /** The peer is not completely constructed yet */
    PEER_NOT_CONSTRUCTED(95, "peer is not properly constructed"),

    /** The session is closing, causing the operation to fail */
    SESSION_CLOSING(96, "session is closing"),

    /** The peer was disconnected in order to leave room for a potentially better peer */
    OPTIMISTIC_DISCONNECT(97, "optimistic disconnect"),

    /** The torrent is finished */
    TORRENT_FINISHED(98, "torrent finished"),

    /** No UPnP router found */
    NO_ROUTER(99, "no router found"),

    /** The metadata message says the metadata exceeds the limit */
    METADATA_TOO_LARGE(100, "metadata too large"),

    /** The peer sent an invalid metadata request message */
    INVALID_METADATA_REQUEST(101, "invalid metadata request"),

    /** The peer advertised an invalid metadata size */
    INVALID_METADATA_SIZE(102, "invalid metadata size"),

    /** The peer sent a message with an invalid metadata offset */
    INVALID_METADATA_OFFSET(103, "invalid metadata offset"),

    /** The peer sent an invalid metadata message */
    INVALID_METADATA_MESSAGE(104, "invalid metadata message"),

    /** The peer sent a peer exchange message that was too large */
    PEX_MESSAGE_TOO_LARGE(105, "pex message too large"),

    /** The peer sent an invalid peer exchange message */
    INVALID_PEX_MESSAGE(106, "invalid pex message"),

    /** The peer sent an invalid tracker exchange message */
    INVALID_LT_TRACKER_MESSAGE(107, "invalid lt_tracker message"),

    /** The peer sent an pex messages too often. This is a possible attempt of and attack */
    TOO_FREQUENT_PEX(108, "pex messages sent too frequent (possible attack)"),

    /**
     * The operation failed because it requires the torrent to have the metadata (.torrent file)
     * and it doesn't have it yet. This happens for magnet links before they have downloaded the
     * metadata, and also torrents added by URL.
     */
    NO_METADATA(109, "torrent has no metadata"),

    /**
     * The peer sent an invalid `dont_have` message. The don't have message is an extension to
     * allow peers to advertise that the no longer has a piece they previously had.
     */
    INVALID_DONT_HAVE(110, "invalid dont-have message"),

    /** The peer tried to connect to an SSL torrent without connecting over SSL. */
    REQUIRES_SSL_CONNECTION(111, "SSL connection required"),

    /** The peer tried to connect to a torrent with a certificate for a different torrent. */
    INVALID_SSL_CERT(112, "invalid SSL certificate"),

    /** the torrent is not an SSL torrent, and the operation requires an SSL torrent */
    NOT_AN_SSL_TORRENT(113, "not an SSL torrent"),

    /**
     * peer was banned because its listen port is within a banned port range, as specified by the
     * port_filter.
     */
    BANNED_BY_PORT_FILTER(114, "banned by port filter"),

    /** The session_handle is not referring to a valid session_impl */
    INVALID_SESSION_HANDLE(115, "invalid session handle used"),

    /** the listen socket associated with this request was closed */
    INVALID_LISTEN_SOCKET(116, "listen socket has been closed"),

    INVALID_HASH_REQUEST(117, "invalid hash request"),

    INVALID_HASHES(118, "invalid hashes"),

    INVALID_HASH_REJECT(119, "invalid hash reject"),

    DEPRECATED_120(120, "unsupported protocol version"),

    DEPRECATED_121(121, "not authorized to create port map (enable NAT-PMP on your router)"),

    DEPRECATED_122(122, "network failure"),

    DEPRECATED_123(123, "out of resources"),

    DEPRECATED_124(124, "unsupported opcode"),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_125(125, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_126(126, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_127(127, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_128(128, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_129(129, ""),

    /** The resume data file is missing the `file sizes` entry */
    MISSING_FILE_SIZES(130, "missing or invalid 'file sizes' entry"),

    /** The resume data file `file sizes` entry is empty */
    NO_FILES_IN_RESUME_DATA(131, "no files in resume data"),

    /** The resume data file is missing the `pieces` and `slots` entry */
    MISSING_PIECES(132, "missing 'slots' and 'pieces' entry"),

    /** The number of files in the resume data does not match the number of files in the torrent */
    MISMATCHING_NUMBER_OF_FILES(133, "mismatching number of files"),

    /** One of the files on disk has a different size than in the fast resume file */
    MISMATCHING_FILE_SIZE(134, "mismatching file size"),

    /** One of the files on disk has a different timestamp than in the fast resume file */
    MISMATCHING_FILE_TIMESTAMP(135, "mismatching file timestamp"),

    /** The resume data file is not a dictionary */
    NOT_A_DICTIONARY(136, "not a dictionary"),

    /** The `blocks per piece` entry is invalid in the resume data file */
    INVALID_BLOCKS_PER_PIECE(137, "invalid 'blocks per piece' entry"),

    /**
     * The resume file is missing the `slots` entry, which is required for torrents with compact
     * allocation. *DEPRECATED*
     */
    MISSING_SLOTS(138, "missing slots list"),

    /** The resume file contains more slots than the torrent */
    TOO_MANY_SLOTS(139, "file has more slots than torrent"),

    /** The `slot` entry is invalid in the resume data */
    INVALID_SLOT_LIST(140, "invalid entry type in slot list"),

    /** One index in the `slot` list is invalid */
    INVALID_PIECE_INDEX(141, "invalid piece index in slot list"),

    /**
     * The pieces on disk needs to be re-ordered for the specified allocation mode. This happens if
     * you specify sparse allocation and the files on disk are using compact storage. The pieces
     * needs to be moved to their right position. *DEPRECATED*
     */
    PIECES_NEED_REORDER(142, "pieces needs to be reordered"),

    /**
     * this error is returned when asking to save resume data and specifying the flag to only save
     * when there's anything new to save (torrent_handle::only_if_modified) and there wasn't
     * anything changed.
     */
    RESUME_DATA_NOT_MODIFIED(143, "fastresume not modified since last save"),

    /** the save_path in add_torrent_params is not valid */
    INVALID_SAVE_PATH(144, "invalid save_path"),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_145(145, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_146(146, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_147(147, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_148(148, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_149(149, ""),

    /** The HTTP header was not correctly formatted */
    HTTP_PARSE_ERROR(150, "Invalid HTTP header"),

    /** The HTTP response was in the 300-399 range but lacked a location header */
    HTTP_MISSING_LOCATION(151, "missing Location header in HTTP redirect"),

    /** The HTTP response was encoded with gzip or deflate but decompressing it failed */
    HTTP_FAILED_DECOMPRESS(152, "failed to decompress HTTP response"),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_153(153, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_154(154, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_155(155, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_156(156, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_157(157, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_158(158, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_159(159, ""),

    /** The URL specified an i2p address, but no i2p router is configured */
    NO_I2P_ROUTER(160, "no i2p router is set up"),

    /** i2p acceptor is not available yet, can't announce without endpoint */
    NO_I2P_ENDPOINT(161, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_162(162, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_163(163, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_164(164, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_165(165, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_166(166, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_167(167, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_168(168, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_169(169, ""),

    /**
     * The tracker URL doesn't support transforming it into a scrape URL. i.e. it doesn't contain
     * "announce.
     */
    SCRAPE_NOT_AVAILABLE(170, "scrape not available on tracker"),

    /** invalid tracker response */
    INVALID_TRACKER_RESPONSE(171, "invalid tracker response"),

    /** invalid peer dictionary entry. Not a dictionary */
    INVALID_PEER_DICT(172, "invalid peer dictionary entry"),

    /** tracker sent a failure message */
    TRACKER_FAILURE(173, "tracker sent a failure message"),

    /** missing or invalid `files` entry */
    INVALID_FILES_ENTRY(174, "missing or invalid 'files' entry"),

    /** missing or invalid `hash` entry */
    INVALID_HASH_ENTRY(175, "missing or invalid 'hash' entry"),

    /** missing or invalid `peers` and `peers6` entry */
    INVALID_PEERS_ENTRY(176, "missing or invalid 'peers' and 'peers6' entry"),

    /** UDP tracker response packet has invalid size */
    INVALID_TRACKER_RESPONSE_LENGTH(177, "udp tracker response packet has invalid size"),

    /** invalid transaction id in UDP tracker response */
    INVALID_TRACKER_TRANSACTION_ID(178, "invalid transaction id in udp tracker response"),

    /** invalid action field in UDP tracker response */
    INVALID_TRACKER_ACTION(179, "invalid action field in udp tracker response"),

    /**
     * skipped announce (because it's assumed to be unreachable over the given source network
     * interface)
     */
    ANNOUNCE_SKIPPED(180, "skipping tracker announce (unreachable)"),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_181(181, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_182(182, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_183(183, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_184(184, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_185(185, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_186(186, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_187(187, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_188(188, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_189(189, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_190(190, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_191(191, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_192(192, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_193(193, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_194(194, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_195(195, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_196(196, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_197(197, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_198(198, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_199(199, ""),

    /** random number generation failed */
    NO_ENTROPY(200, "random number generator failed"),

    /** blocked by SSRF mitigation */
    SSRF_MITIGATION(201, "blocked by SSRF mitigation"),

    /** blocked because IDNA host names are banned */
    BLOCKED_BY_IDNA(202, "blocked by IDNA ban"),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_203(203, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_204(204, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_205(205, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_206(206, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_207(207, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_208(208, ""),

    /**
     * Reserved / unused slot, present only to preserve the integer values of the surrounding error
     * codes.
     */
    RESERVED_209(209, ""),

    /** the torrent file has an unknown meta version */
    TORRENT_UNKNOWN_VERSION(210, "the torrent file has an unknown meta version"),

    /** the v2 torrent file has no file tree */
    TORRENT_MISSING_FILE_TREE(211, "the v2 torrent file has no file tree"),

    /** the torrent contains v2 keys but does not specify meta version 2 */
    TORRENT_MISSING_META_VERSION(212, "the torrent contains v2 keys but does not specify meta version 2"),

    /** the v1 and v2 file metadata does not match */
    TORRENT_INCONSISTENT_FILES(213, "the v1 and v2 file metadata does not match"),

    /** one or more files are missing piece layer hashes */
    TORRENT_MISSING_PIECE_LAYER(214, "one or more files are missing piece layer hashes"),

    /** a piece layer has the wrong size or failed hash check */
    TORRENT_INVALID_PIECE_LAYER(215, "a piece layer is invalid"),

    /** a v2 file entry has no root hash */
    TORRENT_MISSING_PIECES_ROOT(216, "a v2 file entry has no root hash"),

    /** the v1 and v2 hashes do not describe the same data */
    TORRENT_INCONSISTENT_HASHES(217, "v1 and v2 hashes do not describe the same data"),

    /** a file in the v2 metadata has the pad attribute set */
    TORRENT_INVALID_PAD_FILE(218, "a file in the v2 metadata has the pad attribute set"),
    ;

    companion object {
        /**
         * Every entry indexed by its [code]. Because the libtorrent codes form a
         * contiguous range `0 .. error_code_max - 1`, this is a dense lookup table:
         * `entries[c].code == c` for all `c in 0 until ENTRY_COUNT`.
         */
        val entries: List<LibtorrentError> = enumValues<LibtorrentError>().sortedBy { it.code }

        /**
         * The number of distinct error codes, mirroring libtorrent's
         * `errors::error_code_max` (the sentinel just past the last valid code).
         */
        const val ENTRY_COUNT: Int = 219

        /**
         * Resolve a raw integer error value to its [LibtorrentError], or `null`
         * when [code] is outside the known range. Mirrors the bounds check in
         * `libtorrent_error_category::message()`, which returns "Unknown error"
         * for out-of-range values.
         */
        fun fromCodeOrNull(code: Int): LibtorrentError? =
            if (code in 0 until ENTRY_COUNT) entries[code] else null

        /**
         * Resolve a raw integer error value to its [LibtorrentError], throwing
         * [IllegalArgumentException] when [code] is out of range.
         */
        fun fromCode(code: Int): LibtorrentError =
            fromCodeOrNull(code)
                ?: throw IllegalArgumentException("unknown libtorrent error code: $code")

        /**
         * The category message for a raw integer value. Faithful to
         * `libtorrent_category().message(ev)`: returns "Unknown error" for any
         * value outside `0 until ENTRY_COUNT`.
         */
        fun messageOf(code: Int): String =
            fromCodeOrNull(code)?.message ?: "Unknown error"
    }
}

/**
 * Thrown when a libtorrent operation fails with a [LibtorrentError]. Mirrors the
 * way libtorrent surfaces a `libtorrent_category()` `error_code` as a
 * `system_error` / `libtorrent_exception`; the exception message is the error's
 * human-readable [LibtorrentError.message].
 *
 * @property error the underlying libtorrent error code that triggered this exception.
 */
class TorrentException(val error: LibtorrentError) : Exception(error.message)
