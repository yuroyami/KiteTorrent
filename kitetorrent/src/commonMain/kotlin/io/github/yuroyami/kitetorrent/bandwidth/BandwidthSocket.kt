package io.github.yuroyami.kitetorrent.bandwidth

/**
 * The callback surface a bandwidth consumer exposes to a [BandwidthManager] —
 * pure-Kotlin port of libtorrent's `bandwidth_socket` (aux_/bandwidth_socket.hpp).
 *
 * In libtorrent the only implementer is `peer_connection`. The manager is a pure
 * scheduling algorithm and never touches sockets directly; it just calls back
 * through this interface to hand assigned quota to the consumer and to ask whether
 * the consumer is shutting down. Keeping it abstract lets the limiter be tested in
 * isolation and ported without any I/O.
 */
interface BandwidthSocket {
    /**
     * Hands [amount] bytes of quota (on bandwidth [channel] — upload or download)
     * to this consumer. Invoked once the consumer's outstanding request has been
     * fully satisfied or has run out of time-to-live.
     */
    fun assignBandwidth(channel: Int, amount: Int)

    /** True if the consumer is disconnecting; its quota will be reclaimed. */
    fun isDisconnecting(): Boolean
}
