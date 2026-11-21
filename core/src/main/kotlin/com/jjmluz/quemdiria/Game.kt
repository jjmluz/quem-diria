package com.jjmluz.quemdiria

import kotlin.random.Random

/** One part of a game, in the order played. */
sealed interface Step {
    /** "Who's most likely to...?": everyone votes for a player. The final round counts [double]. */
    data class Vote(val question: Item, val double: Boolean = false) : Step

    /** One player in the spotlight: they answer a question about themselves while the others guess, then the
     * others draw on their selfie and everyone picks the best drawing. */
    data class Spotlight(val playerId: Int, val question: SpotlightItem, val prompt: Item) : Step

    /**
     * A question about one of [playerId]'s photos from Google Photos, played after their spotlight turn when
     * every player is linked to a person there. Everyone guesses, and the photo's own date or place is right.
     */
    data class Photo(val playerId: Int, val question: PhotoQuestion) : Step

    data object Scores : Step
    data object FinalIntro : Step
}

/** Who gets a spotlight turn (and so photo questions): nobody, up to [Game.SPOTLIGHTS] players, or everyone. */
enum class Spotlights { NONE, MODERATE, ALL }

/** What's happening right now. Stages that take answers end early once everyone connected has answered. */
enum class Stage {
    VOTE, VOTE_REVEAL, ANSWER, ANSWER_REVEAL, DRAW, PICK, PICK_REVEAL, PHOTO, PHOTO_REVEAL, SCORES, FINAL_INTRO, END
}

/** How the room voted on one question: player id to the votes they got. Kept for the end-of-game awards. */
data class Tally(val question: String, val counts: Map<Int, Int>)

/** A finished drawing, kept for the end-of-game collage. */
class Drawing(val drawerId: Int, val spotlightId: Int, val prompt: String, val jpeg: ByteArray, val picks: Int)

/** A player's end-of-game award: the question the room most picked them for, or null if it never did. */
data class Award(val playerId: Int, val question: String?, val votes: Int)

/**
 * A game in progress, as an immutable snapshot: every action returns a new [Game], or null if the action
 * isn't allowed now. Times are milliseconds from the caller's clock, so tests can drive it.
 *
 * "Active" players are the ones whose phones are connected: stages wait for them, and not for a phone
 * that has gone away. "Present" players are everyone still in the room.
 */
