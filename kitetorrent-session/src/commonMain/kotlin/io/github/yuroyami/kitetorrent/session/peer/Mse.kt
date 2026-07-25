package io.github.yuroyami.kitetorrent.session.peer

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.crypto.DhKeyExchange
import io.github.yuroyami.kitetorrent.crypto.MseCrypto
import io.github.yuroyami.kitetorrent.crypto.MseEncryption
import io.github.yuroyami.kitetorrent.crypto.Rc4
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raised when the MSE/PE handshake fails (bad VC, unknown torrent, sync not found, …). */
class MseException(message: String) : Exception(message)

/**
 * A [ByteStream] that transparently RC4-encrypts writes and decrypts reads for an
 * established MSE/PE connection. This is the live counterpart of libtorrent's `rc4_handler`
 * sitting in `bt_peer_connection`'s send/recv path. RC4 is a byte-stream cipher, so the
 * chunk boundaries a [PeerConnection] happens to read/write in are irrelevant; only the
 * total byte order matters, and the per-direction locks keep encrypt-order == wire-order.
 */
class MseByteStream(private val raw: ByteStream, private val enc: MseEncryption) : ByteStream {
    private val writeLock = Mutex()
    private val readLock = Mutex()

    override suspend fun readExactly(n: Int): ByteArray =
        readLock.withLock { enc.decrypt(raw.readExactly(n)) }

    override suspend fun write(bytes: ByteArray) =
        writeLock.withLock { raw.write(enc.encrypt(bytes)) }

    override val remoteAddress: String get() = raw.remoteAddress
}

/**
 * A [ByteStream] that serves [prefix] bytes first, then delegates to [raw]. Lets the engine
 * peek the opening bytes of an inbound connection (to tell plaintext from MSE) and then hand
 * them back to the chosen handler as if never consumed.
 */
class PrefixedByteStream(prefix: ByteArray, private val raw: ByteStream) : ByteStream {
    private val pre = prefix
    private var pos = 0

    override suspend fun readExactly(n: Int): ByteArray {
        if (pos >= pre.size) return raw.readExactly(n)
        val fromPre = minOf(n, pre.size - pos)
        val head = pre.copyOfRange(pos, pos + fromPre)
        pos += fromPre
        return if (fromPre == n) head else head + raw.readExactly(n - fromPre)
    }

    override suspend fun write(bytes: ByteArray) = raw.write(bytes)
    override val remoteAddress: String get() = raw.remoteAddress
}

/**
 * The MSE/PE (Message Stream Encryption / Protocol Encryption) handshake. This is the
 * live automaton libtorrent runs in `bt_peer_connection.cpp` (`write_pe1_2_dhkey`,
 * `write_pe3_sync`, `write_pe4_sync`, `read_pe_*`). It performs the Diffie-Hellman
 * exchange, the sync-hash and VC obfuscation steps, and crypto negotiation, then returns a
 * transport ([MseByteStream] when RC4 is selected, the raw stream when plaintext is) over
 * which the normal BitTorrent handshake proceeds.
 *
 * Wire layout (https://wiki.vuze.com/w/Message_Stream_Encryption):
 * ```
 * A->B: Ya, PadA
 * B->A: Yb, PadB
 * A->B: HASH('req1', S), HASH('req2', SKEY) xor HASH('req3', S),
 *       ENCRYPT(VC, crypto_provide, len(PadC), PadC, len(IA), IA)
 * B->A: ENCRYPT(VC, crypto_select, len(PadD), PadD), ENCRYPT2(payload)
 * ```
 * `S` is the DH shared secret, `SKEY` the v1 info-hash. We send an empty IA (len 0) and
 * run the BitTorrent handshake over the established channel afterwards. That costs one
 * extra round-trip compared with sending it inside IA, but it is simpler and fully
 * interoperable.
 */
object Mse {

    /** Max padding (PadA/PadB/PadC/PadD) length, per the spec. */
    const val MAX_PAD = 512

    /** A small safety margin added to [MAX_PAD] when scanning for a sync pattern. */
    private const val SYNC_MARGIN = 16

    /**
     * Allowed encryption level (`settings_pack::allowed_enc_level`), expressed as the
     * crypto bitmask the spec puts on the wire: plaintext = `0x01` ([MseCrypto.CRYPTO_PLAINTEXT]),
     * rc4 = `0x02` ([MseCrypto.CRYPTO_RC4]), both = `0x03` ([MseCrypto.CRYPTO_BOTH]). These
     * values intentionally equal libtorrent's `enc_level` enum (`pe_plaintext`=1,
     * `pe_rc4`=2, `pe_both`=3), so a [io.github.yuroyami.kitetorrent.settings.EncLevel]
     * value can be passed straight through.
     */
    const val ALLOW_PLAINTEXT: Int = MseCrypto.CRYPTO_PLAINTEXT
    const val ALLOW_RC4: Int = MseCrypto.CRYPTO_RC4
    const val ALLOW_BOTH: Int = MseCrypto.CRYPTO_BOTH

