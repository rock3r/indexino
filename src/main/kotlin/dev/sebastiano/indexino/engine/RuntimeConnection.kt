package dev.sebastiano.indexino.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** One internal local-runtime session. Public callers never see its frame format. */
internal class RuntimeConnection
private constructor(
    private val channel: SocketChannel,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : AutoCloseable {
    @Synchronized
    fun request(command: ByteArray): ByteArray {
        RuntimeFrameCodec.write(output, command)
        return RuntimeCommandResponseCodec.unwrap(RuntimeFrameCodec.read(input))
    }

    override fun close() {
        channel.close()
    }

    companion object {
        fun connect(endpoint: Path): RuntimeConnection {
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.connect(UnixDomainSocketAddress.of(endpoint))
                val input = DataInputStream(Channels.newInputStream(channel))
                val output = DataOutputStream(Channels.newOutputStream(channel))
                RuntimeFrameCodec.write(
                    output,
                    RuntimeHandshake(
                            major = RuntimeLeaseStore.PROTOCOL_MAJOR,
                            minor = RuntimeLeaseStore.PROTOCOL_MINOR,
                        )
                        .encode(),
                )
                when (
                    val response =
                        RuntimeHandshakeResponseCodec.decode(RuntimeFrameCodec.read(input))
                ) {
                    RuntimeHandshakeResponse.Accepted ->
                        return RuntimeConnection(channel, input, output)
                    is RuntimeHandshakeResponse.Rejected -> {
                        throw RuntimeProtocolException("${response.code}: ${response.message}")
                    }
                }
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                channel.close()
                throw thrown
            }
        }
    }
}
