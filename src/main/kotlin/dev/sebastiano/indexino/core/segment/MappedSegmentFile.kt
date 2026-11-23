package dev.sebastiano.indexino.core.segment

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * A segment file mapped read-only through one shared [Arena]. Closing the file closes the arena
 * and unmaps every table; Windows callers must therefore close before deleting or renaming the
 * underlying file. Table views stay valid only until [close].
 */
internal class MappedSegmentFile
private constructor(
    private val arena: Arena,
    private val tables: Map<String, MappedTable>,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    val primaryTable: MappedTable =
        tables[SegmentFormat.PRIMARY_TABLE]
            ?: throw IOException("Segment file has no primary table")

    fun table(name: String): MappedTable? = tables[name]

    override fun close() {
        // Shared arenas reject a second close; guard so callers can layer ownership safely.
        if (!closed.compareAndSet(false, true)) return
        arena.close()
    }

    internal companion object {
        fun open(path: Path): MappedSegmentFile {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val arena = Arena.ofShared()
                try {
                    val segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena).asReadOnly()
                    validate(segment, size)
                    return MappedSegmentFile(arena, readTables(segment, size))
                } catch (failure: Throwable) {
                    runCatching { arena.close() }
                    throw failure
                }
            }
        }

        private fun validate(segment: MemorySegment, size: Long) {
            if (size < SegmentFormat.HEADER_SIZE + SegmentFormat.TRAILER_SIZE.toLong()) {
                throw IOException("Segment file is too small: $size bytes")
            }
            checkMagic(segment, 0)
            checkMagic(segment, size - SegmentFormat.TRAILER_SIZE)
            val version = segment.get(SegmentFormat.JAVA_BYTE, SegmentFormat.VERSION_OFFSET)
            if (version != SegmentFormat.FORMAT_VERSION.toByte()) {
                throw IOException("Unsupported segment format version: $version")
            }
            val directoryOffset =
                SegmentFormat.readLong(segment, trailerField(segment, SegmentFormat.TRAILER_DIRECTORY_OFFSET))
            if (
                directoryOffset < SegmentFormat.HEADER_SIZE ||
                directoryOffset > size - SegmentFormat.TRAILER_SIZE
            ) {
                throw IOException("Segment directory offset is out of bounds: $directoryOffset")
            }
            if (size <= SegmentFormat.EAGER_VERIFY_BYTES) verifyChecksum(segment, size)
        }

        private fun trailerField(segment: MemorySegment, offset: Long): Long =
            segment.byteSize() - SegmentFormat.TRAILER_SIZE + offset

        private fun checkMagic(segment: MemorySegment, offset: Long) {
            for (index in SegmentFormat.MAGIC.indices) {
                val expected = SegmentFormat.MAGIC[index]
                val actual = segment.get(SegmentFormat.JAVA_BYTE, offset + index)
                if (expected != actual) throw IOException("Segment magic mismatch at $offset")
            }
        }

        private fun verifyChecksum(segment: MemorySegment, size: Long) {
            val expected =
                SegmentFormat.readInt(segment, size - SegmentFormat.TRAILER_SIZE + SegmentFormat.TRAILER_CHECKSUM_OFFSET)
                    .toLong() and SegmentFormat.INT_MASK
            val buffer = segment.asByteBuffer()
            buffer.limit((size - SegmentFormat.TRAILER_SIZE).toInt())
            val checksum = CRC32()
            checksum.update(buffer)
            if (checksum.value != expected) {
                throw IOException("Segment checksum mismatch: expected $expected, was ${checksum.value}")
            }
        }

        private fun readTables(segment: MemorySegment, size: Long): Map<String, MappedTable> {
            val directoryOffset =
                SegmentFormat.readLong(segment, size - SegmentFormat.TRAILER_SIZE + SegmentFormat.TRAILER_DIRECTORY_OFFSET)
            var position = directoryOffset
            val count = SegmentFormat.readVarUint(segment, position)
            position = count.second
            if (count.first > SegmentFormat.MAX_TABLES) {
                throw IOException("Segment declares too many tables: ${count.first}")
            }
            val tables = LinkedHashMap<String, MappedTable>(count.first)
            repeat(count.first) {
                val nameLength =
                    segment.get(SegmentFormat.JAVA_BYTE, position).toLong() and SegmentFormat.BYTE_MASK.toLong()
                position += 1
                if (nameLength == 0L || nameLength > SegmentFormat.MAX_TABLE_NAME_BYTES) {
                    throw IOException("Invalid segment table name length: $nameLength")
                }
                val nameBytes = segment.asSlice(position, nameLength).toArray(SegmentFormat.JAVA_BYTE)
                position += nameLength
                val name = SegmentKeys.decode(nameBytes)
                val blocksStart = SegmentFormat.readLong(segment, position)
                val indexStart = SegmentFormat.readLong(segment, position + SegmentFormat.LONG_SIZE)
                val tableEnd =
                    SegmentFormat.readLong(segment, position + SegmentFormat.LONG_SIZE * 2)
                val entryCount =
                    SegmentFormat.readInt(segment, position + SegmentFormat.LONG_SIZE * 3)
                        .toLong() and SegmentFormat.INT_MASK
                val flags =
                    segment
                        .get(
                            SegmentFormat.JAVA_BYTE,
                            position + SegmentFormat.LONG_SIZE * 3 + SegmentFormat.INT_SIZE,
                        )
                        .toLong() and SegmentFormat.BYTE_MASK.toLong()
                position += SegmentFormat.LONG_SIZE * 3 + SegmentFormat.INT_SIZE + 1
                if (
                    blocksStart < SegmentFormat.HEADER_SIZE ||
                    indexStart < blocksStart ||
                    tableEnd < indexStart ||
                    tableEnd > directoryOffset
                ) {
                    throw IOException("Segment table '$name' region is out of bounds")
                }
                val table = MappedTable(segment, blocksStart, indexStart, tableEnd, entryCount, flags)
                if (tables.put(name, table) != null) {
                    throw IOException("Duplicate segment table name: $name")
                }
            }
            return tables
        }
    }
}

