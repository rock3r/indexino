package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
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
            (context.sourceFiles + context.deletedSourceFiles + POST_PROCESSOR_FILE).forEach {
                relativeFile ->
                context.store
                    .prefixScan(CodeIndexKey.pluginFactFilePrefix(pluginId, relativeFile))
                    .forEach { (key, _) -> context.store.delete(key) }
            }
            try {
                context.sourceFiles.forEach { relativeFile ->
                    analyzers.forEach { registered ->
                        runBlocking {
                            registered.analyzer.analyze(
                                FileAnalysisContextV1(
                                    file =
                                        SourceFile.of(
                                            SourceOriginId.of("workspace"),
                                            relativeFile,
                                            relativeFile,
                                        ),
                                    sourceText = context.readSource(relativeFile),
                                    facts =
                                        StorePluginFactSink(context.store, pluginId, relativeFile),
                                    active = { true },
                                )
                            )
                        }
                    }
                }
            } finally {
                analyzers.forEach { (it.analyzer as? AutoCloseable)?.close() }
            }
            registry.postProcessors
                .filter {
                    it.pluginId.value == pluginId &&
                        it.processor.level == PostProcessLevelV1.SHARD
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
