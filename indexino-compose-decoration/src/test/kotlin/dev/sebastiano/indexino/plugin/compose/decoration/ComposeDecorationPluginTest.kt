package dev.sebastiano.indexino.plugin.compose.decoration

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.plugin.api.IndexinoPluginRegistrar
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(IndexinoInternalApi::class)
class ComposeDecorationPluginTest {
    @Test
    fun `provider is discoverable and registers compose decoration descriptor`() {
        val provider =
            ServiceLoader.load(
                    dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider::class.java
                )
                .toList()
                .filterIsInstance<ComposeDecorationPlugin>()
                .single()
        val registrar = IndexinoPluginRegistrar()

        provider.install(registrar)

        assertEquals("dev.sebastiano.compose-decoration", registrar.descriptor().id.value)
    }
}
