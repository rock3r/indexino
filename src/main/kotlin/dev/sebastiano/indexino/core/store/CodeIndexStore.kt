package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord

internal interface CodeIndexStore {
    fun get(key: CodeIndexKey): CodeIndexRecord?

    fun put(key: CodeIndexKey, record: CodeIndexRecord)

    fun delete(key: CodeIndexKey)

    fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>>

    /**
     * Visits prefix records while the store can retain its read transaction/cursor. Return false to
     * stop scanning early. Implementations should override this when [prefixScan] materializes rows.
     */
    fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        for ((key, record) in prefixScan(prefix)) {
            if (!action(key, record)) {
                return
            }
        }
    }

    fun <T> transaction(block: () -> T): T

    fun close()
}
