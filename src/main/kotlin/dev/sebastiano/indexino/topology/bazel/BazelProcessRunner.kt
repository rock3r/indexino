package dev.sebastiano.indexino.topology.bazel

import java.nio.file.Path

internal data class BazelQueryOutcome(val exitCode: Int, val lines: List<String>)

internal fun interface BazelProcessRunner {
    fun run(query: String, workspace: Path): BazelQueryOutcome
}

internal object LiveBazelProcessRunner : BazelProcessRunner {
    override fun run(query: String, workspace: Path): BazelQueryOutcome {
        val process =
            ProcessBuilder("bazel", "query", query, "--output=label")
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readLines()
        return BazelQueryOutcome(process.waitFor(), output)
    }
}
