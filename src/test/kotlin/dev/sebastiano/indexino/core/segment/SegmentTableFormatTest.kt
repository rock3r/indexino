package dev.sebastiano.indexino.core.segment

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecordCodec
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.record.MetaIndexerVersionRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentTableFormatTest {
    private lateinit var tempDir: Path
    private lateinit var segmentPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("segment-test-")
        segmentPath = tempDir.resolve("base.segment")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `round-trips sorted entries with ordered iteration and point gets`() {
        val keys =
            (0 until 200).map { index ->
                CodeIndexKey.file("src/main/File$index.kt", "hash-$index")
            }
        val records = keys.associateWith { key -> MetaIndexerVersionRecord(key.value) }
        writeSegment(keys.sortedBy { it.value }, records)

        MappedSegmentFile.open(segmentPath).use { file ->
            val table = file.primaryTable
            assertEquals(keys.size.toLong(), table.entryCount)
            keys
                .sortedBy { it.value }
                .forEach { key ->
                    val entry = table.get(key.value)
                    assertNotNull(entry, "Missing point get for ${key.value}")
                    assertEquals(
                        records.getValue(key),
                        CodeIndexRecordCodec.decode(entry.value ?: error("Missing value")),
                    )
                    assertFalse(entry.tombstone)
                }
            assertNull(table.get("file:absent.kt:hash"))

            val iterated = table.cursor().asSequence().toList()
            assertEquals(keys.sortedBy { it.value }.map { it.value }, iterated.map { it.key })
            iterated.forEach { entry ->
                assertEquals(
                    MetaIndexerVersionRecord(entry.key),
                    CodeIndexRecordCodec.decode(entry.value ?: error("Missing value")),
                )
            }
        }
    }

    @Test
    fun `cursor resumes from an inclusive start key`() {
        val keys = (0 until 50).map { CodeIndexKey.sym("com.example.Symbol$it") }
        val sorted = keys.sortedBy { it.value }
        writeSegment(sorted, sorted.associateWith { MetaIndexerVersionRecord(it.value) })

        MappedSegmentFile.open(segmentPath).use { file ->
            val from = tableKey(sorted[10].value)
            val iterated = file.primaryTable.cursor(fromKeyInclusive = from).asSequence().toList()
            assertEquals(sorted.drop(10).map { it.value }, iterated.map { it.key })
        }
    }

    @Test
    fun `cursor start between keys resumes at the next present key`() {
        val first = CodeIndexKey.sym("com.example.Aaa")
        val second = CodeIndexKey.sym("com.example.Bbb")
        writeSegment(
            listOf(first, second),
            mapOf(first to MetaIndexerVersionRecord("a"), second to MetaIndexerVersionRecord("b")),
        )

        MappedSegmentFile.open(segmentPath).use { file ->
            val iterated =
                file.primaryTable
                    .cursor(fromKeyInclusive = tableKey("sym:com.example.Aab"))
                    .asSequence()
                    .toList()
            assertEquals(listOf(second.value), iterated.map { it.key })
        }
    }

    @Test
    fun `empty segment round-trips`() {
        SegmentFileWriter.open(segmentPath, maxBlockEntries = 4).close()

        MappedSegmentFile.open(segmentPath).use { file ->
            assertEquals(0L, file.primaryTable.entryCount)
            assertNull(file.primaryTable.get("sym:anything"))
            assertTrue(file.primaryTable.cursor().asSequence().none())
        }
    }

    @Test
    fun `tombstone entries round-trip with their flag`() {
        val live = CodeIndexKey.sym("com.example.Live")
        val dead = CodeIndexKey.sym("com.example.Dead")
        SegmentFileWriter.open(segmentPath, maxBlockEntries = 4).use { writer ->
            writer.appendTombstone(dead)
            writer.append(live, MetaIndexerVersionRecord("live"))
        }

        MappedSegmentFile.open(segmentPath).use { file ->
            val tombstone = file.primaryTable.get(dead.value)
            assertNotNull(tombstone)
            assertTrue(tombstone.tombstone)
            assertNull(tombstone.value)
            assertFalse(file.primaryTable.get(live.value)?.tombstone ?: true)
            val entries = file.primaryTable.cursor().asSequence().toList()
            assertEquals(listOf(dead.value, live.value), entries.map { it.key })
            assertTrue(entries.first().tombstone)
            assertFalse(entries.last().tombstone)
        }
    }

    @Test
    fun `writer rejects out-of-order keys`() {
        val later = CodeIndexKey.sym("com.example.Zzz")
        val earlier = CodeIndexKey.sym("com.example.Aaa")
        val writer = SegmentFileWriter.open(segmentPath, maxBlockEntries = 4)
        writer.append(later, MetaIndexerVersionRecord("later"))
        assertFailsWith<IllegalStateException> {
            writer.append(earlier, MetaIndexerVersionRecord("earlier"))
        }
        writer.close()
    }

    @Test
    fun `writer rejects duplicate keys`() {
        val key = CodeIndexKey.sym("com.example.Duplicate")
        val writer = SegmentFileWriter.open(segmentPath, maxBlockEntries = 4)
        writer.append(key, MetaIndexerVersionRecord("first"))
        assertFailsWith<IllegalStateException> {
            writer.append(key, MetaIndexerVersionRecord("second"))
        }
        writer.close()
    }

    @Test
    fun `writer rejects appends after close`() {
        val writer = SegmentFileWriter.open(segmentPath, maxBlockEntries = 4)
        writer.close()
        assertFailsWith<IllegalStateException> {
            writer.append(CodeIndexKey.sym("com.example.Late"), MetaIndexerVersionRecord("late"))
        }
    }

    @Test
    fun `record values keep their encoded bytes`() {
        val key = CodeIndexKey.file("src/ui/Panel.kt", "cafebabe")
        val record =
            FileHashRecord(
                relativePath = "src/ui/Panel.kt",
                contentHash = "cafebabe",
                originId = "one",
                isCode = false,
            )
        SegmentFileWriter.open(segmentPath).use { writer -> writer.append(key, record) }

        MappedSegmentFile.open(segmentPath).use { file ->
            val entry = file.primaryTable.get(key.value)
            assertNotNull(entry)
            assertEquals(record, CodeIndexRecordCodec.decode(entry.value ?: error("Missing")))
        }
    }

    @Test
    fun `corrupted magic fails closed`() {
        writeSegment(
            listOf(CodeIndexKey.sym("com.example.Ok")),
            mapOf(CodeIndexKey.sym("com.example.Ok") to MetaIndexerVersionRecord("ok")),
        )
        corruptByte(offset = 0L, value = 'X'.code.toByte())

        assertFailsWith<java.io.IOException> { MappedSegmentFile.open(segmentPath).use {} }
    }

    @Test
    fun `corrupted payload fails closed for small files`() {
        writeSegment(
            listOf(CodeIndexKey.sym("com.example.Ok")),
            mapOf(CodeIndexKey.sym("com.example.Ok") to MetaIndexerVersionRecord("ok")),
        )
        corruptByte(offset = 40L, value = 0x7f)

        assertFailsWith<java.io.IOException> { MappedSegmentFile.open(segmentPath).use {} }
    }

    @Test
    fun `truncated file fails closed`() {
        writeSegment(
            listOf(CodeIndexKey.sym("com.example.Ok")),
            mapOf(CodeIndexKey.sym("com.example.Ok") to MetaIndexerVersionRecord("ok")),
        )
        val bytes = Files.readAllBytes(segmentPath)
        Files.write(segmentPath, bytes.copyOfRange(0, bytes.size / 2))

        assertFailsWith<java.io.IOException> { MappedSegmentFile.open(segmentPath).use {} }
    }

    @Test
    fun `mapped file is deletable after close on this platform`() {
        writeSegment(
            listOf(CodeIndexKey.sym("com.example.Ok")),
            mapOf(CodeIndexKey.sym("com.example.Ok") to MetaIndexerVersionRecord("ok")),
        )
        MappedSegmentFile.open(segmentPath).use { file ->
            assertNotNull(file.primaryTable.get("sym:com.example.Ok"))
        }
        assertTrue(Files.deleteIfExists(segmentPath), "Segment stayed mapped after close")
    }

    private fun writeSegment(
        sortedKeys: List<CodeIndexKey>,
        records: Map<CodeIndexKey, MetaIndexerVersionRecord>,
    ) {
        SegmentFileWriter.open(segmentPath, maxBlockEntries = 4).use { writer ->
            sortedKeys.forEach { key -> writer.append(key, records.getValue(key)) }
        }
    }

    /** Uses the reader's own key encoding so tests cannot drift from production comparison. */
    private fun tableKey(key: String): ByteArray = SegmentKeys.utf8(key)

    private fun corruptByte(offset: Long, value: Byte) {
        val bytes = Files.readAllBytes(segmentPath)
        bytes[offset.toInt()] = value
        Files.write(segmentPath, bytes)
    }
}
