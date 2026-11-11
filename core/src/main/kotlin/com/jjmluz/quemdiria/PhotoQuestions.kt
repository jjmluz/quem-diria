package com.jjmluz.quemdiria

import kotlin.random.Random

/** What the TV knows about one of a player's photos in Google Photos: its key there, its year and its place. */
data class PhotoFacts(val photo: String, val year: Int, val place: String?)

/**
 * Makes the photo questions of a game from each player's photos: when ("What year?") and where ("Where was
 * this?"), with the four choices and the right one taken from the photos' own dates and places.
 */
object PhotoQuestions {
    /**
     * Up to [count] questions for each player in [facts] (player id to their photos, in any order), each on a
     * different photo: a place question first when one can be made, then years. The wrong places come from
     * every player's photos, so they're places the family has been to; a place sharing a name with the right
     * one ("Porto" and "Ribeira, Porto") is never a wrong choice. The years are four in a row, never past
     * [thisYear]. [yearText] and [placeText] are the questions, with `{name}` for the player.
     */
    fun make(
        facts: Map<Int, List<PhotoFacts>>,
        thisYear: Int,
        yearText: String,
        placeText: String,
        random: Random,
        count: Int = Game.PHOTOS_PER_TURN,
    ): Map<Int, List<PhotoQuestion>> {
        val places = facts.values.flatten().mapNotNull { it.place }.distinct()
        return facts.mapValues { (_, photos) ->
            val left = photos.shuffled(random).toMutableList()
            val questions = mutableListOf<PhotoQuestion>()
            // One place question, from the first photo that has a place with three others unlike it.
            left.firstNotNullOfOrNull { p -> p.place?.let { place -> placeQuestion(p, place, places, placeText, random) } }
                ?.let { q ->
                    questions += q
                    left.removeAll { it.photo == q.photo }
                }
            left.filter { it.year <= thisYear }.take(count - questions.size).forEach {
                questions += yearQuestion(it, thisYear, yearText, random)
            }
            questions.take(count)
        }
    }

    private fun placeQuestion(p: PhotoFacts, place: String, all: List<String>, text: String, random: Random): PhotoQuestion? {
        val names = parts(place)
        val wrong = all.filter { other -> parts(other).none { it in names } }.shuffled(random).take(3)
        if (wrong.size < 3) return null
        val options = (wrong + place).shuffled(random)
        return PhotoQuestion(p.photo, PhotoQuestion.Kind.PLACE, text, options, options.indexOf(place))
    }

    private fun yearQuestion(p: PhotoFacts, thisYear: Int, text: String, random: Random): PhotoQuestion {
        // Four years in a row with the right one anywhere among them, but none still to come.
        val first = (p.year - random.nextInt(4)).coerceAtMost(thisYear - 3)
        val options = (first until first + 4).map { it.toString() }
        return PhotoQuestion(p.photo, PhotoQuestion.Kind.YEAR, text, options, p.year - first)
    }

    /** A place's names, lower-cased: "Ribeira, Porto" is "ribeira" and "porto". */
    private fun parts(place: String) = place.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
