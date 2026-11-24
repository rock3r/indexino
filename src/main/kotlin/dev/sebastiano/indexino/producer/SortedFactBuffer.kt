package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.key.CodeIndexKeyOrder
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore

/** Write-only view of a store that producers emit facts through. */
internal fun interface FactSink {
    fun put(key: CodeIndexKey, record: CodeIndexRecord)
}

/**
 * Bounded buffer of facts a producer emits. Facts are sorted by key and committed in coarse store
 * transactions, so a cold build pays one B-tree append pass per chunk instead of a page copy per
 * record. A chunk is also the cancellation point: nothing is committed once the thread is
 * interrupted, and the build owner's checkpoint restores the previous staging state.
 *
 * Duplicate keys inside a chunk keep the last emitted record, matching sequential `put` order.
 * Producers must not read back facts through their store before [flush].
 */
internal class SortedFactBuffer(
    private val store: CodeIndexStore,
    private val phase: String,
    private val chunkEntries: Int = DEFAULT_CHUNK_ENTRIES,
) : FactSink {
    private val buffer = ArrayList<Pair<CodeIndexKey, CodeIndexRecord>>()

    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
        buffer += key to record
        if (buffer.size >= chunkEntries) flush()
    }

    fun flush() {
        if (buffer.isEmpty()) return
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("$phase indexing interrupted")
        }
        buffer.sortWith(FACT_ORDER)
        store.putSorted(dedupeKeepingLast())
        buffer.clear()
    }

    private fun dedupeKeepingLast(): List<Pair<CodeIndexKey, CodeIndexRecord>> {
        val entries = ArrayList<Pair<CodeIndexKey, CodeIndexRecord>>(buffer.size)
        var index = 0
        while (index < buffer.size) {
            var last = index
            while (last + 1 < buffer.size && buffer[last + 1].first == buffer[index].first) {
                last += 1
            }
            entries += buffer[last]
            index = last + 1
        }
        return entries
    }

    private companion object {
        /**
         * Records per transaction. Roughly a hundred files of facts: large enough to amortize a
         * transaction, small enough that one chunk's records plus its derived rows stay a bounded
         * working set, and that a cancelled build loses little work.
         */
        const val DEFAULT_CHUNK_ENTRIES = 4096

        val FACT_ORDER: Comparator<Pair<CodeIndexKey, CodeIndexRecord>> =
            Comparator { left, right ->
                CodeIndexKeyOrder.compare(left.first, right.first)
            }
    }
}
