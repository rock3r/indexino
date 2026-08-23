package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.SourceOriginResolver
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
import kotlin.test.assertNotEquals
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
    fun `bazel status compatibility overload keeps dependency closure default`() {
        val workspace = createGitWorkspace()
        val mockOutput =
            Path("src/test/resources/fixtures/bazel/mock-query-output.txt").readText().lines()
        assertEquals(
            CliExitCodes.SUCCESS,
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    bazelTarget = "//plugins/foo/ui:ui",
                    applications = emptyList(),
                    queryExecutor = MockBazelQueryExecutor(mockOutput),
                ),
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

        assertEquals(CliExitCodes.SUCCESS, exitCode)
        assertTrue(output.toString().contains("\"fresh\":true"), output.toString())
    }

    @Test
    fun `status reads the published generation when no compatibility projection exists`() {
        val workspace = createGitWorkspace()
        val cacheRoot = createTempDirectory("status-generation-cache-").also(tempDirs::add)
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        try {
            System.setProperty("indexino.cache.dir", cacheRoot.toString())
            val mockOutput =
                Path("src/test/resources/fixtures/bazel/mock-query-output.txt").readText().lines()
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    bazelTarget = "//plugins/foo/ui:ui",
                    applications = listOf("dev.sebastiano.selection-context"),
                    queryExecutor = MockBazelQueryExecutor(mockOutput),
                )
            val commit = GitHeadResolver.resolve(workspace)
            val resolver = IndexPathResolver(workspace)
            val manifest = ManifestIO.read(resolver.resolveManifest(commit))
            WorkspaceGenerationManifestStore(cacheRoot, InProcessCacheLayout.workspaceId(workspace))
                .publish(
                    WorkspaceGenerationManifest(
                        generation = "generation",
                        workspaceRevisionFingerprint = "revision",
                        originId = "workspace",
                        revision = commit,
                        stateFingerprint = "state",
                        packKeys = listOf("pack"),
                        compatibilityManifest = manifest,
                    )
                )
            Files.delete(resolver.resolveManifest(commit))

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

            assertEquals(CliExitCodes.SUCCESS, exitCode)
            assertTrue(output.toString().contains("\"fresh\":true"), output.toString())
        } finally {
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
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

    @Suppress("LongMethod")
    @Test
    fun `status reports last known state without resolving a repo manifest`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        val request = TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest)
        assertEquals(
            0,
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest = request,
                    applications = emptyList(),
                ),
        )

        val freshOutput = StringBuilder()
        assertEquals(
            0,
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest = request,
                    output = { freshOutput.appendLine(it) },
                ),
        )
        assertTrue(freshOutput.toString().contains("\"indexed\":true"), freshOutput.toString())
        val cleanGitStatus =
            ProcessBuilder(
                    "git",
                    "-C",
                    workspace.resolve("tools").toString(),
                    "status",
                    "--porcelain",
                )
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
        assertFalse(cleanOrigins.single { it.originId == "repo:tools" }.dirty, "$cleanOrigins")
        val untrackedInput = workspace.resolve("tools/untracked.config")
        untrackedInput.writeText("generated input")
        val gitStatus =
            ProcessBuilder(
                    "git",
                    "-C",
                    workspace.resolve("tools").toString(),
                    "status",
                    "--porcelain",
                )
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
        assertTrue(
            dirtyOrigins.single { it.originId == "repo:tools" }.dirty,
            "$gitStatus origins=$dirtyOrigins",
        )
        untrackedInput.writeText("changed generated input")
        val changedDirtyOrigins =
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
        assertNotEquals(dirtyOrigins, changedDirtyOrigins)
        val dirtyOutput = StringBuilder()
        assertEquals(
            0,
            StatusCommand()
                .runStatus(
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
            """
                .trimIndent()
        )

        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest = request,
                    output = { output.appendLine(it) },
                )

        assertEquals(0, exitCode)
        assertTrue(output.toString().contains("\"indexed\":true"), output.toString())
    }

    @Test
    fun `status reports cached state when a repo child origin is missing`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        val request = TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest)
        assertEquals(
            0,
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest = request,
                    applications = emptyList(),
                ),
        )
        assertTrue(workspace.resolve("tools").toFile().deleteRecursively())

        val output = StringBuilder()
        val exitCode =
            StatusCommand()
                .runStatus(
                    project = workspace,
                    topologyRequest = request,
                    output = { output.appendLine(it) },
                )

        assertEquals(CliExitCodes.SUCCESS, exitCode)
        assertTrue(output.toString().contains("\"indexed\":true"), output.toString())
    }

    @Test
    fun `status CLI reports cached state when a repo child origin is missing`() {
        val workspace = createRepoWorkspace()
        val manifest = workspace.resolve(".repo/manifest.xml")
        assertEquals(
            0,
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(buildSystem = BuildSystem.REPO, repoManifest = manifest),
                    applications = emptyList(),
                ),
        )
        assertTrue(workspace.resolve("tools").toFile().deleteRecursively())

        val result =
            StatusCommand().test("--project", workspace.toString(), "--build-system", "repo")

        assertEquals(CliExitCodes.SUCCESS, result.statusCode)
        assertTrue(result.output.contains("\"indexed\":true"), result.output)
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

    @Test
    fun `status auto mode honors explicit Gradle scope in a hybrid workspace`() {
        val workspace = createGradleWorkspace()
        workspace.resolve("WORKSPACE").writeText("")
        assertEquals(
            0,
            IndexCommand()
                .runIndexedBuild(
                    project = workspace,
                    topologyRequest =
                        TopologyRequest(
                            buildSystem = BuildSystem.GRADLE,
                            gradleModule = ":ui",
                            includeDeps = false,
                        ),
                    applications = emptyList(),
                ),
        )

        val result =
            StatusCommand()
                .test(
                    "--project",
                    workspace.toString(),
                    "--build-system",
                    "auto",
                    "--gradle-module",
                    ":ui",
                )

        assertEquals(CliExitCodes.SUCCESS, result.statusCode)
        assertTrue(result.output.contains("\"fresh\":true"), result.output)
    }

    private fun createRepoWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("status-repo-")
        tempDirs.add(workspace)
        Files.createDirectories(workspace.resolve(".repo"))
        Files.createDirectories(workspace.resolve("tools/src/main/kotlin"))
        workspace
            .resolve(".repo/manifest.xml")
            .writeText(
                """
                <manifest>
                  <project name="tools" path="tools" revision="one" />
                </manifest>
                """
                    .trimIndent()
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

    @Test
    fun `retains source-less external origins in manifest provenance`() {
        val workspace = createGradleWorkspace()
        val externalRoot = createTempDirectory("status-external-").also(tempDirs::add)

        val origins =
            ManifestOriginResolver.resolve(
                workspace,
                sources = emptyList(),
                externalOriginMetadata =
                    mapOf(externalRoot.toRealPath() to ("gradle:build-logic" to "expected")),
                includeWorkspaceWithoutSources = true,
            )

        assertEquals(listOf("gradle:build-logic", "workspace"), origins.map { it.originId })
        assertEquals(
            "expected",
            origins.single { it.originId == "gradle:build-logic" }.expectedRevision,
        )
        val unlabelledOrigins =
            ManifestOriginResolver.resolve(
                workspace,
                sources = emptyList(),
                externalOriginMetadata = mapOf(externalRoot.toRealPath() to (null to null)),
                includeWorkspaceWithoutSources = true,
            )
        assertEquals(
            listOf(SourceOriginResolver.externalOriginId(externalRoot), "workspace"),
            unlabelledOrigins.map { it.originId },
        )
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
