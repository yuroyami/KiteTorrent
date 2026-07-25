package io.github.yuroyami.kitetorrent.stat

/**
 * Aggregates the six byte-stream channels of a peer connection (or the whole
 * session). The pure-Kotlin port of libtorrent's `stat` (stat.hpp).
 *
 * The six [StatChannel]s, indexed by the [Channel] enum, split traffic by
 * direction (upload / download) and class (BitTorrent payload, BitTorrent
 * protocol overhead, and IP/TCP overhead). Helpers roll these up into the
 * familiar `upload_rate` / `total_download` / ... figures the rest of libtorrent
 * reports.
 *
 * `second_tick` must be driven once per second (passing the real elapsed
 * milliseconds) so each channel's smoothed rate stays in bytes/second.
 */
class Stat {

    private val stat = Array(NUM_CHANNELS) { StatChannel() }

    /** The six channels of a [Stat], in their fixed C++ ordinal order. */
    enum class Channel {
        UPLOAD_PAYLOAD,
        UPLOAD_PROTOCOL,
        DOWNLOAD_PAYLOAD,
        DOWNLOAD_PROTOCOL,
        UPLOAD_IP_PROTOCOL,
        DOWNLOAD_IP_PROTOCOL,
    }

    /** Direct access to a channel, mirroring `stat::operator[]`. */
    operator fun get(c: Channel): StatChannel = stat[c.ordinal]

    /** Folds every channel of [s] into this one. (`operator+=`) */
    operator fun plusAssign(s: Stat) {
        for (i in 0 until NUM_CHANNELS) stat[i] += s.stat[i]
    }

    // --- accounting hooks ------------------------------------------------------

