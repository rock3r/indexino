package com.kotlincodeindex.core.store

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.CodeIndexRecord
import com.kotlincodeindex.core.record.ComposeSelectionSiteRecord
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore

/**
 * Read-through overlay: delta keys override base; writes go to delta only.
 * Base store should be opened read-only at query time.
 */
class OverlayCodeIndexStore(
    private val base: CodeIndexStore,
    private val delta: CodeIndexStore,
) : CodeIndexStore {
    override fun get(key: CodeIndexKey): CodeIndexRecord? =
        delta.get(key) ?: base.get(key)

    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
        delta.put(key, record)
    }

    override fun delete(key: CodeIndexKey) {
        delta.delete(key)
    }

    override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> {
        val merged = linkedMapOf<CodeIndexKey, CodeIndexRecord>()
        base.prefixScan(prefix).forEach { (key, record) -> merged[key] = record }
        delta.prefixScan(prefix).forEach { (key, record) -> merged[key] = record }
        return merged.entries
            .sortedBy { it.key.value }
            .asSequence()
            .map { it.key to it.value }
    }

    override fun <T> transaction(block: () -> T): T = delta.transaction(block)

    override fun close() {
        delta.close()
        base.close()
    }

    companion object {
        fun open(basePath: java.nio.file.Path, deltaPath: java.nio.file.Path): OverlayCodeIndexStore {
            val base = XodusCodeIndexStore.open(basePath, readOnly = true)
            val delta = XodusCodeIndexStore.open(deltaPath, readOnly = false)
            return OverlayCodeIndexStore(base, delta)
        }

        fun forkDelta(fromDelta: java.nio.file.Path, toDelta: java.nio.file.Path) {
            toDelta.parent?.toFile()?.mkdirs()
            val source = XodusCodeIndexStore.open(fromDelta, readOnly = true)
            val target = XodusCodeIndexStore.open(toDelta, readOnly = false)
            try {
                source.prefixScan("").forEach { (key, record) ->
                    target.put(key, record)
                }
            } finally {
                source.close()
                target.close()
            }
        }
    }
}
