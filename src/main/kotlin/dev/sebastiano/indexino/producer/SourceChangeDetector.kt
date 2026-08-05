package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal data class SourceChangeSet(
    val changedSources: Set<IndexedSource>,
    val deletedSources: Set<IndexedSource>,
) {
    val changedFiles: Set<String>
        get() = changedSources.mapTo(linkedSetOf()) { it.path }

    val deletedFiles: Set<String>
        get() = deletedSources.mapTo(linkedSetOf()) { it.path }
}

internal object SourceChangeDetector {
    fun detect(
        store: CodeIndexStore,
        sources: List<IndexedSource>,
        sourceSnapshot: SourceContentSnapshot? = null,
        onFileProcessed: ((index: Int, total: Int, source: IndexedSource) -> Unit)? = null,
    ): SourceChangeSet {
        val previousHashes =
            store
                .prefixScan("file:")
                .map { it.second }
                .filterIsInstance<FileHashRecord>()
                .associate { (it.originId to it.relativePath) to it.contentHash }
        val currentSources = sources.associateBy { it.originId to it.path }
        val changedSources =
            sources.filterIndexedTo(linkedSetOf()) { index, source ->
                onFileProcessed?.invoke(index + 1, sources.size, source)
                val currentHash =
                    sourceSnapshot?.contentHash(source)
                        ?: FileHashProducer.contentHash(
                            Files.readAllBytes(source.originRoot.resolve(source.path))
                        )
                previousHashes[source.originId to source.path] != currentHash
            }
        return SourceChangeSet(
            changedSources = changedSources,
            deletedSources =
                (previousHashes.keys - currentSources.keys).mapTo(linkedSetOf()) { (originId, path)
                    ->
                    IndexedSource(originId, Paths.get("."), path)
                },
        )
    }

    fun detect(
        store: CodeIndexStore,
        workspaceRoot: Path,
        sourceFiles: List<String>,
        onFileProcessed: ((index: Int, total: Int, source: IndexedSource) -> Unit)? = null,
    ): SourceChangeSet =
        detect(
            store,
            sourceFiles.map { IndexedSource.workspace(workspaceRoot, it) },
            sourceSnapshot = null,
            onFileProcessed,
        )
}
