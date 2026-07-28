package dev.sebastiano.indexino.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeHandshakeServerTest {
    @Test
    fun `server dispatches frames only after an accepted handshake`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-command-server-")
        val endpoint =
            RuntimePaths.socketPath(cacheRoot, "e".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        val server =
            RuntimeHandshakeServer(endpoint) { payload ->
                byteArrayOf(payload[2], payload[1], payload[0])
            }
        try {
            server.start()
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(endpoint))
                DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                    RuntimeFrameCodec.write(
                        output,
                        RuntimeHandshake(RuntimeLeaseStore.PROTOCOL_MAJOR, minor = 0).encode(),
                    )
                    DataInputStream(Channels.newInputStream(channel)).use { input ->
                        assertEquals(
                            RuntimeHandshakeResponse.Accepted,
                            RuntimeHandshakeResponseCodec.decode(RuntimeFrameCodec.read(input)),
                        )
                        RuntimeFrameCodec.write(output, byteArrayOf(1, 2, 3))
                        assertEquals(
                            listOf<Byte>(3, 2, 1),
                            RuntimeCommandResponseCodec.unwrap(RuntimeFrameCodec.read(input))
                                .toList(),
                        )
                    }
                }
            }
        } finally {
            server.close()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server rejects incompatible protocol majors without exposing a TCP endpoint`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-handshake-server-")
        val endpoint =
            RuntimePaths.socketPath(cacheRoot, "c".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        val server = RuntimeHandshakeServer(endpoint)
        try {
            server.start()
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(endpoint))
                val response =
                    DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                        RuntimeFrameCodec.write(
                            output,
                            RuntimeHandshake(RuntimeLeaseStore.PROTOCOL_MAJOR + 1, minor = 0)
                                .encode(),
                        )
                        DataInputStream(Channels.newInputStream(channel)).use { input ->
                            RuntimeHandshakeResponseCodec.decode(RuntimeFrameCodec.read(input))
                        }
                    }

                val rejected = assertIs<RuntimeHandshakeResponse.Rejected>(response)
                assertEquals("INVALID_REQUEST", rejected.code)
            }
        } finally {
            server.close()
            cacheRoot.toFile().deleteRecursively()
        }
    }
}
