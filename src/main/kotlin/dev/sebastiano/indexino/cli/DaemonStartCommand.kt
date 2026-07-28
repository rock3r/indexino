package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.RuntimeLeaseStore
import dev.sebastiano.indexino.engine.RuntimePaths
import java.nio.file.Files
import java.nio.file.Path

internal class DaemonStartCommand {
    fun start(project: Path, cacheRoot: Path = InProcessCacheLayout.cacheRoot()): Int {
        val canonicalProject = project.toRealPath()
        val workspaceId = InProcessCacheLayout.workspaceId(canonicalProject)
        val lease = RuntimeLeaseStore.read(RuntimePaths.leasePath(cacheRoot, workspaceId))
        if (lease != null && RuntimeLeaseStore.isLive(lease)) {
            return CliExitCodes.SUCCESS
        }

        val command = buildList {
            add(javaExecutable())
            System.getProperty("indexino.cache.dir")?.takeIf(String::isNotBlank)?.let {
                cacheDirectory ->
                add("-Dindexino.cache.dir=$cacheDirectory")
            }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add("dev.sebastiano.indexino.engine.RuntimeOwnerMainKt")
            add(canonicalProject.toString())
            add(AutoRefreshMode.ENABLED.name)
        }
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        repeat(START_WAIT_ATTEMPTS) {
            if (Files.exists(endpoint)) return CliExitCodes.SUCCESS
            Thread.sleep(START_WAIT_MILLIS)
        }
        return CliExitCodes.ANALYSIS_ERROR
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private companion object {
        const val START_WAIT_ATTEMPTS = 100
        const val START_WAIT_MILLIS = 50L
    }
}
