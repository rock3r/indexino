package dev.sebastiano.indexino.core.segment

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CallSiteRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.SymbolRecord
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Writes one immutable segment file in a single sequential pass. Keys must arrive in strictly
 * increasing unsigned-lexicographic order — the natural fast path for pre-sorted bulk input and for
 * streaming an existing sorted store. Derived lookup tables (symbol names/files, call files) are
 * derived while the primary table streams and written after it, so the whole file is produced with
 * sequential writes plus one final [FileChannel.force].
 */
internal class SegmentFileWriter
private constructor(path: Path, maxBlockEntries: Int, maxBlockBytes: Int) : AutoCloseable {
    private val channel =
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    private val sink = SegmentSink(channel)
    private val primary =
        TableWriterState(SegmentFormat.PRIMARY_TABLE, true, maxBlockEntries, maxBlockBytes)
    private val derived = DerivedTableState(maxBlockEntries, maxBlockBytes)
    private var closed = false

    init {
        val header = ByteArrayOutputStream(SegmentFormat.HEADER_SIZE)
        header.write(SegmentFormat.MAGIC)
        header.write(SegmentFormat.FORMAT_VERSION)
        header.write(0) // table count is authoritative in the trailer-adjacent directory
        header.write(0) // flags u16
        header.write(0)
        header.write(0) // reserved u32
        header.write(0)
        header.write(0)
        header.write(0)
        writeLong(header, 0L)
        writeLong(header, 0L)
        val headerBytes = header.toByteArray()
        check(headerBytes.size == SegmentFormat.HEADER_SIZE)
        sink.write(headerBytes)
    }

    fun append(key: CodeIndexKey, record: CodeIndexRecord) {
        appendEntry(SegmentKeys.utf8(key.value), CodeIndexRecordCodec.encode(record))
        derived.collect(key, record)
    }

    fun appendTombstone(key: CodeIndexKey) {
        appendEntry(SegmentKeys.utf8(key.value), null)
    }

    private fun appendEntry(keyBytes: ByteArray, value: ByteArray?) {
        check(!closed) { "Segment writer is closed" }
        val lastKey = primary.lastKey
        if (lastKey != null && SegmentFormat.compareKeys(keyBytes, lastKey) <= 0) {
            error("Segment keys must be strictly increasing: ${SegmentKeys.decode(keyBytes)}")
        }
        primary.append(keyBytes, value, sink)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val regions = listOf(primary.finish(sink)) + derived.writeTables(sink)
            writeDirectory(regions)
            writeTrailer()
            channel.force(true)
        } finally {
            channel.close()
        }
    }

    private fun writeDirectory(regions: List<TableRegion>) {
        check(regions.size <= MAX_TABLES) { "Too many segment tables: ${regions.size}" }
        val directory = ByteArrayOutputStream()
        writeVarUint(regions.size, directory)
        regions.forEach { region ->
            val nameBytes = SegmentKeys.utf8(region.name)
            check(nameBytes.size <= MAX_TABLE_NAME_BYTES)
            directory.write(nameBytes.size)
            directory.write(nameBytes)
            writeLong(directory, region.blocksStart)
            writeLong(directory, region.indexStart)
            writeLong(directory, region.tableEnd)
            writeInt(directory, region.entryCount)
            directory.write(region.flags)
        }
        directoryOffset = sink.write(directory.toByteArray())
    }

    private var directoryOffset = 0L

    private fun writeTrailer() {
        val trailer = ByteArrayOutputStream(SegmentFormat.TRAILER_SIZE)
        trailer.write(SegmentFormat.MAGIC)
        trailer.write(SegmentFormat.FORMAT_VERSION)
        trailer.write(0)
        trailer.write(0)
        trailer.write(0)
        writeInt(trailer, sink.checksum.value.toInt())
        writeLong(trailer, directoryOffset)
        writeLong(trailer, 0L)
        val trailerBytes = trailer.toByteArray()
        check(trailerBytes.size == SegmentFormat.TRAILER_SIZE)
        sink.write(trailerBytes)
    }

    internal companion object {
        internal const val DEFAULT_MAX_BLOCK_ENTRIES = 64
        internal const val DEFAULT_MAX_BLOCK_BYTES = 8192
        private const val MAX_TABLES = 255
        private const val MAX_TABLE_NAME_BYTES = 255

        /** Term-to-primary-key rows of the derived symbol-name index. */
        fun symbolTerms(record: SymbolRecord): Set<String> = buildSet {
            add(record.name)
            add(record.fqn)
            addAll(record.aliases)
        }

        /** File index term, byte-compatible with the Xodus `SymbolFiles`/`CallFiles` keys. */
        fun fileTerm(originId: String, relativeFile: String): String =
            "${originId.length}:$originId$relativeFile"

        fun open(
            path: Path,
            maxBlockEntries: Int = DEFAULT_MAX_BLOCK_ENTRIES,
            maxBlockBytes: Int = DEFAULT_MAX_BLOCK_BYTES,
        ): SegmentFileWriter = SegmentFileWriter(path, maxBlockEntries, maxBlockBytes)
    }
}

