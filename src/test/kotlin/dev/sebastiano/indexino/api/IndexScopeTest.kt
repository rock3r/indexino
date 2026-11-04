package dev.sebastiano.indexino.api

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
    fun `gradle rejects slash-delimited parent escapes inside a segment`() {
        assertFailsWith<IllegalArgumentException> { IndexScope.gradle(":../../outside") }
        assertFailsWith<IllegalArgumentException> { IndexScope.gradle(":foo/../bar") }
    }

    @Test
    fun `gradle accepts ordinary module paths`() {
        val scope = IndexScope.gradle(":ui")
        assertEquals(":ui", scope.value)
        assertEquals(BuildSystem.GRADLE, scope.buildSystem)
    }

    @Test
    fun `repo scope has a public Java factory and build system constant`() {
        val factory =
            assertNotNull(
                IndexScope::class.java.methods.singleOrNull {
                    it.name == "repo" && it.parameterCount == 0 && Modifier.isStatic(it.modifiers)
                }
            )
        val scope = factory.invoke(null) as IndexScope
        assertEquals(BuildSystem::class.java.getField("REPO").get(null), scope.buildSystem)
        assertEquals(".repo/manifest.xml", scope.value)
        assertEquals(true, scope.includesDependencies)
        assertEquals(scope, scope.includingDependencies())
    }
}
