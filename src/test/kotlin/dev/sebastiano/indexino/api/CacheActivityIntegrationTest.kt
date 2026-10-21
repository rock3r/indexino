package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.cli.CacheMaintenance
import dev.sebastiano.indexino.core.cache.ContentAddressedPackCache
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

internal class CacheActivityIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `unavailable activity lock maps to a typed connection failure`() = runBlocking {
        Files.writeString(root.resolve("cache"), "not a directory")
        val failure =
            assertFailsWith<IndexinoException> {
                withIndex { _, _, _ -> error("Connection must fail before use") }
            }
        assertEquals("IO", failure.failure.category.value)
        assertTrue(failure.cause is java.io.IOException)
    }

    @Test
    fun `gc retains an overlay base after pins close until the workspace is forgotten`() =
        runBlocking {
            withIndex { index, workspace, cache ->
                val request =
                    RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
                index.refresh(request).await()
                val manifests =
                    WorkspaceGenerationManifestStore(
                        cache,
                        InProcessCacheLayout.workspaceId(workspace),
                    )
                val oldPack =
                    ContentAddressedPackCache(cache)
                        .packPath(manifests.current()!!.packKeys.single())
                val pinned = index.snapshot()
                try {
                    Files.writeString(
                        workspace.resolve("src/main/kotlin/Marker.kt"),
                        "class Replacement",
                    )
                    index.refresh(request).await()
                    index.close()
                    CacheMaintenance.gc(cache)
                    assertTrue(Files.exists(oldPack), "GC removed a superseded but pinned pack")
                    assertEquals(
                        listOf("Marker"),
                        pinned
                            .findSymbols(SymbolQuery.named("Marker"), QueryOptions.page(10))
                            .items
                            .map { it.name },
                    )
                } finally {
                    pinned.close()
                }
                CacheMaintenance.gc(cache)
                assertTrue(Files.exists(oldPack), "The current overlay still references its base")
                assertEquals(0, CacheMaintenance.forget(cache, workspace))
                CacheMaintenance.gc(cache)
                assertFalse(
                    Files.exists(oldPack),
                    "Forgotten workspace no longer references the base",
                )
            }
        }

    @Test
    fun `gc excludes refresh work after its initiating client closes`() = runBlocking {
        withIndex { index, workspace, cache ->
            val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
            index.refresh(request).await()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val orphan = ContentAddressedPackCache(cache).packPath("d".repeat(64))
            Files.createDirectories(orphan.parent)
            Files.writeString(orphan, "unpublished work")
            Files.writeString(workspace.resolve("src/main/kotlin/Marker.kt"), "class Replacement")
            index.afterPublishGenerationStoreForTests = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            index.snapshot().use { pinned ->
                val refresh = index.refresh(request)
                try {
                    assertTrue(
                        entered.await(10, TimeUnit.SECONDS),
                        "Refresh did not reach publication",
                    )
                    index.close()
                    CacheMaintenance.gc(cache)
                    assertTrue(
                        Files.exists(orphan),
                        "GC raced an active refresh after client close",
                    )
                    assertEquals(
                        listOf("Marker"),
                        pinned
                            .findSymbols(SymbolQuery.named("Marker"), QueryOptions.page(10))
                            .items
                            .map { it.name },
                    )
                    pinned.close()
                    CacheMaintenance.gc(cache)
                    assertTrue(Files.exists(orphan), "Refresh must protect work without a live pin")
                } finally {
                    release.countDown()
                    refresh.await()
                }
            }
            CacheMaintenance.gc(cache)
            assertFalse(Files.exists(orphan), "Completed refresh must release GC exclusion")
        }
    }

    @Test
    fun `reverting after gc rebuilds historical packs for a fresh client`() = runBlocking {
        withIndex { first, workspace, cache ->
            val configuration =
                IndexinoConfiguration.forWorkspace(workspace)
                    .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                    .withAutoRefresh(AutoRefreshMode.DISABLED)
            val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
            val generationA = first.refresh(request).await().generation
            val manifests =
                WorkspaceGenerationManifestStore(cache, InProcessCacheLayout.workspaceId(workspace))
            val packA =
                ContentAddressedPackCache(cache).packPath(manifests.current()!!.packKeys.single())
            first.close()

            Files.writeString(workspace.resolve("src/main/kotlin/Marker.kt"), "class Replacement")
            Indexino.connect(configuration).use { second ->
                // Different analysis coordinates prevent B from retaining A as an overlay base.
                second
                    .refresh(request.withPlugin(PluginId.of("dev.sebastiano.selection-context")))
                    .await()
            }
            assertEquals("materialized", manifests.current()!!.representation)
            assertTrue(manifests.current()!!.baseGeneration == null)
            CacheMaintenance.gc(cache)
            assertFalse(Files.exists(packA), "GC must actually reclaim the historical A pack")

            Files.writeString(workspace.resolve("src/main/kotlin/Marker.kt"), "class Marker")
            Indexino.connect(configuration).use { reverting ->
                assertEquals(generationA, reverting.refresh(request).await().generation)
            }
            Indexino.connect(configuration).use { fresh ->
                fresh.snapshot().use { snapshot ->
                    assertEquals(generationA, snapshot.generation)
                    assertEquals(
                        listOf("Marker"),
                        snapshot
                            .findSymbols(SymbolQuery.named("Marker"), QueryOptions.page(10))
                            .items
                            .map { it.name },
                    )
                    assertTrue(
                        snapshot
                            .findSymbols(SymbolQuery.named("Replacement"), QueryOptions.page(10))
                            .items
                            .isEmpty()
                    )
                }
            }
        }
    }

    private suspend fun withIndex(block: suspend (Indexino, Path, Path) -> Unit) {
        val workspace = Files.createDirectories(root.resolve("workspace"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"fixture\"",
        )
        val source = workspace.resolve("src/main/kotlin/Marker.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Marker")
        val cache = root.resolve("cache")
        val previous = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cache.toString())
        try {
            Indexino.connect(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                        .withAutoRefresh(AutoRefreshMode.DISABLED)
                )
                .use { block(it, workspace, cache) }
        } finally {
            if (previous == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previous)
        }
    }
}
