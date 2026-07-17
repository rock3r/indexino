package com.kotlincodeindex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import com.kotlincodeindex.producer.IndexBuildProgressReporter
import com.kotlincodeindex.producer.JsonlIndexBuildProgressReporter
import com.kotlincodeindex.topology.BuildSystem
import com.kotlincodeindex.topology.TopologyRequest
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
import java.nio.file.Path

class IndexCommand : CliktCommand(name = "index") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val buildSystem by option("--build-system").default("auto")
    private val bazelTarget by option("--bazel-target")
    private val gradleModule by option("--gradle-module")
    private val includeDeps by option("--include-deps").flag(default = false)
    private val applications by option("--applications").split(",").default(emptyList())
    private val progressFormat by option("--progress-format").default("text")

    override fun run() {
        val exitCode =
            runIndexedBuild(
                project = project.toPath(),
                topologyRequest =
                    TopologyRequest(
                        buildSystem = parseBuildSystem(buildSystem),
                        bazelTarget = bazelTarget,
                        gradleModule = gradleModule,
                        includeDeps = includeDeps,
                    ),
                applications = applications.filter { it.isNotBlank() },
                progress = { echo(it, err = true) },
                machineProgress =
                    when (progressFormat) {
                        "text" -> null
                        "jsonl" -> JsonlIndexBuildProgressReporter { echo(it) }
                        else ->
                            throw UsageError(
                                "Unknown --progress-format: $progressFormat (expected text or jsonl)",
                                "--progress-format",
                                CliExitCodes.INVALID_ARGUMENTS,
                            )
                    },
            )
        if (exitCode != CliExitCodes.SUCCESS) {
            throw ProgramResult(exitCode)
        }
    }

    fun runIndexedBuild(
        project: Path,
        bazelTarget: String,
        applications: List<String>,
        queryExecutor: BazelQueryExecutor? = null,
        processRunner: BazelProcessRunner? = null,
        progress: (String) -> Unit = {},
        machineProgress: IndexBuildProgressReporter? = null,
    ): Int =
        runIndexedBuild(
            project = project,
            topologyRequest =
                TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = bazelTarget),
            applications = applications,
            bazelQueryExecutor = queryExecutor,
            bazelProcessRunner = processRunner,
            progress = progress,
            machineProgress = machineProgress,
        )

    @Suppress("TooGenericExceptionCaught")
    fun runIndexedBuild(
        project: Path,
        topologyRequest: TopologyRequest,
        applications: List<String>,
        bazelQueryExecutor: BazelQueryExecutor? = null,
        bazelProcessRunner: BazelProcessRunner? = null,
        progress: (String) -> Unit = {},
        machineProgress: IndexBuildProgressReporter? = null,
    ): Int {
        machineProgress?.discoveryStarted()
        return try {
            IndexBuildRunner(
                    project = project,
                    topologyRequest = topologyRequest,
                    applications = applications,
                    bazelQueryExecutor = bazelQueryExecutor,
                    bazelProcessRunner = bazelProcessRunner,
                    progress = progress,
                    machineProgress = machineProgress,
                )
                .run()
        } catch (exception: Exception) {
            machineProgress?.failed(
                CliExitCodes.ANALYSIS_ERROR,
                exception.message ?: exception.javaClass.name,
            )
            throw exception
        }
    }

    private fun parseBuildSystem(raw: String): BuildSystem =
        when (raw.lowercase()) {
            "auto" -> BuildSystem.AUTO
            "bazel" -> BuildSystem.BAZEL
            "gradle" -> BuildSystem.GRADLE
            else -> error("Unknown --build-system: $raw (expected auto, bazel, gradle)")
        }
}
