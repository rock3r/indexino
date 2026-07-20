package dev.sebastiano.indexino.topology.bazel

import java.nio.file.Path

internal fun interface BazelQueryExecutor {
    fun query(target: String, workspace: Path): List<String>
}

internal class MockBazelQueryExecutor(private val lines: List<String>) : BazelQueryExecutor {
    override fun query(target: String, workspace: Path): List<String> = lines
}
