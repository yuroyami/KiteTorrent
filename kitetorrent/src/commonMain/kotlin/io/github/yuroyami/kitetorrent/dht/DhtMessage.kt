package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.BdecodeNode
import io.github.yuroyami.kitetorrent.bencode.Entry

/**
 * The KRPC message model and codec for the BitTorrent DHT — a pure-Kotlin port of
 * the message-building and -parsing code spread across libtorrent's
 * `src/kademlia/node.cpp` (`incoming_request`, the per-query response builders, and
 * the single-bucket-refresh / ping query builders), `find_data.cpp`,
 * `get_peers.cpp`, `get_item.cpp`, `put_data.cpp` and `msg.cpp`.
 *
 * Every DHT packet is a bencoded dictionary with three or four top-level keys
 * (BEP-5 "KRPC Protocol"):
 *
 *  - `t` — the transaction id, an opaque byte string (libtorrent uses 2 random
 *    bytes, see `rpc_manager::invoke`). Echoed verbatim in the matching response.
 *  - `y` — the message type: `"q"` query, `"r"` response, `"e"` error.
 *  - `v` — an optional client-version string (not modelled here; libtorrent adds it
 *    at the transport layer).
 *  - `q` + `a` — for a query: the query name and its argument dict.
 *  - `r` — for a response: the response dict.
 *  - `e` — for an error: the `[code, message]` list (`incoming_error`).
 *
 * The argument/response dict for every query carries the sender's own node id under
 * `id` (`rpc_manager::add_our_id` / `node::incoming_request`'s `add_our_id(reply)`).
 *
 * This file provides:
 *  - a sealed [DhtMessage] hierarchy ([Query]/[Response]/[ErrorMessage]) with the
 *    typed argument/response payloads BEP-5/44/51 define,
 *  - `build*Query(...)` and `build*Response(...)` helpers returning an editable
 *    [Entry] (ready for `Bencode.encode`), matching the `e["y"]=…; e["a"][…]=…`
 *    assembly in libtorrent, and
 *  - [DhtMessage.parse], the dual of [verifyMessage]: it reads a decoded
 *    [BdecodeNode] envelope back into a typed [DhtMessage].
 *
 * Reuses [NodeId]/[Sha1Hash], [DhtEndpoint]/[CompactNode] (compact node info),
 * [ImmutableItem]/[MutableItem] (BEP-44 values) and [DhtKeySizes].
 */
sealed class DhtMessage {

    /** The transaction id (`t`) — opaque bytes, echoed between query and response. */
    abstract val transactionId: ByteArray

    /** The single character carried in `y`: 'q', 'r' or 'e'. */
    abstract val messageType: Char

    /**
     * A KRPC **query** (`y == "q"`) — `{t, y:"q", q:<name>, a:{id, …}}`. The concrete
     * argument payload is one of the nested [Args] types.
     */
    class Query(
        override val transactionId: ByteArray,
        /** The query name carried in `q` ("ping", "find_node", …). */
        val query: String,
        /** The sender's node id — `a["id"]`. */
        val nodeId: Sha1Hash,
        /** The typed `a` payload. */
        val args: Args,
        /** BEP-43 read-only flag `ro` (top-level, optional). */
        val readOnly: Boolean = false,
    ) : DhtMessage() {
        override val messageType: Char get() = 'q'
    }

    /**
     * A KRPC **response** (`y == "r"`) — `{t, y:"r", r:{id, …}}`. The typed `r`
     * payload is one of the [Reply] types; [raw] keeps the original response dict so
     * fields this model does not break out (`p`, `ip`, scrape bloom filters, …)
     * remain accessible.
     */
    class Response(
        override val transactionId: ByteArray,
        /** The responder's node id — `r["id"]`. */
        val nodeId: Sha1Hash,
        /** The typed `r` payload. */
        val reply: Reply,
        /** The raw `r` dict node, for fields not lifted into [reply]; null when built locally. */
        val raw: BdecodeNode? = null,
    ) : DhtMessage() {
        override val messageType: Char get() = 'r'
    }

