package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.InProcessCacheLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Attaches to, or starts, the local runtime owner for a canonical workspace. */
internal object RuntimeClientBootstrap {
    fun connect(workspace: Path): RuntimeConnection {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        if (!Files.exists(endpoint)) startOwner(workspace, cacheRoot, endpoint)
        var lastFailure: IOException? = null
        repeat(CONNECT_WAIT_ATTEMPTS) {
            try {
                return RuntimeConnection.connect(endpoint)
            } catch (failure: IOException) {
                lastFailure = failure
                recoverDeadEndpoint(cacheRoot, workspace, endpoint)
                if (!Files.exists(endpoint)) startOwner(workspace, cacheRoot, endpoint)
                Thread.sleep(START_WAIT_MILLIS)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun recoverDeadEndpoint(cacheRoot: Path, workspace: Path, endpoint: Path) {
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val lease = RuntimeLeaseStore.read(leasePath)
        if (lease == null || !RuntimeLeaseStore.isLive(lease)) {
            Files.deleteIfExists(endpoint)
            Files.deleteIfExists(leasePath)
        }
    }

    private fun startOwner(workspace: Path, cacheRoot: Path, endpoint: Path) {
        val lease =
            RuntimeLeaseStore.read(
                RuntimePaths.leasePath(cacheRoot, InProcessCacheLayout.workspaceId(workspace))
            )
        if (lease == null || !RuntimeLeaseStore.isLive(lease)) {
            val command = buildList {
                add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                System.getProperty("indexino.cache.dir")?.takeIf(String::isNotBlank)?.let { root ->
                    add("-Dindexino.cache.dir=$root")
                }
                add("-cp")
                add(System.getProperty("java.class.path"))
                add("dev.sebastiano.indexino.cli.MainCommandKt")
                add("daemon")
                add("run")
                add("--project")
                add(workspace.toString())
            }
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
        repeat(START_WAIT_ATTEMPTS) {
            if (Files.exists(endpoint)) return
            Thread.sleep(START_WAIT_MILLIS)
        }
        throw RuntimeProtocolException("Local runtime failed to start")
    }

    private const val CONNECT_WAIT_ATTEMPTS = 100
    private const val START_WAIT_ATTEMPTS = 100
    private const val START_WAIT_MILLIS = 50L
}
