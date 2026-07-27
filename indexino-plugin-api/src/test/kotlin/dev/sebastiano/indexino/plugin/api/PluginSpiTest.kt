package dev.sebastiano.indexino.plugin.api

import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginFactSchemaVersion
import dev.sebastiano.indexino.model.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(IndexinoInternalApi::class)
class PluginSpiTest {
    @Test
    fun `provider registers its descriptor`() {
        val registrar = IndexinoPluginRegistrar()
        val descriptor =
            PluginDescriptor.of(
                id = PluginId.of("example.plugin"),
                version = "1",
                factSchemaVersion = PluginFactSchemaVersion.of(1),
                requiredBasicFactSchema = BasicFactSchemaVersion.of(1),
                producedNamespaces = setOf("example.plugin.facts"),
                displayName = "Example plugin",
            )

        IndexinoPluginProvider { it.plugin(descriptor) }.install(registrar)

        assertEquals(descriptor, registrar.descriptor())
    }
}
