package com.jjmluz.quemdiria

import kotlin.random.Random

/** One of a person's photos to choose from: its key in Google Photos and the day it was taken (days since 1970). */
data class PhotoCandidate(val photo: String, val day: Long)

/**
 * Which of a person's photos to ask about next. A library comes in bursts (a birthday can be thirty photos),
 * so photos are picked one per day, and photos and days the TV has shown before wait until the others are used.
 */
object PhotoPicks {
    /** The photos chosen, and whether everything had been shown, so the memory of shown photos starts over. */
    data class Choice(val photos: List<PhotoCandidate>, val startOver: Boolean)

    /**
     * Up to [count] of [candidates], each from a different day: days not shown yet first, then the others,
     * at random within each; a photo already shown ([shownPhotos]) is never picked. Once fewer than [atLeast]
     * photos are left unshown, everything is forgotten and the pick is made from all of them ([Choice.startOver]).
     */
    fun choose(
        candidates: List<PhotoCandidate>,
        shownPhotos: Set<String>,
        shownDays: Set<Long>,
        count: Int,
        atLeast: Int,
        random: Random,
    ): Choice {
        val fresh = candidates.filter { it.photo !in shownPhotos }
        if (fresh.size < atLeast && fresh.size < candidates.size) {
            return Choice(onePerDay(candidates, emptySet(), count, random), startOver = true)
        }
        return Choice(onePerDay(fresh, shownDays, count, random), startOver = false)
    }

    private fun onePerDay(photos: List<PhotoCandidate>, shownDays: Set<Long>, count: Int, random: Random): List<PhotoCandidate> {
        val (old, new) = photos.groupBy { it.day }.entries.partition { it.key in shownDays }
        return (new.shuffled(random) + old.shuffled(random)).take(count).map { it.value.random(random) }
    }
}
