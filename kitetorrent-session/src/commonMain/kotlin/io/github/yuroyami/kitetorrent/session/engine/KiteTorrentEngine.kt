package io.github.yuroyami.kitetorrent.session.engine

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.dht.generateRandomId
import io.github.yuroyami.kitetorrent.generatePeerId
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.session.dht.DhtNode
import io.github.yuroyami.kitetorrent.session.disk.DiskIo
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.UtpSocketManager
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.peer.MetadataExchange
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.torrent.MagnetLink
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The top-level engine — the KiteTorrent equivalent of libtorrent's `session`. It owns
 * the shared [NetworkRuntime], the [RateLimiter], the [ConnectionBudget], an optional
 * [DhtNode] and [UtpSocketManager], and the set of active [TorrentSession]s — the
 * single object an app talks to.
 *
 * Configuration flows through the ported [SettingsPack], exactly like
 * `session(settings_pack)`: `connections_limit` caps the session-wide peer count,
 * `upload_rate_limit`/`download_rate_limit` seed the limiter (also settable live via
 * [setUploadRateLimit]/[setDownloadRateLimit]), and the per-torrent engine reads its
 * request/choke/timeout knobs from the same pack.
 *
 * With [enableUtp], one UDP socket on [listenPort] carries µTP: outgoing connections
 * dial uTP first (TCP fallback) and inbound uTP connects are accepted and routed by
 * info-hash exactly like the TCP accept loop. When the DHT is enabled too, both share
 * that socket through the manager's demultiplexer — libtorrent's single-listen-socket
 * model.
 *
 * @param clock epoch-seconds provider for DHT expiry (`{ System.currentTimeMillis()/1000 }`).
 */
