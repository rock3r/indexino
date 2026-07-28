package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.model.IndexFailure
import dev.sebastiano.indexino.model.IndexinoInternalApi
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal class RuntimeProtocolException(message: String, val failure: IndexFailure? = null) :
    IOException(message)

internal sealed interface RuntimeCommandResponse {
    class Success(val payload: ByteArray) : RuntimeCommandResponse

    class Error(val code: String, val message: String, val failure: IndexFailure? = null) :
        RuntimeCommandResponse
}

/** Internal command response envelope; handshake frames deliberately remain separate. */
internal object RuntimeCommandResponseCodec {
    private const val SUCCESS = 0
    private const val ERROR = 1

    fun success(payload: ByteArray): ByteArray = encode(RuntimeCommandResponse.Success(payload))

    fun error(code: String, message: String): ByteArray =
        encode(RuntimeCommandResponse.Error(code, message))

    @OptIn(IndexinoInternalApi::class)
    fun structuredError(failure: IndexFailure): ByteArray =
        encode(RuntimeCommandResponse.Error(failure.code, failure.message, failure))

    fun unwrap(payload: ByteArray): ByteArray =
        when (val response = decode(payload)) {
            is RuntimeCommandResponse.Success -> response.payload
            is RuntimeCommandResponse.Error ->
                throw RuntimeProtocolException(
                    "${response.code}: ${response.message}",
                    response.failure,
                )
        }

    @OptIn(IndexinoInternalApi::class)
    fun decode(payload: ByteArray): RuntimeCommandResponse =
        DataInputStream(payload.inputStream()).use { input ->
            when (input.readUnsignedByte()) {
                SUCCESS -> RuntimeCommandResponse.Success(input.readBytes())
                ERROR -> {
                    val code = input.readUTF()
                    val message = input.readUTF()
                    val failure =
                        if (input.readBoolean()) {
                            IndexFailure.of(
                                category = decodeCategory(input.readUTF()),
                                code = input.readUTF(),
                                message = input.readUTF(),
                                retryable = input.readBoolean(),
                            )
                        } else {
                            null
                        }
                    RuntimeCommandResponse.Error(code, message, failure)
                }
                else -> throw RuntimeProtocolException("Unknown runtime command response")
            }
        }