    /** Account for sending a TCP SYN (40 bytes IPv4, 60 IPv6). (`sent_syn`) */
    fun sentSyn(ipv6: Boolean) {
        stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].add(if (ipv6) 60 else 40)
    }

    /** Account for a received SYN-ACK plus the ACK we send back. (`received_synack`) */
    fun receivedSynack(ipv6: Boolean) {
        val n = if (ipv6) 60 else 40
        stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].add(n)
        stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].add(n)
    }

    /** Record received bytes, split into payload and protocol. (`received_bytes`) */
    fun receivedBytes(bytesPayload: Int, bytesProtocol: Int) {
        require(bytesPayload >= 0)
        require(bytesProtocol >= 0)
        stat[Channel.DOWNLOAD_PAYLOAD.ordinal].add(bytesPayload)
        stat[Channel.DOWNLOAD_PROTOCOL.ordinal].add(bytesProtocol)
    }

    /** Record sent bytes, split into payload and protocol. (`sent_bytes`) */
    fun sentBytes(bytesPayload: Int, bytesProtocol: Int) {
        require(bytesPayload >= 0)
        require(bytesProtocol >= 0)
        stat[Channel.UPLOAD_PAYLOAD.ordinal].add(bytesPayload)
        stat[Channel.UPLOAD_PROTOCOL.ordinal].add(bytesProtocol)
    }

    /**
     * Charges the IP/TCP overhead of moving [bytesTransferred] bytes to both the
     * upload and download IP-protocol channels, matching `trancieve_ip_packet`.
     *
     * Each 1500-byte-MTU packet (minus a 40/60-byte header and a 20-byte ACK)
     * carries `packetSize` payload bytes; the header cost is charged once per
     * packet, at least once even for a zero-byte transfer.
     */
    fun trancieveIpPacket(bytesTransferred: Int, ipv6: Boolean) {
        val header = (if (ipv6) 40 else 20) + 20
        val mtu = 1500
        val packetSize = mtu - header
        val overhead = maxOf(1, (bytesTransferred + packetSize - 1) / packetSize) * header
        stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].add(overhead)
        stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].add(overhead)
    }

    /**
     * Seeds the payload totals with [downloaded] / [uploaded] bytes carried over
     * from an earlier connection. (`add_stat`)
     */
    fun addStat(downloaded: Long, uploaded: Long) {
        stat[Channel.DOWNLOAD_PAYLOAD.ordinal].offset(downloaded)
        stat[Channel.UPLOAD_PAYLOAD.ordinal].offset(uploaded)
    }

    // --- per-second smoothing --------------------------------------------------

    /** Advances every channel's smoothed rate; call once per second. (`second_tick`) */
    fun secondTick(tickIntervalMs: Int) {
        for (i in 0 until NUM_CHANNELS) stat[i].secondTick(tickIntervalMs)
    }

    // --- rolled-up rates -------------------------------------------------------

    fun uploadRate(): Int =
        stat[Channel.UPLOAD_PAYLOAD.ordinal].rate() +
            stat[Channel.UPLOAD_PROTOCOL.ordinal].rate() +
            stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].rate()

    fun downloadRate(): Int =
        stat[Channel.DOWNLOAD_PAYLOAD.ordinal].rate() +
            stat[Channel.DOWNLOAD_PROTOCOL.ordinal].rate() +
            stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].rate()

    fun lowPassUploadRate(): Int =
        stat[Channel.UPLOAD_PAYLOAD.ordinal].lowPassRate() +
            stat[Channel.UPLOAD_PROTOCOL.ordinal].lowPassRate() +
            stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].lowPassRate()

    fun lowPassDownloadRate(): Int =
        stat[Channel.DOWNLOAD_PAYLOAD.ordinal].lowPassRate() +
            stat[Channel.DOWNLOAD_PROTOCOL.ordinal].lowPassRate() +
            stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].lowPassRate()

    fun uploadPayloadRate(): Int = stat[Channel.UPLOAD_PAYLOAD.ordinal].rate()
    fun downloadPayloadRate(): Int = stat[Channel.DOWNLOAD_PAYLOAD.ordinal].rate()

    // --- rolled-up totals ------------------------------------------------------

    fun totalUpload(): Long =
        stat[Channel.UPLOAD_PAYLOAD.ordinal].total() +
            stat[Channel.UPLOAD_PROTOCOL.ordinal].total() +
            stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].total()

    fun totalDownload(): Long =
        stat[Channel.DOWNLOAD_PAYLOAD.ordinal].total() +
            stat[Channel.DOWNLOAD_PROTOCOL.ordinal].total() +
            stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].total()

    fun totalPayloadUpload(): Long = stat[Channel.UPLOAD_PAYLOAD.ordinal].total()
    fun totalPayloadDownload(): Long = stat[Channel.DOWNLOAD_PAYLOAD.ordinal].total()

    fun totalProtocolUpload(): Long = stat[Channel.UPLOAD_PROTOCOL.ordinal].total()
    fun totalProtocolDownload(): Long = stat[Channel.DOWNLOAD_PROTOCOL.ordinal].total()

    fun totalTransfer(c: Channel): Long = stat[c.ordinal].total()
    fun transferRate(c: Channel): Int = stat[c.ordinal].rate()

    // --- current-tick counters / overhead -------------------------------------

    fun uploadIpOverhead(): Int = stat[Channel.UPLOAD_IP_PROTOCOL.ordinal].counter()
    fun downloadIpOverhead(): Int = stat[Channel.DOWNLOAD_IP_PROTOCOL.ordinal].counter()

    fun lastPayloadDownloaded(): Int = stat[Channel.DOWNLOAD_PAYLOAD.ordinal].counter()
    fun lastPayloadUploaded(): Int = stat[Channel.UPLOAD_PAYLOAD.ordinal].counter()
    fun lastProtocolDownloaded(): Int = stat[Channel.DOWNLOAD_PROTOCOL.ordinal].counter()
    fun lastProtocolUploaded(): Int = stat[Channel.UPLOAD_PROTOCOL.ordinal].counter()

    /** Resets all six channels. (`clear`) */
    fun clear() {
        for (i in 0 until NUM_CHANNELS) stat[i].clear()
    }

    companion object {
        /** The number of channels a [Stat] tracks; equals `Channel.entries.size`. */
        const val NUM_CHANNELS: Int = 6
    }
}
