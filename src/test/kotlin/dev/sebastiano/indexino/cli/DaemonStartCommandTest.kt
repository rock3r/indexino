package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.FreshnessPolicy
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.engine.RuntimeConnection
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeRefreshClient
import dev.sebastiano.indexino.engine.RuntimeSnapshotClient
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.CheckRequest
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DaemonStartCommandTest {
    @Test
    fun `default public connect auto-spawns and proxies through the daemon`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-public-daemon-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-public-daemon-workspace-")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":app\")\n")
        Files.createDirectories(workspace.resolve("app/src/main/kotlin"))
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/Panel.kt"),
            "package sample\nclass Panel\n",
        )
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        val previousAttachMode = Indexino.defaultRuntimeAttachModeForTests
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        Indexino.defaultRuntimeAttachModeForTests = null
        try {
            val facade = Indexino.connectBlocking(workspace)
            try {
                val refresh = runBlocking {
                    facade.refresh(RefreshRequest.forScope(IndexScope.gradle(":app"))).await()
                }
                assertEquals(emptyList(), runBlocking { facade.activeRefreshes() })
                val snapshot = runBlocking { facade.snapshot() }
                assertEquals(refresh.generation, snapshot.generation)
                snapshot.close()
                val endpoint =
                    RuntimePaths.socketPath(
                        cacheRoot,
                        InProcessCacheLayout.workspaceId(workspace.toRealPath()),
                    )
                assertTrue(Files.exists(endpoint))
                runBlocking { facade.shutdownRuntime() }
                assertFalse(Files.exists(endpoint))
            } finally {
                facade.close()
            }
        } finally {
            DaemonStopCommand().stop(workspace, cacheRoot)
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            Indexino.defaultRuntimeAttachModeForTests = previousAttachMode
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `two independent clients join one refresh in the daemon process`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-join-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-join-workspace-")
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":app\")\n")
        Files.createDirectories(workspace.resolve("app/src/main/kotlin"))
        Files.writeString(
            workspace.resolve("app/src/main/kotlin/Panel.kt"),
            """
            package sample
            import androidx.compose.foundation.text.selection.SelectionContainer

            class Panel
            fun helper(): Int = 1
            fun use(): Int = helper()
            @Composable
            fun panel() {
                SelectionContainer {
                    ActionButton { }
                }
            }
            """
                .trimIndent(),
        )
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            assertEquals(CliExitCodes.SUCCESS, DaemonStartCommand().start(workspace, cacheRoot))
            val endpoint =
                RuntimePaths.socketPath(
                    cacheRoot,
                    InProcessCacheLayout.workspaceId(workspace.toRealPath()),
                )
            val selectionPlugin = PluginId.of("dev.sebastiano.selection-context")
            val request =
                RefreshRequest.forScope(IndexScope.gradle(":app")).withPlugin(selectionPlugin)
            RuntimeConnection.connect(endpoint).use { first ->
                RuntimeConnection.connect(endpoint).use { second ->
                    val firstRefresh = RuntimeRefreshClient(first).refresh(request)
                    val secondRefresh = RuntimeRefreshClient(second).refresh(request)
                    assertEquals(firstRefresh.id, secondRefresh.id)
                    val firstResult = firstRefresh.await().result
                    val secondResult = secondRefresh.await().result
                    assertEquals(firstResult, secondResult)
                    assertEquals(request.scope, firstResult.scope)
                    assertEquals(firstRefresh.id, firstResult.refreshId.value)
                    val snapshots = RuntimeSnapshotClient(first)
                    val snapshot = snapshots.acquire(FreshnessPolicy.PUBLISHED)
                    assertEquals(firstResult.generation, snapshot.generation)
                    val symbols =
                        snapshots.findSymbols(
                            snapshot.id,
                            SymbolQuery.named("Panel"),
                            QueryOptions.page(10),
                        )
                    assertEquals(listOf("Panel"), symbols.items.map { symbol -> symbol.name })
                    val helper =
                        snapshots
                            .findSymbols(
                                snapshot.id,
                                SymbolQuery.named("helper"),
                                QueryOptions.page(10),
                            )
                            .items
                            .single()
                    val references =
                        snapshots.findReferences(
                            snapshot.id,
                            ReferenceQuery.to(helper.id),
                            QueryOptions.page(10),
                        )
                    assertEquals(
                        listOf("helper"),
                        references.items.map { reference -> reference.referencedName },
                    )
                    val calls =
                        snapshots.findCalls(
                            snapshot.id,
                            CallQuery.to("helper"),
                            QueryOptions.page(10),
                        )
                    assertEquals(listOf("helper"), calls.items.map { call -> call.calleeName })
                    val findings =
                        snapshots.runCheck(
                            snapshot.id,
                            CheckRequest.of(selectionPlugin, "interactive-in-selection"),
                            QueryOptions.page(10),
                        )
                    assertEquals(1, findings.items.size)
                    assertEquals(
                        "ActionButton is interactive inside SelectionContainer",
                        findings.items.single().message,
                    )
                    assertEquals("ActionButton", findings.items.single().properties["callee"])
                    snapshots.release(snapshot.id)
                }
            }
            val facade =
                Indexino.connectRemote(workspace.toRealPath(), RuntimeConnection.connect(endpoint))
            try {
                val result = runBlocking { facade.refresh(request).await() }
                assertEquals(request.scope, result.scope)
                val snapshot = runBlocking { facade.snapshot() }
                assertEquals(
                    listOf("Panel"),
                    runBlocking {
                            snapshot.findSymbols(SymbolQuery.named("Panel"), QueryOptions.page(10))
                        }
                        .items
                        .map { symbol -> symbol.name },
                )
                snapshot.close()
            } finally {
                facade.close()
            }
        } finally {
            DaemonStopCommand().stop(workspace, cacheRoot)
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `daemon start launches a foreground owner process that stop can shut down`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-start-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-start-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            assertEquals(CliExitCodes.SUCCESS, DaemonStartCommand().start(workspace, cacheRoot))
            val endpoint =
                RuntimePaths.socketPath(
                    cacheRoot,
                    InProcessCacheLayout.workspaceId(workspace.toRealPath()),
                )
            assertTrue(Files.exists(endpoint))

            assertEquals(CliExitCodes.SUCCESS, DaemonStopCommand().stop(workspace, cacheRoot))
        } finally {
            DaemonStopCommand().stop(workspace, cacheRoot)
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }
}
