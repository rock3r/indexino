package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.FileAnalyzerV1
import dev.sebastiano.indexino.plugin.api.IndexinoCheckV1
import dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider
import dev.sebastiano.indexino.plugin.api.IndexinoPluginRegistrar
import dev.sebastiano.indexino.plugin.api.PluginDescriptor
import dev.sebastiano.indexino.plugin.api.PostProcessorV1
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
import java.util.Enumeration
import java.util.ServiceLoader
import java.util.jar.JarFile

internal class IsolatedPluginClassLoader(pluginJar: Path, parent: ClassLoader) :
    URLClassLoader(arrayOf(pluginJar.toUri().toURL()), parent) {
    override fun getResource(name: String): URL? =
        if (PARENT_FIRST_RESOURCE_PREFIXES.any(name::startsWith)) {
            parent.getResource(name) ?: findResource(name)
        } else {
            findResource(name) ?: parent.getResource(name)
        }

    override fun getResources(name: String): Enumeration<URL> {
        val local = findResources(name).toList()
        val inherited = parent.getResources(name).toList()
        val ordered =
            if (PARENT_FIRST_RESOURCE_PREFIXES.any(name::startsWith)) {
                inherited + local
            } else {
                local + inherited
            }
        return Collections.enumeration(ordered.distinct())
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name)
            val type =
                loaded
                    ?: if (PARENT_FIRST_PREFIXES.any(name::startsWith)) {
                        super.loadClass(name, false)
                    } else {
                        try {
                            findClass(name)
                        } catch (_: ClassNotFoundException) {
                            super.loadClass(name, false)
                        }
                    }
            if (resolve) resolveClass(type)
            type
        }

    private companion object {
        val PARENT_FIRST_PREFIXES =
            listOf(
                "java.",
                "javax.",
                "jdk.",
                "sun.",
                "kotlin.",
                "dev.sebastiano.indexino.model.",
                "dev.sebastiano.indexino.plugin.api.",
            )
        val PARENT_FIRST_RESOURCE_PREFIXES = PARENT_FIRST_PREFIXES.map { prefix ->
            prefix.replace('.', '/')
        }
    }
}

internal class PluginRegistry
internal constructor(
    private val descriptors: Map<PluginId, PluginDescriptor>,
    internal val fileAnalyzers: List<RegisteredFileAnalyzer>,
    internal val postProcessors: List<RegisteredPostProcessor>,
    internal val checks: List<RegisteredCheck>,
) {
    internal fun descriptor(id: PluginId): PluginDescriptor? = descriptors[id]

    internal fun pluginIds(): Set<PluginId> = descriptors.keys

    internal fun selectedCoordinates(selectedIds: Collection<String>): Map<String, String> =
        selectedIds.associateWith { pluginId ->
            descriptors[PluginId.of(pluginId)]?.let { descriptor ->
                "${descriptor.version}:${descriptor.factSchemaVersion.value}"
            } ?: "unavailable"
        }

    internal data class RegisteredFileAnalyzer(val pluginId: PluginId, val analyzer: FileAnalyzerV1)

    internal data class RegisteredCheck(val pluginId: PluginId, val check: IndexinoCheckV1)

    internal data class RegisteredPostProcessor(
        val pluginId: PluginId,
        val processor: PostProcessorV1,
    )

    internal companion object {
        private val HOST_BASIC_FACT_SCHEMA: BasicFactSchemaVersion =
            BasicFactSchemaVersion.of(BASIC_FACT_SCHEMA_VERSION)

        @OptIn(IndexinoInternalApi::class)
        internal fun load(classLoader: ClassLoader): PluginRegistry =
            registryOf(registrationsFrom(classLoader))

        @OptIn(IndexinoInternalApi::class)
        private fun registrationsFrom(
            classLoader: ClassLoader,
            accept: (Class<out IndexinoPluginProvider>) -> Boolean = { true },
        ): List<IndexinoPluginRegistrar> =
            ServiceLoader.load(IndexinoPluginProvider::class.java, classLoader)
                .stream()
                .filter { provider -> accept(provider.type()) }
                .map { provider -> IndexinoPluginRegistrar().also(provider.get()::install) }
                .toList()

        @OptIn(IndexinoInternalApi::class)
        private fun registryOf(registrations: List<IndexinoPluginRegistrar>): PluginRegistry {
            val descriptors = registrations.map { it.descriptor() }
            require(descriptors.map { it.id }.distinct().size == descriptors.size) {
                "Plugin IDs must be unique"
            }
            require(
                descriptors.all { it.requiredBasicFactSchema.value <= HOST_BASIC_FACT_SCHEMA.value }
            ) {
                "Plugin requires a newer basic fact schema"
            }
            return PluginRegistry(
                descriptors = descriptors.associateBy { it.id },
                fileAnalyzers =
                    registrations.flatMap { registration ->
                        registration.fileAnalyzers().map { analyzer ->
                            RegisteredFileAnalyzer(registration.descriptor().id, analyzer)
                        }
                    },
                postProcessors =
                    registrations.flatMap { registration ->
                        registration.postProcessors().map { processor ->
                            RegisteredPostProcessor(registration.descriptor().id, processor)
                        }
                    },
                checks =
                    registrations.flatMap { registration ->
                        registration.checks().map { check ->
                            RegisteredCheck(registration.descriptor().id, check)
                        }
                    },
            )
        }

        internal fun load(pluginJars: List<Path>, parent: ClassLoader): PluginRegistry =
            load(pluginJars, parent, PluginAbiSupport.load(parent))

        @OptIn(IndexinoInternalApi::class)
        internal fun load(
            pluginJars: List<Path>,
            parent: ClassLoader,
            abiSupport: PluginAbiSupport,
            classLoaderFactory: (Path, ClassLoader) -> ClassLoader = { pluginJar, loaderParent ->
                IsolatedPluginClassLoader(pluginJar, loaderParent)
            },
        ): PluginRegistry {
            pluginJars.forEach { pluginJar ->
                val target =
                    JarFile(pluginJar.toFile()).use { jar ->
                        jar.manifest?.mainAttributes?.getValue(PLUGIN_ABI_TARGET_ATTRIBUTE)
                    }
                        ?: throw PluginAbiCompatibilityException(
                            pluginId = pluginJar.fileName.toString(),
                            hostAbi = abiSupport.current.toString(),
                            targetAbi = "<missing>",
                            supportedRange = "[${abiSupport.minimum}, ${abiSupport.current}]",
                            remediation =
                                "Rebuild the plugin with Indexino-Plugin-ABI-Target generated " +
                                    "from its indexino-plugin-api dependency.",
                        )
                abiSupport.requireCompatible(pluginJar.fileName.toString(), target)
            }
            val pluginClassLoaders = pluginJars.map { classLoaderFactory(it, parent) }
            val registrations =
                registrationsFrom(parent) +
                    pluginClassLoaders.flatMap { pluginClassLoader ->
                        registrationsFrom(pluginClassLoader) { provider ->
                            provider.classLoader === pluginClassLoader
                        }
                    }
            return registryOf(registrations)
        }

        private const val PLUGIN_ABI_TARGET_ATTRIBUTE = "Indexino-Plugin-ABI-Target"
    }
}
