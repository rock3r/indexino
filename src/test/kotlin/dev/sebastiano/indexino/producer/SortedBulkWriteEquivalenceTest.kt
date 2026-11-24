package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.engine.BuildStoreCheckpoint
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Pins the persisted key/value contract of the producer write path. The golden was captured from
 * the untouched per-file write path; every later coarse bulk path must store exactly the same
 * records in exactly the same order.
 */
class SortedBulkWriteEquivalenceTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `fresh build stores the golden key values`() {
        val facts = buildFixtureFacts(tempDir.resolve("fresh"))

        assertEquals(golden(), facts)
    }

    @Test
    fun `incremental refresh matches a fresh build`() {
        val workspace = SortedBulkFixture.create(tempDir.resolve("workspace"))
        val staging = tempDir.resolve("incremental")
        val initial = workspace.sources
        val changedPaths =
            setOf(
                "src/main/java/bench/Alpha.java",
                "src/main/kotlin/bench/Gamma.kt",
                "src/main/res/values/strings.xml",
            )
        val incremental =
            XodusCodeIndexStore.open(staging).use { store ->
                workspace.produce(store)
                val editedSnapshot = run {
                    workspace.writeRevision(SortedBulkFixture.Revision.EDITED)
                    workspace.captureSnapshot()
                }
                workspace.produce(
                    store = store,
                    changed = initial.filter { it.path in changedPaths },
                    snapshot = editedSnapshot,
                )
                dumpStoredFacts(store)
            }

        val fresh =
            buildFixtureFacts(tempDir.resolve("fresh-edited"), SortedBulkFixture.Revision.EDITED)
        assertEquals(fresh, incremental)
    }

    @Test
    fun `interrupted bulk writes restore and recover to a fresh build`() {
        val workspace = SortedBulkFixture.create(tempDir.resolve("workspace-interrupted"))
        val staging = tempDir.resolve("interrupted")
        val manifest = staging.resolve("manifest.json")
        XodusCodeIndexStore.open(staging).use { store ->
            workspace.produce(store)
            val before = dumpStoredFacts(store)
            workspace.writeRevision(SortedBulkFixture.Revision.EDITED)
            val editedSnapshot = workspace.captureSnapshot()
            val observed =
                assertFailsWith<IllegalStateException> {
                    BuildStoreCheckpoint(store, manifest).use { checkpoint ->
                        checkpoint.run {
                            workspace.produce(
                                store = InterruptingStore(store, COMMITS_BEFORE_INTERRUPTION),
                                snapshot = editedSnapshot,
                                onPhase = {},
                            )
                        }
                    }
                }
            assertEquals("injected interruption", observed.message)
            assertFalse(manifest.resolveSibling("${manifest.fileName}.rollback").exists())
            assertEquals(before, dumpStoredFacts(store))
        }

        val recovered =
            XodusCodeIndexStore.open(staging).use { store ->
                workspace.produce(store)
                dumpStoredFacts(store)
            }
        val fresh =
            buildFixtureFacts(
                tempDir.resolve("fresh-interrupted"),
                SortedBulkFixture.Revision.EDITED,
            )
        assertEquals(fresh, recovered)
        assertTrue(golden() != recovered, "Edited fixture must add facts")
    }

    @Test
    fun `bulk writes keep derived symbol and call lookups consistent`() {
        val workspace = SortedBulkFixture.create(tempDir.resolve("workspace-derived"))
        XodusCodeIndexStore.open(tempDir.resolve("derived")).use { store ->
            workspace.produce(store)

            val alpha = workspace.sources.single { it.path == "src/main/java/bench/Alpha.java" }
            val fileSymbols = mutableListOf<String>()
            store.forEachSymbolInFile(alpha.originId, alpha.path) { _, record ->
                fileSymbols += record.fqn
                true
            }
            assertTrue(fileSymbols.any { it == "bench.Alpha#run" }, "$fileSymbols")

            val calls = mutableListOf<CallSiteRecord>()
            store.forEachCallInFile(alpha.originId, alpha.path) { _, record ->
                calls += record
                true
            }
            assertTrue(
                calls.any { call -> "bench.Beta#doubleValue" in call.candidateSymbolFqns },
                "${calls.map { it.calleeName }}",
            )

            val byName = mutableListOf<String>()
            store.forEachSymbolMatching("doubleValue", prefix = false) { _, record ->
                byName += record.fqn
                true
            }
            assertEquals(listOf("bench.Beta#doubleValue"), byName)
        }
    }

    private fun golden(): String =
        checkNotNull(javaClass.getResourceAsStream(GOLDEN_RESOURCE)) {
                "Missing golden resource $GOLDEN_RESOURCE"
            }
            .use { it.readAllBytes().decodeToString() }

    /**
     * Fails bulk commits after the first few complete. Red-phase per-fact writes trip the same
     * limit, so both paths must leave a restorable checkpoint.
     */
    private class InterruptingStore(
        private val delegate: CodeIndexStore,
        private val commitsBeforeFailure: Int,
    ) : CodeIndexStore {
        private var commits = 0

        override fun get(key: CodeIndexKey): CodeIndexRecord? = delegate.get(key)

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
            commitPoint()
            delegate.put(key, record)
        }

        override fun putSorted(entries: List<Pair<CodeIndexKey, CodeIndexRecord>>) {
            commitPoint()
            delegate.putSorted(entries)
        }

        override fun delete(key: CodeIndexKey) = delegate.delete(key)

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            delegate.prefixScan(prefix)

        override fun forEachPrefix(
            prefix: String,
            action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
        ) = delegate.forEachPrefix(prefix, action)

        override fun forEachWritablePrefix(
            prefix: String,
            action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
        ) = delegate.forEachWritablePrefix(prefix, action)

        override fun <T> transaction(block: () -> T): T = delegate.transaction(block)

        override fun close() = delegate.close()

        private fun commitPoint() {
            commits += 1
            if (commits >= commitsBeforeFailure) {
                error("injected interruption")
            }
        }
    }

    private companion object {
        const val GOLDEN_RESOURCE = "/golden/sorted-bulk-facts.txt"
        const val COMMITS_BEFORE_INTERRUPTION = 3
    }
}