    private fun encode(response: RuntimeCommandResponse): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            when (response) {
                is RuntimeCommandResponse.Success -> {
                    output.writeByte(SUCCESS)
                    output.write(response.payload)
                }
                is RuntimeCommandResponse.Error -> {
                    output.writeByte(ERROR)
                    output.writeUTF(response.code)
                    output.writeUTF(response.message)
                    output.writeBoolean(response.failure != null)
                    response.failure?.let { failure ->
                        output.writeUTF(failure.category.value)
                        output.writeUTF(failure.code)
                        output.writeUTF(failure.message)
                        output.writeBoolean(failure.retryable)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    @OptIn(IndexinoInternalApi::class)
    private fun decodeCategory(value: String): dev.sebastiano.indexino.model.IndexFailureCategory =
        when (value) {
            "INVALID_REQUEST" -> dev.sebastiano.indexino.model.IndexFailureCategory.INVALID_REQUEST
            "INDEX_NOT_FOUND" -> dev.sebastiano.indexino.model.IndexFailureCategory.INDEX_NOT_FOUND
            "TOPOLOGY" -> dev.sebastiano.indexino.model.IndexFailureCategory.TOPOLOGY
            "STORAGE_BUSY" -> dev.sebastiano.indexino.model.IndexFailureCategory.STORAGE_BUSY
            "IO" -> dev.sebastiano.indexino.model.IndexFailureCategory.IO
            "PARSE" -> dev.sebastiano.indexino.model.IndexFailureCategory.PARSE
            "PLUGIN" -> dev.sebastiano.indexino.model.IndexFailureCategory.PLUGIN
            "SCRIPT" -> dev.sebastiano.indexino.model.IndexFailureCategory.SCRIPT
            "WORKSPACE_LOST" -> dev.sebastiano.indexino.model.IndexFailureCategory.WORKSPACE_LOST
            "CLOSED" -> dev.sebastiano.indexino.model.IndexFailureCategory.CLOSED
            else -> dev.sebastiano.indexino.model.IndexFailureCategory.INTERNAL
        }
}

internal object RuntimeFrameCodec {
    const val MAX_FRAME_BYTES = 1_048_576
    private const val MAX_MESSAGE_BYTES = 64 * 1_048_576
    private const val CHUNKED_MESSAGE = Int.MIN_VALUE

    fun write(output: DataOutputStream, payload: ByteArray) {
        if (payload.size <= MAX_FRAME_BYTES) {
            output.writeInt(payload.size)
            output.write(payload)
        } else {
            require(payload.size <= MAX_MESSAGE_BYTES) {
                "Runtime message exceeds $MAX_MESSAGE_BYTES bytes"
            }
            output.writeInt(CHUNKED_MESSAGE)
            output.writeInt(payload.size)
            var offset = 0
            while (offset < payload.size) {
                val count = minOf(MAX_FRAME_BYTES, payload.size - offset)
                output.write(payload, offset, count)
                offset += count
            }
        }
        output.flush()
    }

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length == CHUNKED_MESSAGE) {
            val messageLength = input.readInt()
            if (messageLength < 0 || messageLength > MAX_MESSAGE_BYTES) {
                throw RuntimeProtocolException("Runtime message length $messageLength is invalid")
            }
            return ByteArray(messageLength).also(input::readFully)
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw RuntimeProtocolException("Runtime frame length $length is invalid")
        }
        return ByteArray(length).also(input::readFully)
    }
}

internal class RuntimeHandshake(private val major: Int, private val minor: Int) {
    fun encode(): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES * 2).putInt(major).putInt(minor).array()

    fun respond(): RuntimeHandshakeResponse =
        if (major == RuntimeLeaseStore.PROTOCOL_MAJOR) {
            RuntimeHandshakeResponse.Accepted
        } else {
            RuntimeHandshakeResponse.Rejected(
                code = "INVALID_REQUEST",
                message =
                    "Runtime protocol major $major is incompatible with host major " +
                        "${RuntimeLeaseStore.PROTOCOL_MAJOR}; restart Indexino with a matching version",
            )
        }

    companion object {
        fun decode(payload: ByteArray): RuntimeHandshake {
            if (payload.size != Int.SIZE_BYTES * 2) {
                throw RuntimeProtocolException(
                    "Runtime handshake must contain major and minor versions"
                )
            }
            val buffer = ByteBuffer.wrap(payload)
            return RuntimeHandshake(buffer.int, buffer.int)
        }
    }
}

internal sealed interface RuntimeHandshakeResponse {
    data object Accepted : RuntimeHandshakeResponse

    class Rejected(val code: String, val message: String) : RuntimeHandshakeResponse
}

internal object RuntimeHandshakeResponseCodec {
    private const val ACCEPTED = 1
    private const val REJECTED = 0

    fun encode(response: RuntimeHandshakeResponse): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            when (response) {
                RuntimeHandshakeResponse.Accepted -> output.writeByte(ACCEPTED)
                is RuntimeHandshakeResponse.Rejected -> {
                    output.writeByte(REJECTED)
                    output.writeUTF(response.code)
                    output.writeUTF(response.message)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(payload: ByteArray): RuntimeHandshakeResponse =
        DataInputStream(payload.inputStream()).use { input ->
            when (input.readUnsignedByte()) {
                ACCEPTED -> RuntimeHandshakeResponse.Accepted
                REJECTED -> RuntimeHandshakeResponse.Rejected(input.readUTF(), input.readUTF())
                else -> throw RuntimeProtocolException("Unknown runtime handshake response")
            }
        }
}
