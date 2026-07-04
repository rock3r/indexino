package com.kotlincodeindex.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.kotlincodeindex.core.git.GitHeadResolver
import com.kotlincodeindex.core.manifest.ManifestFreshness
import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.core.Version
import com.kotlincodeindex.producer.FileHashProducer
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
import com.kotlincodeindex.topology.bazel.BazelTopology
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists

class StatusCommand : CliktCommand(name = "status") {
    private val project by option("--project")
        .file(mustExist = true, mustBeReadable = true)
        .required()
    private val bazelTarget by option("--bazel-target")

    override fun run() {
        val exitCode = runStatus(
            project = requireNotNull(project).toPath(),
            bazelTarget = bazelTarget,
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
    ): Int {
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        if (!manifestPath.exists()) {
            output(Json.encodeToString(StatusReport(indexed = false, commit = commit)))
            return CliExitCodes.ANALYSIS_ERROR
        }

        val manifest = ManifestIO.read(manifestPath)
        val scope = bazelTarget ?: manifest.scope
        val topologyResult = BazelTopology.resolveSources(
            scope,
            project,
            queryExecutor,
            processRunner,
        )
        val currentHash = FileHashProducer.combinedSourcesHash(project, topologyResult.sourceFiles)
        val criteria = ManifestFreshness.criteriaFrom(
            commit = commit,
            scope = scope,
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
