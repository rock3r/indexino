package com.kotlincodeindex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.kotlincodeindex.core.Version
import com.kotlincodeindex.core.git.GitHeadResolver
import com.kotlincodeindex.core.manifest.ManifestFreshness
import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.producer.FileHashProducer
import com.kotlincodeindex.topology.BuildSystem
import com.kotlincodeindex.topology.TopologyRequest
import com.kotlincodeindex.topology.TopologyResolver
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists

class StatusCommand : CliktCommand(name = "status") {
    private val project by option("--project")
        .file(mustExist = true, mustBeReadable = true)
        .required()
    private val buildSystem by option("--build-system").default("auto")
    private val bazelTarget by option("--bazel-target")
    private val gradleModule by option("--gradle-module")
    private val includeDeps by option("--include-deps").flag(default = false)

    override fun run() {
        val exitCode = runStatus(
            project = requireNotNull(project).toPath(),
            topologyRequest = TopologyRequest(
                buildSystem = parseBuildSystem(buildSystem),
                bazelTarget = bazelTarget,
                gradleModule = gradleModule,
                includeDeps = includeDeps,
            ),
            output = { echo(it) },
        )
        if (exitCode != CliExitCodes.SUCCESS) {
            throw RuntimeException("status failed with exit code $exitCode")
        }
    }

    fun runStatus(
        project: Path,
        bazelTarget: String? = null,
        queryExecutor: BazelQueryExecutor? = null,
        processRunner: BazelProcessRunner? = null,
        output: (String) -> Unit = {},
    ): Int = runStatus(
        project = project,
        topologyRequest = TopologyRequest(
            buildSystem = BuildSystem.BAZEL,
            bazelTarget = bazelTarget,
        ),
        bazelQueryExecutor = queryExecutor,
        bazelProcessRunner = processRunner,
        output = output,
    )

    fun runStatus(
        project: Path,
        topologyRequest: TopologyRequest,
        bazelQueryExecutor: BazelQueryExecutor? = null,
        bazelProcessRunner: BazelProcessRunner? = null,
        output: (String) -> Unit = {},
    ): Int {
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        if (!manifestPath.exists()) {
            output(Json.encodeToString(StatusReport(indexed = false, commit = commit)))
            return CliExitCodes.ANALYSIS_ERROR
        }

        val manifest = ManifestIO.read(manifestPath)
        val request = resolveRequestForManifest(topologyRequest, manifest.scope, manifest.topology)
        val topologyResult = TopologyResolver.resolve(
            project = project,
            request = request,
            bazelQueryExecutor = bazelQueryExecutor,
            bazelProcessRunner = bazelProcessRunner,
        )
        val currentHash = FileHashProducer.combinedSourcesHash(project, topologyResult.sourceFiles)
        val criteria = ManifestFreshness.criteriaFrom(
            commit = commit,
            scope = manifest.scope,
            sourcesContentHash = currentHash,
            applications = manifest.applications,
        )
        val fresh = ManifestFreshness.isFresh(manifest, criteria)

        output(
            Json.encodeToString(
                StatusReport(
                    indexed = true,
                    commit = commit,
                    scope = manifest.scope,
                    topology = manifest.topology,
                    indexerVersion = manifest.indexerVersion,
                    sourceFileCount = manifest.sourceFileCount,
                    builtAt = manifest.builtAt,
                    applications = manifest.applications,
                    fresh = fresh,
                    currentSourcesContentHash = currentHash,
                    manifestSourcesContentHash = manifest.sourcesContentHash,
                ),
            ),
        )
        return CliExitCodes.SUCCESS
    }

    private fun resolveRequestForManifest(
        cli: TopologyRequest,
        manifestScope: String,
        manifestTopology: String,
    ): TopologyRequest {
        if (cli.bazelTarget != null || cli.gradleModule != null) {
            return cli
        }
        return if (manifestTopology.startsWith("gradle")) {
            cli.copy(
                buildSystem = BuildSystem.GRADLE,
                gradleModule = manifestScope,
                includeDeps = cli.includeDeps,
            )
        } else {
            cli.copy(
                buildSystem = BuildSystem.BAZEL,
                bazelTarget = manifestScope,
            )
        }
    }

    private fun parseBuildSystem(raw: String): BuildSystem =
        when (raw.lowercase()) {
            "auto" -> BuildSystem.AUTO
            "bazel" -> BuildSystem.BAZEL
            "gradle" -> BuildSystem.GRADLE
            else -> error("Unknown --build-system: $raw")
        }
}

@Serializable
data class StatusReport(
    val indexed: Boolean,
    val commit: String,
    val scope: String = "",
    val topology: String = "",
    val indexerVersion: String = Version.NAME,
    val sourceFileCount: Int = 0,
    val builtAt: String = "",
    val applications: List<String> = emptyList(),
    val fresh: Boolean = false,
    val currentSourcesContentHash: String = "",
    val manifestSourcesContentHash: String = "",
)
