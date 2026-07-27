package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.model.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRegistryTest {
    @Test
    fun `discovers external selection plugin`() {
        val registry = PluginRegistry.load(javaClass.classLoader)

        assertEquals(
            "Selection context",
            registry.descriptor(PluginId.of("dev.sebastiano.selection-context"))?.displayName,
        )
        assertTrue(
            registry.checks.any {
                it.pluginId == PluginId.of("dev.sebastiano.selection-context") &&
                    it.check.id == "interactive-in-selection"
            }
        )
    }
}
