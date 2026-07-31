package dev.sebastiano.indexino.topology.gradle

import dev.sebastiano.indexino.topology.ExternalSourceMount
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GradleIncludedBuildTopologyTest {
    @Test
    fun `rejects unavailable included build mounts`() {
        val workspace =
            createTempDirectory("indexino-missing-build-").also(temporaryDirectories::add)
        workspace.resolve("settings.gradle.kts").writeText("includeBuild(\"../missing-build\")")
        workspace
            .resolve("src/main/kotlin/App.kt")
            .also { it.parent.createDirectories() }
            .writeText("class App")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                GradleTopology.resolveSources(":", workspace)
            }

        assertEquals(
            "Gradle included build mount is unavailable: ${workspace.resolve("../missing-build").normalize()}",
            failure.message,
        )
    }

    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `rejects included builds outside the workspace parent policy`() {
        val root = createTempDirectory("indexino-external-policy-").also(temporaryDirectories::add)
        val workspace = root.resolve("app").also { it.createDirectories() }
        val outside =
            root.parent.resolve("${root.fileName}-outside").also {
                it.createDirectories()
                temporaryDirectories.add(it)
            }
        workspace
            .resolve("settings.gradle.kts")
            .writeText("includeBuild(\"../../${outside.fileName}\")")
        outside.resolve("settings.gradle.kts").writeText("rootProject.name = \"outside\"")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                GradleTopology.resolveSources(":", workspace)
            }

        assertEquals(
            "Gradle included build is outside the allowed external root policy: ${outside.toRealPath()}",
            failure.message,
        )
    }

    @Test
    fun `reports included builds inside the workspace`() {
        val workspace =
            createTempDirectory("indexino-local-included-").also(temporaryDirectories::add)
        val includedBuild = workspace.resolve("build-logic").also { it.createDirectories() }
        workspace.resolve("settings.gradle.kts").writeText("includeBuild(\"build-logic\")")
        workspace
            .resolve("src/main/kotlin/App.kt")
            .also { it.parent.createDirectories() }
            .writeText("class App")
        includedBuild.resolve("settings.gradle.kts").writeText("rootProject.name = \"build-logic\"")
        includedBuild
            .resolve("src/main/kotlin/Convention.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Convention")

        val result = GradleTopology.resolveSources(":", workspace)

        assertEquals(listOf(includedBuild.toRealPath()), result.externalMounts)
        assertEquals(
            listOf("src/main/kotlin/Convention.kt"),
            result.externalSources.single().sourceFiles,
        )
    }

    @Test
    fun `reports declared external included build mounts`() {
        val root = createTempDirectory("indexino-external-report-").also(temporaryDirectories::add)
        val workspace = root.resolve("app").also { it.createDirectories() }
        val includedBuild = root.resolve("build-logic").also { it.createDirectories() }
        workspace.resolve("settings.gradle.kts").writeText("includeBuild(\"../build-logic\")")
        includedBuild.resolve("settings.gradle.kts").writeText("rootProject.name = \"build-logic\"")
        val diagnostics = mutableListOf<String>()

        GradleTopology.resolveSources(":", workspace, onStderr = diagnostics::add)

        assertContains(
            diagnostics,
            "gradle-parse: external included build ${includedBuild.toRealPath()}",
        )
    }

    @Test
    fun `reports canonical external included build mounts`() {
        val root = createTempDirectory("indexino-included-build-").also(temporaryDirectories::add)
        val workspace = root.resolve("app").also { it.createDirectories() }
        val includedBuild = root.resolve("build-logic").also { it.createDirectories() }
        workspace.resolve("settings.gradle.kts").writeText("includeBuild(\"../build-logic\")")
        workspace
            .resolve("src/main/kotlin/App.kt")
            .also { it.parent.createDirectories() }
            .writeText("class App")
        includedBuild.resolve("settings.gradle.kts").writeText("rootProject.name = \"build-logic\"")
        includedBuild
            .resolve("src/main/kotlin/Convention.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Convention")

        val result = GradleTopology.resolveSources(":", workspace)

        assertEquals(listOf(includedBuild.toRealPath()), result.externalMounts)
        assertEquals(
            listOf(
                ExternalSourceMount(
                    includedBuild.toRealPath(),
                    listOf("src/main/kotlin/Convention.kt"),
                )
            ),
            result.externalSources,
        )
    }
}
