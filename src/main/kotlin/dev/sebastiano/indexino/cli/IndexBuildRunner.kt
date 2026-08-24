package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorktreeForkBase
import dev.sebastiano.indexino.core.cache.WorktreeForkCompatibility
import dev.sebastiano.indexino.core.cache.WorktreeOverlayStoreOpener
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
import dev.sebastiano.indexino.core.manifest.ManifestFreshness
import dev.sebastiano.indexino.core.manifest.ManifestIO
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
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
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import dev.sebastiano.indexino.producer.xml.ResourceMetadata
import dev.sebastiano.indexino.topology.ExternalSourceMount
import dev.sebastiano.indexino.topology.SourceOriginResolver
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
    private var latestSourceFiles: List<String> = emptyList()
    private var latestSources: List<IndexedSource> = emptyList()
    private var latestTopologyRoots: List<Path> = emptyList()
    private var reusedFreshIndex: Boolean = false
    private var latestForkBase: WorktreeForkBase? = null
    private var latestOverlayDeltaPath: Path? = null
    private var latestTombstonePrefixes: List<String> = emptyList()

    fun runDetailed(): IndexBuildExecution {
        val exitCode = run()
        if (exitCode != CliExitCodes.SUCCESS) {
            return IndexBuildExecution(
                exitCode,
                null,
                null,
                latestSourceFiles,
                latestSources,
                latestTopologyRoots,
                reusedFreshIndex,
                latestForkBase,
                latestOverlayDeltaPath,
                latestTombstonePrefixes,
            )
        }
        return IndexBuildExecution(
            exitCode = exitCode,
            manifest =
                checkNotNull(latestManifest) { "Successful index run did not produce a manifest" },
            changes = latestChanges,
            sourceFiles = latestSourceFiles,
            sources = latestSources,
            topologyRoots = latestTopologyRoots,
            reusedFreshIndex = reusedFreshIndex,
            forkBase = latestForkBase,
            overlayDeltaPath = latestOverlayDeltaPath,
            tombstonePrefixes = latestTombstonePrefixes,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(): Int {
        latestChanges = null
        latestManifest = null
        latestSourceFiles = emptyList()
        latestSources = emptyList()
        latestTopologyRoots = emptyList()
        reusedFreshIndex = false
        latestForkBase = null
        latestOverlayDeltaPath = null
        latestTombstonePrefixes = emptyList()
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
        val sources = resolveSources(sourceFiles, topologyResult.externalSources)
        latestSourceFiles = sourceFiles
        latestSources = sources
        latestTopologyRoots = (listOf(project) + topologyResult.externalMounts).distinct()
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
        val sourceSnapshot = SourceContentSnapshot.capture(sources)
        val pluginCoordinates = pluginRegistry.selectedCoordinates(applications)
        machineProgress?.discoveryCompleted(sources.size)
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project, storeRootOverride = storeRootOverride)
        val manifestPath = resolver.resolveManifest(commit)
        val previewHash = previewHash(sources, sourceSnapshot)
        val origins =
            resolveOrigins(sources, externalOriginMetadata, topologyResult.topology, sourceSnapshot)
        val existingManifest = manifestPath.takeIf { it.exists() }?.let(ManifestIO::read)
        val vanishedOrigins =
            existingManifest
                ?.origins
                ?.mapTo(linkedSetOf()) { it.originId }
                ?.minus(origins.mapTo(linkedSetOf()) { it.originId })
                .orEmpty()
        val preservesExistingTopology =
            existingManifest?.scope == topologyResult.scope &&
                existingManifest.topology == topologyResult.topology &&
                existingManifest.includeDeps == topologyResult.includeDeps &&
                existingManifest.resolvedTopologyDigest == topologyResult.resolvedTopologyDigest
        if (preservesExistingTopology && vanishedOrigins.isNotEmpty()) {
            WorkspaceGenerationManifestStore(
                    InProcessCacheLayout.cacheRoot(),
                    InProcessCacheLayout.workspaceId(project),
                )
                .markOriginsUnavailable(vanishedOrigins)
            val message = "topology origin unavailable: ${vanishedOrigins.sorted().joinToString()}"
            progress(message)
            machineProgress?.failed(CliExitCodes.TOPOLOGY_FAILED, message)
            return CliExitCodes.TOPOLOGY_FAILED
        }
        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = commit,
                scope = topologyResult.scope,
                includeDeps = topologyResult.includeDeps,
                sourcesContentHash = previewHash,
                applications = applications,
                pluginCoordinates = pluginCoordinates,
                origins = origins,
                resolvedTopologyDigest = topologyResult.resolvedTopologyDigest,
            )
        if (existingManifest != null && ManifestFreshness.isFresh(existingManifest, criteria)) {
            latestManifest = existingManifest
            reusedFreshIndex = true
            progress("index fresh for ${topologyResult.scope} @ $commit — skip rebuild")
            machineProgress?.completed("fresh")
            return CliExitCodes.SUCCESS
        }

        val forkBase =
            if (existingManifest == null) {
                WorktreeForkCompatibility.findCompatibleBase(
                    project = project,
                    cacheRoot = InProcessCacheLayout.cacheRoot(),
                    criteria = criteria,
                )
            } else {
                null
            }
        if (forkBase != null && forkBase.unchanged) {
            ManifestIO.write(manifestPath, forkBase.baseManifest)
            latestManifest = forkBase.baseManifest
            latestForkBase = forkBase
            reusedFreshIndex = true
            progress("worktree fork reuses compatible base generation — skip rebuild")
            machineProgress?.completed("fresh")
            return CliExitCodes.SUCCESS
        }
        if (forkBase != null) {
            latestForkBase = forkBase
        }

        buildStore(
            resolver = resolver,
            commit = commit,
            scope = topologyResult.scope,
            topology = topologyResult.topology,
            resolvedTopologyDigest = topologyResult.resolvedTopologyDigest,
            includeDeps = topologyResult.includeDeps,
            sourceFiles = sourceFiles,
            sources = sources,
            sourceSnapshot = sourceSnapshot,
            origins = origins,
            previewHash = previewHash,
            pluginRegistry = pluginRegistry,
            pluginCoordinates = pluginCoordinates,
            forceFullRebuild =
                (existingManifest == null && forkBase == null) ||
                    (existingManifest != null &&
                        (existingManifest.indexerVersion != Version.NAME ||
                            existingManifest.basicFactSchemaVersion != BASIC_FACT_SCHEMA_VERSION)),
            forkBase = forkBase,
        )
        machineProgress?.completed("indexed")
        return CliExitCodes.SUCCESS
    }

    private fun resolveOrigins(
        sources: List<IndexedSource>,
        externalOriginMetadata: Map<Path, Pair<String?, String?>>,
        topology: String,
        sourceSnapshot: SourceContentSnapshot,
    ): List<IndexManifestOrigin> =
        ManifestOriginResolver.resolve(
            project,
            sources,
            externalOriginMetadata,
            includeWorkspaceWithoutSources = topology != "repo-manifest",
            sourceSnapshot = sourceSnapshot,
        )

    private fun previewHash(
        sources: List<IndexedSource>,
        sourceSnapshot: SourceContentSnapshot,
    ): String {
        machineProgress?.phaseStarted(SOURCE_HASH_PREVIEW_PHASE, sources.size)
        val previewHash =
            FileHashProducer.combinedIndexedSourcesHash(sources, sourceSnapshot) {
                index,
                total,
                source ->
                machineProgress?.fileProgress(
                    SOURCE_HASH_PREVIEW_PHASE,
                    index,
                    total,
                    source.originId,
                    source.path,
                )
            }
        machineProgress?.phaseCompleted(SOURCE_HASH_PREVIEW_PHASE, sources.size)
        return previewHash
    }

    private fun resolveSources(
        sourceFiles: List<String>,
        externalSources: List<ExternalSourceMount>,
    ): List<IndexedSource> =
        SourceOriginResolver.resolve(project, sourceFiles).flatMap { origin ->
            (origin.sourceFiles +
                    ResourceMetadata.additionalMetadataPaths(origin.root, origin.sourceFiles))
                .map { path -> IndexedSource(origin.id, origin.root, path) }
        } +
            externalSources.flatMap { mount ->
                SourceOriginResolver.resolveExternal(
                        mountRoot = mount.root,
                        sourceFiles = mount.sourceFiles,
                        mountOriginId =
                            mount.originId ?: SourceOriginResolver.externalOriginId(mount.root),
                    )
                    .flatMap { origin ->
                        (origin.sourceFiles +
                                ResourceMetadata.additionalMetadataPaths(
                                    origin.root,
                                    origin.sourceFiles,
                                ))
                            .map { path -> IndexedSource(origin.id, origin.root, path) }
                    }
            }

    private fun buildStore(
        resolver: IndexPathResolver,
        commit: String,
        scope: String,
        topology: String,
        resolvedTopologyDigest: String?,
        includeDeps: Boolean,
        sourceFiles: List<String>,
        sources: List<IndexedSource>,
        sourceSnapshot: SourceContentSnapshot,
        origins: List<IndexManifestOrigin>,
        previewHash: String,
        pluginRegistry: PluginRegistry,
        pluginCoordinates: Map<String, String>,
        forceFullRebuild: Boolean,
        forkBase: WorktreeForkBase? = null,
    ) {
        val overlayDeltaPath = forkBase?.let {
            InProcessCacheLayout.overlayBuildDelta(project, commit)
        }
        if (overlayDeltaPath != null) {
            overlayDeltaPath.parent.toFile().deleteRecursively()
        }
        val store =
            if (forkBase != null) {
                val baseStorePath =
                    WorktreeOverlayStoreOpener.materializedGenerationStore(
                        cacheRoot = InProcessCacheLayout.cacheRoot(),
                        workspace = forkBase.baseWorkspacePath,
                        manifest =
                            WorkspaceGenerationManifestStore(
                                    InProcessCacheLayout.cacheRoot(),
                                    forkBase.baseWorkspaceId,
                                )
                                .readGeneration(forkBase.baseGeneration)
                                ?: error("Missing base generation ${forkBase.baseGeneration}"),
                    )
                val baseStore = XodusCodeIndexStore.open(baseStorePath, readOnly = true)
                val deltaStore =
                    XodusCodeIndexStore.open(checkNotNull(overlayDeltaPath), readOnly = false)
                WorktreeOverlayIndexStore(baseStore, deltaStore, emptyList())
            } else {
                XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
            }
        val previousRecords = store.prefixScan("").toList()
        try {
            val changes = detectChanges(store, sources, sourceSnapshot, forceFullRebuild)
            latestChanges = changes
            val context =
                IndexBuildContext(
                    store = store,
                    commitHash = commit,
                    scope = scope,
                    sourceFiles = sourceFiles,
                    workspaceRoot = project,
                    sources = sources,
                    sourceSnapshot = sourceSnapshot,
                    resolvedOriginIds = origins.mapTo(linkedSetOf()) { it.originId },
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
                    basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
                    scope = scope,
                    topology = topology,
                    includeDeps = includeDeps,
                    sourceFileCount = sources.size,
                    sourcesContentHash = previewHash,
                    builtAt = Instant.now().toString(),
                    applications = applications,
                    pluginCoordinates = pluginCoordinates,
                    origins = origins,
                    resolvedTopologyDigest = resolvedTopologyDigest,
                )
            ManifestIO.write(resolver.resolveManifest(commit), manifest)
            latestManifest = manifest
            if (overlayDeltaPath != null) {
                latestOverlayDeltaPath = overlayDeltaPath
                latestTombstonePrefixes =
                    (changes.deletedSources + changes.changedSources)
                        .map { source ->
                            val relative = source.path.replace('\\', '/')
                            WorktreeOverlayIndexStore.tombstonePrefixForRelativeFile(relative)
                        }
                        .distinct()
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            store.prefixScan("").forEach { (key, _) -> store.delete(key) }
            previousRecords.forEach { (key, record) -> store.put(key, record) }
            throw failure
        } finally {
            store.close()
        }
    }

    private fun detectChanges(
        store: dev.sebastiano.indexino.core.store.CodeIndexStore,
        sources: List<IndexedSource>,
        sourceSnapshot: SourceContentSnapshot,
        forceFullRebuild: Boolean,
    ): SourceChangeSet {
        machineProgress?.phaseStarted(SOURCE_CHANGE_DETECTION_PHASE, sources.size)
        val detectedChanges =
            SourceChangeDetector.detect(store, sources, sourceSnapshot) { index, total, source ->
                machineProgress?.fileProgress(
                    SOURCE_CHANGE_DETECTION_PHASE,
                    index,
                    total,
                    source.originId,
                    source.path,
                )
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
            changedFiles = changes.changedSources.size,
            unchangedFiles = sources.size - changes.changedSources.size,
            removedFiles = changes.deletedSources.size,
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
    val sources: List<IndexedSource>,
    val topologyRoots: List<Path>,
    val reusedFreshIndex: Boolean,
    val forkBase: WorktreeForkBase? = null,
    val overlayDeltaPath: Path? = null,
    val tombstonePrefixes: List<String> = emptyList(),
)
