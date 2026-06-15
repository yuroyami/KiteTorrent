package io.github.yuroyami.kitetorrent.session.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.dht.CompactNode
import io.github.yuroyami.kitetorrent.dht.compareRef
import io.github.yuroyami.kitetorrent.dht.distance

/**
 * The pure candidate bookkeeping of an iterative Kademlia lookup — the
 * network-free heart of libtorrent's `traversal_algorithm`
 * (`src/kademlia/traversal_algorithm.cpp`). [DhtNode.getPeers] drives one of these:
 * it seeds the state with the routing table's closest nodes, then repeatedly asks the
 * state for the next batch to query ([next]), fires the actual `get_peers`/`find_node`
 * RPCs, and feeds the returned closer nodes back in ([add]).
 *
 * **The structure (faithful):** `m_results` is the list of every node we've heard of for
 * this target, kept **sorted by XOR distance to the target** and de-duplicated by node
 * id (`traversal_algorithm::add_entry` inserts with `std::lower_bound` under
 * `compare_ref` and drops duplicates). Each entry tracks the three observer states that
 * matter — *not yet queried*, *queried/in-flight*, and *responded/alive* (the
 * `flag_queried` / `flag_alive` bits in `observer`). The lookup stops ([isDone]) once the
 * closest [resultsTarget] entries have all responded with nothing closer to add, exactly
 * `add_requests`'s completion test ("`results_target` completed results without finding
 * any still in flight closer ones").
 *
 * **Branch factor.** [next] hands out at most enough ids to keep [branchFactor]
 * (libtorrent's α = 3, `m_branch_factor`) requests outstanding *among the closest
 * unqueried nodes*, never reaching past the [resultsTarget]-th closest. That is the
 * "keep the top-k queried" variation libtorrent documents in `add_requests` (it bounds
 * good outstanding requests rather than raw outstanding requests).
 *
 * **Deliberate simplifications** (the live-network nuances [DhtNode] doesn't need):
 *  - **No short-timeout / branch-factor inflation.** libtorrent escalates a slow node to
 *    a 1-second timeout and temporarily widens α; here a node either responds (→ alive)
 *    or is marked failed by the caller ([fail]) when its RPC returns null. The flat per-
 *    query timeout lives in [DhtRpc].
 *  - **No per-IP/CIDR spread or `restrict_routing_ips` filtering of candidates.** Those
 *    guard the *routing table*; the in-flight result list takes any node it's told about.
 *  - **No address-family split.** Candidates carry a [CompactNode] (which already knows
 *    its v4/v6 endpoint); the caller decides which family to query.
 *
 * This class is intentionally synchronous and allocation-light so it can be unit-tested
 * without a socket: drive it with hand-built [CompactNode]s and assert the ordering /
 * completion exactly as the routing-table tests do.
 *
 * @param target the lookup target id (an info-hash for `get_peers`, our own id for a
 *   bootstrap `find_node`).
 * @param resultsTarget how many of the closest nodes must respond before the lookup is
 *   done — Kademlia's `k` (libtorrent uses `m_table.bucket_size()`, default 8).
 * @param branchFactor the maximum number of concurrent in-flight queries among the top
 *   candidates — Kademlia's α (libtorrent's `m_branch_factor`, default 3).
 */
