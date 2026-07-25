package io.github.yuroyami.kitetorrent.crypto

import io.github.yuroyami.kitetorrent.math.BigInt

/**
 * Ed25519 signatures (RFC 8032), pure Kotlin. Used by libtorrent for BEP-44
 * mutable DHT items; the C++ lives under `src/ed25519/` (ref10: `ge.cpp`,
 * `fe.cpp`, `sc.cpp`, `sign.cpp`, `keypair.cpp`, `verify.cpp`).
 *
 * Rather than transcribing ref10's hand-rolled radix-2^25.5 field arithmetic and
 * its giant precomputed base-point table (extremely error-prone), this is a
 * compact textbook implementation:
 *
 *  - field arithmetic mod p = 2^255 - 19 via [BigInt];
 *  - the twisted Edwards curve  -x^2 + y^2 = 1 + d*x^2*y^2  in extended
 *    coordinates (X,Y,Z,T) with the standard add/double formulas;
 *  - scalar multiplication by constant-position double-and-add;
 *  - SHA-512 from [Sha512] for the key/nonce/challenge hashes;
 *  - scalars reduced mod the group order
 *    L = 2^252 + 27742317777372353535851937790883648493.
 *
 * Slower than ref10 but correct and small. Validated against the RFC 8032 /
 * libgcrypt vectors that libtorrent itself uses (see `test/test_ed25519.cpp`).
 *
 * Key layout matches libtorrent exactly:
 *  - `createKeypair(seed32)` -> (publicKey 32, privateKey 64) where
 *    privateKey = SHA-512(seed); privateKey[0..32) is the clamped scalar `a`,
 *    privateKey[32..64) is the nonce prefix.
 *  - `sign` / `verify` operate on those 32/64-byte blobs.
 */
object Ed25519 {

