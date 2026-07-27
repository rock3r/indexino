package dev.sebastiano.indexino.core.plugin

import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.PluginFactRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.model.PluginFactEntry
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceRange
import dev.sebastiano.indexino.plugin.api.PluginFactViewV1

@OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
internal class StorePluginFactView(
    private val store: CodeIndexStore,
    private val pluginId: String,
    private val relativeFile: String? = null,
) : PluginFactViewV1 {
    override suspend fun get(key: String): PluginFactValue? =
        store
            .prefixScan(pluginPrefix())
            .mapNotNull { (_, record) -> record as? PluginFactRecord }
            .firstOrNull { it.factKey == key }
            ?.let { PluginFactValueCodec.decode(it.encodedValue) }

    override suspend fun entries(
        prefix: String,
        options: QueryOptions,
    ): QueryPage<PluginFactEntry> {
        val allValues =
            store
                .prefixScan(pluginPrefix())
                .mapNotNull { (_, record) -> record as? PluginFactRecord }
                .filter { it.factKey.startsWith(prefix) }
                .sortedBy { it.factKey }
                .map { record ->
                    PluginFactEntry(
                        key = record.factKey,
                        range = record.toSourceRange(),
                        value = PluginFactValueCodec.decode(record.encodedValue),
                    )
                }
                .toList()
        val start = options.offset.coerceAtMost(allValues.size)
        val end = (start + options.limit).coerceAtMost(allValues.size)
        return QueryPage(
            items = allValues.subList(start, end),
            offset = options.offset,
            limit = options.limit,
            hasMore = end < allValues.size,
            nextCursor = null,
            totalCount = allValues.size,
        )
    }

    private fun pluginPrefix(): String =
        relativeFile?.let { CodeIndexKey.pluginFactFilePrefix(pluginId, it) } ?: "plugin:$pluginId:"

    private fun PluginFactRecord.toSourceRange(): SourceRange? {
        val startLine = rangeStartLine ?: return null
        val endLine = rangeEndLine ?: return null
        val file = SourceFile.of(WORKSPACE_ORIGIN, relativeFile, relativeFile)
        return SourceRange.of(
            SourceLocation.of(file, startLine, rangeStartColumn, rangeStartOffset),
            SourceLocation.of(file, endLine, rangeEndColumn, rangeEndOffset),
        )
    }

    private companion object {
        val WORKSPACE_ORIGIN: SourceOriginId = SourceOriginId.of("workspace")
    }
}
