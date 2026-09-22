package dev.sebastiano.indexino.topology.bazel

import dev.sebastiano.indexino.topology.TopologyResult
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

internal data class BazelQueryResult(
    val lines: List<String>,
    val codeLines: List<String>? = null,
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
        checkBazelInterrupted()
        if (executor != null) {
            val lines = executor.query(target, workspace)
            checkBazelInterrupted()
            return TopologyResult(
                sourceFiles = BazelQueryResultParser.parseKotlinSourcePaths(lines),
                codeSourceFiles = null,
                topology = resolveTopology(executor, workspace),
                includeDeps = includeDeps,
                scope = target,
            )
        }

        if (processRunner != null || isBazelAvailable(workspace)) {
            val runner = processRunner ?: LiveBazelProcessRunner
            val queryResult = queryWithFallback(target, workspace, includeDeps, runner, onStderr)
            return TopologyResult(
                sourceFiles = BazelQueryResultParser.parseKotlinSourcePaths(queryResult.lines),
                codeSourceFiles =
                    queryResult.codeLines
                        ?.let(BazelQueryResultParser::parseKotlinSourcePaths)
                        ?.toSet(),
                topology = queryResult.topology,
                includeDeps = queryResult.includeDeps,
                scope = target,
            )
        }

        val parsed = degradedBuildResult(target, workspace, onStderr)
        return TopologyResult(
            sourceFiles = parsed.paths,
            codeSourceFiles = parsed.codePaths,
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
            val primary = runner.runActive(dependencyQuery, workspace)
            if (primary.exitCode == 0) {
                val roles = BazelCodeRoleQuery.dependencySources(target, workspace, runner)
                if (roles.exitCode != 0) {
                    onStderr("bazel code-role query failed; preserving unknown classification")
                }
                return BazelQueryResult(
                    lines = primary.lines,
                    codeLines = roles.lines.takeIf { roles.exitCode == 0 },
                    includeDeps = true,
                )
            }

            onStderr("bazel query failed ($dependencyQuery); retrying with labels(srcs, $target)")
            val fallback = queryTargetOnly(target, workspace, runner)
            if (fallback.exitCode == 0) {
                if (fallback.codeLines == null) {
                    onStderr("bazel code-role query failed; preserving unknown classification")
                }
                return BazelQueryResult(fallback.lines, fallback.codeLines, includeDeps = false)
            }
            onStderr("bazel target-only query failed; retrying with build-parse")
            return degradedQueryResult(target, workspace, includeDeps = false, onStderr)
        }

        val primary = queryTargetOnly(target, workspace, runner)
        if (primary.exitCode == 0) {
            if (primary.codeLines == null) {
                onStderr("bazel code-role query failed; preserving unknown classification")
            }
            return BazelQueryResult(primary.lines, primary.codeLines, includeDeps = false)
        }

        onStderr("bazel target-only query failed; retrying with build-parse")
        return degradedQueryResult(target, workspace, includeDeps = false, onStderr)
    }

    private data class TargetQueryOutcome(
        val exitCode: Int,
        val lines: List<String>,
        val codeLines: List<String>? = null,
    )

    private fun queryTargetOnly(
        target: String,
        workspace: Path,
        runner: BazelProcessRunner,
    ): TargetQueryOutcome {
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val sources = linkedSetOf<String>()
        pending += target
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            val aliasResult = runner.runActive(aliasClassificationQuery(current), workspace)
            if (aliasResult.exitCode != 0)
                return TargetQueryOutcome(aliasResult.exitCode, aliasResult.lines)
            val isAlias = aliasResult.lines.any(::isBazelLabel)
            val sourceResult = runner.runActive(targetSourceQuery(current, isAlias), workspace)
            if (sourceResult.exitCode != 0)
                return TargetQueryOutcome(sourceResult.exitCode, sourceResult.lines)
            sources += sourceResult.lines
            val filegroupResult =
                runner.runActive(targetFilegroupQuery(current, isAlias), workspace)
            if (filegroupResult.exitCode != 0) {
                return TargetQueryOutcome(filegroupResult.exitCode, filegroupResult.lines)
            }
            pending += filegroupResult.lines.filter(::isBazelLabel)
        }
        val codeResult = BazelCodeRoleQuery.targetSources(target, workspace, runner)
        return TargetQueryOutcome(
            exitCode = 0,
            lines = sources.toList(),
            codeLines = codeResult.lines.takeIf { codeResult.exitCode == 0 },
        )
    }

    private fun aliasClassificationQuery(target: String): String = "kind('alias rule', $target)"

    private fun targetSourceQuery(target: String, isAlias: Boolean): String =
        if (isAlias) {
            "kind('source file', labels(actual, $target))"
        } else {
            SOURCE_ATTRIBUTES.joinToString(" union ") { attribute ->
                "kind('source file', labels($attribute, $target))"
            }
        }

    private fun targetFilegroupQuery(target: String, isAlias: Boolean): String {
        if (isAlias) return "kind('rule', labels(actual, $target))"
        return SOURCE_ATTRIBUTES.joinToString(" union ") { attribute ->
            "kind('filegroup rule', labels($attribute, $target)) union " +
                "kind('alias rule', labels($attribute, $target))"
        }
    }

    private fun isBazelLabel(line: String): Boolean = line.startsWith("//") || line.startsWith("@")

    private val SOURCE_ATTRIBUTES = listOf("srcs", "resources", "resource_files", "data")

    private fun resolveTopology(executor: BazelQueryExecutor, workspace: Path): String =
        when {
            executor is MockBazelQueryExecutor -> "bazel-query"
            isBazelAvailable(workspace) -> "bazel-query"
            else -> "build-parse"
        }

    internal fun isBazelAvailable(
        workspace: Path,
        command: List<String> = listOf("bazel", "version"),
    ): Boolean =
        try {
            LiveBazelProcessRunner.runCommand(command, workspace, timeoutMillis = 10_000)
                .exitCode == 0
        } catch (_: IOException) {
            checkBazelInterrupted()
            false
        } catch (_: TimeoutException) {
            checkBazelInterrupted()
            false
        }

    private fun BazelProcessRunner.runActive(query: String, workspace: Path): BazelQueryOutcome {
        checkBazelInterrupted()
        val outcome = run(query, workspace)
        checkBazelInterrupted()
        return outcome
    }

    private fun degradedBuildResult(
        target: String,
        workspace: Path,
        onStderr: (String) -> Unit,
    ): BuildParseResult {
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
        return parseResult
    }

    private fun degradedQueryResult(
        target: String,
        workspace: Path,
        includeDeps: Boolean,
        onStderr: (String) -> Unit,
    ): BazelQueryResult {
        val parsed = degradedBuildResult(target, workspace, onStderr)
        val packagePath = target.removePrefix("//").substringBefore(':')
        return BazelQueryResult(
            lines = parsed.paths.map { pathToLabel(it, packagePath) },
            codeLines = parsed.codePaths.map { pathToLabel(it, packagePath) },
            includeDeps = includeDeps,
            topology = "build-parse",
        )
    }

    fun degradedSourceLabels(
        target: String,
        workspace: Path,
        onStderr: (String) -> Unit = { System.err.println(it) },
    ): List<String> {
        checkBazelInterrupted()
        val packagePath = target.removePrefix("//").substringBefore(':')
        return degradedBuildResult(target, workspace, onStderr).paths.map {
            pathToLabel(it, packagePath)
        }
    }

    private fun pathToLabel(relativePath: String, packagePath: String): String {
        if (relativePath.startsWith("$packagePath/")) {
            return "//$packagePath:${relativePath.removePrefix("$packagePath/")}"
        }
        val sourcePackage = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val filePart = relativePath.substringAfterLast('/')
        return "//$sourcePackage:$filePart"
    }
}
