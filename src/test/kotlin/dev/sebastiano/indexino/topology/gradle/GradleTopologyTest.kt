package dev.sebastiano.indexino.topology.gradle

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
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
    fun `includes CMP resources and skips test resources`() {
        val workspace = createTempDirectory("indexino-cmp-gradle-topology-")
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"cmp\"\ninclude(\":app\")\n",
        )
        Files.createDirectories(workspace.resolve("app/src/commonMain/composeResources/values"))
        Files.createDirectories(workspace.resolve("app/src/commonTest/composeResources/values"))
        Files.createDirectories(workspace.resolve("app/src/main/resources/META-INF"))
        Files.writeString(
            workspace.resolve("app/src/main/resources/META-INF/logback.xml"),
            "<configuration />",
        )
        Files.writeString(
            workspace.resolve("app/src/commonMain/composeResources/values/strings.xml"),
            "<resources />",
        )
        Files.writeString(
            workspace.resolve("app/src/commonTest/composeResources/values/test_strings.xml"),
            "<resources />",
        )

        val result = GradleTopology.resolveSources(":app", workspace, includeDeps = false)

        assertEquals(
            listOf("app/src/commonMain/composeResources/values/strings.xml"),
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

    @Test
    fun `root scope reports includeDeps true even when the request asked for false`() {
        // Root selection always walks every included module; reporting the requested false would
        // publish a false provenance record and defeat facade mismatch checks.
        val result = GradleTopology.resolveSources(":", fixtureRoot, includeDeps = false)
        assertTrue(result.includeDeps)
        assertEquals(":", result.scope)
    }
}
