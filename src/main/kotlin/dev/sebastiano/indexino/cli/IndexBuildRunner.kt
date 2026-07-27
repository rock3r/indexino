package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.ManifestFreshness
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.engine.PluginAnalyzerRunner
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexBuildProgressReporter
import dev.sebastiano.indexino.producer.ProducerRegistry
import dev.sebastiano.indexino.producer.SOURCE_CHANGE_DETECTION_PHASE
import dev.sebastiano.indexino.producer.SourceChangeDetector
import dev.sebastiano.indexino.producer.SourceChangeSet
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResolver
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

internal class IndexBuildRunner(
    private val project: Path,
    private val topologyRequest: TopologyRequest,
    private val applications: List<String>,
    private val bazelQueryExecutor: BazelQueryExecutor?,
    private val bazelProcessRunner: BazelProcessRunner?,
    private val progress: (String) -> Unit,
    private val machineProgress: IndexBuildProgressReporter?,
    private val storeRootOverride: Path? = null,
) {
    private var latestChanges: SourceChangeSet? = null
    private var latestManifest: IndexManifest? = null
    private var reusedFreshIndex: Boolean = false

    fun runDetailed(): IndexBuildExecution {
        val exitCode = run()
        if (exitCode != CliExitCodes.SUCCESS) {
            return IndexBuildExecution(exitCode, null, null, reusedFreshIndex)
        }
        return IndexBuildExecution(
            exitCode = exitCode,
            manifest =
                checkNotNull(latestManifest) { "Successful index run did not produce a manifest" },
            changes = latestChanges,
            reusedFreshIndex = reusedFreshIndex,
        )
    }

    fun run(): Int {
        latestChanges = null
        latestManifest = null
        reusedFreshIndex = false
        val topologyResult =
            TopologyResolver.resolve(
                project = project,
                request = topologyRequest,
                bazelQueryExecutor = bazelQueryExecutor,
                bazelProcessRunner = bazelProcessRunner,
                onStderr = progress,
            )
        if (topologyResult.sourceFiles.isEmpty()) {
            progress("topology discovery failed: no source files")
            machineProgress?.failed(CliExitCodes.TOPOLOGY_FAILED, "no source files")
            return CliExitCodes.TOPOLOGY_FAILED
        }

        val sourceFiles = topologyResult.sourceFiles
        val pluginRegistry = PluginRegistry.load(javaClass.classLoader)
        val unknownApplications = applications.filter { PluginId.of(it) !in pluginRegistry.pluginIds() }
        if (unknownApplications.isNotEmpty()) {
            val message = "unknown application(s): ${unknownApplications.joinToString()}"
            progress(message)
            machineProgress?.failed(CliExitCodes.INVALID_ARGUMENTS, message)
            return CliExitCodes.INVALID_ARGUMENTS
        }
        val pluginCoordinates = pluginRegistry.selectedCoordinates(applications)
        machineProgress?.discoveryCompleted(sourceFiles.size)
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project, storeRootOverride = storeRootOverride)
        val manifestPath = resolver.resolveManifest(commit)
        val previewHash = previewHash(sourceFiles)
        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = commit,
                scope = topologyResult.scope,
                includeDeps = topologyResult.includeDeps,
                sourcesContentHash = previewHash,
                applications = applications,
                pluginCoordinates = pluginCoordinates,
            )
        val existingManifest = manifestPath.takeIf { it.exists() }?.let(ManifestIO::read)
        if (existingManifest != null && ManifestFreshness.isFresh(existingManifest, criteria)) {
            latestManifest = existingManifest
            reusedFreshIndex = true
            progress("index fresh for ${topologyResult.scope} @ $commit — skip rebuild")
            machineProgress?.completed("fresh")
            return CliExitCodes.SUCCESS
        }

        buildStore(
            resolver = resolver,
            commit = commit,
            scope = topologyResult.scope,
            topology = topologyResult.topology,
            includeDeps = topologyResult.includeDeps,
            sourceFiles = sourceFiles,
            previewHash = previewHash,
            pluginRegistry = pluginRegistry,
            pluginCoordinates = pluginCoordinates,
            forceFullRebuild =
                existingManifest == null || existingManifest.indexerVersion != Version.NAME,
        )
        machineProgress?.completed("indexed")
        return CliExitCodes.SUCCESS
    }

    private fun previewHash(sourceFiles: List<String>): String {
        machineProgress?.phaseStarted(SOURCE_HASH_PREVIEW_PHASE, sourceFiles.size)
        val previewHash =
            FileHashProducer.combinedSourcesHash(
                workspaceRoot = project,
                sourceFiles = sourceFiles,
                onFileProcessed = { index, total, path ->
                    machineProgress?.fileProgress(SOURCE_HASH_PREVIEW_PHASE, index, total, path)
                },
            )
        machineProgress?.phaseCompleted(SOURCE_HASH_PREVIEW_PHASE, sourceFiles.size)
        return previewHash
    }

    private fun buildStore(
        resolver: IndexPathResolver,
        commit: String,
        scope: String,
        topology: String,
        includeDeps: Boolean,
        sourceFiles: List<String>,
        previewHash: String,
        pluginRegistry: PluginRegistry,
        pluginCoordinates: Map<String, String>,
        forceFullRebuild: Boolean,
    ) {
        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
        try {
            val changes = detectChanges(store, sourceFiles, forceFullRebuild)
            latestChanges = changes
            val context =
                IndexBuildContext(
                    store = store,
                    commitHash = commit,
                    scope = scope,
                    sourceFiles = sourceFiles,
                    workspaceRoot = project,
                    progress = progress,
                    machineProgress = machineProgress,
                    changedSourceFiles = changes.changedFiles,
                    deletedSourceFiles = changes.deletedFiles,
                )
            ProducerRegistry.forApplications(applications).forEach { producer ->
                progress(producer.displayName)
                val phaseTotal = producer.progressTotal?.invoke(context)
                machineProgress?.phaseStarted(producer.id, phaseTotal)
                producer.produce(context.copy(activePhase = producer.id), store)
                machineProgress?.phaseCompleted(producer.id, phaseTotal)
            }
            PluginAnalyzerRunner(pluginRegistry).analyze(context, applications.toSet())
            val manifest =
                IndexManifest(
                    commit = commit,
                    indexerVersion = Version.NAME,
                    scope = scope,
                    topology = topology,
                    includeDeps = includeDeps,
                    sourceFileCount = sourceFiles.size,
                    sourcesContentHash = previewHash,
                    builtAt = Instant.now().toString(),
                    applications = applications,
                    pluginCoordinates = pluginCoordinates,
                )
            ManifestIO.write(resolver.resolveManifest(commit), manifest)
            latestManifest = manifest
        } finally {
            store.close()
        }
    }

    private fun detectChanges(
        store: XodusCodeIndexStore,
        sourceFiles: List<String>,
        forceFullRebuild: Boolean,
    ): SourceChangeSet {
        machineProgress?.phaseStarted(SOURCE_CHANGE_DETECTION_PHASE, sourceFiles.size)
        val detectedChanges =
            SourceChangeDetector.detect(store, project, sourceFiles) { index, total, path ->
                machineProgress?.fileProgress(SOURCE_CHANGE_DETECTION_PHASE, index, total, path)
            }
        machineProgress?.phaseCompleted(SOURCE_CHANGE_DETECTION_PHASE, sourceFiles.size)
        val changes =
            if (forceFullRebuild) {
                SourceChangeSet(sourceFiles.toSet(), detectedChanges.deletedFiles)
            } else {
                detectedChanges
            }
        machineProgress?.countersAvailable(
            changedFiles = changes.changedFiles.size,
            unchangedFiles = sourceFiles.size - changes.changedFiles.size,
            removedFiles = changes.deletedFiles.size,
        )
        return changes
    }

    private companion object {
        const val SOURCE_HASH_PREVIEW_PHASE = "source-hash-preview"
    }
}

internal data class IndexBuildExecution(
    val exitCode: Int,
    val manifest: IndexManifest?,
    val changes: SourceChangeSet?,
    val reusedFreshIndex: Boolean,
)
