package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import dev.sebastiano.indexino.engine.WorkspaceRuntime
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.SAME_THREAD)
class WorktreeOverlayRuntimeTest {
    init {
        Indexino.defaultRuntimeAttachModeForTests = RuntimeAttachMode.IN_PROCESS
    }

    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `deleted fork worktree with overlay index shuts down runtime and preserves main base`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-runtime-loss-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toRealPath().toString())
        val runtime = WorkspaceRuntime.start(forkWorkspace, cacheDirectory.toRealPath())
        val forkWorkspaceId = InProcessCacheLayout.workspaceId(forkWorkspace.toRealPath())
        val tombstonePath = RuntimePaths.tombstonePath(cacheDirectory.toRealPath(), forkWorkspaceId)
        try {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Files.writeString(
                forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
                Files.readString(forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"))
                    .replace("ActionButton", "RuntimeLossForkButton"),
            )
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(cacheDirectory.toRealPath(), forkWorkspaceId)
                        .current()
                )
            assertEquals(WorktreeOverlayPolicy.REPRESENTATION_OVERLAY, forkManifest.representation)

            assertTrue(forkWorkspace.toFile().deleteRecursively())
            waitUntil {
                RuntimeTombstoneStore.read(tombstonePath) != null && !Files.exists(runtime.endpoint)
            }

            assertEquals("WORKSPACE_LOST", RuntimeTombstoneStore.read(tombstonePath)?.code)
            assertFalse(Files.exists(runtime.endpoint))

            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking {
                    main.snapshot().use { snapshot ->
                        assertTrue(
                            snapshot
                                .findSymbols(
                                    SymbolQuery.named("Panel"),
                                    QueryOptions.page(limit = 1),
                                )
                                .items
                                .isNotEmpty()
                        )
                    }
                }
            }

            val mainManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            cacheDirectory.toRealPath(),
                            InProcessCacheLayout.workspaceId(mainWorkspace),
                        )
                        .current()
                )
            assertTrue(mainManifest.packKeys.isNotEmpty())
        } finally {
            runtime.close()
            if (previousCacheDirectory == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheDirectory)
        }
    }

    @Test
    fun `nested overlay fork increments chain depth until max depth fallback`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-chain-cache-")
        tempDirs.add(cacheDirectory)
        val mainWorkspace = createGitWorkspace()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }

            var baseWorkspace = mainWorkspace
            var lastManifest: WorkspaceGenerationManifest? = null
            repeat(WorktreeOverlayPolicy.MAX_CHAIN_DEPTH - 1) { level ->
                val forkWorkspace = createLinkedFork(baseWorkspace)
                Files.writeString(
                    forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
                    Files.readString(forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt"))
                        .replace("ActionButton", "ChainButton$level"),
                )
                Indexino.connectBlocking(forkWorkspace).use { fork ->
                    runBlocking { fork.refresh(request).await() }
                }
                lastManifest =
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                assertEquals(
                    WorktreeOverlayPolicy.REPRESENTATION_OVERLAY,
                    lastManifest?.representation,
                )
                assertEquals(level + 1, lastManifest?.overlayChainDepth)
                baseWorkspace = forkWorkspace
            }

            val depthEightFork = createLinkedFork(baseWorkspace)
            Files.writeString(
                depthEightFork.resolve("ui/src/main/kotlin/Panel.kt"),
                Files.readString(depthEightFork.resolve("ui/src/main/kotlin/Panel.kt"))
                    .replace("ActionButton", "ChainDepthEightButton"),
            )
            Indexino.connectBlocking(depthEightFork).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }
            val depthEightManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(depthEightFork),
                        )
                        .current()
                )
            assertEquals(
                WorktreeOverlayPolicy.REPRESENTATION_OVERLAY,
                depthEightManifest.representation,
            )
            assertEquals(
                WorktreeOverlayPolicy.MAX_CHAIN_DEPTH,
                depthEightManifest.overlayChainDepth,
            )

            val overflowFork = createLinkedFork(depthEightFork)
            Files.writeString(
                overflowFork.resolve("ui/src/main/kotlin/Panel.kt"),
                Files.readString(overflowFork.resolve("ui/src/main/kotlin/Panel.kt"))
                    .replace("ActionButton", "ChainOverflowButton"),
            )
            Indexino.connectBlocking(overflowFork).use { fork ->
                runBlocking { fork.refresh(request).await() }
            }
            val overflowManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(overflowFork),
                        )
                        .current()
                )
            assertEquals(
                WorktreeOverlayPolicy.REPRESENTATION_MATERIALIZED,
                overflowManifest.representation,
            )
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(WORKSPACE_LOSS_WAIT_ATTEMPTS) {
            if (condition()) return
            Thread.sleep(WORKSPACE_LOSS_WAIT_MILLIS)
        }
        assertTrue(condition(), "Timed out waiting for workspace-loss shutdown")
    }

    private fun createLinkedFork(baseWorkspace: java.nio.file.Path): java.nio.file.Path {
        val forkWorkspace = createTempDirectory("indexino-overlay-chain-fork-")
        tempDirs.add(forkWorkspace)
        runGit(
            baseWorkspace,
            "worktree",
            "add",
            "-b",
            "overlay-chain-${System.nanoTime()}",
            forkWorkspace.toString(),
        )
        return forkWorkspace
    }

    private fun createLinkedWorktrees(): Pair<java.nio.file.Path, java.nio.file.Path> {
        val mainWorkspace = createGitWorkspace()
        return mainWorkspace to createLinkedFork(mainWorkspace)
    }

    private fun canonicalCacheRoot(cacheDirectory: java.nio.file.Path): java.nio.file.Path =
        cacheDirectory.toRealPath()

    private fun withCache(cacheDirectory: java.nio.file.Path, block: () -> Unit) {
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", canonicalCacheRoot(cacheDirectory).toString())
        try {
            block()
        } finally {
            if (previousCacheDirectory == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheDirectory)
        }
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-runtime-main-")
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

    private companion object {
        const val WORKSPACE_LOSS_WAIT_ATTEMPTS = 50
        const val WORKSPACE_LOSS_WAIT_MILLIS = 20L
    }
}
