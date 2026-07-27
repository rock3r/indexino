package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.plugin.StorePluginFactSink
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.plugin.api.FileAnalysisContextV1
import dev.sebastiano.indexino.producer.IndexBuildContext
import kotlinx.coroutines.runBlocking

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class PluginAnalyzerRunner(private val registry: PluginRegistry) {
    internal fun analyze(context: IndexBuildContext, selectedPluginIds: Set<String>) {
        registry.fileAnalyzers
            .filter { it.pluginId.value in selectedPluginIds }
            .forEach { registered ->
                val pluginId = registered.pluginId.value
                (context.sourceFiles + context.deletedSourceFiles).forEach { relativeFile ->
                    context.store
                        .prefixScan(CodeIndexKey.pluginFactFilePrefix(pluginId, relativeFile))
                        .forEach { (key, _) -> context.store.delete(key) }
                }
                context.sourceFiles.forEach { relativeFile ->
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
                                facts = StorePluginFactSink(context.store, pluginId, relativeFile),
                                active = { true },
                            )
                        )
                    }
                }
            }
    }
}
