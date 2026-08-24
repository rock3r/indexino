package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.model.PluginId
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginJarMetadataTest {
    @Test
    fun `selection context plugin jar exposes descriptor manifest metadata`() {
        val pluginJar = selectionContextJar()
        val descriptor =
            PluginJarMetadata.readDescriptor(
                pluginJar,
                PluginJarMetadataTest::class.java.classLoader,
            )
        assertEquals(PluginId.of("dev.sebastiano.selection-context"), descriptor.id)
        assertEquals("1", descriptor.version)
        assertEquals("Selection context", descriptor.displayName)
    }

    private fun selectionContextJar(): Path {
        val libs = Path.of("indexino-selection-context/build/libs")
        require(libs.toFile().isDirectory) {
            "Build indexino-selection-context first: ./gradlew :indexino-selection-context:jar"
        }
        return libs.listDirectoryEntries("indexino-selection-context-*.jar").single {
            !it.fileName.toString().contains("-sources") &&
                !it.fileName.toString().contains("-javadoc")
        }
    }
}
