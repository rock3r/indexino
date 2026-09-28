package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.record.ReferenceRecord
import dev.sebastiano.indexino.core.record.ResourceDefinitionRecord
import dev.sebastiano.indexino.core.record.ResourceUsageRecord
import dev.sebastiano.indexino.core.record.SymbolRecord

/**
 * Read-through overlay with deterministic tombstone shadowing. Delta keys override base; tombstone
 * prefixes hide base keys without copying them.
 */
internal class WorktreeOverlayIndexStore(
    private val base: CodeIndexStore,
    private val delta: CodeIndexStore?,
    tombstonePrefixes: List<String>,
) : CodeIndexStore {
    private val tombstonePrefixes = tombstonePrefixes.toMutableSet()

    fun hideBaseForFiles(tombstones: Collection<String>) {
        tombstonePrefixes.addAll(tombstones)
    }

    override fun forEachWritablePrefix(
        prefix: String,
        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
    ) {
        delta?.forEachPrefix(prefix, action)
    }

    override fun get(key: CodeIndexKey): CodeIndexRecord? {
        delta?.get(key)?.let {
            return it
        }
        val record = base.get(key) ?: return null
        return record.takeUnless { isTombstoned(key, it) }
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
            if (!isTombstoned(key, record)) merged[key] = record
        }
        delta?.prefixScan(prefix)?.forEach { (key, record) -> merged[key] = record }
        return merged.entries.sortedBy { it.key.value }.asSequence().map { it.key to it.value }
    }

    override fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        for ((key, record) in prefixScan(prefix)) {
            if (!action(key, record)) return
        }
    }

    override fun forEachSymbolMatching(
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ) {
        var keepGoing = true
        delta?.forEachSymbolMatching(name, prefix) { key, record ->
            keepGoing = action(key, record)
            keepGoing
        }
        if (!keepGoing) return
        base.forEachSymbolMatching(name, prefix) { key, record ->
            if (!isTombstoned(key, record) && delta?.get(key) == null) {
                keepGoing = action(key, record)
            }
            keepGoing
        }
    }

    override fun forEachCallInFile(
        originId: String,
        relativeFile: String,
        action: (CodeIndexKey, CallSiteRecord) -> Boolean,
    ) {
        var keepGoing = true
        delta?.forEachCallInFile(originId, relativeFile) { key, record ->
            keepGoing = action(key, record)
            keepGoing
        }
        if (!keepGoing) return
        if (tombstonePrefixForSource(originId, relativeFile) in tombstonePrefixes) return
        base.forEachCallInFile(originId, relativeFile) { key, record ->
            if (!isTombstoned(key, record) && delta?.get(key) == null) {
                keepGoing = action(key, record)
            }
            keepGoing
        }
    }

    override fun <T> transaction(block: () -> T): T =
        delta?.transaction(block) ?: base.transaction(block)

    override fun close() {
        base.use { delta?.close() }
    }

    private fun isTombstoned(key: CodeIndexKey, record: CodeIndexRecord): Boolean {
        val source =
            when (record) {
                is FileHashRecord -> record.originId to record.relativePath
                is SymbolRecord -> record.originId to record.relativeFile
                is ReferenceRecord -> record.originId to record.relativeFile
                is CallSiteRecord -> record.originId to record.relativeFile
                is ResourceDefinitionRecord -> record.originId to record.relativeFile
                is ResourceUsageRecord -> record.originId to record.relativeFile
                is PluginFactRecord -> record.originId to record.relativeFile
                else -> return false
            }
        return tombstonePrefixForSource(source.first, source.second) in tombstonePrefixes ||
            tombstonePrefixes.any { prefix -> '\u0000' !in prefix && prefix in key.value }
    }

    companion object {
        fun tombstonePrefixForSource(originId: String, relativeFile: String): String =
            "$originId\u0000$relativeFile"

        fun tombstonePrefixForRelativeFile(relativeFile: String): String = ":$relativeFile:"
    }
}
