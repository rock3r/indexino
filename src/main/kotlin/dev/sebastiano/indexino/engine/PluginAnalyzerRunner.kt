package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessContextV1
import dev.sebastiano.indexino.plugin.api.PostProcessLevelV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import kotlinx.coroutines.runBlocking

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class PluginAnalyzerRunner(private val registry: PluginRegistry) {
    internal fun analyze(context: IndexBuildContext, selectedPluginIds: Set<String>) {
        val previousPluginFacts = context.store.prefixScan("plugin:").toList()
        try {
            analyzeMutable(context, selectedPluginIds)
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            context.store.prefixScan("plugin:").forEach { (key, _) -> context.store.delete(key) }
            previousPluginFacts.forEach { (key, record) -> context.store.put(key, record) }
            throw failure
        }
    }

    @Suppress("LongMethod")
    private fun analyzeMutable(context: IndexBuildContext, selectedPluginIds: Set<String>) {
        registry
            .pluginIds()
            .map { it.value }
            .filterNot(selectedPluginIds::contains)
            .forEach { pluginId ->
                context.store.prefixScan(CodeIndexKey.pluginFactPluginPrefix(pluginId)).forEach {
                    (key, _) ->
                    context.store.delete(key)
                }
            }
        val analyzersByPlugin =
            registry.fileAnalyzers
                .filter { it.pluginId.value in selectedPluginIds }
                .groupBy { it.pluginId.value }
        selectedPluginIds.forEach { pluginId ->
            val analyzers = analyzersByPlugin[pluginId].orEmpty()
            val affectedSources = context.sources + context.deletedSources
            context.store
                .prefixScan(CodeIndexKey.pluginFactPluginPrefix(pluginId))
                .filter { (_, record) ->
                    record is PluginFactRecord &&
                        affectedSources.any { source ->
                            record.originId == source.originId && record.relativeFile == source.path
                        }
                }
                .map { it.first }
                .toList()
                .forEach(context.store::delete)
            context.store
                .prefixScan(CodeIndexKey.pluginFactPluginPrefix(pluginId))
                .filter { (_, record) ->
                    record is PluginFactRecord && record.relativeFile == POST_PROCESSOR_FILE
                }
                .map { it.first }
                .toList()
                .forEach(context.store::delete)
            try {
                context.sources.forEach { source ->
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
                                    active = { true },
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
                    context.resolvedOriginIds
                        .ifEmpty { setOf("workspace") }
                        .forEach { originId ->
                            runBlocking {
                                registered.processor.process(
                                    PostProcessContextV1(
                                        facts =
                                            StorePluginFactSink(
                                                context.store,
                                                pluginId,
                                                POST_PROCESSOR_FILE,
                                                originId,
                                            ),
                                        originId = SourceOriginId.of(originId),
                                        active = { true },
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
                    runBlocking {
                        registered.processor.process(
                            PostProcessContextV1(
                                facts =
                                    StorePluginFactSink(
                                        context.store,
                                        pluginId,
                                        POST_PROCESSOR_FILE,
                                    ),
                                active = { true },
                            )
                        )
                    }
                }
        }
    }

    private companion object {
        const val POST_PROCESSOR_FILE: String = "__postprocess__"
    }
}
