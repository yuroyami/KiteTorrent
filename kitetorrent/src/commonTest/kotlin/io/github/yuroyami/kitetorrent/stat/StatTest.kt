package io.github.yuroyami.kitetorrent.stat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [StatChannel] / [Stat] — the port of libtorrent's `stat_channel` and
 * `stat` (stat.hpp, stat.cpp).
 *
 * Golden rate values are computed by hand from `stat_channel::second_tick`:
 * ```
 * sample     = counter * 1000 / tickIntervalMs   (integer)
 * fiveSecAvg = fiveSecAvg * 4 / 5 + sample / 5    (integer)
 * counter    = 0
 * ```
 */
class StatTest {

    // --- StatChannel -----------------------------------------------------------

    @Test
    fun channelAccumulatesTotalAndCounter() {
        val c = StatChannel()
        assertEquals(0L, c.total())
        assertEquals(0, c.counter())

        c.add(100)
        c.add(50)
        assertEquals(150L, c.total())
        assertEquals(150, c.counter()) // not ticked yet
        assertEquals(0, c.rate())      // rate only updates on tick
    }

    @Test
    fun secondTickComputesSmoothedRateAndResetsCounter() {
        val c = StatChannel()
        // tick1: counter 1000, dt 1000ms -> sample 1000, avg = 0*4/5 + 1000/5 = 200
        c.add(1000)
        c.secondTick(1000)
        assertEquals(200, c.rate())
        assertEquals(0, c.counter()) // counter reset
        assertEquals(1000L, c.total())

        // tick2: counter 1000 again -> avg = 200*4/5 + 200 = 360
        c.add(1000)
        c.secondTick(1000)
        assertEquals(360, c.rate())
        assertEquals(2000L, c.total())
    }

    @Test
    fun rateConvergesTowardConstantInput() {
        val c = StatChannel()
        // Feeding a steady 1000 bytes/sec, the smoothed rate must climb monotonically
        // toward 1000 and never overshoot it.
        var prev = 0
        repeat(60) {
            c.add(1000)
            c.secondTick(1000)
            val r = c.rate()
            assertTrue(r >= prev, "rate must be non-decreasing under constant load")
            assertTrue(r <= 1000, "smoothed rate must not exceed the steady input")
            prev = r
        }
        // after a minute of steady load it should be very close to 1000
        assertTrue(prev > 950, "rate should approach 1000, was $prev")
    }

    @Test
    fun secondTickNormalisesToBytesPerSecond() {
        // 500 bytes over a 500ms tick == 1000 bytes/sec -> sample 1000, avg 200.
        val c = StatChannel()
        c.add(500)
        c.secondTick(500)
        assertEquals(200, c.rate())
    }

    @Test
    fun offsetAdjustsTotalOnly() {
        val c = StatChannel()
        c.add(100)
        c.offset(1_000_000L)
        assertEquals(1_000_100L, c.total())
        assertEquals(100, c.counter()) // counter untouched
    }

    @Test
    fun plusAssignFoldsCurrentCounterIntoTotalAndCounter() {
        // operator+= adds the SOURCE counter to both this counter and this total.
        val a = StatChannel()
        a.add(100)
        val b = StatChannel()
        b.add(40)
        a += b
        assertEquals(140, a.counter())
        assertEquals(140L, a.total())
        // b is unchanged
        assertEquals(40, b.counter())
    }

    @Test
    fun clearResetsEverything() {
        val c = StatChannel()
        c.add(1000)
        c.secondTick(1000)
        c.clear()
        assertEquals(0L, c.total())
        assertEquals(0, c.counter())
        assertEquals(0, c.rate())
    }

    // --- Stat ------------------------------------------------------------------

    @Test
    fun statSplitsPayloadAndProtocol() {
        val s = Stat()
        s.receivedBytes(1000, 50)
        s.sentBytes(200, 30)

        assertEquals(1000L, s.totalPayloadDownload())
        assertEquals(50L, s.totalProtocolDownload())
        assertEquals(200L, s.totalPayloadUpload())
        assertEquals(30L, s.totalProtocolUpload())

        // totals roll payload + protocol + ip overhead (ip overhead is 0 here)
        assertEquals(1050L, s.totalDownload())
        assertEquals(230L, s.totalUpload())

        // current-tick counters
        assertEquals(1000, s.lastPayloadDownloaded())
        assertEquals(200, s.lastPayloadUploaded())
        assertEquals(30, s.lastProtocolUploaded())
    }

