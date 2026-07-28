package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DaemonStatusCommand {
    fun runStatus(
        project: Path,
        cacheRoot: Path = InProcessCacheLayout.cacheRoot(),
        output: (String) -> Unit = {},
    ): Int {
        val identityPath =
            if (Files.isDirectory(project)) {
                project.toRealPath()
            } else {
                project.toAbsolutePath().normalize()
            }
        val workspaceId = InProcessCacheLayout.workspaceId(identityPath)
        val tombstone =
            RuntimeTombstoneStore.read(RuntimePaths.tombstonePath(cacheRoot, workspaceId))
        if (tombstone != null) {
            output(
                Json.encodeToString(
                    DaemonStatusReport(
                        state = "workspace_lost",
                        code = tombstone.code,
                        message = tombstone.message,
                    )
                )
            )
            return CliExitCodes.ANALYSIS_ERROR
        }
        val lease = RuntimeLeaseStore.read(RuntimePaths.leasePath(cacheRoot, workspaceId))
        val running = lease?.let(RuntimeLeaseStore::isLive) ?: false
        output(
            Json.encodeToString(
                DaemonStatusReport(
                    state = if (running) "running" else "stopped",
                    ownerPid = lease?.ownerPid,
                    endpoint = lease?.endpoint,
                )
            )
        )
        return CliExitCodes.SUCCESS
    }
}

@Serializable
internal data class DaemonStatusReport(
    val state: String,
    val code: String? = null,
    val message: String? = null,
    val ownerPid: Long? = null,
    val endpoint: String? = null,
)
