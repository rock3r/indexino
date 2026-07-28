package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import dev.sebastiano.indexino.engine.WorkspaceRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonStopCommandTest {
    @Test
    fun `daemon stop acknowledges workspace loss without a lease`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-tombstone-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-tombstone-workspace-")
        val workspaceId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        val tombstone = RuntimePaths.tombstonePath(cacheRoot, workspaceId)
        RuntimeTombstoneStore.write(tombstone, workspace)
        try {
            assertEquals(CliExitCodes.SUCCESS, DaemonStopCommand().stop(workspace, cacheRoot))
            assertFalse(Files.exists(tombstone))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `daemon stop is reachable from the root CLI`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-cli-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-cli-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val result =
                MainCommand().test(listOf("daemon", "stop", "--project", workspace.toString()))

            assertEquals(CliExitCodes.SUCCESS, result.statusCode)
            assertFalse(Files.exists(runtime.endpoint))
        } finally {
            runtime.close()
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `daemon stop explicitly shuts down the shared runtime`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val exitCode = DaemonStopCommand().stop(workspace, cacheRoot)

            assertEquals(CliExitCodes.SUCCESS, exitCode)
            assertFalse(Files.exists(runtime.endpoint))
            assertFalse(
                Files.exists(
                    RuntimePaths.leasePath(
                        cacheRoot,
                        InProcessCacheLayout.workspaceId(workspace.toRealPath()),
                    )
                )
            )
        } finally {
            runtime.close()
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }
}
