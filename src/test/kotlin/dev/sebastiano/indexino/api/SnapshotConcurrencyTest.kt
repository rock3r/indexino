package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

internal class SnapshotConcurrencyTest {
    @TempDir lateinit var root: Path

    @Test
    fun `same generation pins coexist across clients and close independently`() = runBlocking {
        val workspace = Files.createDirectories(root.resolve("workspace"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"fixture\"",
        )
        val source = workspace.resolve("src/main/kotlin/Marker.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class Marker")
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        val configuration =
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                .withAutoRefresh(AutoRefreshMode.DISABLED)
        try {
            Indexino.connect(configuration).use { first ->
                Indexino.connect(configuration).use { second ->
                    val request =
                        RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
                    val initial = first.refresh(request).await()
                    first.snapshot().use { old ->
                        val opened = runCatching { first.snapshot() }
                        assertTrue(
                            opened.isSuccess,
                            "A second pin must open: ${opened.exceptionOrNull()}",
                        )
                        val sibling = opened.getOrThrow()
                        try {
                            second.snapshot().use { peer ->
                                assertEquals(initial.generation, sibling.generation)
                                assertEquals(initial.generation, peer.generation)
                                sibling.close()
                                sibling.close()
                                assertEquals(
                                    1,
                                    peer
                                        .findSymbols(
                                            SymbolQuery.named("Marker"),
                                            QueryOptions.page(10),
                                        )
                                        .items
                                        .size,
                                )
                                Files.writeString(source, "class Replacement")
                                val updated = second.refresh(request).await()
                                assertTrue(updated.generation != initial.generation)
                                second.snapshot().use { current ->
                                    assertEquals(
                                        1,
                                        current
                                            .findSymbols(
                                                SymbolQuery.named("Replacement"),
                                                QueryOptions.page(10),
                                            )
                                            .items
                                            .size,
                                    )
                                    assertEquals(
                                        0,
                                        current
                                            .findSymbols(
                                                SymbolQuery.named("Marker"),
                                                QueryOptions.page(10),
                                            )
                                            .items
                                            .size,
                                    )
                                }
                                assertEquals(
                                    1,
                                    old.findSymbols(
                                            SymbolQuery.named("Marker"),
                                            QueryOptions.page(10),
                                        )
                                        .items
                                        .size,
                                )
                                assertEquals(
                                    1,
                                    peer
                                        .findSymbols(
                                            SymbolQuery.named("Marker"),
                                            QueryOptions.page(10),
                                        )
                                        .items
                                        .size,
                                )
                            }
                        } finally {
                            sibling.close()
                        }
                    }
                }
            }
        } finally {
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
        }
    }
}
