package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.model.PluginId
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Explicit trusted dynamic plugin JAR paths registered at startup (CLI `--plugin`). */
internal object DynamicPluginCatalog {
    internal data class Registration(
        val jar: Path,
        val descriptor: dev.sebastiano.indexino.plugin.api.PluginDescriptor,
    )

    private val plugins = ConcurrentHashMap<PluginId, Registration>()

    fun register(
        pluginId: PluginId,
        jar: Path,
        descriptor: dev.sebastiano.indexino.plugin.api.PluginDescriptor,
    ) {
        require(jar.toFile().isFile) { "Plugin JAR does not exist: $jar" }
        require(descriptor.id == pluginId) { "Plugin descriptor id must match registration id" }
        plugins[pluginId] = Registration(jar.toAbsolutePath().normalize(), descriptor)
    }

    fun registrationFor(pluginId: PluginId): Registration? = plugins[pluginId]

    fun isDynamic(pluginId: PluginId): Boolean = plugins.containsKey(pluginId)

    fun clearForTests(): Unit = plugins.clear()
}
