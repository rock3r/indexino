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
            .resolve("tools/base-local/src/test/kotlin/Fixture.kt")
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
    fun `includes production modules named test`() {
        val workspace = createTempDirectory("indexino-repo-").also(temporaryDirectories::add)
        val manifest = workspace.resolve(".repo/manifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText("<manifest><project name=\"tools\" path=\"tools\"/></manifest>")
        workspace
            .resolve("tools/test/src/main/kotlin/Production.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Production")

        val result =
            TopologyResolver.resolve(
                workspace,
                TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
            )

        assertEquals(
            listOf("test/src/main/kotlin/Production.kt"),
            result.externalSources.single().sourceFiles,
        )
    }

    @Test
    fun `does not scan nested repo project sources through their parent mount`() {
        val workspace = createTempDirectory("indexino-repo-").also(temporaryDirectories::add)
        val manifest = workspace.resolve(".repo/manifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
            <manifest>
              <project name="parent" path="tools"/>
              <project name="child" path="tools/child"/>
            </manifest>
            """
                .trimIndent()
        )
        workspace
            .resolve("tools/src/main/kotlin/Parent.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Parent")
        workspace
            .resolve("tools/child/src/main/kotlin/Child.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Child")

        val result =
            TopologyResolver.resolve(
                workspace,
                TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
            )

        assertEquals(
            listOf("src/main/kotlin/Parent.kt"),
            result.externalSources.single { it.originId == "repo:parent" }.sourceFiles,
        )
        assertEquals(
            listOf("src/main/kotlin/Child.kt"),
            result.externalSources.single { it.originId == "repo:child" }.sourceFiles,
        )
    }

    @Test
    fun `disambiguates duplicate project names by mount path`() {
        val workspace = createTempDirectory("indexino-repo-").also(temporaryDirectories::add)
        val manifest = workspace.resolve(".repo/manifest.xml")
        manifest.parent.createDirectories()
        manifest.writeText(
            """
            <manifest>
              <project name="shared" path="tools/one"/>
              <project name="shared" path="tools/two"/>
            </manifest>
            """
                .trimIndent()
        )
        listOf("one", "two").forEach { mount ->
            workspace
                .resolve("tools/$mount/src/main/kotlin/$mount.kt")
                .also { it.parent.createDirectories() }
                .writeText("class $mount")
        }

        val result =
            TopologyResolver.resolve(
                workspace,
                TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
            )

        assertEquals(
            listOf("repo:shared:tools/one", "repo:shared:tools/two"),
            result.externalSources.map { it.originId },
        )
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
