package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentaryTest {
    private val players = listOf(1, 2, 3, 4)
    private fun game(scores: Map<Int, Int> = emptyMap(), gained: Map<Int, Int> = emptyMap()) =
        Game(steps = listOf(Step.Scores), index = 0, stage = Stage.SCORES, deadline = 0, scores = scores, gained = gained)

    @Test
    fun afterARevealTheTopScorersAreNamed() {
        assertEquals(Remark.Scored(listOf(2), 4), Remark.afterReveal(game(gained = mapOf(1 to 2, 2 to 4)), players))
        assertEquals(Remark.Scored(listOf(1, 3), 2), Remark.afterReveal(game(gained = mapOf(1 to 2, 3 to 2, 4 to 0)), players))
        assertEquals(Remark.NobodyScored, Remark.afterReveal(game(gained = mapOf(1 to 0)), players))
    }

    @Test
    fun aPlayerWhoLeftIsntNamed() {
        assertEquals(Remark.Scored(listOf(1), 2), Remark.afterReveal(game(gained = mapOf(1 to 2, 9 to 6)), players))
    }

    @Test
    fun theScoreboardNamesTheLeaderAndWhoIsNext() {
        val r = Remark.standings(game(scores = mapOf(1 to 3, 2 to 10, 3 to 6, 4 to 6)), players) as Remark.Leads
        assertEquals(2, r.leader)
        assertEquals(10, r.points)
        assertEquals(listOf(3, 4), r.next)
        assertEquals(4, r.gap)
        assertFalse(r.close)
        assertTrue((Remark.standings(game(scores = mapOf(1 to 7, 2 to 5)), players) as Remark.Leads).close)
    }

    @Test
    fun aDrawAtTheTopAndNoPointsYet() {
        assertEquals(Remark.Tied(listOf(1, 3), 5), Remark.standings(game(scores = mapOf(1 to 5, 2 to 2, 3 to 5)), players))
        assertEquals(Remark.NoScores, Remark.standings(game(), players))
        assertNull(Remark.standings(game(scores = mapOf(1 to 5)), listOf(1)))
    }
}
