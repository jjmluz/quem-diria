package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoRulesTest {
    private val rules = PhotoRules.of(
        mapOf("João" to listOf("2012", "2014"), "Diana" to listOf("2013-2015"), "Rui" to emptyList())
    )

    @Test
    fun leftOutYearsDontComeUp() {
        assertFalse(rules.allows("João", 2012))
        assertTrue(rules.allows("João", 2013))
        assertFalse(rules.allows("João", 2014))
    }

    @Test
    fun rangesCoverEveryYearInThem() {
        listOf(2013, 2014, 2015).forEach { assertFalse(rules.allows("Diana", it)) }
        assertTrue(rules.allows("Diana", 2012))
        assertTrue(rules.allows("Diana", 2016))
    }

    @Test
    fun noYearsLeavesThePersonOutAltogether() {
        assertFalse(rules.allows("Rui", 1999))
        assertFalse(rules.allows("Rui", 2024))
    }

    @Test
    fun namesMatchWhateverTheCaseAndAccents() {
        assertFalse(rules.allows("joao", 2012))
        assertFalse(rules.allows(" JOÃO ", 2014))
        assertTrue(rules.allows("Ana", 2012))
        assertTrue(PhotoRules.NONE.allows("João", 2012))
    }

    @Test
    fun aYearThatIsntOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { PhotoRules.of(mapOf("Ana" to listOf("twenty"))) }
        assertFailsWith<IllegalArgumentException> { PhotoRules.of(mapOf("Ana" to listOf("2015-2012"))) }
    }
}
