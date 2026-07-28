package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.cache.ContentAddressedPackCache
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.producer.IndexBuildProgressReporter
import dev.sebastiano.indexino.producer.JsonlIndexBuildProgressReporter
import dev.sebastiano.indexino.topology.BuildSystem
import dev.sebastiano.indexino.topology.BuildSystemDetector
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

internal class IndexCommand : CliktCommand(name = "index") {
    private val project by
        option("--project").file(mustExist = true, mustBeReadable = true).required()
    private val buildSystem by option("--build-system").default("auto")
    private val bazelTarget by option("--bazel-target")
    private val gradleModule by option("--gradle-module")
    private val includeDeps by option("--include-deps").flag(default = false)
    private val applications by option("--applications").split(",").default(emptyList())
    private val progressFormat by option("--progress-format").default("text")

    @Suppress("CyclomaticComplexMethod")
    override fun run() {
        val jsonlProgress =
            when (progressFormat) {
                "text" -> false
                "jsonl" -> true
                else ->
                    throw UsageError(
                        "Unknown --progress-format: $progressFormat (expected text or jsonl)",
                        "--progress-format",
                        CliExitCodes.INVALID_ARGUMENTS,
                    )
            }
        val scope = daemonScope(project.toPath())
        if (jsonlProgress) JsonlIndexBuildProgressReporter { echo(it) }.discoveryStarted()
        runBlocking {
            Indexino.connect(project.toPath()).use { indexino ->
                val request =
                    applications.filter(String::isNotBlank).fold(RefreshRequest.forScope(scope)) {
                        current,
                        application ->
                        current.withPlugin(PluginId.of(application))
                    }
                val handle = indexino.refresh(request)
                val awaiting = async { runCatching { handle.await() } }
                val emittedText = mutableSetOf<String>()
                val emittedMachine = mutableSetOf<String>()
                while (!awaiting.isCompleted) {
                    replayProgress(
                        indexino,
                        handle.id.value,
                        jsonlProgress,
                        emittedText,
                        emittedMachine,
                    )
                    delay(PROGRESS_POLL_INTERVAL_MILLIS)
                }
                val result = awaiting.await().getOrNull()
                val failure = awaiting.await().exceptionOrNull()
                replayProgress(
                    indexino,
                    handle.id.value,
                    jsonlProgress,
                    emittedText,
                    emittedMachine,
                )
                result?.let { materializeCliCompatibilityProjection(project.toPath().toRealPath()) }
                if (!jsonlProgress && result?.outcome?.value == "UNCHANGED")
                    echo("index fresh", err = true)
                failure?.let { thrown ->
                    if (jsonlProgress) {
                        JsonlIndexBuildProgressReporter { echo(it) }
                            .failed(
                                CliExitCodes.ANALYSIS_ERROR,
                                thrown.message ?: thrown.javaClass.name,
                            )
                    }
                    throw thrown
                }
            }
        }
    }

    private fun replayProgress(
        indexino: Indexino,
        refreshId: String,
        jsonlProgress: Boolean,
        emittedText: MutableSet<String>,
        emittedMachine: MutableSet<String>,
    ) {
        val journal = indexino.refreshProgress(refreshId)
        journal.text.filter(emittedText::add).forEach { echo(it, err = true) }
        if (jsonlProgress) journal.machine.filter(emittedMachine::add).forEach(::echo)
    }

    private fun materializeCliCompatibilityProjection(project: Path) {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val manifest =
            requireNotNull(
                WorkspaceGenerationManifestStore(
                        cacheRoot,
                        InProcessCacheLayout.workspaceId(project),
                    )
                    .current()
            )
        val resolver = IndexPathResolver(project)
        val commit = GitHeadResolver.resolve(project)
        ContentAddressedPackCache(cacheRoot)
            .materializeDirectory(manifest.packKeys.single(), resolver.resolveBaseStore(commit))
        ManifestIO.write(
            resolver.resolveManifest(commit),
            IndexManifest(
                commit = commit,
                indexerVersion = Version.NAME,
                scope = manifest.scopeValue,
                topology = manifest.scopeBuildSystem,
                includeDeps = manifest.includesDependencies,
                sourceFileCount = 0,
                sourcesContentHash = manifest.stateFingerprint,
                builtAt = Instant.now().toString(),
                applications = manifest.applications,
            ),
        )
    }

    private fun daemonScope(project: Path): IndexScope {
        val effective =
            when (parseBuildSystem(buildSystem)) {
                BuildSystem.AUTO ->
                    BuildSystemDetector.detect(project)
                        ?: throw UsageError("Cannot detect build system", "--build-system")
                else -> parseBuildSystem(buildSystem)
            }
        return when (effective) {
            BuildSystem.BAZEL -> {
                val scope =
                    IndexScope.bazel(requireNotNull(bazelTarget) { "--bazel-target is required" })
                if (!includeDeps) {
                    throw UsageError(
                        "Daemon-backed Bazel indexing requires --include-deps",
                        "--include-deps",
                    )
                }
                scope.includingDependencies()
            }
            BuildSystem.GRADLE -> {
                val scope =
                    IndexScope.gradle(
                        requireNotNull(gradleModule) { "--gradle-module is required" }
                    )
                if (includeDeps) scope.includingDependencies() else scope
            }
            BuildSystem.AUTO -> error("unreachable")
        }
    }

    private companion object {
        const val PROGRESS_POLL_INTERVAL_MILLIS = 50L
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