class TraversalState(
    val target: Sha1Hash,
    val resultsTarget: Int = DEFAULT_RESULTS_TARGET,
    val branchFactor: Int = DEFAULT_BRANCH_FACTOR,
) {
    /** Per-candidate progress — the subset of `observer`'s flags the traversal needs. */
    enum class Status { UNQUERIED, IN_FLIGHT, ALIVE, FAILED }

    /** One node in the result list, mutated in place as its query progresses. */
    class Candidate(val node: CompactNode) {
        var status: Status = Status.UNQUERIED
        val id: Sha1Hash get() = node.id
    }

    // m_results, kept sorted nearest-first by distance(target, id) and unique by id.
    private val results = ArrayList<Candidate>()
    private val seenIds = HashSet<Sha1Hash>()

    /** Read-only snapshot of the current result list (nearest-first). */
    val candidates: List<Candidate> get() = results

    /**
     * Insert [node] into the result list if its id is new, preserving the nearest-first
     * ordering by XOR distance to [target]. Port of `traversal_algorithm::add_entry`'s
     * `lower_bound`+`insert` (sans the no-id/random-id path, since our candidates always
     * carry a real id from a compact-nodes reply). Returns `true` if it was added.
     */
    fun add(node: CompactNode): Boolean {
        if (!seenIds.add(node.id)) return false
        val pos = insertionIndex(node.id)
        results.add(pos, Candidate(node))
        return true
    }

    /** Bulk [add] — convenience for seeding from a `find_node` / `nodes` reply. */
    fun addAll(nodes: Iterable<CompactNode>) {
        for (n in nodes) add(n)
    }

    /**
     * The next batch of candidates to query — the unqueried nodes nearest the target,
     * enough to bring the number of in-flight queries (among the closest [resultsTarget])
     * up to [branchFactor]. Port of the request-issuing loop in `add_requests`: it walks
     * the sorted results, counts alive nodes against `results_target`, counts in-flight
     * ones against `branch_factor`, and stops as soon as either budget is spent.
     *
     * Each returned candidate is marked [Status.IN_FLIGHT] (`o->flags |= flag_queried`)
     * before being handed back, so a second [next] won't re-issue it. The caller fires
     * the RPCs and then reports each outcome via [alive] or [fail].
     */
    fun next(): List<Candidate> {
        val toQuery = ArrayList<Candidate>()
        var resultsLeft = resultsTarget
        var outstanding = countInFlight()

        for (c in results) {
            if (resultsLeft <= 0) break
            if (outstanding >= branchFactor) break
            when (c.status) {
                Status.ALIVE -> resultsLeft--          // a completed top result
                Status.IN_FLIGHT -> { /* already counted in `outstanding` */ }
                Status.FAILED -> { /* skip; doesn't consume a result slot */ }
                Status.UNQUERIED -> {
                    c.status = Status.IN_FLIGHT
                    toQuery.add(c)
                    outstanding++
                }
            }
        }
        return toQuery
    }

    /** Mark [c] as having responded (`flag_alive`). */
    fun alive(c: Candidate) {
        c.status = Status.ALIVE
    }

    /** Mark [c] as failed/timed-out (`flag_failed`) so it stops blocking completion. */
    fun fail(c: Candidate) {
        c.status = Status.FAILED
    }

    /**
     * `true` once the lookup has converged: among the closest [resultsTarget] live
     * candidates there is nothing still in flight and nothing left unqueried that is
     * closer than them. Port of `add_requests`'s completion condition — we have found
     * `results_target` alive nodes with no closer in-flight/unqueried work, or the whole
     * result list is exhausted.
     */
    fun isDone(): Boolean {
        var alive = 0
        for (c in results) {
            when (c.status) {
                Status.ALIVE -> {
                    alive++
                    if (alive >= resultsTarget) return true
                }
                // a still-pending or not-yet-tried node closer than the k-th alive one
                // means we're not done.
                Status.UNQUERIED, Status.IN_FLIGHT -> return false
                Status.FAILED -> { /* doesn't block completion */ }
            }
        }
        // ran off the end of the list: done iff nothing is still in flight or untried
        return results.none { it.status == Status.UNQUERIED || it.status == Status.IN_FLIGHT }
    }

    /** The [count] closest nodes that actually responded — the lookup's usable output. */
    fun closestAlive(count: Int = resultsTarget): List<CompactNode> =
        results.asSequence()
            .filter { it.status == Status.ALIVE }
            .map { it.node }
            .take(count)
            .toList()

    private fun countInFlight(): Int = results.count { it.status == Status.IN_FLIGHT }

    /**
     * Lower-bound insertion index for an id under the nearest-first `compare_ref`
     * ordering. Mirrors `std::lower_bound(m_results.begin(), m_results.end(), id, …)`.
     */
    private fun insertionIndex(id: Sha1Hash): Int {
        var lo = 0
        var hi = results.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareRef(results[mid].id, id, target)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Kademlia `k` — libtorrent's `m_table.bucket_size()` default. */
        const val DEFAULT_RESULTS_TARGET: Int = 8

        /** Kademlia α — libtorrent's `m_branch_factor` default. */
        const val DEFAULT_BRANCH_FACTOR: Int = 3
    }
}

/**
 * Order [nodes] nearest-first by XOR distance to [target] — the standalone form of the
 * comparator [TraversalState] inserts under, exposed for callers (and tests) that just
 * want a sorted snapshot of compact nodes. Stable on ties by id.
 */
fun sortByDistance(nodes: List<CompactNode>, target: Sha1Hash): List<CompactNode> =
    nodes.sortedWith { a, b ->
        val da = distance(a.id, target)
        val db = distance(b.id, target)
        da.compareTo(db)
    }
