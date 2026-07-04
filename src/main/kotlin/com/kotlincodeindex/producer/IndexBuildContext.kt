package com.kotlincodeindex.producer

import com.kotlincodeindex.core.store.CodeIndexStore
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText

data class IndexBuildContext(
    val store: CodeIndexStore,
    val commitHash: String,
    val scope: String = "",
    val sourceFiles: List<String>,
    val workspaceRoot: Path = Path("."),
    val sourceContentOverrides: Map<String, String> = emptyMap(),
    val progress: ((String) -> Unit)? = null,
) {
    fun readSource(relativePath: String): String =
        sourceContentOverrides[relativePath]
            ?: workspaceRoot.resolve(relativePath).readText()

    fun reportFileProgress(index: Int, total: Int, relativePath: String) {
        progress?.invoke("[$index/$total] $relativePath")
    }

    companion object {
        fun forInlineSources(
            store: CodeIndexStore,
            commitHash: String,
            sourceFiles: Map<String, String>,
            scope: String = "",
        ): IndexBuildContext = IndexBuildContext(
            store = store,
            commitHash = commitHash,
            scope = scope,
            sourceFiles = sourceFiles.keys.toList(),
            sourceContentOverrides = sourceFiles,
        )
    }
}
