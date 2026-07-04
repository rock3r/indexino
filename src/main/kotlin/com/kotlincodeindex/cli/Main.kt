package com.kotlincodeindex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.kotlincodeindex.core.Version
import com.kotlincodeindex.parse.IdeaHomeBootstrap

/** Entry point for the kotlin-code-index CLI. */
class MainCommand : CliktCommand(name = "kotlin-code-index") {
    init {
        subcommands(IndexCommand(), QueryCommand())
    }

    override fun run() {
        echo("kotlin-code-index ${Version.NAME} — see .plans/HANDOFF.md")
    }
}

fun main(args: Array<String>) {
    IdeaHomeBootstrap.ensure()
    MainCommand().main(args)
}
