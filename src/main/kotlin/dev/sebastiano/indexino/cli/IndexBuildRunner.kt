package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.Version
import dev.sebastiano.indexino.core.cache.ContentAddressedPackCache
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
import dev.sebastiano.indexino.topology.TopologyResult
import dev.sebastiano.indexino.topology.bazel.BazelProcessRunner
import dev.sebastiano.indexino.topology.bazel.BazelQueryExecutor
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

// Keep eligibility, checkpoint join, and fallback decisions in the build owner.
@Suppress("LargeClass")
internal class IndexBuildRunner(
    private val project: Path,
    private val topologyRequest: TopologyRequest,
    private val applications: List<String>,
    private val bazelQueryExecutor: BazelQueryExecutor?,
    private val bazelProcessRunner: BazelProcessRunner?,
    private val progress: (String) -> Unit,
    private val machineProgress: IndexBuildProgressReporter?,
    private val storeRootOverride: Path? = null,
    private val topologyOverride: TopologyResult? = null,
    private val inheritedSourceHashes: Map<IndexedSource, String> = emptyMap(),
    /** The closure resolved for [topologyOverride]; only valid with that exact topology. */
    private val inheritedSources: List<IndexedSource>? = null,
    /** Git origin state captured with [inheritedSources]; see [OriginIncrementalHint]. */
    private val inheritedOriginStates: Map<String, OriginGitState>? = null,
    /** Root of a recursive watcher that proved only [hintedPaths] changed since the capture. */
    private val recursiveWatchRoot: Path? = null,
    private val captureOriginStates: Boolean = false,
    private val hintedPaths: Set<Path> = emptySet(),
    private val onSourcesResolved: ((List<IndexedSource>, List<Path>) -> Unit)? = null,
) {
    private var latestChanges: SourceChangeSet? = null
    private var latestManifest: IndexManifest? = null
    private var latestSourceFiles: List<String> = emptyList()
    private var latestSources: List<IndexedSource> = emptyList()
    private var latestTopologyRoots: List<Path> = emptyList()
    private var latestTopologyResult: TopologyResult? = null
    private var latestSourceHashes: Map<IndexedSource, String> = emptyMap()
    private var latestOriginStates: Map<String, OriginGitState> = emptyMap()
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
                latestTopologyResult,
                latestSourceHashes,
                latestOriginStates,
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
            topologyResult = latestTopologyResult,
            sourceHashes = latestSourceHashes,
            originStates = latestOriginStates,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun run(): Int {
        latestChanges = null
        latestManifest = null
        latestSourceFiles = emptyList()
        latestSources = emptyList()
        latestTopologyRoots = emptyList()
        latestTopologyResult = null
        latestSourceHashes = emptyMap()
        latestOriginStates = emptyMap()
        reusedFreshIndex = false
        latestForkBase = null
        latestOverlayDeltaPath = null
        latestTombstonePrefixes = emptyList()
        val topologyResult =
            timedPhase("topology") {
                topologyOverride?.also { progress("index topology=reused watcher-source-edit") }
                    ?: TopologyResolver.resolve(
                        project = project,
                        request = topologyRequest,
                        bazelQueryExecutor = bazelQueryExecutor,
                        bazelProcessRunner = bazelProcessRunner,
                        onStderr = progress,
                    )
            }
        latestTopologyResult = topologyResult
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
            inheritedSources?.also { progress("index sources=reused watcher-capture") }
                ?: timedPhase("source-resolution") {
                    resolveSources(
                        sourceFiles,
                        topologyResult.externalSources,
                        topologyResult.codeSourceFiles,
                    )
                }
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
        onSourcesResolved?.let { register ->
            timedPhase("watcher-registration") { register(sources, latestTopologyRoots) }
        }
        val sourceSnapshot =
            timedPhase("source-capture") {
                SourceContentSnapshot.capture(sources, inheritedSourceHashes, hintedPaths)
            }
        val inheritedCount = sourceSnapshot.inheritedCount()
        progress(
            "index source-capture read=${sources.size - inheritedCount} inherited=$inheritedCount"
        )
        latestSourceHashes = sourceSnapshot.hashes()
        val pluginCoordinates = pluginRegistry.selectedCoordinates(applications)
        machineProgress?.discoveryCompleted(sources.size)
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project, storeRootOverride = storeRootOverride)
        val manifestPath = resolver.resolveManifest(commit)
        BuildStoreCheckpoint.requireRecovered(manifestPath)
        val previewHash = timedPhase("source-preview") { previewHash(sources, sourceSnapshot) }
        val existingManifest = manifestPath.takeIf { it.exists() }?.let(ManifestIO::read)
        val publishedManifest =
            WorkspaceGenerationManifestStore(
                    InProcessCacheLayout.cacheRoot(),
                    InProcessCacheLayout.workspaceId(project),
                )
                .current()
                ?.compatibilityManifest
        val sourceOriginIds = sources.map { it.originId }.distinct().sorted()
        val sourceRoots = sources.mapTo(linkedSetOf()) { it.originRoot }
        // Only a known-source edit can skip the freshness decision while provenance is pending.
        // Source-less mounts and implicit workspace origins may change the origin inventory.
        val overlapOrigins =
            inheritedSources != null &&
                inheritedOriginStates != null &&
                recursiveWatchRoot != null &&
                hintedPaths.isNotEmpty() &&
                existingManifest != null &&
                existingManifest.commit == commit &&
                existingManifest.sourcesContentHash != previewHash &&
                existingManifest.scope == topologyResult.scope &&
                existingManifest.topology == topologyResult.topology &&
                existingManifest.includeDeps == topologyResult.includeDeps &&
                existingManifest.resolvedTopologyDigest == topologyResult.resolvedTopologyDigest &&
                existingManifest.applications.sorted() == applications.sorted() &&
                existingManifest.pluginCoordinates == pluginCoordinates &&
                existingManifest.indexerVersion == Version.NAME &&
                existingManifest.basicFactSchemaVersion == BASIC_FACT_SCHEMA_VERSION &&
                existingManifest.origins.all { it.available } &&
                existingManifest.origins.map { it.originId }.sorted() == sourceOriginIds &&
                externalOriginMetadata.keys.all { root ->
                    sourceRoots.any { it.toRealPath() == root }
                } &&
                (storeRootOverride != InProcessCacheLayout.writerRoot(project) ||
                    publishedManifest?.copy(builtAt = existingManifest.builtAt) ==
                        existingManifest) &&
                (topologyResult.topology == "repo-manifest" || "workspace" in sourceOriginIds)
        val origins =
            if (overlapOrigins) null
            else
                timedPhase("origin-resolution") {
                    resolveOrigins(
                        sources,
                        externalOriginMetadata,
                        topologyResult.topology,
                        sourceSnapshot,
                    )
                }
        val vanishedOrigins =
            existingManifest
                ?.origins
                ?.mapTo(linkedSetOf()) { it.originId }
                ?.minus((origins?.map { it.originId } ?: sourceOriginIds).toSet())
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
                origins = origins.orEmpty(),
                resolvedTopologyDigest = topologyResult.resolvedTopologyDigest,
            )
        if (
            !overlapOrigins &&
                existingManifest != null &&
                ManifestFreshness.isFresh(existingManifest, criteria) &&
                (storeRootOverride != InProcessCacheLayout.writerRoot(project) ||
                    publishedManifest?.copy(builtAt = existingManifest.builtAt) == existingManifest)
        ) {
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
                if (storeRootOverride == InProcessCacheLayout.writerRoot(project)) {
                    WorktreeForkCompatibility.findCurrentWorkspaceBase(
                        project = project,
                        cacheRoot = InProcessCacheLayout.cacheRoot(),
                        criteria = criteria,
                    )
                } else null
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
        val pendingOrigins =
            if (overlapOrigins)
                CompletableFuture.supplyAsync {
                    timedPhase("origin-resolution") {
                        resolveOrigins(
                            sources,
                            externalOriginMetadata,
                            topologyResult.topology,
                            sourceSnapshot,
                        )
                    }
                }
            else null
        fun resolvedOrigins(): List<IndexManifestOrigin> =
            origins
                ?: try {
                    checkNotNull(pendingOrigins).join()
                } catch (failure: CompletionException) {
                    throw failure.cause ?: failure
                }
        try {
            progress("index store=${if (forkBase == null) "writer" else "overlay"}")
            timedPhase("store-build") {
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
                    originIds = origins?.map { it.originId } ?: sourceOriginIds,
                    originsForPublication = ::resolvedOrigins,
                    previewHash = previewHash,
                    pluginRegistry = pluginRegistry,
                    pluginCoordinates = pluginCoordinates,
                    previousPluginCoordinates =
                        (existingManifest ?: forkBase?.baseManifest)?.pluginCoordinates.orEmpty(),
                    forceFullRebuild =
                        (existingManifest == null && forkBase == null) ||
                            (existingManifest != null &&
                                forkBase == null &&
                                storeRootOverride == InProcessCacheLayout.writerRoot(project) &&
                                publishedManifest?.copy(builtAt = existingManifest.builtAt) !=
                                    existingManifest) ||
                            (existingManifest != null &&
                                (existingManifest.indexerVersion != Version.NAME ||
                                    existingManifest.basicFactSchemaVersion !=
                                        BASIC_FACT_SCHEMA_VERSION)),
                    forkBase = forkBase,
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // No origin worker may outlive a failed build; preserve origin-first failure ordering.
            val originFailure = pendingOrigins?.let {
                runCatching(::resolvedOrigins).exceptionOrNull()
            }
            if (originFailure != null && originFailure !== failure) {
                originFailure.addSuppressed(failure)
                throw originFailure
            }
            throw failure
        }
        machineProgress?.completed("indexed")
        return CliExitCodes.SUCCESS
    }

    private fun resolveOrigins(
        sources: List<IndexedSource>,
        externalOriginMetadata: Map<Path, Pair<String?, String?>>,
        topology: String,
        sourceSnapshot: SourceContentSnapshot,
    ): List<IndexManifestOrigin> {
        val hint =
            if (inheritedOriginStates != null && recursiveWatchRoot != null) {
                OriginIncrementalHint(
                    previous = inheritedOriginStates,
                    hintedSources =
                        sources.filter { source ->
                            source.originRoot.resolve(source.path).normalize() in hintedPaths
                        },
                    recursiveWatchRoot = recursiveWatchRoot,
                )
            } else null
        val resolution =
            ManifestOriginResolver.resolveWithState(
                project,
                sources,
                externalOriginMetadata,
                includeWorkspaceWithoutSources = topology != "repo-manifest",
                sourceSnapshot = sourceSnapshot,
                captureState = captureOriginStates,
                hint = hint,
                progress = progress,
            )
        latestOriginStates = resolution.states
        progress(
            "index origins incremental=${resolution.incremental} " +
                "full=${resolution.origins.size - resolution.incremental}"
        )
        return resolution.origins
    }

    private fun <T> timedPhase(phase: String, block: () -> T): T {
        progress("index phase=$phase state=started")
        val start = System.nanoTime()
        val result = block()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        progress("index phase=$phase state=completed durationMillis=$elapsedMillis")
        return result
    }

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
        codeSourceFiles: Set<String>?,
    ): List<IndexedSource> {
        // Canonicalize roots once; per-source realpath calls dominate large closures.
        val canonicalProject = project.toRealPath()
        return SourceOriginResolver.resolve(project, sourceFiles).flatMap { origin ->
            (origin.sourceFiles +
                    ResourceMetadata.additionalMetadataPaths(origin.root, origin.sourceFiles))
                .map { path ->
                    IndexedSource(
                        origin.id,
                        origin.root,
                        path,
                        isCode =
                            codeSourceFiles?.contains(
                                canonicalProject
                                    .relativize(origin.root.resolve(path))
                                    .toString()
                                    .replace('\\', '/')
                            ) ?: true,
                    )
                }
        } +
            externalSources.flatMap { mount ->
                val canonicalMount = mount.root.toRealPath()
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
                            .map { path ->
                                IndexedSource(
                                    origin.id,
                                    origin.root,
                                    path,
                                    isCode =
                                        mount.codeSourceFiles?.contains(
                                            canonicalMount
                                                .relativize(origin.root.resolve(path))
                                                .toString()
                                                .replace('\\', '/')
                                        ) ?: true,
                                )
                            }
                    }
            }
    }

    @Suppress("LongMethod")
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
        originIds: List<String>,
        originsForPublication: () -> List<IndexManifestOrigin>,
        previewHash: String,
        pluginRegistry: PluginRegistry,
        pluginCoordinates: Map<String, String>,
        previousPluginCoordinates: Map<String, String>,
        forceFullRebuild: Boolean,
        forkBase: WorktreeForkBase? = null,
    ) {
        val overlayDeltaPath = forkBase?.let {
            InProcessCacheLayout.overlayBuildDelta(project, commit)
        }
        if (overlayDeltaPath != null) {
            overlayDeltaPath.parent.toFile().deleteRecursively()
            forkBase.previousOverlayPackKey?.let { packKey ->
                timedPhase("overlay-restore") {
                    ContentAddressedPackCache(InProcessCacheLayout.cacheRoot())
                        .materializeDirectory(packKey, overlayDeltaPath)
                }
            }
        }
        val writableStore: XodusCodeIndexStore
        val store =
            if (forkBase != null) {
                val baseManifest =
                    WorkspaceGenerationManifestStore(
                            InProcessCacheLayout.cacheRoot(),
                            forkBase.baseWorkspaceId,
                        )
                        .readGeneration(forkBase.baseGeneration)
                        ?: error("Missing base generation ${forkBase.baseGeneration}")
                val baseStore =
                    WorktreeOverlayStoreOpener.openForBuildBase(
                        cacheRoot = InProcessCacheLayout.cacheRoot(),
                        workspace = forkBase.baseWorkspacePath,
                        manifest = baseManifest,
                    )
                val deltaStore =
                    XodusCodeIndexStore.open(checkNotNull(overlayDeltaPath), readOnly = false)
                writableStore = deltaStore
                WorktreeOverlayIndexStore(baseStore, deltaStore, forkBase.previousTombstones)
            } else {
                val baseStore = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
                writableStore = baseStore
                baseStore
            }
        val timedClose = AutoCloseable {
            val started = System.nanoTime()
            // Closing must not be skipped when a cancelled refresh rejects progress.
            runCatching { progress("index phase=store-close state=started") }
            store.close()
            runCatching {
                progress(
                    "index phase=store-close state=completed durationMillis=" +
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                )
            }
        }
        timedClose.use {
            BuildStoreCheckpoint(writableStore, resolver.resolveManifest(commit), progress).use {
                checkpoint ->
                checkpoint.run {
                    val changes =
                        timedPhase("change-detection") {
                            detectChanges(store, sources, sourceSnapshot, forceFullRebuild)
                        }
                    latestChanges = changes
                    val tombstones =
                        (changes.deletedSources + changes.changedSources).map { source ->
                            WorktreeOverlayIndexStore.tombstonePrefixForSource(
                                source.originId,
                                source.path.replace('\\', '/'),
                            )
                        } +
                            if (
                                pluginRegistry.postProcessors.any {
                                    it.pluginId.value in applications
                                }
                            ) {
                                (originIds + "workspace").distinct().map { originId ->
                                    WorktreeOverlayIndexStore.tombstonePrefixForSource(
                                        originId,
                                        PluginAnalyzerRunner.POST_PROCESSOR_FILE,
                                    )
                                }
                            } else emptyList()
                    (store as? WorktreeOverlayIndexStore)?.hideBaseForFiles(tombstones)
                    val context =
                        IndexBuildContext(
                            store = store,
                            commitHash = commit,
                            scope = scope,
                            sourceFiles = sourceFiles,
                            workspaceRoot = project,
                            sources = sources,
                            sourceSnapshot = sourceSnapshot,
                            resolvedOriginIds = originIds.toCollection(linkedSetOf()),
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
                        timedPhase("producer:${producer.id}") {
                            producer.produce(context.copy(activePhase = producer.id), store)
                        }
                        machineProgress?.phaseCompleted(producer.id, phaseTotal)
                    }
                    timedPhase("plugins") {
                        val fullAnalysisPlugins =
                            pluginCoordinates
                                .filter { (id, coordinate) ->
                                    forceFullRebuild || previousPluginCoordinates[id] != coordinate
                                }
                                .keys
                        PluginAnalyzerRunner(pluginRegistry)
                            .analyze(context, applications.toSet(), fullAnalysisPlugins)
                    }
                    val origins = originsForPublication()
                    check(origins.map { it.originId }.sorted() == originIds.sorted()) {
                        "Origin inventory changed during build"
                    }
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
                            (forkBase.previousTombstones + tombstones).distinct()
                    }
                }
            }
        }
    }

    private fun detectChanges(
        store: dev.sebastiano.indexino.core.store.CodeIndexStore,
        sources: List<IndexedSource>,
        sourceSnapshot: SourceContentSnapshot,
        forceFullRebuild: Boolean,
    ): SourceChangeSet {
        machineProgress?.phaseStarted(SOURCE_CHANGE_DETECTION_PHASE, sources.size)
        val report: (Int, Int, IndexedSource) -> Unit = { index, total, source ->
            machineProgress?.fileProgress(
                SOURCE_CHANGE_DETECTION_PHASE,
                index,
                total,
                source.originId,
                source.path,
            )
        }
        val detectedChanges =
            if (inheritedSourceHashes.isEmpty())
                SourceChangeDetector.detect(store, sources, sourceSnapshot, report)
            else SourceChangeDetector.detect(inheritedSourceHashes, sources, sourceSnapshot, report)
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
        progress(
            "index changes changed=${changes.changedSources.size} " +
                "deleted=${changes.deletedSources.size} full=$forceFullRebuild"
        )
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
    val topologyResult: TopologyResult? = null,
    val sourceHashes: Map<IndexedSource, String> = emptyMap(),
    val originStates: Map<String, OriginGitState> = emptyMap(),
)
