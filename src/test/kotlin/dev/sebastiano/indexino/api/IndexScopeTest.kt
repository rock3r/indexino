package dev.sebastiano.indexino.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IndexScopeTest {
    @Test
    fun `gradle rejects parent path segments`() {
        val failure = assertFailsWith<IllegalArgumentException> { IndexScope.gradle(":..:sibling") }
        assertEquals(true, failure.message!!.contains(".."))
    }

    @Test
    fun `gradle rejects current-directory path segments`() {
        assertFailsWith<IllegalArgumentException> { IndexScope.gradle(":.:ui") }
    }

    @Test
    fun `gradle accepts ordinary module paths`() {
        val scope = IndexScope.gradle(":ui")
        assertEquals(":ui", scope.value)
        assertEquals(BuildSystem.GRADLE, scope.buildSystem)
    }
}
