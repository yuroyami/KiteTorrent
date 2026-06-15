package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import io.github.yuroyami.kitetorrent.dht.DhtMessage
import io.github.yuroyami.kitetorrent.session.net.DatagramTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/**
 * The live KRPC request/response manager — the coroutine port of libtorrent's
 * `rpc_manager` (`src/kademlia/rpc_manager.cpp`) reduced to the one job that a
 * `commonMain` engine can carry: match an outgoing query to its reply.
 *
 * libtorrent's `rpc_manager::invoke` tags every query with a 2-byte transaction id
 * (`std::uint16_t`, written big-endian into `t`, see `rpc_manager.cpp` `m_transactions`
 * keyed by `aux::read_uint16`) and parks an `observer` until either the reply with the
 * matching `t` arrives (`incoming`) or the 15-second timeout fires (`check_timeouts`).
 * Here the observer collapses to a [CompletableDeferred]: [query] sends the datagram,
 * registers the deferred under the freshly-minted id, and suspends on it under a
 * [withTimeout]. The receive side ([dispatchResponse]) looks the id up and completes
 * the waiter.
 *
 * **Threading model — the libtorrent strand.** libtorrent runs the whole DHT on one
 * `io_context` (effectively a single-threaded strand): there are never two concurrent
 * touches of `m_transactions`. We reproduce that contract rather than locking: this
 * manager and the [DhtNode] receive loop that drives it MUST share one
 * [kotlinx.coroutines.CoroutineScope] backed by a **single-threaded** dispatcher
 * confinement (e.g. `Dispatchers.Default.limitedParallelism(1)`, which [DhtNode]
 * arranges). All access to the [pending] map happens in non-suspending sections
 * between suspension points, so on that confined dispatcher it is race-free without a
 * mutex — and `commonMain` has no portable lock primitive to reach for anyway. The
 * only suspension inside [query] (the `socket.send` and the `await`) happens *after*
 * the id is registered and *before* it is removed, so a reply can never arrive at an
 * unregistered waiter.
 *
 * **What this owns vs. what [DhtNode] owns.** This class is purely the *outgoing-query
 * bookkeeping* — the `m_transactions` map and the wire encode/decode of one datagram.
 * It does **not** run the socket receive loop and it does **not** answer inbound
 * queries; [DhtNode] owns the single `udp.receive()` loop and routes each parsed
 * [DhtMessage] either to [dispatchResponse] (responses/errors) or to its own
 * query-handler (inbound queries). This mirrors libtorrent, where `dht_tracker`
 * pumps packets into `node::incoming`, which in turn hands responses to
 * `rpc_manager::incoming`.
 *
 * **Simplifications vs. libtorrent (each deliberate):**
 *  - **No RTT / short-timeout escalation.** libtorrent bumps an observer to a 1-second
 *    "short timeout" and adjusts the traversal branch factor; we use a single flat
 *    [timeoutMillis] per query and surface a timeout as a `null` result. The traversal
 *    in [DhtNode] simply treats a `null` as "this node didn't answer".
 *  - **No per-endpoint observer identity / address spoof check.** `rpc_manager::incoming`
 *    verifies the reply's source endpoint matches the observer's; because we key only
 *    on the transaction id (which is what actually demultiplexes the socket), a reply
 *    from an unexpected address that happens to echo a live id would be accepted. With
 *    2-byte ids drawn at random per in-flight query the collision window is tiny, and
 *    [DhtNode] re-validates the responder's node id against its routing-table slot.
 *  - **Errors resolve the waiter too.** A `y:"e"` reply for a live id completes the
 *    deferred exceptionally with a [DhtProtocolException] rather than being dropped —
 *    the caller decides how to treat a protocol error (libtorrent logs and drops it).
 */
