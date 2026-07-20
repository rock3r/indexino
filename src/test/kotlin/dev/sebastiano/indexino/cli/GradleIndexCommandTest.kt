package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.ComposeSelectionSiteRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleIndexCommandTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `index gradle module builds store with selection context`() {
        val workspace = createGitWorkspace()
        val exitCode =
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.GRADLE,
                            gradleModule = ":ui",
                            includeDeps = false,
                        ),
                    applications = listOf("selection-context"),
                )
        assertEquals(0, exitCode)

        val commit = gitHead(workspace)
        val manifest = ManifestIO.read(IndexPathResolver(workspace).resolveManifest(commit))
        assertEquals(":ui", manifest.scope)
        assertEquals("gradle-parse", manifest.topology)

        val store =
            XodusCodeIndexStore.open(
                IndexPathResolver(workspace).resolveBaseStore(commit),
                readOnly = true,
            )
        try {
            val sites = store.prefixScan("compose:selection-site:").toList()
            assertTrue(sites.isNotEmpty())
            val record = sites.first().second as ComposeSelectionSiteRecord
            assertEquals("ActionButton", record.callee)
            assertTrue(record.inSelectionContainer)
        } finally {
            store.close()
        }
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("gradle-index-")
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

    private fun gitHead(workspace: java.nio.file.Path): String =
        ProcessBuilder("git", "-C", workspace.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

    private fun runGit(workspace: java.nio.file.Path, vararg args: String) {
        check(
            ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        )
    }
}
