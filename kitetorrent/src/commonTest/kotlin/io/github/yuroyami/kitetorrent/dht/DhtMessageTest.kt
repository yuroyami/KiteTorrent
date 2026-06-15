package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.bencode.Bdecode
import io.github.yuroyami.kitetorrent.bencode.Bencode
import io.github.yuroyami.kitetorrent.bencode.Entry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the DHT KRPC codec ([DhtMessage], [CompactNodes]/[DhtEndpoint]) and the
 * in-memory [DhtStorage] — the ports of `src/kademlia/{node,msg,dht_storage}.cpp`
 * and the `find_data`/`get_peers`/`get_item`/`put_data` query builders.
 *
 * Round-trips go through the real [Bencode]/[Bdecode] codec so the bytes exercised
 * are exactly the on-wire form.
 */
class DhtMessageTest {

    private val myId = Sha1Hash.fromHex("0123456789abcdef0123456789abcdef01234567")
    private val theirId = Sha1Hash.fromHex("fedcba9876543210fedcba9876543210fedcba98")
    private val tid = byteArrayOf('a'.code.toByte(), 'b'.code.toByte())

    private fun roundTrip(env: Entry): DhtMessage? =
        DhtMessage.parse(Bdecode.decode(Bencode.encode(env)))

    // --- ping query round-trip ---------------------------------------------------

    @Test
    fun pingQueryRoundTrip() {
        val env = DhtMessage.buildPingQuery(tid, myId)
        val bytes = Bencode.encode(env)

        // exact bencoding: d1:ad2:id20:<id>e1:q4:ping1:t2:ab1:y1:qe
        val expected =
            "1:ad2:id20:".encodeToByteArray() + myId.toByteArray() +
                "e1:q4:ping1:t2:ab1:y1:qe".encodeToByteArray()
        // whole envelope is the dict 'd' + body
        val whole = "d".encodeToByteArray() + expected
        assertContentEquals(whole, bytes, "ping query bencoding must match BEP-5 layout")

        val msg = DhtMessage.parse(Bdecode.decode(bytes))
        assertNotNull(msg)
        assertTrue(msg is DhtMessage.Query)
        assertEquals('q', msg.messageType)
        assertEquals("ping", msg.query)
        assertEquals(myId, msg.nodeId)
        assertContentEquals(tid, msg.transactionId)
        assertTrue(msg.args is DhtMessage.Args.Ping)
    }

    // --- ping response round-trip ------------------------------------------------

    @Test
    fun pingResponseRoundTrip() {
        val env = DhtMessage.buildPingResponse(tid, theirId)
        val msg = roundTrip(env)
        assertNotNull(msg)
        assertTrue(msg is DhtMessage.Response)
        assertEquals('r', msg.messageType)
        assertEquals(theirId, msg.nodeId)
        assertContentEquals(tid, msg.transactionId)
        assertTrue(msg.reply is DhtMessage.Reply.Pong)
    }

    // --- compact node-info encode/decode for 1.2.3.4:6881 ------------------------

