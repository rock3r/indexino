package dev.sebastiano.indexino.producer

import java.nio.file.Files
import kotlin.text.Charsets

/** Consistent source hashes and lazily verified bytes inherited from an immutable generation. */
internal class SourceContentSnapshot
private constructor(private val entries: Map<IndexedSource, Entry>) {
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
        ): SourceContentSnapshot =
            SourceContentSnapshot(
                sources.associateWith { source ->
                    val previous = inheritedHashes[source]
                    if (
                        previous != null &&
                            source.originRoot.resolve(source.path).normalize() !in hintedPaths
                    )
                        Entry(bytes = null, contentHash = previous)
                    else {
                        val bytes = Files.readAllBytes(source.originRoot.resolve(source.path))
                        Entry(bytes = bytes, contentHash = FileHashProducer.contentHash(bytes))
                    }
                }
            )
    }
}
