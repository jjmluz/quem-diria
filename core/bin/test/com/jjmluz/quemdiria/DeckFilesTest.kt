package com.jjmluz.quemdiria

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The app's real decks parse in both languages and have enough for several games. */
class DeckFilesTest {
    private val dir = File("../app/src/main/assets/decks")

    @Test
    fun theDecksParseAndFillAGame() {
        for (name in listOf("family.txt", "adults.txt")) {
            for (language in Language.entries) {
                val deck = Deck.parse(File(dir, name).readText(), language)
                assertTrue(deck.votes.size >= 100, "$name votes")
                assertTrue(deck.spotlights.size >= 30, "$name spotlights")
                assertTrue(deck.drawings.size >= 30, "$name drawings")
                (deck.spotlights.map { it.text } + deck.drawings.map { it.text }).forEach {
                    assertTrue("{name}" in it, "$name: no {name} in $it")
                }
                Game.plan((1..8).toList(), deck, Random(1))
            }
        }
    }
}