    @Test
    fun compactEndpointV4() {
        val ep = DhtEndpoint.of("1.2.3.4", 6881)
        assertNotNull(ep)
        assertTrue(ep.isV4)
        // 01 02 03 04 1A E1   (6881 = 0x1AE1)
        val expected = byteArrayOf(1, 2, 3, 4, 0x1A, 0xE1.toByte())
        assertContentEquals(expected, ep.encode())

        val decoded = DhtEndpoint.decodeV4(expected)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), decoded.address)
        assertEquals(6881, decoded.port)
        assertEquals("1.2.3.4", decoded.addressLiteral())
        assertEquals(ep, decoded)
    }

    @Test
    fun compactNodeRoundTrip() {
        val id = Sha1Hash.fromHex("00112233445566778899aabbccddeeff00112233")
        val ep = DhtEndpoint.of("1.2.3.4", 6881)!!
        val node = CompactNode(id, ep)

        val encoded = CompactNodes.encode(listOf(node))
        // 20-byte id + 6-byte v4 endpoint = 26 bytes
        assertEquals(CompactNodes.V4_NODE_SIZE, encoded.size)
        assertContentEquals(id.toByteArray() + byteArrayOf(1, 2, 3, 4, 0x1A, 0xE1.toByte()), encoded)

        val decoded = CompactNodes.decodeV4(encoded)
        assertEquals(1, decoded.size)
        assertEquals(id, decoded[0].id)
        assertEquals(ep, decoded[0].endpoint)

        // a trailing partial record is ignored
        val withJunk = encoded + byteArrayOf(0x42)
        assertEquals(1, CompactNodes.decodeV4(withJunk).size)
    }

    @Test
    fun compactEndpointV6RoundTrip() {
        // 2001:db8::1 port 1
        val ep = DhtEndpoint.of("2001:db8::1", 1)
        assertNotNull(ep)
        assertTrue(ep.isV6)
        assertEquals(DhtEndpoint.V6_SIZE, ep.encode().size)
        val decoded = DhtEndpoint.decodeV6(ep.encode())
        assertEquals(1, decoded.port)
        assertContentEquals(ep.address, decoded.address)
    }

    // --- find_node query + nodes response ----------------------------------------

    @Test
    fun findNodeRoundTrip() {
        val target = Sha1Hash.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val q = DhtMessage.buildFindNodeQuery(tid, myId, target)
        val pq = roundTrip(q)
        assertTrue(pq is DhtMessage.Query)
        val args = pq.args
        assertTrue(args is DhtMessage.Args.FindNode)
        assertEquals(target, args.target)

        val node = CompactNode(theirId, DhtEndpoint.of("9.8.7.6", 1234)!!)
        val r = DhtMessage.buildNodesResponse(tid, theirId, nodes = listOf(node))
        val pr = roundTrip(r)
        assertTrue(pr is DhtMessage.Response)
        val reply = pr.reply
        assertTrue(reply is DhtMessage.Reply.Nodes)
        assertEquals(1, reply.nodes.size)
        assertEquals(theirId, reply.nodes[0].id)
        assertEquals(1234, reply.nodes[0].endpoint.port)
    }

    // --- get_peers query + response with values ----------------------------------

    @Test
    fun getPeersRoundTrip() {
        val ih = Sha1Hash.fromHex("1111111111111111111111111111111111111111")
        val q = DhtMessage.buildGetPeersQuery(tid, myId, ih, noseed = true)
        val pq = roundTrip(q)
        assertTrue(pq is DhtMessage.Query)
        val args = pq.args
        assertTrue(args is DhtMessage.Args.GetPeers)
        assertEquals(ih, args.infoHash)
        assertTrue(args.noseed)

        val token = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val peer = DhtEndpoint.of("1.2.3.4", 6881)!!
        val r = DhtMessage.buildPeersResponse(tid, theirId, token, values = listOf(peer))
        val pr = roundTrip(r)
        assertTrue(pr is DhtMessage.Response)
        val reply = pr.reply
        assertTrue(reply is DhtMessage.Reply.Peers)
        assertContentEquals(token, reply.token)
        assertEquals(1, reply.values.size)
        assertEquals(peer, reply.values[0])
    }

    // --- announce_peer query parse -----------------------------------------------

    @Test
    fun announcePeerQueryParse() {
        val ih = Sha1Hash.fromHex("2222222222222222222222222222222222222222")
        val token = byteArrayOf(1, 2, 3, 4)
        val q = DhtMessage.buildAnnouncePeerQuery(
            tid, myId, ih, port = 6881, token = token, impliedPort = true, seed = true, name = "hello",
        )
        val pq = roundTrip(q)
        assertTrue(pq is DhtMessage.Query)
        assertEquals("announce_peer", pq.query)
        val args = pq.args
        assertTrue(args is DhtMessage.Args.AnnouncePeer)
        assertEquals(ih, args.infoHash)
        assertEquals(6881, args.port)
        assertContentEquals(token, args.token)
        assertTrue(args.impliedPort)
        assertTrue(args.seed)
        assertEquals("hello", args.name)

        // announce_peer reply is a bare pong (id only)
        val pr = roundTrip(DhtMessage.buildPingResponse(tid, theirId))
        assertTrue(pr is DhtMessage.Response && pr.reply is DhtMessage.Reply.Pong)
    }

    // --- error round-trip --------------------------------------------------------

    @Test
    fun errorRoundTrip() {
        val env = DhtMessage.buildError(tid, "invalid token", 203)
        val msg = roundTrip(env)
        assertNotNull(msg)
        assertTrue(msg is DhtMessage.ErrorMessage)
        assertEquals('e', msg.messageType)
        assertEquals(203, msg.code)
        assertEquals("invalid token", msg.message)
    }

    // --- put/get immutable item round-trip ---------------------------------------

    @Test
    fun putImmutableQueryRoundTrip() {
        val item = ImmutableItem.of(Entry.of("Hello World!"))
        val token = byteArrayOf(9, 9, 9)
        val q = DhtMessage.buildPutQuery(tid, myId, token, item)
        val pq = roundTrip(q)
        assertTrue(pq is DhtMessage.Query)
        assertEquals("put", pq.query)
        val args = pq.args
        assertTrue(args is DhtMessage.Args.Put)
        assertContentEquals(token, args.token)
        // the parsed item must hash to the same immutable target
        assertEquals(item.target, args.item.target)
        assertEquals(false, args.item.isMutable)
        // the bencoded value round-trips byte-for-byte (1:v12:Hello World! → "12:Hello World!")
        assertContentEquals(item.bencodedValue, args.item.bencodedValue)
    }

    @Test
    fun getImmutableResponseRoundTrip() {
        val item = ImmutableItem.of(Entry.of("Hello World!"))
        val token = byteArrayOf(5, 5)
        val r = DhtMessage.buildItemResponse(tid, theirId, token, item = item)
        val pr = roundTrip(r)
        assertTrue(pr is DhtMessage.Response)
        val reply = pr.reply
        assertTrue(reply is DhtMessage.Reply.Item)
        assertContentEquals(token, reply.token)
        val got = reply.item
        assertNotNull(got)
        assertEquals(item.target, got.target)
        assertContentEquals(item.bencodedValue, got.bencodedValue)
    }

    @Test
    fun getQueryWithSeq() {
        val target = Sha1Hash.fromHex("3333333333333333333333333333333333333333")
        val q = DhtMessage.buildGetQuery(tid, myId, target, seq = 7L)
        val pq = roundTrip(q)
        assertTrue(pq is DhtMessage.Query)
        val args = pq.args
        assertTrue(args is DhtMessage.Args.Get)
        assertEquals(target, args.target)
        assertEquals(7L, args.seq)

        // without seq → null seq
        val q2 = roundTrip(DhtMessage.buildGetQuery(tid, myId, target))
        assertTrue(q2 is DhtMessage.Query && (q2.args as DhtMessage.Args.Get).seq == null)
    }

    // --- malformed inputs --------------------------------------------------------

    @Test
    fun parseRejectsMalformed() {
        // not a dict
        assertNull(DhtMessage.parse(Bdecode.decode("li1ee".encodeToByteArray())))
        // missing 'y'
        assertNull(DhtMessage.parse(Bdecode.decode("d1:t2:abe".encodeToByteArray())))
        // unknown 'y'
        assertNull(DhtMessage.parse(Bdecode.decode("d1:t2:ab1:y1:ze".encodeToByteArray())))
        // query with a non-20-byte id
        assertNull(DhtMessage.parse(Bdecode.decode("d1:ad2:id3:abce1:q4:ping1:t2:ab1:y1:qe".encodeToByteArray())))
    }
}