data class Game(
    val steps: List<Step>,
    val index: Int,
    val stage: Stage,
    /** When the stage ends by itself; 0 for the end screen, which waits for OK. */
    val deadline: Long,
    /**
     * Goes up with every stage, so phones can tell a new stage from an update to the same one. It names
     * the drawings' addresses too, which phones keep, so it never starts again: see [start].
     */
    val serial: Int = 0,
    val scores: Map<Int, Int> = emptyMap(),
    /** Points each player got at the last reveal. */
    val gained: Map<Int, Int> = emptyMap(),
    /** Voter to the player they voted for (VOTE). */
    val votes: Map<Int, Int> = emptyMap(),
    /** Player to the answer they picked (ANSWER); the spotlight player answers for themselves. */
    val answers: Map<Int, Int> = emptyMap(),
    /** Drawer to their drawing (DRAW, PICK). */
    val drawings: Map<Int, ByteArray> = emptyMap(),
    /** The drawers in the order their drawings are shown, so nobody can tell whose is whose. */
    val galleryOrder: List<Int> = emptyList(),
    /** Voter to the drawer whose drawing they picked (PICK). */
    val picks: Map<Int, Int> = emptyMap(),
    val tallies: List<Tally> = emptyList(),
    val gallery: List<Drawing> = emptyList(),
    /** When Back paused the game, or null while it's playing. The clock stands still meanwhile. */
    val pausedAt: Long? = null,
    private val seed: Long = 0,
) {
    val step: Step? get() = steps.getOrNull(index)
    /** The player the step is about: in the spotlight, or in the photo. */
    val spotlightId: Int?
        get() = when (val s = step) {
            is Step.Spotlight -> s.playerId
            is Step.Photo -> s.playerId
            else -> null
        }
    val double: Boolean get() = (step as? Step.Vote)?.double == true

    /** The number of this vote among all the votes, and how many there are, for "Question 3 of 10". */
    val voteNumber: Pair<Int, Int>
        get() {
            val all = steps.filterIsInstance<Step.Vote>()
            return steps.take(index + 1).count { it is Step.Vote } to all.size
        }

    val paused: Boolean get() = pausedAt != null

    /** The stage's time left at [now], frozen while paused; 0 for stages without a deadline. */
    fun remaining(now: Long): Long = if (deadline == 0L) 0 else (deadline - (pausedAt ?: now)).coerceAtLeast(0)

    /** Stops the clock; null if already paused. Phones can't answer until [resume]. */
    fun pause(now: Long): Game? = if (paused) null else copy(pausedAt = now)

    /** Starts the clock again, with the stage's end pushed back by the time it was paused. */
    fun resume(now: Long): Game? {
        val at = pausedAt ?: return null
        return copy(pausedAt = null, deadline = if (deadline == 0L) 0 else deadline + (now - at).coerceAtLeast(0))
    }

    fun vote(voter: Int, target: Int, present: Set<Int>): Game? {
        if (paused || stage != Stage.VOTE || target !in present || voter !in present) return null
        return copy(votes = votes + (voter to target))
    }

    fun answer(player: Int, option: Int, present: Set<Int>): Game? {
        if (paused || (stage != Stage.ANSWER && stage != Stage.PHOTO) || option !in 0..3 || player !in present) return null
        return copy(answers = answers + (player to option))
    }

    fun draw(player: Int, jpeg: ByteArray, present: Set<Int>): Game? {
        if (paused || stage != Stage.DRAW || player == spotlightId || player !in present) return null
        return copy(drawings = drawings + (player to jpeg))
    }

    fun pick(player: Int, drawer: Int, present: Set<Int>): Game? {
        if (paused || stage != Stage.PICK || drawer == player || drawer !in drawings || player !in present) return null
        if (!picksDrawing(player)) return null
        return copy(picks = picks + (player to drawer))
    }

    /**
     * Whether [player] picks the best drawing: only with at least two drawings that aren't theirs. With three players
     * the two who drew could only pick each other's, so only the player in the spotlight picks.
     */
    fun picksDrawing(player: Int): Boolean = drawings.keys.count { it != player } >= 2

    /** Moves on if the stage's time is up or everyone [active] has answered; null if nothing changes. */
    fun tick(now: Long, active: Set<Int>, present: Set<Int>): Game? {
        if (paused) return null
        val due = deadline != 0L && now >= deadline
        return if (due || everyoneDone(active, present)) finish(now, present) else null
    }

    /** Ends the stage now (OK on the remote): stops waiting for answers, or skips a reveal. */
    fun skip(now: Long, present: Set<Int>): Game? = if (paused || stage == Stage.END) null else finish(now, present)

    private fun everyoneDone(active: Set<Int>, present: Set<Int>): Boolean {
        val waiting = active.intersect(present)
        return when (stage) {
            Stage.VOTE -> waiting.isNotEmpty() && waiting.all { it in votes }
            Stage.ANSWER, Stage.PHOTO -> waiting.isNotEmpty() && waiting.all { it in answers }
            Stage.DRAW -> {
                val drawers = waiting - setOfNotNull(spotlightId)
                drawers.isNotEmpty() && drawers.all { it in drawings }
            }
            Stage.PICK -> waiting.isNotEmpty() && waiting.all { it in picks || !picksDrawing(it) }
            else -> false
        }
    }

    private fun finish(now: Long, present: Set<Int>): Game = when (stage) {
        Stage.VOTE -> revealVotes(now)
        Stage.ANSWER -> revealAnswers(now)
        Stage.ANSWER_REVEAL -> {
            val drawers = present - setOfNotNull(spotlightId)
            if (spotlightId in present && drawers.isNotEmpty()) {
                at(Stage.DRAW, now + DRAW_MS).copy(drawings = emptyMap())
            } else {
                enter(index + 1, now, present)
            }
        }
        Stage.DRAW -> when {
            drawings.size >= 2 -> at(Stage.PICK, now + PICK_MS).copy(
                picks = emptyMap(),
                galleryOrder = drawings.keys.shuffled(Random(seed + index)),
            )
            drawings.size == 1 -> revealPicks(now, galleryOrder = drawings.keys.toList())
            else -> enter(index + 1, now, present)
        }
        Stage.PICK -> revealPicks(now, galleryOrder)
        Stage.PHOTO -> revealPhoto(now)
        Stage.VOTE_REVEAL, Stage.PICK_REVEAL, Stage.PHOTO_REVEAL, Stage.SCORES, Stage.FINAL_INTRO -> enter(index + 1, now, present)
        Stage.END -> this
    }

    private fun at(stage: Stage, deadline: Long) = copy(stage = stage, deadline = deadline, serial = serial + 1)

    private fun addPoints(points: Map<Int, Int>) =
        (scores.keys + points.keys).associateWith { (scores[it] ?: 0) + (points[it] ?: 0) }

    private fun revealVotes(now: Long): Game {
        val factor = if (double) 2 else 1
        // You score one point for every other player who voted the same way as you.
        val points = votes.mapValues { (voter, target) -> votes.count { (other, t) -> other != voter && t == target } * factor }
        val question = (step as Step.Vote).question.text
        val counts = votes.values.groupingBy { it }.eachCount()
        return at(Stage.VOTE_REVEAL, now + REVEAL_MS).copy(
            scores = addPoints(points),
            gained = points,
            tallies = tallies + Tally(question, counts),
        )
    }

    private fun revealAnswers(now: Long): Game {
        val spotlight = spotlightId!!
        val truth = answers[spotlight]
        // Guessing right is worth 2; the player in the spotlight gets 1 for each person who knows them that well.
        val right = if (truth == null) emptyList() else answers.filter { (p, a) -> p != spotlight && a == truth }.keys
        val points = right.associateWith { GUESS_POINTS } + (spotlight to right.size * KNOWN_POINTS)
        return at(Stage.ANSWER_REVEAL, now + REVEAL_MS).copy(scores = addPoints(points), gained = points)
    }

    private fun revealPhoto(now: Long): Game {
        val truth = (step as Step.Photo).question.truth
        // Everyone guesses, the player in the photo too: it isn't always easy to place your own photos.
        val points = answers.filterValues { it == truth }.mapValues { GUESS_POINTS }
        return at(Stage.PHOTO_REVEAL, now + REVEAL_MS).copy(scores = addPoints(points), gained = points)
    }

    private fun revealPicks(now: Long, galleryOrder: List<Int>): Game {
        val counts = picks.values.groupingBy { it }.eachCount()
        val points = drawings.keys.associateWith { (counts[it] ?: 0) * PICK_POINTS }
        val spot = step as Step.Spotlight
        val finished = galleryOrder.mapNotNull { drawer ->
            drawings[drawer]?.let { Drawing(drawer, spot.playerId, spot.prompt.text, it, counts[drawer] ?: 0) }
        }
        return at(Stage.PICK_REVEAL, now + PICK_REVEAL_MS).copy(
            scores = addPoints(points),
            gained = points,
            galleryOrder = galleryOrder,
            gallery = gallery + finished,
        )
    }

    /** Starts step [i]: a spotlight whose player has left is skipped, and after the last step comes the end. */
    private fun enter(i: Int, now: Long, present: Set<Int>): Game {
        val blank = copy(
            index = i, votes = emptyMap(), answers = emptyMap(), picks = emptyMap(), gained = emptyMap(),
            drawings = emptyMap(), galleryOrder = emptyList(),
        )
        return when (val next = steps.getOrNull(i)) {
            null -> blank.at(Stage.END, 0)
            is Step.Vote -> blank.at(Stage.VOTE, now + if (next.double) FINAL_VOTE_MS else VOTE_MS)
            is Step.Spotlight ->
                if (next.playerId in present) blank.at(Stage.ANSWER, now + ANSWER_MS) else blank.enter(i + 1, now, present)
            is Step.Photo ->
                if (next.playerId in present) blank.at(Stage.PHOTO, now + PHOTO_MS) else blank.enter(i + 1, now, present)
            Step.Scores -> blank.at(Stage.SCORES, now + SCORES_MS)
            Step.FinalIntro -> blank.at(Stage.FINAL_INTRO, now + FINAL_INTRO_MS)
        }
    }

    /** Each player's award: the question the room most often picked them for. */
    fun awards(players: List<Int>): List<Award> = players.map { id ->
        val best = tallies.maxByOrNull { it.counts[id] ?: 0 }
        val votes = best?.counts?.get(id) ?: 0
        Award(id, if (votes > 0) best?.question else null, votes)
    }

    /** The players with the highest score: more than one on a draw. */
    fun winners(players: List<Int>): List<Int> {
        val top = players.maxOfOrNull { scores[it] ?: 0 } ?: return emptyList()
        return players.filter { (scores[it] ?: 0) == top }
    }

    companion object {
        const val VOTE_MS = 30_000L
        const val FINAL_VOTE_MS = 20_000L
        const val REVEAL_MS = 9_000L
        const val ANSWER_MS = 30_000L
        const val DRAW_MS = 75_000L
        const val PICK_MS = 30_000L
        const val PICK_REVEAL_MS = 10_000L
        const val PHOTO_MS = 25_000L
        const val SCORES_MS = 8_000L
        const val FINAL_INTRO_MS = 5_000L

        const val GUESS_POINTS = 2
        const val KNOWN_POINTS = 1
        const val PICK_POINTS = 2

        /** With [Spotlights.MODERATE], up to this many players get a spotlight turn, which keeps a game to about 15 minutes. */
        const val SPOTLIGHTS = 4

        /** With [Spotlights.NONE], the game is this many groups of [NO_SPOTLIGHT_GROUP] questions, each followed by the scores. */
        const val NO_SPOTLIGHT_GROUPS = 3
        const val NO_SPOTLIGHT_GROUP = 3

        /** Photo questions after each spotlight turn, when everyone is linked to Google Photos. */
        const val PHOTOS_PER_TURN = 2

        /**
         * The order of a game: three questions, then each spotlight turn followed by the scores and two more
         * questions, and a final of three questions worth double. Questions are taken from the front of each
         * list, so put the ones not played recently first; a short list is reused from its start.
         *
         * [photos] are questions about each player's photos. They're played only when every player has a list
         * (every one is linked to Google Photos), up to [PHOTOS_PER_TURN] after each spotlight turn; a player
         * whose list is empty (no usable photos) just has none.
         *
         * [spotlights] says who gets a spotlight turn: nobody (then the questions come in groups, with the scores
         * after each, and there are no photo questions, since those follow a spotlight turn), up to
         * [SPOTLIGHTS] players at random, or everyone.
         */
        fun plan(
            players: List<Int>, deck: Deck, random: Random, photos: Map<Int, List<PhotoQuestion>> = emptyMap(),
            spotlights: Spotlights = Spotlights.MODERATE,
        ): List<Step> {
            require(deck.votes.isNotEmpty() && deck.spotlights.isNotEmpty() && deck.drawings.isNotEmpty())
            val spotlit = when (spotlights) {
                Spotlights.NONE -> emptyList()
                Spotlights.MODERATE -> players.shuffled(random).take(SPOTLIGHTS)
                Spotlights.ALL -> players.shuffled(random)
            }
            var v = 0
            fun vote(double: Boolean = false) = Step.Vote(deck.votes[v++ % deck.votes.size], double)
            val steps = mutableListOf<Step>()
            if (spotlit.isEmpty()) {
                repeat(NO_SPOTLIGHT_GROUPS) {
                    repeat(NO_SPOTLIGHT_GROUP) { steps += vote() }
                    steps += Step.Scores
                }
            } else {
                steps += listOf(vote(), vote(), vote())
            }
            spotlit.forEachIndexed { i, player ->
                steps += Step.Spotlight(player, deck.spotlights[i % deck.spotlights.size], deck.drawings[i % deck.drawings.size])
                if (players.all { it in photos }) {
                    photos[player].orEmpty().take(PHOTOS_PER_TURN).forEach { steps += Step.Photo(player, it) }
                }
                steps += Step.Scores
                if (i < spotlit.lastIndex) {
                    steps += vote()
                    steps += vote()
                }
            }
            steps += Step.FinalIntro
            repeat(3) { steps += vote(double = true) }
            return steps
        }

        /** A new game, whose stages are numbered after [serial] (the last game's last one). */
        fun start(steps: List<Step>, now: Long, present: Set<Int>, seed: Long = now, serial: Int = 0): Game =
            Game(steps, -1, Stage.VOTE, 0, serial = serial, seed = seed).enter(0, now, present)
    }
}
