package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

/** Entry point for the indexino CLI. */
internal class MainCommand : CliktCommand(name = "indexino") {
    init {
        subcommands(
            IndexCommand(),
            QueryCommand(),
            StatusCommand(),
            FindSymbolCommand(),
            FindReferencesCommand(),
            ResolveResourceCommand(),
        )
    }

    override fun run() = Unit
}

/**
 * Internal to Kotlin consumers, but emitted as the standard public static JVM entry point. The fat
 * and R8 JAR launch contracts are exercised by `verifyShrunkCli`.
 */
internal fun main(args: Array<String>) {
    WindowsConsoleCtrlHandler.install()
    MainCommand().main(args)
}