    /**
     * Active open (we dialed): run the MSE handshake as the initiator, offering the crypto
     * methods permitted by [allowedEncLevel]. Returns the negotiated transport
     * ([MseByteStream] for RC4, [raw] for plaintext). Throws [MseException] on any
     * verification failure.
     *
     * @param allowedEncLevel one of [ALLOW_PLAINTEXT] / [ALLOW_RC4] / [ALLOW_BOTH]
     *   (== `settings_pack::allowed_enc_level`); defaults to [ALLOW_BOTH] so existing call
     *   sites keep offering both methods. Any value not intersecting [ALLOW_BOTH] falls
     *   back to offering both, mirroring `bt_peer_connection::write_pe1_2_dhkey`.
     */
    suspend fun initiate(
        raw: ByteStream,
        infoHash: Sha1Hash,
        rng: Random = Random.Default,
        allowedEncLevel: Int = ALLOW_BOTH,
    ): ByteStream {
        val dh = DhKeyExchange.random(rng)
        raw.write(dh.publicKey + randomPad(rng))                         // A->B: Ya, PadA
        val yb = raw.readExactly(DhKeyExchange.KEY_LEN)                  // B->A: Yb (PadB follows, skipped at the VC sync)
        val secret = dh.computeSecret(yb)

        // outgoing peer encrypts with keyA, decrypts with keyB
        val enc = MseCrypto.rc4(secret, infoHash, outgoing = true)

        // crypto_provide: the bits we permit. An invalid level (no overlap with BOTH) offers both,
        // exactly as bt_peer_connection::write_pe1_2_dhkey does (enc_level & pe_both == 0 ? both : level).
        val cryptoProvide =
            if (allowedEncLevel and MseCrypto.CRYPTO_BOTH == 0) MseCrypto.CRYPTO_BOTH else allowedEncLevel and MseCrypto.CRYPTO_BOTH

        // A->B: req1 sync hash || obfuscated SKEY hash || ENCRYPT(VC, provide, lenPadC=0, lenIA=0)
        val cleartext = MseCrypto.VERIFICATION_CONSTANT + u32(cryptoProvide) + u16(0) + u16(0)
        raw.write(
            MseCrypto.reqSyncHash(secret).toByteArray() +
                MseCrypto.obfuscatedStreamKeyHash(secret, infoHash).toByteArray() +
                enc.encrypt(cleartext),
        )

        // B->A: sync to the encrypted VC (skipping PadB), then read crypto_select + PadD
        val vcPattern = Rc4(MseCrypto.keyB(secret, infoHash).toByteArray())
            .also { it.discard(MseCrypto.RC4_DISCARD) }
            .process(ByteArray(MseCrypto.VC_LEN))
        syncToPattern(raw, vcPattern, MAX_PAD + SYNC_MARGIN)
        val vc = enc.decrypt(vcPattern)                                  // advance + verify the keystream is aligned
        if (!vc.all { it.toInt() == 0 }) throw MseException("MSE: verification constant mismatch")

        val select = u32be(enc.decrypt(raw.readExactly(4)))
        val padD = u16be(enc.decrypt(raw.readExactly(2)))
        if (padD > 0) enc.decrypt(raw.readExactly(padD))                 // consume PadD (advance the stream)

        return finish(raw, enc, select)
    }

    /**
     * Passive open for a single torrent [infoHash]. Returns the negotiated transport.
     * Throws [MseException] if the obfuscated SKEY doesn't match or verification fails.
     */
    suspend fun accept(
        raw: ByteStream,
        infoHash: Sha1Hash,
        rng: Random = Random.Default,
        allowedEncLevel: Int = ALLOW_BOTH,
        preferRc4: Boolean = true,
    ): ByteStream =
        accept(raw, listOf(infoHash), rng, allowedEncLevel, preferRc4).first

