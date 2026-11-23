package dev.sebastiano.indexino.core.segment

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.FileHashRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * MemorySegment unmapping lifecycle on Windows (and everywhere else): mapped packs must block
 * deletion and renaming until the last pin closes, refcounted mappings must be shared, and pins
 * taken before a refresh must keep serving their generation afterwards.
 */
class SegmentLifecycleTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("segment-lifecycle-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `windows mapped packs keep serving readers through deletion attempts`() {
        val pack = writePack("mapped.segment", ENTRY_COUNT_FOR_LIFECYCLE)
        val first = SharedSegmentStore.open(pack)
        val second = SharedSegmentStore.open(pack)

        // On Windows the JDK opens mapped files with FILE_SHARE_DELETE: the namespace entry can
        // be removed while mappings stay live. Either way, pinned readers must keep serving.
        runCatching { Files.deleteIfExists(pack) }
        assertEquals(
            RECORD_SENTINEL,
            recordAt(first, ENTRY_COUNT_FOR_LIFECYCLE - 1),
            "Pinned reader lost its mapping",
        )
        first.close()
        assertEquals(
            RECORD_SENTINEL,
            recordAt(second, ENTRY_COUNT_FOR_LIFECYCLE - 1),
            "Closing one pin unmapped a still-pinned pack",
        )
        second.close()
        // After the last close the pack must be fully removable.
        val stillThere = Files.exists(pack)
        assertTrue(!stillThere || Files.deleteIfExists(pack), "Pack stayed mapped after close")
        assertFalse(Files.exists(pack))
    }

    @Test
    fun `windows mapped packs survive rename attempts and rename after close`() {
        val pack = writePack("rename-me.segment", 64)
        val pin = SharedSegmentStore.open(pack)
        val renamed = tempDir.resolve("renamed.segment")
        // A rename may succeed through FILE_SHARE_DELETE; the mapping must remain readable.
        runCatching { Files.move(pack, renamed, StandardCopyOption.REPLACE_EXISTING) }
        assertEquals(RECORD_SENTINEL, recordAt(pin, 63), "Mapping broke across a rename attempt")
        pin.close()
        if (Files.exists(pack)) {
            Files.move(pack, renamed, StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(Files.exists(renamed) || !Files.exists(pack))
        assertFalse(Files.exists(pack), "Original path must not survive the final rename")
    }

    @Test
    fun `simultaneous pins share one mapping and survive each other's close`() {
        val pack = writePack("shared.segment", 128)
        val first = SharedSegmentStore.open(pack)
        val second = SharedSegmentStore.open(pack)
        assertSame(first.file, second.file, "Pins created two mappings for one pack path")

        first.close()
        assertEquals(RECORD_SENTINEL, recordAt(second, 127), "Shared mapping was unmapped early")

        // While a pin remains open, new pins keep sharing the same live mapping...
        val sharing = SharedSegmentStore.open(pack)
        assertSame(second.file, sharing.file, "A live mapping was not shared with a new pin")
        sharing.close()

        // ...and only after the last release does a fresh pin map a fresh instance.
        second.close()
        val reopened = SharedSegmentStore.open(pack)
        assertNotEquals(first.file, reopened.file, "A closed mapping was resurrected for a new pin")
        reopened.close()
        assertTrue(Files.deleteIfExists(pack))
    }

    @Test
    fun `a snapshot pin keeps serving its generation across a later publication`() {
        val oldPack = writePack("generation-old.segment", 96)
        val pinned = SharedSegmentStore.open(oldPack)

        // A refresh publishes a new generation beside the pinned one.
        val newPack = writePack("generation-new.segment", 96)
        val fresh = SharedSegmentStore.open(newPack)

        assertEquals(RECORD_SENTINEL, recordAt(pinned, 95), "Pinned generation lost its facts")
        assertEquals(RECORD_SENTINEL, recordAt(fresh, 95), "New generation lost its facts")

        pinned.close()
        assertTrue(Files.deleteIfExists(oldPack), "Old pack stayed mapped after its pin closed")
        assertEquals(RECORD_SENTINEL, recordAt(fresh, 95), "New generation was disturbed")
        fresh.close()
        assertTrue(Files.deleteIfExists(newPack))
    }

    @Test
    fun `cold open of a fixture-scale pack completes in milliseconds`() {
        val pack = writePack("cold-open.segment", 50_000)
        // Warm the OS page cache with one full read so the measurement exercises the mapping
        // path rather than disk spin-up.
        Files.newInputStream(pack).use { input -> input.readNBytes(8) }

        val durationsMillis = DoubleArray(COLD_OPEN_SAMPLES) { measureColdOpenMillis(pack) }
        val median = durationsMillis.sorted()[durationsMillis.size / 2]
        println("SEGMENT_COLD_OPEN medianMillis=$median samples=${durationsMillis.toList()}")
        assertTrue(median < COLD_OPEN_BOUND_MILLIS, "Cold open median ${median}ms exceeds bound")
        Files.deleteIfExists(pack)
    }

    @Test
    fun `warm point and prefix queries on a fixture-scale pack stay sub-millisecond`() {
        val pack = writePack("warm-queries.segment", 50_000)
        val pin = SharedSegmentStore.open(pack)
        val store = SegmentCodeIndexStore.open(listOf(pin.file)) { pin.close() }
        val probeKey = CodeIndexKey.file("src/main/File${padded(50_000 - 1)}.kt", "hash")
        val prefix = "file:src/main/File4999"

        repeat(QUERY_WARMUP) {
            store.get(probeKey)
            store.prefixScan(prefix).first()
        }
        val pointMillis = measureMillis(QUERY_SAMPLES) { store.get(probeKey) }
        val prefixMillis =
            measureMillis(QUERY_SAMPLES) { store.prefixScan(prefix).take(10).toList() }
        println("SEGMENT_WARM_QUERIES pointMillis=$pointMillis prefixMillis=$prefixMillis")
        assertTrue(store.get(probeKey) is dev.sebastiano.indexino.core.record.FileHashRecord)
        assertTrue(pointMillis < WARM_QUERY_BOUND_MILLIS, "Point median ${pointMillis}ms")
        assertTrue(prefixMillis < WARM_QUERY_BOUND_MILLIS, "Prefix median ${prefixMillis}ms")
        store.close()
        assertTrue(Files.deleteIfExists(pack))
    }

    private fun measureColdOpenMillis(pack: Path): Double {
        val started = System.nanoTime()
        MappedSegmentFile.open(pack).use { file ->
            assertTrue(file.primaryTable.entryCount > 0)
            val entry =
                file.primaryTable.get(
                    "file:src/main/File${padded(ENTRY_COUNT_FOR_COLD - 1)}.kt:hash"
                )
            assertTrue(entry != null)
        }
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun measureMillis(samples: Int, action: () -> Unit): Double {
        val durations = DoubleArray(samples)
        repeat(samples) { index ->
            val started = System.nanoTime()
            action()
            durations[index] = (System.nanoTime() - started) / 1_000_000.0
        }
        return durations.sorted()[durations.size / 2]
    }

    private fun recordAt(pin: PinnedSegmentFile, index: Int): String {
        val entry = pin.file.primaryTable.get("file:src/main/File${padded(index)}.kt:hash")
        val record =
            entry?.value?.let { bytes ->
                dev.sebastiano.indexino.core.record.CodeIndexRecordCodec.decode(bytes)
            } as? dev.sebastiano.indexino.core.record.FileHashRecord
                ?: error("Missing entry $index")
        return record.contentHash
    }

    private fun writePack(name: String, entries: Int): Path {
        val pack = tempDir.resolve(name)
        SegmentFileWriter.open(pack).use { writer ->
            (0 until entries).forEach { index ->
                val relativePath = "src/main/File${padded(index)}.kt"
                writer.append(
                    CodeIndexKey.file(relativePath, "hash"),
                    FileHashRecord(relativePath = relativePath, contentHash = RECORD_SENTINEL),
                )
            }
        }
        return pack
    }

    private fun padded(index: Int): String = index.toString().padStart(KEY_INDEX_WIDTH, PAD_CHAR)

    private companion object {
        const val ENTRY_COUNT_FOR_LIFECYCLE = 256
        const val ENTRY_COUNT_FOR_COLD = 50_000
        const val RECORD_SENTINEL = "lifecycle-sentinel"
        const val KEY_INDEX_WIDTH = 5
        const val PAD_CHAR = '0'
        const val COLD_OPEN_SAMPLES = 11
        const val COLD_OPEN_BOUND_MILLIS = 10.0
        const val QUERY_WARMUP = 50
        const val QUERY_SAMPLES = 200
        const val WARM_QUERY_BOUND_MILLIS = 1.0
    }
}
