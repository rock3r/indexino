package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimeConnection
import dev.sebastiano.indexino.engine.RuntimeControlProtocol
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal class DaemonStopCommand {
    fun stop(project: Path, cacheRoot: Path = InProcessCacheLayout.cacheRoot()): Int {
        val identityPath =
            if (Files.isDirectory(project)) project.toRealPath()
            else project.toAbsolutePath().normalize()
        val workspaceId = InProcessCacheLayout.workspaceId(identityPath)
        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val lease = RuntimeLeaseStore.read(leasePath)
        if (lease == null) {
            RuntimeTombstoneStore.acknowledge(RuntimePaths.tombstonePath(cacheRoot, workspaceId))
            return CliExitCodes.SUCCESS
        }
        try {
            RuntimeConnection.connect(endpoint).use { connection ->
                connection.request(RuntimeControlProtocol.shutdownCommand())
            }
        } catch (_: IOException) {
            // A local owner can close the endpoint before writing the final empty response.
        }
        repeat(ENDPOINT_CLOSE_WAIT_ATTEMPTS) {
            if (!Files.exists(endpoint)) return@repeat
            Thread.sleep(ENDPOINT_CLOSE_WAIT_MILLIS)
        }
        if (Files.exists(endpoint)) return CliExitCodes.ANALYSIS_ERROR
        if (
            Files.exists(leasePath) && RuntimeLeaseStore.read(leasePath)?.ownerPid == lease.ownerPid
        ) {
            Files.deleteIfExists(leasePath)
        }
        return CliExitCodes.SUCCESS
    }

    private companion object {
        const val ENDPOINT_CLOSE_WAIT_ATTEMPTS = 50
        const val ENDPOINT_CLOSE_WAIT_MILLIS = 20L
    }
}