    /**
     * Derive a key pair from a 32-byte seed (libtorrent's `ed25519_create_keypair`).
     * @return Pair(publicKey 32 bytes, privateKey 64 bytes).
     */
    fun createKeypair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 32) { "seed must be 32 bytes, was ${seed.size}" }
        val privateKey = Sha512.hash(seed) // 64 bytes
        // clamp the lower half into a valid scalar
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = ((privateKey[31].toInt() and 63) or 64).toByte()

        val a = leToScalar(privateKey, 0)
        val publicKey = encodePoint(scalarMultBase(a))
        return publicKey to privateKey
    }

    /**
     * Sign [message] (libtorrent's `ed25519_sign`).
     * @param publicKey 32 bytes, @param privateKey 64 bytes (from [createKeypair]).
     * @return 64-byte signature R || S.
     */
    fun sign(message: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "public key must be 32 bytes" }
        require(privateKey.size == 64) { "private key must be 64 bytes" }

        // r = SHA-512(prefix || message) mod L
        val r = Sha512().update(privateKey, 32, 32).update(message).digest()
        val rScalar = reduceScalar(r)
        val rPoint = scalarMultBase(rScalar)
        val rEnc = encodePoint(rPoint) // first 32 bytes of signature

        // k = SHA-512(R || A || message) mod L
        val k = Sha512().update(rEnc).update(publicKey).update(message).digest()
        val kScalar = reduceScalar(k)

        // S = (r + k * a) mod L
        val a = leToScalar(privateKey, 0)
        val s = (rScalar + kScalar.modMul(a, L)).mod(L)

        val sig = ByteArray(64)
        rEnc.copyInto(sig, 0, 0, 32)
        scalarToLe(s).copyInto(sig, 32, 0, 32)
        return sig
    }

    /**
     * Verify [signature] over [message] under [publicKey] (libtorrent's
     * `ed25519_verify`). Returns true iff valid.
     */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        // S must be in [0, L). Reject the high bit (matches `signature[63] & 224` and more).
        if (signature[63].toInt() and 0xe0 != 0) return false

        val a = decodePoint(publicKey) ?: return false
        val s = leToScalar(signature, 32)
        if (s >= L) return false

        // k = SHA-512(R || A || message) mod L
        val k = Sha512().update(signature, 0, 32).update(publicKey).update(message).digest()
        val kScalar = reduceScalar(k)

        // Check [S]B == R + [k]A, i.e. [S]B - [k]A == R.
        val sb = scalarMultBase(s)
        val ka = scalarMult(kScalar, a)
        val r = add(sb, negate(ka))
        val rEnc = encodePoint(r)

        return rEnc.contentEquals(signature.copyOfRange(0, 32))
    }

    // ============================================================
    // Field arithmetic mod p = 2^255 - 19
    // ============================================================

    // p = 2^255 - 19
    private val P: BigInt = pow2(255) - BigInt.fromInt(19)

    // group order L = 2^252 + 27742317777372353535851937790883648493
    private val L: BigInt =
        pow2(252) + BigInt.fromHex("14def9dea2f79cd65812631a5cf5d3ed")

    // curve constant d = -121665 / 121666 mod p
    private val D: BigInt = run {
        val num = P - BigInt.fromInt(121665)          // -121665 mod p
        val den = BigInt.fromInt(121666)
        fieldMul(num, fieldInv(den))
    }

    // sqrt(-1) mod p = 2^((p-1)/4)
    private val SQRT_M1: BigInt = run {
        val e = (P - BigInt.ONE).let { divByPow2(it, 2) } // (p-1)/4
        BigInt.TWO.modPow(e, P)
    }

    private fun fieldAdd(a: BigInt, b: BigInt): BigInt = (a + b).mod(P)
    private fun fieldSub(a: BigInt, b: BigInt): BigInt = (a + P - b.mod(P)).mod(P)
    private fun fieldMul(a: BigInt, b: BigInt): BigInt = a.modMul(b, P)
    private fun fieldInv(a: BigInt): BigInt = a.modPow(P - BigInt.TWO, P) // Fermat

    // ============================================================
    // Twisted Edwards points in extended coords (X : Y : Z : T), T = XY/Z
    // Curve: -x^2 + y^2 = 1 + d x^2 y^2
    // ============================================================

    private class Pt(val x: BigInt, val y: BigInt, val z: BigInt, val t: BigInt)

    // Neutral element (0, 1).
    private val IDENTITY = Pt(BigInt.ZERO, BigInt.ONE, BigInt.ONE, BigInt.ZERO)

    // Base point B = (x, 4/5) with x even (the standard ed25519 generator).
    private val BASE: Pt = run {
        val y = fieldMul(BigInt.fromInt(4), fieldInv(BigInt.fromInt(5)))
        val x = recoverX(y, 0) ?: error("base point x recovery failed")
        Pt(x, y, BigInt.ONE, fieldMul(x, y))
    }

    /** Unified addition (independent of whether the points are equal). */
    private fun add(p1: Pt, p2: Pt): Pt {
        // RFC 8032 add-2008-hwcd-3 with a = -1
        val a = fieldMul(fieldSub(p1.y, p1.x), fieldSub(p2.y, p2.x))
        val b = fieldMul(fieldAdd(p1.y, p1.x), fieldAdd(p2.y, p2.x))
        val c = fieldMul(fieldMul(p1.t, p2.t), fieldMul(BigInt.TWO, D))
        val dd = fieldMul(fieldMul(p1.z, p2.z), BigInt.TWO)
        val e = fieldSub(b, a)
        val f = fieldSub(dd, c)
        val g = fieldAdd(dd, c)
        val h = fieldAdd(b, a)
        val x3 = fieldMul(e, f)
        val y3 = fieldMul(g, h)
        val t3 = fieldMul(e, h)
        val z3 = fieldMul(f, g)
        return Pt(x3, y3, z3, t3)
    }

    private fun negate(p: Pt): Pt = Pt(fieldSub(BigInt.ZERO, p.x), p.y, p.z, fieldSub(BigInt.ZERO, p.t))

    /** Constant-position double-and-add over the 253-bit scalar. */
    private fun scalarMult(scalar: BigInt, p: Pt): Pt {
        var result = IDENTITY
        var addend = p
        var i = 0
        val bits = 256
        while (i < bits) {
            if (scalar.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
            i++
        }
        return result
    }

    private fun scalarMultBase(scalar: BigInt): Pt = scalarMult(scalar, BASE)

    // ============================================================
    // Point encoding / decoding (RFC 8032 section 5.1.2 / 5.1.3)
    // ============================================================

    /** Encode point to 32 bytes: little-endian y, top bit = parity of x. */
    private fun encodePoint(p: Pt): ByteArray {
        val zInv = fieldInv(p.z)
        val x = fieldMul(p.x, zInv)
        val y = fieldMul(p.y, zInv)
        val out = fieldToLe32(y)
        // set high bit of last byte to LSB of x
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /**
     * Decode a 32-byte public key to a point, or null if it is not a valid
     * curve point (mirrors `ge_frombytes` returning non-zero).
     */
    private fun decodePoint(enc: ByteArray): Pt? {
        // y is the low 255 bits, little-endian; bit 255 is the sign of x.
        val tmp = enc.copyOf(32)
        val signBit = (tmp[31].toInt() ushr 7) and 1
        tmp[31] = (tmp[31].toInt() and 0x7f).toByte()
        val y = leToBig(tmp, 0, 32)
        if (y >= P) return null
        val x = recoverX(y, signBit) ?: return null
        return Pt(x, y, BigInt.ONE, fieldMul(x, y))
    }

    /**
     * Recover the x coordinate from y and the desired sign bit.
     * x^2 = (y^2 - 1) / (d y^2 + 1).  p = 5 (mod 8), so use the standard
     * sqrt-with-i candidate selection. Returns null if no square root exists.
     */
    private fun recoverX(y: BigInt, sign: Int): BigInt? {
        val y2 = fieldMul(y, y)
        val u = fieldSub(y2, BigInt.ONE)
        val v = fieldAdd(fieldMul(D, y2), BigInt.ONE)
        val vInv = fieldInv(v)
        val xx = fieldMul(u, vInv) // candidate for x^2

        // x = xx^((p+3)/8)
        val exp = divByPow2(P + BigInt.fromInt(3), 3) // (p+3)/8
        var x = xx.modPow(exp, P)

        // fix up: if x^2 != xx, multiply by sqrt(-1)
        if (fieldMul(x, x) != xx) {
            x = fieldMul(x, SQRT_M1)
        }
        if (fieldMul(x, x) != xx) return null // not on curve

        // adjust sign
        if (x.testBit(0) != (sign == 1)) {
            x = fieldSub(BigInt.ZERO, x)
        }
        // RFC: if sign==1 and x==0, reject
        if (sign == 1 && x.isZero) return null
        return x
    }

    // ============================================================
    // Scalar helpers (mod L), 32-byte little-endian encoding
    // ============================================================

    /** Reduce a 64-byte little-endian hash output mod L (== sc_reduce). */
    private fun reduceScalar(h64: ByteArray): BigInt = leToBig(h64, 0, 64).mod(L)

    /** Interpret 32 little-endian bytes at [off] as an integer (not reduced). */
    private fun leToScalar(b: ByteArray, off: Int): BigInt = leToBig(b, off, 32)

    /** Encode a scalar (< 2^256) as 32 little-endian bytes. */
    private fun scalarToLe(s: BigInt): ByteArray = bigToLe(s, 32)

    // ============================================================
    // little-endian <-> BigInt helpers
    // ============================================================

    private fun leToBig(b: ByteArray, off: Int, len: Int): BigInt {
        // reverse the bytes into big-endian, then parse
        val be = ByteArray(len)
        for (i in 0 until len) be[i] = b[off + len - 1 - i]
        return BigInt.fromBytes(be)
    }

    private fun bigToLe(v: BigInt, len: Int): ByteArray {
        val be = v.toFixedBytes(len)
        val le = ByteArray(len)
        for (i in 0 until len) le[i] = be[len - 1 - i]
        return le
    }

    private fun fieldToLe32(v: BigInt): ByteArray = bigToLe(v.mod(P), 32)

    // ============================================================
    // tiny constant helpers
    // ============================================================

    private fun pow2(n: Int): BigInt {
        // 2^n as a BigInt
        val bytes = ByteArray(n / 8 + 1)
        bytes[bytes.size - 1 - (n / 8)] = (1 shl (n % 8)).toByte()
        return BigInt.fromBytes(bytes)
    }

    /** Exact division of v by 2^k (v must be a multiple of 2^k). */
    private fun divByPow2(v: BigInt, k: Int): BigInt {
        // build (v >> k) by reconstructing from bits
        val nbits = v.bitLength()
        if (nbits <= k) return BigInt.ZERO
        val outBytes = ByteArray((nbits - k + 7) / 8)
        var i = k
        while (i < nbits) {
            if (v.testBit(i)) {
                val pos = i - k
                outBytes[outBytes.size - 1 - (pos ushr 3)] =
                    (outBytes[outBytes.size - 1 - (pos ushr 3)].toInt() or (1 shl (pos and 7))).toByte()
            }
            i++
        }
        return BigInt.fromBytes(outBytes)
    }
}
