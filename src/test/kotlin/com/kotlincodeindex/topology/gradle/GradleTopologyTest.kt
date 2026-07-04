package com.kotlincodeindex.topology.gradle

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleTopologyTest {
    private val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")

    @Test
    fun `resolves ui module kotlin sources`() {
        val result = GradleTopology.resolveSources(":ui", fixtureRoot, includeDeps = false)
        assertEquals("gradle-parse", result.topology)
        assertEquals(":ui", result.scope)
        assertEquals(listOf("ui/src/main/kotlin/Panel.kt"), result.sourceFiles)
    }

    @Test
    fun `include deps adds core sources`() {
        val result = GradleTopology.resolveSources(":ui", fixtureRoot, includeDeps = true)
        assertTrue(result.includeDeps)
        assertEquals(
            listOf(
                "core/src/main/kotlin/Core.kt",
                "ui/src/main/kotlin/Panel.kt",
            ),
            result.sourceFiles,
        )
    }
}
