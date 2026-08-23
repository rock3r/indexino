package dev.sebastiano.indexino.topology.bazel

import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal data class BazelQueryResult(val lines: List<String>, val includeDeps: Boolean)

internal object BazelTopology {
    fun defaultExecutor(
        onStderr: (String) -> Unit = { System.err.println(it) }
    ): BazelQueryExecutor = BazelQueryExecutor { target, workspace ->
        queryWithFallback(target, workspace, includeDeps = true, LiveBazelProcessRunner, onStderr)
            .lines
    }

    fun resolveSources(
        target: String,
        workspace: Path,
        includeDeps: Boolean,
        executor: BazelQueryExecutor? = null,
        processRunner: BazelProcessRunner? = null,
        onStderr: (String) -> Unit = { System.err.println(it) },
    ): TopologyResult {
        if (executor != null) {
            val lines = executor.query(target, workspace)
            return TopologyResult(
                sourceFiles = BazelQueryResultParser.parseKotlinSourcePaths(lines),
                topology = resolveTopology(executor),
                includeDeps = includeDeps,
                scope = target,
            )
        }

        if (processRunner != null || isBazelAvailable()) {
            val runner = processRunner ?: LiveBazelProcessRunner
            val queryResult = queryWithFallback(target, workspace, includeDeps, runner, onStderr)
            return TopologyResult(
                sourceFiles = BazelQueryResultParser.parseKotlinSourcePaths(queryResult.lines),
                topology = "bazel-query",
                includeDeps = queryResult.includeDeps,
                scope = target,
            )
        }

        val lines = degradedQuery(target, workspace, onStderr)
        return TopologyResult(
            sourceFiles = BazelQueryResultParser.parseKotlinSourcePaths(lines),
            topology = "build-parse",
            includeDeps = false,
            scope = target,
        )
    }

    fun queryWithFallback(
        target: String,
        workspace: Path,
        includeDeps: Boolean = true,
        runner: BazelProcessRunner = LiveBazelProcessRunner,
        onStderr: (String) -> Unit = { System.err.println(it) },
    ): BazelQueryResult {
        val primaryQuery =
            if (includeDeps) "kind('source file', deps($target))" else "labels(srcs, $target)"
        val primary = runner.run(primaryQuery, workspace)
        if (primary.exitCode == 0) {
            return BazelQueryResult(primary.lines, includeDeps = includeDeps)
        }

        if (includeDeps) {
            onStderr("bazel query failed ($primaryQuery); retrying with labels(srcs, $target)")
            val fallbackQuery = "labels(srcs, $target)"
            val fallback = runner.run(fallbackQuery, workspace)
            if (fallback.exitCode == 0) {
                return BazelQueryResult(fallback.lines, includeDeps = false)
            }
            onStderr("bazel query failed ($fallbackQuery); retrying with build-parse")
            return BazelQueryResult(degradedSourceLabels(target, workspace, onStderr), false)
        }

        onStderr("bazel query failed ($primaryQuery); retrying with build-parse")
        return BazelQueryResult(degradedSourceLabels(target, workspace, onStderr), false)
    }

    private fun resolveTopology(executor: BazelQueryExecutor): String =
        when {
            executor is MockBazelQueryExecutor -> "bazel-query"
            isBazelAvailable() -> "bazel-query"
            else -> "build-parse"
        }

    private fun isBazelAvailable(): Boolean =
        runCatching {
                ProcessBuilder("bazel", "version").redirectErrorStream(true).start().waitFor() == 0
            }
            .getOrDefault(false)

    private fun degradedQuery(
        target: String,
        workspace: Path,
        onStderr: (String) -> Unit,
    ): List<String> = degradedSourceLabels(target, workspace, onStderr)

    fun degradedSourceLabels(
        target: String,
        workspace: Path,
        onStderr: (String) -> Unit = { System.err.println(it) },
    ): List<String> {
        val packagePath = target.removePrefix("//").substringBefore(':')
        val packageDir = workspace.resolve(packagePath)
        check(packageDir.isDirectory()) {
            "Package directory not found for target $target: $packageDir"
        }
        val buildFile =
            sequenceOf("BUILD.bazel", "BUILD")
                .map { packageDir.resolve(it) }
                .firstOrNull { it.exists() } ?: error("No BUILD file under $packageDir")
        val parseResult = BuildFileParser.parseKotlinSources(buildFile, workspace)
        parseResult.warnings.forEach(onStderr)
        if (parseResult.paths.isEmpty()) {
            onStderr("build-parse: no Kotlin sources found for $target under $packagePath")
        }
        return parseResult.paths.map { relativePath ->
            val filePart = relativePath.removePrefix("$packagePath/")
            "//$packagePath:$filePart"
        }
    }
}
