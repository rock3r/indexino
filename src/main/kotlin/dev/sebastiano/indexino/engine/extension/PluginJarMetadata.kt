@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.api.indexinoFailure
import dev.sebastiano.indexino.engine.PluginAbiSupport
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.PluginFactSchemaVersion
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.plugin.api.PluginDescriptor
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Reads plugin identity and descriptor metadata without loading plugin bytecode in closed-world.
 */
internal object PluginJarMetadata {
    private const val PLUGIN_ID = "Indexino-Plugin-Id"
    private const val PLUGIN_VERSION = "Indexino-Plugin-Version"
    private const val PLUGIN_FACT_SCHEMA = "Indexino-Plugin-Fact-Schema-Version"
    private const val PLUGIN_REQUIRED_BASIC = "Indexino-Plugin-Required-Basic-Fact-Schema"
    private const val PLUGIN_DISPLAY_NAME = "Indexino-Plugin-Display-Name"
    private const val PLUGIN_NAMESPACES = "Indexino-Plugin-Produced-Namespaces"
    private const val PLUGIN_ABI_TARGET = "Indexino-Plugin-ABI-Target"

    fun readDescriptor(jar: Path, parent: ClassLoader): PluginDescriptor {
        validateAbi(jar, parent)
        JarFile(jar.toFile()).use { pluginJar ->
            val attributes = pluginJar.manifest?.mainAttributes
            val pluginId = attributes?.getValue(PLUGIN_ID)
            if (pluginId != null) {
                return descriptorFromManifest(pluginId, attributes)
            }
        }
        if (DistributionCapabilities.requiresOutOfProcessExtensions()) {
            throw indexinoFailure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "plugin_descriptor_missing",
                message =
                    "Plugin JAR is missing $PLUGIN_ID manifest metadata required for " +
                        "closed-world dynamic extensions; rebuild the plugin with current " +
                        "indexino-plugin-api Gradle metadata",
                retryable = false,
            )
        }
        val registry = PluginRegistry.loadFromPluginJarsOnly(listOf(jar), parent)
        val pluginIds = registry.pluginIds()
        require(pluginIds.size == 1) {
            "Plugin JAR must declare exactly one plugin; found ${pluginIds.size} in $jar"
        }
        return checkNotNull(registry.descriptor(pluginIds.single())) {
            "Plugin descriptor is unavailable for $jar"
        }
    }

    fun validateAbi(jar: Path, parent: ClassLoader) {
        val abiSupport = PluginAbiSupport.load(parent)
        val target =
            JarFile(jar.toFile()).use { pluginJar ->
                pluginJar.manifest?.mainAttributes?.getValue(PLUGIN_ABI_TARGET)
            }
                ?: throw indexinoFailure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "plugin_abi_missing",
                    message =
                        "Plugin JAR is missing $PLUGIN_ABI_TARGET; rebuild the plugin against " +
                            "indexino-plugin-api",
                    retryable = false,
                )
        abiSupport.requireCompatible(jar.fileName.toString(), target)
    }

    private fun descriptorFromManifest(
        pluginId: String,
        attributes: java.util.jar.Attributes,
    ): PluginDescriptor {
        fun required(name: String): String =
            attributes.getValue(name)?.takeIf { it.isNotBlank() }
                ?: throw indexinoFailure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "plugin_descriptor_incomplete",
                    message = "Plugin JAR is missing manifest attribute $name",
                    retryable = false,
                )
        val namespaces =
            required(PLUGIN_NAMESPACES).split(',').map(String::trim).filter(String::isNotBlank)
        require(namespaces.isNotEmpty()) {
            "Plugin JAR manifest $PLUGIN_NAMESPACES must list at least one namespace"
        }
        return PluginDescriptor.of(
            id = PluginId.of(pluginId),
            version = required(PLUGIN_VERSION),
            factSchemaVersion = PluginFactSchemaVersion.of(required(PLUGIN_FACT_SCHEMA).toInt()),
            requiredBasicFactSchema =
                BasicFactSchemaVersion.of(required(PLUGIN_REQUIRED_BASIC).toInt()),
            producedNamespaces = namespaces.toSet(),
            displayName = required(PLUGIN_DISPLAY_NAME),
        )
    }
}