    /**
     * Passive open (we accepted a connection that began with a DH key rather than the
     * plaintext BitTorrent handshake): run the MSE handshake as the receiver, resolving
     * which torrent the peer wants from the obfuscated SKEY against [infoHashes]. Returns
     * the negotiated transport plus the matched info-hash. Throws [MseException] if no
     * candidate matches or verification fails.
     */
    suspend fun accept(
        raw: ByteStream,
        infoHashes: List<Sha1Hash>,
        rng: Random = Random.Default,
        allowedEncLevel: Int = ALLOW_BOTH,
        preferRc4: Boolean = false,
    ): Pair<ByteStream, Sha1Hash> {
        val dh = DhKeyExchange.random(rng)
        val ya = raw.readExactly(DhKeyExchange.KEY_LEN)                  // A->B: Ya (PadA follows, skipped at the req1 sync)
        val secret = dh.computeSecret(ya)
        raw.write(dh.publicKey + randomPad(rng))                        // B->A: Yb, PadB

        // sync to req1 hash (skipping PadA), then resolve the torrent from the obfuscated SKEY
        syncToPattern(raw, MseCrypto.reqSyncHash(secret).toByteArray(), MAX_PAD + SYNC_MARGIN)
        val obf = raw.readExactly(Digest32.SHA1_SIZE)
        val infoHash = infoHashes.firstOrNull {
            obf.contentEquals(MseCrypto.obfuscatedStreamKeyHash(secret, it).toByteArray())
        } ?: throw MseException("MSE: stream key (SKEY) matches no active torrent")

        // receiver encrypts with keyB, decrypts with keyA
        val enc = MseCrypto.rc4(secret, infoHash, outgoing = false)

        val vc = enc.decrypt(raw.readExactly(MseCrypto.VC_LEN))
        if (!vc.all { it.toInt() == 0 }) throw MseException("MSE: verification constant mismatch")
        val provide = u32be(enc.decrypt(raw.readExactly(4)))
        val padC = u16be(enc.decrypt(raw.readExactly(2)))
        if (padC > 0) enc.decrypt(raw.readExactly(padC))
        val iaLen = u16be(enc.decrypt(raw.readExactly(2)))
        if (iaLen > 0) enc.decrypt(raw.readExactly(iaLen))              // initial payload (we expect none)

        // crypto_select: mask the peer's offer by what we allow, then pick a single bit.
        // Port of bt_peer_connection.cpp:3120-3148. When prefer_rc4 keep the most significant
        // set bit (rc4), otherwise the least significant (plaintext); empty intersection fails.
        val allowed = if (allowedEncLevel and MseCrypto.CRYPTO_BOTH == 0) MseCrypto.CRYPTO_BOTH
        else allowedEncLevel and MseCrypto.CRYPTO_BOTH
        val masked = provide and allowed
        val select = when {
            masked == 0 ->
                throw MseException("MSE: peer offered no allowed crypto (provide=$provide, allowed=$allowed)")
            preferRc4 && masked and MseCrypto.CRYPTO_RC4 != 0 -> MseCrypto.CRYPTO_RC4
            masked and MseCrypto.CRYPTO_PLAINTEXT != 0 -> MseCrypto.CRYPTO_PLAINTEXT
            else -> MseCrypto.CRYPTO_RC4
        }
        // B->A: ENCRYPT(VC, crypto_select, len(PadD)=0)
        raw.write(enc.encrypt(MseCrypto.VERIFICATION_CONSTANT + u32(select) + u16(0)))

        return finish(raw, enc, select) to infoHash
    }

    /**
     * True if [bytes] begin with a plaintext BitTorrent handshake (`0x13` + "BitTorrent
     * protocol"). The engine uses this to tell a plaintext peer from an MSE peer
     * (whose first bytes are a random DH public key).
     */
    fun looksLikePlaintextHandshake(bytes: ByteArray): Boolean {
        val pstr = "BitTorrent protocol".encodeToByteArray()
        if (bytes.size < 1 + pstr.size) return false
        if (bytes[0].toInt() != 19) return false
        for (i in pstr.indices) if (bytes[1 + i] != pstr[i]) return false
        return true
    }

    private fun finish(raw: ByteStream, enc: MseEncryption, select: Int): ByteStream = when (select) {
        MseCrypto.CRYPTO_RC4 -> MseByteStream(raw, enc)
        MseCrypto.CRYPTO_PLAINTEXT -> raw
        else -> throw MseException("MSE: unsupported crypto selection $select")
    }

    /**
     * Read and discard bytes from [raw] until its most-recent [pattern].size bytes equal
     * [pattern] (which is itself consumed). This is the resync libtorrent does to find the start
     * of the obfuscated handshake past a variable-length pad. Fails after [maxSkip] bytes
     * so a non-MSE peer can't make us read forever.
     */
    private suspend fun syncToPattern(raw: ByteStream, pattern: ByteArray, maxSkip: Int) {
        val window = ByteArray(pattern.size)
        var filled = 0
        var skipped = 0
        while (true) {
            val b = raw.readExactly(1)[0]
            if (filled < window.size) {
                window[filled++] = b
            } else {
                window.copyInto(window, 0, 1, window.size)
                window[window.size - 1] = b
                skipped++
            }
            if (filled == window.size && window.contentEquals(pattern)) return
            if (skipped > maxSkip) throw MseException("MSE: sync pattern not found within $maxSkip bytes")
        }
    }

    private fun randomPad(rng: Random): ByteArray = ByteArray(rng.nextInt(MAX_PAD + 1)).also { rng.nextBytes(it) }

    private fun u16(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun u16be(b: ByteArray): Int = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    private fun u32be(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
