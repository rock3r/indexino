package dev.sebastiano.indexino.producer

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.text.Charsets

private const val PARALLEL_CAPTURE_MIN_SOURCES = 64
private const val CAPTURE_WORKERS = 4

/** Consistent source hashes and lazily verified bytes inherited from an immutable generation. */
internal class SourceContentSnapshot
private constructor(
    private val entries: Map<IndexedSource, Entry>,
    val capturedSources: List<IndexedSource>,
) {
    internal class Entry(var bytes: ByteArray?, val contentHash: String)

    fun content(source: IndexedSource): String = contentBytes(source).toString(Charsets.UTF_8)

    fun contentBytes(source: IndexedSource): ByteArray {
        val entry = entry(source)
        synchronized(entry) {
            entry.bytes?.let {
                return it
            }
            val bytes = Files.readAllBytes(source.originRoot.resolve(source.path))
            check(FileHashProducer.contentHash(bytes) == entry.contentHash) {
                "Inherited source changed during analysis: ${source.originId}:${source.path}"
            }
            entry.bytes = bytes
            return bytes
        }
    }

    fun contentHash(source: IndexedSource): String = entry(source).contentHash

    fun hashes(): Map<IndexedSource, String> = entries.mapValues { it.value.contentHash }

    fun inheritedCount(): Int = entries.values.count { it.bytes == null }

    private fun entry(source: IndexedSource): Entry =
        checkNotNull(entries[source]) {
            "Source was not captured: ${source.originId}:${source.path}"
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
        fun capture(
            sources: List<IndexedSource>,
            inheritedHashes: Map<IndexedSource, String> = emptyMap(),
            hintedPaths: Set<java.nio.file.Path> = emptySet(),
            readBytes: (Path) -> ByteArray = Files::readAllBytes,
        ): SourceContentSnapshot {
            if (inheritedHashes.isEmpty() && sources.size >= PARALLEL_CAPTURE_MIN_SOURCES)
                return captureParallel(sources, readBytes)

            val captured = mutableListOf<IndexedSource>()
            val entries = sources.associateWith { source ->
                val previous = inheritedHashes[source]
                if (
                    previous != null &&
                        source.originRoot.resolve(source.path).normalize() !in hintedPaths
                )
                    Entry(bytes = null, contentHash = previous)
                else {
                    captured += source
                    val bytes = readBytes(source.originRoot.resolve(source.path))
                    Entry(bytes = bytes, contentHash = FileHashProducer.contentHash(bytes))
                }
            }
            return SourceContentSnapshot(entries, captured)
        }

        private fun captureParallel(
            sources: List<IndexedSource>,
            readBytes: (Path) -> ByteArray,
        ): SourceContentSnapshot {
            val entries = arrayOfNulls<Entry>(sources.size)
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
                                val bytes = readBytes(source.originRoot.resolve(source.path))
                                entries[index] =
                                    Entry(
                                        bytes = bytes,
                                        contentHash = FileHashProducer.contentHash(bytes),
                                    )
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
            return SourceContentSnapshot(ordered, sources.toList())
        }
    }
}
