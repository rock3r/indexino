package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.IndexChanges
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshOutcome
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RefreshResult
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(IndexinoInternalApi::class)
class AutoRefreshControllerTest {
    @Test
    fun `watcher overflow reconciles every registered scope`() {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-overflow-watch-")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        Files.createDirectories(sourceRoot)
        val first = RefreshRequest.forScope(IndexScope.gradle(":first"))
        val second = RefreshRequest.forScope(IndexScope.gradle(":second"))
        val refreshed = ConcurrentHashMap.newKeySet<RefreshRequest>()
        val reconciled = CountDownLatch(2)
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { request -> if (refreshed.add(request)) reconciled.countDown() },
                reconciliationIntervalMillis = 1L,
            )
        try {
            controller.register(
                first,
                listOf(IndexedSource("workspace", workspace, "src/main/kotlin/First.kt")),
            )
            controller.register(
                second,
                listOf(IndexedSource("workspace", workspace, "src/main/kotlin/Second.kt")),
            )
            assertEquals(
                SnapshotFreshness.CURRENT,
                controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
            )

            controller.onWatcherOverflowForTests()

            assertEquals(
                SnapshotFreshness.UNKNOWN,
                controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
            )
            assertTrue(
                reconciled.await(5, TimeUnit.SECONDS),
                "Every registered scope must be reconciled",
            )
            assertEquals(setOf(first, second), refreshed)
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `edit during active automatic refresh schedules a successor`() {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-active-edit-")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Panel.kt")
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val firstStarted = CountDownLatch(1)
        val successorStarted = CountDownLatch(1)
        val firstResult = CompletableFuture<RefreshResult>()
        lateinit var controller: AutoRefreshController
        val refreshCount = AtomicInteger()
        controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { refreshed ->
                    assertEquals(request, refreshed)
                    if (refreshCount.incrementAndGet() == 1) {
                        val id = RefreshId.of("active")
                        controller.onRefreshStarted(
                            request,
                            RefreshHandle.inFlight(
                                id,
                                firstResult,
                                CompletableFuture(),
                                stopAction = {},
                            ),
                        )
                        firstStarted.countDown()
                    } else {
                        successorStarted.countDown()
                    }
                },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource("workspace", workspace, "src/main/kotlin/Panel.kt")),
            )
            controller.onPathChangedForTests(source)
            assertTrue(
                firstStarted.await(5, TimeUnit.SECONDS),
                "Initial automatic refresh did not start",
            )

            controller.onPathChangedForTests(source)
            assertEquals(
                SnapshotFreshness.DIRTY,
                controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
            )
            firstResult.complete(successfulResult(request, RefreshId.of("active")))

            assertTrue(
                successorStarted.await(5, TimeUnit.SECONDS),
                "Newer edit did not start a successor",
            )
            assertEquals(2, refreshCount.get())
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `external origin changes reach the refresh queue`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-external-watch-")
        val workspace = root.resolve("workspace")
        val externalRoot = root.resolve("external")
        val sourceRoot = externalRoot.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Convention.kt")
        Files.createDirectories(workspace)
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Convention")
        val refreshes = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { refreshes.incrementAndGet() },
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            controller.register(
                request,
                listOf(IndexedSource("external", externalRoot, "src/main/kotlin/Convention.kt")),
            )

            assertTrue(sourceRoot in controller.directoriesForTests(request))
            controller.onPathChangedForTests(source)
            awaitRefresh(refreshes)
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `watches empty non-main source roots discovered from topology`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-empty-source-root-")
        val workspace = root.resolve("workspace")
        val sourceRoot = workspace.resolve("module/src/commonMain/kotlin")
        Files.createDirectories(sourceRoot)
        val controller = AutoRefreshController(workspace, AutoRefreshMode.ENABLED, refresh = {})
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":module"))
            controller.register(request, sources = emptyList(), topologyRoots = listOf(workspace))

            assertTrue(sourceRoot in controller.directoriesForTests(request))
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `watches compose resources roots discovered from topology`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-compose-resource-watch-")
        val workspace = root.resolve("workspace")
        val sourceRoot = workspace.resolve("module/src/commonMain/composeResources")
        Files.createDirectories(sourceRoot)
        val controller = AutoRefreshController(workspace, AutoRefreshMode.ENABLED, refresh = {})
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":module"))
            controller.register(request, sources = emptyList(), topologyRoots = listOf(workspace))

            assertTrue(sourceRoot in controller.directoriesForTests(request))
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `refreshed closure stops watching removed external roots`() {
        val root = Files.createTempDirectory(Path.of("/tmp"), "indexino-watch-replace-")
        val workspace = root.resolve("workspace")
        val firstRoot = root.resolve("first")
        val secondRoot = root.resolve("second")
        val firstSource = firstRoot.resolve("src/main/kotlin/First.kt")
        val secondSource = secondRoot.resolve("src/main/kotlin/Second.kt")
        Files.createDirectories(workspace)
        Files.createDirectories(firstSource.parent)
        Files.createDirectories(secondSource.parent)
        Files.writeString(firstSource, "class First")
        Files.writeString(secondSource, "class Second")
        val refreshes = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { refreshes.incrementAndGet() },
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            controller.register(
                request,
                listOf(IndexedSource("first", firstRoot, "src/main/kotlin/First.kt")),
            )
            controller.register(
                request,
                listOf(IndexedSource("second", secondRoot, "src/main/kotlin/Second.kt")),
            )

            assertFalse(firstSource.parent in controller.directoriesForTests(request))
            assertTrue(secondSource.parent in controller.directoriesForTests(request))
            controller.onPathChangedForTests(firstSource)
            Thread.sleep(DEBOUNCE_SETTLE_MILLIS)
            assertEquals(0, refreshes.get())
            controller.onPathChangedForTests(secondSource)
            awaitRefresh(refreshes)
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitRefresh(refreshes: AtomicInteger) {
        repeat(100) {
            if (refreshes.get() > 0) return
            Thread.sleep(20L)
        }
        assertTrue(refreshes.get() > 0, "No refresh was enqueued")
    }

    private fun successfulResult(request: RefreshRequest, id: RefreshId): RefreshResult =
        RefreshResult(
            id,
            RefreshOutcome.UPDATED,
            WorkspaceGenerationId.of("generation"),
            WorkspaceRevision(
                "revision",
                listOf(SourceOriginRevision(SourceOriginId.of("workspace"), null, "state", null)),
            ),
            request.scope,
            IndexChanges(1, 0, 0),
        )

    private companion object {
        const val DEBOUNCE_SETTLE_MILLIS = 250L
    }
}
