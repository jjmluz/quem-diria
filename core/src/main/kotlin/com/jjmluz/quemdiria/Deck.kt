package com.jjmluz.quemdiria

/** Which questions a game uses. */
enum class Mode { FAMILY, ADULTS }

/** The languages the questions are written in. Anything else plays in English. */
enum class Language { EN, PT }

/** A question or drawing prompt in the game's language. `{name}` stands for the player in the spotlight. */
data class Item(val id: String, val text: String)

/** A question about the player in the spotlight, with the four answers to choose from. */
data class SpotlightItem(val id: String, val text: String, val options: List<String>)

/** Every question of one mode, in one language. */
data class Deck(val votes: List<Item>, val spotlights: List<SpotlightItem>, val drawings: List<Item>) {
    companion object {
        /**
         * Reads a deck file (`assets/decks/family.txt`, `adults.txt`). Lines are `|`-separated, in sections:
         *
         * ```
         * [vote]       id | English | Portuguese
         * [spotlight]  id | English | Portuguese | 4 English answers split by " / " | 4 Portuguese ones
         * [draw]       id | English | Portuguese
         * ```
         *
         * Blank lines and lines starting with `#` are skipped.
         * @throws IllegalArgumentException naming the line, for a line that doesn't fit its section.
         */
        fun parse(text: String, language: Language): Deck {
            val votes = mutableListOf<Item>()
            val spotlights = mutableListOf<SpotlightItem>()
            val drawings = mutableListOf<Item>()
            var section = ""
            val ids = mutableSetOf<String>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.removeSurrounding("[", "]")
                    return@forEachIndexed
                }
                val fields = line.split("|").map { it.trim() }
                fun fail(why: String): Nothing = throw IllegalArgumentException("Line ${i + 1}: $why: $line")
                val id = fields[0]
                if (!ids.add(id)) fail("repeated id")
                val pick = if (language == Language.PT) 2 else 1
                when (section) {
                    "vote", "draw" -> {
                        if (fields.size != 3 || fields.any { it.isEmpty() }) fail("expected id | English | Portuguese")
                        (if (section == "vote") votes else drawings) += Item(id, fields[pick])
                    }
                    "spotlight" -> {
                        if (fields.size != 5 || fields.any { it.isEmpty() }) fail("expected id | English | Portuguese | answers | respostas")
                        val options = fields[pick + 2].split(" / ").map { it.trim() }
                        if (options.size != 4 || options.any { it.isEmpty() }) fail("expected 4 answers split by ' / '")
                        spotlights += SpotlightItem(id, fields[pick], options)
                    }
                    else -> fail("outside a [vote], [spotlight] or [draw] section")
                }
            }
            return Deck(votes, spotlights, drawings)
        }
    }
}

/** The text with the spotlight player's name in place of `{name}`. */
fun String.withName(name: String) = replace("{name}", name)
