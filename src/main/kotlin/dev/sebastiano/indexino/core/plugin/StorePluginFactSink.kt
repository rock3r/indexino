package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.PluginFactSinkV1

internal class StorePluginFactSink(
    private val store: CodeIndexStore,
    private val pluginId: String,
    private val relativeFile: String,
) : PluginFactSinkV1 {
    override suspend fun put(key: String, value: PluginFactValue) {
        putAt(key, null, value)
    }

    override suspend fun putAt(key: String, range: SourceRange?, value: PluginFactValue) {
        store.put(
            CodeIndexKey.pluginFact(pluginId, relativeFile, key),
            PluginFactRecord(
                pluginId = pluginId,
                relativeFile = relativeFile,
                factKey = key,
                rangeStartLine = range?.start?.line,
                rangeStartColumn = range?.start?.column,
                rangeStartOffset = range?.start?.offset,
                rangeEndLine = range?.end?.line,
                rangeEndColumn = range?.end?.column,
                rangeEndOffset = range?.end?.offset,
                encodedValue = PluginFactValueCodec.encode(value),
            ),
        )
    }
}
