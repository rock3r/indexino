package com.kotlincodeindex.producer

import com.kotlincodeindex.core.record.FileHashRecord
import com.kotlincodeindex.core.store.CodeIndexStore
import java.nio.file.Path
import kotlin.io.path.readText

data class SourceChangeSet(val changedFiles: Set<String>, val deletedFiles: Set<String>)

object SourceChangeDetector {
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
