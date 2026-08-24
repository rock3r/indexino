package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.engine.extension.DynamicPluginCatalog
import dev.sebastiano.indexino.engine.extension.PluginJarMetadata
import dev.sebastiano.indexino.model.PluginId
import java.nio.file.Path

internal fun CliktCommand.trustedPluginOption() =
    option(
            "--plugin",
            help =
                "Explicit trusted compiled plugin JAR (repeatable). " +
                    "Closed-world distributions run checks out-of-process.",
        )
        .file(mustExist = true, mustBeReadable = true)
        .multiple()

/** Registers explicit trusted plugin JARs from CLI `--plugin` before runtime attach. */
internal object CliTrustedPlugins {
    private val pluginJars = linkedSetOf<Path>()
    private val pluginIds = linkedSetOf<PluginId>()

    fun registerFromCli(paths: List<java.io.File>) {
        paths.map { it.toPath().toAbsolutePath().normalize() }.forEach(::register)
    }

    fun register(jar: Path) {
        pluginJars.add(jar)
    }

    fun install(parent: ClassLoader = CliTrustedPlugins::class.java.classLoader) {
        pluginJars.forEach { jar ->
            val descriptor = PluginJarMetadata.readDescriptor(jar, parent)
            DynamicPluginCatalog.register(descriptor.id, jar, descriptor)
            PluginRegistry.registerCliPluginJar(jar)
            pluginIds.add(descriptor.id)
        }
    }

    fun registeredPluginIds(): Set<PluginId> = pluginIds.toSet()

    fun clearForTests() {
        pluginJars.clear()
        pluginIds.clear()
        PluginRegistry.clearCliPluginJarsForTests()
        DynamicPluginCatalog.clearForTests()
    }
}
