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
    val reindexedSources: Set<IndexedSource> = emptySet(),
    /** Host hook for bundled Kotlin analyzers that run while a file's syntax tree is live. */
    val kotlinFileAnalysis: KotlinFileAnalysis? = null,
) {
    private val sourcesByIdentity by lazy {
        buildMap {
            sources.forEach { source -> putIfAbsent(source.originId to source.path, source) }
        }
    }

    fun findSource(originId: String, path: String): IndexedSource? =
        sourcesByIdentity[originId to path]

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

/**
 * Internal hand-off between the Kotlin parse pool and bundled file analyzers. Implementations run
 * analyzer work on the parse worker while the [org.jetbrains.kotlin.psi.KtFile] is alive and buffer
 * their facts so the producer can commit them with its own sorted write batch.
 */
internal interface KotlinFileAnalysis {
    /** Runs after the file's core walk, before the syntax tree can be collected. */
    fun analyze(source: IndexedSource, sourceText: String, file: org.jetbrains.kotlin.psi.KtFile)

    /** Emits plugin facts buffered by [analyze] into the producer's fact sink. */
    fun writeBufferedFacts(sink: FactSink)
}
