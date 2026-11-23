package dev.sebastiano.indexino.core.segment

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.Arrays

/**
 * Immutable segment table format (storage format version 2 packs).
 *
 * A segment file is one immutable, self-contained sorted table set:
 *
 * ```
 * [header 32 B][table 0: data blocks | block index | bloom][table 1 ...][directory][trailer 32 B]
 * ```
 *
 * All integers are little-endian. Keys are UTF-8 bytes compared by **unsigned lexicographic
 * order** (the same ordering Xodus `StringBinding` uses for persisted keys). Data entries are
 * prefix-compressed against the previous key *of their block*; each block index entry stores the
 * full first key so any block decodes without prior context. A bloom filter (8 bits per entry,
 * 4 probes) accelerates point lookups on the primary table. Tombstones are entries whose value
 * length is zero with the tombstone flag set; a tombstone in a newer segment shadows the same
 * key in older segments. The trailer carries a CRC-32 over the whole file prefix; mapped opens
 * verify it eagerly only up to [EAGER_VERIFY_BYTES] because published packs are additionally
 * named by the SHA-256 of their bytes.
 */
internal object SegmentFormat {
    /** ASCII magic at the first and last eight bytes of every segment file. */
    val MAGIC: ByteArray = "IDXSEGv1".toByteArray(Charsets.US_ASCII)

    const val FORMAT_VERSION: Int = 1

    const val HEADER_SIZE: Int = 32
    const val TRAILER_SIZE: Int = 32

    /** Point-lookup bloom filters use this many bits per entry. */
    const val BLOOM_BITS_PER_ENTRY: Int = 8

    /** Eager CRC verification bound; larger packs rely on their content-addressed names. */
    const val EAGER_VERIFY_BYTES: Long = 64L * 1024L * 1024L

    const val PRIMARY_TABLE: String = "primary"
    const val SYMBOL_NAMES_TABLE: String = "symnames"
    const val SYMBOL_FILES_TABLE: String = "symfiles"
    const val CALL_FILES_TABLE: String = "callfiles"

    const val TABLE_FLAG_BLOOM: Int = 1

    /** Tombstone flag bit of the encoded entry value spec. */
    const val TOMBSTONE_BIT: Int = 1

    /** Separator between a derived-table term and its primary key inside composite keys. */
    private const val COMPOSITE_KEY_SEPARATOR: Int = 0x00

    private const val VAR_UINT_PAYLOAD: Int = 0x7f
    private const val VAR_UINT_CONTINUATION: Int = 0x80
    private const val VAR_UINT_SHIFT: Int = 7

    const val VERSION_OFFSET: Long = 8
    const val TRAILER_CHECKSUM_OFFSET: Long = 12
    const val TRAILER_DIRECTORY_OFFSET: Long = 16
    const val BYTE_MASK: Int = 0xff
    const val INT_MASK: Long = 0xffffffffL
    const val LONG_SIZE: Int = Long.SIZE_BYTES
    const val INT_SIZE: Int = Int.SIZE_BYTES
    const val BLOOM_WORD_BITS: Long = 64
    const val BLOOM_PROBES: Int = 4
    const val BLOOM_STRIDE: Long = -0x61c8864680b583ebL // 0x9e3779b97f4a7c15 unsigned
    const val MAX_TABLES: Int = 255
    const val MAX_TABLE_NAME_BYTES: Long = 255

    val JAVA_BYTE: ValueLayout.OfByte = ValueLayout.JAVA_BYTE

    fun compositeKey(term: String, primaryKey: String): ByteArray {
        val termBytes = SegmentKeys.utf8(term)
        val keyBytes = SegmentKeys.utf8(primaryKey)
        val composite = ByteArray(termBytes.size + 1 + keyBytes.size)
        termBytes.copyInto(composite)
        composite[termBytes.size] = COMPOSITE_KEY_SEPARATOR.toByte()
        keyBytes.copyInto(composite, termBytes.size + 1)
        return composite
    }

    fun compareKeys(left: ByteArray, right: ByteArray): Int = Arrays.compareUnsigned(left, right)

    /** FNV-1a 64-bit hash of the key bytes; bloom probes derive from it. */
    fun keyHash(key: ByteArray): Long {
        var hash = -0x340D61667E5D869DL // 0xcbf29ce484222325 as the unsigned FNV-1a seed
        for (byte in key) {
            hash = hash xor (byte.toLong() and BYTE_MASK.toLong())
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** SplitMix64 finalizer used to derive independent bloom probe positions. */
    fun mix64(value: Long): Long {
        var z = value
        z += -0x61c8864680b583ebL // golden gamma, 0x9e3779b97f4a7c15 unsigned
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Reads an unsigned LEB128 varint; returns (value, next absolute offset). */
    fun readVarUint(segment: MemorySegment, offset: Long): Pair<Int, Long> {
        var value = 0
        var shift = 0
        var position = offset
        while (true) {
            val byte = segment.get(JAVA_BYTE, position).toLong() and BYTE_MASK.toLong()
            position++
            value = value or ((byte.toInt() and VAR_UINT_PAYLOAD) shl shift)
            if (byte.toInt() and VAR_UINT_CONTINUATION == 0) return value to position
            shift += VAR_UINT_SHIFT
        }
    }

    fun readLong(segment: MemorySegment, offset: Long): Long =
        segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)

    fun readInt(segment: MemorySegment, offset: Long): Int =
        segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset)
}

/** UTF-8 key encoding shared by the segment writer and reader. */
internal object SegmentKeys {
    fun utf8(key: String): ByteArray = key.toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): String = bytes.decodeToString()
}

/** One decoded entry: a full key with either a value or a tombstone marker. */
internal class SegmentEntry internal constructor(
    val keyBytes: ByteArray,
    val value: ByteArray?,
    val tombstone: Boolean,
) {
    val key: String by lazy(LazyThreadSafetyMode.NONE) { SegmentKeys.decode(keyBytes) }
}

/** Unsigned-lexicographic cursor over one table region of a mapped segment file. */
internal abstract class SegmentTableCursor protected constructor() {
    /** Advances to the next entry, or returns null at the end of the table. */
    abstract fun next(): SegmentEntry?

    fun asSequence(): Sequence<SegmentEntry> = sequence {
        while (true) {
            val entry = next() ?: break
            yield(entry)
        }
    }
}

/** A named sorted table region inside a [MappedSegmentFile]. */
internal abstract class SegmentTable protected constructor() {
    abstract val entryCount: Long

    /** Point lookup by key; returns tombstone entries as-is. */
    abstract fun get(key: String): SegmentEntry?

    abstract fun cursor(fromKeyInclusive: ByteArray? = null): SegmentTableCursor
}
