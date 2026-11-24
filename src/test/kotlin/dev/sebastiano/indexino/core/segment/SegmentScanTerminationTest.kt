package dev.sebastiano.indexino.core.segment

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Derived-table lookups must be bounded: the scan stops as soon as the composite key leaves the
 * term's matching range or the visitor returns false, instead of decoding the rest of the table.
 */
class SegmentScanTerminationTest {
    @Test
    fun `exact name scan stops when the term range ends`() {
        // The production cursor is positioned at the first key >= the term, so the scan sees
        // only the matching range and what follows it.
        val tail = (0 until 500).map { index -> entry("Rest$index\u0000sym:r$index") }
        val cursor = CountingCursor(listOf(entry("Beta\u0000sym:b")) + tail)
        val visited = mutableListOf<String>()
        scanNameTerms(cursor, "Beta", prefix = false) { term, primaryKey ->
            visited += primaryKey
            true
        }
        assertEquals(listOf("sym:b"), visited)
        assertEquals(
            2,
            cursor.nextCalls,
            "Exact scan decoded ${cursor.nextCalls} entries instead of stopping after the range",
        )
    }

    @Test
    fun `exact name scan stops when the visitor declines the first match`() {
        val cursor =
            CountingCursor(
                listOf(
                    entry("Alpha\u0000sym:a"),
                    entry("Beta\u0000sym:b"),
                    entry("Gamma\u0000sym:c"),
                )
            )
        scanNameTerms(cursor, "Alpha", prefix = false) { _, _ -> false }
        assertEquals(
            1,
            cursor.nextCalls,
            "Declined match decoded ${cursor.nextCalls} entries instead of stopping",
        )
    }

    @Test
    fun `prefix name scan stops when terms leave the prefix`() {
        val cursor =
            CountingCursor(
                listOf(
                    entry("Pan\u0000sym:a"),
                    entry("Panel\u0000sym:b"),
                    entry("PanelKt\u0000sym:c"),
                    entry("Qrt\u0000sym:d"),
                    entry("Zzz\u0000sym:e"),
                )
            )
        val visited = mutableListOf<String>()
        scanNameTerms(cursor, "Pan", prefix = true) { _, primaryKey ->
            visited += primaryKey
            true
        }
        assertEquals(listOf("sym:a", "sym:b", "sym:c"), visited)
        assertEquals(
            4,
            cursor.nextCalls,
            "Prefix scan decoded ${cursor.nextCalls} entries instead of stopping after the range",
        )
    }

    @Test
    fun `composite term scan stops when the term range ends`() {
        val cursor =
            CountingCursor(
                listOf(
                    entry("3:oneFile.kt\u0000sym:a"),
                    entry("3:oneFile.kt\u0000sym:b"),
                    entry("4:other.kt\u0000sym:c"),
                    entry("5:rest.kt\u0000sym:d"),
                )
            )
        val visited = mutableListOf<String>()
        scanCompositeTerm(cursor, SegmentKeys.utf8("3:oneFile.kt")) { primaryKey ->
            visited += primaryKey
            true
        }
        assertEquals(listOf("sym:a", "sym:b"), visited)
        assertEquals(
            3,
            cursor.nextCalls,
            "File scan decoded ${cursor.nextCalls} entries instead of stopping after the range",
        )
    }

    @Test
    fun `composite term scan stops when the visitor declines`() {
        val cursor =
            CountingCursor(
                listOf(
                    entry("3:oneFile.kt\u0000sym:a"),
                    entry("3:oneFile.kt\u0000sym:b"),
                    entry("3:oneFile.kt\u0000sym:c"),
                )
            )
        scanCompositeTerm(cursor, SegmentKeys.utf8("3:oneFile.kt")) { _ -> false }
        assertEquals(
            1,
            cursor.nextCalls,
            "Declined match decoded ${cursor.nextCalls} entries instead of stopping",
        )
    }

    private fun entry(compositeKey: String): SegmentEntry =
        SegmentEntry(SegmentKeys.utf8(compositeKey), ByteArray(0), tombstone = false)

    private class CountingCursor(entries: List<SegmentEntry>) : SegmentTableCursor() {
        private val remaining = ArrayDeque(entries)

        var nextCalls: Int = 0
            private set

        override fun next(): SegmentEntry? {
            nextCalls++
            return remaining.removeFirstOrNull()
        }
    }
}
