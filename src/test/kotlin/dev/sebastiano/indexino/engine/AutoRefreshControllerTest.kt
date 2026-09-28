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
import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `explicit refresh coalescing with pending automatic launch retains the edit`() {
        val workspace = Files.createTempDirectory("indexino-coalesced-refresh-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val explicitResult = CompletableFuture<RefreshResult>()
        val autoDispatched = CountDownLatch(1)
        val successor = CountDownLatch(1)
        lateinit var controller: AutoRefreshController
        controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    if (autoDispatched.count == 1L) {
                        autoDispatched.countDown()
                        val id = RefreshId.of("explicit")
                        controller.onRefreshStarted(
                            request,
                            RefreshHandle.inFlight(
                                id,
                                explicitResult,
                                CompletableFuture(),
                                stopAction = {},
                            ),
                            automatic = false,
                        )
                        controller.onRefreshStarted(
                            request,
                            RefreshHandle.inFlight(
                                id,
                                explicitResult,
                                CompletableFuture(),
                                stopAction = {},
                            ),
                            automatic = true,
                        )
                    } else successor.countDown()
                },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            controller.onPathChangedForTests(source)
            assertTrue(autoDispatched.await(5, TimeUnit.SECONDS))
            explicitResult.complete(successfulResult(request, RefreshId.of("explicit")))
            assertTrue(
                successor.await(5, TimeUnit.SECONDS),
                "The coalesced worker may predate the edit",
            )
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `source event between watch arming and handle registration retains successor`() {
        val workspace = Files.createTempDirectory("indexino-arming-gap-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val successor = CountDownLatch(1)
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { successor.countDown() },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            controller.onPathChangedForTests(source)
            val id = RefreshId.of("arming-gap")
            controller.onRefreshStarted(
                request,
                RefreshHandle.inFlight(
                    id,
                    CompletableFuture.completedFuture(successfulResult(request, id)),
                    CompletableFuture(),
                    stopAction = {},
                ),
            )
            assertTrue(
                successor.await(5, TimeUnit.SECONDS),
                "An edit after arming must be reconciled",
            )
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful explicit refresh drops hints only when no newer edit exists`() {
        for (newerEdit in listOf(false, true)) {
            val workspace = Files.createTempDirectory("indexino-explicit-hints-")
            val directory = workspace.resolve("src/main/kotlin")
            Files.createDirectories(directory)
            val first = directory.resolve("First.kt")
            val second = directory.resolve("Second.kt")
            val last = directory.resolve("Last.kt")
            listOf(first, second, last).forEach { Files.writeString(it, "class ${it.fileName}") }
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val sources =
                listOf("First.kt", "Second.kt", "Last.kt").map {
                    IndexedSource.workspace(workspace, "src/main/kotlin/$it")
                }
            val topology =
                TopologyResult(
                    sources.map { "src/main/kotlin/${it.path.substringAfterLast('/')}" },
                    topology = "gradle",
                    includeDeps = false,
                    scope = ":app",
                )
            val hints = CopyOnWriteArrayList<Set<Path>>()
            val dispatched = CountDownLatch(1)
            val result = CompletableFuture<RefreshResult>()
            val controller =
                AutoRefreshController(
                    workspace,
                    AutoRefreshMode.ENABLED,
                    refresh = { dispatched.countDown() },
                    topologyProvider = { topology },
                    refreshWithTopology = { _, _, paths, _, _ ->
                        hints += paths
                        dispatched.countDown()
                    },
                    nativeWorkspaceWatcher = { _, _, _ -> AutoCloseable {} },
                )
            try {
                controller.register(request, sources)
                controller.onPathChangedForTests(first)
                controller.onPathChangedForTests(second)
                // Full source capture starts after registration and covers these two events.
                controller.register(request, sources)
                val id = RefreshId.of("explicit-covered")
                controller.onRefreshStarted(
                    request,
                    RefreshHandle.inFlight(id, result, CompletableFuture(), stopAction = {}),
                    automatic = false,
                )
                if (newerEdit) controller.onPathChangedForTests(last)
                result.complete(successfulResult(request, id))
                if (!newerEdit) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (
                        controller.freshness(FreshnessPolicy.AWAIT_CURRENT) !=
                            SnapshotFreshness.CURRENT && System.nanoTime() < deadline
                    ) Thread.sleep(10)
                    assertEquals(
                        SnapshotFreshness.CURRENT,
                        controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
                    )
                    controller.onPathChangedForTests(last)
                }
                assertTrue(dispatched.await(5, TimeUnit.SECONDS))
                assertEquals(1, hints.size)
                if (newerEdit) assertTrue(last in hints.single())
                else assertEquals(setOf(last), hints.single())
            } finally {
                controller.close()
                workspace.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `full capture after handle registration covers earlier hints but not later edits`() {
        for (newerPath in listOf(null, "Last.kt", "First.kt", "BUILD.bazel")) {
            val workspace = Files.createTempDirectory("indexino-active-capture-")
            val directory = workspace.resolve("src/main/kotlin")
            Files.createDirectories(directory)
            val first = directory.resolve("First.kt")
            val second = directory.resolve("Second.kt")
            val last = directory.resolve("Last.kt")
            listOf(first, second, last).forEach { Files.writeString(it, "class Panel") }
            val buildFile = workspace.resolve("BUILD.bazel")
            Files.writeString(buildFile, "")
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val sources =
                listOf("First.kt", "Second.kt", "Last.kt").map {
                    IndexedSource.workspace(workspace, "src/main/kotlin/$it")
                }
            val topology =
                TopologyResult(
                    sources.map { it.path },
                    topology = "gradle",
                    includeDeps = false,
                    scope = ":app",
                )
            val hinted = CopyOnWriteArrayList<Set<Path>>()
            val successor = CountDownLatch(1)
            val full = AtomicInteger()
            val result = CompletableFuture<RefreshResult>()
            val controller =
                AutoRefreshController(
                    workspace,
                    AutoRefreshMode.ENABLED,
                    refresh = {
                        full.incrementAndGet()
                        successor.countDown()
                    },
                    topologyProvider = { topology },
                    refreshWithTopology = { _, _, paths, _, _ ->
                        hinted += paths
                        successor.countDown()
                    },
                    nativeWorkspaceWatcher = { _, _, _ -> AutoCloseable {} },
                )
            try {
                controller.register(request, sources)
                val id = RefreshId.of("active-capture")
                controller.onRefreshStarted(
                    request,
                    RefreshHandle.inFlight(id, result, CompletableFuture(), stopAction = {}),
                    automatic = false,
                )
                controller.onPathChangedForTests(first)
                controller.onPathChangedForTests(second)
                // This callback precedes the actual full source capture in IndexBuildRunner.
                controller.register(request, sources)
                if (newerPath != null)
                    controller.onPathChangedForTests(
                        if (newerPath == "BUILD.bazel") buildFile else directory.resolve(newerPath)
                    )
                result.complete(successfulResult(request, id))
                if (newerPath == null) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (
                        controller.freshness(FreshnessPolicy.AWAIT_CURRENT) !=
                            SnapshotFreshness.CURRENT && System.nanoTime() < deadline
                    ) Thread.sleep(10)
                    assertEquals(
                        SnapshotFreshness.CURRENT,
                        controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
                    )
                    controller.onPathChangedForTests(last)
                }
                assertTrue(successor.await(5, TimeUnit.SECONDS))
                if (newerPath == "BUILD.bazel") {
                    assertEquals(1, full.get())
                    assertTrue(hinted.isEmpty())
                } else {
                    assertEquals(0, full.get())
                    assertEquals(listOf(setOf(directory.resolve(newerPath ?: "Last.kt"))), hinted)
                }
            } finally {
                controller.close()
                workspace.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `one nested FSEvents path produces one epoch even with several ancestor watches`() {
        val workspace = Files.createTempDirectory("indexino-nested-event-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val controller = AutoRefreshController(workspace, AutoRefreshMode.ENABLED, refresh = {})
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
                topologyRoots = listOf(workspace),
            )
            assertTrue(controller.directoriesForTests(request).size > 1)
            controller.onPathChangedForTests(source)
            assertEquals(1L, controller.dirtyEpochForTests(request))
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `git bookkeeping inside a watched workspace does not dirty the scope`() {
        val workspace = Files.createTempDirectory("indexino-git-bookkeeping-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        Files.createDirectories(workspace.resolve(".git"))
        Files.createDirectories(workspace.resolve("nested/.git"))
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val controller = AutoRefreshController(workspace, AutoRefreshMode.ENABLED, refresh = {})
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
                topologyRoots = listOf(workspace),
            )
            // Origin resolution runs git status/diff, which may rewrite these files.
            controller.onPathChangedForTests(workspace.resolve(".git/index.lock"))
            controller.onPathChangedForTests(workspace.resolve(".git/index"))
            controller.onPathChangedForTests(workspace.resolve("nested/.git/index"))
            assertEquals(0L, controller.dirtyEpochForTests(request))

            // A repository appearing or disappearing changes origin boundaries.
            controller.onPathChangedForTests(workspace.resolve("nested/.git"))
            assertEquals(1L, controller.dirtyEpochForTests(request))
            controller.onPathChangedForTests(source)
            assertEquals(2L, controller.dirtyEpochForTests(request))
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native workspace watcher keeps polling registrations to external roots`() {
        val root = Files.createTempDirectory("indexino-native-coverage-")
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val source = workspace.resolve("app/src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val external = Files.createDirectories(root.resolve("external/src/main/kotlin"))
        Files.writeString(external.resolve("Convention.kt"), "class Convention")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        var nativeRoot: Path? = null
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {},
                nativeWorkspaceWatcher = { watchedRoot, _, _ ->
                    nativeRoot = watchedRoot
                    AutoCloseable {}
                },
            )
        try {
            controller.register(
                request,
                listOf(
                    IndexedSource.workspace(workspace, "app/src/main/kotlin/Panel.kt"),
                    IndexedSource(
                        "external:logic",
                        root.resolve("external"),
                        "src/main/kotlin/Convention.kt",
                    ),
                ),
                topologyRoots = listOf(workspace, root.resolve("external")),
            )
            assertEquals(workspace, nativeRoot)
            assertTrue(controller.directoriesForTests(request).any { it.startsWith(workspace) })
            val polled = controller.polledDirectoriesForTests()
            assertTrue(polled.isNotEmpty(), "External roots still need polling coverage")
            assertTrue(
                polled.none { it.startsWith(workspace) },
                "The native stream covers the workspace; polling it rescans every directory: $polled",
            )
            assertEquals(
                SnapshotFreshness.CURRENT,
                controller.freshness(FreshnessPolicy.AWAIT_CURRENT),
            )
        } finally {
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `watcher overflow after claiming a hint forces full reconciliation`() {
        val workspace = Files.createTempDirectory("indexino-pending-overflow-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val full = CountDownLatch(1)
        val hinted = AtomicInteger()
        lateinit var controller: AutoRefreshController
        controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { full.countDown() },
                topologyProvider = {
                    controller.onWatcherOverflowForTests()
                    TopologyResult(
                        listOf("src/main/kotlin/Panel.kt"),
                        topology = "gradle",
                        includeDeps = false,
                        scope = ":app",
                    )
                },
                refreshWithTopology = { _, _, _, _, _ -> hinted.incrementAndGet() },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            controller.onPathChangedForTests(source)
            assertTrue(full.await(5, TimeUnit.SECONDS))
            assertEquals(0, hinted.get())
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `only a native recursive stream vouches for the whole workspace tree`() {
        val workspace = Files.createTempDirectory("indexino-recursive-root-").toRealPath()
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.bazel("//:main"))
        val topology =
            TopologyResult(
                listOf("src/main/kotlin/Panel.kt"),
                topology = "bazel-query",
                includeDeps = false,
                scope = "//:main",
            )
        try {
            for (native in listOf(true, false)) {
                val roots = CopyOnWriteArrayList<Path?>()
                val launched = CountDownLatch(1)
                val controller =
                    AutoRefreshController(
                        workspace,
                        AutoRefreshMode.ENABLED,
                        refresh = {},
                        topologyProvider = { topology },
                        refreshWithTopology = { _, _, _, _, recursiveRoot ->
                            roots += recursiveRoot
                            launched.countDown()
                        },
                        nativeWorkspaceWatcher =
                            if (native) { _, _, _ -> AutoCloseable {} } else null,
                    )
                try {
                    controller.register(
                        request,
                        listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
                    )
                    controller.onPathChangedForTests(source)
                    assertTrue(launched.await(5, TimeUnit.SECONDS))
                    // Per-directory WatchService registrations miss unwatched directories.
                    assertEquals(listOf(if (native) workspace else null), roots.toList())
                } finally {
                    controller.close()
                }
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `known source edit reuses resolved topology while build input change re-resolves it`() {
        val workspace = Files.createTempDirectory("indexino-topology-hint-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val buildFile = workspace.resolve("BUILD.bazel")
        Files.writeString(buildFile, "")
        val request = RefreshRequest.forScope(IndexScope.bazel("//:main"))
        val topology =
            TopologyResult(
                listOf("src/main/kotlin/Panel.kt"),
                topology = "bazel-query",
                includeDeps = false,
                scope = "//:main",
            )
        val hints = CopyOnWriteArrayList<TopologyResult>()
        val hintedSources = CopyOnWriteArrayList<Set<Path>>()
        val firstCompleted = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val full = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    full.incrementAndGet()
                    firstCompleted.countDown()
                },
                topologyProvider = { topology },
                refreshWithTopology = { _, hint, paths, _, _ ->
                    hints += hint
                    hintedSources += paths
                    firstCompleted.countDown()
                },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            controller.onPathChangedForTests(source)
            assertTrue(firstCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(topology), hints)
            assertEquals(listOf(setOf(source)), hintedSources)
            // The first dispatch has not registered a handle; use an independent controller for
            // the second event so a pending launch cannot mask the topology invalidation.
        } finally {
            controller.close()
        }
        val second =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    full.incrementAndGet()
                    secondCompleted.countDown()
                },
                topologyProvider = { topology },
                refreshWithTopology = { _, hint, paths, _, _ ->
                    hints += hint
                    hintedSources += paths
                    secondCompleted.countDown()
                },
            )
        try {
            second.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            second.onPathChangedForTests(source)
            second.onPathChangedForTests(buildFile)
            assertTrue(secondCompleted.await(5, TimeUnit.SECONDS))
            assertEquals(1, full.get())
            assertEquals(listOf(topology), hints)
            assertEquals(listOf(setOf(source)), hintedSources)
        } finally {
            second.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `atomic replacement of a known source reuses topology without losing deletion safety`() {
        val workspace = Files.createTempDirectory("indexino-atomic-edit-")
        val outside = Files.createTempDirectory("indexino-atomic-edit-outside-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val replacement = outside.resolve("Panel.kt")
        Files.writeString(replacement, "class ChangedPanel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val topology =
            TopologyResult(
                listOf("src/main/kotlin/Panel.kt"),
                topology = "gradle",
                includeDeps = false,
                scope = ":app",
            )
        val reused = CountDownLatch(1)
        val full = CountDownLatch(1)
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { full.countDown() },
                topologyProvider = { topology },
                refreshWithTopology = { _, _, paths, _, _ ->
                    assertEquals(setOf(source), paths)
                    reused.countDown()
                },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            Files.move(source, outside.resolve("old-Panel.kt"))
            Files.move(replacement, source)
            assertTrue(
                reused.await(5, TimeUnit.SECONDS),
                "Atomic save should retain the known path",
            )
            assertEquals(0L, reused.count)
            assertEquals(1L, full.count)
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleted known source and newly created source both re-resolve topology`() {
        val workspace = Files.createTempDirectory("indexino-source-membership-")
        val source = workspace.resolve("src/main/kotlin/Panel.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val full = CountDownLatch(1)
        val hinted = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { full.countDown() },
                topologyProvider = {
                    TopologyResult(
                        listOf("src/main/kotlin/Panel.kt"),
                        topology = "gradle",
                        includeDeps = false,
                        scope = ":app",
                    )
                },
                refreshWithTopology = { _, _, _, _, _ -> hinted.incrementAndGet() },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            Files.delete(source)
            assertTrue(full.await(5, TimeUnit.SECONDS), "A missing source changes membership")
            assertEquals(0, hinted.get())
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }

        Files.createDirectories(source.parent)
        Files.writeString(source, "class Panel")
        val additionFull = CountDownLatch(1)
        val additionHinted = AtomicInteger()
        val addition =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = { additionFull.countDown() },
                topologyProvider = {
                    TopologyResult(
                        listOf("src/main/kotlin/Panel.kt"),
                        topology = "gradle",
                        includeDeps = false,
                        scope = ":app",
                    )
                },
                refreshWithTopology = { _, _, _, _, _ -> additionHinted.incrementAndGet() },
            )
        try {
            addition.register(
                request,
                listOf(IndexedSource.workspace(workspace, "src/main/kotlin/Panel.kt")),
            )
            Files.writeString(source.parent.resolve("Added.kt"), "class Added")
            assertTrue(additionFull.await(5, TimeUnit.SECONDS), "A new source changes membership")
            assertEquals(0, additionHinted.get())
        } finally {
            addition.close()
            workspace.toFile().deleteRecursively()
        }
    }

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
                reconciliationIntervalMillis = TimeUnit.MINUTES.toMillis(1),
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
                "Overflow must reconcile promptly, without waiting for the periodic timer",
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
    fun `known edits during active refresh keep successor hint but unknown edits reconcile fully`() {
        for (unknown in listOf(false, true)) {
            val workspace = Files.createTempDirectory("indexino-active-hints-")
            val first = workspace.resolve("src/main/kotlin/First.kt")
            val second = workspace.resolve("src/main/kotlin/Second.kt")
            Files.createDirectories(first.parent)
            Files.writeString(first, "class First")
            Files.writeString(second, "class Second")
            val buildFile = workspace.resolve("BUILD.bazel")
            Files.writeString(buildFile, "")
            val request = RefreshRequest.forScope(IndexScope.bazel("//:main"))
            val topology =
                TopologyResult(
                    listOf("src/main/kotlin/First.kt", "src/main/kotlin/Second.kt"),
                    topology = "bazel-query",
                    includeDeps = false,
                    scope = "//:main",
                )
            val firstStarted = CountDownLatch(1)
            val successorStarted = CountDownLatch(1)
            val firstResult = CompletableFuture<RefreshResult>()
            val hints = CopyOnWriteArrayList<Set<Path>>()
            val full = AtomicInteger()
            lateinit var controller: AutoRefreshController
            controller =
                AutoRefreshController(
                    workspace,
                    AutoRefreshMode.ENABLED,
                    refresh = {
                        full.incrementAndGet()
                        successorStarted.countDown()
                    },
                    topologyProvider = { topology },
                    refreshWithTopology = { _, _, paths, _, _ ->
                        hints += paths
                        if (hints.size == 1) {
                            val id = RefreshId.of("first")
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
                        } else successorStarted.countDown()
                    },
                    nativeWorkspaceWatcher = { _, _, _ -> AutoCloseable {} },
                )
            try {
                controller.register(
                    request,
                    listOf(
                        IndexedSource.workspace(workspace, "src/main/kotlin/First.kt"),
                        IndexedSource.workspace(workspace, "src/main/kotlin/Second.kt"),
                    ),
                )
                controller.onPathChangedForTests(first)
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
                controller.onPathChangedForTests(if (unknown) buildFile else second)
                firstResult.complete(successfulResult(request, RefreshId.of("first")))
                assertTrue(successorStarted.await(5, TimeUnit.SECONDS))
                if (unknown) {
                    assertEquals(1, full.get())
                    assertEquals(listOf(setOf(first)), hints)
                } else {
                    assertEquals(0, full.get())
                    assertEquals(listOf(setOf(first), setOf(second)), hints)
                }
            } finally {
                controller.close()
                workspace.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `edit after a worker captures input but before its handle is registered starts a successor`() {
        assertEditBeforeHandleRegistrationStartsSuccessor(promoteForAwaitCurrent = false)
    }

    @Test
    fun `await current promotion preserves an edit before handle registration`() {
        assertEditBeforeHandleRegistrationStartsSuccessor(promoteForAwaitCurrent = true)
    }

    private fun assertEditBeforeHandleRegistrationStartsSuccessor(promoteForAwaitCurrent: Boolean) {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-launch-edit-")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Panel.kt")
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val version = AtomicInteger(1)
        val observed = CopyOnWriteArrayList<Int>()
        val successorStarted = CountDownLatch(1)
        lateinit var controller: AutoRefreshController
        controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    observed.add(
                        version.get()
                    ) // The worker captured this input before registration.
                    if (observed.size == 1) {
                        version.set(2)
                        controller.onPathChangedForTests(source)
                        val id = RefreshId.of("launched")
                        controller.onRefreshStarted(
                            request,
                            RefreshHandle.inFlight(
                                id,
                                CompletableFuture.completedFuture(successfulResult(request, id)),
                                CompletableFuture(),
                                stopAction = {},
                            ),
                        )
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
            if (promoteForAwaitCurrent) controller.startQueuedForAwaitCurrent()
            assertTrue(
                successorStarted.await(5, TimeUnit.SECONDS),
                "An edit after the first capture needs a successor",
            )
            assertEquals(listOf(1, 2), observed.toList())
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `burst waits for quiet before starting its first refresh`() {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-burst-edit-")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Panel.kt")
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val observed = CopyOnWriteArrayList<Int>()
        val firstRefresh = CountDownLatch(1)
        val version = AtomicInteger()
        val controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    observed.add(version.get())
                    firstRefresh.countDown()
                },
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource("workspace", workspace, "src/main/kotlin/Panel.kt")),
            )
            repeat(12) {
                version.incrementAndGet()
                controller.onPathChangedForTests(source)
                Thread.sleep(70L)
            }
            assertTrue(firstRefresh.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(12), observed.toList(), "Burst must not start from its first edit")
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `continuous burst starts a refresh within the maximum delay and retains the final edit`() {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-continuous-edit-")
        val sourceRoot = workspace.resolve("src/main/kotlin")
        val source = sourceRoot.resolve("Panel.kt")
        Files.createDirectories(sourceRoot)
        Files.writeString(source, "class Panel")
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val observed = CopyOnWriteArrayList<Int>()
        val firstRefresh = CountDownLatch(1)
        val lastRefresh = CountDownLatch(1)
        val version = AtomicInteger()
        lateinit var controller: AutoRefreshController
        controller =
            AutoRefreshController(
                workspace,
                AutoRefreshMode.ENABLED,
                refresh = {
                    val current = version.get()
                    observed.add(current)
                    val id = RefreshId.of("continuous-$current")
                    controller.onRefreshStarted(
                        request,
                        RefreshHandle.inFlight(
                            id,
                            CompletableFuture.completedFuture(successfulResult(request, id)),
                            CompletableFuture(),
                            stopAction = {},
                        ),
                    )
                    firstRefresh.countDown()
                    if (current == 20) lastRefresh.countDown()
                },
                maxDebounceNanos = TimeUnit.MILLISECONDS.toNanos(350),
            )
        try {
            controller.register(
                request,
                listOf(IndexedSource("workspace", workspace, "src/main/kotlin/Panel.kt")),
            )
            repeat(20) {
                version.incrementAndGet()
                controller.onPathChangedForTests(source)
                Thread.sleep(70L)
            }
            assertTrue(firstRefresh.await(5, TimeUnit.SECONDS))
            assertTrue(observed.first() < 20, "Continuous edits must not defer refresh forever")
            assertTrue(lastRefresh.await(5, TimeUnit.SECONDS), "Final edit needs a successor")
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
