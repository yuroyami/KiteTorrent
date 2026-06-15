package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.Digest32
import io.github.yuroyami.kitetorrent.Sha1Hash
import io.github.yuroyami.kitetorrent.Sha256Hash

/**
 * Incremental SHA-1 hasher producing a [Sha1Hash] — port of libtorrent's `hasher`
 * (hasher.hpp). This is the workhorse for v1 info-hashes and piece hashes.
 */
class Hasher {
    private val sha = Sha1()

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Hasher {
        sha.update(data, offset, length)
        return this
    }

    /** Finish and return the digest as a [Sha1Hash]. */
    fun final(): Sha1Hash = Digest32.sha1(sha.digest())

    companion object {
        /** One-shot SHA-1 over [data], as a [Sha1Hash]. */
        fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Sha1Hash =
            Digest32.sha1(Sha1.hash(data, offset, length))
    }
}

/**
 * Incremental SHA-256 hasher producing a [Sha256Hash] — port of libtorrent's
 * `hasher256`. Used for BitTorrent v2 piece hashing and merkle-tree nodes.
 */
class Hasher256 {
    private val sha = Sha256()

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Hasher256 {
        sha.update(data, offset, length)
        return this
    }

    /** Finish and return the digest as a [Sha256Hash]. */
    fun final(): Sha256Hash = Digest32.sha256(sha.digest())

    companion object {
        /** One-shot SHA-256 over [data], as a [Sha256Hash]. */
        fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Sha256Hash =
            Digest32.sha256(Sha256.hash(data, offset, length))
    }
}
