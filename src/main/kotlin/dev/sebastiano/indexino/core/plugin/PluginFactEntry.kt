package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.SourceRange

/**
 * Canonical plugin-fact key and record construction. Store-backed and buffered sinks must agree so
 * an eager analyzer pass and the regular plugin pass produce byte-identical facts.
 */
internal fun pluginFactEntry(
    pluginId: String,
    originId: String,
    relativeFile: String,
    factKey: String,
    range: SourceRange?,
    value: PluginFactValue,
): Pair<CodeIndexKey, PluginFactRecord> {
    require(value.depth() <= PluginFactValue.MAX_DEPTH) {
        "Plugin fact nesting exceeds maximum depth of ${PluginFactValue.MAX_DEPTH}"
    }
    val key =
        if (originId == "workspace") {
            CodeIndexKey.pluginFact(pluginId, relativeFile, factKey)
        } else {
            CodeIndexKey.pluginFact(pluginId, originId, relativeFile, factKey)
        }
    return key to
        PluginFactRecord(
            pluginId = pluginId,
            relativeFile = relativeFile,
            factKey = factKey,
            rangeStartLine = range?.start?.line,
            rangeStartColumn = range?.start?.column,
            rangeStartOffset = range?.start?.offset,
            rangeEndLine = range?.end?.line,
            rangeEndColumn = range?.end?.column,
            rangeEndOffset = range?.end?.offset,
            encodedValue = PluginFactValueCodec.encode(value),
            originId = originId,
        )
}

private fun PluginFactValue.depth(): Int =
    when (this) {
        is PluginFactValue.Struct -> 1 + fields.values.maxOfOrNull { it.depth() }.orEmpty()
        else -> 1
    }

private fun Int?.orEmpty(): Int = this ?: 0
