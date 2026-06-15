package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.dht.CompactNode
import io.github.yuroyami.kitetorrent.dht.DhtEndpoint
import io.github.yuroyami.kitetorrent.dht.DhtMessage
import io.github.yuroyami.kitetorrent.dht.DhtStorage
import io.github.yuroyami.kitetorrent.dht.RoutingTable
import io.github.yuroyami.kitetorrent.peer.PeerAddress
import io.github.yuroyami.kitetorrent.session.net.DatagramTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A live Kademlia DHT node (BEP-5) — the networked half of the DHT, wiring the pure
 * [RoutingTable] / [DhtMessage] / [DhtStorage] / [DhtItem] types to a UDP socket via
 * [DhtRpc] and [TraversalState]. Port of `dht::node` + `dht_tracker` + the
 * `find_data`/`get_peers` traversal algorithms.
 *
 * Drives the two things a client actually needs from the DHT: trackerless peer
 * discovery ([getPeers]) and re-announcing ourselves ([announce]); plus it answers
 * inbound queries so we are a good citizen of the network.
 *
 * @param clock supplies epoch-seconds for storage expiry/token rotation — the core is
 *   clockless, so the platform passes one in (`{ System.currentTimeMillis()/1000 }`).
 */
class DhtNode(
    private val socket: DatagramTransport,
    val nodeId: Sha1Hash,
    private val scope: CoroutineScope,
    val routingTable: RoutingTable = RoutingTable(nodeId, K_BUCKET),
    val storage: DhtStorage = DhtStorage(),
    private val tokens: DhtTokens = DhtTokens(),
    private val clock: () -> Long = { 0L },
) {
    private val rpc = DhtRpc(socket)

    /** Peers found for an info-hash, plus the closest responders + their write tokens (for announce). */
    class GetPeersResult(
        val peers: List<DhtEndpoint>,
        val closestWithTokens: List<Pair<CompactNode, ByteArray>>,
    )

    /** Start the inbound receive loop. Responses are routed to RPC waiters; queries are answered. */
    fun start(): Job = scope.launch {
        while (isActive) {
            val pkt = try {
                socket.receive()
            } catch (_: Throwable) {
                break // socket closed
            }
            val msg = rpc.parseAndDispatch(pkt.data) ?: continue
            if (msg is DhtMessage.Query) {
                runCatching { handleQuery(msg, pkt.host, pkt.port) }
            }
        }
    }

    // --- client side -------------------------------------------------------------

    /** Ping the router nodes, pull their neighbours, then self-lookup to fill the table. */
    suspend fun bootstrap(routers: List<Pair<String, Int>>) {
        coroutineScope {
            routers.map { (h, p) ->
                async {
                    val resp = rpc.query(h, p) { tid -> DhtMessage.buildFindNodeQuery(tid, nodeId, nodeId) }
                    if (resp != null) absorbNodes(resp, h, p)
                }
            }.awaitAll()
        }
        lookup(nodeId) // populate the routing table around our own id
    }

    /** Iterative get_peers traversal toward [infoHash]; returns the swarm peers it found. */
    suspend fun getPeers(infoHash: Sha1Hash, maxRounds: Int = 12): GetPeersResult {
        val state = TraversalState(infoHash)
        seed(state, infoHash)

        val peers = LinkedHashSet<DhtEndpoint>()
        val tokenByNode = HashMap<Sha1Hash, ByteArray>()
        var rounds = 0
        while (!state.isDone() && rounds++ < maxRounds) {
            val batch = state.next()
            if (batch.isEmpty()) break
            coroutineScope {
                batch.map { c ->
                    async {
                        val ep = c.node.endpoint
                        val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            DhtMessage.buildGetPeersQuery(tid, nodeId, infoHash)
                        }
                        if (resp == null) {
                            state.fail(c)
                        } else {
                            state.alive(c)
                            routingTable.nodeSeen(resp.nodeId, ep.addressLiteral(), ep.port, RTT_OK)
                            val reply = resp.reply
                            if (reply is DhtMessage.Reply.Peers) {
                                peers.addAll(reply.values)
                                state.addAll(reply.nodes)
                                reply.token?.let { tokenByNode[c.node.id] = it }
                            } else if (reply is DhtMessage.Reply.Nodes) {
                                state.addAll(reply.nodes)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val closest = state.closestAlive().mapNotNull { node ->
            tokenByNode[node.id]?.let { node to it }
        }
        return GetPeersResult(peers.toList(), closest)
    }

    /** Announce that we are a peer for [infoHash] on [port] to the closest nodes that gave us a token. */
    suspend fun announce(infoHash: Sha1Hash, port: Int) {
        val result = getPeers(infoHash)
        coroutineScope {
            result.closestWithTokens.map { (node, token) ->
                async {
                    val ep = node.endpoint
                    rpc.query(ep.addressLiteral(), ep.port) { tid ->
                        DhtMessage.buildAnnouncePeerQuery(tid, nodeId, infoHash, port, token)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun lookup(target: Sha1Hash, maxRounds: Int = 12) {
        val state = TraversalState(target)
        seed(state, target)
        var rounds = 0
        while (!state.isDone() && rounds++ < maxRounds) {
            val batch = state.next()
            if (batch.isEmpty()) break
            coroutineScope {
                batch.map { c ->
                    async {
                        val ep = c.node.endpoint
                        val resp = rpc.query(ep.addressLiteral(), ep.port) { tid ->
                            DhtMessage.buildFindNodeQuery(tid, nodeId, target)
                        }
                        if (resp == null) state.fail(c) else {
                            state.alive(c)
                            absorbNodes(resp, ep.addressLiteral(), ep.port)
                            (resp.reply as? DhtMessage.Reply.Nodes)?.let { state.addAll(it.nodes) }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun seed(state: TraversalState, target: Sha1Hash) {
        for (entry in routingTable.findNode(target, K_BUCKET)) {
            val ep = DhtEndpoint.of(entry.host, entry.port) ?: continue
            state.add(CompactNode(entry.id, ep))
        }
    }

    private fun absorbNodes(resp: DhtMessage.Response, host: String, port: Int) {
        routingTable.nodeSeen(resp.nodeId, host, port, RTT_OK)
        val nodes = when (val r = resp.reply) {
            is DhtMessage.Reply.Nodes -> r.nodes
            is DhtMessage.Reply.Peers -> r.nodes
            else -> emptyList()
        }
        for (n in nodes) routingTable.nodeSeen(n.id, n.endpoint.addressLiteral(), n.endpoint.port, RTT_UNKNOWN)
    }

    // --- server side: answer inbound queries -------------------------------------

    private suspend fun handleQuery(query: DhtMessage.Query, host: String, port: Int) {
        routingTable.nodeSeen(query.nodeId, host, port, RTT_UNKNOWN)
        val addrBytes = PeerAddress.parseOrNull(host)?.bytes ?: ByteArray(0)
        val tid = query.transactionId

        val response: io.github.yuroyami.kitetorrent.bencode.Entry = when (val a = query.args) {
            DhtMessage.Args.Ping -> DhtMessage.buildPingResponse(tid, nodeId)

            is DhtMessage.Args.FindNode ->
                DhtMessage.buildNodesResponse(tid, nodeId, closestCompact(a.target))

            is DhtMessage.Args.GetPeers -> {
                val token = tokens.generate(addrBytes, a.infoHash)
                val stored = storage.getPeers(a.infoHash, wantV4 = true, now = clock(), noseed = a.noseed)
                DhtMessage.buildPeersResponse(
                    tid, nodeId, token,
                    values = stored?.peers ?: emptyList(),
                    nodes = if (stored?.peers.isNullOrEmpty()) closestCompact(a.infoHash) else emptyList(),
                )
            }

            is DhtMessage.Args.AnnouncePeer -> {
                if (tokens.verify(a.token, addrBytes, a.infoHash)) {
                    val p = if (a.impliedPort) port else a.port
                    DhtEndpoint.of(host, p)?.let { storage.announcePeer(a.infoHash, it, clock(), a.seed, a.name) }
                    DhtMessage.buildPingResponse(tid, nodeId) // bare {id} ack
                } else {
                    DhtMessage.buildError(tid, "bad token", 203)
                }
            }

            else -> DhtMessage.buildError(tid, "method unknown", 204)
        }
        socket.send(Bencode.encode(response), host, port)
    }

    private fun closestCompact(target: Sha1Hash): List<CompactNode> =
        routingTable.findNode(target, K_BUCKET).mapNotNull { e ->
            DhtEndpoint.of(e.host, e.port)?.let { CompactNode(e.id, it) }
        }

    companion object {
        const val K_BUCKET = 8
        private const val RTT_OK = 50
        private const val RTT_UNKNOWN = 0xffff
    }
}
