package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord

/**
 * Read-through overlay with deterministic tombstone shadowing. Delta keys override base; tombstone
 * prefixes hide base keys without copying them.
 */
internal class WorktreeOverlayIndexStore(
    private val base: CodeIndexStore,
    private val delta: CodeIndexStore?,
    private val tombstonePrefixes: List<String>,
) : CodeIndexStore {
    override fun get(key: CodeIndexKey): CodeIndexRecord? {
        delta?.get(key)?.let {
            return it
        }
        if (isTombstoned(key)) return null
        return base.get(key)
    }

    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
        requireNotNull(delta) { "Cannot write to a read-only overlay view" }
        delta.put(key, record)
    }

    override fun delete(key: CodeIndexKey) {
        requireNotNull(delta) { "Cannot delete in a read-only overlay view" }
        delta.delete(key)
    }

    override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> {
        val merged = linkedMapOf<CodeIndexKey, CodeIndexRecord>()
        base.prefixScan(prefix).forEach { (key, record) ->
            if (!isTombstoned(key)) merged[key] = record
        }
        delta?.prefixScan(prefix)?.forEach { (key, record) -> merged[key] = record }
        return merged.entries.sortedBy { it.key.value }.asSequence().map { it.key to it.value }
    }

    override fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        for ((key, record) in prefixScan(prefix)) {
            if (!action(key, record)) return
        }
    }

    override fun <T> transaction(block: () -> T): T =
        delta?.transaction(block) ?: base.transaction(block)

    override fun close() {
        delta?.close()
        base.close()
    }

    private fun isTombstoned(key: CodeIndexKey): Boolean {
        val raw = key.value
        return tombstonePrefixes.any { prefix -> prefix in raw }
    }

    companion object {
        fun tombstonePrefixForRelativeFile(relativeFile: String): String = ":$relativeFile:"
    }
}
