package dev.sebastiano.indexino.cli

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

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        val process =
            ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
                .redirectErrorStream(true)
                .start()
        val result = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git failed: $result" }
    }
}
