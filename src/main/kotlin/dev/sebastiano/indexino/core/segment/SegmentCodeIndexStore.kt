package dev.sebastiano.indexino.core.segment

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Path
import java.util.PriorityQueue

/**
 * Read-only [CodeIndexStore] over one or more mapped immutable segment files ordered oldest to
 * newest. Newer files override older keys; tombstones in newer files shadow the same key
 * everywhere. Derived symbol-name/file and call-file tables accelerate the matching [CodeIndexStore]
 * lookups; a missing derived table falls back to prefix scans exactly like a legacy Xodus store.
 */
internal class SegmentCodeIndexStore
private constructor(
    private val files: List<MappedSegmentFile>,
) : CodeIndexStore {
    /** Segment files ordered newest first; the index is the precedence rank. */
    private val newestFirst: List<MappedSegmentFile> = files.asReversed()

    override fun get(key: CodeIndexKey): CodeIndexRecord? {
        val entry = newestEntry(key.value) ?: return null
        if (entry.tombstone) return null
        return CodeIndexRecordCodec.decode(entry.value ?: error("Missing segment value"))
    }

    override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
        throw IllegalStateException("Segment stores are read-only: ${key.value}")
    }

    override fun delete(key: CodeIndexKey) {
        throw IllegalStateException("Segment stores are read-only: ${key.value}")
    }

    override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
        mergedPrefix(prefix)

    override fun forEachPrefix(prefix: String, action: (CodeIndexKey, CodeIndexRecord) -> Boolean) {
        for ((key, record) in mergedPrefix(prefix)) {
            if (!action(key, record)) return
        }
    }

    private fun mergedPrefix(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
        sequence {
            val prefixBytes = SegmentKeys.utf8(prefix)
            val pending =
                newestFirst
                    .mapIndexed { rank, file -> CursorState(rank, file.primaryTable.cursor(prefixBytes)) }
                    .filter { it.advance() }
            if (pending.isEmpty()) return@sequence
            val queue = PriorityQueue<CursorState>()
            pending.forEach(queue::add)
            while (queue.isNotEmpty()) {
                val state = queue.poll()
                val entry = state.current ?: continue
                // Newer files shadow the same key in older files.
                while (queue.isNotEmpty() && queue.peek().current!!.keyBytes.contentEquals(entry.keyBytes)) {
                    val shadowed = queue.poll()
                    if (shadowed.advance()) queue.add(shadowed)
                }
                if (state.advance()) queue.add(state)
                if (!entry.keyBytes.startsWith(prefixBytes)) return@sequence
                if (entry.tombstone) continue
                val record =
                    CodeIndexRecordCodec.decode(entry.value ?: error("Missing segment value"))
                yield(CodeIndexKey.parse(entry.key) to record)
            }
        }

    override fun forEachSymbolMatching(
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ) {
        for (rank in newestFirst.indices) {
            val file = newestFirst[rank]
            val table = file.table(SegmentFormat.SYMBOL_NAMES_TABLE)
            if (table == null) {
                if (!scanSymbolsMatching(file, rank, name, prefix, action)) return
                continue
            }
            if (!visitNameIndex(table, rank, name, prefix, action)) return
        }
    }

    private fun scanSymbolsMatching(
        file: MappedSegmentFile,
        rank: Int,
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ): Boolean {
        var keepGoing = true
        file.primaryTable.cursor(SegmentKeys.utf8(SYMBOL_PREFIX)).asSequence().forEach { entry ->
            if (!entry.keyBytes.startsWith(SYMBOL_PREFIX_BYTES)) return@forEach
            if (entry.tombstone) return@forEach
            if (newerFileContains(entry.key, rank)) return@forEach
            val record = CodeIndexRecordCodec.decode(entry.value ?: return@forEach) as? SymbolRecord
            if (record != null) {
                val terms = sequenceOf(record.name, record.fqn) + record.aliases.asSequence()
                if (terms.any { if (prefix) it.startsWith(name) else it == name }) {
                    keepGoing = action(CodeIndexKey.parse(entry.key), record)
                    if (!keepGoing) return@forEach
                }
            }
        }
        return keepGoing
    }

    private fun visitNameIndex(
        table: MappedTable,
        rank: Int,
        name: String,
        prefix: Boolean,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ): Boolean {
        var keepGoing = true
        table.cursor(SegmentKeys.utf8(name)).asSequence().forEach { entry ->
            val term = termOf(entry.keyBytes) ?: return@forEach
            val termMatches = if (prefix) term.startsWith(name) else term == name
            if (!termMatches) return@forEach
            val primaryKey = primaryKeyOf(entry.keyBytes) ?: return@forEach
            if (newerFileContains(primaryKey, rank)) return@forEach
            val record = resolveEntry(primaryKey, rank) ?: return@forEach
            val symbol = record as? SymbolRecord ?: return@forEach
            // Emit each symbol once: only through its smallest matching term, like the Xodus
            // derived index.
            val canonical =
                (sequenceOf(symbol.name, symbol.fqn) + symbol.aliases.asSequence())
                    .filter { if (prefix) it.startsWith(name) else it == name }
                    .minOrNull()
            if (term == canonical) {
                keepGoing = action(CodeIndexKey.parse(primaryKey), symbol)
                if (!keepGoing) return@forEach
            }
        }
        return keepGoing
    }

    override fun forEachSymbolInFile(
        originId: String,
        relativeFile: String,
        action: (CodeIndexKey, SymbolRecord) -> Boolean,
    ) {
        val term = SegmentFileWriter.fileTerm(originId, relativeFile)
        forEachIndexedInFile(SegmentFormat.SYMBOL_FILES_TABLE, term, SYMBOL_PREFIX) { key, record ->
            if (record is SymbolRecord) action(key, record) else true
        }
    }

    override fun forEachCallInFile(
        originId: String,
        relativeFile: String,
        action: (CodeIndexKey, CallSiteRecord) -> Boolean,
    ) {
        val term = SegmentFileWriter.fileTerm(originId, relativeFile)
        forEachIndexedInFile(SegmentFormat.CALL_FILES_TABLE, term, CALL_PREFIX) { key, record ->
            if (record is CallSiteRecord) action(key, record) else true
        }
    }

    private fun forEachIndexedInFile(
        tableName: String,
        term: String,
        fallbackPrefix: String,
        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
    ) {
        for (rank in newestFirst.indices) {
            val file = newestFirst[rank]
            val table = file.table(tableName)
            if (table == null) {
                if (!scanFileFallback(file, rank, term, fallbackPrefix, action)) return
                continue
            }
            var keepGoing = true
            val termBytes = SegmentKeys.utf8(term)
            table.cursor(compositeStart(term)).asSequence().forEach { entry ->
                if (!hasCompositeTerm(entry.keyBytes, termBytes)) return@forEach
                val primaryKey = primaryKeyOf(entry.keyBytes) ?: return@forEach
                if (newerFileContains(primaryKey, rank)) return@forEach
                val record = resolveEntry(primaryKey, rank) ?: return@forEach
                keepGoing = action(CodeIndexKey.parse(primaryKey), record)
                if (!keepGoing) return@forEach
            }
            if (!keepGoing) return
        }
    }

    private fun scanFileFallback(
        file: MappedSegmentFile,
        rank: Int,
        term: String,
        prefix: String,
        action: (CodeIndexKey, CodeIndexRecord) -> Boolean,
    ): Boolean {
        var keepGoing = true
        val prefixBytes = SegmentKeys.utf8(prefix)
        file.primaryTable.cursor(prefixBytes).asSequence().forEach { entry ->
            if (!entry.keyBytes.startsWith(prefixBytes)) return@forEach
            if (entry.tombstone) return@forEach
            if (newerFileContains(entry.key, rank)) return@forEach
            val record = CodeIndexRecordCodec.decode(entry.value ?: return@forEach)
            val recordTerm =
                when (record) {
                    is SymbolRecord -> SegmentFileWriter.fileTerm(record.originId, record.relativeFile)
                    is CallSiteRecord ->
                        SegmentFileWriter.fileTerm(record.originId, record.relativeFile)
                    else -> return@forEach
                }
            if (recordTerm == term) {
                keepGoing = action(CodeIndexKey.parse(entry.key), record)
                if (!keepGoing) return@forEach
            }
        }
        return keepGoing
    }

    private fun newestEntry(key: String): SegmentEntry? {
        for (file in newestFirst) {
            val entry = file.primaryTable.get(key) ?: continue
            return entry
        }
        return null
    }

    private fun resolveEntry(key: String, rank: Int): CodeIndexRecord? {
        for (index in rank until newestFirst.size) {
            val entry = newestFirst[index].primaryTable.get(key) ?: continue
            if (entry.tombstone) return null
            return CodeIndexRecordCodec.decode(entry.value ?: error("Missing segment value"))
        }
        return null
    }

    private fun newerFileContains(key: String, rank: Int): Boolean {
        for (index in 0 until rank) {
            if (newestFirst[index].primaryTable.get(key) != null) return true
        }
        return false
    }

    override fun <T> transaction(block: () -> T): T = block()

    override fun close() {
        files.forEach { runCatching { it.close() } }
    }

    private class CursorState(val rank: Int, val cursor: SegmentTableCursor) :
        Comparable<CursorState> {
        var current: SegmentEntry? = null

        fun advance(): Boolean {
            current = cursor.next()
            return current != null
        }

        override fun compareTo(other: CursorState): Int {
            val comparison =
                SegmentFormat.compareKeys(current!!.keyBytes, other.current!!.keyBytes)
            if (comparison != 0) return comparison
            return rank.compareTo(other.rank)
        }
    }

    internal companion object {
        private const val SYMBOL_PREFIX = "sym:"
        private const val CALL_PREFIX = "call:"
        private val SYMBOL_PREFIX_BYTES = SegmentKeys.utf8(SYMBOL_PREFIX)
        private const val SEPARATOR: Byte = 0x00

        private fun compositeStart(term: String): ByteArray = SegmentFormat.compositeKey(term, "")

        private fun hasCompositeTerm(bytes: ByteArray, termBytes: ByteArray): Boolean =
            bytes.size > termBytes.size &&
                bytes.startsWith(termBytes) &&
                bytes[termBytes.size] == SEPARATOR

        private fun termOf(bytes: ByteArray): String? {
            val index = bytes.indexOf(SEPARATOR)
            if (index < 0) return null
            return SegmentKeys.decode(bytes.copyOfRange(0, index))
        }

        private fun primaryKeyOf(bytes: ByteArray): String? {
            val index = bytes.indexOf(SEPARATOR)
            if (index < 0 || index + 1 >= bytes.size) return null
            return SegmentKeys.decode(bytes.copyOfRange(index + 1, bytes.size))
        }

        private fun ByteArray.indexOf(byte: Byte): Int {
            for (index in indices) {
                if (this[index] == byte) return index
            }
            return -1
        }

        /**
         * Streams a store into a fresh immutable segment file — the publication conversion path.
         * Records arrive sorted from the source store; explicit tombstone keys merge in key
         * order, replacing an equal source key.
         */
        fun writeFrom(
            source: CodeIndexStore,
            target: Path,
            tombstoneKeys: List<CodeIndexKey> = emptyList(),
        ): MappedSegmentFile {
            val tombstones =
                tombstoneKeys
                    .map { it.value }
                    .sortedWith { left, right -> SegmentFormat.compareKeys(SegmentKeys.utf8(left), SegmentKeys.utf8(right)) }
            SegmentFileWriter.open(target).use { writer ->
                var tombstoneIndex = 0
                source.forEachPrefix("") { key, record ->
                    while (tombstoneIndex < tombstones.size &&
                        SegmentFormat.compareKeys(
                            SegmentKeys.utf8(tombstones[tombstoneIndex]),
                            SegmentKeys.utf8(key.value),
                        ) < 0
                    ) {
                        writer.appendTombstone(CodeIndexKey.parse(tombstones[tombstoneIndex]))
                        tombstoneIndex++
                    }
                    val shadowed =
                        tombstoneIndex < tombstones.size && tombstones[tombstoneIndex] == key.value
                    if (shadowed) {
                        tombstoneIndex++
                    } else {
                        writer.append(key, record)
                    }
                    true
                }
                while (tombstoneIndex < tombstones.size) {
                    writer.appendTombstone(CodeIndexKey.parse(tombstones[tombstoneIndex]))
                    tombstoneIndex++
                }
            }
            return MappedSegmentFile.open(target)
        }

        fun open(files: List<MappedSegmentFile>): SegmentCodeIndexStore =
            SegmentCodeIndexStore(files)
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) {
        if (this[index] != prefix[index]) return false
    }
    return true
}
