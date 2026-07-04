package com.kotlincodeindex.topology.bazel

import java.nio.file.Path

data class BazelQueryOutcome(
    val exitCode: Int,
    val lines: List<String>,
)

fun interface BazelProcessRunner {
    fun run(query: String, workspace: Path): BazelQueryOutcome
}

object LiveBazelProcessRunner : BazelProcessRunner {
    override fun run(query: String, workspace: Path): BazelQueryOutcome {
        val process = ProcessBuilder("bazel", "query", query, "--output=label")
            .directory(workspace.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readLines()
        return BazelQueryOutcome(process.waitFor(), output)
    }
}
