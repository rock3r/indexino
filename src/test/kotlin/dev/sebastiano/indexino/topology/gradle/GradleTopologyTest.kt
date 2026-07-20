package dev.sebastiano.indexino.topology.gradle

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleTopologyTest {
    private val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")

    @Test
    fun `resolves ui module indexable sources`() {
        val result = GradleTopology.resolveSources(":ui", fixtureRoot, includeDeps = false)
        assertEquals("gradle-parse", result.topology)
        assertEquals(":ui", result.scope)
        assertEquals(
            listOf(
                "ui/src/main/java/LegacyPanel.java",
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/res/layout/main.xml",
            ),
            result.sourceFiles,
        )
    }

    @Test
    fun `include deps adds core sources`() {
        val result = GradleTopology.resolveSources(":ui", fixtureRoot, includeDeps = true)
        assertTrue(result.includeDeps)
        assertEquals(
            listOf(
                "core/src/main/kotlin/Core.kt",
                "ui/src/main/java/LegacyPanel.java",
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/res/layout/main.xml",
            ),
            result.sourceFiles,
        )
    }

    @Test
    fun `resolves Java and Android XML sources with Kotlin`() {
        val result = GradleTopology.resolveSources(":ui", fixtureRoot, includeDeps = false)
        assertEquals(
            listOf(
                "ui/src/main/java/LegacyPanel.java",
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/res/layout/main.xml",
            ),
            result.sourceFiles,
        )
    }

    @Test
    fun `root scope includes root project and included module sources`() {
        val result = GradleTopology.resolveSources(":", fixtureRoot)
        assertEquals(
            listOf(
                "core/src/main/kotlin/Core.kt",
                "src/main/java/RootLegacy.java",
                "src/main/kotlin/RootPanel.kt",
                "src/main/res/layout/root.xml",
                "ui/src/main/java/LegacyPanel.java",
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/res/layout/main.xml",
            ),
            result.sourceFiles,
        )
    }
}
