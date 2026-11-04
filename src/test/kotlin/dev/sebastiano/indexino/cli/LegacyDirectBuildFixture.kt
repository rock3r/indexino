package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.engine.IndexBuildRunner
import dev.sebastiano.indexino.producer.IndexBuildProgressReporter
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path

// Older direct-store and lookup fixtures need the compatibility writer, not facade publication.
// Keep this bridge in test sources so the production index command always uses Indexino.refresh.
internal fun IndexCommand.runIndexedBuild(
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
            TopologyRequest(
                buildSystem = BuildSystem.BAZEL,
                bazelTarget = bazelTarget,
                includeDeps = true,
            ),
        applications = applications,
        bazelQueryExecutor = queryExecutor,
        bazelProcessRunner = processRunner,
        progress = progress,
        machineProgress = machineProgress,
    )

@Suppress("TooGenericExceptionCaught")
internal fun IndexCommand.runIndexedBuild(
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
