package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.engine.IndexingCoordinator
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.topology.bazel.BazelClientCleanupException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RefreshStopCleanupTest {
    @Test
    fun `stop after publication cannot cancel the committed result`() = runBlocking {
        val root = Files.createTempDirectory("indexino-committed-stop-")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"stop\"")
        val source = workspace.resolve("src/main/kotlin/Marker.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Marker")
        val previousCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        try {
            Indexino.connect(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                        .withAutoRefresh(AutoRefreshMode.DISABLED)
                )
                .use { index ->
                    index.onRefreshSucceededForRuntime = { _, _, _ ->
                        IndexingCoordinator.active(workspace).single().second.stop()
                    }
                    val handle =
                        index.refresh(
                            RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
                        )
                    val completion = runCatching { withTimeout(5_000) { handle.await() } }
                    assertTrue(
                        completion.isSuccess,
                        "Committed refresh was reported as ${completion.exceptionOrNull()}",
                    )
                    val result = completion.getOrThrow()
                    assertIs<RefreshCompleted>(handle.events().toList().last())
                    index.snapshot().use { snapshot ->
                        assertEquals(result.generation, snapshot.generation)
                        assertEquals(
                            listOf("Marker"),
                            snapshot
                                .findSymbols(SymbolQuery.named("Marker"), QueryOptions.page(10))
                                .items
                                .map { it.name },
                        )
                    }
                }
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stopped facade refresh retains its active handle until interruption cleanup finishes`() {
        exerciseStop(cleanupFails = false)
    }

    @Test
    fun `stopped facade refresh preserves typed client cleanup failure`() {
        exerciseStop(cleanupFails = true)
    }

    private fun exerciseStop(cleanupFails: Boolean) = runBlocking {
        val root = Files.createTempDirectory("indexino-facade-stop-")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"stop\"\n")
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        try {
            Indexino.connect(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                        .withAutoRefresh(AutoRefreshMode.DISABLED)
                )
                .use { index ->
                    val handle =
                        index.refresh(
                            request,
                            progress = {
                                entered.countDown()
                                try {
                                    CountDownLatch(1).await()
                                } catch (interrupted: InterruptedException) {
                                    cleanupEntered.countDown()
                                    check(finishCleanup.await(5, TimeUnit.SECONDS))
                                    if (cleanupFails) throw BazelClientCleanupException(interrupted)
                                    throw interrupted
                                }
                            },
                            machineProgress = null,
                        )
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        handle.stop()
                        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                        assertEquals(handle.id, index.activeRefreshes().single().id)
                        assertEquals(handle.id, index.refresh(request).id)
                    } finally {
                        finishCleanup.countDown()
                    }
                    withTimeout(5_000) {
                        if (cleanupFails) {
                            val failure = assertFailsWith<IndexinoException> { handle.await() }
                            val cause = assertIs<BazelClientCleanupException>(failure.cause)
                            assertIs<InterruptedException>(cause.cause)
                            assertIs<RefreshFailed>(handle.events().toList().last())
                        } else {
                            assertFailsWith<CancellationException> { handle.await() }
                            assertIs<RefreshStopped>(handle.events().toList().last())
                        }
                    }
                    assertTrue(index.activeRefreshes().isEmpty())
                }
        } finally {
            finishCleanup.countDown()
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
            root.toFile().deleteRecursively()
        }
    }
}
