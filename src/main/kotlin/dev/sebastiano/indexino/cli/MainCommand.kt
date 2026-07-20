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

internal fun main(args: Array<String>) {
    MainCommand().main(args)
}
