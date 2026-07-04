package com.kotlincodeindex.cli

import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.topology.bazel.MockBazelQueryExecutor
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class IndexSkipFreshTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `second index skips when manifest is fresh`() {
        val workspace = createGitWorkspace()
        val mockOutput = Path("src/test/resources/fixtures/bazel/mock-query-output.txt")
            .readText()
            .lines()
        val executor = MockBazelQueryExecutor(mockOutput)
        val command = IndexCommand()

        val stderrFirst = StringBuilder()
        assertEquals(0, command.runIndexedBuild(
            project = workspace,
            bazelTarget = "//plugins/foo/ui:ui",
            applications = emptyList(),
            queryExecutor = executor,
            progress = { stderrFirst.appendLine(it) },
        ))

        val stderrSecond = StringBuilder()
        assertEquals(0, command.runIndexedBuild(
            project = workspace,
            bazelTarget = "//plugins/foo/ui:ui",
            applications = emptyList(),
            queryExecutor = executor,
            progress = { stderrSecond.appendLine(it) },
        ))

        assertTrue(stderrFirst.toString().contains("FileHashProducer"))
        assertTrue(stderrSecond.toString().contains("skip"), stderrSecond.toString())
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("skip-fresh-test-")
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
        val process = ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
            .redirectErrorStream(true)
            .start()
        check(process.waitFor() == 0)
    }
}