class DhtRpc(
    private val socket: DatagramTransport,
    /** RNG for transaction ids; inject a seeded one for deterministic tests. */
    private val random: Random = Random.Default,
    /** Per-query reply timeout in ms. Default 15 000 — libtorrent's `rpc_manager` `timeout`. */
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    /**
     * In-flight queries keyed by the 16-bit transaction id. Confined to the DHT's
     * single dispatcher (see the class threading note); never touched from a second
     * thread, so a plain map suffices.
     */
    private val pending = HashMap<Int, CompletableDeferred<DhtMessage.Response>>()

    /**
     * Send the query [build] produced — a bencodable [Entry] envelope from
     * `DhtMessage.build*Query(transactionId, …)` — to [host]:[port], then suspend until
     * the matching response arrives or [timeoutMillis] elapses.
     *
     * The caller does **not** supply the transaction id: [query] mints a fresh unused
     * one, hands it to [build], and uses the same bytes as the map key — exactly the
     * `rpc_manager::invoke` contract where the manager, not the algorithm, owns `t`.
     *
     * @param build given the freshly-minted 2-byte transaction id, returns the full
     *   query [Entry] (e.g. `{ tid -> DhtMessage.buildGetPeersQuery(tid, myId, ih) }`).
     * @return the matching [DhtMessage.Response], or `null` on timeout. A protocol
     *   error reply is surfaced as a thrown [DhtProtocolException] so a `null`
     *   unambiguously means "no answer".
     * @throws DhtProtocolException if the peer answered with a KRPC error.
     */
    suspend fun query(host: String, port: Int, build: (ByteArray) -> Entry): DhtMessage.Response? {
        val (tid, key) = allocateTransaction()
        val deferred = CompletableDeferred<DhtMessage.Response>()
        pending[key] = deferred

        val bytes = Bencode.encode(build(tid))
        try {
            socket.send(bytes, host, port)
            return withTimeout(timeoutMillis) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            return null
        } finally {
            pending.remove(key)
        }
    }

    /**
     * Feed one already-parsed inbound [message] to the waiter table. Returns `true` if
     * it was a response/error that matched a live transaction id (and so was consumed
     * here), `false` otherwise — in which case the caller ([DhtNode]) should treat it as
     * an inbound query (or a stray/late reply) and handle it itself.
     *
     * Port of the response branch of `rpc_manager::incoming`: look the id up, and if a
     * waiter exists complete it (or, for `y:"e"`, fail it with a [DhtProtocolException]).
     * A response whose id is not in the table is a late/duplicate reply and is dropped
     * (`return false` — libtorrent logs "unknown transaction id").
     */
    fun dispatchResponse(message: DhtMessage): Boolean {
        when (message) {
            is DhtMessage.Response -> {
                val waiter = takeWaiter(message.transactionId) ?: return false
                waiter.complete(message)
                return true
            }
            is DhtMessage.ErrorMessage -> {
                val waiter = takeWaiter(message.transactionId) ?: return false
                waiter.completeExceptionally(DhtProtocolException(message.code, message.message))
                return true
            }
            is DhtMessage.Query -> return false
        }
    }

    /**
     * Decode a raw datagram payload and route it. Convenience used by [DhtNode]'s
     * receive loop when it wants the RPC manager to claim responses before it inspects
     * the message as a possible inbound query. Returns the parsed [DhtMessage] (so the
     * caller can handle queries / refresh its routing table from a response's node id),
     * or `null` if the bytes were not a well-formed KRPC envelope.
     */
    fun parseAndDispatch(data: ByteArray): DhtMessage? {
        val node = Bdecode.decodeOrNull(data) ?: return null
        val message = DhtMessage.parse(node) ?: return null
        dispatchResponse(message)
        return message
    }

    /** Fail every outstanding waiter (e.g. on socket close). Port of `rpc_manager::~`. */
    fun cancelAll() {
        val snapshot = pending.values.toList()
        pending.clear()
        for (d in snapshot) d.cancel(CancellationException("DHT RPC manager closed"))
    }

    /** Number of queries currently awaiting a reply (`rpc_manager::num_allocated`-ish). */
    fun outstanding(): Int = pending.size

    // --- internals ---------------------------------------------------------------

    private fun takeWaiter(tid: ByteArray): CompletableDeferred<DhtMessage.Response>? {
        if (tid.size != TRANSACTION_ID_SIZE) return null
        val key = ((tid[0].toInt() and 0xff) shl 8) or (tid[1].toInt() and 0xff)
        return pending[key]
    }

    /**
     * Mint a 2-byte transaction id not currently in flight. libtorrent draws a random
     * `std::uint16_t`; we do the same and, in the (vanishingly rare) event of a live
     * collision, redraw — capping the attempts so a near-full table can't spin forever.
     */
    private fun allocateTransaction(): Pair<ByteArray, Int> {
        repeat(MAX_TID_ATTEMPTS) {
            val key = random.nextInt(0, 0x1_0000)
            if (!pending.containsKey(key)) {
                val bytes = byteArrayOf(((key ushr 8) and 0xff).toByte(), (key and 0xff).toByte())
                return bytes to key
            }
        }
        // 65 536 ids all in flight is absurd for a single node; fail loudly rather than
        // hand back a colliding id that would cross-wire two waiters.
        throw IllegalStateException("DHT transaction id space exhausted (too many in-flight queries)")
    }

    companion object {
        /** Transaction-id width libtorrent uses (`write_uint16` into `t`). */
        const val TRANSACTION_ID_SIZE: Int = 2

        /** Default reply timeout: `rpc_manager`'s `timeout = seconds(15)`. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L

        private const val MAX_TID_ATTEMPTS = 64
    }
}

/**
 * Raised when a DHT peer answers a query with a KRPC error (`y:"e"`, `e:[code, msg]`),
 * the read side of `incoming_error`. Carries the numeric [code] (201 generic, 203
 * protocol, 204 method-unknown, …) and the human-readable [message].
 */
class DhtProtocolException(
    /** KRPC error code from the `e` list. */
    val code: Int,
    message: String,
) : Exception("DHT error $code: $message")
