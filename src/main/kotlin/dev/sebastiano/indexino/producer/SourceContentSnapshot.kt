package dev.sebastiano.indexino.producer

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.Charsets

private const val PARALLEL_CAPTURE_MIN_SOURCES = 64
private const val CAPTURE_WORKERS = 4

/**
 * Consistent source hashes and a bounded window of decoded text. Producers read through [content] /
 * [contentBytes]. Text leaving the window is appended to a build-scoped spill file, so analysis
 * still sees exactly the captured bytes; only entries that were never captured (inherited hashes
 * and hash-cache hits) are re-read, and those must still hash to the captured value.
 *
 * A snapshot owns its spill file: close it when the build is done.
 */
internal class SourceContentSnapshot
private constructor(
    private val entries: Map<IndexedSource, Entry>,
    val capturedSources: List<IndexedSource>,
    private val retention: SourceTextRetention,
    private val readBytes: (Path) -> ByteArray,
    private val statHashCache: SourceStatHashCache?,
    private val statCacheSourced: Set<IndexedSource>,
) : AutoCloseable {
    internal class Entry(val contentHash: String)

    fun content(source: IndexedSource): String = contentBytes(source).toString(Charsets.UTF_8)

    fun contentBytes(source: IndexedSource): ByteArray {
        retention.get(source)?.let {
            return it
        }
        val entry = entry(source)
        val bytes = readVerified(source, entry)
        retention.put(source, bytes)
        return bytes
    }

    fun retainedBytes(): Long = retention.retainedBytes()

    fun contentHash(source: IndexedSource): String = entry(source).contentHash

    fun hashes(): Map<IndexedSource, String> = entries.mapValues { it.value.contentHash }

    /** Sources whose content came from an inherited hash or hash cache instead of this capture. */
    fun inheritedCount(): Int = entries.size - capturedSources.size

    override fun close() = retention.close()

    private fun entry(source: IndexedSource): Entry =
        checkNotNull(entries[source]) {
            "Source was not captured: ${source.originId}:${source.path}"
        }

    private fun readVerified(source: IndexedSource, entry: Entry): ByteArray {
        val bytes =
            try {
                readBytes(source.originRoot.resolve(source.path))
            } catch (failure: IOException) {
                throw IllegalStateException(
                    "Inherited source changed during analysis: ${source.originId}:${source.path}",
                    failure,
                )
            }
        val actualHash = FileHashProducer.contentHash(bytes)
        if (actualHash != entry.contentHash && source in statCacheSourced) {
            // A stat-cache entry claimed the file was unchanged since its hash was recorded, but
            // Windows exposes no inode change time, so a same-size rewrite that restores the
            // modification time can keep a poisoned entry trusted. Drop the entry and report the
            // lie so the build restarts on a capture that reads the real bytes.
            statHashCache?.invalidate(source)
            throw StatHashCacheMismatchFailure(source)
        }
        check(actualHash == entry.contentHash) {
            "Inherited source changed during analysis: ${source.originId}:${source.path}"
        }
        return bytes
    }

    fun combinedHash(): String =
        FileHashProducer.contentHash(
            entries.keys
                .sortedWith(compareBy(IndexedSource::originId, IndexedSource::path))
                .joinToString("\n") { source ->
                    "${source.originId}:${source.path}:${source.isCode}:${contentHash(source)}"
                }
        )

    companion object {
        /** Default ceiling on decoded source text held in heap across producer passes. */
        internal const val DEFAULT_RETENTION_BYTES: Long = 64L * 1024 * 1024

        fun capture(
            sources: List<IndexedSource>,
            inheritedHashes: Map<IndexedSource, String> = emptyMap(),
            hintedPaths: Set<java.nio.file.Path> = emptySet(),
            hashCache: SourceStatHashCache? = null,
            readBytes: (Path) -> ByteArray = Files::readAllBytes,
            retentionBytes: Long = DEFAULT_RETENTION_BYTES,
            spillDirectory: Path? = null,
        ): SourceContentSnapshot {
            val retention = SourceTextRetention(retentionBytes, spillDirectory)
            return try {
                val snapshot =
                    if (inheritedHashes.isEmpty() && sources.size >= PARALLEL_CAPTURE_MIN_SOURCES) {
                        captureParallel(sources, hashCache, readBytes, retention)
                    } else {
                        captureSerial(
                            sources,
                            inheritedHashes,
                            hintedPaths,
                            hashCache,
                            readBytes,
                            retention,
                        )
                    }
                hashCache?.flush()
                snapshot
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                // Capture failed before a snapshot existed to own the spill. The parallel path
                // has already stopped its workers (its own finally runs before this catch).
                retention.close()
                throw failure
            }
        }

        private fun captureSerial(
            sources: List<IndexedSource>,
            inheritedHashes: Map<IndexedSource, String>,
            hintedPaths: Set<java.nio.file.Path>,
            hashCache: SourceStatHashCache?,
            readBytes: (Path) -> ByteArray,
            retention: SourceTextRetention,
        ): SourceContentSnapshot {
            val captured = mutableListOf<IndexedSource>()
            val statCacheSourced = mutableSetOf<IndexedSource>()
            val entries = sources.associateWith { source ->
                val previous = inheritedHashes[source]
                if (
                    previous != null &&
                        source.originRoot.resolve(source.path).normalize() !in hintedPaths
                )
                    Entry(contentHash = previous)
                else {
                    val stat = hashCache?.stat(source)
                    val cached = stat?.let { hashCache.hashIfUnchanged(source, it) }
                    if (cached != null) {
                        statCacheSourced += source
                        Entry(contentHash = cached)
                    } else {
                        captured += source
                        val bytes = readBytes(source.originRoot.resolve(source.path))
                        val hash = FileHashProducer.contentHash(bytes)
                        if (stat != null) {
                            val after = hashCache.stat(source)
                            if (after != null && stat.sameMetadata(after)) {
                                hashCache.record(source, after, hash)
                            }
                        }
                        checkNotInterrupted()
                        retention.put(source, bytes)
                        Entry(contentHash = hash)
                    }
                }
            }
            return SourceContentSnapshot(
                entries,
                captured,
                retention,
                readBytes,
                hashCache,
                statCacheSourced,
            )
        }

        private fun checkNotInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        }

        private fun captureParallel(
            sources: List<IndexedSource>,
            hashCache: SourceStatHashCache?,
            readBytes: (Path) -> ByteArray,
            retention: SourceTextRetention,
        ): SourceContentSnapshot {
            val entries = arrayOfNulls<Entry>(sources.size)
            val captured = BooleanArray(sources.size)
            val statSourced = BooleanArray(sources.size)
            val next = AtomicInteger()
            val executor = Executors.newFixedThreadPool(CAPTURE_WORKERS)
            val completed = ExecutorCompletionService<Unit>(executor)
            try {
                repeat(CAPTURE_WORKERS) {
                    completed.submit(
                        Callable {
                            while (true) {
                                if (Thread.currentThread().isInterrupted)
                                    throw InterruptedException()
                                val index = next.getAndIncrement()
                                if (index >= sources.size) break
                                val source = sources[index]
                                val stat = hashCache?.stat(source)
                                val cached = stat?.let { hashCache.hashIfUnchanged(source, it) }
                                entries[index] =
                                    if (cached != null) {
                                        statSourced[index] = true
                                        Entry(contentHash = cached)
                                    } else {
                                        val bytes =
                                            readBytes(source.originRoot.resolve(source.path))
                                        val hash = FileHashProducer.contentHash(bytes)
                                        if (stat != null) {
                                            val after = hashCache.stat(source)
                                            if (after != null && stat.sameMetadata(after)) {
                                                hashCache.record(source, after, hash)
                                            }
                                        }
                                        checkNotInterrupted()
                                        retention.put(source, bytes)
                                        captured[index] = true
                                        Entry(contentHash = hash)
                                    }
                            }
                        }
                    )
                }
                repeat(CAPTURE_WORKERS) { completed.take().get() }
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } finally {
                executor.shutdownNow()
                // Do not retire a refresh while a sibling can still read its sources.
                executor.close()
            }
            val ordered = LinkedHashMap<IndexedSource, Entry>(sources.size)
            sources.forEachIndexed { index, source ->
                ordered[source] = checkNotNull(entries[index])
            }
            return SourceContentSnapshot(
                ordered,
                sources.filterIndexed { index, _ -> captured[index] },
                retention,
                readBytes,
                hashCache,
                sources.filterIndexed { index, _ -> statSourced[index] }.toSet(),
            )
        }
    }
}

