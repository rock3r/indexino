package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.SnapshotFreshness
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class PluginAnalyzerRunner(private val registry: PluginRegistry) {
    /**
     * Mutates build facts; the build owner checkpoints and restores the complete writable store.
     */
    @Suppress("LongMethod")
    internal fun analyze(
        context: IndexBuildContext,
        selectedPluginIds: Set<String>,
        fullAnalysisPlugins: Set<String> = emptySet(),
    ) {
        registry
            .pluginIds()
            .map { it.value }
            .filterNot(selectedPluginIds::contains)
            .forEach { pluginId ->
                val keys = mutableListOf<CodeIndexKey>()
                context.store.forEachWritablePrefix(
                    CodeIndexKey.pluginFactPluginPrefix(pluginId)
                ) { key, _ ->
                    keys += key
                    true
                }
                keys.forEach(context.store::delete)
            }
        val analyzersByPlugin =
            registry.fileAnalyzers
                .filter { it.pluginId.value in selectedPluginIds }
                .groupBy { it.pluginId.value }
        selectedPluginIds.forEach { pluginId ->
            val analyzers = analyzersByPlugin[pluginId].orEmpty()
            val filesToAnalyze =
                if (pluginId in fullAnalysisPlugins) context.sources else context.changedSources
            val affectedSources = filesToAnalyze + context.deletedSources
            val affectedFiles = affectedSources.mapTo(hashSetOf()) { it.originId to it.path }
            val keys = mutableListOf<CodeIndexKey>()
            context.store.forEachWritablePrefix(CodeIndexKey.pluginFactPluginPrefix(pluginId)) {
                key,
                record ->
                if (
                    record is PluginFactRecord &&
                        ((record.originId to record.relativeFile) in affectedFiles ||
                            record.relativeFile == POST_PROCESSOR_FILE)
                )
                    keys += key
                true
            }
            keys.forEach(context.store::delete)
            try {
                filesToAnalyze.forEach { source ->
                    analyzers.forEach { registered ->
                        runBlocking {
                            registered.analyzer.analyze(
                                FileAnalysisContextV1(
                                    file =
                                        SourceFile.of(
                                            SourceOriginId.of(source.originId),
                                            source.path,
                                            source.path,
                                        ),
                                    sourceText = context.readSource(source),
                                    facts =
                                        StorePluginFactSink(
                                            context.store,
                                            pluginId,
                                            source.path,
                                            source.originId,
                                        ),
                                    active = {
                                        coroutineContext.ensureActive()
                                        !Thread.currentThread().isInterrupted
                                    },
                                )
                            )
                        }
                    }
                }
            } finally {
                analyzers.forEach { analyzer ->
                    runCatching { (analyzer.analyzer as? AutoCloseable)?.close() }
                }
            }
            registry.postProcessors
                .filter {
                    it.pluginId.value == pluginId && it.processor.level == PostProcessLevelV1.SHARD
                }
                .forEach { registered ->
                    val buildQueries = context.buildQueriesSnapshot()
                    context.resolvedOriginIds
                        .ifEmpty { setOf("workspace") }
                        .forEach { originId ->
                            runBlocking {
                                registered.processor.process(
                                    PostProcessContextV1(
                                        queries = buildQueries,
                                        facts =
                                            StorePluginFactSink(
                                                context.store,
                                                pluginId,
                                                POST_PROCESSOR_FILE,
                                                originId,
                                            ),
                                        originId = SourceOriginId.of(originId),
                                        active = {
                                            coroutineContext.ensureActive()
                                            !Thread.currentThread().isInterrupted
                                        },
                                    )
                                )
                            }
                        }
                }
            registry.postProcessors
                .filter {
                    it.pluginId.value == pluginId &&
                        it.processor.level == PostProcessLevelV1.COMPOSITE
                }
                .forEach { registered ->
                    val buildQueries = context.buildQueriesSnapshot()
                    runBlocking {
                        registered.processor.process(
                            PostProcessContextV1(
                                queries = buildQueries,
                                facts =
                                    StorePluginFactSink(
                                        context.store,
                                        pluginId,
                                        POST_PROCESSOR_FILE,
                                    ),
                                active = {
                                    coroutineContext.ensureActive()
                                    !Thread.currentThread().isInterrupted
                                },
                            )
                        )
                    }
                }
        }
    }

    internal companion object {
        const val POST_PROCESSOR_FILE: String = "__postprocess__"
    }
}

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
private fun IndexBuildContext.buildQueriesSnapshot(): IndexSnapshot {
    val originIds = resolvedOriginIds.ifEmpty { setOf("workspace") }
    return IndexSnapshot.create(
        store = store,
        revision =
            WorkspaceRevision(
                fingerprint = commitHash,
                origins =
                    originIds
                        .map { originId ->
                            SourceOriginRevision(
                                originId = SourceOriginId.of(originId),
                                revision = commitHash,
                                stateFingerprint = commitHash,
                                expectedRevision = null,
                            )
                        }
                        .toList(),
            ),
        generation = WorkspaceGenerationId.of("build:$commitHash"),
        freshnessAtAcquisition = SnapshotFreshness.UNKNOWN,
    )
}
