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

/** Who gets a spotlight turn (and so photo questions): up to [Game.SPOTLIGHTS] players, or everyone. */
enum class Spotlights { MODERATE, ALL }

/**
 * What's happening right now. Stages that take answers end early once everyone connected has answered.
 * TIE_INTRO, TIE and TIE_REVEAL are the tie-breaker, after the last step, when the top score is shared.
 */
enum class Stage {
    VOTE, VOTE_REVEAL, ANSWER, ANSWER_REVEAL, DRAW, PICK, PICK_REVEAL, PHOTO, PHOTO_REVEAL, SCORES, FINAL_INTRO,
    TIE_INTRO, TIE, TIE_REVEAL, END,
}

/** Something that happened in the game, to ask the tied players about in the tie-breaker. */
sealed interface Memory {
    /** "Who drew this?": the answer is its drawer, a player. */
    data class Drew(val drawing: Drawing) : Memory

    /** "Who did the room pick for this?": the player most voted for [question], by a clear lead. */
    data class Picked(val question: String, val player: Int) : Memory

    /** "What did [player] answer?": their own answer, one of [question]'s options, in their spotlight turn. */
    data class Answered(val player: Int, val question: SpotlightItem, val answer: Int) : Memory
}

/**
 * The tie-breaker: [contenders] (the players tied for first who are still in) answer [question], with [later]
 * questions to come if it doesn't settle it. [right] is known at the reveal: who got it right.
 */