    /**
     * A KRPC **error** (`y == "e"`) — `{t, y:"e", e:[code, message]}`, built by
     * libtorrent's `incoming_error`.
     */
    class ErrorMessage(
        override val transactionId: ByteArray,
        /** Numeric error code (201 generic, 203 protocol, 205 message-too-big, …). */
        val code: Int,
        /** Human-readable error message. */
        val message: String,
    ) : DhtMessage() {
        override val messageType: Char get() = 'e'
    }

    // --- query argument payloads -------------------------------------------------

    /**
     * The typed contents of a query's `a` dictionary, minus the always-present
     * `id`. Each subtype corresponds to one BEP-5/44/51 query.
     */
    sealed class Args {
        /** `ping` — no arguments beyond `id`. */
        object Ping : Args()

        /** `find_node {target}` — BEP-5. */
        class FindNode(val target: Sha1Hash) : Args()

        /**
         * `get_peers {info_hash}` — BEP-5. [noseed]/[scrape] are libtorrent's optional
         * extension flags (`a["noseed"]`, `a["scrape"]`).
         */
        class GetPeers(
            val infoHash: Sha1Hash,
            val noseed: Boolean = false,
            val scrape: Boolean = false,
        ) : Args()

        /**
         * `announce_peer {info_hash, port, token, implied_port?, seed?, n?}` — BEP-5.
         * When [impliedPort] is set the responder uses the UDP source port instead of
         * [port] (`a["implied_port"] = 1`).
         */
        class AnnouncePeer(
            val infoHash: Sha1Hash,
            val port: Int,
            val token: ByteArray,
            val impliedPort: Boolean = false,
            val seed: Boolean = false,
            val name: String? = null,
        ) : Args()

        /** `get {target, seq?}` — BEP-44. [seq] limits the reply to newer mutable items. */
        class Get(val target: Sha1Hash, val seq: Long? = null) : Args()

        /**
         * `put {token, v, [k, sig, seq, salt, cas]}` — BEP-44. [item] is the value to
         * store; [DhtItem.bencodedValue] is what travels as `a["v"]`, and the mutable
         * extras (`k`/`sig`/`seq`/`salt`) come from a [MutableItem]. [cas] is the
         * optional compare-and-swap expected sequence number.
         */
        class Put(
            val token: ByteArray,
            val item: DhtItem,
            val cas: Long? = null,
        ) : Args()
    }

    // --- response payloads -------------------------------------------------------

    /** The typed contents of a response's `r` dictionary, minus the always-present `id`. */
    sealed class Reply {
        /** A bare response carrying only `id` — what `ping` (and `announce_peer`) reply with. */
        object Pong : Reply()

        /**
         * `find_node` / generic node reply — `r{nodes, nodes6}`. The compact node
         * lists are decoded into [nodes] (from `nodes`) and [nodes6] (from `nodes6`).
         */
        class Nodes(
            val nodes: List<CompactNode> = emptyList(),
            val nodes6: List<CompactNode> = emptyList(),
        ) : Reply()

        /**
         * `get_peers` reply — `r{token, values?, nodes?, nodes6?}`. [values] holds the
         * peer endpoints from the `values` list; [token] is the opaque write token to
         * present on a subsequent `announce_peer`.
         */
        class Peers(
            val token: ByteArray?,
            val values: List<DhtEndpoint> = emptyList(),
            val nodes: List<CompactNode> = emptyList(),
            val nodes6: List<CompactNode> = emptyList(),
        ) : Reply()

        /**
         * `get` reply — `r{token, nodes?, nodes6?, seq?, v?, k?, sig?}` (BEP-44). When
         * the responder has the item it includes [item] (immutable or mutable,
         * reconstructed and — for mutable — signature-verified); otherwise just the
         * token and closer nodes.
         */
        class Item(
            val token: ByteArray?,
            val item: DhtItem? = null,
            val nodes: List<CompactNode> = emptyList(),
            val nodes6: List<CompactNode> = emptyList(),
            /** The raw `seq` for a mutable item even when [item] is absent (responder withheld `v`). */
            val seq: Long? = null,
        ) : Reply()
    }