internal fun writeVarUint(value: Int, out: ByteArrayOutputStream) {
    var remaining = value
    while (true) {
        val byte = remaining and VAR_UINT_PAYLOAD
        remaining = remaining ushr VAR_UINT_SHIFT
        if (remaining == 0) {
            out.write(byte)
            return
        }
        out.write(byte or VAR_UINT_CONTINUATION)
    }
}

internal fun writeInt(out: ByteArrayOutputStream, value: Int) {
    repeat(Int.SIZE_BYTES) { byte -> out.write((value ushr (byte * BITS_PER_BYTE)) and BYTE_MASK) }
}

internal fun writeLong(out: ByteArrayOutputStream, value: Long) {
    writeInt(out, value.toInt())
    writeInt(out, (value ushr Int.SIZE_BITS).toInt())
}

private const val BITS_PER_BYTE: Int = 8

private const val VAR_UINT_PAYLOAD = 0x7f
private const val VAR_UINT_CONTINUATION = 0x80
private const val VAR_UINT_SHIFT = 7
private const val BYTE_MASK = 0xff

internal class TableRegion
internal constructor(
    internal val name: String,
    internal val blocksStart: Long,
    internal val indexStart: Long,
    internal val tableEnd: Long,
    internal val entryCount: Int,
    internal val flags: Int,
)

/** Sequential, checksummed output positioned exactly after the previous write. */
private class SegmentSink(private val channel: FileChannel) {
    var offset = 0L
        private set

    val checksum = CRC32()

    fun write(bytes: ByteArray): Long {
        val start = offset
        offset += bytes.size
        checksum.update(bytes, 0, bytes.size)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer, start + buffer.position())
        return start
    }
}

/** Buffers one sorted table's blocks, flushing each to the sink at a block boundary. */
private class TableWriterState(
    private val name: String,
    private val wantsBloom: Boolean,
    private val maxBlockEntries: Int,
    private val maxBlockBytes: Int,
) {
    private val blockFirstKeys = mutableListOf<ByteArray>()
    private val blockStartOffsets = mutableListOf<Long>()
    private var bloomHashes = LongArray(INITIAL_HASH_CAPACITY)
    private var bloomHashCount = 0
    var lastKey: ByteArray? = null
        private set

    var entryCount = 0
        private set

    private val currentBlock = ByteArrayOutputStream()
    private var currentBlockEntryCount = 0
    private var currentBlockFirstKey: ByteArray? = null
    private var currentBlockStart = -1L

    fun append(keyBytes: ByteArray, value: ByteArray?, sink: SegmentSink) {
        if (currentBlockEntryCount == 0) {
            currentBlockFirstKey = keyBytes
            currentBlockStart = sink.offset
        }
        val sharedPrefix =
            if (currentBlockEntryCount == 0) 0 else commonPrefixLength(lastKey, keyBytes)
        writeVarUint(sharedPrefix, currentBlock)
        writeVarUint(keyBytes.size - sharedPrefix, currentBlock)
        currentBlock.write(keyBytes, sharedPrefix, keyBytes.size - sharedPrefix)
        if (value == null) {
            writeVarUint(TOMBSTONE_VALUE_SPEC, currentBlock)
        } else {
            writeVarUint(value.size shl 1, currentBlock)
            currentBlock.write(value)
        }
        if (wantsBloom) addBloomHash(keyBytes)
        lastKey = keyBytes
        entryCount++
        currentBlockEntryCount++
        if (currentBlockEntryCount >= maxBlockEntries || currentBlock.size() >= maxBlockBytes) {
            flushCurrentBlock(sink)
        }
    }

    fun finish(sink: SegmentSink): TableRegion {
        flushCurrentBlock(sink)
        val blocksStart = blockStartOffsets.firstOrNull() ?: sink.offset
        val indexStart = sink.offset
        val index = ByteArrayOutputStream()
        writeVarUint(blockFirstKeys.size, index)
        for (ordinal in blockFirstKeys.indices) {
            writeLong(index, blockStartOffsets[ordinal])
            val firstKey = blockFirstKeys[ordinal]
            writeVarUint(firstKey.size, index)
            index.write(firstKey)
        }
        sink.write(index.toByteArray())
        var flags = 0
        if (wantsBloom && bloomHashCount > 0) {
            val bloom = ByteArrayOutputStream()
            val words = buildBloom()
            writeInt(bloom, words.size)
            words.forEach { word -> writeLong(bloom, word) }
            sink.write(bloom.toByteArray())
            flags = SegmentFormat.TABLE_FLAG_BLOOM
        }
        return TableRegion(name, blocksStart, indexStart, sink.offset, entryCount, flags)
    }

    private fun flushCurrentBlock(sink: SegmentSink) {
        if (currentBlockEntryCount == 0) return
        sink.write(currentBlock.toByteArray())
        blockFirstKeys += checkNotNull(currentBlockFirstKey)
        blockStartOffsets += currentBlockStart
        currentBlockFirstKey = null
        currentBlockEntryCount = 0
        currentBlock.reset()
        currentBlockStart = -1L
    }

    private fun addBloomHash(keyBytes: ByteArray) {
        if (bloomHashCount == bloomHashes.size) {
            bloomHashes = bloomHashes.copyOf(bloomHashes.size * 2)
        }
        bloomHashes[bloomHashCount++] = SegmentFormat.keyHash(keyBytes)
    }

    private fun buildBloom(): LongArray {
        // Both writer and reader derive probe positions modulo wordCount * 64, so the bit count
        // must stay word-aligned.
        val targetBits =
            maxOf(entryCount * SegmentFormat.BLOOM_BITS_PER_ENTRY.toLong(), MIN_BLOOM_BITS)
        val bitCount =
            ((targetBits + SegmentFormat.BLOOM_WORD_BITS - 1) / SegmentFormat.BLOOM_WORD_BITS) *
                SegmentFormat.BLOOM_WORD_BITS
        val words = LongArray((bitCount / SegmentFormat.BLOOM_WORD_BITS).toInt())
        for (index in 0 until bloomHashCount) {
            val hash = bloomHashes[index]
            for (probe in 0 until SegmentFormat.BLOOM_PROBES) {
                val position =
                    ((SegmentFormat.mix64(hash + probe * SegmentFormat.BLOOM_STRIDE) % bitCount) +
                        bitCount) % bitCount
                val word = (position / SegmentFormat.BLOOM_WORD_BITS).toInt()
                words[word] =
                    words[word] or (1L shl (position % SegmentFormat.BLOOM_WORD_BITS).toInt())
            }
        }
        return words
    }

    private fun commonPrefixLength(left: ByteArray?, right: ByteArray): Int {
        if (left == null) return 0
        val limit = minOf(left.size, right.size)
        var index = 0
        while (index < limit && left[index] == right[index]) index++
        return index
    }

    private companion object {
        private const val INITIAL_HASH_CAPACITY = 1024
        private const val TOMBSTONE_VALUE_SPEC = 1
        private const val MIN_BLOOM_BITS = 64L
    }
}

