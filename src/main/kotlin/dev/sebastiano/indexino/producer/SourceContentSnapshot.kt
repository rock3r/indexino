package dev.sebastiano.indexino.producer

import java.nio.file.Files
import kotlin.text.Charsets

/** Immutable source content captured once for one refresh attempt. */
internal class SourceContentSnapshot
private constructor(private val entries: Map<IndexedSource, Entry>) {
    internal data class Entry(val bytes: ByteArray, val contentHash: String)

    fun content(source: IndexedSource): String =
        checkNotNull(entries[source]) {
                "Source was not captured: ${source.originId}:${source.path}"
            }
            .bytes
            .toString(Charsets.UTF_8)

    fun contentBytes(source: IndexedSource): ByteArray =
        checkNotNull(entries[source]) {
                "Source was not captured: ${source.originId}:${source.path}"
            }
            .bytes

    fun contentHash(source: IndexedSource): String =
        checkNotNull(entries[source]) {
                "Source was not captured: ${source.originId}:${source.path}"
            }
            .contentHash

    fun combinedHash(): String =
        FileHashProducer.contentHash(
            entries.keys
                .sortedWith(compareBy(IndexedSource::originId, IndexedSource::path))
                .joinToString("\n") { source ->
                    "${source.originId}:${source.path}:${contentHash(source)}"
                }
        )

    companion object {
        fun capture(sources: List<IndexedSource>): SourceContentSnapshot =
            SourceContentSnapshot(
                sources.associateWith { source ->
                    val bytes = Files.readAllBytes(source.originRoot.resolve(source.path))
                    Entry(bytes = bytes, contentHash = FileHashProducer.contentHash(bytes))
                }
            )
    }
}
