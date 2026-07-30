package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.ManifestFreshness
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.SourceOriginResolver
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResolver
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Suppress("UnusedPrivateProperty", "UnusedPrivateFunction")
internal class StatusCommand : CliktCommand(name = "status") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val buildSystem by option("--build-system").default("auto")
    private val bazelTarget by option("--bazel-target")
    private val gradleModule by option("--gradle-module")
    private val includeDeps by option("--include-deps").flag(default = false)

    override fun run() {
        val workspace = requireNotNull(project).toPath().toRealPath()
        val manifest =
            WorkspaceGenerationManifestStore(
                    InProcessCacheLayout.cacheRoot(),
                    InProcessCacheLayout.workspaceId(workspace),
                )
                .current()
        if (manifest == null) {
            echo(
                Json.encodeToString(
                    StatusReport(indexed = false, commit = GitHeadResolver.resolve(workspace))
                )
            )
            throw ProgramResult(CliExitCodes.ANALYSIS_ERROR)
        }
        echo(
            Json.encodeToString(
                StatusReport(
                    indexed = true,
                    commit = manifest.revision.orEmpty(),
                    scope = manifest.scopeValue,
                    topology = manifest.scopeBuildSystem,
                    applications = manifest.applications,
                    fresh = true,
                )
            )
        )
    }

    fun runStatus(
        project: Path,
        bazelTarget: String? = null,
        queryExecutor: BazelQueryExecutor? = null,
        processRunner: BazelProcessRunner? = null,
        output: (String) -> Unit = {},
    ): Int =
        runStatus(
            project = project,
            topologyRequest =
                TopologyRequest(buildSystem = BuildSystem.BAZEL, bazelTarget = bazelTarget),
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
        val request =
            resolveRequestForManifest(
                topologyRequest,
                manifest.scope,
                manifest.topology,
                manifest.includeDeps,
            )
        val topologyResult =
            TopologyResolver.resolve(
                project = project,
                request = request,
                bazelQueryExecutor = bazelQueryExecutor,
                bazelProcessRunner = bazelProcessRunner,
            )
        val currentSources =
            SourceOriginResolver.resolve(project, topologyResult.sourceFiles).flatMap { origin ->
                origin.sourceFiles.map { path ->
                    dev.sebastiano.indexino.producer.IndexedSource(origin.id, origin.root, path)
                }
            } +
                topologyResult.externalSources.flatMap { mount ->
                    mount.sourceFiles.map { path ->
                        dev.sebastiano.indexino.producer.IndexedSource(
                            mount.originId ?: SourceOriginResolver.externalOriginId(mount.root),
                            mount.root,
                            path,
                        )
                    }
                }
        val currentHash = FileHashProducer.combinedIndexedSourcesHash(currentSources)
        val pluginCoordinates =
            PluginRegistry.load(javaClass.classLoader).selectedCoordinates(manifest.applications)
        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = commit,
                scope = manifest.scope,
                // Omitted scope reconstructs the stored configuration. An explicit scope instead
                // asks whether this manifest satisfies the caller's requested dependency policy.
                includeDeps = request.includeDeps,
                sourcesContentHash = currentHash,
                applications = manifest.applications,
                pluginCoordinates = pluginCoordinates,
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
                )
            )
        )
        return CliExitCodes.SUCCESS
    }

    private fun resolveRequestForManifest(
        cli: TopologyRequest,
        manifestScope: String,
        manifestTopology: String,
        manifestIncludeDeps: Boolean,
    ): TopologyRequest {
        if (cli.bazelTarget != null || cli.gradleModule != null) {
            return cli
        }
        return when {
            manifestTopology == "repo-manifest" ->
                cli.copy(
                    buildSystem = BuildSystem.REPO,
                    repoManifest = Path.of(manifestScope),
                    includeDeps = manifestIncludeDeps,
                )
            manifestTopology.startsWith("gradle") ->
                cli.copy(
                    buildSystem = BuildSystem.GRADLE,
                    gradleModule = manifestScope,
                    includeDeps = manifestIncludeDeps,
                )
            else ->
                cli.copy(
                    buildSystem = BuildSystem.BAZEL,
                    bazelTarget = manifestScope,
                    includeDeps = manifestIncludeDeps,
                )
        }
    }

    private fun parseBuildSystem(raw: String): BuildSystem =
        when (raw.lowercase()) {
            "auto" -> BuildSystem.AUTO
            "bazel" -> BuildSystem.BAZEL
            "gradle" -> BuildSystem.GRADLE
            "repo" -> BuildSystem.REPO
            else -> error("Unknown --build-system: $raw")
        }
}

@Serializable
internal data class StatusReport(
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
