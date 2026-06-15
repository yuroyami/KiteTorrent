package io.github.yuroyami.kitetorrent.session.engine

/**
 * The unchoke decision — port of libtorrent's `choker.cpp`.
 *
 * BitTorrent is **tit-for-tat**: you only upload ("unchoke") to a small number of
 * peers, and you pick the ones that reward you most — while *downloading* that means
 * the peers feeding you the fastest; while *seeding*, the peers you can push to the
 * fastest. Everyone else stays "choked" (you refuse to upload to them). On top of that,
 * one rotating **optimistic** slot unchokes a random choked peer so you can discover
 * new fast partners you'd otherwise never try.
 *
 * This object is pure and deterministic given its inputs, so it's unit-tested in
 * isolation; [TorrentSession] supplies the live rates and the rotating optimistic pick.
 */
internal object Choker {
    /**
     * @param rates       candidate peer → its rate this round (bytes received-from while
     *                    leeching, sent-to while seeding). Pass only *interested* peers.
     * @param uploadSlots how many regular unchoke slots (libtorrent default 4).
     * @param optimistic  an extra peer to unchoke regardless of rate (the caller rotates
     *                    this every few rounds), or null.
     * @return the set of peers that should be unchoked this round.
     */
    fun <T> chooseUnchoked(rates: Map<T, Long>, uploadSlots: Int, optimistic: T?): Set<T> {
        val result = LinkedHashSet<T>()
        rates.entries
            .sortedByDescending { it.value }
            .take(uploadSlots.coerceAtLeast(0))
            .forEach { result.add(it.key) }
        if (optimistic != null) result.add(optimistic)
        return result
    }
}
