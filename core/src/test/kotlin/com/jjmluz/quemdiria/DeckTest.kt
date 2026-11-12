package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckTest {
    private fun deck(prefix: String, n: Int) = Deck(
        (1..n).map { Item("$prefix$it", "") },
        (1..n).map { SpotlightItem("$prefix$it", "", listOf("", "", "", "")) },
        (1..n).map { Item("$prefix$it", "") },
    )

    @Test
    fun aThirdOrSoComesFromTheOtherDeckSpreadEvenly() {
        val mixed = deck("f", 50).mixedWith(deck("a", 50), 0.3)
        val first10 = mixed.votes.take(10).map { it.id }
        assertEquals(3, first10.count { it.startsWith("a") })
        // Every stretch of ten has three, not a clump at the start.
        for (start in 0..60 step 10) assertEquals(3, mixed.votes.subList(start, start + 10).count { it.id.startsWith("a") })
        // Each deck keeps its own order.
        assertEquals((1..50).map { "a$it" }, mixed.votes.map { it.id }.filter { it.startsWith("a") })
        assertEquals((1..50).map { "f$it" }, mixed.spotlights.map { it.id }.filter { it.startsWith("f") })
    }

    @Test
    fun whenOneRunsOutTheOtherFollows() {
        val mixed = deck("f", 3).mixedWith(deck("a", 5), 0.3)
        assertEquals(8, mixed.drawings.size)
        assertEquals(listOf("a2", "a3", "a4", "a5"), mixed.drawings.takeLast(4).map { it.id })
    }
}
