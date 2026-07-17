package com.kotlincodeindex.producer

import com.kotlincodeindex.core.key.CodeIndexKey
import com.kotlincodeindex.core.record.FileHashRecord
import com.kotlincodeindex.core.store.CodeIndexStore
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.readText

class FileHashProducer : IndexProducer {
    override val id: String = "file-hash"
    override val namespace: String = "file"
    override val displayName: String = "FileHashProducer"

    override val progressTotal: (IndexBuildContext) -> Int = { context ->
        context.sourceFiles.count { it in context.changedSourceFiles }
    }

    override fun produce(context: IndexBuildContext, store: CodeIndexStore) {
        val currentFiles = context.sourceFiles.toSet()
        store
            .prefixScan("file:")
            .filter { (_, record) ->
                record is FileHashRecord &&
                    (record.relativePath !in currentFiles ||
                        record.relativePath in context.changedSourceFiles)
            }
            .map { it.first }
            .toList()
            .forEach(store::delete)
        val files = context.sourceFiles.filter { it in context.changedSourceFiles }
        files.forEachIndexed { index, relativePath ->
            context.reportFileProgress(index + 1, files.size, relativePath)
            val content = context.readSource(relativePath)
            val hash = contentHash(content)
            store.put(
                CodeIndexKey.file(relativePath, hash),
                FileHashRecord(relativePath = relativePath, contentHash = hash),
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
