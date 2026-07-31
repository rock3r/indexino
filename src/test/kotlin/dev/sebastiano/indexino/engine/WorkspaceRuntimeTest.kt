package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.RefreshOutcome
import dev.sebastiano.indexino.api.RefreshRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WorkspaceRuntimeTest {
    @Test
    fun `disabled mode keeps explicit refresh available without watcher scheduling`() {
        val cacheRoot =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-auto-disabled-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime =
            WorkspaceRuntime.start(
                workspace,
                cacheRoot,
                dev.sebastiano.indexino.api.AutoRefreshMode.DISABLED,
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                RuntimeRefreshClient(connection).refresh(request).await()
            }
            Files.writeString(
                workspace.resolve("app/src/main/kotlin/Panel.kt"),
                "package sample\nclass ManualPanel\n",
            )
            Thread.sleep(500L)
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                assertTrue(RuntimeRefreshClient(connection).active().isEmpty())
                val snapshots = RuntimeSnapshotClient(connection)
                val stale = snapshots.acquire(FreshnessPolicy.AWAIT_CURRENT)
                assertEquals("DIRTY", stale.freshness.value)
                snapshots.release(stale.id)
                assertEquals(
                    RefreshOutcome.UPDATED,
                    RuntimeRefreshClient(connection).refresh(request).await().result.outcome,
                )
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `uncovered source edits are caught by reconciliation`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-watch-cap-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        val previousWatchCap = WorkspaceRuntime.maxWatchedDirectoriesForTests
        val previousReconciliation = WorkspaceRuntime.reconciliationIntervalMillisForTests
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        WorkspaceRuntime.maxWatchedDirectoriesForTests = 0
        WorkspaceRuntime.reconciliationIntervalMillisForTests = 100L
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val initial =
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await().result.generation
                }
            Files.writeString(
                workspace.resolve("app/src/main/kotlin/Panel.kt"),
                "package sample\nclass ReconciledPanel\n",
            )

            waitUntil {
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    snapshots.release(lease.id)
                    lease.generation != initial
                }
            }
        } finally {
            runtime.close()
            WorkspaceRuntime.maxWatchedDirectoriesForTests = previousWatchCap
            WorkspaceRuntime.reconciliationIntervalMillisForTests = previousReconciliation
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `source edit enqueues a daemon owned successor without manual refresh`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-auto-refresh-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val initial =
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await().result
                }
            Files.writeString(
                workspace.resolve("app/src/main/kotlin/Panel.kt"),
                "package sample\nclass RenamedPanel\n",
            )

            waitUntil {
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    snapshots.release(lease.id)
                    lease.generation != initial.generation
                }
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `external included build sources join the watcher closure`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-external-")
        val fixtureRoot =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-external-root-")
        val workspace = createGradleWorkspaceWithExternalIncludedBuild(fixtureRoot)
        val externalSource = fixtureRoot.resolve("build-logic/src/main/kotlin/Convention.kt")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime =
            WorkspaceRuntime.start(
                workspace,
                cacheRoot,
                dev.sebastiano.indexino.api.AutoRefreshMode.DISABLED,
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                RuntimeRefreshClient(connection).refresh(request).await()
            }
            assertTrue(
                externalSource.parent.toRealPath() in runtime.watchedDirectoriesForTests(request),
                "registered=${runtime.watchedDirectoriesForTests(request)}",
            )
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            fixtureRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `empty external included build keeps its topology inputs in the watcher closure`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-empty-external-")
        val fixtureRoot =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-empty-external-root-")
        val workspace = createGradleWorkspaceWithExternalIncludedBuild(fixtureRoot, withExternalSource = false)
        val externalBuild = fixtureRoot.resolve("build-logic")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime =
            WorkspaceRuntime.start(
                workspace,
                cacheRoot,
                dev.sebastiano.indexino.api.AutoRefreshMode.DISABLED,
            )
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                RuntimeRefreshClient(connection).refresh(request).await()
            }

            assertTrue(
                externalBuild.toRealPath() in runtime.watchedDirectoriesForTests(request),
                "registered=${runtime.watchedDirectoriesForTests(request)}",
            )
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            fixtureRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `await current promotes a debounced automatic refresh`() {
        val cacheRoot =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-await-current-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val initial =
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await().result.generation
                }
            Files.writeString(
                workspace.resolve("app/src/main/kotlin/Panel.kt"),
                "package sample\nclass AwaitCurrentPanel\n",
            )

            waitUntil {
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    snapshots.release(lease.id)
                    lease.freshness.value == "DIRTY"
                }
            }
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                val snapshots = RuntimeSnapshotClient(connection)
                val lease = snapshots.acquire(FreshnessPolicy.AWAIT_CURRENT)
                snapshots.release(lease.id)
                assertTrue(initial != lease.generation)
                assertEquals("CURRENT", lease.freshness.value)
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `new sibling source directory triggers a daemon owned refresh`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-sibling-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val initial =
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await().result.generation
                }
            val sibling = workspace.resolve("app/src/main/kotlin/other")
            Files.createDirectories(sibling)
            Files.writeString(sibling.resolve("Added.kt"), "package other\nclass Added\n")

            waitUntil {
                RuntimeConnection.connect(runtime.endpoint).use { connection ->
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    snapshots.release(lease.id)
                    lease.generation != initial
                }
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `restarted runtime rehydrates published scope watches`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-rehydrate-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
        val first = WorkspaceRuntime.start(workspace, cacheRoot)
        val initial =
            try {
                RuntimeConnection.connect(first.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await().result.generation
                }
            } finally {
                first.close()
            }
        val restarted = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            Files.writeString(
                workspace.resolve("app/src/main/kotlin/Panel.kt"),
                "package sample\nclass RehydratedPanel\n",
            )
            waitUntil {
                RuntimeConnection.connect(restarted.endpoint).use { connection ->
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    snapshots.release(lease.id)
                    lease.generation != initial
                }
            }
        } finally {
            restarted.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `disconnecting a client releases its daemon snapshot leases`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-disconnect-pin-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val connection = RuntimeConnection.connect(runtime.endpoint)
            val refresh =
                RuntimeRefreshClient(connection)
                    .refresh(RefreshRequest.forScope(IndexScope.gradle(":app")))
            refresh.await()
            RuntimeSnapshotClient(connection).acquire(FreshnessPolicy.PUBLISHED)
            assertEquals(1, runtime.snapshotLeaseCountForTests())

            connection.close()

            waitUntil { runtime.snapshotLeaseCountForTests() == 0 }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `daemon retains an opaque snapshot pin until the client releases it`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-snapshot-runtime-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            RuntimeConnection.connect(runtime.endpoint).use { connection ->
                val refresh =
                    RuntimeRefreshClient(connection)
                        .refresh(RefreshRequest.forScope(IndexScope.gradle(":app")))
                val refreshResult = refresh.await().result
                val snapshots = RuntimeSnapshotClient(connection)

                val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)

                assertEquals(refreshResult.generation, lease.generation)
                assertEquals(refreshResult.revision, lease.revision)
                val remoteSnapshot =
                    IndexSnapshot.createRemote(
                        client = snapshots,
                        leaseId = lease.id,
                        revision = lease.revision,
                        generation = lease.generation,
                        freshnessAtAcquisition = lease.freshness,
                        onClose = { snapshots.release(lease.id) },
                    )
                assertEquals(
                    listOf("Panel"),
                    runBlocking {
                            remoteSnapshot.findSymbols(
                                dev.sebastiano.indexino.model.SymbolQuery.named("Panel"),
                                dev.sebastiano.indexino.model.QueryOptions.page(10),
                            )
                        }
                        .items
                        .map { symbol -> symbol.name },
                )
                remoteSnapshot.close()
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `disconnecting the initiator does not cancel a daemon owned refresh`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-reconnect-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val refreshId =
                RuntimeConnection.connect(runtime.endpoint).use { client ->
                    RuntimeRefreshClient(client).refresh(request).id
                }

            RuntimeConnection.connect(runtime.endpoint).use { client ->
                val reattached = RuntimeRefreshClient(client).refresh(request)
                assertEquals(refreshId, reattached.id)
                assertTrue(reattached.await().generation.isNotBlank())
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `replaced workspace at the same path shuts down the bound runtime`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-replaced-")
        val workspace = createGradleWorkspace()
        val replacement = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        val workspaceId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        try {
            assertTrue(workspace.toFile().deleteRecursively())
            Files.move(replacement, workspace)

            val tombstonePath = RuntimePaths.tombstonePath(cacheRoot, workspaceId)
            waitUntil {
                RuntimeTombstoneStore.read(tombstonePath) != null && !Files.exists(runtime.endpoint)
            }

            assertEquals("WORKSPACE_LOST", RuntimeTombstoneStore.read(tombstonePath)?.code)
            assertFalse(Files.exists(runtime.endpoint))
            assertTrue(Files.isDirectory(workspace))
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            replacement.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `deleted workspace shuts down the runtime and leaves an external tombstone`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-lost-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        val workspaceId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        try {
            assertTrue(workspace.toFile().deleteRecursively())
            assertFalse(Files.exists(workspace))

            val tombstonePath = RuntimePaths.tombstonePath(cacheRoot, workspaceId)
            waitUntil {
                RuntimeTombstoneStore.read(tombstonePath) != null && !Files.exists(runtime.endpoint)
            }

            val tombstone = RuntimeTombstoneStore.read(tombstonePath)
            assertEquals("WORKSPACE_LOST", tombstone?.code)
            assertFalse(Files.exists(runtime.endpoint))
            assertFalse(Files.exists(workspace))
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `separate protocol clients join one daemon owned refresh`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-workspace-runtime-")
        val workspace = createGradleWorkspace()
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            RuntimeConnection.connect(runtime.endpoint).use { first ->
                RuntimeConnection.connect(runtime.endpoint).use { second ->
                    val firstHandle = RuntimeRefreshClient(first).refresh(request)
                    val secondHandle = RuntimeRefreshClient(second).refresh(request)

                    assertEquals(firstHandle.id, secondHandle.id)
                    assertEquals(firstHandle.await().generation, secondHandle.await().generation)
                }
            }
        } finally {
            runtime.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(WORKSPACE_LOSS_WAIT_ATTEMPTS) {
            if (condition()) return
            Thread.sleep(WORKSPACE_LOSS_WAIT_MILLIS)
        }
        assertTrue(condition(), "Timed out waiting for workspace-loss shutdown")
    }

    private fun createGradleWorkspaceWithExternalIncludedBuild(
        root: Path,
        withExternalSource: Boolean = true,
    ): Path {
        val workspace = root.resolve("app")
        Files.createDirectories(workspace.resolve("app/src/main/kotlin"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"test\"\ninclude(\":app\")\nincludeBuild(\"../build-logic\")\n",
        )
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/Panel.kt"),
            "package sample\nclass Panel\n",
        )
        val externalBuild = root.resolve("build-logic")
        Files.createDirectories(externalBuild)
        Files.writeString(
            externalBuild.resolve("settings.gradle.kts"),
            "rootProject.name = \"logic\"\n",
        )
        if (withExternalSource) {
            Files.createDirectories(externalBuild.resolve("src/main/kotlin"))
            Files.writeString(
                externalBuild.resolve("src/main/kotlin/Convention.kt"),
                "package logic\nclass Convention\n",
            )
        }
        return workspace
    }

    private fun createGradleWorkspace(): Path {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-workspace-")
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"test\"\ninclude(\":app\")\n",
        )
        Files.createDirectories(workspace.resolve("app/src/main/kotlin"))
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/Panel.kt"),
            "package sample\nclass Panel\n",
        )
        return workspace
    }

    private companion object {
        const val WORKSPACE_LOSS_WAIT_ATTEMPTS = 50
        const val WORKSPACE_LOSS_WAIT_MILLIS = 20L
    }
}
