package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InProcessIndexinoTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `manual refresh exposes paged symbols and references through a stable snapshot`() {
        val workspace = createGitWorkspace()
        val cacheDirectory = createTempDirectory("indexino-facade-cache-")
        tempDirs.add(cacheDirectory)
        val previousCacheDirectory = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheDirectory.toString())
        val indexino = Indexino.connectBlocking(workspace)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":ui"))
            val handle = runSuspend { indexino.refresh(request) }
            val result = runSuspend { handle.await() }

            assertEquals(handle.id, result.refreshId)
            assertEquals(RefreshOutcome.UPDATED, result.outcome)
            assertTrue(result.changes.changedFileCount > 0)

            val snapshot = runSuspend { indexino.snapshot() }
            try {
                assertSnapshotQueries(snapshot, result, workspace)
            } finally {
                snapshot.close()
            }

            val unknownPluginFailure =
                assertFailsWith<IndexinoException> {
                    runSuspend {
                        indexino.refresh(request.withPlugin(PluginId.of("dev.example.unknown")))
                    }
                }
            assertEquals("INVALID_REQUEST", unknownPluginFailure.failure.category.value)
        } finally {
            indexino.close()
            if (previousCacheDirectory == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previousCacheDirectory)
            }
        }
    }

    @Test
    fun `connecting to a missing workspace reports invalid request`() {
        val missingWorkspace = createTempDirectory("missing-indexino-workspace-")
        missingWorkspace.toFile().deleteRecursively()

        val failure =
            assertFailsWith<IndexinoException> { Indexino.connectBlocking(missingWorkspace) }

        assertEquals("INVALID_REQUEST", failure.failure.category.value)
    }

    private fun assertSnapshotQueries(
        snapshot: IndexSnapshot,
        result: RefreshResult,
        workspace: java.nio.file.Path,
    ) {
        assertEquals(result.generation, snapshot.generation)
        assertEquals(result.revision, snapshot.revision)

        val sourceFile =
            SourceFile.of(
                SourceOriginId.of("workspace"),
                "ui/src/main/kotlin/Panel.kt",
                "ui/src/main/kotlin/Panel.kt",
            )
        val firstSymbols = runSuspend {
            snapshot.findSymbols(SymbolQuery.inFile(sourceFile), QueryOptions.page(limit = 2))
        }
        assertEquals(2, firstSymbols.items.size)
        assertTrue(firstSymbols.hasMore)
        val cursor = requireNotNull(firstSymbols.nextCursor)
        assertTrue(cursor.startsWith("indexino:v1:"))

        val remainingSymbols = runSuspend {
            snapshot.findSymbols(
                SymbolQuery.inFile(sourceFile),
                QueryOptions.after(limit = 10, cursor = cursor),
            )
        }
        assertFalse(remainingSymbols.items.isEmpty())

        val actionButton =
            runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.named("ActionButton"),
                        QueryOptions.page(limit = 1),
                    )
                }
                .items
                .single()
        val references = runSuspend {
            snapshot.findReferences(
                ReferenceQuery.to(actionButton.id),
                QueryOptions.page(limit = 1),
            )
        }
        assertEquals(1, references.items.size)
        val invalidCursorFailure =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.after(limit = 1, cursor = "not-a-cursor"),
                    )
                }
            }
        assertEquals("INVALID_REQUEST", invalidCursorFailure.failure.category.value)
        val otherOriginFile =
            SourceFile.of(SourceOriginId.of("other"), sourceFile.path, sourceFile.displayPath)
        val otherOriginSymbols = runSuspend {
            snapshot.findSymbols(SymbolQuery.inFile(otherOriginFile), QueryOptions.page(limit = 10))
        }
        assertTrue(otherOriginSymbols.items.isEmpty())

        snapshot.close()
        val closedFailure =
            assertFailsWith<IndexinoException> {
                runSuspend {
                    snapshot.findSymbols(
                        SymbolQuery.inFile(sourceFile),
                        QueryOptions.page(limit = 1),
                    )
                }
            }
        assertEquals("CLOSED", closedFailure.failure.category.value)
        assertFalse(Files.exists(workspace.resolve(".indexino")))
    }

    private fun createGitWorkspace(): java.nio.file.Path {
        val workspace = createTempDirectory("indexino-facade-")
        tempDirs.add(workspace)
        val fixtureRoot = Path("src/test/resources/gradle-fixtures/multi-module")
        Files.walk(fixtureRoot).use { paths ->
            paths.forEach { path ->
                val destination = workspace.resolve(fixtureRoot.relativize(path))
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
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

    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
