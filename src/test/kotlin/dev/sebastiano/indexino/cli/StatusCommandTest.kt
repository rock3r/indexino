package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.bazel.MockBazelQueryExecutor
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                applications = listOf("dev.sebastiano.selection-context"),
                queryExecutor = MockBazelQueryExecutor(mockOutput),
            )

        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.BAZEL,
                            bazelTarget = "//plugins/foo/ui:ui",
                            includeDeps = true,
                        ),
                    bazelQueryExecutor = MockBazelQueryExecutor(mockOutput),
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

    @Test
    fun `status marks resolved repo manifest changes stale`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        val request = TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest)
        assertEquals(
            0,
            IndexCommand().runIndexedBuild(
                project = workspace,
                topologyRequest = request,
                applications = emptyList(),
            ),
        )

        val freshOutput = StringBuilder()
        assertEquals(
            0,
            StatusCommand().runStatus(
                project = workspace,
                topologyRequest = request,
                output = { freshOutput.appendLine(it) },
            ),
        )
        assertTrue(freshOutput.toString().contains("\"fresh\":true"), freshOutput.toString())
        val cleanGitStatus =
            ProcessBuilder("git", "-C", workspace.resolve("tools").toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertEquals("", cleanGitStatus)
        val cleanOrigins =
            ManifestOriginResolver.resolve(
                workspace,
                listOf(
                    IndexedSource(
                        "repo:tools",
                        workspace.resolve("tools"),
                        "src/main/kotlin/Tool.kt",
                    )
                ),
                mapOf(workspace.resolve("tools").toRealPath() to ("repo:tools" to "one")),
            )
        assertFalse(cleanOrigins.single().dirty, "$cleanOrigins")
        val untrackedInput = workspace.resolve("tools/untracked.config")
        untrackedInput.writeText("generated input")
        val gitStatus =
            ProcessBuilder("git", "-C", workspace.resolve("tools").toString(), "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
        assertTrue(gitStatus.contains("untracked.config"), gitStatus)
        val dirtyOrigins =
            ManifestOriginResolver.resolve(
                workspace,
                listOf(
                    IndexedSource(
                        "repo:tools",
                        workspace.resolve("tools"),
                        "src/main/kotlin/Tool.kt",
                    )
                ),
                mapOf(workspace.resolve("tools").toRealPath() to ("repo:tools" to "one")),
            )
        assertTrue(dirtyOrigins.single().dirty, "$gitStatus origins=$dirtyOrigins")
        val dirtyOutput = StringBuilder()
        assertEquals(
            0,
            StatusCommand().runStatus(
                project = workspace,
                topologyRequest = request,
                output = { dirtyOutput.appendLine(it) },
            ),
        )
        assertFalse(dirtyOutput.toString().contains("\"fresh\":true"), dirtyOutput.toString())
        Files.delete(untrackedInput)

        manifest.writeText(
            """
            <manifest>
              <project name="tools" path="tools" revision="two" />
            </manifest>
            """.trimIndent(),
        )

        val output = StringBuilder()
        val exitCode =
            StatusCommand().runStatus(
                project = workspace,
                topologyRequest = request,
                output = { output.appendLine(it) },
            )

        assertEquals(0, exitCode)
        assertFalse(output.toString().contains("\"fresh\":true"), output.toString())
    }

    @Test
    fun `status reports a missing repo child origin as unavailable`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        val request = TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest)
        assertEquals(
            0,
            IndexCommand().runIndexedBuild(
                project = workspace,
                topologyRequest = request,
                applications = emptyList(),
            ),
        )
        assertTrue(workspace.resolve("tools").toFile().deleteRecursively())

        val output = StringBuilder()
        val exitCode =
            StatusCommand().runStatus(
                project = workspace,
                topologyRequest = request,
                output = { output.appendLine(it) },
            )

        assertEquals(CliExitCodes.TOPOLOGY_FAILED, exitCode)
        assertTrue(output.toString().contains("\"fresh\":false"), output.toString())
        assertTrue(output.toString().contains("\"available\":false"), output.toString())
    }

    @Test
    fun `status CLI reports a missing repo child origin as unavailable`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        assertEquals(
            0,
            IndexCommand().runIndexedBuild(
                project = workspace,
                topologyRequest = TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
                applications = emptyList(),
            ),
        )
        assertTrue(workspace.resolve("tools").toFile().deleteRecursively())

        val result =
            StatusCommand().test(
                "--project",
                workspace.toString(),
                "--build-system",
                "repo",
            )

        assertEquals(CliExitCodes.TOPOLOGY_FAILED, result.statusCode)
        assertTrue(result.output.contains("\"fresh\":false"), result.output)
        assertTrue(result.output.contains("\"available\":false"), result.output)
    }

    @Test
    fun `status marks explicit includeDeps mode changes stale`() {
        val workspace = createGradleWorkspace()
        assertEquals(
            0,
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
                ),
        )

        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.GRADLE,
                            gradleModule = ":ui",
                            includeDeps = false,
                        ),
                    output = { output.appendLine(it) },
                )

        assertEquals(0, exitCode)
        assertFalse(output.toString().contains("\"fresh\":true"), output.toString())
    }

    private fun createRepoWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("status-repo-")
        tempDirs.add(workspace)
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("tools/src/main/kotlin"))
        workspace.resolve(".repo/manifest.xml").writeText(
            """
            <manifest>
              <project name="tools" path="tools" revision="one" />
            </manifest>
            """.trimIndent(),
        )
        Files.writeString(workspace.resolve("tools/src/main/kotlin/Tool.kt"), "class Tool")
        val tools = workspace.resolve("tools")
        runGit(tools, "init")
        runGit(tools, "config", "user.email", "test@example.com")
        runGit(tools, "config", "user.name", "Test User")
        runGit(tools, "add", ".")
        runGit(tools, "commit", "-m", "tools fixture")
        return workspace
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
