package dev.sebastiano.indexino.producer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Cold captures must not pin every decoded source for the whole build. Retention is bounded; a
 * source evicted from that window is re-read and must still hash to its captured content, so
 * producers keep analyzing exactly the bytes that were hashed and a mid-analysis edit fails closed.
 */
class SourceContentSnapshotRetentionTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `retains at most the configured byte budget of source text`() {
        val sources = createSources(SOURCE_COUNT)
        val snapshot = SourceContentSnapshot.capture(sources, retentionBytes = RETAIN_BYTES)

        assertTrue(
            snapshot.retainedBytes() <= RETAIN_BYTES,
            "Retained ${snapshot.retainedBytes()} bytes over a $RETAIN_BYTES byte budget",
        )
        sources.forEach { source -> snapshot.content(source) }
        assertTrue(snapshot.retainedBytes() <= RETAIN_BYTES)
    }

    @Test
    fun `evicted capture bytes are re-read from disk`() {
        val sources = createSources(SOURCE_COUNT)
        val reads = AtomicInteger()
        val snapshot =
            SourceContentSnapshot.capture(
                sources,
                retentionBytes = RETAIN_BYTES,
                readBytes = { path ->
                    reads.incrementAndGet()
                    Files.readAllBytes(path)
                },
            )
        assertEquals(SOURCE_COUNT, reads.get(), "Capture should read each source once")

        val contents = sources.map { source -> snapshot.content(source) }

        assertTrue(
            reads.get() > SOURCE_COUNT,
            "Evicted sources were served from retained text instead of a verified re-read",
        )
        sources.forEachIndexed { index, source ->
            assertEquals(contents[index], sourceText(source), "${source.path} text changed")
        }
    }

    @Test
    fun `captured bytes that leave the window spill instead of being re-read`() {
        val sources = createSources(SOURCE_COUNT)
        val reads = AtomicInteger()
        val spillDirectory = tempDir.resolve("spill")
        val snapshot =
            SourceContentSnapshot.capture(
                sources,
                retentionBytes = RETAIN_BYTES,
                readBytes = { path ->
                    reads.incrementAndGet()
                    Files.readAllBytes(path)
                },
                spillDirectory = spillDirectory,
            )

        val contents = sources.map { source -> snapshot.content(source) }

        assertEquals(SOURCE_COUNT, reads.get(), "Spilled sources must not be re-read")
        sources.forEachIndexed { index, source ->
            assertEquals(contents[index], sourceText(source), "${source.path} text changed")
        }
        assertEquals(1, Files.list(spillDirectory).use { files -> files.count() })
        snapshot.close()
        assertEquals(0, Files.list(spillDirectory).use { files -> files.count() })
    }

    @Test
    fun `spilled capture still serves the captured bytes after a source edit`() {
        val sources = createSources(SOURCE_COUNT)
        val snapshot =
            SourceContentSnapshot.capture(
                sources,
                retentionBytes = RETAIN_BYTES,
                spillDirectory = tempDir.resolve("spill"),
            )
        val evicted = sources.first()
        val captured = snapshot.content(evicted)
        evicted.originRoot.resolve(evicted.path).writeText("class Mutated {}")

        assertEquals(captured, snapshot.content(evicted))
        assertEquals(FileHashProducer.contentHash(captured), snapshot.contentHash(evicted))
        snapshot.close()
    }

    @Test
    fun `fails when an evicted source changed after capture`() {
        val sources = createSources(SOURCE_COUNT)
        val snapshot = SourceContentSnapshot.capture(sources, retentionBytes = RETAIN_BYTES)
        val evicted = sources.first()
        evicted.originRoot.resolve(evicted.path).writeText("class Mutated {}")

        val failure = assertFailsWith<IllegalStateException> { snapshot.content(evicted) }

        assertTrue(
            failure.message.orEmpty().contains("changed during analysis"),
            "${failure.message}",
        )
    }

    @Test
    fun `inherited hashes stay separate from evicted capture bytes`() {
        val sources = createSources(SOURCE_COUNT)
        val inherited = sources.associateWith { source ->
            FileHashProducer.contentHash(sourceText(source))
        }
        val snapshot =
            SourceContentSnapshot.capture(
                sources,
                inheritedHashes = inherited,
                retentionBytes = RETAIN_BYTES,
            )

        assertEquals(SOURCE_COUNT, snapshot.inheritedCount())
        assertEquals(0, snapshot.retainedBytes())
    }

    @Test
    fun `a failed serial capture releases its spill`() {
        val sources = createSources(SOURCE_COUNT)
        val spillDirectory = tempDir.resolve("spill")
        val failure = IllegalStateException("read failed")
        val reads = AtomicInteger()

        val observed =
            assertFailsWith<IllegalStateException> {
                SourceContentSnapshot.capture(
                    sources,
                    retentionBytes = RETAIN_BYTES,
                    readBytes = { path ->
                        if (reads.incrementAndGet() == SOURCE_COUNT) throw failure
                        Files.readAllBytes(path)
                    },
                    spillDirectory = spillDirectory,
                )
            }

        assertSame(failure, observed)
        assertSpillReleased(spillDirectory)
    }

    @Test
    fun `a cancelled parallel capture releases its spill`() {
        val sources = createSources(PARALLEL_SOURCE_COUNT)
        val spillDirectory = tempDir.resolve("spill")
        val reads = AtomicInteger()

        assertFailsWith<InterruptedException> {
            SourceContentSnapshot.capture(
                sources,
                retentionBytes = RETAIN_BYTES,
                readBytes = { path ->
                    val bytes = Files.readAllBytes(path)
                    if (reads.incrementAndGet() == INTERRUPT_AFTER_READS) {
                        Thread.currentThread().interrupt()
                    }
                    bytes
                },
                spillDirectory = spillDirectory,
            )
        }

        assertSpillReleased(spillDirectory)
    }

    private fun assertSpillReleased(spillDirectory: Path) {
        val spillFiles =
            if (Files.isDirectory(spillDirectory)) {
                Files.list(spillDirectory).use { files -> files.toList() }
            } else emptyList()
        assertTrue(spillFiles.isEmpty(), "Capture failure left spill files: $spillFiles")
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")))
        val openDescriptors =
            Files.list(Path.of("/proc/self/fd")).use { descriptors ->
                descriptors
                    .filter { descriptor ->
                        runCatching { Files.readSymbolicLink(descriptor).toString() }
                            .getOrNull()
                            ?.contains(spillDirectory.toString()) == true
                    }
                    .toList()
            }
        assertTrue(
            openDescriptors.isEmpty(),
            "Capture failure left a spill channel open: $openDescriptors",
        )
    }

    private fun createSources(count: Int): List<IndexedSource> {
        val root = tempDir.resolve("workspace").also { it.createDirectories() }
        return (0 until count).map { index ->
            val relative = "src/main/kotlin/bench/File$index.kt"
            root
                .resolve(relative)
                .also { it.parent.createDirectories() }
                .writeText("package bench\n\nclass File$index\n")
            IndexedSource("workspace", root, relative)
        }
    }

    private fun sourceText(source: IndexedSource): String =
        source.originRoot.resolve(source.path).readText()

    private companion object {
        const val SOURCE_COUNT = 8
        const val PARALLEL_SOURCE_COUNT = 128
        const val INTERRUPT_AFTER_READS = 8
        const val RETAIN_BYTES = 120L
    }
}
