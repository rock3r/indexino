package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.PluginFactValue
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

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

    @Test
    fun `reads origin qualified plugin facts`() = runBlocking {
        val store =
            XodusCodeIndexStore.open(createTempDirectory("plugin-fact-origin-").resolve("store"))
        try {
            val value = PluginFactValue.Text.of("nested origin")
            StorePluginFactSink(
                    store = store,
                    pluginId = "dev.example.plugin",
                    relativeFile = "src/main/kotlin/Sample.kt",
                    originId = "git:nested",
                )
                .put("fact", value)

            assertEquals(
                value,
                StorePluginFactView(
                        store = store,
                        pluginId = "dev.example.plugin",
                        relativeFile = "src/main/kotlin/Sample.kt",
                    )
                    .get("fact"),
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun `rejects a fact tree deeper than the public maximum`() = runBlocking {
        val store =
            XodusCodeIndexStore.open(createTempDirectory("plugin-fact-depth-").resolve("store"))
        try {
            val value =
                (1..PluginFactValue.MAX_DEPTH).fold(
                    PluginFactValue.Text.of("leaf") as PluginFactValue
                ) { nested, _ ->
                    PluginFactValue.Struct.of(mapOf("nested" to nested))
                }

            assertFailsWith<IllegalArgumentException> {
                StorePluginFactSink(store, "dev.example.plugin", "Sample.kt").put("fact", value)
            }
        } finally {
            store.close()
        }
    }
}
