package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.QueryOptions
import java.io.ByteArrayInputStream
import java.io.DataInputStream

internal object ExtensionCommandCodec {
    fun decode(payload: ByteArray): ExtensionCommand {
        val input = DataInputStream(ByteArrayInputStream(payload))
        return when (input.readUnsignedByte()) {
            ExtensionMessageCodec.CMD_RUN_CHECK -> ExtensionCommand.RunCheck
            ExtensionMessageCodec.CMD_PLUGIN_FACT_ENTRIES -> {
                val sessionToken = input.readUTF()
                val prefix = input.readUTF()
                val offset = input.readInt()
                val limit = input.readInt()
                ExtensionCommand.PluginFactEntries(
                    sessionToken = sessionToken,
                    prefix = prefix,
                    options = QueryOptions.page(limit, offset),
                )
            }
            ExtensionMessageCodec.CMD_PLUGIN_FACT_GET ->
                ExtensionCommand.PluginFactGet(
                    sessionToken = input.readUTF(),
                    key = input.readUTF(),
                )
            ExtensionMessageCodec.CMD_PING -> ExtensionCommand.Ping(sessionToken = input.readUTF())
            ExtensionMessageCodec.CMD_COMPLETE_FINDINGS -> {
                val count = input.readInt()
                require(count in 0..ExtensionProtocolConstants.MAX_FINDINGS_PER_CHECK) {
                    "Finding count $count exceeds limit"
                }
                ExtensionCommand.CompleteFindings(
                    (0 until count).map { ExtensionPayloadCodec.readFinding(input) }.toList()
                )
            }
            ExtensionMessageCodec.CMD_ERROR ->
                ExtensionCommand.Error(code = input.readUTF(), message = input.readUTF())
            ExtensionMessageCodec.CMD_CANCELLED -> ExtensionCommand.Cancelled
            else -> throw ExtensionProtocolException("Unknown extension command")
        }
    }
}

internal object ExtensionResponseCodec {
    fun encodeSuccess(payload: ByteArray): ByteArray = ExtensionMessageCodec.bytes {
        writeByte(ExtensionMessageCodec.RESP_SUCCESS)
        write(payload)
    }

    fun encodeError(code: String, message: String): ByteArray = ExtensionMessageCodec.bytes {
        writeByte(ExtensionMessageCodec.RESP_ERROR)
        writeUTF(code)
        writeUTF(message)
    }

    fun decode(payload: ByteArray): ExtensionResponse {
        val input = DataInputStream(ByteArrayInputStream(payload))
        return when (input.readUnsignedByte()) {
            ExtensionMessageCodec.RESP_SUCCESS -> {
                val length = input.available()
                ExtensionResponse.Success(
                    ByteArray(length).also { if (length > 0) input.readFully(it) }
                )
            }
            ExtensionMessageCodec.RESP_ERROR ->
                ExtensionResponse.Error(input.readUTF(), input.readUTF())
            else -> throw ExtensionProtocolException("Unknown extension response")
        }
    }
}

internal sealed interface ExtensionCommand {
    data object RunCheck : ExtensionCommand

    data class PluginFactEntries(
        val sessionToken: String,
        val prefix: String,
        val options: QueryOptions,
    ) : ExtensionCommand

    data class PluginFactGet(val sessionToken: String, val key: String) : ExtensionCommand

    data class Ping(val sessionToken: String) : ExtensionCommand

    data class CompleteFindings(val findings: List<Finding>) : ExtensionCommand

    data class Error(val code: String, val message: String) : ExtensionCommand

    data object Cancelled : ExtensionCommand
}

internal sealed interface ExtensionResponse {
    class Success(val payload: ByteArray) : ExtensionResponse

    class Error(val code: String, val message: String) : ExtensionResponse
}
