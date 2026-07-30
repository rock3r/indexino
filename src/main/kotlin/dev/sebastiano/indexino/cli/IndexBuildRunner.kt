package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
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
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import dev.sebastiano.indexino.producer.SOURCE_CHANGE_DETECTION_PHASE
import dev.sebastiano.indexino.producer.SourceChangeDetector
import dev.sebastiano.indexino.producer.SourceChangeSet
import dev.sebastiano.indexino.topology.SourceOriginResolver
import dev.sebastiano.indexino.topology.TopologyRequest
import dev.sebastiano.indexino.topology.TopologyResolver
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

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
    private var latestSourceFiles: List<String> = emptyList()
    private var reusedFreshIndex: Boolean = false

    fun runDetailed(): IndexBuildExecution {
        val exitCode = run()
        if (exitCode != CliExitCodes.SUCCESS) {
            return IndexBuildExecution(exitCode, null, null, latestSourceFiles, reusedFreshIndex)
        }
        return IndexBuildExecution(
            exitCode = exitCode,
            manifest =
                checkNotNull(latestManifest) { "Successful index run did not produce a manifest" },
            changes = latestChanges,
            sourceFiles = latestSourceFiles,
            reusedFreshIndex = reusedFreshIndex,
        )
    }

    fun run(): Int {
        latestChanges = null
        latestManifest = null
        latestSourceFiles = emptyList()
        reusedFreshIndex = false
        val topologyResult =
            TopologyResolver.resolve(
                project = project,
                request = topologyRequest,
                bazelQueryExecutor = bazelQueryExecutor,
                bazelProcessRunner = bazelProcessRunner,
                onStderr = progress,
            )
        if (
            topologyResult.sourceFiles.isEmpty() &&
                topologyResult.externalSources.none { it.sourceFiles.isNotEmpty() }
        ) {
            progress("topology discovery failed: no source files")
            machineProgress?.failed(CliExitCodes.TOPOLOGY_FAILED, "no source files")
            return CliExitCodes.TOPOLOGY_FAILED
        }

        val sourceFiles = topologyResult.sourceFiles
        val externalOriginMetadata =
            topologyResult.externalSources.associate { mount ->
                mount.root.toRealPath() to (mount.originId to mount.expectedRevision)
            }
        val sources =
            SourceOriginResolver.resolve(project, sourceFiles).flatMap { origin ->
                origin.sourceFiles.map { path -> IndexedSource(origin.id, origin.root, path) }
            } +
                topologyResult.externalSources.flatMap { mount ->
                    mount.sourceFiles.map { path ->
                        IndexedSource(
                            mount.originId ?: SourceOriginResolver.externalOriginId(mount.root),
                            mount.root,
                            path,
                        )
                    }
                }
        latestSourceFiles = sourceFiles
        val pluginRegistry = PluginRegistry.load(javaClass.classLoader)
        val unknownApplications = applications.filter {
            PluginId.of(it) !in pluginRegistry.pluginIds()
        }
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
        val previewHash = previewHash(sources)
        val origins = manifestOrigins(sources, externalOriginMetadata)
        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = commit,
                scope = topologyResult.scope,
                includeDeps = topologyResult.includeDeps,
                sourcesContentHash = previewHash,
                applications = applications,
                pluginCoordinates = pluginCoordinates,
                origins = origins,
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
            sources = sources,
            origins = origins,
            previewHash = previewHash,
            pluginRegistry = pluginRegistry,
            pluginCoordinates = pluginCoordinates,
            forceFullRebuild =
                existingManifest == null || existingManifest.indexerVersion != Version.NAME,
        )
        machineProgress?.completed("indexed")
        return CliExitCodes.SUCCESS
    }

    private fun previewHash(sources: List<IndexedSource>): String {
        machineProgress?.phaseStarted(SOURCE_HASH_PREVIEW_PHASE, sources.size)
        val previewHash = FileHashProducer.combinedIndexedSourcesHash(sources)
        machineProgress?.phaseCompleted(SOURCE_HASH_PREVIEW_PHASE, sources.size)
        return previewHash
    }

    private fun buildStore(
        resolver: IndexPathResolver,
        commit: String,
        scope: String,
        topology: String,
        includeDeps: Boolean,
        sourceFiles: List<String>,
        sources: List<IndexedSource>,
        origins: List<IndexManifestOrigin>,
        previewHash: String,
        pluginRegistry: PluginRegistry,
        pluginCoordinates: Map<String, String>,
        forceFullRebuild: Boolean,
    ) {
        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
        val previousRecords = store.prefixScan("").toList()
        try {
            val changes = detectChanges(store, sources, forceFullRebuild)
            latestChanges = changes
            val context =
                IndexBuildContext(
                    store = store,
                    commitHash = commit,
                    scope = scope,
                    sourceFiles = sourceFiles,
                    workspaceRoot = project,
                    sources = sources,
                    progress = progress,
                    machineProgress = machineProgress,
                    changedSourceFiles = changes.changedFiles,
                    deletedSourceFiles = changes.deletedFiles,
                    changedSourceSet = changes.changedSources,
                    deletedSourceSet = changes.deletedSources,
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
                    sourceFileCount = sources.size,
                    sourcesContentHash = previewHash,
                    builtAt = Instant.now().toString(),
                    applications = applications,
                    pluginCoordinates = pluginCoordinates,
                    origins = origins,
                )
            ManifestIO.write(resolver.resolveManifest(commit), manifest)
            latestManifest = manifest
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            store.prefixScan("").forEach { (key, _) -> store.delete(key) }
            previousRecords.forEach { (key, record) -> store.put(key, record) }
            throw failure
        } finally {
            store.close()
        }
    }

    private fun manifestOrigins(
        sources: List<IndexedSource>,
        externalOriginMetadata: Map<Path, Pair<String?, String?>>,
    ): List<IndexManifestOrigin> =
        sources
            .groupBy { it.originId to it.originRoot }
            .map { (identity, originSources) ->
                val (originId, originRoot) = identity
                val stateFingerprint =
                    FileHashProducer.contentHash(
                        originSources
                            .sortedBy { it.path }
                            .joinToString("\n") { source ->
                                val file = source.originRoot.resolve(source.path)
                                "${source.path}:${FileHashProducer.contentHash(file.readText())}"
                            }
                    )
                IndexManifestOrigin(
                    originId = originId,
                    revision =
                        GitHeadResolver.resolve(originRoot)
                            .takeUnless(GitHeadResolver::isFilesystemRevision),
                    stateFingerprint = stateFingerprint,
                    expectedRevision =
                        externalOriginMetadata[originRoot.toRealPath()]?.second
                            ?: expectedSubmoduleRevision(originRoot),
                    dirty = isGitDirty(originRoot),
                )
            }
            .sortedBy { it.originId }

    private fun isGitDirty(originRoot: Path): Boolean {
        val process =
            runCatching {
                    ProcessBuilder("git", "-C", originRoot.toString(), "status", "--porcelain")
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return false
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() == 0 && output.isNotBlank()
    }

    private fun expectedSubmoduleRevision(originRoot: Path): String? {
        val canonicalWorkspace = project.toRealPath()
        if (originRoot == canonicalWorkspace || !originRoot.startsWith(canonicalWorkspace))
            return null
        val mount = canonicalWorkspace.relativize(originRoot).toString().replace('\\', '/')
        val process =
            runCatching {
                    ProcessBuilder(
                            "git",
                            "-C",
                            canonicalWorkspace.toString(),
                            "ls-tree",
                            "HEAD",
                            "--",
                            mount,
                        )
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return null
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) return null
        val fields = output.substringBefore('\t').split(' ')
        return fields.getOrNull(0)?.takeIf { it == "160000" }?.let { fields.getOrNull(2) }
    }

    private fun detectChanges(
        store: XodusCodeIndexStore,
        sources: List<IndexedSource>,
        forceFullRebuild: Boolean,
    ): SourceChangeSet {
        machineProgress?.phaseStarted(SOURCE_CHANGE_DETECTION_PHASE, sources.size)
        val detectedChanges =
            SourceChangeDetector.detect(store, sources) { index, total, path ->
                machineProgress?.fileProgress(SOURCE_CHANGE_DETECTION_PHASE, index, total, path)
            }
        machineProgress?.phaseCompleted(SOURCE_CHANGE_DETECTION_PHASE, sources.size)
        val changes =
            if (forceFullRebuild) {
                SourceChangeSet(
                    changedSources = sources.toCollection(linkedSetOf()),
                    deletedSources = detectedChanges.deletedSources,
                )
            } else {
                detectedChanges
            }
        machineProgress?.countersAvailable(
            changedFiles = changes.changedFiles.size,
            unchangedFiles = sources.size - changes.changedSources.size,
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
    val sourceFiles: List<String>,
    val reusedFreshIndex: Boolean,
)
