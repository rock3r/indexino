package com.kotlincodeindex.topology

import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
import com.kotlincodeindex.topology.bazel.BazelTopology
import com.kotlincodeindex.topology.gradle.GradleTopology
import java.nio.file.Path

data class TopologyRequest(
    val buildSystem: BuildSystem = BuildSystem.AUTO,
    val bazelTarget: String? = null,
    val gradleModule: String? = null,
    val includeDeps: Boolean = false,
)

object TopologyResolver {
    fun resolve(
        project: Path,
        request: TopologyRequest,
        bazelQueryExecutor: BazelQueryExecutor? = null,
        bazelProcessRunner: BazelProcessRunner? = null,
        onStderr: (String) -> Unit = {},
    ): TopologyResult {
        val effective: BuildSystem = when (request.buildSystem) {
            BuildSystem.AUTO -> BuildSystemDetector.detect(project)
                ?: error("Cannot detect build system: no MODULE.bazel/WORKSPACE or settings.gradle(.kts)")
            BuildSystem.BAZEL -> BuildSystem.BAZEL
            BuildSystem.GRADLE -> BuildSystem.GRADLE
        }
        return when (effective) {
            BuildSystem.BAZEL -> {
                val target = requireNotNull(request.bazelTarget) {
                    "--bazel-target is required for Bazel topology"
                }
                BazelTopology.resolveSources(
                    target,
                    project,
                    bazelQueryExecutor,
                    bazelProcessRunner,
                    onStderr,
                )
            }
            BuildSystem.GRADLE -> {
                val module = requireNotNull(request.gradleModule) {
                    "--gradle-module is required for Gradle topology"
                }
                GradleTopology.resolveSources(module, project, request.includeDeps, onStderr)
            }
            BuildSystem.AUTO -> error("unreachable")
        }
    }
}
