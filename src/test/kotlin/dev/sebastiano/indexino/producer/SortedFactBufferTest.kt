package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortedFactBufferTest {
    @Test
    fun `commits one sorted batch per bounded chunk`() {
        val store = RecordingStore()
        val buffer = SortedFactBuffer(store, "test", chunkEntries = CHUNK)

        (0 until FACT_COUNT).forEach { index -> buffer.put(key(index), record(index)) }
        buffer.flush()

        assertEquals(listOf(CHUNK, CHUNK, FACT_COUNT - 2 * CHUNK), store.batches.map { it.size })
        store.batches.forEach { batch ->
            batch.zipWithNext().forEach { (left, right) ->
                assertTrue(
                    left.first.value < right.first.value,
                    "${left.first} then ${right.first}",
                )
            }
        }
        assertEquals(
            (0 until FACT_COUNT).map { index -> key(index).value }.sorted(),
            store.batches.flatten().map { entry -> entry.first.value }.sorted(),
        )
    }

    @Test
    fun `keeps the last record emitted for a duplicate key`() {
        val store = RecordingStore()
        val buffer = SortedFactBuffer(store, "test", chunkEntries = CHUNK)

        buffer.put(key(1), record(1))
        buffer.put(key(1), record(2))
        buffer.flush()

        val committed = store.batches.single()
        assertEquals(1, committed.size)
        assertEquals(record(2), committed.single().second)
    }

    @Test
    fun `commits nothing once the thread is interrupted`() {
        val store = RecordingStore()
        val buffer = SortedFactBuffer(store, "test", chunkEntries = CHUNK)
        buffer.put(key(1), record(1))

        val failure = run {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedException> { buffer.flush() }
            } finally {
                Thread.interrupted()
            }
        }

        assertTrue(failure.message.orEmpty().contains("test indexing interrupted"))
        assertTrue(store.batches.isEmpty())
    }

    private fun key(index: Int): CodeIndexKey = CodeIndexKey.file("File$index.kt", "hash$index")

    private fun record(index: Int): CodeIndexRecord = FileHashRecord("File$index.kt", "hash$index")

    private class RecordingStore : CodeIndexStore {
        val batches = mutableListOf<List<Pair<CodeIndexKey, CodeIndexRecord>>>()

        override fun get(key: CodeIndexKey): CodeIndexRecord? = null

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) = error("unbatched put")

        override fun putSorted(entries: List<Pair<CodeIndexKey, CodeIndexRecord>>) {
            batches += entries.toList()
        }

        override fun delete(key: CodeIndexKey) = Unit

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            emptySequence()

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit
    }

    private companion object {
        const val CHUNK = 4
        const val FACT_COUNT = 10
    }
}
