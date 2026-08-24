@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.core.plugin.PluginFactValueCodec
import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.PluginFactEntry
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.SourceRange
import java.io.DataInputStream
import java.io.DataOutputStream

internal object ExtensionPayloadCodec {
    @OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
    fun encodeComplete(findings: List<Finding>): ByteArray = ExtensionMessageCodec.bytes {
        writeByte(ExtensionMessageCodec.CMD_COMPLETE_FINDINGS)
        writeInt(findings.size)
        findings.forEach { finding -> writeFinding(this, finding) }
    }

    fun encodeFactEntries(page: QueryPage<PluginFactEntry>): ByteArray =
        ExtensionMessageCodec.bytes {
            writeInt(page.items.size)
            page.items.forEach { entry ->
                writeUTF(entry.key)
                writeBoolean(entry.range != null)
                entry.range?.let { range -> writeRange(this, range) }
                writeUTF(PluginFactValueCodec.encode(entry.value))
            }
            writeBoolean(page.hasMore)
            writeInt(page.totalCount ?: page.items.size)
        }

    fun decodeFactEntries(payload: ByteArray): QueryPage<PluginFactEntry> {
        val input = java.io.ByteArrayInputStream(payload).let(::DataInputStream)
        val count = input.readInt()
        val items =
            (0 until count).map {
                val key = input.readUTF()
                val range = if (input.readBoolean()) readRange(input) else null
                PluginFactEntry(
                    key = key,
                    range = range,
                    value = PluginFactValueCodec.decode(input.readUTF()),
                )
            }
        val hasMore = input.readBoolean()
        val total = input.readInt()
        return QueryPage(
            items = items,
            offset = 0,
            limit = items.size,
            hasMore = hasMore,
            nextCursor = null,
            totalCount = total,
        )
    }

    fun encodeFactValue(value: PluginFactValue?): ByteArray = ExtensionMessageCodec.bytes {
        writeBoolean(value != null)
        if (value != null) writeUTF(PluginFactValueCodec.encode(value))
    }

    fun decodeFactValue(payload: ByteArray): PluginFactValue? {
        val input = java.io.ByteArrayInputStream(payload).let(::DataInputStream)
        return if (input.readBoolean()) PluginFactValueCodec.decode(input.readUTF()) else null
    }

    @OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
    fun readFinding(input: DataInputStream): Finding =
        Finding(
            plugin = PluginId.of(input.readUTF()),
            checkId = input.readUTF(),
            message = input.readUTF(),
            range = if (input.readBoolean()) readRange(input) else null,
            properties =
                buildMap { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } },
        )

    @OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)
    fun writeFinding(output: DataOutputStream, finding: Finding) {
        output.writeUTF(finding.plugin.value)
        output.writeUTF(finding.checkId)
        output.writeUTF(finding.message)
        output.writeBoolean(finding.range != null)
        finding.range?.let { range -> writeRange(output, range) }
        output.writeInt(finding.properties.size)
        finding.properties.forEach { (key, value) ->
            output.writeUTF(key)
            output.writeUTF(value)
        }
    }

    private fun writeRange(output: DataOutputStream, range: SourceRange) {
        writeLocation(output, range.start)
        writeLocation(output, range.end)
    }

    private fun readRange(input: DataInputStream): SourceRange =
        SourceRange.of(readLocation(input), readLocation(input))

    private fun writeLocation(
        output: DataOutputStream,
        location: dev.sebastiano.indexino.model.SourceLocation,
    ) {
        output.writeUTF(location.file.originId.value)
        output.writeUTF(location.file.path)
        output.writeUTF(location.file.displayPath)
        output.writeInt(location.line)
        output.writeInt(location.column ?: ABSENT_COORDINATE)
        output.writeInt(location.offset ?: ABSENT_COORDINATE)
    }

    private fun readLocation(input: DataInputStream): dev.sebastiano.indexino.model.SourceLocation {
        val file =
            dev.sebastiano.indexino.model.SourceFile.of(
                dev.sebastiano.indexino.model.SourceOriginId.of(input.readUTF()),
                input.readUTF(),
                input.readUTF(),
            )
        val line = input.readInt()
        val column = input.readInt().takeIf { it >= 1 }
        val offset = input.readInt().takeIf { it >= 0 }
        return dev.sebastiano.indexino.model.SourceLocation.of(file, line, column, offset)
    }

    private const val ABSENT_COORDINATE = -1
}
