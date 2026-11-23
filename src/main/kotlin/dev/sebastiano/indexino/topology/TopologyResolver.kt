package dev.sebastiano.indexino.topology

import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import dev.sebastiano.indexino.topology.bazel.BazelTopology
import dev.sebastiano.indexino.topology.gradle.GradleTopology
import dev.sebastiano.indexino.topology.repo.RepoTopology
import java.nio.file.Path

internal data class TopologyRequest(
    val buildSystem: BuildSystem = BuildSystem.AUTO,
    val bazelTarget: String? = null,
    val gradleModule: String? = null,
    val includeDeps: Boolean = false,
    val repoManifest: Path? = null,
)

internal object TopologyResolver {
    /**
     * Resolves topology, reusing a persisted result when [cacheRoot] is set, no test executor is
     * injected, and every build-definition input and file name below the workspace is unchanged.
     */
    fun resolve(
        project: Path,
        request: TopologyRequest,
        bazelQueryExecutor: BazelQueryExecutor? = null,
        bazelProcessRunner: BazelProcessRunner? = null,
        onStderr: (String) -> Unit = {},
        cacheRoot: Path? = null,
    ): TopologyResult {
        val effective = effectiveBuildSystem(project, request)
        val fresh: () -> TopologyResult = {
            resolveFresh(
                effective,
                project,
                request,
                bazelQueryExecutor,
                bazelProcessRunner,
                onStderr,
            )
        }
        if (
            cacheRoot == null ||
                bazelQueryExecutor != null ||
                bazelProcessRunner != null ||
                (effective != BuildSystem.BAZEL && effective != BuildSystem.GRADLE)
        ) {
            return fresh()
        }
        return TopologyCache.resolve(
            project = project,
            request = request,
            buildSystem = effective,
            cacheRoot = cacheRoot,
            toolchain = ToolchainIdentity.capture(project, effective),
            progress = onStderr,
            resolveFresh = fresh,
        )
    }

    fun effectiveBuildSystem(project: Path, request: TopologyRequest): BuildSystem =
        when (request.buildSystem) {
            BuildSystem.AUTO ->
                BuildSystemDetector.detect(project)
                    ?: error(
                        "Cannot detect build system: no MODULE.bazel/WORKSPACE or settings.gradle(.kts)"
                    )
            BuildSystem.BAZEL -> BuildSystem.BAZEL
            BuildSystem.GRADLE -> BuildSystem.GRADLE
            BuildSystem.REPO -> BuildSystem.REPO
        }

    private fun resolveFresh(
        effective: BuildSystem,
        project: Path,
        request: TopologyRequest,
        bazelQueryExecutor: BazelQueryExecutor?,
        bazelProcessRunner: BazelProcessRunner?,
        onStderr: (String) -> Unit,
    ): TopologyResult =
        when (effective) {
            BuildSystem.BAZEL -> {
                val target =
                    requireNotNull(request.bazelTarget) {
                        "--bazel-target is required for Bazel topology"
                    }
                BazelTopology.resolveSources(
                    target,
                    project,
                    request.includeDeps,
                    bazelQueryExecutor,
                    bazelProcessRunner,
                    onStderr,
                )
            }
            BuildSystem.GRADLE -> {
                val module =
                    requireNotNull(request.gradleModule) {
                        "--gradle-module is required for Gradle topology"
                    }
                GradleTopology.resolveSources(module, project, request.includeDeps, onStderr)
            }
            BuildSystem.REPO ->
                RepoTopology.resolveSources(
                    project,
                    requireNotNull(request.repoManifest) {
                        "repo manifest is required for repo topology"
                    },
                )
            BuildSystem.AUTO -> error("unreachable")
        }
}
