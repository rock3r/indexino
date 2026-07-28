package dev.sebastiano.indexino.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

internal class RuntimeProtocolException(message: String) : IOException(message)

internal sealed interface RuntimeCommandResponse {
    class Success(val payload: ByteArray) : RuntimeCommandResponse

    class Error(val code: String, val message: String) : RuntimeCommandResponse
}

/** Internal command response envelope; handshake frames deliberately remain separate. */
internal object RuntimeCommandResponseCodec {
    private const val SUCCESS = 0
    private const val ERROR = 1

    fun success(payload: ByteArray): ByteArray = encode(RuntimeCommandResponse.Success(payload))

    fun error(code: String, message: String): ByteArray =
        encode(RuntimeCommandResponse.Error(code, message))

    fun unwrap(payload: ByteArray): ByteArray =
        when (val response = decode(payload)) {
            is RuntimeCommandResponse.Success -> response.payload
            is RuntimeCommandResponse.Error ->
                throw RuntimeProtocolException("${response.code}: ${response.message}")
        }

    fun decode(payload: ByteArray): RuntimeCommandResponse =
        DataInputStream(payload.inputStream()).use { input ->
            when (input.readUnsignedByte()) {
                SUCCESS -> RuntimeCommandResponse.Success(input.readBytes())
                ERROR -> RuntimeCommandResponse.Error(input.readUTF(), input.readUTF())
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
                }
            }
        }
        return bytes.toByteArray()
    }
}

internal object RuntimeFrameCodec {
    const val MAX_FRAME_BYTES = 1_048_576

    fun write(output: DataOutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "Runtime frame exceeds $MAX_FRAME_BYTES bytes" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
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
