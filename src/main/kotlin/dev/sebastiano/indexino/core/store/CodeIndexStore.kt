package dev.sebastiano.indexino.core.store

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.SymbolRecord

internal interface CodeIndexStore : AutoCloseable {
    fun get(key: CodeIndexKey): CodeIndexRecord?

    fun put(key: CodeIndexKey, record: CodeIndexRecord)

    fun delete(key: CodeIndexKey)

    fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>>

    /**
     * Visits prefix records while the store can retain its read transaction/cursor. Return false to
     * stop scanning early. Implementations should override this when [prefixScan] materializes
     * rows.
     */
    fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        for ((key, record) in prefixScan(prefix)) {
            if (!action(key, record)) {
                return
            }
        }
    }

    /**
     * Visits symbol records whose name, FQN, or alias equals [name], or starts with it when
     * [prefix] is true. Each primary key is emitted at most once. Return false to stop early.
     */
    fun forEachSymbolMatching(
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ) {
        forEachPrefix("sym:") { key, record ->
            val symbol = record as? SymbolRecord
            if (symbol != null) {
                val terms = sequenceOf(symbol.name, symbol.fqn) + symbol.aliases.asSequence()
                val matches = terms.any { if (prefix) it.startsWith(name) else it == name }
                if (matches && !action(key, symbol)) return@forEachPrefix false
            }
            true
        }
    }

    /** Visits calls in one origin-qualified source file, with legacy scanning fallback. */
    fun forEachCallInFile(
        originId: String,
        relativeFile: String,
        action: (CodeIndexKey, CallSiteRecord) -> Boolean,
    ) {
        forEachPrefix("call:") { key, record ->
            if (
                record is CallSiteRecord &&
                    record.originId == originId &&
                    record.relativeFile == relativeFile
            ) {
                action(key, record)
            } else true
        }
    }

    fun <T> transaction(block: () -> T): T

    override fun close()
}
