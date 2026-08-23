package dev.sebastiano.indexino.producer

import kotlin.io.path.readText

/** Immutable source content captured once for one refresh attempt. */
internal class SourceContentSnapshot
private constructor(private val entries: Map<IndexedSource, Entry>) {
    internal data class Entry(val content: String, val contentHash: String)

    fun content(source: IndexedSource): String =
        checkNotNull(entries[source]) {
                "Source was not captured: ${source.originId}:${source.path}"
            }
            .content

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
                    val content = source.originRoot.resolve(source.path).readText()
                    Entry(content, FileHashProducer.contentHash(content))
                }
            )
    }
}
