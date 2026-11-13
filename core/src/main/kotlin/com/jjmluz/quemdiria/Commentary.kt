package com.jjmluz.quemdiria

/**
 * What the narrator remarks on: after each question who scored the most, on the scoreboard who's in first
 * and who's next, and at the end who won. The TV turns each into one of several wordings, so it doesn't sound the same every time.
 */
sealed interface Remark {
    /** [players] scored the most at the last reveal, [points] each. */
    data class Scored(val players: List<Int>, val points: Int) : Remark

    /** Nobody scored at the last reveal. */
    data object NobodyScored : Remark

    /** [leader] is in first with [points], ahead of [next] (sharing second) on [nextPoints]. */
    data class Leads(val leader: Int, val points: Int, val next: List<Int>, val nextPoints: Int) : Remark {
        val gap: Int get() = points - nextPoints

        /** Close enough for the narrator to call it a tight race. */
        val close: Boolean get() = gap <= CLOSE_GAP
    }

    /** [players] share first place, on [points]. */
    data class Tied(val players: List<Int>, val points: Int) : Remark

    /** Nobody has any points yet. */
    data object NoScores : Remark

    /** At the end: [players] won (more than one on a draw), with [points]. */
    data class Won(val players: List<Int>, val points: Int) : Remark

    companion object {
        const val CLOSE_GAP = 2

        /** After a reveal: who among [players] gained the most in [game]'s last reveal. */
        fun afterReveal(game: Game, players: List<Int>): Remark {
            val best = players.maxOfOrNull { game.gained[it] ?: 0 } ?: 0
            if (best <= 0) return NobodyScored
            return Scored(players.filter { (game.gained[it] ?: 0) == best }, best)
        }

        /** On the scoreboard: who among [players] is in first, and who's next. Null with fewer than two players. */
        fun standings(game: Game, players: List<Int>): Remark? {
            if (players.size < 2) return null
            fun score(p: Int) = game.scores[p] ?: 0
            val top = players.maxOf(::score)
            if (top == 0) return NoScores
            val first = players.filter { score(it) == top }
            if (first.size > 1) return Tied(first, top)
            val rest = players - first.toSet()
            val second = rest.maxOf(::score)
            return Leads(first.single(), top, rest.filter { score(it) == second }, second)
        }

        /** At the end: who among [players] won. Null with nobody left. */
        fun winner(game: Game, players: List<Int>): Remark? {
            val won = game.winners(players).ifEmpty { return null }
            return Won(won, game.scores[won.first()] ?: 0)
        }
    }
}