    companion object {
        /** Default KRPC error code used by `incoming_error` (protocol error). */
        const val ERROR_PROTOCOL = 203

        // --- query builders ------------------------------------------------------

        /**
         * Build a `ping` query — `{t, y:"q", q:"ping", a:{id}}`. Mirrors the
         * `e["q"]="ping"` branch of `node::send_single_refresh`.
         */
        fun buildPingQuery(transactionId: ByteArray, nodeId: Sha1Hash): Entry =
            queryEnvelope(transactionId, "ping", nodeId)

        /**
         * Build a `find_node` query — `a{id, target}`. (libtorrent issues this via
         * `refresh`/`bootstrap`; the argument layout matches `incoming_request`'s
         * `find_node` schema.)
         */
        fun buildFindNodeQuery(transactionId: ByteArray, nodeId: Sha1Hash, target: Sha1Hash): Entry =
            queryEnvelope(transactionId, "find_node", nodeId) { a ->
                a["target"] = Entry.of(target.toByteArray())
            }

        /**
         * Build a `get_peers` query — `a{id, info_hash, noseed?}`. Port of
         * `get_peers::invoke`: `a["info_hash"]=…; if (noseed) a["noseed"]=1`.
         */
        fun buildGetPeersQuery(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            infoHash: Sha1Hash,
            noseed: Boolean = false,
            scrape: Boolean = false,
        ): Entry = queryEnvelope(transactionId, "get_peers", nodeId) { a ->
            a["info_hash"] = Entry.of(infoHash.toByteArray())
            if (noseed) a["noseed"] = Entry.of(1L)
            if (scrape) a["scrape"] = Entry.of(1L)
        }

        /**
         * Build an `announce_peer` query — `a{id, info_hash, port, token,
         * implied_port?, seed?, n?}`. Port of the `e["q"]="announce_peer"` assembly
         * in `node::announce`'s callback.
         */
        fun buildAnnouncePeerQuery(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            infoHash: Sha1Hash,
            port: Int,
            token: ByteArray,
            impliedPort: Boolean = false,
            seed: Boolean = false,
            name: String? = null,
        ): Entry = queryEnvelope(transactionId, "announce_peer", nodeId) { a ->
            a["info_hash"] = Entry.of(infoHash.toByteArray())
            a["port"] = Entry.of(port.toLong())
            a["token"] = Entry.of(token)
            if (impliedPort) a["implied_port"] = Entry.of(1L)
            if (seed) a["seed"] = Entry.of(1L)
            if (name != null) a["n"] = Entry.of(name)
        }

        /**
         * Build a `get` query — `a{id, target, seq?}`. Port of `get_item::invoke`
         * (`a["target"]=target`); [seq] is the optional BEP-44 read filter.
         */
        fun buildGetQuery(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            target: Sha1Hash,
            seq: Long? = null,
        ): Entry = queryEnvelope(transactionId, "get", nodeId) { a ->
            a["target"] = Entry.of(target.toByteArray())
            if (seq != null) a["seq"] = Entry.of(seq)
        }

        /**
         * Build a `put` query — `a{id, token, v, [k, seq, sig, salt, cas]}`. Faithful
         * to `put_data::invoke`: the value is spliced in as its exact bencoding (so
         * the signature round-trips), and the mutable extras are added only for a
         * [MutableItem].
         *
         * The [DhtItem.bencodedValue] is inserted via [Entry.Preformatted] so the
         * `v` bytes are byte-identical to what was signed — re-encoding could reorder
         * dict keys and break the signature.
         */
        fun buildPutQuery(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            token: ByteArray,
            item: DhtItem,
            cas: Long? = null,
        ): Entry = queryEnvelope(transactionId, "put", nodeId) { a ->
            a["token"] = Entry.of(token)
            a["v"] = Entry.Preformatted(item.bencodedValue)
            if (item is MutableItem) {
                a["k"] = Entry.of(item.publicKey)
                a["seq"] = Entry.of(item.seq)
                val sig = item.signature
                    ?: throw IllegalArgumentException("mutable put requires a signed item (call sign())")
                a["sig"] = Entry.of(sig)
                if (item.salt.isNotEmpty()) a["salt"] = Entry.of(item.salt)
                if (cas != null) a["cas"] = Entry.of(cas)
            }
        }

        /** Build a [Query] model directly into its bencoded [Entry] envelope. */
        fun build(query: Query): Entry = when (val a = query.args) {
            Args.Ping -> buildPingQuery(query.transactionId, query.nodeId)
            is Args.FindNode -> buildFindNodeQuery(query.transactionId, query.nodeId, a.target)
            is Args.GetPeers ->
                buildGetPeersQuery(query.transactionId, query.nodeId, a.infoHash, a.noseed, a.scrape)
            is Args.AnnouncePeer -> buildAnnouncePeerQuery(
                query.transactionId, query.nodeId, a.infoHash, a.port, a.token,
                a.impliedPort, a.seed, a.name,
            )
            is Args.Get -> buildGetQuery(query.transactionId, query.nodeId, a.target, a.seq)
            is Args.Put -> buildPutQuery(query.transactionId, query.nodeId, a.token, a.item, a.cas)
        }.also { env ->
            if (query.readOnly) env["ro"] = Entry.of(1L)
        }

        // --- response builders ---------------------------------------------------

        /**
         * Build a bare response carrying only `id` — `{t, y:"r", r:{id}}`. This is
         * what `ping`/`announce_peer` reply with (`incoming_request` adds nothing
         * beyond `t` and `id`).
         */
        fun buildPingResponse(transactionId: ByteArray, nodeId: Sha1Hash): Entry =
            responseEnvelope(transactionId, nodeId)

        /**
         * Build a `find_node`-style response — `r{id, nodes?, nodes6?}`. Mirrors
         * `write_nodes_entries`, which writes the compact list under the protocol's
         * `nodes`/`nodes6` key.
         */
        fun buildNodesResponse(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            nodes: List<CompactNode> = emptyList(),
            nodes6: List<CompactNode> = emptyList(),
        ): Entry = responseEnvelope(transactionId, nodeId) { r ->
            if (nodes.isNotEmpty()) r["nodes"] = Entry.of(CompactNodes.encode(nodes))
            if (nodes6.isNotEmpty()) r["nodes6"] = Entry.of(CompactNodes.encode(nodes6))
        }

        /**
         * Build a `get_peers` response — `r{id, token, values?, nodes?, nodes6?}`.
         * Port of `lookup_peers` + `write_nodes_entries`: the `values` list holds one
         * compact endpoint string per peer.
         */
        fun buildPeersResponse(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            token: ByteArray?,
            values: List<DhtEndpoint> = emptyList(),
            nodes: List<CompactNode> = emptyList(),
            nodes6: List<CompactNode> = emptyList(),
        ): Entry = responseEnvelope(transactionId, nodeId) { r ->
            if (token != null) r["token"] = Entry.of(token)
            if (values.isNotEmpty()) {
                val list = Entry.list()
                for (ep in values) list.items.add(Entry.of(ep.encode()))
                r["values"] = list
            }
            if (nodes.isNotEmpty()) r["nodes"] = Entry.of(CompactNodes.encode(nodes))
            if (nodes6.isNotEmpty()) r["nodes6"] = Entry.of(CompactNodes.encode(nodes6))
        }

        /**
         * Build a `get` (BEP-44) response — `r{id, token, nodes?, nodes6?, [seq, v,
         * k, sig]}`. Port of the `get` branch of `incoming_request` plus
         * `dht_storage::get_mutable_item`/`get_immutable_item`: an immutable item adds
         * only `v`; a mutable item adds `seq` always and `v`/`sig`/`k` when filled.
         *
         * @param item the stored item to return, or null when only nodes/token apply.
         * @param includeValue for a mutable item, whether to include `v`/`sig`/`k`
         *   (libtorrent withholds them when the requester's `seq` is already current).
         */
        fun buildItemResponse(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            token: ByteArray?,
            item: DhtItem? = null,
            nodes: List<CompactNode> = emptyList(),
            nodes6: List<CompactNode> = emptyList(),
            includeValue: Boolean = true,
        ): Entry = responseEnvelope(transactionId, nodeId) { r ->
            if (token != null) r["token"] = Entry.of(token)
            if (nodes.isNotEmpty()) r["nodes"] = Entry.of(CompactNodes.encode(nodes))
            if (nodes6.isNotEmpty()) r["nodes6"] = Entry.of(CompactNodes.encode(nodes6))
            when (item) {
                is MutableItem -> {
                    r["seq"] = Entry.of(item.seq)
                    if (includeValue) {
                        r["v"] = Entry.Preformatted(item.bencodedValue)
                        item.signature?.let { r["sig"] = Entry.of(it) }
                        r["k"] = Entry.of(item.publicKey)
                    }
                }
                is ImmutableItem -> {
                    if (includeValue) r["v"] = Entry.Preformatted(item.bencodedValue)
                }
                null -> { /* nodes/token only */ }
            }
        }

        /**
         * Build an error response — `{t, y:"e", e:[code, message]}`. Direct port of
         * `incoming_error(entry&, char const*, int)`.
         */
        fun buildError(
            transactionId: ByteArray,
            message: String,
            code: Int = ERROR_PROTOCOL,
        ): Entry {
            val e = Entry.dict()
            e["t"] = Entry.of(transactionId)
            e["y"] = Entry.of("e")
            val list = Entry.list()
            list.items.add(Entry.of(code.toLong()))
            list.items.add(Entry.of(message))
            e["e"] = list
            return e
        }

        // --- envelope assembly helpers -------------------------------------------

        private inline fun queryEnvelope(
            transactionId: ByteArray,
            query: String,
            nodeId: Sha1Hash,
            fillArgs: (Entry) -> Unit = {},
        ): Entry {
            val e = Entry.dict()
            e["t"] = Entry.of(transactionId)
            e["y"] = Entry.of("q")
            e["q"] = Entry.of(query)
            val a = Entry.dict()
            a["id"] = Entry.of(nodeId.toByteArray())
            fillArgs(a)
            e["a"] = a
            return e
        }

        private inline fun responseEnvelope(
            transactionId: ByteArray,
            nodeId: Sha1Hash,
            fillReply: (Entry) -> Unit = {},
        ): Entry {
            val e = Entry.dict()
            e["t"] = Entry.of(transactionId)
            e["y"] = Entry.of("r")
            val r = Entry.dict()
            r["id"] = Entry.of(nodeId.toByteArray())
            fillReply(r)
            e["r"] = r
            return e
        }

        // --- parsing -------------------------------------------------------------

        /**
         * Parse a decoded KRPC envelope [node] into a typed [DhtMessage], or `null`
         * if the message is malformed (not a dict, missing/!string `t` or `y`,
         * unknown `y`, missing payload, or a 20-byte-id violation).
         *
         * This is the read side of what [verifyMessage] guards on the wire: it does
         * the same `dict_find`/length checks libtorrent does in `incoming_request`
         * and the various `*_observer::reply` handlers, but rather than dispatching
         * side effects it returns the structured message. Unknown query names parse
         * into a [Query] with [Args.Ping] semantics only if they truly carry no
         * recognised payload; otherwise an unrecognised query yields `null` so the
         * caller can answer with `incoming_error("unknown message")`.
         */
        fun parse(node: BdecodeNode): DhtMessage? {
            if (node.type != BdecodeNode.Type.DICT) return null
            val t = node.dictFindString("t")
            if (!t.isValid) return null
            val tid = t.stringBytes()
            val y = node.dictFindString("y")
            if (!y.isValid) return null
            return when (y.stringValue()) {
                "q" -> parseQuery(node, tid)
                "r" -> parseResponse(node, tid)
                "e" -> parseError(node, tid)
                else -> null
            }
        }

        private fun parseQuery(node: BdecodeNode, tid: ByteArray): DhtMessage? {
            val q = node.dictFindString("q")
            if (!q.isValid) return null
            val a = node.dictFindDict("a")
            if (!a.isValid) return null
            val id = id20(a, "id") ?: return null
            val readOnly = node.dictFindIntValue("ro", 0L) != 0L
            val name = q.stringValue()
            val args: Args = when (name) {
                "ping" -> Args.Ping
                "find_node" -> {
                    val target = id20(a, "target") ?: return null
                    Args.FindNode(target)
                }
                "get_peers" -> {
                    val ih = id20(a, "info_hash") ?: return null
                    Args.GetPeers(
                        ih,
                        noseed = a.dictFindIntValue("noseed", 0L) != 0L,
                        scrape = a.dictFindIntValue("scrape", 0L) != 0L,
                    )
                }
                "announce_peer" -> {
                    val ih = id20(a, "info_hash") ?: return null
                    val portNode = a.dictFindInt("port")
                    if (!portNode.isValid) return null
                    val tokenNode = a.dictFindString("token")
                    if (!tokenNode.isValid) return null
                    Args.AnnouncePeer(
                        ih,
                        port = portNode.intValue().toInt(),
                        token = tokenNode.stringBytes(),
                        impliedPort = a.dictFindIntValue("implied_port", 0L) != 0L,
                        seed = a.dictFindIntValue("seed", 0L) != 0L,
                        name = a.dictFindString("n").takeIf { it.isValid }?.stringValue(),
                    )
                }
                "get" -> {
                    val target = id20(a, "target") ?: return null
                    val seqNode = a.dictFindInt("seq")
                    Args.Get(target, if (seqNode.isValid) seqNode.intValue() else null)
                }
                "put" -> parsePutArgs(a) ?: return null
                else -> return null
            }
            return Query(tid, name, id, args, readOnly)
        }

        private fun parsePutArgs(a: BdecodeNode): Args.Put? {
            val tokenNode = a.dictFindString("token")
            if (!tokenNode.isValid) return null
            val v = a.dictFind("v")
            if (!v.isValid) return null
            val cas = a.dictFindInt("cas").takeIf { it.isValid }?.intValue()

            val k = a.dictFindString("k")
            val sig = a.dictFindString("sig")
            val seqNode = a.dictFindInt("seq")
            val mutablePut = k.isValid && sig.isValid && seqNode.isValid

            return if (!mutablePut) {
                Args.Put(tokenNode.stringBytes(), ImmutableItem.of(v), cas)
            } else {
                if (k.stringLength() != DhtKeySizes.PUBLIC_KEY_LEN) return null
                if (sig.stringLength() != DhtKeySizes.SIGNATURE_LEN) return null
                val saltNode = a.dictFindString("salt")
                val salt = if (saltNode.isValid) saltNode.stringBytes() else DhtItems.EMPTY
                val item = MutableItem.received(
                    value = v,
                    publicKey = k.stringBytes(),
                    seq = seqNode.intValue(),
                    signature = sig.stringBytes(),
                    salt = salt,
                ) ?: return null
                Args.Put(tokenNode.stringBytes(), item, cas)
            }
        }

        private fun parseResponse(node: BdecodeNode, tid: ByteArray): DhtMessage? {
            val r = node.dictFindDict("r")
            if (!r.isValid) return null
            val id = id20(r, "id") ?: return null

            val token = r.dictFindString("token").takeIf { it.isValid }?.stringBytes()
            val nodes = r.dictFindString("nodes").takeIf { it.isValid }
                ?.let { CompactNodes.decodeV4(it.stringBytes()) } ?: emptyList()
            val nodes6 = r.dictFindString("nodes6").takeIf { it.isValid }
                ?.let { CompactNodes.decodeV6(it.stringBytes()) } ?: emptyList()

            val valuesNode = r.dictFindList("values")
            val vNode = r.dictFind("v")
            val seqNode = r.dictFindInt("seq")

            val reply: Reply = when {
                // get_peers reply: a "values" list present.
                valuesNode.isValid -> Reply.Peers(token, parseValues(valuesNode), nodes, nodes6)

                // BEP-44 get reply: a value, or a seq (mutable item, possibly value-withheld).
                vNode.isValid || seqNode.isValid -> {
                    val item = parseGetItem(r, vNode, seqNode)
                    Reply.Item(
                        token = token,
                        item = item,
                        nodes = nodes,
                        nodes6 = nodes6,
                        seq = if (seqNode.isValid) seqNode.intValue() else null,
                    )
                }

                // A token but no value/values → a get reply that returned only nodes.
                token != null -> Reply.Item(token, null, nodes, nodes6)

                // Otherwise it's a find_node-style nodes reply, or a bare pong.
                nodes.isNotEmpty() || nodes6.isNotEmpty() -> Reply.Nodes(nodes, nodes6)
                else -> Reply.Pong
            }
            return Response(tid, id, reply, r)
        }

        /**
         * Reconstruct the BEP-44 item from a `get` response's `r` dict — port of
         * `get_item_observer::reply`: read `k`/`sig`/`seq`/`v`, verify a mutable
         * item's signature, fall back to an immutable item when no key is present.
         * Returns null when `v` is absent or a mutable item fails verification.
         */
        private fun parseGetItem(r: BdecodeNode, vNode: BdecodeNode, seqNode: BdecodeNode): DhtItem? {
            if (!vNode.isValid) return null
            val k = r.dictFindString("k")
            val sig = r.dictFindString("sig")
            return if (k.isValid && sig.isValid &&
                k.stringLength() == DhtKeySizes.PUBLIC_KEY_LEN &&
                sig.stringLength() == DhtKeySizes.SIGNATURE_LEN &&
                seqNode.isValid
            ) {
                // libtorrent's `get` reply does not carry the salt; the salt is known
                // to the requester out-of-band (it asked for SHA1(pk||salt)). With no
                // salt available here we verify against the empty salt, which is the
                // common case; callers needing a salted item should verify themselves.
                MutableItem.received(
                    value = vNode,
                    publicKey = k.stringBytes(),
                    seq = seqNode.intValue(),
                    signature = sig.stringBytes(),
                )
            } else {
                ImmutableItem.of(vNode)
            }
        }

        private fun parseError(node: BdecodeNode, tid: ByteArray): DhtMessage? {
            val e = node.dictFindList("e")
            if (!e.isValid || e.listSize() < 2) return null
            val code = e.listAt(0).takeIf { it.type == BdecodeNode.Type.INT }?.intValue()?.toInt() ?: return null
            val msg = e.listAt(1).takeIf { it.type == BdecodeNode.Type.STRING }?.stringValue() ?: ""
            return ErrorMessage(tid, code, msg)
        }

        /**
         * Read a `values` list into endpoints. Handles both the mainline single
         * concatenated-string form and the uTorrent/libtorrent one-string-per-peer
         * form — port of `get_peers_observer::reply` / `aux::read_endpoint_list`:
         * a 6-byte string is a v4 endpoint, an 18-byte string is v6, and a single
         * longer string is split into 6-byte v4 records.
         */
        private fun parseValues(values: BdecodeNode): List<DhtEndpoint> {
            val out = ArrayList<DhtEndpoint>()
            if (values.type != BdecodeNode.Type.LIST) return out
            val n = values.listSize()
            if (n == 1 && values.listAt(0).type == BdecodeNode.Type.STRING) {
                val s = values.listAt(0).stringBytes()
                if (s.size == DhtEndpoint.V6_SIZE) {
                    out.add(DhtEndpoint.decodeV6(s, 0))
                    return out
                }
                // mainline: one string holding many 6-byte v4 records
                var off = 0
                while (off + DhtEndpoint.V4_SIZE <= s.size) {
                    out.add(DhtEndpoint.decodeV4(s, off))
                    off += DhtEndpoint.V4_SIZE
                }
                return out
            }
            for (i in 0 until n) {
                val item = values.listAt(i)
                if (item.type != BdecodeNode.Type.STRING) continue
                val s = item.stringBytes()
                when (s.size) {
                    DhtEndpoint.V4_SIZE -> out.add(DhtEndpoint.decodeV4(s, 0))
                    DhtEndpoint.V6_SIZE -> out.add(DhtEndpoint.decodeV6(s, 0))
                    else -> { /* skip malformed */ }
                }
            }
            return out
        }

        /**
         * Find [key] in dict [d] and return it as a [Sha1Hash] iff it is a 20-byte
         * string, else null — the `string_length() == 20` guard libtorrent applies
         * to every id/target/info_hash field.
         */
        private fun id20(d: BdecodeNode, key: String): Sha1Hash? {
            val s = d.dictFindString(key)
            if (!s.isValid || s.stringLength() != NODE_ID_SIZE) return null
            return Sha1Hash.of(s.stringBytes())
        }
    }
}
