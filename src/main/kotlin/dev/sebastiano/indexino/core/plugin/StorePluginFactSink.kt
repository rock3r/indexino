package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.PluginFactSinkV1

internal class StorePluginFactSink(
    private val store: CodeIndexStore,
    private val pluginId: String,
    private val relativeFile: String,
    private val originId: String = "workspace",
) : PluginFactSinkV1 {
    override suspend fun put(key: String, value: PluginFactValue) {
        putAt(key, null, value)
    }

    override suspend fun putAt(key: String, range: SourceRange?, value: PluginFactValue) {
        val (factKey, record) = pluginFactEntry(pluginId, originId, relativeFile, key, range, value)
        store.put(factKey, record)
    }
}
