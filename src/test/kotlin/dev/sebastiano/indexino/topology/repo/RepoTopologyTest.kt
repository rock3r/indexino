package dev.sebastiano.indexino.topology.repo

import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResolver
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RepoTopologyTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `excludes nested test fixtures and generated trees`() {
        val workspace = createTempDirectory("indexino-repo-").also(temporaryDirectories::add)
        val manifest = workspace.resolve(".repo/manifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText(
            "<manifest><project name=\"platform/tools/base\" path=\"tools/base-local\"/></manifest>"
        )
        workspace
            .resolve("tools/base-local/src/main/kotlin/Base.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Base")
        workspace
            .resolve("tools/base-local/tests/src/main/kotlin/Fixture.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Fixture")
        workspace
            .resolve("tools/base-local/build/generated/src/main/kotlin/Generated.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Generated")

        val result =
            TopologyResolver.resolve(
                workspace,
                TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
            )

        assertEquals(listOf("src/main/kotlin/Base.kt"), result.externalSources.single().sourceFiles)
    }

    @Test
    fun `resolves each manifest project source closure`() {
        val workspace = createTempDirectory("indexino-repo-").also(temporaryDirectories::add)
        val manifest = workspace.resolve(".repo/manifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
            <manifest>
              <project name="platform/tools/base" path="tools/base-local" revision="deadbeef"/>
            </manifest>
            """
                .trimIndent()
        )
        workspace
            .resolve("tools/base-local/src/main/kotlin/Base.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Base")

        val result =
            TopologyResolver.resolve(
                workspace,
                TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
            )

        assertEquals(1, result.externalSources.size)
        assertEquals("repo:platform/tools/base", result.externalSources.single().originId)
        assertEquals("deadbeef", result.externalSources.single().expectedRevision)
        assertEquals(listOf("src/main/kotlin/Base.kt"), result.externalSources.single().sourceFiles)
    }
}
