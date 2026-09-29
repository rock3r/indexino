package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.core.cache.ContentAddressedPackCache
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class KotlinShadowingQueryTest {
    @Test
    fun `public references and call candidates preserve the shadowed constructor receiver`() =
        withWorkspace { workspace ->
            connect(workspace).use { indexino ->
                runBlocking {
                    indexino.refresh(request()).await()
                    indexino.snapshot().use { assertReceiverQueries(it) }
                }
            }
        }

    @Test
    fun `schema three cached wrong receivers are rejected and rebuilt without source edits`() =
        withWorkspace { workspace ->
            connect(workspace).use { indexino ->
                runBlocking { indexino.refresh(request()).await() }
            }
            seedSchemaThreeWrongReceiver(workspace)

            connect(workspace).use { indexino ->
                runBlocking {
                    val failure = assertFailsWith<IndexinoException> { indexino.snapshot().close() }
                    assertEquals("INDEX_NOT_FOUND", failure.failure.category.value)
                    assertEquals(
                        RefreshOutcome.UPDATED,
                        indexino.refresh(request()).await().outcome,
                    )
                    indexino.snapshot().use { assertReceiverQueries(it) }
                    assertEquals(
                        RefreshOutcome.UNCHANGED,
                        indexino.refresh(request()).await().outcome,
                    )
                }
            }
        }

    private suspend fun assertReceiverQueries(snapshot: IndexSnapshot) {
        val symbols =
            snapshot.findSymbols(SymbolQuery.named("mark"), QueryOptions.page(limit = 20)).items
        val ledger = symbols.single {
            it.location.file.path == "src/main/kotlin/fixture/library/Ledger.kt" &&
                it.location.line == 4
        }
        val javaLedger = symbols.single {
            it.location.file.path == "src/main/java/fixture/java/JavaLedger.java"
        }
        for ((symbol, expectedLine) in listOf(ledger to 7, javaLedger to 14)) {
            val references =
                snapshot
                    .findReferences(ReferenceQuery.to(symbol.id), QueryOptions.page(limit = 20))
                    .items
                    .filter { it.location.file.path == USE_FILE }
            assertEquals(listOf(expectedLine), references.map { it.location.line })
            assertEquals(listOf(symbol.id), references.single().candidateSymbolIds)
        }
        val call =
            snapshot.findCalls(CallQuery.to("mark"), QueryOptions.page(limit = 20)).items.single {
                it.range.start.file.path == USE_FILE && it.range.start.line == 14
            }
        assertEquals(listOf(javaLedger.id), call.candidateSymbolIds)
    }

    private fun seedSchemaThreeWrongReceiver(workspace: Path) {
        val cache = InProcessCacheLayout.cacheRoot()
        val generations =
            WorkspaceGenerationManifestStore(cache, InProcessCacheLayout.workspaceId(workspace))
        val current = checkNotNull(generations.current())
        // Only the old poisoned pack may remain queryable; a previously correct materialization
        // must not mask failure to rebuild the incremental writer on unchanged source bytes.
        check(
            InProcessCacheLayout.sharedGenerationStore(workspace, current.generation)
                .toFile()
                .deleteRecursively()
        )
        val manifest = checkNotNull(current.compatibilityManifest)
        val resolver =
            IndexPathResolver(
                workspace,
                storeRootOverride = InProcessCacheLayout.writerRoot(workspace),
            )
        val writer = resolver.resolveBaseStore(manifest.commit)
        val store = XodusCodeIndexStore.open(writer)
        try {
            val (key, original) =
                store
                    .prefixScan("ref:")
                    .mapNotNull { (key, value) ->
                        (value as? ReferenceRecord)
                            ?.takeIf {
                                it.relativeFile == USE_FILE &&
                                    it.line == 14 &&
                                    it.referencedName == "mark"
                            }
                            ?.let { key to it }
                    }
                    .single()
            store.delete(key)
            val wrong =
                original.copy(
                    symbolFqn = "fixture.library.Ledger#mark",
                    candidateSymbolFqns = listOf("fixture.library.Ledger#mark"),
                )
            store.put(
                CodeIndexKey.ref(
                    wrong.symbolFqn,
                    wrong.originId,
                    wrong.relativeFile,
                    wrong.line,
                    wrong.column,
                ),
                wrong,
            )
            val (callKey, call) =
                store
                    .prefixScan("call:")
                    .mapNotNull { (key, value) ->
                        (value as? CallSiteRecord)
                            ?.takeIf {
                                it.relativeFile == USE_FILE &&
                                    it.startLine == 14 &&
                                    it.calleeName == "mark"
                            }
                            ?.let { key to it }
                    }
                    .single()
            store.put(callKey, call.copy(candidateSymbolFqns = wrong.candidateSymbolFqns))
        } finally {
            store.close()
        }
        val oldManifest = manifest.copy(basicFactSchemaVersion = 3)
        ManifestIO.write(resolver.resolveManifest(manifest.commit), oldManifest)
        val pack = ContentAddressedPackCache(cache).installDirectory(writer, 3)
        generations.publish(
            current.copy(
                generation = "schema-three-shadowed-receiver",
                basicFactSchemaVersion = 3,
                compatibilityManifest = oldManifest,
                packKeys = listOf(pack),
            )
        )
    }

    private fun connect(workspace: Path): Indexino =
        Indexino.connectBlocking(
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                .withAutoRefresh(AutoRefreshMode.DISABLED)
        )

    private fun request(): RefreshRequest =
        RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = createTempDirectory("shadow-query-workspace-")
        val cache = createTempDirectory("shadow-query-cache-")
        val previousCache = System.getProperty("indexino.cache.dir")
        try {
            val fixture = Path.of("src/test/resources/fixtures/retrieval-v1/workspace")
            Files.walk(fixture).use { paths ->
                paths.forEach { source ->
                    val destination = workspace.resolve(fixture.relativize(source))
                    if (Files.isDirectory(source)) Files.createDirectories(destination)
                    else Files.copy(source, destination)
                }
            }
            System.setProperty("indexino.cache.dir", cache.toString())
            block(workspace)
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
            workspace.toFile().deleteRecursively()
            cache.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val USE_FILE = "src/main/kotlin/fixture/use/Use.kt"
    }
}
