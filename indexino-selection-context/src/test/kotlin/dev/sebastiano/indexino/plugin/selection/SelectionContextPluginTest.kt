package dev.sebastiano.indexino.plugin.selection

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.plugin.api.IndexinoPluginRegistrar
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(IndexinoInternalApi::class)
class SelectionContextPluginTest {
    @Test
    fun `provider is discoverable and registers selection descriptor`() {
        val provider =
            ServiceLoader.load(
                    dev.sebastiano.indexino.plugin.api.IndexinoPluginProvider::class.java
                )
                .single()
        val registrar = IndexinoPluginRegistrar()

        provider.install(registrar)

        assertEquals("dev.sebastiano.selection-context", registrar.descriptor().id.value)
    }
}
