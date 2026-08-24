package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class WorktreeForkDiscoveryTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests =
            dev.sebastiano.indexino.api.RuntimeAttachMode.IN_PROCESS
    }

    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `linked worktrees register and share git common dir`() {
        val cacheDirectory = createTempDirectory("indexino-fork-discovery-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val previous = System.getProperty("indexino.cache.dir")
        val cacheRoot = cacheDirectory.toRealPath()
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            assertEquals(cacheRoot, InProcessCacheLayout.cacheRoot())
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking {
                    main.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
                }
            }
            val mainId = InProcessCacheLayout.workspaceId(mainWorkspace)
            val published =
                checkNotNull(WorkspaceGenerationManifestStore(cacheRoot, mainId).current())
            assertNotNull(published.compatibilityManifest)
            assertEquals(1, WorkspaceRegistryStore(cacheRoot).entries().size)
            assertEquals(
                GitWorktreeLayout.commonDir(mainWorkspace),
                GitWorktreeLayout.commonDir(forkWorkspace),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                val result = runBlocking {
                    fork.refresh(RefreshRequest.forScope(IndexScope.gradle(":ui"))).await()
                }
                val forkManifest =
                    checkNotNull(
                        WorkspaceGenerationManifestStore(
                                cacheRoot,
                                InProcessCacheLayout.workspaceId(forkWorkspace),
                            )
                            .current()
                    )
                assertEquals(mainId, forkManifest.baseWorkspaceId)
                assertTrue(result.changes.changedFileCount == 0)
            }
        } finally {
            if (previous == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previous)
        }
    }

    private fun createLinkedWorktrees(): Pair<java.nio.file.Path, java.nio.file.Path> {
        val mainWorkspace = createGitWorkspace()
        val forkWorkspace = createTempDirectory("indexino-worktree-fork-")
        tempDirs.add(forkWorkspace)
        runGit(
            mainWorkspace,
            "worktree",
            "add",
            "-b",
            "overlay-fork-${System.nanoTime()}",
            forkWorkspace.toString(),
        )
        return mainWorkspace to forkWorkspace
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-main-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val destination = workspace.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination)
                }
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
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
