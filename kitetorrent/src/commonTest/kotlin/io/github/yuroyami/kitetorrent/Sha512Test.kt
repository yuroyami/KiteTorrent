package io.github.yuroyami.kitetorrent

import io.github.yuroyami.kitetorrent.crypto.Sha512
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha512Test {
    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    @Test
    fun sha512_fips_vectors() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Hex.encode(Sha512.hash(ByteArray(0))),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Hex.encode(Sha512.hash(ascii("abc"))),
        )
        assertEquals(
            "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018" +
                "501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909",
            Hex.encode(Sha512.hash(ascii(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
            ))),
        )
    }
}
