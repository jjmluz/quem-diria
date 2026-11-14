package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoRulesTest {
    private val rules = PhotoRules.of(
        mapOf("Renée" to listOf("2012", "2014"), "Grace" to listOf("2013-2015"), "Oliver" to emptyList())
    )

    @Test
    fun leftOutYearsDontComeUp() {
        assertFalse(rules.allows("Renée", 2012))
        assertTrue(rules.allows("Renée", 2013))
        assertFalse(rules.allows("Renée", 2014))
    }

    @Test
    fun rangesCoverEveryYearInThem() {
        listOf(2013, 2014, 2015).forEach { assertFalse(rules.allows("Grace", it)) }
        assertTrue(rules.allows("Grace", 2012))
        assertTrue(rules.allows("Grace", 2016))
    }

    @Test
    fun noYearsLeavesThePersonOutAltogether() {
        assertFalse(rules.allows("Oliver", 1999))
        assertFalse(rules.allows("Oliver", 2024))
    }

    @Test
    fun namesMatchWhateverTheCaseAndAccents() {
        assertFalse(rules.allows("renee", 2012))
        assertFalse(rules.allows(" RENÉE ", 2014))
        assertTrue(rules.allows("Emma", 2012))
        assertTrue(PhotoRules.NONE.allows("Renée", 2012))
    }

    @Test
    fun aYearThatIsntOneIsRefused() {
        assertFailsWith<IllegalArgumentException> { PhotoRules.of(mapOf("Emma" to listOf("twenty"))) }
        assertFailsWith<IllegalArgumentException> { PhotoRules.of(mapOf("Emma" to listOf("2015-2012"))) }
    }
}
