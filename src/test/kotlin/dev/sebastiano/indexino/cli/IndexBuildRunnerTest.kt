package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class IndexBuildRunnerTest {
    @Test
    fun `indexes external included build sources in composite manifest`() {
        val root = tempDir.resolve("included")
        val workspace = root.resolve("app")
        val includedBuild = root.resolve("build-logic")
        Files.createDirectories(workspace.resolve("src/main/kotlin"))
        Files.createDirectories(includedBuild.resolve("src/main/kotlin"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "includeBuild(\"../build-logic\")",
        )
        Files.writeString(workspace.resolve("src/main/kotlin/App.kt"), "class App")
        Files.writeString(
            includedBuild.resolve("settings.gradle.kts"),
            "rootProject.name = \"logic\"",
        )
        Files.writeString(
            includedBuild.resolve("src/main/kotlin/Convention.kt"),
            "class Convention",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "workspace")

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(2, execution.manifest?.sourceFileCount, "manifest=${execution.manifest}")
        assertEquals(2, execution.manifest?.origins?.size, "manifest=${execution.manifest}")
    }

    @Test
    fun `indexes repo manifest project with manifest identity and revision`() {
        val workspace = tempDir.resolve("repo-workspace")
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("local/tools/base/src/main/kotlin"))
        Files.writeString(
            workspace.resolve(".repo/manifest.xml"),
            """
            <manifest>
              <project name="platform/tools/base" path="local/tools/base" revision="deadbeef"/>
            </manifest>
            """
                .trimIndent(),
        )
        Files.writeString(
            workspace.resolve("local/tools/base/src/main/kotlin/Base.kt"),
            "class Base",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "repo workspace")

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.REPO,
                            repoManifest = workspace.resolve(".repo/manifest.xml"),
                        ),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals("repo:platform/tools/base", execution.manifest?.origins?.single()?.originId)
        assertEquals("deadbeef", execution.manifest?.origins?.single()?.expectedRevision)
    }

    @Test
    fun `rejects unavailable repo manifest projects`() {
        val workspace = tempDir.resolve("partial-repo-workspace")
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("checked-out/src/main/kotlin"))
        Files.writeString(
            workspace.resolve(".repo/manifest.xml"),
            """
            <manifest>
              <project name="checked" path="checked-out" revision="one"/>
              <project name="missing" path="not-synced" revision="two"/>
            </manifest>
            """
                .trimIndent(),
        )
        Files.writeString(
            workspace.resolve("checked-out/src/main/kotlin/Checked.kt"),
            "class Checked",
        )
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "partial repo workspace")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                IndexBuildRunner(
                        project = workspace,
                        topologyRequest =
                            TopologyRequest(
                                buildSystem = BuildSystem.REPO,
                                repoManifest = workspace.resolve(".repo/manifest.xml"),
                            ),
                        applications = emptyList(),
                        bazelQueryExecutor = null,
                        bazelProcessRunner = null,
                        progress = {},
                        machineProgress = null,
                        storeRootOverride = tempDir.resolve("store"),
                    )
                    .runDetailed()
            }

        assertContains(failure.message.orEmpty(), "repo project mount is unavailable: missing")
    }

    @Test
    fun `manifest records expected revision for Git submodule origin`() {
        val child = tempDir.resolve("child")
        Files.createDirectories(child.resolve("src/main/kotlin"))
        Files.writeString(child.resolve("settings.gradle.kts"), "rootProject.name = \\\"child\\\"")
        Files.writeString(child.resolve("src/main/kotlin/Child.kt"), "class Child")
        git(child, "init")
        git(child, "config", "user.email", "test@example.invalid")
        git(child, "config", "user.name", "Indexino Test")
        git(child, "add", ".")
        git(child, "commit", "-m", "child")
        val childRevision = git(child, "rev-parse", "HEAD").trim()

        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        workspace.resolve("ui").toFile().deleteRecursively()
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "root")
        git(
            workspace,
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "add",
            child.toString(),
            "ui",
        )
        git(workspace, "commit", "-m", "add submodule")
        Files.writeString(workspace.resolve("ui/src/main/kotlin/Child.kt"), "class DirtyChild")

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {},
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(childRevision, execution.manifest?.origins?.single()?.expectedRevision)
        assertEquals(true, execution.manifest?.origins?.single()?.dirty)
    }

    @Test
    fun `incremental build retains nested origin identity`() {
        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "initial fixture")
        val nested = workspace.resolve("ui")
        git(nested, "init")
        git(nested, "config", "user.email", "test@example.invalid")
        git(nested, "config", "user.name", "Indexino Test")
        git(nested, "add", ".")
        git(nested, "commit", "-m", "nested fixture")
        val runner = {
            IndexBuildRunner(
                project = workspace,
                topologyRequest =
                    TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                applications = emptyList(),
                bazelQueryExecutor = null,
                bazelProcessRunner = null,
                progress = {},
                machineProgress = null,
                storeRootOverride = tempDir.resolve("store"),
            )
        }
        val initial = runner().runDetailed()
        assertEquals(CliExitCodes.SUCCESS, initial.exitCode)
        assertEquals(setOf("git:ui"), initial.manifest?.origins?.map { it.originId }?.toSet())
        val source = nested.resolve("src/main/kotlin/Panel.kt")
        source.writeText(source.readText() + "\nfun nestedChange() = Unit\n")

        val execution = runner().runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(
            setOf("git:ui"),
            execution.changes?.changedSources?.map { it.originId }?.toSet(),
        )
    }

    @TempDir lateinit var tempDir: Path

    @Test
    fun `detailed result keeps the commit indexed when head advances during the run`() {
        val workspace = tempDir.resolve("workspace")
        copyFixture(Path("src/test/resources/gradle-fixtures/multi-module"), workspace)
        git(workspace, "init")
        git(workspace, "config", "user.email", "test@example.invalid")
        git(workspace, "config", "user.name", "Indexino Test")
        git(workspace, "add", ".")
        git(workspace, "commit", "-m", "initial fixture")
        val indexedCommit = git(workspace, "rev-parse", "HEAD").trim()
        var headAdvanced = false

        val execution =
            IndexBuildRunner(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.GRADLE, gradleModule = ":ui"),
                    applications = emptyList(),
                    bazelQueryExecutor = null,
                    bazelProcessRunner = null,
                    progress = {
                        if (!headAdvanced) {
                            headAdvanced = true
                            Files.writeString(workspace.resolve("head-marker.txt"), "advanced")
                            git(workspace, "add", "head-marker.txt")
                            git(workspace, "commit", "-m", "advance head")
                        }
                    },
                    machineProgress = null,
                    storeRootOverride = tempDir.resolve("store"),
                )
                .runDetailed()

        assertEquals(CliExitCodes.SUCCESS, execution.exitCode)
        assertEquals(indexedCommit, execution.manifest?.commit)
    }

    private fun copyFixture(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target)
                }
            }
        }
    }

    private fun git(workspace: Path, vararg arguments: String): String {
        val process =
            ProcessBuilder(
                    "git",
                    "-C",
                    workspace.toString(),
                    "-c",
                    "commit.gpgsign=false",
                    *arguments,
                )
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}
