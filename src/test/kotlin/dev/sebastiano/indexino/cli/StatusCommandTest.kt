package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.bazel.MockBazelQueryExecutor
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `status reports fresh after index`() {
        val workspace = createGitWorkspace()
        val mockOutput =
            Path("src/test/resources/fixtures/bazel/mock-query-output.txt").readText().lines()

        IndexCommand()
            .runIndexedBuild(
                project = workspace,
                bazelTarget = "//plugins/foo/ui:ui",
                applications = listOf("selection-context"),
                queryExecutor = MockBazelQueryExecutor(mockOutput),
            )

        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    bazelTarget = "//plugins/foo/ui:ui",
                    queryExecutor = MockBazelQueryExecutor(mockOutput),
                    output = { output.appendLine(it) },
                )

        assertEquals(0, exitCode)
        val text = output.toString()
        assertTrue(text.contains("\"fresh\":true"), text)
        assertTrue(text.contains("\"sourceFileCount\":3"), text)
        assertTrue(text.contains("selection-context"), text)
    }

    @Test
    fun `status reports missing index`() {
        val workspace = createGitWorkspace()
        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    bazelTarget = "//plugins/foo/ui:ui",
                    queryExecutor = MockBazelQueryExecutor(emptyList()),
                    output = { output.appendLine(it) },
                )
        assertEquals(CliExitCodes.ANALYSIS_ERROR, exitCode)
        assertTrue(output.toString().contains("\"indexed\":false"))
    }

    @Test
    fun `status rehashes with stored includeDeps when reconstructing from the manifest`() {
        val workspace = createGradleWorkspace()
        val indexExit =
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.GRADLE,
                            gradleModule = ":ui",
                            includeDeps = true,
                        ),
                    applications = emptyList(),
                )
        assertEquals(0, indexExit)

        val output = StringBuilder()
        // Reconstruct from the stored manifest (no live --gradle-module). Default CLI
        // includeDeps=false must not change the hashed source set away from the manifest's true.
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest = TopologyRequest(buildSystem = BuildSystem.AUTO),
                    output = { output.appendLine(it) },
                )

        assertEquals(0, exitCode)
        val text = output.toString()
        assertTrue(text.contains("\"fresh\":true"), text)
        assertTrue(text.contains("\"scope\":\":ui\""), text)
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("status-cmd-test-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/fixtures/bazel")
        Files.walk(fixtureRoot).forEach { path ->
            val relative = fixtureRoot.relativize(path)
            val dest = workspace.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(dest)
            } else {
                Files.createDirectories(dest.parent)
                Files.copy(path, dest)
            }
        }
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun createGradleWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("status-gradle-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).forEach { path ->
            val relative = fixtureRoot.relativize(path)
            val dest = workspace.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(dest)
            } else {
                Files.createDirectories(dest.parent)
                Files.copy(path, dest)
            }
        }
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        val process =
            ProcessBuilder(
                    *listOf("git", "-C", workspace.toString(), "-c", "commit.gpgsign=false", *args)
                        .toTypedArray()
                )
                .redirectErrorStream(true)
                .start()
        val result = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git failed: $result" }
    }
}
