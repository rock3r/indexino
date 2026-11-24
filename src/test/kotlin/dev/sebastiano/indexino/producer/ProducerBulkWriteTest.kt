package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.key.CodeIndexKeyOrder
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The producer write path must commit coarse, key-sorted batches. Per-record or per-file
 * transactions are both rejected: every producer flushes through the bulk boundary.
 */
class ProducerBulkWriteTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `producers commit facts in sorted bulk batches instead of per record writes`() {
        val workspace = SortedBulkFixture.create(tempDir.resolve("workspace"))
        XodusCodeIndexStore.open(tempDir.resolve("staging")).use { delegate ->
            val store = RecordingStore(delegate)
            workspace.produce(store)

            assertEquals(0, store.puts, "Individual puts bypass the bulk write path")
            assertTrue(store.batches.isNotEmpty(), "No bulk batch was committed")
            assertTrue(
                store.batches.size <= PRODUCER_COUNT,
                "Expected at most one batch per producer, got ${store.batches.size}",
            )
            store.batches.forEach { batch ->
                assertTrue(batch.isNotEmpty())
                batch.zipWithNext().forEach { (left, right) ->
                    assertTrue(
                        CodeIndexKeyOrder.compare(left.first, right.first) < 0,
                        "Batch is not strictly key-sorted: ${left.first} then ${right.first}",
                    )
                }
            }
            val storedRecords = delegate.prefixScan("").count()
            assertEquals(storedRecords, store.batchedEntries)
        }
    }

    /** Counts the write calls producers make, and enforces the batch contract they rely on. */
    private class RecordingStore(private val delegate: CodeIndexStore) : CodeIndexStore {
        val batches = mutableListOf<List<Pair<CodeIndexKey, CodeIndexRecord>>>()
        var puts = 0
            private set

        var batchedEntries = 0
            private set

        override fun get(key: CodeIndexKey): CodeIndexRecord? = delegate.get(key)

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
            puts += 1
            delegate.put(key, record)
        }

        override fun putSorted(entries: List<Pair<CodeIndexKey, CodeIndexRecord>>) {
            batches += entries
            batchedEntries += entries.size
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
    }

    private companion object {
        /** XmlResourceProducer, JavaSourceProducer, KotlinPsiSymbolProducer, FileHashProducer. */
        const val PRODUCER_COUNT = 4
    }
}