/**
 * Access-ordered cache of decoded source text capped at [maxBytes]. Evicted text is appended to one
 * spill file inside [spillDirectory] and served back from it, so a bounded heap never forces a
 * source re-read. Without a spill directory (tests) evicted text is simply dropped and re-read.
 */
internal class SourceTextRetention(private val maxBytes: Long, private val spillDirectory: Path?) :
    AutoCloseable {
    private val texts = object : LinkedHashMap<IndexedSource, ByteArray>(16, 0.75f, true) {}
    private val spilled = HashMap<IndexedSource, SpillRange>()
    private var spillChannel: FileChannel? = null
    private var spillPath: Path? = null
    private var spillOffset = 0L
    private var retained = 0L

    @Synchronized
    fun get(source: IndexedSource): ByteArray? {
        texts[source]?.let {
            return it
        }
        val range = spilled[source] ?: return null
        val channel = checkNotNull(spillChannel) { "Source spill is closed" }
        val bytes = ByteArray(range.length)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, range.offset + buffer.position())
            check(read > 0) { "Truncated source spill: ${source.originId}:${source.path}" }
        }
        return bytes
    }

    @Synchronized
    fun put(source: IndexedSource, bytes: ByteArray) {
        texts.put(source, bytes)?.let { previous -> retained -= previous.size }
        retained += bytes.size
        val eldest = texts.entries.iterator()
        while (retained > maxBytes && eldest.hasNext()) {
            val entry = eldest.next()
            eldest.remove()
            retained -= entry.value.size
            spill(entry.key, entry.value)
        }
    }

    @Synchronized fun retainedBytes(): Long = retained

    @Synchronized
    override fun close() {
        spillChannel?.let { channel -> runCatching { channel.close() } }
        spillChannel = null
        spillPath?.let { path ->
            // Best effort: the next capture scrubs its spill directory before use.
            runCatching { Files.deleteIfExists(path) }
        }
        spillPath = null
        spilled.clear()
    }

    private fun spill(source: IndexedSource, bytes: ByteArray) {
        val directory = spillDirectory ?: return
        val channel = spillChannel ?: openSpill(directory)
        val buffer = ByteBuffer.wrap(bytes)
        var offset = spillOffset
        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset)
        }
        spilled[source] = SpillRange(offset = spillOffset, length = bytes.size)
        spillOffset += bytes.size
    }

    private fun openSpill(directory: Path): FileChannel {
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX)
        spillPath = path
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).also {
            channel ->
            spillChannel = channel
        }
    }

    private class SpillRange(val offset: Long, val length: Int)

    private companion object {
        const val SPILL_FILE_PREFIX = "source-spill-"
        const val SPILL_FILE_SUFFIX = ".bin"
    }
}
