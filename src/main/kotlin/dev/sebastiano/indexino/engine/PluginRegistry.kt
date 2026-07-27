package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.api.IndexinoCheckV1
import dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider
import dev.sebastiano.indexino.plugin.api.IndexinoPluginRegistrar
import dev.sebastiano.indexino.plugin.api.PluginDescriptor
import dev.sebastiano.indexino.plugin.api.PostProcessorV1
import java.util.ServiceLoader

internal class PluginRegistry
private constructor(
    private val descriptors: Map<PluginId, PluginDescriptor>,
    internal val fileAnalyzers: List<RegisteredFileAnalyzer>,
    internal val postProcessors: List<PostProcessorV1>,
    internal val checks: List<RegisteredCheck>,
) {
    internal fun descriptor(id: PluginId): PluginDescriptor? = descriptors[id]

    internal fun pluginIds(): Set<PluginId> = descriptors.keys

    internal data class RegisteredFileAnalyzer(val pluginId: PluginId, val analyzer: FileAnalyzerV1)

    internal data class RegisteredCheck(val pluginId: PluginId, val check: IndexinoCheckV1)

    internal companion object {
        @OptIn(IndexinoInternalApi::class)
        internal fun load(classLoader: ClassLoader): PluginRegistry {
            val registrations =
                ServiceLoader.load(IndexinoPluginProvider::class.java, classLoader).map { provider
                    ->
                    IndexinoPluginRegistrar().also(provider::install)
                }
            val descriptors = registrations.map { it.descriptor() }
            require(descriptors.map { it.id }.distinct().size == descriptors.size) {
                "Plugin IDs must be unique"
            }
            return PluginRegistry(
                descriptors = descriptors.associateBy { it.id },
                fileAnalyzers =
                    registrations.flatMap { registration ->
                        registration.fileAnalyzers().map { analyzer ->
                            RegisteredFileAnalyzer(registration.descriptor().id, analyzer)
                        }
                    },
                postProcessors = registrations.flatMap { it.postProcessors() },
                checks =
                    registrations.flatMap { registration ->
                        registration.checks().map { check ->
                            RegisteredCheck(registration.descriptor().id, check)
                        }
                    },
            )
        }
    }
}