/** One mapped, block-indexed sorted table. All offsets are absolute file positions. */
internal class MappedTable
internal constructor(
    private val segment: MemorySegment,
    private val blocksStart: Long,
    private val indexStart: Long,
    private val tableEnd: Long,
    override val entryCount: Long,
    flags: Long,
) : SegmentTable() {
    private val hasBloom = flags and SegmentFormat.TABLE_FLAG_BLOOM.toLong() != 0L
    private val blockIndex: BlockIndex by lazy(LazyThreadSafetyMode.NONE) { parseBlockIndex() }

    override fun get(key: String): SegmentEntry? {
        val keyBytes = SegmentKeys.utf8(key)
        if (hasBloom && !blockIndex.mayContain(keyBytes)) return null
        val block = blockIndex.locate(keyBytes) ?: return null
        var position = block.startOffset
        var previousKey: ByteArray? = null
        while (position < block.endOffset) {
            val decoded = decodeEntry(position, previousKey)
            position = decoded.nextPosition
            val comparison = SegmentFormat.compareKeys(decoded.entry.keyBytes, keyBytes)
            if (comparison == 0) return decoded.entry
            if (comparison > 0) return null
            previousKey = decoded.entry.keyBytes
        }
        return null
    }

    override fun cursor(fromKeyInclusive: ByteArray?): SegmentTableCursor {
        if (blockIndex.blockCount == 0) return EmptyCursor
        if (fromKeyInclusive == null) {
            return tableCursor(0, blockIndex.blockStart(0), null)
        }
        val block = blockIndex.locate(fromKeyInclusive) ?: return tableCursor(0, blockIndex.blockStart(0), null)
        var position = block.startOffset
        var previousKey: ByteArray? = null
        var ordinal = block.ordinal
        var endOffset = block.endOffset
        while (true) {
            if (position >= endOffset) {
                ordinal++
                if (ordinal >= blockIndex.blockCount) return EmptyCursor
                position = blockIndex.blockStart(ordinal)
                endOffset = blockIndex.blockEnd(ordinal)
                previousKey = null
                continue
            }
            val decoded = decodeEntry(position, previousKey)
            if (SegmentFormat.compareKeys(decoded.entry.keyBytes, fromKeyInclusive) >= 0) {
                return tableCursor(ordinal, position, previousKey)
            }
            position = decoded.nextPosition
            previousKey = decoded.entry.keyBytes
        }
    }

    private fun tableCursor(ordinal: Int, position: Long, previousKey: ByteArray?): SegmentTableCursor =
        MappedTableCursor(segment, blockIndex, ordinal, position, previousKey, blockIndex.blockEnd(ordinal))

    private fun decodeEntry(position: Long, previousKey: ByteArray?): DecodedEntry {
        var offset = position
        val shared = SegmentFormat.readVarUint(segment, offset)
        offset = shared.second
        val suffix = SegmentFormat.readVarUint(segment, offset)
        offset = suffix.second
        val suffixBytes = segment.asSlice(offset, suffix.first.toLong()).toArray(SegmentFormat.JAVA_BYTE)
        offset += suffix.first
        val valueSpec = SegmentFormat.readVarUint(segment, offset)
        offset = valueSpec.second
        val tombstone = valueSpec.first and SegmentFormat.TOMBSTONE_BIT != 0
        val valueLength = valueSpec.first ushr 1
        val keyBytes = ByteArray(shared.first + suffixBytes.size)
        if (shared.first > 0) {
            val prior = checkNotNull(previousKey) { "Segment entry lacks prefix context" }
            require(prior.size >= shared.first) { "Segment entry prefix exceeds prior key" }
            prior.copyInto(keyBytes, 0, 0, shared.first)
        }
        suffixBytes.copyInto(keyBytes, shared.first)
        val value =
            if (tombstone) null
            else segment.asSlice(offset, valueLength.toLong()).toArray(SegmentFormat.JAVA_BYTE)
        return DecodedEntry(SegmentEntry(keyBytes, value, tombstone), offset + valueLength)
    }

    private fun parseBlockIndex(): BlockIndex {
        var position = indexStart
        val count = SegmentFormat.readVarUint(segment, position)
        position = count.second
        val starts = LongArray(count.first)
        val firstKeys = arrayOfNulls<ByteArray>(count.first)
        repeat(count.first) { ordinal ->
            starts[ordinal] = SegmentFormat.readLong(segment, position)
            position += SegmentFormat.LONG_SIZE
            val keyLength = SegmentFormat.readVarUint(segment, position)
            position = keyLength.second
            firstKeys[ordinal] = segment.asSlice(position, keyLength.first.toLong()).toArray(SegmentFormat.JAVA_BYTE)
            position += keyLength.first
        }
        // The bloom filter, when the table flags announce one, starts exactly after the index.
        val bloomOffset = if (hasBloom) position else -1L
        return BlockIndex(segment, starts, firstKeys.requireNoNulls(), indexStart, bloomOffset)
    }

    private class DecodedEntry(val entry: SegmentEntry, val nextPosition: Long)

    private object EmptyCursor : SegmentTableCursor() {
        override fun next(): SegmentEntry? = null
    }

    /** Parsed block index: block start offsets, full first keys, and the optional bloom. */
    private class BlockIndex(
        private val segment: MemorySegment,
        private val starts: LongArray,
        private val firstKeys: Array<ByteArray>,
        private val indexStart: Long,
        private val bloomOffset: Long,
    ) {
        val blockCount: Int get() = starts.size

        fun blockStart(ordinal: Int): Long = starts[ordinal]

        /** Data of the last block ends where the block index section begins. */
        fun blockEnd(ordinal: Int): Long =
            if (ordinal + 1 < starts.size) starts[ordinal + 1] else indexStart

        fun mayContain(key: ByteArray): Boolean {
            val bloom = bloom() ?: return true
            val bitCount = bloom.size.toLong() * SegmentFormat.BLOOM_WORD_BITS
            val hash = SegmentFormat.keyHash(key)
            for (probe in 0 until SegmentFormat.BLOOM_PROBES) {
                val position =
                    ((SegmentFormat.mix64(hash + probe * SegmentFormat.BLOOM_STRIDE) % bitCount) + bitCount) % bitCount
                val word = bloom[(position / SegmentFormat.BLOOM_WORD_BITS).toInt()]
                if (word and (1L shl (position % SegmentFormat.BLOOM_WORD_BITS).toInt()) == 0L) return false
            }
            return true
        }

        private var bloomWords: LongArray? = null
        private var bloomParsed = false

        private fun bloom(): LongArray? {
            if (bloomOffset < 0L) return null
            if (!bloomParsed) {
                val wordCount =
                    SegmentFormat.readInt(segment, bloomOffset).toLong() and SegmentFormat.INT_MASK
                val words = LongArray(wordCount.toInt())
                for (index in 0 until wordCount) {
                    words[index.toInt()] =
                        SegmentFormat.readLong(segment, bloomOffset + SegmentFormat.INT_SIZE + index * SegmentFormat.LONG_SIZE)
                }
                bloomWords = if (wordCount == 0L) null else words
                bloomParsed = true
            }
            return bloomWords
        }

        fun locate(key: ByteArray): BlockLocation? {
            var low = 0
            var high = starts.size - 1
            var result = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (SegmentFormat.compareKeys(firstKeys[middle], key) <= 0) {
                    result = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            if (result < 0) return null
            return BlockLocation(result, starts[result], blockEnd(result))
        }

        class BlockLocation(val ordinal: Int, val startOffset: Long, val endOffset: Long)
    }

    private class MappedTableCursor(
        private val segment: MemorySegment,
        private val blockIndex: BlockIndex,
        private var blockOrdinal: Int,
        private var position: Long,
        private var previousKey: ByteArray?,
        private var blockEnd: Long,
    ) : SegmentTableCursor() {
        override fun next(): SegmentEntry? {
            while (position >= blockEnd) {
                blockOrdinal++
                if (blockOrdinal >= blockIndex.blockCount) return null
                position = blockIndex.blockStart(blockOrdinal)
                blockEnd = blockIndex.blockEnd(blockOrdinal)
                previousKey = null
            }
            var offset = position
            val shared = SegmentFormat.readVarUint(segment, offset)
            offset = shared.second
            val suffix = SegmentFormat.readVarUint(segment, offset)
            offset = suffix.second
            val suffixBytes = segment.asSlice(offset, suffix.first.toLong()).toArray(SegmentFormat.JAVA_BYTE)
            offset += suffix.first
            val valueSpec = SegmentFormat.readVarUint(segment, offset)
            offset = valueSpec.second
            val tombstone = valueSpec.first and SegmentFormat.TOMBSTONE_BIT != 0
            val valueLength = valueSpec.first ushr 1
            val keyBytes = ByteArray(shared.first + suffixBytes.size)
            if (shared.first > 0) {
                val prior = checkNotNull(previousKey) { "Segment entry lacks prefix context" }
                prior.copyInto(keyBytes, 0, 0, shared.first)
            }
            suffixBytes.copyInto(keyBytes, shared.first)
            val value =
                if (tombstone) null
                else segment.asSlice(offset, valueLength.toLong()).toArray(SegmentFormat.JAVA_BYTE)
            position = offset + valueLength
            previousKey = keyBytes
            return SegmentEntry(keyBytes, value, tombstone)
        }
    }
}