data class TieBreak(
    val contenders: List<Int>,
    val question: Memory,
    val later: List<Memory>,
    val round: Int = 1,
    val right: List<Int>? = null,
) {
    /** Who's still in after the reveal: those who got it right, or all of them if nobody did. */
    val still: List<Int> get() = right?.ifEmpty { contenders } ?: contenders

    /** The answer: a player for "who drew" and "who did the room pick", an option for "what did they answer". */
    val truth: Int
        get() = when (val q = question) {
            is Memory.Drew -> q.drawing.drawerId
            is Memory.Picked -> q.player
            is Memory.Answered -> q.answer
        }

    /**
     * The players to pick from, for a question whose answer is a player: everyone in the room, except for a
     * drawing, where it can't be a contender (their own drawings aren't asked about) nor the player drawn on.
     */
    fun choices(present: Collection<Int>): List<Int> = when (val q = question) {
        is Memory.Drew -> present.filter { it !in contenders && it != q.drawing.spotlightId }
        is Memory.Picked -> present.toList()
        is Memory.Answered -> emptyList()
    }
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
    /** What happened so far, for the tie-breaker's questions. */
    val memories: List<Memory> = emptyList(),
    /** The tie-breaker, once it has started; kept at the end, as it decides the winner. */
    val tie: TieBreak? = null,
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

    /**
     * A tied player's answer in the tie-breaker: a player's id, or an option's index for "what did they answer".
     * Only the contenders answer; the others watch.
     */
    fun guess(player: Int, value: Int, present: Set<Int>): Game? {
        val t = tie ?: return null
        if (paused || stage != Stage.TIE || player !in t.contenders || player !in present) return null
        val ok = if (t.question is Memory.Answered) value in 0..3 else value in t.choices(present)
        return if (ok) copy(answers = answers + (player to value)) else null
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
            Stage.TIE -> {
                val tied = waiting.intersect(tie?.contenders.orEmpty().toSet())
                tied.isNotEmpty() && tied.all { it in answers }
            }
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
        Stage.TIE_INTRO -> at(Stage.TIE, now + TIE_MS).copy(answers = emptyMap())
        Stage.TIE -> {
            val t = tie!!
            val right = t.contenders.filter { answers[it] == t.truth }
            at(Stage.TIE_REVEAL, now + REVEAL_MS).copy(tie = t.copy(right = right))
        }
        Stage.TIE_REVEAL -> {
            val t = tie!!
            // Settled, out of questions, or long enough: those still in share the win.
            val still = t.still.filter { it in present }
            if (still.size < 2 || t.later.isEmpty() || t.round >= TIE_ROUNDS) {
                at(Stage.END, 0).copy(tie = t.copy(contenders = still.ifEmpty { t.still }, right = null))
            } else {
                at(Stage.TIE, now + TIE_MS).copy(
                    answers = emptyMap(),
                    tie = TieBreak(still, t.later.first(), t.later.drop(1), t.round + 1),
                )
            }
        }
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
        // Remembered for the tie-breaker when one player clearly got the most votes.
        val top = counts.values.maxOrNull() ?: 0
        val picked = counts.filterValues { it == top }.keys.singleOrNull()
        return at(Stage.VOTE_REVEAL, now + REVEAL_MS).copy(
            scores = addPoints(points),
            gained = points,
            tallies = tallies + Tally(question, counts),
            memories = memories + listOfNotNull(picked?.let { Memory.Picked(question, it) }),
        )
    }

    private fun revealAnswers(now: Long): Game {
        val spotlight = spotlightId!!
        val truth = answers[spotlight]
        // Guessing right is worth 2; the player in the spotlight gets 1 for each person who knows them that well.
        val right = if (truth == null) emptyList() else answers.filter { (p, a) -> p != spotlight && a == truth }.keys
        val points = right.associateWith { GUESS_POINTS } + (spotlight to right.size * KNOWN_POINTS)
        val step = step as Step.Spotlight
        return at(Stage.ANSWER_REVEAL, now + REVEAL_MS).copy(
            scores = addPoints(points),
            gained = points,
            memories = memories + listOfNotNull(truth?.let { Memory.Answered(spotlight, step.question, it) }),
        )
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
            memories = memories + finished.map { Memory.Drew(it) },
        )
    }

    /** Starts step [i]: a spotlight whose player has left is skipped, and after the last step comes the end. */
    private fun enter(i: Int, now: Long, present: Set<Int>): Game {
        val blank = copy(
            index = i, votes = emptyMap(), answers = emptyMap(), picks = emptyMap(), gained = emptyMap(),
            drawings = emptyMap(), galleryOrder = emptyList(),
        )
        return when (val next = steps.getOrNull(i)) {
            null -> blank.tieBreak(now, present) ?: blank.at(Stage.END, 0)
            is Step.Vote -> blank.at(Stage.VOTE, now + if (next.double) FINAL_VOTE_MS else VOTE_MS)
            is Step.Spotlight ->
                if (next.playerId in present) blank.at(Stage.ANSWER, now + ANSWER_MS) else blank.enter(i + 1, now, present)
            is Step.Photo ->
                if (next.playerId in present) blank.at(Stage.PHOTO, now + PHOTO_MS) else blank.enter(i + 1, now, present)
            Step.Scores -> blank.at(Stage.SCORES, now + SCORES_MS)
            Step.FinalIntro -> blank.at(Stage.FINAL_INTRO, now + FINAL_INTRO_MS)
        }
    }

    /**
     * The tie-breaker, when two or more players share the top score (but not everyone: then there's no one to
     * beat), with questions about the game for them; null if there's no tie or nothing fair to ask. A tied
     * player is never asked about their own drawing or their own answer. The kinds take turns, a drawing first.
     */
    private fun tieBreak(now: Long, present: Set<Int>): Game? {
        val players = present.toList()
        val tied = winners(players)
        if (tied.size < 2 || tied.size == players.size) return null
        val random = Random(seed + index)
        val drawings = memories.filterIsInstance<Memory.Drew>().filter { m ->
            val d = m.drawing
            d.drawerId in present && d.drawerId !in tied &&
                players.count { it !in tied && it != d.spotlightId } >= 2
        }
        val picked = memories.filterIsInstance<Memory.Picked>().filter { it.player in present }
        val answered = memories.filterIsInstance<Memory.Answered>().filter { it.player in present && it.player !in tied }
        val kinds = listOf(drawings, picked, answered).map { it.shuffled(random).toMutableList<Memory>() }
        val questions = mutableListOf<Memory>()
        while (kinds.any { it.isNotEmpty() } && questions.size < TIE_ROUNDS) {
            kinds.forEach { if (it.isNotEmpty()) questions += it.removeAt(0) }
        }
        if (questions.isEmpty()) return null
        val ask = questions.take(TIE_ROUNDS)
        return copy(tie = TieBreak(tied, ask.first(), ask.drop(1))).at(Stage.TIE_INTRO, now + TIE_INTRO_MS)
    }

    /** Each player's award: the question the room most often picked them for. */
    fun awards(players: List<Int>): List<Award> = players.map { id ->
        val best = tallies.maxByOrNull { it.counts[id] ?: 0 }
        val votes = best?.counts?.get(id) ?: 0
        Award(id, if (votes > 0) best?.question else null, votes)
    }

    /**
     * The players with the highest score: more than one on a draw. At the end of a tie-breaker, those it left
     * in: one, or more if it didn't settle it.
     */
    fun winners(players: List<Int>): List<Int> {
        if (stage == Stage.END && tie != null) tie.contenders.filter { it in players }.ifEmpty { null }?.let { return it }
        val top = players.maxOfOrNull { scores[it] ?: 0 } ?: return emptyList()
        return players.filter { (scores[it] ?: 0) == top }
    }

    /** [player]'s place among [players]: 1 + how many scored more, or as much but won the tie-breaker. */
    fun rank(player: Int, players: List<Int>): Int {
        val won = winners(players)
        fun score(p: Int) = scores[p] ?: 0
        return 1 + players.count { score(it) > score(player) || (score(it) == score(player) && it in won && player !in won) }
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
        const val TIE_INTRO_MS = 6_000L
        const val TIE_MS = 20_000L

        /** The tie-breaker asks at most this many questions; if it's still tied, the win is shared. */
        const val TIE_ROUNDS = 5

        const val GUESS_POINTS = 2
        const val KNOWN_POINTS = 1
        const val PICK_POINTS = 2

        /** With [Spotlights.MODERATE], up to this many players get a spotlight turn, which keeps a game to about 15 minutes. */
        const val SPOTLIGHTS = 4

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
         * [spotlights] says who gets a spotlight turn: up to [SPOTLIGHTS] players at random, or everyone. (There's
         * no game without spotlight turns: the tie-breaker asks about what happened in them.)
         */
        fun plan(
            players: List<Int>, deck: Deck, random: Random, photos: Map<Int, List<PhotoQuestion>> = emptyMap(),
            spotlights: Spotlights = Spotlights.MODERATE,
        ): List<Step> {
            require(deck.votes.isNotEmpty() && deck.spotlights.isNotEmpty() && deck.drawings.isNotEmpty())
            val spotlit = when (spotlights) {
                Spotlights.MODERATE -> players.shuffled(random).take(SPOTLIGHTS)
                Spotlights.ALL -> players.shuffled(random)
            }
            var v = 0
            fun vote(double: Boolean = false) = Step.Vote(deck.votes[v++ % deck.votes.size], double)
            val steps = mutableListOf<Step>(vote(), vote(), vote())
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
