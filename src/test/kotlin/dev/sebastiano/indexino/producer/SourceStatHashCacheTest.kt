package dev.sebastiano.indexino.producer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir

class SourceStatHashCacheTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `unchanged files reuse their persisted hash without reading`() {
        val root = workspace("unchanged")
        val sources =
            listOf("src/A.kt", "src/B.kt", "res/strings.xml").map { path ->
                Files.createDirectories(root.resolve(path).parent)
                root.resolve(path).writeText("content of $path")
                stableTimestamps(root.resolve(path))
                IndexedSource.workspace(root, path)
            }
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()

        val first = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(sources.size, reads.get())
        assertEquals(sources.size, first.capturedSources.size)

        val second = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(sources.size, reads.get(), "Unchanged files were read again")
        assertTrue(second.capturedSources.isEmpty(), "Cache hits must not be reported as reads")
        assertEquals(first.hashes(), second.hashes())
    }

    @Test
    fun `an edit is rehashed while untouched siblings reuse their hash`() {
        val root = workspace("edit")
        val sources =
            (0 until 3).map { index ->
                val path = "src/F$index.kt"
                Files.createDirectories(root.resolve(path).parent)
                root.resolve(path).writeText("before-$index")
                stableTimestamps(root.resolve(path))
                IndexedSource.workspace(root, path)
            }
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()
        val first = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)

        root.resolve("src/F1.kt").writeText("after-1")
        stableTimestamps(root.resolve("src/F1.kt"))

        val second = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(4, reads.get(), "Only the edited file should be read")
        assertEquals(listOf(sources[1]), second.capturedSources)
        assertEquals(first.contentHash(sources[0]), second.contentHash(sources[0]))
        assertEquals(first.contentHash(sources[2]), second.contentHash(sources[2]))
        assertTrue(first.contentHash(sources[1]) != second.contentHash(sources[1]))
    }

    @Test
    fun `a metadata-only change forces a rehash with the same content hash`() {
        val root = workspace("touch")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("same bytes")
        stableTimestamps(file)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()
        val first = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)

        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 5_000))

        val second = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(2, reads.get())
        assertEquals(first.contentHash(source), second.contentHash(source))
    }

    @Test
    fun `a same-size rewrite with a restored mtime is rejected on POSIX`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val root = workspace("restored-mtime")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("aaaa")
        val original = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
        Files.setLastModifiedTime(file, original)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()
        val first = capture(listOf(source), SourceStatHashCache.open(cacheFile), reads)

        file.writeText("bbbb")
        Files.setLastModifiedTime(file, original)

        val second = capture(listOf(source), SourceStatHashCache.open(cacheFile), reads)
        assertEquals(2, reads.get())
        assertTrue(first.contentHash(source) != second.contentHash(source))
    }

    @Test
    fun `a restored mtime with a colliding recent ctime cannot reuse a stale hash after reopen`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val root = workspace("ctime-collision")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        val original = FileTime.fromMillis(System.currentTimeMillis() - 60_000)
        file.writeText("aaaa")
        Files.setLastModifiedTime(file, original)
        file.writeText("bbbb")
        Files.setLastModifiedTime(file, original)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val current = SourceStatHashCache.statOf(file)!!
        // Reproduce the observed collision deterministically: the old hash was recorded with
        // exactly the new bytes' stat, within the ctime tick. No dependence on host tick timing.
        val recorded =
            SourceStatHashCache.SourceStat(
                current.size,
                current.modifiedNanos,
                current.changedNanos,
                current.fileKey,
                observedAtNanos = current.changedNanos + 1,
            )
        val cacheFile = tempDir.resolve("source-hashes")
        val cache = SourceStatHashCache.open(cacheFile)
        cache.record(source, recorded, FileHashProducer.contentHash("aaaa"))
        assertNull(cache.hashIfUnchanged(source, current))
        cache.flush()
        val reopened = SourceStatHashCache.open(cacheFile)
        assertNull(reopened.hashIfUnchanged(source, current))

        val reads = AtomicInteger()
        capture(listOf(source), reopened, reads).use { snapshot ->
            assertEquals(1, reads.get())
            assertEquals(listOf(source), snapshot.capturedSources)
            assertEquals(FileHashProducer.contentHash("bbbb"), snapshot.contentHash(source))
        }
    }

    @Test
    fun `both timestamps must predate observation by more than the racy window even after reopen`() {
        val source = IndexedSource.workspace(workspace("timestamp-boundary"), "src/A.kt")
        val observed = 100_000_000_000L
        val window = 2_000_000_000L
        val hash = FileHashProducer.contentHash("aaaa")
        // Include future, equal, recent and exact-boundary timestamps, then a safe control.
        for (age in listOf(-1L, 0L, 1L, window - 1, window, window + 1)) {
            for (changeIsNewer in listOf(false, true)) {
                val stat =
                    SourceStatHashCache.SourceStat(
                        size = 4,
                        modifiedNanos = observed - if (changeIsNewer) 60_000_000_000L else age,
                        changedNanos = observed - if (changeIsNewer) age else 60_000_000_000L,
                        fileKey = "device:inode",
                        observedAtNanos = observed,
                    )
                val later =
                    SourceStatHashCache.SourceStat(
                        stat.size,
                        stat.modifiedNanos,
                        stat.changedNanos,
                        stat.fileKey,
                        observedAtNanos = observed + 60_000_000_000L,
                    )
                val cacheFile = tempDir.resolve("hashes-$age-$changeIsNewer")
                val cache = SourceStatHashCache.open(cacheFile)
                cache.record(source, stat, hash)
                val expected = if (age == window + 1) hash else null
                assertEquals(
                    expected,
                    cache.hashIfUnchanged(source, later),
                    "age=$age ctime=$changeIsNewer",
                )
                cache.flush()
                assertEquals(
                    expected,
                    SourceStatHashCache.open(cacheFile).hashIfUnchanged(source, later),
                    "reopened age=$age ctime=$changeIsNewer",
                )
            }
        }
    }

    @Test
    fun `an entry observed inside the racy window is not trusted`() {
        val root = workspace("racy")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("racy")
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()
        val cache = SourceStatHashCache.open(cacheFile)

        capture(listOf(source), cache, reads)
        cache.flush()
        capture(listOf(source), SourceStatHashCache.open(cacheFile), reads)
        assertEquals(2, reads.get(), "A just-written file must be hashed again")
    }

    @Test
    fun `a corrupt cache file falls back to reading every source`() {
        val root = workspace("corrupt")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("content")
        stableTimestamps(file)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cacheFile = tempDir.resolve("source-hashes")
        cacheFile.writeText("not a cache\n\u0000garbage\n")
        val reads = AtomicInteger()

        val snapshot = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(1, reads.get())
        assertEquals(FileHashProducer.contentHash("content"), snapshot.contentHash(source))
    }

    @Test
    fun `a cache hit still rejects bytes changed after capture`() {
        val root = workspace("toctou")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("before")
        stableTimestamps(file)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cacheFile = tempDir.resolve("source-hashes")
        capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), AtomicInteger())

        val reads = AtomicInteger()
        val snapshot = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(0, reads.get(), "The second capture should be a cache hit")
        assertTrue(snapshot.capturedSources.isEmpty())

        file.writeText("changed after capture")
        assertFailsWith<IllegalStateException> { snapshot.content(source) }
    }

    @Test
    fun `a stale stat-cache hash discovered on read is evicted and reported separately`() {
        val root = workspace("invisible-stat")
        val file = root.resolve("src/A.kt")
        Files.createDirectories(file.parent)
        file.writeText("stale")
        stableTimestamps(file)
        val source = IndexedSource.workspace(root, "src/A.kt")
        val cache = SourceStatHashCache.inMemory(0)
        val reads = AtomicInteger()

        // Windows exposes no inode change time, so a same-size rewrite that restores the
        // modification time is invisible to the stat key. Simulate that lie directly: the entry
        // holds the file's current stat but the hash of content the file no longer has.
        cache.record(
            source,
            SourceStatHashCache.statOf(file)!!,
            FileHashProducer.contentHash("stale"),
        )

        val snapshot = capture(listOf(source), cache, reads)
        assertEquals(0, reads.get(), "The poisoned entry must be served as a cache hit")
        assertTrue(snapshot.capturedSources.isEmpty())

        file.writeText("rewritten-in-place")
        val failure = assertFailsWith<StatHashCacheMismatchFailure> { snapshot.content(source) }

        assertEquals(source, failure.source)
        assertNull(
            cache.hashIfUnchanged(source, SourceStatHashCache.statOf(file)!!),
            "The poisoned entry must be evicted when the mismatch is discovered",
        )
    }

    @Test
    fun `the parallel capture path skips reads on a warm cache`() {
        val root = workspace("parallel")
        val sources =
            (0 until 68).map { index ->
                val path = "src/F$index.kt"
                Files.createDirectories(root.resolve(path).parent)
                root.resolve(path).writeText("parallel-$index")
                stableTimestamps(root.resolve(path))
                IndexedSource.workspace(root, path)
            }
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()

        capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(68, reads.get())

        val second = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(68, reads.get(), "Warm parallel capture read files again")
        assertTrue(second.capturedSources.isEmpty())
    }

    private fun workspace(name: String): Path =
        Files.createDirectories(tempDir.resolve(name)).toRealPath()

    private fun stableTimestamps(file: Path) {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000))
        // POSIX ctime cannot be backdated and can be newer than the millisecond observation clock.
        // Age it before recording: waiting afterward cannot make an unsafe entry trusted.
        val changedNanos = checkNotNull(SourceStatHashCache.statOf(file)).changedNanos
        while (TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()) <= changedNanos) {
            Thread.sleep(1)
        }
    }

    private fun capture(
        sources: List<IndexedSource>,
        cache: SourceStatHashCache,
        reads: AtomicInteger,
    ): SourceContentSnapshot =
        SourceContentSnapshot.capture(
                sources,
                hashCache = cache,
                readBytes = { path ->
                    reads.incrementAndGet()
                    Files.readAllBytes(path)
                },
            )
            .also { cache.flush() }
}