class KiteTorrentEngine(
    private val scope: CoroutineScope,
    val peerId: Sha1Hash = generatePeerId(),
    val listenPort: Int = 6881,
    private val httpTracker: HttpTracker? = null,
    private val enableDht: Boolean = false,
    private val enableUtp: Boolean = false,
    private val clock: () -> Long = { 0L },
    val settings: SettingsPack = SettingsPack(),
) {
    val network = NetworkRuntime(scope.coroutineContext)

    /** The session-wide bandwidth scheduler (the live `bandwidth_manager` wiring). */
    val rateLimiter = RateLimiter(scope)

    private val connections = ConnectionBudget(settings.getInt(IntSetting.CONNECTIONS_LIMIT))
    private val torrents = LinkedHashMap<String, TorrentSession>()
    private var dht: DhtNode? = null
    private var utpManager: UtpSocketManager? = null

    /** The actual TCP port we're listening on (resolved after [start]; equals [listenPort] unless it was 0). */
    var boundListenPort: Int = listenPort
        private set

    init {
        rateLimiter.setUploadLimit(settings.getInt(IntSetting.UPLOAD_RATE_LIMIT))
        rateLimiter.setDownloadLimit(settings.getInt(IntSetting.DOWNLOAD_RATE_LIMIT))
    }

    /** Bring up the listen socket(s) and the DHT (if enabled). Call [bootstrapDht] to join the DHT. */
    suspend fun start() {
        startListening()
        rateLimiter.start()
        // UDP (µTP + DHT) binds the same port number TCP resolved to — peers reach
        // both transports on the one advertised port, libtorrent's listen model
        if (enableUtp && utpManager == null) {
            val udp = network.bindUdp(boundListenPort)
            utpManager = UtpSocketManager(udp, scope).also { mgr ->
                mgr.onIncomingConnection = { stream -> acceptUtpPeer(mgr, stream) }
                mgr.start()
            }
        }
        if (enableDht && dht == null) {
            // share the µTP socket when there is one (session_impl routes both over
            // the listen socket); otherwise bind our own
            val transport = utpManager?.dhtTransport() ?: network.bindUdp(boundListenPort)
            dht = DhtNode(transport, generateRandomId(), scope, clock = clock).also { it.start() }
        }
    }

    /** Cap session-wide upload at [bytesPerSecond] (0 = unlimited). `upload_rate_limit`. */
    fun setUploadRateLimit(bytesPerSecond: Int) {
        settings.setInt(IntSetting.UPLOAD_RATE_LIMIT, bytesPerSecond)
        rateLimiter.setUploadLimit(bytesPerSecond)
    }

    /** Cap session-wide download at [bytesPerSecond] (0 = unlimited). `download_rate_limit`. */
    fun setDownloadRateLimit(bytesPerSecond: Int) {
        settings.setInt(IntSetting.DOWNLOAD_RATE_LIMIT, bytesPerSecond)
        rateLimiter.setDownloadLimit(bytesPerSecond)
    }

    /** Accept inbound TCP peers and route each to the matching torrent by info-hash. */
    private suspend fun startListening() {
        val server = network.bindTcp(listenPort)
        boundListenPort = server.localPort
        scope.launch {
            while (isActive) {
                val conn = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                scope.launch {
                    try {
                        val hs = Handshake.decode(conn.readExactly(Handshake.LENGTH))
                        val session = hs?.let { torrents[it.infoHash.toHex()] }
                        if (session == null) conn.close() else session.acceptInbound(conn, hs)
                    } catch (_: Throwable) {
                        conn.close()
                    }
                }
            }
        }
    }

    /** Accept one inbound µTP peer: read the handshake off the stream and route by info-hash. */
    private suspend fun acceptUtpPeer(mgr: UtpSocketManager, stream: UtpStream) {
        try {
            val hs = Handshake.decode(stream.readExactly(Handshake.LENGTH))
            val session = hs?.let { torrents[it.infoHash.toHex()] }
            if (session == null) {
                mgr.disconnect(stream)
            } else {
                session.acceptInbound(stream, { mgr.disconnect(stream) }, hs)
            }
        } catch (_: Throwable) {
            runCatching { mgr.disconnect(stream) }
        }
    }

    /** Join the DHT via well-known router nodes (e.g. `router.bittorrent.com:6881`). */
    suspend fun bootstrapDht(routers: List<Pair<String, Int>>) {
        dht?.bootstrap(routers)
    }

    /**
     * Add a torrent and begin downloading it. Announces to trackers immediately, and —
     * if the DHT is up — kicks off a `get_peers` lookup whose results are fed in too.
     */
    suspend fun addTorrent(torrent: TorrentInfo, disk: DiskIo): TorrentSession {
        val session = TorrentSession(
            torrent = torrent,
            disk = disk,
            network = network,
            scope = scope,
            peerId = peerId,
            listenPort = listenPort,
            httpTracker = httpTracker,
            settings = settings,
            utp = utpManager,
            limiter = rateLimiter,
            torrentBandwidth = rateLimiter.newTorrentChannels(),
            connections = connections,
        )
        torrents[torrent.infoHashHex()] = session
        session.start()

        val node = dht
        val ih = torrent.infoHashV1
        if (node != null && ih != null) {
            scope.launch {
                val result = node.getPeers(ih)
                session.connect(result.peers.map { PeerEndpoint(it.addressLiteral(), it.port) })
                node.announce(ih, listenPort)
            }
        }
        return session
    }

    /**
     * Start a download from a magnet link. Connects to [peers] (from the magnet's
     * trackers/DHT — discovery is the caller's or the engine's job), fetches the
     * metadata from the first cooperative peer via BEP-9 `ut_metadata`, builds a
     * [TorrentInfo], then begins a normal download with the disk [diskFactory] produces.
     * Returns null if no peer could supply the metadata.
     */
    suspend fun addMagnet(
        magnet: MagnetLink,
        peers: List<PeerEndpoint>,
        diskFactory: (TorrentInfo) -> DiskIo,
    ): TorrentSession? {
        val infoHash = magnet.infoHashV1 ?: return null
        for (peer in peers) {
            val infoBytes = fetchMetadataFrom(peer, infoHash) ?: continue
            // Splice the verified info bytes in verbatim (Preformatted) so the info-hash
            // is preserved exactly; attach the magnet's trackers.
            val root = Entry.dict()
            root.map["info"] = Entry.Preformatted(infoBytes)
            if (magnet.trackers.isNotEmpty()) {
                root.map["announce-list"] = Entry.ListEntry(
                    magnet.trackers.mapTo(ArrayList()) { Entry.ListEntry(mutableListOf(Entry.of(it))) },
                )
            }
            val torrent = TorrentInfo.parse(Bencode.encode(root))
            val session = addTorrent(torrent, diskFactory(torrent))
            session.connect(peers) // now download the data from the same peers
            return session
        }
        return null
    }

    private suspend fun fetchMetadataFrom(peer: PeerEndpoint, infoHash: Sha1Hash): ByteArray? {
        val conn = try {
            network.connectTcp(peer.host, peer.port)
        } catch (_: Exception) {
            return null
        }
        return try {
            val pc = PeerConnection(conn.asByteStream(), infoHash, peerId, numPieces = 0)
            pc.performHandshake()
            MetadataExchange(pc, infoHash).fetch()
        } catch (_: Exception) {
            null
        } finally {
            conn.close()
        }
    }

    /** The active torrent for [infoHash], or null. */
    fun torrentFor(infoHash: Sha1Hash): TorrentSession? = torrents[infoHash.toHex()]

    /** All active torrents. */
    fun torrents(): List<TorrentSession> = torrents.values.toList()

    /** Connections currently held against `connections_limit` (observability/tests). */
    suspend fun numConnections(): Int = connections.inUse()

    /** Live µTP streams (0 when µTP is disabled). */
    suspend fun numUtpStreams(): Int = utpManager?.numStreams() ?: 0

    /** Stop everything and release the sockets. */
    fun shutdown() {
        rateLimiter.close()
        utpManager?.close()
        utpManager = null
        network.close()
        torrents.clear()
        dht = null
    }
}
