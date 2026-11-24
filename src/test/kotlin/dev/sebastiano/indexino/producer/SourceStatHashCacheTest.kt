package dev.sebastiano.indexino.producer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
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
                stableMtime(root.resolve(path))
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
                stableMtime(root.resolve(path))
                IndexedSource.workspace(root, path)
            }
        val cacheFile = tempDir.resolve("source-hashes")
        val reads = AtomicInteger()
        val first = capture(sources, SourceStatHashCache.open(cacheFile, 0), reads)

        root.resolve("src/F1.kt").writeText("after-1")
        stableMtime(root.resolve("src/F1.kt"))

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
        stableMtime(file)
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
        val first = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)

        file.writeText("bbbb")
        Files.setLastModifiedTime(file, original)

        val second = capture(listOf(source), SourceStatHashCache.open(cacheFile, 0), reads)
        assertEquals(2, reads.get())
        assertTrue(first.contentHash(source) != second.contentHash(source))
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
        stableMtime(file)
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
        stableMtime(file)
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
        stableMtime(file)
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
                stableMtime(root.resolve(path))
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

    private fun stableMtime(file: Path) {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000))
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
