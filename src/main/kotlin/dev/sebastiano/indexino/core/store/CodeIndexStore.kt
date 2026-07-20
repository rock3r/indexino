package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord

internal interface CodeIndexStore {
    fun get(key: CodeIndexKey): CodeIndexRecord?

    fun put(key: CodeIndexKey, record: CodeIndexRecord)

    fun delete(key: CodeIndexKey)

    fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>>

    fun <T> transaction(block: () -> T): T

    fun close()
}
