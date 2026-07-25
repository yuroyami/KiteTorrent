package io.github.yuroyami.kitetorrent.session.engine

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash
import io.github.yuroyami.kitetorrent.alert.Alert
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.dht.generateRandomId
import io.github.yuroyami.kitetorrent.generatePeerId
import io.github.yuroyami.kitetorrent.peer.IpFilter
import io.github.yuroyami.kitetorrent.protocol.Handshake
import io.github.yuroyami.kitetorrent.session.dht.DhtNode
import io.github.yuroyami.kitetorrent.session.disk.DiskIo
import io.github.yuroyami.kitetorrent.session.net.HttpProxyConfig
import io.github.yuroyami.kitetorrent.session.net.IgdControl
import io.github.yuroyami.kitetorrent.session.net.NatPmp
import io.github.yuroyami.kitetorrent.session.net.NetworkRuntime
import io.github.yuroyami.kitetorrent.session.net.Socks4Config
import io.github.yuroyami.kitetorrent.session.net.Socks5Config
import io.github.yuroyami.kitetorrent.session.net.Socks5UdpAssociation
import io.github.yuroyami.kitetorrent.session.net.Upnp
import io.github.yuroyami.kitetorrent.session.net.UpnpMapping
import io.github.yuroyami.kitetorrent.session.net.UdpSocket
import io.github.yuroyami.kitetorrent.session.net.UtpSocketManager
import io.github.yuroyami.kitetorrent.session.net.UtpStream
import io.github.yuroyami.kitetorrent.session.net.bindTcp
import io.github.yuroyami.kitetorrent.session.net.bindUdp
import io.github.yuroyami.kitetorrent.session.net.connectTcp
import io.github.yuroyami.kitetorrent.session.net.socks5UdpAssociate
import io.github.yuroyami.kitetorrent.session.peer.ByteStream
import io.github.yuroyami.kitetorrent.session.peer.MetadataExchange
import io.github.yuroyami.kitetorrent.session.peer.Mse
import io.github.yuroyami.kitetorrent.session.peer.PeerConnection
import io.github.yuroyami.kitetorrent.session.peer.PrefixedByteStream
import io.github.yuroyami.kitetorrent.session.peer.asByteStream
import io.github.yuroyami.kitetorrent.settings.BoolSetting
import io.github.yuroyami.kitetorrent.settings.EncPolicy
import io.github.yuroyami.kitetorrent.settings.ProxyType
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceRequest
import io.github.yuroyami.kitetorrent.session.tracker.AnnounceResponse
import io.github.yuroyami.kitetorrent.session.tracker.HttpTracker
import io.github.yuroyami.kitetorrent.session.tracker.PeerEndpoint
import io.github.yuroyami.kitetorrent.session.tracker.ScrapeResponse
import io.github.yuroyami.kitetorrent.session.tracker.TrackerEvent
import io.github.yuroyami.kitetorrent.session.tracker.UdpTracker
import io.github.yuroyami.kitetorrent.settings.IntSetting
import io.github.yuroyami.kitetorrent.settings.SettingsPack
import io.github.yuroyami.kitetorrent.settings.StringSetting
import io.github.yuroyami.kitetorrent.torrent.MagnetLink
import io.github.yuroyami.kitetorrent.torrent.TorrentInfo
import io.github.yuroyami.kitetorrent.torrent.resume.AddTorrentParams
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The top-level engine, the KiteTorrent equivalent of libtorrent's `session`. It owns
 * the shared [NetworkRuntime], the [RateLimiter], the [ConnectionBudget], an optional
 * [DhtNode] and [UtpSocketManager], and the set of active [TorrentSession]s. This is
 * the single object an app talks to.
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
 * that socket through the manager's demultiplexer. This is libtorrent's
 * single-listen-socket model.
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
    /**
     * Optional ktor [HttpClient] used to drive the UPnP IGD SOAP control flow (port mapping). When
     * null, UPnP is skipped even if `enable_upnp` is set; NAT-PMP (which needs no HTTP) still runs
     * when [gateway] is supplied. Kept optional so existing callers compile unchanged.
     */
    private val httpClient: HttpClient? = null,
    /**
     * The LAN default-gateway IPv4 literal (e.g. `"192.168.1.1"`) for NAT-PMP. KMP common code has
     * no portable route-table query, so the platform supplies it; null disables NAT-PMP.
     */
    private val gateway: String? = null,
) {
    val network = NetworkRuntime(scope.coroutineContext)

    /** The session-wide bandwidth scheduler (the live `bandwidth_manager` wiring). */
    val rateLimiter = RateLimiter(scope)

    // The shared connection budget. A val per-budget (ConnectionBudget.limit is immutable in its
    // owning file), so a live connections_limit change recreates it for *future* torrents; the
    // current count and already-running sessions keep the previous budget (see setConnectionsLimit).
    private var connections = ConnectionBudget(settings.getInt(IntSetting.CONNECTIONS_LIMIT))

    /** Session-wide IP blocklist. Add ranges via [IpFilter.addRule]. Enforced on dial and accept. */
    val ipFilter = IpFilter()

    /**
     * Max concurrently-*active* (non-paused) torrents (`active_limit`); -1 = unlimited. Torrents
     * added past the limit are queued (paused), and the oldest queued one resumes whenever an
     * active torrent pauses. This is the port of libtorrent's auto-managed torrent queue.
     */
    var activeLimit: Int = -1
        set(value) { field = value; scope.launch { reconcileQueue() } }
    private val queueMutex = Mutex()
    private val managedOrder = ArrayList<TorrentSession>() // add order == queue priority (FIFO)
    private val queuePaused = HashSet<TorrentSession>()    // sessions WE paused for the queue
    private val torrents = LinkedHashMap<String, TorrentSession>()
    private var dht: DhtNode? = null
    private var utpManager: UtpSocketManager? = null

    /** The actual TCP port we're listening on (resolved after [start]; equals [listenPort] unless it was 0). */
    var boundListenPort: Int = listenPort
        private set

    // --- alert delivery (port of session_impl's alert_manager queue) ----------------
    private val alertLock = Mutex()
    private val alertQueue = ArrayDeque<Alert>()

    /** Bound on the buffered-alert backlog; oldest are dropped past this (alert_queue_size). */
    private val maxAlertQueue = 4096

    // --- NAT traversal bookkeeping --------------------------------------------------
    private var upnpIgd: IgdControl? = null
    private var upnpMapping: UpnpMapping? = null
    private var natpmp: NatPmp? = null
    private var natpmpTcpLifetime: Long = 0
    private var natTimerJob: Job? = null

    // --- DHT maintenance + UDP-proxy association ------------------------------------
    private var dhtMaintainJob: Job? = null
    private var socks5Udp: Socks5UdpAssociation? = null

    // --- external-IP voter (port of session_impl::ip_voter / external_ip) -----------
    private val ipVotes = HashMap<String, Int>()
    /** The current external-IP majority winner, fed to DHT secure-id + self-connect suppression. */
    var externalIp: String? = null
        private set

    init {
        rateLimiter.setUploadLimit(settings.getInt(IntSetting.UPLOAD_RATE_LIMIT))
        rateLimiter.setDownloadLimit(settings.getInt(IntSetting.DOWNLOAD_RATE_LIMIT))
    }

    /** True when libtorrent's `anonymous_mode` is set: force-proxy, suppress identity, gate listen/LSD. */
    private fun anonymousMode(): Boolean = settings.getBool(BoolSetting.ANONYMOUS_MODE)

    /** Bring up the listen socket(s) and the DHT (if enabled). Call [bootstrapDht] to join the DHT. */
    suspend fun start() {
        // map settings_pack PROXY_* onto the runtime proxy before any dial happens.
        applyProxyFromSettings()
        // anonymous_mode: refuse to even open a listen socket (no inbound, no identifying port).
        if (!anonymousMode()) startListening()
        rateLimiter.start()
        // UDP (µTP + DHT) binds the same port number TCP resolved to, so peers reach
        // both transports on the one advertised port. This is libtorrent's listen model.
        // When a SOCKS5 proxy is configured we first open the UDP ASSOCIATE relay and route the
        // shared socket through it, so the real destination IP is hidden (libtorrent's
        // udp_socket wraps/unwraps on the same listen socket when a socks5 relay is up). The relay
        // socket *is* the shared uTP/DHT socket: one physical socket carries the wrapped traffic.
        if (enableUtp || enableDht) {
            val shared = openSharedUdpSocket()
            if (enableUtp && utpManager == null) {
                utpManager = UtpSocketManager(shared, scope).also { mgr ->
                    mgr.onIncomingConnection = { stream -> acceptUtpPeer(mgr, stream) }
                    mgr.start()
                }
            }
            if (enableDht && dht == null) {
                // share the µTP socket when there is one (session_impl routes both over the listen
                // socket); otherwise the shared socket goes straight to the DHT.
                val transport = utpManager?.dhtTransport() ?: shared
                dht = DhtNode(transport, generateRandomId(), scope, clock = clock).also { it.start() }
                startDhtMaintenance()
            }
        }
        // NAT traversal (UPnP + NAT-PMP). Never in anonymous mode: it would reveal our presence.
        if (!anonymousMode()) startNatTraversal()
    }

    /**
     * The bind address for the listen sockets, honouring `listen_interfaces`: the first interface's
     * address when set (and a literal), else the IPv4 wildcard. A bare `"[::]"`/`"::"` requests the
     * IPv6 dual-stack wildcard (libtorrent's `listen_interface` address-family selection).
     */
    private fun listenBindHost(): String {
        val ifaces = settings.getString(StringSetting.LISTEN_INTERFACES)
        if (ifaces.isBlank()) return "0.0.0.0"
        // listen_interfaces is "addr:port[,addr:port…]"; take the first address component.
        val first = ifaces.split(',').firstOrNull()?.trim() ?: return "0.0.0.0"
        return when {
            first.startsWith("[") -> first.substringAfter('[').substringBefore(']') // [::]:port
            first == "::" -> "::"
            else -> first.substringBeforeLast(':', first).ifBlank { "0.0.0.0" }
        }
    }

    /**
     * Route all outbound TCP peer/tracker dials through a SOCKS5 proxy (RFC 1928; RFC-1929
     * username/password auth when [username] is given). Pass null to go direct. UDP transports
     * (µTP/DHT) are not yet proxied. The port of `settings_pack`'s proxy_* knobs.
     */
    fun setSocks5Proxy(host: String?, port: Int = 0, username: String? = null, password: String? = null) {
        network.proxy = if (host == null) null else Socks5Config(host, port, username, password)
    }

    /** Route all outbound TCP dials through an HTTP CONNECT proxy (HTTP Basic auth when given). */
    fun setHttpProxy(host: String?, port: Int = 0, username: String? = null, password: String? = null) {
        network.proxy = if (host == null) null else HttpProxyConfig(host, port, username, password)
    }

    /** Route all outbound TCP dials through a SOCKS4/4a proxy ([username] = SOCKS4 USERID). */
    fun setSocks4Proxy(host: String?, port: Int = 0, username: String? = null) {
        network.proxy = if (host == null) null else Socks4Config(host, port, username)
    }

    /**
     * Map the `settings_pack` PROXY_* knobs (proxy_type / proxy_hostname / proxy_port /
     * proxy_username / proxy_password) onto [NetworkRuntime.proxy], the port of
     * `session_impl::update_proxy`. proxy_type NONE clears any proxy; SOCKS4/SOCKS5(/_pw)/HTTP(_pw)
     * build the matching [ProxyConfig]. Empty hostnames leave the proxy untouched (incomplete config).
     */
    fun applyProxyFromSettings() {
        val type = settings.getInt(IntSetting.PROXY_TYPE)
        val host = settings.getString(StringSetting.PROXY_HOSTNAME)
        val port = settings.getInt(IntSetting.PROXY_PORT)
        val user = settings.getString(StringSetting.PROXY_USERNAME).ifEmpty { null }
        val pass = settings.getString(StringSetting.PROXY_PASSWORD).ifEmpty { null }
        network.proxy = when (type) {
            ProxyType.NONE -> null
            ProxyType.SOCKS4 -> if (host.isBlank()) network.proxy else Socks4Config(host, port, user)
            ProxyType.SOCKS5, ProxyType.SOCKS5_PW ->
                if (host.isBlank()) network.proxy else Socks5Config(host, port, user, pass)
            ProxyType.HTTP, ProxyType.HTTP_PW ->
                if (host.isBlank()) network.proxy else HttpProxyConfig(host, port, user, pass)
            else -> network.proxy // i2p / unknown: leave as-is
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

    /**
     * Update the session-wide `connections_limit` live. The shared [ConnectionBudget] holds an
     * immutable limit (owned elsewhere), so this records the new value in [settings] and recreates
     * the budget for *future* torrents; torrents already running keep the prior budget object. Once
     * the owning class grows a `setLimit`, swap this to mutate in place. The port of
     * `session_impl::update_connections_limit`.
     */
    fun setConnectionsLimit(limit: Int) {
        settings.setInt(IntSetting.CONNECTIONS_LIMIT, limit)
        connections = ConnectionBudget(limit)
    }

    // --- live settings re-application (port of session_impl::apply_settings) ---------

    /**
     * Re-apply a (possibly partial) [pack] of settings to the running engine and its
     * sessions. This is the port of `session::apply_settings`. It copies every override in [pack] into this engine's
     * live [settings], then re-drives the knobs that are wired here: rate limits, connections_limit,
     * proxy_*, per-torrent unchoke slots and connection caps. Knobs read once at construction time
     * (request/choke timeouts) take effect for torrents added afterwards.
     */
    suspend fun applySettings(pack: SettingsPack) {
        pack.forEach { name, value ->
            when (value) {
                is Int -> settings.setInt(name, value)
                is Boolean -> settings.setBool(name, value)
                is String -> settings.setString(name, value)
            }
        }
        // rate limits
        rateLimiter.setUploadLimit(settings.getInt(IntSetting.UPLOAD_RATE_LIMIT))
        rateLimiter.setDownloadLimit(settings.getInt(IntSetting.DOWNLOAD_RATE_LIMIT))
        // connections_limit
        if (pack.hasVal(IntSetting.CONNECTIONS_LIMIT)) {
            setConnectionsLimit(settings.getInt(IntSetting.CONNECTIONS_LIMIT))
        }
        // proxy_* re-mapping (and tear the UDP relay if the proxy changed away from socks5)
        applyProxyFromSettings()
        if (network.proxy !is Socks5Config) { socks5Udp?.close(); socks5Udp = null }
        // per-torrent live knobs: unchoke slots (max_uploads) + connection cap (max_connections)
        val slots = settings.getInt(IntSetting.UNCHOKE_SLOTS_LIMIT)
        val connLimit = settings.getInt(IntSetting.CONNECTIONS_LIMIT)
        for (t in torrents.values) {
            if (pack.hasVal(IntSetting.UNCHOKE_SLOTS_LIMIT)) t.uploadSlots = slots
            if (pack.hasVal(IntSetting.CONNECTIONS_LIMIT) && connLimit > 0) {
                t.maxPeers = minOf(t.maxPeers, connLimit)
            }
        }
    }

    // --- alert delivery -------------------------------------------------------------

    /**
     * Buffer one alert (off the hot path). Called by each [TorrentSession.onAlert] hook. Honours the
     * `alert_mask` from [settings]: an alert whose [Alert.category] shares no bit with the mask is
     * dropped (mirroring `alert_manager::should_post`). The oldest are evicted past [maxAlertQueue].
     */
    fun postAlert(a: Alert) {
        val mask = settings.getInt(IntSetting.ALERT_MASK)
        if (mask != 0 && (a.category and mask) == 0) return // filtered out by alert_mask
        scope.launch {
            alertLock.withLock {
                alertQueue.addLast(a)
                while (alertQueue.size > maxAlertQueue) alertQueue.removeFirst()
            }
        }
    }

    /**
     * Drain and return all buffered alerts, oldest first (the port of `session::pop_alerts`). The
     * queue is emptied; a fresh batch accumulates for the next call.
     */
    suspend fun popAlerts(): List<Alert> = alertLock.withLock {
        if (alertQueue.isEmpty()) emptyList()
        else ArrayList<Alert>(alertQueue.size).also { it.addAll(alertQueue); alertQueue.clear() }
    }

    // --- session control ------------------------------------------------------------

    /** Pause every torrent (`session::pause`). Each torrent sends event=stopped and flushes. */
    suspend fun pauseSession() {
        for (t in torrents.values) runCatching { t.pause() }
    }

    /** Resume every torrent (`session::resume`). */
    suspend fun resumeSession() {
        for (t in torrents.values) runCatching { t.resume() }
    }

    /** Accept inbound TCP peers and route each to the matching torrent by info-hash. */
    private suspend fun startListening() {
        val server = network.bindTcp(listenPort, host = listenBindHost())
        boundListenPort = server.localPort
        scope.launch {
            while (isActive) {
                val conn = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                scope.launch { routeInbound(conn.asByteStream()) { conn.close() } }
            }
        }
    }

    /** Accept one inbound µTP peer: detect plaintext/MSE and route by info-hash. */
    private suspend fun acceptUtpPeer(mgr: UtpSocketManager, stream: UtpStream) {
        routeInbound(stream) { mgr.disconnect(stream) }
    }

    /**
     * Route one inbound connection (over any transport): peek the opening bytes, tell a
     * plaintext BitTorrent handshake from an MSE/PE DH key, honour `in_enc_policy`, and
     * hand the (possibly decrypting) stream to the matching torrent's accept path.
     */
    private suspend fun routeInbound(stream: ByteStream, close: suspend () -> Unit) {
        hostOf(stream.remoteAddress)?.let { host ->
            // IpFilter.access throws on a non-literal host; treat unfilterable hosts as allowed
            if (runCatching { ipFilter.access(host) }.getOrDefault(0) == IpFilter.BLOCKED) {
                runCatching { close() }; return // blocklisted
            }
        }
        val first = try {
            stream.readExactly(Handshake.LENGTH)
        } catch (_: Throwable) {
            runCatching { close() }; return
        }
        val inPolicy = settings.getInt(IntSetting.IN_ENC_POLICY)
        try {
            if (Mse.looksLikePlaintextHandshake(first)) {
                if (inPolicy == EncPolicy.PE_FORCED) { close(); return } // encryption required
                val hs = Handshake.decode(first)
                val session = hs?.let { torrents[it.infoHash.toHex()] }
                if (session == null) close() else session.acceptInbound(stream, close, hs)
            } else {
                if (inPolicy == EncPolicy.PE_DISABLED) { close(); return } // encryption refused
                val candidates = torrents.values.map { it.torrent.wireInfoHash() }
                val (enc, _) = Mse.accept(PrefixedByteStream(first, stream), candidates)
                val hs = Handshake.decode(enc.readExactly(Handshake.LENGTH))
                val session = hs?.let { torrents[it.infoHash.toHex()] }
                if (session == null) close() else session.acceptInbound(enc, close, hs)
            }
        } catch (_: Throwable) {
            runCatching { close() }
        }
    }

    /** Join the DHT via well-known router nodes (e.g. `router.bittorrent.com:6881`). */
    suspend fun bootstrapDht(routers: List<Pair<String, Int>>) {
        dht?.bootstrap(routers)
    }

    /**
     * Add a torrent and begin downloading it. Announces to trackers immediately. When the
     * DHT is running it also starts a `get_peers` lookup and feeds those results in.
     *
     * Pass [resume] (from [TorrentSession.saveResumeData] / `ResumeData.read`) to restore a
     * previous run's progress without re-hashing the data on disk. This is the fast-resume path.
     */
    suspend fun addTorrent(
        torrent: TorrentInfo,
        disk: DiskIo,
        resume: AddTorrentParams? = null,
    ): TorrentSession {
        // anonymous_mode: use a fresh per-torrent random peer-id (no engine-wide identifying id).
        val sessionPeerId = if (anonymousMode()) generateRandomId() else peerId
        val session = TorrentSession(
            torrent = torrent,
            disk = disk,
            network = network,
            scope = scope,
            peerId = sessionPeerId,
            listenPort = listenPort,
            httpTracker = httpTracker,
            settings = settings,
            utp = utpManager,
            limiter = rateLimiter,
            torrentBandwidth = rateLimiter.newTorrentChannels(),
            connections = connections,
            resumeData = resume,
            ipFilter = ipFilter,
        )
        // funnel per-torrent events into the session-wide alert queue.
        session.onAlert = { a -> postAlert(a) }
        // feed a BEP-5 `port` peer's advertised UDP node to the DHT, if any.
        session.onDhtPort = { host, port -> scope.launch { runCatching { feedDhtNode(host, port) } } }
        // key by the 20-byte WIRE info-hash (v1, or truncated v2). Inbound handshakes carry
        // that hash, so v1, hybrid and v2-only torrents all route correctly
        torrents[torrent.wireInfoHash().toHex()] = session
        session.start()

        registerForQueue(session)

        val node = dht
        // the DHT keys torrents by the 20-byte hash: v1, or truncated v2 for a v2-only torrent
        val ih = torrent.wireInfoHash()
        if (node != null) {
            scope.launch {
                // a failure here must not kill the coroutine and lose the announce
                runCatching {
                    val result = node.getPeers(ih)
                    session.connect(result.peers.map { PeerEndpoint(it.addressLiteral(), it.port) })
                    node.announce(ih, listenPort)
                }
            }
        }
        return session
    }

    /**
     * Enrol [session] in the auto-managed queue: hook its state changes (via the internal
     * hook, so the app's [TorrentSession.onStateChanged] stays free) to re-reconcile, then
     * reconcile once. The hook schedules reconcile *asynchronously* so a pause/resume issued
     * by reconcile can't re-enter [reconcileQueue] and deadlock.
     */
    private suspend fun registerForQueue(session: TorrentSession) {
        session.onStateChangedInternal = { scope.launch { reconcileQueue() } }
        queueMutex.withLock { managedOrder.add(session) }
        reconcileQueue()
    }

    /**
     * Bring the active-torrent count in line with [activeLimit]: pause the newest running
     * torrents over the limit (queue them), and resume the oldest queue-paused ones when slots
     * free. Idempotent and serialized by [queueMutex]: decisions are made under the lock,
     * and pause or resume is applied after release. A torrent the app paused itself stays paused (it's
     * never in [queuePaused], so reconcile won't resume it) and its freed slot goes to the queue.
     */
    private suspend fun reconcileQueue() {
        if (activeLimit < 0) return
        val toPause = ArrayList<TorrentSession>()
        val toResume = ArrayList<TorrentSession>()
        queueMutex.withLock {
            managedOrder.retainAll { torrents.containsValue(it) } // drop removed torrents
            val running = managedOrder.filter { it.state != TorrentState.PAUSED }
            val over = running.size - activeLimit
            when {
                over > 0 -> running.takeLast(over).forEach { toPause.add(it); queuePaused.add(it) }
                over < 0 -> managedOrder.filter { it in queuePaused }.take(-over)
                    .forEach { toResume.add(it); queuePaused.remove(it) }
            }
        }
        for (s in toPause) runCatching { s.pause() }
        for (s in toResume) runCatching { s.resume() }
    }

    /**
     * Start a download from a magnet link. Connects to [peers] (from the magnet's
     * trackers or the DHT, since discovery is the caller's or the engine's job), fetches the
     * metadata from the first cooperative peer via BEP-9 `ut_metadata`, builds a
     * [TorrentInfo], then begins a normal download with the disk [diskFactory] produces.
     * Returns null if no peer could supply the metadata.
     */
    suspend fun addMagnet(
        magnet: MagnetLink,
        peers: List<PeerEndpoint>,
        diskFactory: (TorrentInfo) -> DiskIo,
    ): TorrentSession? {
        // v1-or-v2: a btmh-only (v2) magnet has no v1 hash, so fetch and verify against the v2 SHA-256.
        // For a hybrid magnet prefer v1 (the metadata verifies against either; v1 is the wire hash).
        val verifyHash: Digest32 = magnet.infoHashV1 ?: magnet.infoHashV2 ?: return null
        // the hash we present to the wire/peer-connection is always the 20-byte form.
        val wireHash: Sha1Hash = magnet.infoHashV1 ?: truncateToWire(magnet.infoHashV2!!)
        for (peer in peers) {
            val infoBytes = fetchMetadataFrom(peer, wireHash, verifyHash) ?: continue
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

    /**
     * Convenience magnet add with built-in peer discovery. Pools peers for the magnet's
     * info-hash from the DHT (when [enableDht]) and from the magnet's trackers, then
     * delegates to the peer-list [addMagnet] overload, which fetches the metadata via
     * BEP-9 `ut_metadata` and starts the download. Returns null if no peer was reachable
     * or none could supply the metadata.
     */
    suspend fun addMagnet(
        magnet: MagnetLink,
        diskFactory: (TorrentInfo) -> DiskIo,
    ): TorrentSession? {
        // accept a btmh-only (v2) magnet: discover against the 20-byte wire hash (v1, or truncated v2).
        if (magnet.infoHashV1 == null && magnet.infoHashV2 == null) return null
        val wireHash: Sha1Hash = magnet.infoHashV1 ?: truncateToWire(magnet.infoHashV2!!)
        val peers = discoverPeers(wireHash, magnet.trackers)
        if (peers.isEmpty()) return null
        return addMagnet(magnet, peers, diskFactory)
    }

    /** The 20-byte wire and DHT form of a v2 (SHA-256) info-hash: the v2 hash truncated (BEP-52). */
    private fun truncateToWire(v2: Sha256Hash): Sha1Hash =
        Digest32.sha1(v2.toByteArray().copyOf(Digest32.SHA1_SIZE))

    /**
     * Best-effort peer discovery for [infoHash]: a DHT `get_peers` lookup (when the DHT
     * is up) unioned with announces to [trackers] (http/https via the injected
     * [HttpTracker], udp via an ephemeral [UdpTracker]). Any single source failing is
     * swallowed; the deduplicated union is returned.
     */
    suspend fun discoverPeers(
        infoHash: Sha1Hash,
        trackers: List<String> = emptyList(),
    ): List<PeerEndpoint> {
        val pooled = LinkedHashSet<PeerEndpoint>()

        dht?.let { node ->
            runCatching { node.getPeers(infoHash) }.getOrNull()?.peers?.forEach {
                pooled += PeerEndpoint(it.addressLiteral(), it.port)
            }
        }

        if (trackers.isNotEmpty()) {
            val req = AnnounceRequest(
                infoHash = infoHash,
                peerId = peerId,
                port = boundListenPort,
                uploaded = 0L,
                downloaded = 0L,
                left = Long.MAX_VALUE, // metadata not yet known, so announce as a leecher
                event = TrackerEvent.STARTED,
            )
            for (url in trackers) {
                runCatching { announceForPeers(url, req) }.getOrNull()?.let { pooled += it.peers }
            }
        }

        return pooled.toList()
    }

    /**
     * Scrape swarm statistics (seeders / completed / leechers) for [infoHash] from the first
     * responsive entry in [trackers]: UDP via an ephemeral socket (BEP-15), HTTP via the
     * injected client (BEP-48). Returns null if no tracker answered. The counterpart of
     * libtorrent's `tracker_manager` scrape path.
     */
    suspend fun scrape(infoHash: Sha1Hash, trackers: List<String>): ScrapeResponse? {
        val req = AnnounceRequest(
            infoHash = infoHash, peerId = peerId, port = boundListenPort,
            uploaded = 0L, downloaded = 0L, left = 0L,
        )
        for (url in trackers) {
            val resp = runCatching {
                when {
                    url.startsWith("udp://", ignoreCase = true) -> {
                        val hp = parseUdpHostPort(url) ?: return@runCatching null
                        val sock = bindUdpTracker()
                        try {
                            UdpTracker(sock).scrape(hp.first, hp.second, req)
                        } finally {
                            sock.close()
                        }
                    }
                    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
                        httpTracker?.scrape(url, listOf(infoHash))?.get(infoHash)
                    else -> null
                }
            }.getOrNull()
            if (resp != null) return resp
        }
        return null
    }

    /** Route an announce to the right tracker transport by URL scheme; null if unsupported. */
    private suspend fun announceForPeers(url: String, req: AnnounceRequest): AnnounceResponse? = when {
        url.startsWith("udp://", ignoreCase = true) -> {
            val hostPort = parseUdpHostPort(url) ?: return null
            val sock = bindUdpTracker()
            try {
                UdpTracker(sock).announce(hostPort.first, hostPort.second, req)
            } finally {
                sock.close()
            }
        }
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) ->
            httpTracker?.announce(url, req)
        else -> null
    }

    /**
     * Bind an ephemeral UDP socket for a one-shot UDP-tracker announce/scrape, routing it through
     * the proxy when one is configured: for a SOCKS5 proxy a fresh UDP ASSOCIATE relay is opened
     * over this socket (its own short-lived control connection, torn down when the socket closes)
     * so the tracker datagrams are wrapped and the real tracker IP is hidden. libtorrent runs
     * UDP trackers over the same proxied `udp_socket` path. Without a SOCKS5 proxy the plain socket is
     * returned unchanged. The proxied view shares this socket, so closing it ends the association.
     */
    private suspend fun bindUdpTracker(): UdpSocket {
        val raw = network.bindUdp(0)
        val cfg = network.proxy as? Socks5Config ?: return raw
        val control = runCatching { network.connectTcp(cfg.host, cfg.port, bypassProxy = true) }.getOrNull() ?: return raw
        val assoc = runCatching { socks5UdpAssociate(control, raw, cfg) }.getOrNull()
            ?: run { control.close(); return raw }
        return raw.proxiedThrough(assoc)
    }

    /** Extract the host from a `host:port` / `[v6]:port` remote-address string. */
    private fun hostOf(remoteAddress: String): String? {
        // ktor/Java socket addresses stringify as "/127.0.0.1:54321", so drop the leading slash
        val s = remoteAddress.removePrefix("/")
        if (s.startsWith("[")) return s.substringAfter('[').substringBefore(']')
        val host = s.substringBeforeLast(':', s)
        return host.ifEmpty { null }
    }

    /** Pull host + port out of a `udp://host:port/...` tracker URL. */
    private fun parseUdpHostPort(url: String): Pair<String, Int>? {
        val rest = url.substringAfter("://").substringBefore('/')
        val idx = rest.lastIndexOf(':')
        if (idx <= 0 || idx == rest.length - 1) return null
        val host = rest.substring(0, idx)
        val port = rest.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }

    /**
     * Fetch the `info` dict from [peer] via BEP-9: handshake under the 20-byte [wireHash] (what the
     * peer expects on the wire), then verify the assembled metadata against [verifyHash]. That is
     * the v1 SHA-1 for a v1 or hybrid magnet, and the v2 SHA-256 for a btmh-only magnet ([MetadataExchange]
     * dispatches on digest width). Defaults [verifyHash] to [wireHash] for the v1 callers.
     */
    private suspend fun fetchMetadataFrom(
        peer: PeerEndpoint,
        wireHash: Sha1Hash,
        verifyHash: Digest32 = wireHash,
    ): ByteArray? {
        val conn = try {
            network.connectTcp(peer.host, peer.port)
        } catch (_: Exception) {
            return null
        }
        return try {
            val pc = PeerConnection(conn.asByteStream(), wireHash, peerId, numPieces = 0)
            pc.performHandshake()
            MetadataExchange(pc, verifyHash).fetch()
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

    // --- DHT-port feed --------------------------------------------------------------

    /** Introduce a DHT node learned from a peer's BEP-5 `port` message into our routing table. */
    private suspend fun feedDhtNode(host: String, port: Int) {
        if (port !in 1..0xffff) return
        dht?.bootstrap(listOf(host to port))
    }

    // --- external-IP voter (session_impl::external_ip / ip_voter) -------------------

    /**
     * Record an external-IP observation (from a DHT `ip` reply, a tracker's `external ip`, or a
     * port-mapper's reported address) into the majority vote. When a new winner emerges it is
     * adopted as [externalIp] and propagated to the DHT secure-id derivation (BEP-42). Minimal
     * inline [io.github.yuroyami.kitetorrent.session.dht] `ip_voter`: the address with the
     * most votes wins.
     */
    fun voteExternalIp(address: String) {
        if (address.isBlank()) return
        val n = (ipVotes[address] ?: 0) + 1
        ipVotes[address] = n
        val winner = ipVotes.maxByOrNull { it.value }?.key ?: return
        if (winner != externalIp) {
            externalIp = winner
            // re-derive a BEP-42 secure node id around the new external address.
            runCatching { dht?.enableSecureId(winner, enforce = false) }
        }
    }

    // --- SOCKS5 UDP ASSOCIATE (route uTP/DHT/UDP-tracker UDP through the proxy) ------

    /**
     * Open the single UDP socket the engine shares between µTP and the DHT, bound to the resolved
     * listen port (libtorrent's one-listen-socket model). When the configured proxy is SOCKS5 it
     * first negotiates a UDP ASSOCIATE relay *over that very socket* and returns a proxied view that
     * SOCKS5-wraps every send and unwraps every receive, so the destination IP never leaves
     * the host in clear. Any proxy-negotiation failure falls back to the direct socket (best-effort, matching
     * the rest of start()'s `runCatching` posture), keeping the non-proxy path byte-identical.
     */
    private suspend fun openSharedUdpSocket(): UdpSocket {
        val raw = network.bindUdp(boundListenPort, host = listenBindHost())
        val cfg = network.proxy as? Socks5Config ?: return raw
        val assoc = runCatching { ensureSocks5Udp(raw) }.getOrNull() ?: return raw
        return raw.proxiedThrough(assoc)
    }

    /**
     * Lazily open a SOCKS5 UDP ASSOCIATE relay over the configured proxy so UDP transports
     * (uTP/DHT/UDP-trackers) can be tunnelled, the port of libtorrent's `socks5::socks_forward_udp`.
     * Returns the live association (also stored for teardown), or null if the proxy isn't SOCKS5 or
     * the negotiation failed. The relay socket and control connection are owned by the association.
     *
     * [relay] is the UDP socket the wrapped datagrams physically transit; when the engine wires the
     * shared listen socket through the proxy it passes that socket here so there is one physical
     * socket. Callers with no socket of their own omit it and an ephemeral relay is bound.
     */
    suspend fun ensureSocks5Udp(
        relay: UdpSocket? = null,
    ): Socks5UdpAssociation? {
        socks5Udp?.let { return it }
        val cfg = network.proxy as? Socks5Config ?: return null
        return runCatching {
            // the control connection reaches the proxy itself, so dial it DIRECTLY, never through
            // the proxy we are about to establish (that would make the negotiation recurse forever).
            val control = network.connectTcp(cfg.host, cfg.port, bypassProxy = true)
            val relaySock = relay ?: network.bindUdp(0)
            socks5UdpAssociate(control, relaySock, cfg).also { socks5Udp = it }
        }.getOrNull()
    }

    // --- NAT traversal: UPnP + NAT-PMP (session_impl start_natpmp/start_upnp) -------

    /**
     * Discover the gateway and open TCP+UDP listen-port mappings via UPnP (when an [httpClient] is
     * available) and NAT-PMP (when a [gateway] literal was supplied), then schedule a lease-refresh
     * timer at roughly half the lease so the mapping never lapses. Best-effort; failures are
     * swallowed. Any external address learned from NAT-PMP feeds the IP voter.
     */
    private fun startNatTraversal() {
        val wantUpnp = settings.getBool(BoolSetting.ENABLE_UPNP)
        val wantNatpmp = settings.getBool(BoolSetting.ENABLE_NATPMP)
        if (!wantUpnp && !wantNatpmp) return
        scope.launch {
            var leaseSeconds = Upnp.DEFAULT_LEASE_SECONDS
            val client = httpClient
            // UPnP: SSDP-discover an IGD then map TCP+UDP for our listen port.
            if (wantUpnp && client != null) {
                runCatching {
                    val ssdp = network.bindUdp(0)
                    try {
                        val igd = Upnp.discoverIgd(client, ssdp)
                        if (igd != null) {
                            upnpIgd = igd
                            val lease = settings.getInt(IntSetting.UPNP_LEASE_DURATION)
                                .takeIf { it > 0 } ?: Upnp.DEFAULT_LEASE_SECONDS
                            leaseSeconds = lease
                            upnpMapping = Upnp.mapTcpAndUdp(
                                client, igd,
                                externalPort = boundListenPort,
                                internalPort = boundListenPort,
                                internalClient = listenBindHost(),
                                leaseSeconds = lease,
                            )
                        }
                    } finally {
                        ssdp.close()
                    }
                }
            }
            // NAT-PMP: query the gateway external address (vote it) and map TCP+UDP.
            val gw = gateway
            if (wantNatpmp && gw != null) {
                runCatching {
                    val sock = network.bindUdp(0)
                    val pmp = NatPmp(sock, gw).also { natpmp = it }
                    pmp.externalAddress()?.let { voteExternalIp(it) }
                    val tcp = pmp.mapDetailed(boundListenPort, boundListenPort, tcp = true)
                    val udp = pmp.mapDetailed(boundListenPort, boundListenPort, tcp = false)
                    val lt = listOfNotNull(tcp?.lifetimeSeconds, udp?.lifetimeSeconds).minOrNull()
                    if (lt != null && lt > 0) {
                        natpmpTcpLifetime = lt
                        leaseSeconds = minOf(leaseSeconds.toLong(), lt).toInt()
                    }
                }
            }
            scheduleNatRefresh(leaseSeconds)
        }
    }

    /** Re-issue the port mappings at ~half the lease so they never expire (upnp::update_map cadence). */
    private fun scheduleNatRefresh(leaseSeconds: Int) {
        natTimerJob?.cancel()
        val half = maxOf(60L, leaseSeconds / 2L)
        natTimerJob = scope.launch {
            try {
                while (isActive) {
                    delay(half * 1000)
                    val igd = upnpIgd
                    val client = httpClient
                    if (igd != null && client != null) {
                        runCatching {
                            upnpMapping = Upnp.mapTcpAndUdp(
                                client, igd,
                                externalPort = boundListenPort,
                                internalPort = boundListenPort,
                                internalClient = listenBindHost(),
                                leaseSeconds = settings.getInt(IntSetting.UPNP_LEASE_DURATION)
                                    .takeIf { it > 0 } ?: Upnp.DEFAULT_LEASE_SECONDS,
                            )
                        }
                    }
                    natpmp?.let { pmp ->
                        runCatching {
                            pmp.map(boundListenPort, boundListenPort, tcp = true)
                            pmp.map(boundListenPort, boundListenPort, tcp = false)
                        }
                    }
                }
            } catch (_: Throwable) { /* scope cancelled */ }
        }
    }

    /** Delete any NAT mappings we created (best-effort) and stop the refresh timer. */
    private suspend fun teardownNatTraversal() {
        natTimerJob?.cancel(); natTimerJob = null
        val mapping = upnpMapping
        val client = httpClient
        if (mapping != null && client != null) {
            runCatching { Upnp.removeMapping(client, mapping) }
        }
        upnpMapping = null
        // NAT-PMP delete = a map request with lifetime 0.
        natpmp?.let { pmp ->
            runCatching {
                pmp.map(boundListenPort, boundListenPort, tcp = true, lifetimeSeconds = 0)
                pmp.map(boundListenPort, boundListenPort, tcp = false, lifetimeSeconds = 0)
            }
        }
        natpmp = null
    }

    // --- DHT maintenance + state persistence ----------------------------------------

    /**
     * Drive [DhtNode.maintain] (self-refresh, bucket refresh, token rotation, storage expiry,
     * DoS-window reset) on a periodic tick, and re-announce all active torrents on the DHT
     * announce interval. This is the port of session_impl's `dht_announce` and `node::tick` cadence.
     */
    private fun startDhtMaintenance() {
        if (dhtMaintainJob != null) return
        dhtMaintainJob = scope.launch {
            var sinceAnnounce = 0
            try {
                while (isActive) {
                    delay(DHT_MAINTAIN_INTERVAL_MS)
                    val node = dht ?: continue
                    runCatching { node.maintain() }
                    sinceAnnounce++
                    if (sinceAnnounce >= DHT_REANNOUNCE_EVERY_TICKS) {
                        sinceAnnounce = 0
                        val hashes = torrents.values.map { it.torrent.wireInfoHash() }
                        if (hashes.isNotEmpty()) runCatching { node.reannounce(hashes, listenPort) }
                    }
                }
            } catch (_: Throwable) { /* scope cancelled */ }
        }
    }

    /** Serialize live DHT routing state for the platform to persist; null when the DHT is off. */
    fun saveDhtState(): ByteArray? = dht?.serialize()

    /** Re-seed the DHT routing table from a [saveDhtState] blob; no-op when the DHT is off. */
    fun restoreDhtState(data: ByteArray) {
        dht?.restore(data)
    }

    /**
     * Graceful async teardown: delete NAT mappings, persist DHT state (returned for the caller to
     * write), shut every torrent down cleanly, close the proxy UDP relay, then release sockets.
     * Complements the synchronous [shutdown]. Returns the serialized DHT state (or null).
     */
    suspend fun shutdownGraceful(): ByteArray? {
        val dhtState = saveDhtState()
        teardownNatTraversal()
        dhtMaintainJob?.cancel(); dhtMaintainJob = null
        for (t in torrents.values) runCatching { t.shutdown() }
        socks5Udp?.close(); socks5Udp = null
        shutdown()
        return dhtState
    }

    /** Stop everything and release the sockets. */
    fun shutdown() {
        rateLimiter.close()
        natTimerJob?.cancel(); natTimerJob = null
        dhtMaintainJob?.cancel(); dhtMaintainJob = null
        socks5Udp?.close(); socks5Udp = null
        utpManager?.close()
        utpManager = null
        network.close()
        torrents.clear()
        managedOrder.clear()
        queuePaused.clear()
        dht = null
    }

    companion object {
        /** How often the engine drives [DhtNode.maintain] (libtorrent's ~6s node tick, scaled up). */
        const val DHT_MAINTAIN_INTERVAL_MS = 60_000L

        /** DHT re-announce cadence in maintenance ticks (~15 min at [DHT_MAINTAIN_INTERVAL_MS]). */
        const val DHT_REANNOUNCE_EVERY_TICKS = 15
    }
}
