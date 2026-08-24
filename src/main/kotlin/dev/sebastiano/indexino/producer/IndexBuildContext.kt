package dev.sebastiano.indexino.producer

import dev.sebastiano.indexino.core.store.CodeIndexStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.text.Charsets

internal data class IndexBuildContext(
    val store: CodeIndexStore,
    val commitHash: String,
    val scope: String = "",
    val sourceFiles: List<String>,
    val workspaceRoot: Path = Path("."),
    val sourceContentOverrides: Map<String, String> = emptyMap(),
    val sourceSnapshot: SourceContentSnapshot? = null,
    val sources: List<IndexedSource> = sourceFiles.map {
        IndexedSource.workspace(workspaceRoot, it)
    },
    val resolvedOriginIds: Set<String> = sources.mapTo(linkedSetOf()) { it.originId },
    val progress: ((String) -> Unit)? = null,
    val machineProgress: IndexBuildProgressReporter? = null,
    val activePhase: String? = null,
    val changedSourceFiles: Set<String> = sourceFiles.toSet(),
    val deletedSourceFiles: Set<String> = emptySet(),
    val changedSourceSet: Set<IndexedSource>? = null,
    val deletedSourceSet: Set<IndexedSource>? = null,
) {
    val changedSources: Set<IndexedSource> =
        changedSourceSet
            ?: sources.filterTo(linkedSetOf()) { source ->
                source.path in changedSourceFiles ||
                    runCatching {
                            workspaceRoot
                                .relativize(source.originRoot.resolve(source.path))
                                .toString()
                                .replace('\\', '/')
                        }
                        .getOrNull() in changedSourceFiles
            }

    val deletedSources: Set<IndexedSource> =
        deletedSourceSet
            ?: deletedSourceFiles.mapTo(linkedSetOf()) {
                IndexedSource.workspace(workspaceRoot, it)
            }

    fun readSource(source: IndexedSource): String =
        sourceSnapshot?.content(source)
            ?: sourceContentOverrides[source.path]
            ?: source.originRoot.resolve(source.path).readText()

    fun sourceHash(source: IndexedSource): String =
        sourceSnapshot?.contentHash(source) ?: FileHashProducer.contentHash(readSourceBytes(source))

    fun readSourceBytes(source: IndexedSource): ByteArray =
        sourceSnapshot?.contentBytes(source)
            ?: sourceContentOverrides[source.path]?.toByteArray(Charsets.UTF_8)
            ?: Files.readAllBytes(source.originRoot.resolve(source.path))

    fun reportFileProgress(index: Int, total: Int, source: IndexedSource) {
        progress?.invoke("[$index/$total] ${source.originId}:${source.path}")
        activePhase?.let {
            machineProgress?.fileProgress(it, index, total, source.originId, source.path)
        }
    }

    companion object {
        fun forInlineSources(
            store: CodeIndexStore,
            commitHash: String,
            sourceFiles: Map<String, String>,
            scope: String = "",
        ): IndexBuildContext =
            IndexBuildContext(
                store = store,
                commitHash = commitHash,
                scope = scope,
                sourceFiles = sourceFiles.keys.toList(),
                sourceContentOverrides = sourceFiles,
            )
    }
}
