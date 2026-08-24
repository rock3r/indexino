package dev.sebastiano.indexino.engine.extension

import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.QueryOptions
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

internal object ExtensionMessageCodec {
    const val CMD_RUN_CHECK: Int = 1
    const val CMD_PLUGIN_FACT_ENTRIES: Int = 2
    const val CMD_PLUGIN_FACT_GET: Int = 3
    const val CMD_PING: Int = 4
    const val CMD_COMPLETE_FINDINGS: Int = 5
    const val CMD_ERROR: Int = 6
    const val CMD_CANCELLED: Int = 7
    const val CMD_CANCEL: Int = 8
    const val CMD_SHUTDOWN: Int = 9

    const val RESP_SUCCESS: Int = 0
    const val RESP_ERROR: Int = 1

    fun encodeRunCheck(): ByteArray = byteArrayOf(CMD_RUN_CHECK.toByte())

    fun encodePing(sessionToken: String): ByteArray = bytes {
        writeByte(CMD_PING)
        writeUTF(sessionToken)
    }

    fun encodePluginFactEntries(
        sessionToken: String,
        prefix: String,
        options: QueryOptions,
    ): ByteArray = bytes {
        writeByte(CMD_PLUGIN_FACT_ENTRIES)
        writeUTF(sessionToken)
        writeUTF(prefix)
        writeInt(options.offset)
        writeInt(options.limit)
    }

    fun encodePluginFactGet(sessionToken: String, key: String): ByteArray = bytes {
        writeByte(CMD_PLUGIN_FACT_GET)
        writeUTF(sessionToken)
        writeUTF(key)
    }

    fun encodeComplete(findings: List<Finding>): ByteArray =
        ExtensionPayloadCodec.encodeComplete(findings)

    fun encodeError(code: String, message: String): ByteArray = bytes {
        writeByte(CMD_ERROR)
        writeUTF(code)
        writeUTF(message)
    }

    fun encodeCancelled(): ByteArray = byteArrayOf(CMD_CANCELLED.toByte())

    fun decodeCommand(payload: ByteArray): ExtensionCommand = ExtensionCommandCodec.decode(payload)

    fun encodeSuccess(payload: ByteArray): ByteArray = ExtensionResponseCodec.encodeSuccess(payload)

    fun encodeResponseError(code: String, message: String): ByteArray =
        ExtensionResponseCodec.encodeError(code, message)

    fun decodeResponse(payload: ByteArray): ExtensionResponse =
        ExtensionResponseCodec.decode(payload)

    internal fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(block)
        return out.toByteArray()
    }
}
