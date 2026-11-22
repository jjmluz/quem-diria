package com.jjmluz.quemdiria

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoPicksTest {
    /** Ten days, each with a burst of five photos ("d3p2" is day 3's third). */
    private val library = (0L until 10).flatMap { day -> (0 until 5).map { PhotoCandidate("d${day}p$it", day) } }

    @Test
    fun onePhotoPerDay() {
        repeat(20) { seed ->
            val picks = PhotoPicks.choose(library, emptySet(), emptySet(), 6, 2, Random(seed)).photos
            assertEquals(6, picks.size)
            assertEquals(6, picks.map { it.day }.toSet().size)
        }
    }

    @Test
    fun shownPhotosAreNeverPickedAndShownDaysWait() {
        val shown = library.filter { it.day < 3 }.take(4).map { it.photo }.toSet()
        repeat(20) { seed ->
            val choice = PhotoPicks.choose(library, shown, setOf(0L, 1L, 2L), 7, 2, Random(seed))
            assertFalse(choice.startOver)
            assertTrue(choice.photos.none { it.photo in shown })
            // Seven days were never shown: they come first.
            assertTrue(choice.photos.none { it.day < 3 })
        }
        // With more wanted than new days, shown days make up the rest.
        val more = PhotoPicks.choose(library, shown, setOf(0L, 1L, 2L), 10, 2, Random(1)).photos
        assertEquals(10, more.map { it.day }.toSet().size)
        assertTrue(more.none { it.photo in shown })
    }

    @Test
    fun allShownStartsOver() {
        val shown = library.drop(1).map { it.photo }.toSet()
        val choice = PhotoPicks.choose(library, shown, library.map { it.day }.toSet(), 4, 2, Random(1))
        assertTrue(choice.startOver)
        assertEquals(4, choice.photos.size)
    }

    @Test
    fun aSmallLibraryDoesNotStartOverForever() {
        // One photo in all: it's always the one, without starting over each time.
        val one = listOf(PhotoCandidate("only", 5))
        assertEquals(listOf("only"), PhotoPicks.choose(one, emptySet(), emptySet(), 4, 2, Random(1)).photos.map { it.photo })
        val again = PhotoPicks.choose(one, setOf("only"), setOf(5L), 4, 2, Random(1))
        assertTrue(again.startOver)
        assertEquals(listOf("only"), again.photos.map { it.photo })
    }
}
