package dev.sebastiano.indexino.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One internal local-runtime session. Public callers never see its frame format. */
internal class RuntimeConnection
private constructor(
    internal val endpoint: Path,
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
        fun connect(endpoint: Path): RuntimeConnection =
            connect(endpoint, SocketChannel.open(StandardProtocolFamily.UNIX))

        /** Bound only the bootstrap handshake; a connected session remains unrestricted. */
        fun connect(endpoint: Path, timeoutMillis: Long): RuntimeConnection {
            require(timeoutMillis > 0) { "Handshake timeout must be positive" }
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            val task = FutureTask { connect(endpoint, channel) }
            Thread.ofVirtual().start(task)
            return try {
                task.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (!task.cancel(true)) runCatching { task.get().close() }
                channel.close()
                throw SocketTimeoutException("Runtime handshake timed out after $timeoutMillis ms")
            } catch (thrown: InterruptedException) {
                if (!task.cancel(true)) runCatching { task.get().close() }
                channel.close()
                Thread.currentThread().interrupt()
                throw thrown
            } catch (thrown: ExecutionException) {
                throw thrown.cause ?: thrown
            }
        }

        private fun connect(endpoint: Path, channel: SocketChannel): RuntimeConnection {
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
                        return RuntimeConnection(endpoint, channel, input, output)
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
