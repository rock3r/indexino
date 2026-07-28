package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.engine.WorkspaceRuntime
import java.nio.file.Files
import java.nio.file.Path

internal class DaemonCommand : CliktCommand(name = "daemon") {
    init {
        subcommands(
            DaemonStartCliCommand(),
            DaemonStatusCliCommand(),
            DaemonStopCliCommand(),
            DaemonRunCliCommand(),
        )
    }

    override fun run() = Unit
}

internal class DaemonStartCliCommand : CliktCommand(name = "start") {
    private val project by option("--project").required()

    override fun run() {
        val exitCode = DaemonStartCommand().start(Path.of(project))
        if (exitCode != CliExitCodes.SUCCESS) throw ProgramResult(exitCode)
    }
}

internal class DaemonRunCliCommand : CliktCommand(name = "run") {
    private val project by option("--project").required()

    override fun run() {
        WorkspaceRuntime.start(Path.of(project), InProcessCacheLayout.cacheRoot()).use { runtime ->
            while (Files.exists(runtime.endpoint)) {
                Thread.sleep(DAEMON_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private companion object {
        const val DAEMON_POLL_INTERVAL_MILLIS = 100L
    }
}

internal class DaemonStopCliCommand : CliktCommand(name = "stop") {
    private val project by option("--project").required()

    override fun run() {
        val exitCode = DaemonStopCommand().stop(Path.of(project))
        if (exitCode != CliExitCodes.SUCCESS) throw ProgramResult(exitCode)
    }
}

internal class DaemonStatusCliCommand : CliktCommand(name = "status") {
    private val project by option("--project").required()

    override fun run() {
        val exitCode = DaemonStatusCommand().runStatus(Path.of(project)) { echo(it) }
        if (exitCode != CliExitCodes.SUCCESS) throw ProgramResult(exitCode)
    }
}