    @Test
    fun statRatesAggregateChannelsAfterTick() {
        val s = Stat()
        s.receivedBytes(1000, 0) // download payload
        s.sentBytes(500, 0)      // upload payload
        s.secondTick(1000)
        // each channel: avg = sample/5; download payload sample 1000 -> 200
        assertEquals(200, s.downloadRate())
        assertEquals(200, s.downloadPayloadRate())
        assertEquals(100, s.uploadRate()) // 500/5
        assertEquals(100, s.uploadPayloadRate())
    }

    @Test
    fun sentSynChargesIpOverhead() {
        val s = Stat()
        s.sentSyn(false) // IPv4 -> 40 bytes on upload IP protocol
        assertEquals(40, s.uploadIpOverhead())
        assertEquals(40L, s.totalUpload()) // rolled into upload total via ip channel
        s.sentSyn(true) // IPv6 -> +60
        assertEquals(100, s.uploadIpOverhead())
    }

    @Test
    fun receivedSynackChargesBothDirections() {
        val s = Stat()
        s.receivedSynack(false) // IPv4 -> 40 up and 40 down
        assertEquals(40, s.uploadIpOverhead())
        assertEquals(40, s.downloadIpOverhead())
    }

    @Test
    fun trancieveIpPacketChargesPerPacketHeader() {
        val s = Stat()
        // IPv4 header = 20 + 20 = 40; mtu 1500; packetSize = 1460.
        // A 1-byte transfer is 1 packet -> overhead 40 (charged up AND down).
        s.trancieveIpPacket(1, false)
        assertEquals(40, s.uploadIpOverhead())
        assertEquals(40, s.downloadIpOverhead())

        // 1461 bytes -> ceil(1461/1460) = 2 packets -> overhead 80.
        val s2 = Stat()
        s2.trancieveIpPacket(1461, false)
        assertEquals(80, s2.uploadIpOverhead())
        assertEquals(80, s2.downloadIpOverhead())

        // zero-byte transfer still costs one header.
        val s3 = Stat()
        s3.trancieveIpPacket(0, false)
        assertEquals(40, s3.uploadIpOverhead())
    }

    @Test
    fun addStatOffsetsPayloadTotals() {
        val s = Stat()
        s.addStat(downloaded = 5000L, uploaded = 3000L)
        assertEquals(5000L, s.totalPayloadDownload())
        assertEquals(3000L, s.totalPayloadUpload())
        // counters stay zero (offset only touches totals)
        assertEquals(0, s.lastPayloadDownloaded())
    }

    @Test
    fun plusAssignFoldsAllChannels() {
        val a = Stat()
        a.receivedBytes(100, 10)
        val b = Stat()
        b.receivedBytes(40, 5)
        a += b
        // current-tick download payload counter folds 100 + 40
        assertEquals(140, a.lastPayloadDownloaded())
        assertEquals(15, a.lastProtocolDownloaded())
    }

    @Test
    fun indexedAccessExposesChannels() {
        val s = Stat()
        s.sentBytes(700, 0)
        assertEquals(700L, s[Stat.Channel.UPLOAD_PAYLOAD].total())
        assertEquals(700L, s.totalTransfer(Stat.Channel.UPLOAD_PAYLOAD))
    }

    @Test
    fun statClearResetsAllChannels() {
        val s = Stat()
        s.receivedBytes(1000, 100)
        s.sentBytes(500, 50)
        s.secondTick(1000)
        s.clear()
        assertEquals(0L, s.totalDownload())
        assertEquals(0L, s.totalUpload())
        assertEquals(0, s.downloadRate())
    }

    @Test
    fun channelCountMatchesEnum() {
        assertEquals(Stat.NUM_CHANNELS, Stat.Channel.entries.size)
    }
}
