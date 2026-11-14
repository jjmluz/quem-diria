package com.jjmluz.quemdiria

import java.text.Normalizer

/**
 * Photos the family keeps out of the game: for a Google Photos person (by name), the years whose photos of them
 * never come up, or all of them. The TV reads them from a file kept out of git (see the README).
 */
class PhotoRules private constructor(private val years: Map<String, Set<Int>?>) {
    /** Whether a photo of [person] (their name in Google Photos) taken in [year] may come up. */
    fun allows(person: String, year: Int): Boolean {
        val name = plain(person)
        if (name !in years) return true
        val out = years[name] ?: return false
        return year !in out
    }

    val isEmpty: Boolean get() = years.isEmpty()

    companion object {
        val NONE = PhotoRules(emptyMap())

        /**
         * Rules from each person's name to their left-out years: `"2012"`, a range `"2012-2015"`, or no years at
         * all for every photo of them. Names match whatever the case and accents ("renee" is "Renée").
         * @throws IllegalArgumentException naming the year that isn't one.
         */
        fun of(entries: Map<String, List<String>>): PhotoRules = PhotoRules(
            entries.entries.associate { (name, list) ->
                plain(name) to if (list.isEmpty()) null else list.flatMap(::years).toSet()
            }
        )

        private fun years(text: String): IntRange {
            val parts = text.split("-").map { it.trim() }
            val from = parts[0].toIntOrNull()
            val to = parts.getOrNull(1)?.toIntOrNull() ?: from
            require(parts.size <= 2 && from != null && to != null && from <= to) { "Not a year or years: \"$text\"" }
            return from..to
        }

        private fun plain(s: String) =
            Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
    }
}
