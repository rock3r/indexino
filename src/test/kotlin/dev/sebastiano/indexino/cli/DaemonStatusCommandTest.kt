package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonStatusCommandTest {
    @Test
    fun `daemon status reports the runtime auto refresh mode`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-status-mode-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-status-mode-workspace-")
        try {
            assertEquals(
                CliExitCodes.SUCCESS,
                DaemonStartCommand().start(workspace, cacheRoot, AutoRefreshMode.DISABLED),
            )
            val output = StringBuilder()

            val exitCode =
                DaemonStatusCommand().runStatus(workspace, cacheRoot) { output.appendLine(it) }

            assertEquals(CliExitCodes.SUCCESS, exitCode)
            assertTrue(output.toString().contains("DISABLED"), output.toString())
        } finally {
            DaemonStopCommand().stop(workspace, cacheRoot)
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `daemon status is reachable from the root CLI`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-cli-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-cli-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        try {
            System.setProperty("indexino.cache.dir", cacheRoot.toString())
            val result =
                MainCommand().test(listOf("daemon", "status", "--project", workspace.toString()))

            assertEquals(CliExitCodes.SUCCESS, result.statusCode)
            assertTrue(result.stdout.contains("stopped"), result.stdout)
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `daemon status reports a persisted workspace-loss tombstone`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-status-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-status-workspace-")
        try {
            val workspaceId = InProcessCacheLayout.workspaceId(workspace)
            RuntimeTombstoneStore.write(
                RuntimePaths.tombstonePath(cacheRoot, workspaceId),
                workspace,
            )
            workspace.toFile().deleteRecursively()

            val output = StringBuilder()
            val exitCode =
                DaemonStatusCommand().runStatus(workspace, cacheRoot) { output.appendLine(it) }

            assertEquals(CliExitCodes.ANALYSIS_ERROR, exitCode)
            assertTrue(output.toString().contains("WORKSPACE_LOST"), output.toString())
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }
}
