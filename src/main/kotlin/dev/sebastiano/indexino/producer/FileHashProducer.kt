package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.readText

internal class FileHashProducer : IndexProducer {
    override val id: String = "file-hash"
    override val namespace: String = "file"
    override val displayName: String = "FileHashProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        context.changedSources.size
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val currentFiles = context.sources.map { it.originId to it.path }.toSet()
        store
            .prefixScan("file:")
            .filter { (_, record) ->
                record is FileHashRecord &&
                    (record.originId to record.relativePath !in currentFiles ||
                        context.changedSources.any {
                            it.originId == record.originId && it.path == record.relativePath
                        })
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
        val files = context.changedSources
        files.forEachIndexed { index, source ->
            context.reportFileProgress(index + 1, files.size, source.path)
            val hash = contentHash(context.readSource(source))
            store.put(
                CodeIndexKey.file("${source.originId}:${source.path}", hash),
                FileHashRecord(
                    relativePath = source.path,
                    contentHash = hash,
                    originId = source.originId,
                ),
            )
        }
    }

    companion object {
        fun contentHash(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            return "sha256:" + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        fun combinedSourcesHash(context: IndexBuildContext, sourceFiles: List<String>): String =
            combinedSourcesHash(
                workspaceRoot = context.workspaceRoot,
                sourceFiles = sourceFiles,
                sourceContentOverrides = context.sourceContentOverrides,
            )

        fun combinedIndexedSourcesHash(sources: List<IndexedSource>): String =
            contentHash(
                sources
                    .sortedWith(compareBy(IndexedSource::originId, IndexedSource::path))
                    .joinToString("\n") { source ->
                        val file = source.originRoot.resolve(source.path)
                        "${source.originId}:${source.path}:${contentHash(file.readText())}"
                    }
            )

        fun combinedSourcesHash(
            workspaceRoot: Path,
            sourceFiles: List<String>,
            sourceContentOverrides: Map<String, String> = emptyMap(),
            onFileProcessed: ((index: Int, total: Int, relativePath: String) -> Unit)? = null,
        ): String {
            val sortedSourceFiles = sourceFiles.sorted()
            val combined =
                sortedSourceFiles
                    .mapIndexed { index, path ->
                        onFileProcessed?.invoke(index + 1, sortedSourceFiles.size, path)
                        val content =
                            sourceContentOverrides[path] ?: workspaceRoot.resolve(path).readText()
                        "$path:${contentHash(content)}"
                    }
                    .joinToString("\n")
            return contentHash(combined)
        }
    }
}
