package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeConnectionTest {
    @Test
    fun `command errors are framed and do not close the session`() {
        val cacheRoot =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-command-error-")
        val endpoint =
            RuntimePaths.socketPath(cacheRoot, "d".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        RuntimeHandshakeServer(endpoint) { payload ->
                if (payload.singleOrNull() == 0.toByte()) {
                    throw RuntimeProtocolException("unknown command")
                }
                payload.reversedArray()
            }
            .use { server ->
                server.start()

                RuntimeConnection.connect(endpoint).use { client ->
                    val error =
                        assertFailsWith<RuntimeProtocolException> { client.request(byteArrayOf(0)) }
                    assertEquals("INVALID_REQUEST: unknown command", error.message)
                    assertEquals(
                        listOf<Byte>(3, 2, 1),
                        client.request(byteArrayOf(1, 2, 3)).toList(),
                    )
                }
            }
        cacheRoot.toFile().deleteRecursively()
    }

    @Test
    fun `client performs the handshake before issuing a command`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-client-")
        val endpoint =
            RuntimePaths.socketPath(cacheRoot, "f".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        RuntimeHandshakeServer(endpoint) { payload ->
                byteArrayOf(payload[2], payload[1], payload[0])
            }
            .use { server ->
                server.start()

                RuntimeConnection.connect(endpoint).use { client ->
                    assertEquals(
                        listOf<Byte>(3, 2, 1),
                        client.request(byteArrayOf(1, 2, 3)).toList(),
                    )
                }
            }
        cacheRoot.toFile().deleteRecursively()
    }
}
