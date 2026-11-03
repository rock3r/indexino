package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimeConnection
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class DaemonStartCommand {
    fun start(
        project: Path,
        cacheRoot: Path = InProcessCacheLayout.cacheRoot(),
        autoRefreshMode: AutoRefreshMode = AutoRefreshMode.ENABLED,
    ): Int {
        val canonicalProject = project.toRealPath()
        val workspaceId = InProcessCacheLayout.workspaceId(canonicalProject)
        val lease = RuntimeLeaseStore.read(RuntimePaths.leasePath(cacheRoot, workspaceId))
        if (lease != null && RuntimeLeaseStore.isLive(lease)) {
            return if (lease.autoRefreshMode == autoRefreshMode) {
                CliExitCodes.SUCCESS
            } else {
                CliExitCodes.INVALID_ARGUMENTS
            }
        }

        val command = buildList {
            add(javaExecutable())
            add("--enable-native-access=ALL-UNNAMED")
            add("-Dindexino.cache.dir=$cacheRoot")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("dev.sebastiano.indexino.engine.RuntimeOwnerMainKt")
            add(canonicalProject.toString())
            add(autoRefreshMode.name)
        }
        val process =
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()

        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        val deadline =
            System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(START_WAIT_MILLIS * START_WAIT_ATTEMPTS)
        while (true) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMillis <= 0) return CliExitCodes.ANALYSIS_ERROR
            if (Files.exists(endpoint)) {
                try {
                    RuntimeConnection.connect(endpoint, remainingMillis).use {}
                    return CliExitCodes.SUCCESS
                } catch (_: IOException) {
                    // The socket file can exist before the owner is accepting connections.
                }
            }
            if (!process.isAlive) return CliExitCodes.ANALYSIS_ERROR
            Thread.sleep(minOf(START_WAIT_MILLIS, remainingMillis))
        }
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private companion object {
        const val START_WAIT_ATTEMPTS = 100
        const val START_WAIT_MILLIS = 50L
    }
}
