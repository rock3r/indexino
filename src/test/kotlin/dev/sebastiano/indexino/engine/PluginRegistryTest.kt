package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.model.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRegistryTest {
    @Test
    fun `discovers bundled reference plugins`() {
        val registry = PluginRegistry.load(javaClass.classLoader)

        assertEquals(
            "Selection context",
            registry.descriptor(PluginId.of("dev.sebastiano.selection-context"))?.displayName,
        )
        assertEquals(
            "Compose decoration",
            registry.descriptor(PluginId.of("dev.sebastiano.compose-decoration"))?.displayName,
        )
        assertTrue(
            registry.checks.any {
                it.pluginId == PluginId.of("dev.sebastiano.selection-context") &&
                    it.check.id == "interactive-in-selection"
            }
        )
        assertTrue(
            registry.postProcessors.any {
                it.pluginId == PluginId.of("dev.sebastiano.compose-decoration") &&
                    it.processor.id == "modifier-chains"
            }
        )
    }
}
