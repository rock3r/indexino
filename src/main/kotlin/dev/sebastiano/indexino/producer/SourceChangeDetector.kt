package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.record.FileHashRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Path
import kotlin.io.path.readText

internal data class SourceChangeSet(val changedFiles: Set<String>, val deletedFiles: Set<String>)

internal object SourceChangeDetector {
    fun detect(
        store: CodeIndexStore,
        workspaceRoot: Path,
        sourceFiles: List<String>,
        onFileProcessed: ((index: Int, total: Int, relativePath: String) -> Unit)? = null,
    ): SourceChangeSet {
        val previousHashes =
            store
                .prefixScan("file:")
                .map { it.second }
                .filterIsInstance<FileHashRecord>()
                .associate { it.relativePath to it.contentHash }
        val currentFiles = sourceFiles.toSet()
        val changedFiles =
            sourceFiles.filterIndexedTo(linkedSetOf()) { index, relativePath ->
                onFileProcessed?.invoke(index + 1, sourceFiles.size, relativePath)
                val currentHash =
                    FileHashProducer.contentHash(workspaceRoot.resolve(relativePath).readText())
                previousHashes[relativePath] != currentHash
            }
        return SourceChangeSet(
            changedFiles = changedFiles,
            deletedFiles = previousHashes.keys - currentFiles,
        )
    }
}
