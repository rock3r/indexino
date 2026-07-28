package dev.sebastiano.indexino.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** An opaque identity for one accepted AF_UNIX client connection. */
internal class RuntimeSession
internal constructor(internal val id: String = UUID.randomUUID().toString())

/**
 * Minimal AF_UNIX server that validates every peer before later runtime operations are dispatched.
 */
internal class RuntimeHandshakeServer(
    private val endpoint: Path,
    private val sessionCommandHandler: ((RuntimeSession, ByteArray) -> ByteArray)? = null,
    private val sessionDisconnected: (RuntimeSession) -> Unit = {},
    private val commandHandler: ((ByteArray) -> ByteArray)? = null,
) : AutoCloseable {
    private val running = AtomicBoolean()
    private var server: ServerSocketChannel? = null
    private var acceptThread: Thread? = null
    private val clientExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "indexino-runtime-client").apply { isDaemon = true }
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "Runtime handshake server is already running" }
        Files.createDirectories(endpoint.parent)
        Files.deleteIfExists(endpoint)
        try {
            server =
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
                    channel.bind(UnixDomainSocketAddress.of(endpoint))
                }
            acceptThread = Thread(::acceptLoop, "indexino-runtime-handshake").also(Thread::start)
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            running.set(false)
            server?.close()
            server = null
            Files.deleteIfExists(endpoint)
            throw thrown
        }
    }

    private fun acceptLoop() {
        while (running.get()) {
            val channel =
                try {
                    server?.accept()
                } catch (_: IOException) {
                    if (!running.get()) return
                    null
                }
            if (channel != null) {
                clientExecutor.execute {
                    try {
                        channel.use(::handle)
                    } catch (_: IOException) {
                        // A malformed frame or disconnected peer is isolated to this local client.
                    }
                }
            }
        }
    }

    private fun handle(channel: SocketChannel) {
        DataInputStream(Channels.newInputStream(channel)).use { input ->
            DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                val handshake = RuntimeHandshake.decode(RuntimeFrameCodec.read(input))
                val response = handshake.respond()
                RuntimeFrameCodec.write(output, RuntimeHandshakeResponseCodec.encode(response))
                if (
                    response != RuntimeHandshakeResponse.Accepted ||
                        (commandHandler == null && sessionCommandHandler == null)
                ) {
                    return
                }
                val session = RuntimeSession()
                try {
                    while (true) {
                        val command =
                            try {
                                RuntimeFrameCodec.read(input)
                            } catch (_: java.io.EOFException) {
                                return
                            }
                        val result =
                            try {
                                RuntimeCommandResponseCodec.success(
                                    sessionCommandHandler?.invoke(session, command)
                                        ?: checkNotNull(commandHandler).invoke(command)
                                )
                            } catch (failure: RuntimeProtocolException) {
                                RuntimeCommandResponseCodec.error(
                                    code = "INVALID_REQUEST",
                                    message = failure.message.orEmpty(),
                                )
                            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                                RuntimeCommandResponseCodec.error(
                                    code = "INTERNAL",
                                    message = failure.message ?: failure.javaClass.name,
                                )
                            }
                        RuntimeFrameCodec.write(output, result)
                    }
                } finally {
                    sessionDisconnected(session)
                }
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        server?.close()
        Files.deleteIfExists(endpoint)
        acceptThread?.join(THREAD_JOIN_TIMEOUT_MILLIS)
        clientExecutor.shutdownNow()
    }

    private companion object {
        const val THREAD_JOIN_TIMEOUT_MILLIS = 5_000L
    }
}
