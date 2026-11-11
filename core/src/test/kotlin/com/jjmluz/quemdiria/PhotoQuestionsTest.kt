package com.jjmluz.quemdiria

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoQuestionsTest {
    private val deck = Deck(
        votes = (1..20).map { Item("v$it", "Question $it?") },
        spotlights = (1..5).map { SpotlightItem("s$it", "{name} spotlight $it?", listOf("a", "b", "c", "d")) },
        drawings = (1..5).map { Item("d$it", "Draw {name} $it") },
    )
    private val players = listOf(1, 2, 3)

    private fun question(photo: String, truth: Int = 0) =
        PhotoQuestion(photo, PhotoQuestion.Kind.YEAR, "When?", listOf("2020", "2021", "2022", "2023"), truth)

    private val photos = players.associateWith { p -> listOf(question("a$p", 1), question("b$p", 2), question("c$p")) }

    @Test
    fun photoQuestionsFollowEachSpotlightWhenEveryoneIsLinked() {
        val steps = Game.plan(players, deck, Random(1), photos)
        steps.forEachIndexed { i, step ->
            if (step is Step.Spotlight) {
                val next = steps.drop(i + 1).take(3)
                assertEquals(listOf(step.playerId, step.playerId), next.take(2).map { (it as Step.Photo).playerId })
                assertEquals(Step.Scores, next[2])
            }
        }
        assertEquals(6, steps.count { it is Step.Photo })
    }

    @Test
    fun noPhotoQuestionsUnlessEveryoneIsLinked() {
        val steps = Game.plan(players, deck, Random(1), photos - 3)
        assertEquals(0, steps.count { it is Step.Photo })
        // Linked, but with no photos to ask about: the others still get theirs.
        val some = Game.plan(players, deck, Random(1), photos + (3 to emptyList()))
        assertEquals(4, some.count { it is Step.Photo })
        assertTrue(some.filterIsInstance<Step.Photo>().none { it.playerId == 3 })
    }

    @Test
    fun everyoneGuessesAndTheRightAnswerIsThePhotos() {
        val steps = listOf(Step.Photo(1, question("x", truth = 2)), Step.Scores)
        var g = Game.start(steps, 0, players.toSet())
        assertEquals(Stage.PHOTO, g.stage)
        assertEquals(1, g.spotlightId)
        g = g.answer(1, 2, players.toSet())!!.answer(2, 2, players.toSet())!!.answer(3, 0, players.toSet())!!
        g = g.tick(1, players.toSet(), players.toSet())!!
        assertEquals(Stage.PHOTO_REVEAL, g.stage)
        assertEquals(mapOf(1 to 2, 2 to 2), g.gained)
        g = g.skip(2, players.toSet())!!
        assertEquals(Stage.SCORES, g.stage)
    }

    @Test
    fun aPhotoOfSomeoneWhoLeftIsSkipped() {
        val steps = listOf(Step.Photo(3, question("x")), Step.Scores)
        assertEquals(Stage.SCORES, Game.start(steps, 0, setOf(1, 2)).stage)
    }

    private fun make(facts: Map<Int, List<PhotoFacts>>, seed: Int = 1) =
        PhotoQuestions.make(facts, thisYear = 2026, yearText = "When, {name}?", placeText = "Where, {name}?", random = Random(seed))

    @Test
    fun aPlaceQuestionThenAYear() {
        val facts = mapOf(
            1 to listOf(PhotoFacts("p1", 2019, "Ribeira, Porto"), PhotoFacts("p2", 2024, null)),
            2 to listOf(PhotoFacts("p3", 2015, "Paris, France"), PhotoFacts("p4", 2016, "Porto")),
            3 to listOf(PhotoFacts("p5", 2010, "Lisboa"), PhotoFacts("p6", 2011, "Faro")),
        )
        repeat(20) { seed ->
            val q = make(facts, seed)
            val first = q.getValue(1)
            assertEquals(listOf(PhotoQuestion.Kind.PLACE, PhotoQuestion.Kind.YEAR), first.map { it.kind })
            val place = first[0]
            assertEquals("p1", place.photo)
            assertEquals("Ribeira, Porto", place.options[place.truth])
            // "Porto" would give it away, or be right too.
            assertTrue("Porto" !in place.options)
            assertEquals(4, place.options.distinct().size)
            val year = first[1]
            assertEquals("p2", year.photo)
            assertEquals("2024", year.options[year.truth])
            assertTrue(year.options.all { it.toInt() <= 2026 })
            assertEquals(year.options, (year.options[0].toInt()..year.options[0].toInt() + 3).map { it.toString() })
        }
    }

    @Test
    fun withoutEnoughPlacesItAsksYears() {
        val facts = mapOf(1 to listOf(PhotoFacts("p1", 2020, "Porto"), PhotoFacts("p2", 2021, "Lisboa"), PhotoFacts("p3", 2026, null)))
        val q = make(facts).getValue(1)
        assertEquals(listOf(PhotoQuestion.Kind.YEAR, PhotoQuestion.Kind.YEAR), q.map { it.kind })
        assertEquals(2, q.map { it.photo }.distinct().size)
        // This year's photo can't have years after it among the choices.
        q.firstOrNull { it.photo == "p3" }?.let { assertEquals("2026", it.options.last()) }
        assertNotNull(make(mapOf(1 to emptyList()))[1]).let { assertTrue(it.isEmpty()) }
    }
}
