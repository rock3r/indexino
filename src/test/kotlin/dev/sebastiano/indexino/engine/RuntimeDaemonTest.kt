package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeDaemonTest {
    @Test
    fun `daemon starts with a long cache path`() {
        val cacheRoot = Files.createTempDirectory("indexino-daemon-").resolve("x".repeat(120))
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-workspace-")
        val start =
            RuntimeDaemon.start(cacheRoot, "e".repeat(RuntimePaths.WORKSPACE_ID_LENGTH), workspace)
        try {
            assertTrue(Files.exists(assertIs<RuntimeDaemonStart.Owned>(start).daemon.endpoint))
        } finally {
            (start as? RuntimeDaemonStart.Owned)?.daemon?.close()
            cacheRoot.parent.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one daemon owns the lease and endpoint until explicit shutdown`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-workspace-")
        val workspaceId = "d".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
        val first =
            RuntimeDaemon.start(cacheRoot, workspaceId, workspace) { payload ->
                byteArrayOf(payload[2], payload[1], payload[0])
            }
        try {
            val owner = assertIs<RuntimeDaemonStart.Owned>(first)
            assertTrue(Files.exists(owner.daemon.endpoint))
            RuntimeConnection.connect(owner.daemon.endpoint).use { client ->
                kotlin.test.assertEquals(
                    listOf<Byte>(3, 2, 1),
                    client.request(byteArrayOf(1, 2, 3)).toList(),
                )
            }
            val second = RuntimeDaemon.start(cacheRoot, workspaceId, workspace)
            val attached = assertIs<RuntimeDaemonStart.Existing>(second)
            assertTrue(attached.lease.ownerPid > 0)
        } finally {
            (first as? RuntimeDaemonStart.Owned)?.daemon?.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }
}
