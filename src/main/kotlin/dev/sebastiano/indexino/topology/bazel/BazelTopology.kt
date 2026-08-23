package dev.sebastiano.indexino.topology.bazel

import dev.sebastiano.indexino.topology.TopologyResult
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal data class BazelQueryResult(
    val lines: List<String>,
    val includeDeps: Boolean,
    val topology: String = "bazel-query",
)

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
                topology = queryResult.topology,
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
        if (includeDeps) {
            val dependencyQuery = "kind('source file', deps($target))"
            val primary = runner.run(dependencyQuery, workspace)
            if (primary.exitCode == 0) return BazelQueryResult(primary.lines, includeDeps = true)

            onStderr("bazel query failed ($dependencyQuery); retrying with labels(srcs, $target)")
            val fallback = queryTargetOnly(target, workspace, runner)
            if (fallback.exitCode == 0) {
                return BazelQueryResult(fallback.lines, includeDeps = false)
            }
            onStderr("bazel target-only query failed; retrying with build-parse")
            return BazelQueryResult(
                degradedSourceLabels(target, workspace, onStderr),
                includeDeps = false,
                topology = "build-parse",
            )
        }

        val primary = queryTargetOnly(target, workspace, runner)
        if (primary.exitCode == 0) return BazelQueryResult(primary.lines, includeDeps = false)

        onStderr("bazel target-only query failed; retrying with build-parse")
        return BazelQueryResult(
            degradedSourceLabels(target, workspace, onStderr),
            includeDeps = false,
            topology = "build-parse",
        )
    }

    private fun queryTargetOnly(
        target: String,
        workspace: Path,
        runner: BazelProcessRunner,
    ): BazelQueryOutcome {
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val sources = linkedSetOf<String>()
        pending += target
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val aliasResult = runner.run(aliasClassificationQuery(current), workspace)
            if (aliasResult.exitCode != 0) return aliasResult
            val isAlias = aliasResult.lines.any { line -> line == current }
            val sourceResult = runner.run(targetSourceQuery(current, isAlias), workspace)
            if (sourceResult.exitCode != 0) return sourceResult
            sources += sourceResult.lines
            val filegroupResult = runner.run(targetFilegroupQuery(current, isAlias), workspace)
            if (filegroupResult.exitCode != 0) return filegroupResult
            pending += filegroupResult.lines.filter(::isBazelLabel)
        }
        return BazelQueryOutcome(0, sources.toList())
    }

    private fun aliasClassificationQuery(target: String): String = "kind('alias rule', $target)"

    private fun targetSourceQuery(target: String, isAlias: Boolean): String =
        if (isAlias) {
            "kind('source file', labels(actual, $target))"
        } else {
            "kind('source file', labels(srcs, $target)) union " +
                "kind('source file', labels(resource_files, $target))"
        }

    private fun targetFilegroupQuery(target: String, isAlias: Boolean): String {
        val attributes = if (isAlias) listOf("actual") else listOf("srcs", "resource_files")
        return attributes.joinToString(" union ") { attribute ->
            "kind('filegroup rule', labels($attribute, $target)) union " +
                "kind('alias rule', labels($attribute, $target))"
        }
    }

    private fun isBazelLabel(line: String): Boolean = line.startsWith("//") || line.startsWith("@")

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
        val targetName =
            target.substringAfter(
                ':',
                missingDelimiterValue = target.removePrefix("//").substringAfterLast('/'),
            )
        val parseResult = BuildFileParser.parseKotlinSources(buildFile, workspace, targetName)
        parseResult.warnings.forEach(onStderr)
        if (parseResult.paths.isEmpty()) {
            onStderr("build-parse: no Kotlin sources found for $target under $packagePath")
        }
        return parseResult.paths.map { relativePath ->
            if (relativePath.startsWith("$packagePath/")) {
                val filePart = relativePath.removePrefix("$packagePath/")
                "//$packagePath:$filePart"
            } else {
                val sourcePackage =
                    relativePath.substringBeforeLast('/', missingDelimiterValue = "")
                val filePart = relativePath.substringAfterLast('/')
                "//$sourcePackage:$filePart"
            }
        }
    }
}
