package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RuntimeRecoveryTest {
    @Test
    fun `old remote snapshot fails after runtime restart while a new snapshot reads the published generation`() {
        withRuntimeFixture("restart") { workspace, cacheRoot ->
            val request = RefreshRequest.forScope(IndexScope.gradle(":app"))
            val firstRuntime = WorkspaceRuntime.start(workspace, cacheRoot)
            val oldConnection = RuntimeConnection.connect(firstRuntime.endpoint)
            val oldSnapshots = RuntimeSnapshotClient(oldConnection)
            RuntimeRefreshClient(oldConnection).refresh(request).await()
            val oldLease = oldSnapshots.acquire(FreshnessPolicy.PUBLISHED)
            val oldSnapshot = remoteSnapshot(oldSnapshots, oldLease)
            val oldGeneration = oldLease.generation

            firstRuntime.close()
            val restarted = WorkspaceRuntime.start(workspace, cacheRoot)
            try {
                assertEquals(4, oldSnapshot.basicFactSchemaVersion.value)
                Files.writeString(
                    workspace.resolve("app/src/main/kotlin/Panel.kt"),
                    "package sample\nclass RestartedPanel\n",
                )
                RuntimeConnection.connect(restarted.endpoint).use { connection ->
                    RuntimeRefreshClient(connection).refresh(request).await()
                    val snapshots = RuntimeSnapshotClient(connection)
                    val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    remoteSnapshot(snapshots, lease).use { snapshot ->
                        assertTrue(lease.generation != oldGeneration)
                        assertEquals(4, snapshot.basicFactSchemaVersion.value)
                        assertEquals(
                            listOf("RestartedPanel"),
                            queryNames(snapshot, "RestartedPanel"),
                        )
                        assertEquals(emptyList(), queryNames(snapshot, "Panel"))
                    }
                }

                val failure =
                    assertFailsWith<IndexinoException> { queryNames(oldSnapshot, "Panel") }
                assertEquals("internal", failure.failure.code)
            } finally {
                runCatching { oldSnapshot.close() }
                oldConnection.close()
                restarted.close()
            }
        }
    }

    @Test
    fun `moving a workspace stops only its bound runtime`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-recovery-move-cache-")
        val movedWorkspace = createGradleWorkspace("MovedPanel")
        val unrelatedWorkspace = createGradleWorkspace("UnrelatedPanel")
        val destination = movedWorkspace.resolveSibling("${movedWorkspace.fileName}-moved")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val movedRuntime = WorkspaceRuntime.start(movedWorkspace, cacheRoot)
        val unrelatedRuntime = WorkspaceRuntime.start(unrelatedWorkspace, cacheRoot)
        try {
            refresh(unrelatedRuntime, ":app")
            Files.move(movedWorkspace, destination)

            waitUntil { !Files.exists(movedRuntime.endpoint) }

            assertFalse(Files.exists(movedRuntime.endpoint))
            assertTrue(Files.isDirectory(destination))
            RuntimeConnection.connect(unrelatedRuntime.endpoint).use { connection ->
                val snapshots = RuntimeSnapshotClient(connection)
                val lease = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                remoteSnapshot(snapshots, lease).use { snapshot ->
                    assertEquals(listOf("UnrelatedPanel"), queryNames(snapshot, "UnrelatedPanel"))
                }
            }
        } finally {
            movedRuntime.close()
            unrelatedRuntime.close()
            cacheRoot.toFile().deleteRecursively()
            destination.toFile().deleteRecursively()
            movedWorkspace.toFile().deleteRecursively()
            unrelatedWorkspace.toFile().deleteRecursively()
            restoreCacheRoot(previousCacheRoot)
        }
    }

    private fun refresh(runtime: WorkspaceRuntime, scope: String) {
        RuntimeConnection.connect(runtime.endpoint).use { connection ->
            RuntimeRefreshClient(connection)
                .refresh(RefreshRequest.forScope(IndexScope.gradle(scope)))
                .await()
        }
    }

    private fun remoteSnapshot(
        client: RuntimeSnapshotClient,
        lease: RuntimeSnapshotLease,
    ): IndexSnapshot =
        IndexSnapshot.createRemote(
            client = client,
            leaseId = lease.id,
            revision = lease.revision,
            generation = lease.generation,
            freshnessAtAcquisition = lease.freshness,
            onClose = { client.release(lease.id) },
        )

    private fun queryNames(snapshot: IndexSnapshot, name: String): List<String> =
        runBlocking { snapshot.findSymbols(SymbolQuery.named(name), QueryOptions.page(10)) }
            .items
            .map { it.name }

    private fun withRuntimeFixture(
        name: String,
        block: (workspace: Path, cacheRoot: Path) -> Unit,
    ) {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-recovery-$name-cache-")
        val workspace = createGradleWorkspace("Panel")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            block(workspace, cacheRoot)
        } finally {
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
            restoreCacheRoot(previousCacheRoot)
        }
    }

    private fun createGradleWorkspace(symbol: String): Path {
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-recovery-workspace-")
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"test\"\ninclude(\":app\")\n",
        )
        Files.createDirectories(workspace.resolve("app/src/main/kotlin"))
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/Panel.kt"),
            "package sample\nclass $symbol\n",
        )
        return workspace
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(WAIT_ATTEMPTS) {
            if (condition()) return
            Thread.sleep(WAIT_MILLIS)
        }
        assertTrue(condition(), "Timed out waiting for runtime shutdown")
    }

    private fun restoreCacheRoot(previous: String?) {
        if (previous == null) System.clearProperty("indexino.cache.dir")
        else System.setProperty("indexino.cache.dir", previous)
    }

    private companion object {
        const val WAIT_ATTEMPTS = 50
        const val WAIT_MILLIS = 20L
    }
}
