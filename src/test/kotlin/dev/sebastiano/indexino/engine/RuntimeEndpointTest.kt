package dev.sebastiano.indexino.engine

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BIND_ACCEPTANCE_PATH_LENGTH = 100

class RuntimeEndpointTest {
    @Test
    fun `long cache paths use a stable short local socket endpoint`() {
        val cacheRoot = Files.createTempDirectory("indexino-runtime-path-").resolve("x".repeat(120))
        val workspaceId = "a".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)

        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)

        assertTrue(endpoint.toString().length <= BIND_ACCEPTANCE_PATH_LENGTH)
        assertEquals(endpoint, RuntimePaths.socketPath(cacheRoot, workspaceId))
        assertTrue(endpoint.fileName.toString().startsWith(workspaceId))
    }

    @Test
    fun `runtime endpoint binds at a 100 character path`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-path-")
        try {
            val workspaceId = "a".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
            val fixedLength = RuntimePaths.socketPath(cacheRoot, workspaceId).toString().length
            val cacheSegmentLength = BIND_ACCEPTANCE_PATH_LENGTH - fixedLength - 1
            val constrainedCacheRoot = cacheRoot.resolve("x".repeat(cacheSegmentLength))
            Files.createDirectories(constrainedCacheRoot)

            val endpoint = RuntimePaths.socketPath(constrainedCacheRoot, workspaceId)

            assertEquals(BIND_ACCEPTANCE_PATH_LENGTH, endpoint.toString().length)
            Files.createDirectories(endpoint.parent)
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(endpoint))
                assertTrue(Files.exists(endpoint))
            }
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }
}
