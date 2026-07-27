package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.model.PluginFactValue
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginFactValueCodecTest {
    @Test
    fun `round trips nested plugin fact values`() {
        val value =
            PluginFactValue.Struct.of(
                mapOf(
                    "name" to PluginFactValue.Text.of("Button"),
                    "flags" to PluginFactValue.TextList.of(listOf("interactive", "selection")),
                    "enabled" to PluginFactValue.Bool.of(true),
                    "count" to PluginFactValue.Integer.of(2),
                )
            )

        assertEquals(value, PluginFactValueCodec.decode(PluginFactValueCodec.encode(value)))
    }
}
