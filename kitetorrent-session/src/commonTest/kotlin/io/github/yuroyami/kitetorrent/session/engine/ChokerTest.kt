package io.github.yuroyami.kitetorrent.session.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChokerTest {

    @Test
    fun unchokesTopRatesPlusOptimistic() {
        val rates = mapOf("a" to 100L, "b" to 50L, "c" to 10L, "d" to 5L, "e" to 1L)
        // top 2 by rate are a,b; e is the optimistic slot
        assertEquals(setOf("a", "b", "e"), Choker.chooseUnchoked(rates, uploadSlots = 2, optimistic = "e"))
    }

    @Test
    fun ranksStrictlyByRate() {
        val rates = mapOf("slow" to 1L, "fast" to 999L, "mid" to 50L)
        assertEquals(setOf("fast"), Choker.chooseUnchoked(rates, uploadSlots = 1, optimistic = null))
    }

    @Test
    fun optimisticAlreadyInTopIsNotDuplicated() {
        val r = Choker.chooseUnchoked(mapOf("a" to 10L, "b" to 1L), uploadSlots = 2, optimistic = "a")
        assertEquals(setOf("a", "b"), r)
    }

    @Test
    fun zeroSlotsStillHonorsOptimistic() {
        assertEquals(setOf("x"), Choker.chooseUnchoked(mapOf("x" to 5L, "y" to 9L), uploadSlots = 0, optimistic = "x"))
    }

    @Test
    fun emptyInputIsEmpty() {
        assertTrue(Choker.chooseUnchoked(emptyMap<String, Long>(), uploadSlots = 4, optimistic = null).isEmpty())
    }
}