/** Collects derived symbol-name/file and call-file rows while the primary table streams. */
private class DerivedTableState(private val maxBlockEntries: Int, private val maxBlockBytes: Int) {
    private val symbolNames = mutableListOf<Pair<String, String>>()
    private val symbolFiles = mutableListOf<Pair<String, String>>()
    private val callFiles = mutableListOf<Pair<String, String>>()

    fun collect(key: CodeIndexKey, record: CodeIndexRecord) {
        when {
            key.value.startsWith(SYMBOL_PREFIX) && record is SymbolRecord -> {
                val primaryKey = key.value
                SegmentFileWriter.symbolTerms(record).forEach { term ->
                    symbolNames += term to primaryKey
                }
                symbolFiles +=
                    SegmentFileWriter.fileTerm(record.originId, record.relativeFile) to primaryKey
            }
            key.value.startsWith(CALL_PREFIX) && record is CallSiteRecord -> {
                callFiles +=
                    SegmentFileWriter.fileTerm(record.originId, record.relativeFile) to key.value
            }
        }
    }

    fun writeTables(sink: SegmentSink): List<TableRegion> =
        listOf(
                SegmentFormat.SYMBOL_NAMES_TABLE to symbolNames,
                SegmentFormat.SYMBOL_FILES_TABLE to symbolFiles,
                SegmentFormat.CALL_FILES_TABLE to callFiles,
            )
            .filter { (_, rows) -> rows.isNotEmpty() }
            .map { (name, rows) -> writeCompositeTable(name, rows, sink) }

    private fun writeCompositeTable(
        name: String,
        rows: List<Pair<String, String>>,
        sink: SegmentSink,
    ): TableRegion {
        val compositeKeys =
            rows
                .map { (term, primaryKey) -> SegmentFormat.compositeKey(term, primaryKey) }
                .sortedWith { left, right -> SegmentFormat.compareKeys(left, right) }
        val state = TableWriterState(name, false, maxBlockEntries, maxBlockBytes)
        compositeKeys.forEach { composite -> state.append(composite, ByteArray(0), sink) }
        return state.finish(sink)
    }

    private companion object {
        private const val SYMBOL_PREFIX = "sym:"
        private const val CALL_PREFIX = "call:"
    }
}
