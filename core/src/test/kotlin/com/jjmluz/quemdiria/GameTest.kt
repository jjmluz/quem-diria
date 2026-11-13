package com.jjmluz.quemdiria

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameTest {
    private val deck = Deck(
        votes = (1..20).map { Item("v$it", "Question $it?") },
        spotlights = (1..5).map { SpotlightItem("s$it", "{name} spotlight $it?", listOf("a", "b", "c", "d")) },
        drawings = (1..5).map { Item("d$it", "Draw {name} $it") },
    )
    private val jpeg = byteArrayOf(1)

    @Test
    fun aDeckFileIsReadInEitherLanguage() {
        val text = """
            # comment
            [vote]
            v1 | Who sings? | Quem canta?
            [spotlight]
            s1 | {name} at a party? | {name} numa festa? | dances / eats / leaves / sleeps | dança / come / sai / dorme
            [draw]
            d1 | {name} as a cat | {name} como gato
        """.trimIndent()
        val en = Deck.parse(text, Language.EN)
        assertEquals(Item("v1", "Who sings?"), en.votes.single())
        assertEquals(listOf("dances", "eats", "leaves", "sleeps"), en.spotlights.single().options)
        val pt = Deck.parse(text, Language.PT)
        assertEquals("Quem canta?", pt.votes.single().text)
        assertEquals("{name} numa festa?", pt.spotlights.single().text)
        assertEquals(listOf("dança", "come", "sai", "dorme"), pt.spotlights.single().options)
        assertEquals("Ana como gato", pt.drawings.single().text.withName("Ana"))
    }

    @Test
    fun aBrokenDeckLineSaysWhere() {
        val e = assertFailsWith<IllegalArgumentException> { Deck.parse("[vote]\nv1 | only English", Language.EN) }
        assertTrue(e.message!!.startsWith("Line 2"))
        assertFailsWith<IllegalArgumentException> { Deck.parse("[vote]\nv1 | a | b\nv1 | c | d", Language.EN) }
        assertFailsWith<IllegalArgumentException> {
            Deck.parse("[spotlight]\ns1 | q | q | a / b / c | a / b / c / d", Language.EN)
        }
    }

    @Test
    fun aGameIsQuestionsSpotlightsAndADoubleFinal() {
        val steps = Game.plan(listOf(1, 2, 3), deck, Random(1))
        assertEquals(3, steps.count { it is Step.Spotlight })
        assertEquals(setOf(1, 2, 3), steps.filterIsInstance<Step.Spotlight>().map { it.playerId }.toSet())
        assertEquals(10, steps.count { it is Step.Vote })
        assertEquals(3, steps.filterIsInstance<Step.Vote>().count { it.double })
        assertTrue(steps.takeLast(4).first() is Step.FinalIntro)
        // Questions come from the front of the deck, all different.
        val questions = steps.filterIsInstance<Step.Vote>().map { it.question.id }
        assertEquals((1..10).map { "v$it" }, questions)
    }

    @Test
    fun onlyFourPlayersGetASpotlight() {
        val steps = Game.plan((1..8).toList(), deck, Random(1))
        assertEquals(4, steps.count { it is Step.Spotlight })
        assertEquals(12, steps.count { it is Step.Vote })
    }

    @Test
    fun aShortDeckIsReused() {
        val small = deck.copy(votes = deck.votes.take(2))
        val questions = Game.plan(listOf(1, 2, 3), small, Random(1)).filterIsInstance<Step.Vote>().map { it.question.id }
        assertEquals(listOf("v1", "v2", "v1", "v2"), questions.take(4))
    }

    private val all = setOf(1, 2, 3, 4)

    @Test
    fun pausingStopsTheClockAndTheAnswers() {
        val g = voteGame()
        val left = g.remaining(1_000)
        val paused = assertNotNull(g.pause(1_000))
        assertNull(paused.pause(2_000), "already paused")
        // Long after the deadline: nothing moves, nobody can vote, and the time left stays put.
        val late = g.deadline + 60_000
        assertEquals(left, paused.remaining(late))
        assertNull(paused.tick(late, all, all))
        assertNull(paused.skip(late, all))
        assertNull(paused.vote(1, 2, all))
        // Resumed a minute later: the same time left as when it paused, counting down again.
        val resumed = assertNotNull(paused.resume(61_000))
        assertNull(resumed.resume(62_000), "not paused")
        assertEquals(left, resumed.remaining(61_000))
        assertEquals(left - 500, resumed.remaining(61_500))
        assertNotNull(resumed.vote(1, 2, all))
    }

    private fun voteGame() = Game.start(listOf(Step.Vote(Item("v", "Who?")), Step.Scores), now = 0, present = all)

    @Test
    fun votesScoreOnePointPerPlayerWhoAgreed() {
        var g = voteGame()
        assertEquals(Stage.VOTE, g.stage)
        g = g.vote(1, 3, all)!!.vote(2, 3, all)!!.vote(3, 3, all)!!.vote(4, 1, all)!!
        // Everyone connected has voted, so it doesn't wait for the time to run out.
        g = g.tick(now = 1000, active = all, present = all)!!
        assertEquals(Stage.VOTE_REVEAL, g.stage)
        assertEquals(mapOf(1 to 2, 2 to 2, 3 to 2, 4 to 0), g.gained)
        assertEquals(mapOf(3 to 3, 1 to 1), g.tallies.single().counts)
    }

    @Test
    fun theFinalCountsDouble() {
        var g = Game.start(listOf(Step.Vote(Item("v", "Who?"), double = true)), now = 0, present = all)
        g = g.vote(1, 2, all)!!.vote(2, 2, all)!!
        g = g.skip(0, all)!!
        assertEquals(mapOf(1 to 2, 2 to 2), g.gained)
    }

    @Test
    fun aVoteWaitsForConnectedPlayersOnlyAndThenForTheClock() {
        var g = voteGame().vote(1, 2, all)!!
        // Player 2 is still connected and hasn't voted: keep waiting.
        assertNull(g.tick(1000, active = setOf(1, 2), present = all))
        g = g.vote(2, 1, all)!!
        // 3 and 4 have gone quiet (phones asleep): not waited for.
        assertNotNull(g.tick(1000, active = setOf(1, 2), present = all))
        // Nobody else voted: time runs out.
        val alone = voteGame().vote(1, 2, all)!!
        assertNull(alone.tick(Game.VOTE_MS - 1, all, all))
        assertEquals(Stage.VOTE_REVEAL, alone.tick(Game.VOTE_MS, all, all)!!.stage)
    }

    @Test
    fun votesForSomeoneWhoLeftOrOutOfTurnAreRefused() {
        val g = voteGame()
        assertNull(g.vote(1, 9, all))
        assertNull(g.vote(9, 1, all))
        assertNull(g.answer(1, 0, all))
        assertNull(g.draw(1, jpeg, all))
    }

    private fun spotlightGame(present: Set<Int> = all) = Game.start(
        listOf(Step.Spotlight(1, SpotlightItem("s", "{name}?", listOf("a", "b", "c", "d")), Item("d", "Draw {name}")), Step.Scores),
        now = 0, present = present,
    )

    @Test
    fun guessingRightScoresTwoAndTheSpotlightGetsOnePerRightGuess() {
        var g = spotlightGame()
        assertEquals(Stage.ANSWER, g.stage)
        assertEquals(1, g.spotlightId)
        g = g.answer(1, 2, all)!!.answer(2, 2, all)!!.answer(3, 2, all)!!.answer(4, 0, all)!!
        g = g.tick(1, all, all)!!
        assertEquals(Stage.ANSWER_REVEAL, g.stage)
        assertEquals(mapOf(1 to 2, 2 to 2, 3 to 2), g.gained.filterValues { it > 0 })
    }

    @Test
    fun withoutTheSpotlightsAnswerNobodyScores() {
        var g = spotlightGame().answer(2, 1, all)!!
        g = g.skip(1, all)!!
        assertEquals(0, g.gained.values.sum())
    }

    @Test
    fun drawingThenPickingTheBest() {
        var g = spotlightGame().skip(0, all)!!.skip(1, all)!!
        assertEquals(Stage.DRAW, g.stage)
        // The player in the spotlight doesn't draw.
        assertNull(g.draw(1, jpeg, all))
        g = g.draw(2, jpeg, all)!!.draw(3, jpeg, all)!!
        assertNull(g.tick(2, all, all))
        g = g.draw(4, jpeg, all)!!.tick(2, all, all)!!
        assertEquals(Stage.PICK, g.stage)
        assertEquals(setOf(2, 3, 4), g.galleryOrder.toSet())
        // Nobody picks their own.
        assertNull(g.pick(2, 2, all))
        g = g.pick(1, 3, all)!!.pick(2, 3, all)!!.pick(3, 4, all)!!.pick(4, 3, all)!!
        g = g.tick(3, all, all)!!
        assertEquals(Stage.PICK_REVEAL, g.stage)
        assertEquals(mapOf(2 to 0, 3 to 6, 4 to 2), g.gained)
        assertEquals(3, g.gallery.size)
        assertEquals("Draw {name}", g.gallery.first().prompt)
        assertEquals(Stage.SCORES, g.skip(4, all)!!.stage)
    }

    @Test
    fun withTwoDrawingsOnlyThoseWhoDidntDrawPick() {
        val three = setOf(1, 2, 3)
        var g = spotlightGame(present = three).skip(0, three)!!.skip(1, three)!!
        g = g.draw(2, jpeg, three)!!.draw(3, jpeg, three)!!.tick(2, three, three)!!
        assertEquals(Stage.PICK, g.stage)
        // The two who drew could only pick each other's: they don't pick at all.
        assertFalse(g.picksDrawing(2))
        assertNull(g.pick(2, 3, three))
        assertTrue(g.picksDrawing(1))
        // Once the player in the spotlight picks, everyone has.
        g = g.pick(1, 3, three)!!.tick(3, three, three)!!
        assertEquals(Stage.PICK_REVEAL, g.stage)
        assertEquals(mapOf(2 to 0, 3 to 2), g.gained)
    }

    @Test
    fun oneDrawingIsShownWithoutAVote() {
        var g = spotlightGame().skip(0, all)!!.skip(1, all)!!
        g = g.draw(2, jpeg, all)!!.skip(2, all)!!
        assertEquals(Stage.PICK_REVEAL, g.stage)
        assertEquals(1, g.gallery.size)
    }

    @Test
    fun aSpotlightForSomeoneWhoLeftIsSkipped() {
        val g = spotlightGame(present = setOf(2, 3, 4))
        assertEquals(Stage.SCORES, g.stage)
    }

    @Test
    fun theGameEndsWithAwardsAndWinners() {
        var g = Game.start(listOf(Step.Vote(Item("a", "Who sings?")), Step.Vote(Item("b", "Who dances?"))), 0, all)
        g = g.vote(1, 2, all)!!.vote(2, 2, all)!!.vote(3, 4, all)!!.skip(0, all)!!.skip(0, all)!!
        g = g.vote(1, 3, all)!!.vote(2, 3, all)!!.vote(3, 3, all)!!.skip(0, all)!!.skip(0, all)!!
        assertEquals(Stage.END, g.stage)
        assertNull(g.skip(0, all))
        val awards = g.awards(listOf(1, 2, 3, 4)).associateBy { it.playerId }
        assertEquals(Award(2, "Who sings?", 2), awards[2])
        assertEquals(Award(3, "Who dances?", 3), awards[3])
        assertEquals(Award(1, null, 0), awards[1])
        assertEquals(listOf(1, 2), g.winners(listOf(1, 2, 3, 4)))
    }

    @Test
    fun aWholeGamePlaysThroughOnTimeAlone() {
        var g = Game.start(Game.plan(listOf(1, 2, 3), deck, Random(2)), 0, setOf(1, 2, 3))
        var now = 0L
        var stages = 0
        while (g.stage != Stage.END && stages < 200) {
            now = g.deadline
            g = g.tick(now, emptySet(), setOf(1, 2, 3)) ?: break
            stages++
        }
        assertEquals(Stage.END, g.stage)
        // About 15 minutes even when nobody answers early.
        assertTrue(now in 10 * 60_000L..20 * 60_000L, "game took ${now / 1000} s")
    }
}
