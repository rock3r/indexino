package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
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
class WorktreeOverlayIntegrationTest {
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
    fun `compatible sibling worktree with no changes runs zero analyzers and bounded metadata`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        withCache(cacheDirectory) {
            indexMainAndFork(mainWorkspace, forkWorkspace, request)

            val mainWorkspaceId = InProcessCacheLayout.workspaceId(mainWorkspace)
            val forkWorkspaceId = InProcessCacheLayout.workspaceId(forkWorkspace)
            assertFalse(mainWorkspaceId == forkWorkspaceId)

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            forkWorkspaceId,
                        )
                        .current()
                )
            assertEquals(mainWorkspaceId, forkManifest.baseWorkspaceId)
            assertTrue(forkManifest.overlayPackKeys.isEmpty())

            val cacheRoot = canonicalCacheRoot(cacheDirectory)
            val forkBytes = workspaceTreeBytes(cacheRoot, forkWorkspaceId)
            val mainBytes = workspaceTreeBytes(cacheRoot, mainWorkspaceId)
            assertTrue(forkBytes <= WorktreeOverlayPolicy.METADATA_BUDGET_BYTES)
            assertTrue(forkBytes < mainBytes / 4)
        }
    }

    @Test
    fun `small fork delta stores and analyzes only changed files plus closure`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-delta-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }

            Files.writeString(
                forkSource,
                Files.readString(forkSource).replace("Panel", "ForkedPanel"),
            )

            val fork = Indexino.connectBlocking(forkWorkspace)
            try {
                val result = runBlocking { fork.refresh(request).await() }
                assertTrue(result.changes.changedFileCount >= 1)
                assertTrue(result.changes.changedFileCount <= 5)
            } finally {
                fork.close()
            }

            val forkManifest =
                checkNotNull(
                    WorkspaceGenerationManifestStore(
                            canonicalCacheRoot(cacheDirectory),
                            InProcessCacheLayout.workspaceId(forkWorkspace),
                        )
                        .current()
                )
            assertTrue(forkManifest.overlayPackKeys.isNotEmpty())
            assertTrue(!forkManifest.baseGeneration.isNullOrBlank())
        }
    }

    @Test
    fun `base plus overlay queries match a clean materialized rebuild`() {
        val cacheDirectory = createTempDirectory("indexino-overlay-equivalence-cache-")
        tempDirs.add(cacheDirectory)
        val (mainWorkspace, forkWorkspace) = createLinkedWorktrees()
        val rebuildWorkspace = createGitWorkspaceCopy(mainWorkspace)
        val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
        val forkSource = forkWorkspace.resolve("ui/src/main/kotlin/Panel.kt")
        Files.writeString(
            forkSource,
            Files.readString(forkSource).replace("ActionButton", "ForkActionButton"),
        )
        Files.writeString(
            rebuildWorkspace.resolve("ui/src/main/kotlin/Panel.kt"),
            Files.readString(forkSource),
        )

        lateinit var overlaySymbols: List<String>
        lateinit var rebuiltSymbols: List<String>
        withCache(cacheDirectory) {
            Indexino.connectBlocking(mainWorkspace).use { main ->
                runBlocking { main.refresh(request).await() }
            }
            Indexino.connectBlocking(forkWorkspace).use { fork ->
                runBlocking { fork.refresh(request).await() }
                overlaySymbols = querySymbolNames(fork)
            }
        }

        val rebuildCache = createTempDirectory("indexino-overlay-rebuild-cache-")
        tempDirs.add(rebuildCache)
        withCache(rebuildCache) {
            Indexino.connectBlocking(rebuildWorkspace).use { rebuilt ->
                runBlocking { rebuilt.refresh(request).await() }
                rebuiltSymbols = querySymbolNames(rebuilt)
            }
        }

        assertEquals(rebuiltSymbols.sorted(), overlaySymbols.sorted())
    }

    private fun indexMainAndFork(
        mainWorkspace: java.nio.file.Path,
        forkWorkspace: java.nio.file.Path,
        request: RefreshRequest,
    ) {
        Indexino.connectBlocking(mainWorkspace).use { main ->
            runBlocking { main.refresh(request).await() }
        }
        Indexino.connectBlocking(forkWorkspace).use { fork ->
            val result = runBlocking { fork.refresh(request).await() }
            assertEquals(0, result.changes.changedFileCount)
        }
    }

    private fun querySymbolNames(indexino: Indexino): List<String> = runBlocking {
        indexino.snapshot().use { snapshot ->
            listOf("Panel", "ActionButton", "ForkActionButton", "SelectionContainer").flatMap { name
                ->
                snapshot
                    .findSymbols(SymbolQuery.named(name), QueryOptions.page(limit = 10))
                    .items
                    .map { it.name }
            }
        }
    }

    private fun workspaceTreeBytes(cacheRoot: java.nio.file.Path, workspaceId: String): Long {
        val root = cacheRoot.resolve("workspaces").resolve(workspaceId)
        if (!Files.isDirectory(root)) return 0
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong { Files.size(it) }.sum()
        }
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

    private fun createGitWorkspaceCopy(source: java.nio.file.Path): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-overlay-rebuild-")
        tempDirs.add(workspace)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                if (relative.startsWith(".git")) return@forEach
                val destination = workspace.resolve(relative)
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
        runGit(workspace, "commit", "-m", "rebuild fixture")
        return workspace
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
