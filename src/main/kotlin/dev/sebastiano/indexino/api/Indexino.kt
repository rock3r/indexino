@file:Suppress("RedundantSuspendModifier")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.cli.CliExitCodes
import dev.sebastiano.indexino.cli.IndexBuildExecution
import dev.sebastiano.indexino.cli.IndexBuildRunner
import dev.sebastiano.indexino.core.BASIC_FACT_SCHEMA_VERSION
import dev.sebastiano.indexino.core.cache.ContentAddressedPackCache
import dev.sebastiano.indexino.core.cache.GitWorktreeLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationOrigin
import dev.sebastiano.indexino.core.cache.WorkspaceRegistryStore
import dev.sebastiano.indexino.core.cache.WorktreeForkBase
import dev.sebastiano.indexino.core.cache.WorktreeOverlayPolicy
import dev.sebastiano.indexino.core.cache.WorktreeOverlayStoreOpener
import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.workspaceRevisionFingerprint
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.sourcelink.LinkIndexService
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.engine.InFlightRefresh
import dev.sebastiano.indexino.engine.IndexingCoordinator
import dev.sebastiano.indexino.engine.PluginRegistry
import dev.sebastiano.indexino.engine.RuntimeClientBootstrap
import dev.sebastiano.indexino.engine.RuntimeConnection
import dev.sebastiano.indexino.engine.RuntimeProtocolException
import dev.sebastiano.indexino.engine.RuntimeRefreshClient
import dev.sebastiano.indexino.engine.RuntimeRefreshHandle
import dev.sebastiano.indexino.engine.RuntimeSnapshotClient
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.LinkGenerationId
import dev.sebastiano.indexino.model.RefreshId
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexBuildProgressReporter
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.topology.BuildSystem as InternalBuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LargeClass", "TooManyFunctions")
public class Indexino
private constructor(
    private val workspace: Path,
    private val runtimeConnection: RuntimeConnection? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val clientId = UUID.randomUUID().toString()
    private val storeRoot = InProcessCacheLayout.writerRoot(workspace)
    private val generationLock = Any()
    private val generationStores = mutableMapOf<WorkspaceGenerationId, Path>()
    private val snapshotPins = mutableMapOf<WorkspaceGenerationId, Int>()
    private var published: PublishedGeneration? = null
    private val remoteSnapshots = mutableMapOf<String, IndexSnapshot>()

    /**
     * Test-only seam: runs after a generation copy is staged and before it is published under
     * [generationLock]. Production leaves this null. Instance-scoped so one client cannot leak the
     * hook into another.
     */
    @Volatile internal var afterPublishGenerationStoreForTests: (() -> Unit)? = null

    /**
     * Test-only seam: when non-null, compared against [IndexScope.includesDependencies] instead of
     * the manifest's observed `includeDeps`. Production leaves this null.
     */
    @Volatile internal var observedIncludeDepsOverrideForTests: Boolean? = null

    /** Runtime-owned hook that receives the resolved source closure of completed refreshes. */
    @Volatile
    internal var onRefreshSucceededForRuntime:
        ((RefreshRequest, List<IndexedSource>, List<Path>) -> Unit)? =
        null

    public companion object {
        /**
         * Test-only seam: replaces [Path.toRealPath] during [connectBlocking]. Production leaves
         * this null.
         */
        @Volatile internal var canonicalWorkspacePathForTests: ((Path) -> Path)? = null

        /** Test-only seam that keeps legacy unit fixtures in one process. */
        @Volatile internal var defaultRuntimeAttachModeForTests: RuntimeAttachMode? = null

        @JvmStatic
        public suspend fun connect(workspace: Path): Indexino = connectBlocking(workspace)

        @JvmStatic
        public suspend fun connect(configuration: IndexinoConfiguration): Indexino =
            connectBlocking(configuration)

        @JvmStatic
        public fun connectBlocking(workspace: Path): Indexino =
            connectBlocking(
                IndexinoConfiguration.forWorkspace(workspace)
                    .withRuntimeAttach(
                        defaultRuntimeAttachModeForTests ?: RuntimeAttachMode.PREFER_DAEMON
                    )
            )

        @JvmStatic
        public fun connectBlocking(configuration: IndexinoConfiguration): Indexino =
            connectBlocking(configuration, allowExistingModeMismatch = false)

        internal fun connectBlockingForCli(configuration: IndexinoConfiguration): Indexino =
            connectBlocking(configuration, allowExistingModeMismatch = true)

        private fun connectBlocking(
            configuration: IndexinoConfiguration,
            allowExistingModeMismatch: Boolean,
        ): Indexino {
            val canonical = canonicalWorkspace(configuration.workspace)
            return when (configuration.runtimeAttachMode) {
                RuntimeAttachMode.PREFER_DAEMON ->
                    try {
                        Indexino(
                            canonical,
                            RuntimeClientBootstrap.connect(
                                canonical,
                                configuration.autoRefreshMode,
                                allowExistingModeMismatch,
                            ),
                        )
                    } catch (thrown: IndexinoException) {
                        throw thrown
                    } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                        throw indexinoFailure(
                            category = IndexFailureCategory.IO,
                            code = "runtime_connect_failed",
                            message = "Unable to connect to the local Indexino runtime",
                            retryable = true,
                            cause = thrown,
                        )
                    }
                RuntimeAttachMode.IN_PROCESS -> Indexino(canonical)
            }
        }

        internal fun connectRemote(workspace: Path, connection: RuntimeConnection): Indexino =
            Indexino(workspace, connection)

        private fun canonicalWorkspace(workspace: Path): Path {
            if (!Files.isDirectory(workspace)) {
                throw indexinoFailure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "invalid_workspace",
                    message = "Workspace must be an existing directory",
                    retryable = false,
                )
            }
            return try {
                canonicalWorkspacePathForTests?.invoke(workspace) ?: workspace.toRealPath()
            } catch (thrown: IOException) {
                throw indexinoFailure(
                    category = IndexFailureCategory.IO,
                    code = "workspace_path_unresolvable",
                    message =
                        thrown.message?.takeIf { it.isNotBlank() }
                            ?: "Workspace path could not be resolved",
                    retryable = true,
                    cause = thrown,
                )
            }
        }
    }

    @OptIn(IndexinoInternalApi::class)
    public suspend fun refresh(request: RefreshRequest): RefreshHandle =
        refresh(request, progress = {}, machineProgress = null)

    @OptIn(IndexinoInternalApi::class)
    internal suspend fun refresh(
        request: RefreshRequest,
        progress: (String) -> Unit,
        machineProgress: IndexBuildProgressReporter?,
    ): RefreshHandle {
        ensureOpen()
        runtimeConnection?.let { connection ->
            return mapRemoteFailures { remoteRefresh(connection, request) }
        }
        val applications = applicationsFor(request)
        val operation =
            IndexingCoordinator.start(workspace, request) { created ->
                try {
                    val result =
                        runRefresh(
                            request,
                            created.id,
                            applications,
                            created,
                            progress,
                            machineProgress,
                        )
                    if (!created.isStopped()) {
                        created.result.complete(result)
                        created.terminalEvent.complete(RefreshCompleted(created.id, result))
                    }
                } catch (cancelled: CancellationException) {
                    if (!created.isStopped()) {
                        val failure = mapRefreshFailure(cancelled)
                        created.result.completeExceptionally(failure)
                        created.terminalEvent.complete(
                            RefreshHandle.failed(created.id, failure.failure)
                        )
                    }
                } catch (thrown: IndexinoException) {
                    created.result.completeExceptionally(thrown)
                    created.terminalEvent.complete(RefreshHandle.failed(created.id, thrown.failure))
                } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                    val failure = mapRefreshFailure(thrown)
                    created.result.completeExceptionally(failure)
                    created.terminalEvent.complete(
                        RefreshHandle.failed(created.id, failure.failure)
                    )
                }
            }
        return RefreshHandle.inFlight(
            operation.id,
            operation.result,
            operation.terminalEvent,
            operation::stop,
        )
    }

    internal fun refreshProgress(
        id: String
    ): dev.sebastiano.indexino.engine.RuntimeRefreshProgress {
        val connection = checkNotNull(runtimeConnection) { "Refresh progress is daemon-owned" }
        return RuntimeConnection.connect(connection.endpoint).use { progressConnection ->
            RuntimeRefreshClient(progressConnection).progress(id)
        }
    }

    @OptIn(IndexinoInternalApi::class)
    public suspend fun activeRefreshes(): List<RefreshSummary> {
        ensureOpen()
        runtimeConnection?.let { connection ->
            return mapRemoteFailures { RuntimeRefreshClient(connection).active() }
        }
        return IndexingCoordinator.active(workspace)
            .asSequence()
            .map { (request, operation) -> RefreshSummary(operation.id, request) }
            .sortedBy { summary -> summary.id.value }
            .toList()
    }

    @OptIn(IndexinoInternalApi::class)
    private fun runRefresh(
        request: RefreshRequest,
        refreshId: RefreshId,
        applications: List<String>,
        operation: InFlightRefresh,
        progress: (String) -> Unit,
        machineProgress: IndexBuildProgressReporter?,
    ): RefreshResult {
        // Serialize in-process peers across run+copy so a shared commit writer cannot mutate while
        // another client copies it. Cross-process single-writer election remains S6.
        synchronized(IndexingCoordinator.refreshLockFor(workspace)) {
            try {
                operation.checkActive()
                val execution =
                    IndexBuildRunner(
                            project = workspace,
                            topologyRequest = request.scope.toTopologyRequest(),
                            applications = applications,
                            bazelQueryExecutor = null,
                            bazelProcessRunner = null,
                            progress = { message ->
                                operation.checkActive()
                                progress(message)
                            },
                            machineProgress = machineProgress,
                            storeRootOverride = storeRoot,
                        )
                        .runDetailed()
                operation.checkActive()
                val manifest =
                    execution.manifest
                        ?: throw buildFailure(
                            execution,
                            "Refresh failed while resolving or indexing ${request.scope.value}",
                        )
                requireScopeMatchesManifest(
                    scope = request.scope,
                    observedIncludeDeps = manifest.includeDeps,
                    observedTopology = manifest.topology,
                    observedOverride = observedIncludeDepsOverrideForTests,
                )
                val revision = manifest.toWorkspaceRevision()
                val generation = manifest.toGenerationId(revision)
                operation.checkActive()
                publishGenerationOrAbortIfClosed(
                    manifest.commit,
                    generation,
                    revision,
                    request.scope,
                    applications,
                    manifest,
                    execution.forkBase,
                    execution.overlayDeltaPath,
                    execution.tombstonePrefixes,
                )
                onRefreshSucceededForRuntime?.invoke(
                    request,
                    execution.sources,
                    execution.topologyRoots,
                )
                val changedFileCount = execution.changes?.changedSources?.size ?: 0
                val removedFileCount = execution.changes?.deletedSources?.size ?: 0
                val result =
                    RefreshResult(
                        refreshId = refreshId,
                        outcome =
                            if (execution.reusedFreshIndex) {
                                RefreshOutcome.UNCHANGED
                            } else {
                                RefreshOutcome.UPDATED
                            },
                        generation = generation,
                        revision = revision,
                        scope = request.scope,
                        changes =
                            IndexChanges(
                                changedFileCount = changedFileCount,
                                unchangedFileCount =
                                    (manifest.sourceFileCount - changedFileCount).coerceAtLeast(0),
                                removedFileCount = removedFileCount,
                            ),
                    )
                return result
            } catch (thrown: IndexinoException) {
                throw thrown
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                // Contract: unexpected throwables become IndexinoException INTERNAL/TOPOLOGY with
                // the original retained as cause — never leak internal exception types.
                throw mapRefreshFailure(thrown)
            }
        }
    }

    private fun applicationsFor(request: RefreshRequest): List<String> {
        val registry = PluginRegistry.load(Indexino::class.java.classLoader)
        return request.plugins.map { plugin ->
            if (registry.descriptor(plugin) == null) {
                throw failure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "unknown_plugin",
                    message = "Plugin ${plugin.value} is not loaded",
                    retryable = false,
                )
            }
            plugin.value
        }
    }

    private fun publishGenerationOrAbortIfClosed(
        commit: String,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
        scope: IndexScope,
        applications: List<String>,
        manifest: IndexManifest,
        forkBase: WorktreeForkBase? = null,
        overlayDeltaPath: Path? = null,
        tombstonePrefixes: List<String> = emptyList(),
    ) {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val linkGeneration = LinkIndexService(cacheRoot, workspace).publishFromConfig()?.value
        val publishedStore =
            publishGenerationStore(
                commit,
                generation,
                revision,
                scope,
                applications,
                manifest,
                forkBase,
                overlayDeltaPath,
                tombstonePrefixes,
                linkGeneration,
            )
        afterPublishGenerationStoreForTests?.invoke()
        synchronized(generationLock) {
            if (closed.get()) {
                // Publication is workspace-owned, but this closed client no longer owns a ref.
                if (publishedStore.created) {
                    deleteClientGenerationRef(publishedStore.path)
                }
                return
            }
            generationStores[generation] = publishedStore.path
            published =
                PublishedGeneration(
                    storePath = publishedStore.path,
                    revision = revision,
                    generation = generation,
                )
            reclaimUnpinnedGenerations()
        }
    }

    public suspend fun snapshot(): IndexSnapshot = snapshot(FreshnessPolicy.PUBLISHED)

    public suspend fun snapshot(freshness: FreshnessPolicy): IndexSnapshot {
        ensureOpen()
        runtimeConnection?.let { connection ->
            return mapRemoteFailures { remoteSnapshot(connection, freshness) }
        }
        if (freshness == FreshnessPolicy.AWAIT_CURRENT) {
            IndexingCoordinator.active(workspace)
                .asSequence()
                .map { (_, operation) -> operation }
                .distinct()
                .forEach { operation ->
                    RefreshHandle.inFlight(
                            operation.id,
                            operation.result,
                            operation.terminalEvent,
                            operation::stop,
                        )
                        .await()
                }
        }
        val generation =
            try {
                synchronized(generationLock) {
                    // Prefer CLOSED over INDEX_NOT_FOUND if close() raced after ensureOpen().
                    if (closed.get()) {
                        throw failure(
                            category = IndexFailureCategory.CLOSED,
                            code = "client_closed",
                            message = "Indexino client is closed",
                            retryable = false,
                        )
                    }
                    val sharedGeneration = currentPublishedGenerationId()
                    val current =
                        published?.takeIf { it.generation == sharedGeneration }
                            ?: restorePublishedGeneration()
                            ?: published
                            ?: throw failure(
                                category = IndexFailureCategory.INDEX_NOT_FOUND,
                                code = "index_not_found",
                                message = "No published index exists; call refresh first",
                                retryable = true,
                            )
                    snapshotPins[current.generation] =
                        snapshotPins.getOrDefault(current.generation, 0) + 1
                    current
                }
            } catch (thrown: IndexinoException) {
                throw thrown
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                throw mapUnexpectedFailure(thrown)
            }
        val openedStore =
            try {
                openPublishedStore(generation.generation)
            } catch (thrown: IndexinoException) {
                releaseGeneration(generation.generation)
                throw thrown
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                // Release the pin before mapping/throwing so a failed open cannot leak disk.
                releaseGeneration(generation.generation)
                throw mapUnexpectedFailure(thrown)
            }
        try {
            return IndexSnapshot.create(
                store = openedStore,
                revision = generation.revision,
                generation = generation.generation,
                freshnessAtAcquisition =
                    if (freshness == FreshnessPolicy.AWAIT_CURRENT) {
                        SnapshotFreshness.CURRENT
                    } else {
                        SnapshotFreshness.UNKNOWN
                    },
                onClose = { releaseGeneration(generation.generation) },
                consumerWorkspace = workspace,
                linkGeneration =
                    WorkspaceGenerationManifestStore(
                            InProcessCacheLayout.cacheRoot(),
                            InProcessCacheLayout.workspaceId(workspace),
                        )
                        .readGeneration(generation.generation.value)
                        ?.linkGeneration
                        ?.let(LinkGenerationId::of),
            )
        } catch (thrown: IndexinoException) {
            closeStoreAndReleaseGeneration(openedStore, generation.generation, thrown)
            throw thrown
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            closeStoreAndReleaseGeneration(openedStore, generation.generation, thrown)
            throw mapUnexpectedFailure(thrown)
        }
    }

    public suspend fun shutdownRuntime() {
        ensureOpen()
        runtimeConnection?.let { connection ->
            try {
                connection.request(
                    dev.sebastiano.indexino.engine.RuntimeControlProtocol.shutdownCommand()
                )
            } catch (_: IOException) {
                // A successful owner shutdown may close the socket before its empty response.
            }
        }
        close()
    }

    private fun <T> mapRemoteFailures(block: () -> T): T =
        try {
            block()
        } catch (thrown: IndexinoException) {
            throw thrown
        } catch (thrown: RuntimeProtocolException) {
            thrown.failure?.let { failure -> throw IndexinoException(failure, thrown) }
            throw mapUnexpectedFailure(thrown)
        } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
            throw mapUnexpectedFailure(thrown)
        }

    @OptIn(IndexinoInternalApi::class)
    private fun remoteRefresh(
        connection: RuntimeConnection,
        request: RefreshRequest,
    ): RefreshHandle {
        val remote = RuntimeRefreshClient(connection).refresh(request)
        val id = RefreshId.of(remote.id)
        val result = CompletableFuture.supplyAsync {
            RuntimeConnection.connect(connection.endpoint).use { observation ->
                mapRemoteFailures { RuntimeRefreshHandle(remote.id, observation).await().result }
            }
        }
        val terminal = result.thenApply<RefreshEvent> { refresh -> RefreshCompleted(id, refresh) }
        return RefreshHandle.inFlight(id, result, terminal, remote::stop)
    }

    private fun remoteSnapshot(
        connection: RuntimeConnection,
        freshness: FreshnessPolicy,
    ): IndexSnapshot {
        val client = RuntimeSnapshotClient(connection)
        val lease = client.acquire(freshness)
        val snapshot =
            IndexSnapshot.createRemote(
                client = client,
                leaseId = lease.id,
                revision = lease.revision,
                generation = lease.generation,
                freshnessAtAcquisition = lease.freshness,
                onClose = {
                    synchronized(remoteSnapshots) { remoteSnapshots.remove(lease.id) }
                    client.release(lease.id)
                },
            )
        synchronized(remoteSnapshots) { remoteSnapshots[lease.id] = snapshot }
        return snapshot
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtimeConnection?.let { connection ->
            val snapshots = synchronized(remoteSnapshots) { remoteSnapshots.values.toList() }
            snapshots.forEach { snapshot -> runCatching(snapshot::close) }
            synchronized(remoteSnapshots) { remoteSnapshots.clear() }
            connection.close()
            return
        }
        // Drop the published exemption so unpinned per-client copies can be reclaimed. Open
        // snapshots keep their pins and are only deleted when those snapshots close (see C3/S3/S6).
        synchronized(generationLock) {
            published = null
            reclaimUnpinnedGenerations()
        }
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw failure(
                category = IndexFailureCategory.CLOSED,
                code = "client_closed",
                message = "Indexino client is closed",
                retryable = false,
            )
        }
    }

    private fun IndexScope.toTopologyRequest(): TopologyRequest =
        if (buildSystem == BuildSystem.GRADLE) {
            TopologyRequest(
                buildSystem = InternalBuildSystem.GRADLE,
                gradleModule = value,
                includeDeps = includesDependencies,
            )
        } else {
            TopologyRequest(
                buildSystem = InternalBuildSystem.BAZEL,
                bazelTarget = value,
                includeDeps = includesDependencies,
            )
        }

    @OptIn(IndexinoInternalApi::class)
    private fun IndexManifest.toWorkspaceRevision(): WorkspaceRevision {
        val origins =
            origins
                .ifEmpty {
                    listOf(
                        dev.sebastiano.indexino.core.manifest.IndexManifestOrigin(
                            originId = "workspace",
                            revision = commit.takeUnless(GitHeadResolver::isFilesystemRevision),
                            stateFingerprint = sourcesContentHash,
                        )
                    )
                }
                .map { origin ->
                    SourceOriginRevision(
                        originId = SourceOriginId.of(origin.originId),
                        revision = origin.revision,
                        stateFingerprint = origin.stateFingerprint,
                        expectedRevision = origin.expectedRevision,
                    )
                }
        return WorkspaceRevision(workspaceRevisionFingerprint(), origins)
    }

    private fun IndexManifest.toGenerationId(revision: WorkspaceRevision): WorkspaceGenerationId =
        WorkspaceGenerationId.of(
            sha256(
                // BasicFactSchemaVersion joins these inputs when the S2 generation manifest lands.
                listOf(
                        revision.fingerprint,
                        BASIC_FACT_SCHEMA_VERSION.toString(),
                        indexerVersion,
                        applications.sorted().joinToString("\u0001"),
                        pluginCoordinates.toSortedMap().entries.joinToString("\u0001") {
                            (pluginId, coordinate) ->
                            "$pluginId=$coordinate"
                        },
                    )
                    .joinToString("\u0000")
            )
        )

    private fun publishGenerationStore(
        commit: String,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
        scope: IndexScope,
        applications: List<String>,
        compatibilityManifest: IndexManifest,
        forkBase: WorktreeForkBase? = null,
        overlayDeltaPath: Path? = null,
        tombstonePrefixes: List<String> = emptyList(),
        linkGeneration: String? = null,
    ): PublishedStore {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val clientStorePath =
            InProcessCacheLayout.generationStore(workspace, clientId, generation.value)
        WorkspaceRegistryStore(cacheRoot)
            .upsert(workspaceId, workspace, GitWorktreeLayout.commonDir(workspace))
        val emptyOriginFingerprint = FileHashProducer.contentHash("")
        val legacyOrigin =
            revision.origins.firstOrNull {
                it.originId.value == "workspace" && it.stateFingerprint != emptyOriginFingerprint
            }
                ?: revision.origins.firstOrNull { it.stateFingerprint != emptyOriginFingerprint }
                ?: revision.origins.first()
        if (forkBase != null) {
            return publishOverlayGenerationStore(
                cacheRoot = cacheRoot,
                workspaceId = workspaceId,
                clientStorePath = clientStorePath,
                generation = generation,
                revision = revision,
                scope = scope,
                applications = applications,
                compatibilityManifest = compatibilityManifest,
                legacyOrigin = legacyOrigin,
                forkBase = forkBase,
                overlayDeltaPath = overlayDeltaPath,
                tombstonePrefixes = tombstonePrefixes,
                linkGeneration = linkGeneration,
            )
        }

        return publishMaterializedGenerationStore(
            cacheRoot = cacheRoot,
            workspaceId = workspaceId,
            clientStorePath = clientStorePath,
            commit = commit,
            generation = generation,
            revision = revision,
            scope = scope,
            applications = applications,
            compatibilityManifest = compatibilityManifest,
            legacyOrigin = legacyOrigin,
            linkGeneration = linkGeneration,
        )
    }

    private fun publishOverlayGenerationStore(
        cacheRoot: Path,
        workspaceId: String,
        clientStorePath: Path,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
        scope: IndexScope,
        applications: List<String>,
        compatibilityManifest: IndexManifest,
        legacyOrigin: SourceOriginRevision,
        forkBase: WorktreeForkBase,
        overlayDeltaPath: Path?,
        tombstonePrefixes: List<String>,
        linkGeneration: String? = null,
    ): PublishedStore {
        val overlayPackKeys =
            overlayDeltaPath
                ?.let { deltaPath ->
                    listOf(
                        ContentAddressedPackCache(cacheRoot)
                            .installDirectory(deltaPath, BASIC_FACT_SCHEMA_VERSION)
                    )
                }
                .orEmpty()
        WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
            .publish(
                workspaceGenerationManifest(
                    generation = generation.value,
                    revision = revision,
                    scope = scope,
                    applications = applications,
                    compatibilityManifest = compatibilityManifest,
                    legacyOrigin = legacyOrigin,
                    packKeys = emptyList(),
                    representation = WorktreeOverlayPolicy.REPRESENTATION_OVERLAY,
                    baseWorkspaceId = forkBase.baseWorkspaceId,
                    baseGeneration = forkBase.baseGeneration,
                    overlayPackKeys = overlayPackKeys,
                    tombstonePrefixes = tombstonePrefixes,
                    overlayChainDepth = forkBase.overlayChainDepth,
                    linkGeneration = linkGeneration,
                )
            )
        if (overlayPackKeys.isNotEmpty()) {
            WorktreeOverlayStoreOpener.materializeOverlayDelta(
                cacheRoot,
                workspace,
                clientId,
                WorkspaceGenerationManifestStore(cacheRoot, workspaceId).current()
                    ?: error("Missing published overlay manifest"),
            )
        }
        Files.createDirectories(clientStorePath.parent)
        return PublishedStore(path = clientStorePath, created = overlayPackKeys.isNotEmpty())
    }

    private fun publishMaterializedGenerationStore(
        cacheRoot: Path,
        workspaceId: String,
        clientStorePath: Path,
        commit: String,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
        scope: IndexScope,
        applications: List<String>,
        compatibilityManifest: IndexManifest,
        legacyOrigin: SourceOriginRevision,
        linkGeneration: String? = null,
    ): PublishedStore {
        val source =
            IndexPathResolver(workspace, storeRootOverride = storeRoot).resolveBaseStore(commit)
        val packKey =
            ContentAddressedPackCache(cacheRoot).installDirectory(source, BASIC_FACT_SCHEMA_VERSION)
        WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
            .publish(
                workspaceGenerationManifest(
                    generation = generation.value,
                    revision = revision,
                    scope = scope,
                    applications = applications,
                    compatibilityManifest = compatibilityManifest,
                    legacyOrigin = legacyOrigin,
                    packKeys = listOf(packKey),
                    representation = WorktreeOverlayPolicy.REPRESENTATION_MATERIALIZED,
                    linkGeneration = linkGeneration,
                )
            )
        val sharedDestination =
            InProcessCacheLayout.sharedGenerationStore(workspace, generation.value)
        val packs = ContentAddressedPackCache(cacheRoot)
        if (!Files.isDirectory(sharedDestination)) {
            packs.materializeDirectory(packKey, sharedDestination)
        }
        val createdClientCopy = !Files.isDirectory(clientStorePath)
        if (createdClientCopy) {
            packs.materializeDirectory(packKey, clientStorePath)
        }
        return PublishedStore(path = clientStorePath, created = createdClientCopy)
    }

    private fun workspaceGenerationManifest(
        generation: String,
        revision: WorkspaceRevision,
        scope: IndexScope,
        applications: List<String>,
        compatibilityManifest: IndexManifest,
        legacyOrigin: SourceOriginRevision,
        packKeys: List<String>,
        representation: String,
        baseWorkspaceId: String? = null,
        baseGeneration: String? = null,
        overlayPackKeys: List<String> = emptyList(),
        tombstonePrefixes: List<String> = emptyList(),
        overlayChainDepth: Int = 0,
        linkGeneration: String? = null,
    ): WorkspaceGenerationManifest =
        WorkspaceGenerationManifest(
            basicFactSchemaVersion = BASIC_FACT_SCHEMA_VERSION,
            generation = generation,
            workspaceRevisionFingerprint = revision.fingerprint,
            originId = legacyOrigin.originId.value,
            revision = legacyOrigin.revision,
            stateFingerprint = legacyOrigin.stateFingerprint,
            origins =
                revision.origins.map { origin ->
                    WorkspaceGenerationOrigin(
                        originId = origin.originId.value,
                        revision = origin.revision,
                        stateFingerprint = origin.stateFingerprint,
                        expectedRevision = origin.expectedRevision,
                    )
                },
            packKeys = packKeys,
            scopeBuildSystem = scope.buildSystem.value,
            scopeValue = scope.value,
            includesDependencies = scope.includesDependencies,
            applications = applications,
            compatibilityManifest = compatibilityManifest,
            representation = representation,
            baseWorkspaceId = baseWorkspaceId,
            baseGeneration = baseGeneration,
            overlayPackKeys = overlayPackKeys,
            tombstonePrefixes = tombstonePrefixes,
            overlayChainDepth = overlayChainDepth,
            linkGeneration = linkGeneration,
        )

    private fun openPublishedStore(generation: WorkspaceGenerationId): CodeIndexStore {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val manifest =
            WorkspaceGenerationManifestStore(cacheRoot, InProcessCacheLayout.workspaceId(workspace))
                .readGeneration(generation.value)
                ?: error("Missing generation manifest ${generation.value}")
        return WorktreeOverlayStoreOpener.openForQuery(cacheRoot, workspace, clientId, manifest)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun currentPublishedGenerationId(): WorkspaceGenerationId? {
        val manifest =
            WorkspaceGenerationManifestStore(
                    InProcessCacheLayout.cacheRoot(),
                    InProcessCacheLayout.workspaceId(workspace),
                )
                .current() ?: return null
        return manifest
            .takeIf { it.basicFactSchemaVersion == BASIC_FACT_SCHEMA_VERSION }
            ?.generation
            ?.let(WorkspaceGenerationId::of)
    }

    @OptIn(IndexinoInternalApi::class)
    private fun restorePublishedGeneration(): PublishedGeneration? {
        val cacheRoot = InProcessCacheLayout.cacheRoot()
        val manifest =
            WorkspaceGenerationManifestStore(cacheRoot, InProcessCacheLayout.workspaceId(workspace))
                .current() ?: return null
        if (manifest.basicFactSchemaVersion != BASIC_FACT_SCHEMA_VERSION) return null
        val generation = WorkspaceGenerationId.of(manifest.generation)
        val storePath =
            if (manifest.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                InProcessCacheLayout.overlayDeltaStore(workspace, clientId, generation.value)
            } else {
                InProcessCacheLayout.generationStore(workspace, clientId, generation.value)
            }
        if (
            manifest.representation != WorktreeOverlayPolicy.REPRESENTATION_OVERLAY &&
                !Files.isDirectory(storePath)
        ) {
            ContentAddressedPackCache(cacheRoot)
                .materializeDirectory(manifest.packKeys.single(), storePath)
            if (
                !Files.isDirectory(
                    InProcessCacheLayout.sharedGenerationStore(workspace, generation.value)
                )
            ) {
                ContentAddressedPackCache(cacheRoot)
                    .materializeDirectory(
                        manifest.packKeys.single(),
                        InProcessCacheLayout.sharedGenerationStore(workspace, generation.value),
                    )
            }
        } else if (
            manifest.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY &&
                manifest.overlayPackKeys.isNotEmpty() &&
                !Files.isDirectory(storePath)
        ) {
            WorktreeOverlayStoreOpener.materializeOverlayDelta(
                cacheRoot,
                workspace,
                clientId,
                manifest,
            )
        }
        val revision =
            WorkspaceRevision(
                manifest.workspaceRevisionFingerprint,
                manifest.origins.map { origin ->
                    SourceOriginRevision(
                        originId = SourceOriginId.of(origin.originId),
                        revision = origin.revision,
                        stateFingerprint = origin.stateFingerprint,
                        expectedRevision = origin.expectedRevision,
                    )
                },
            )
        val restored = PublishedGeneration(storePath, revision, generation)
        generationStores[generation] = storePath
        published = restored
        return restored
    }

    private fun closeStoreAndReleaseGeneration(
        store: CodeIndexStore,
        generation: WorkspaceGenerationId,
        failure: Throwable,
    ) {
        try {
            store.close()
        } catch (@Suppress("TooGenericExceptionCaught") closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        } finally {
            releaseGeneration(generation)
        }
    }

    private fun releaseGeneration(generation: WorkspaceGenerationId) {
        synchronized(generationLock) {
            val remaining = snapshotPins.getOrDefault(generation, 0) - 1
            if (remaining > 0) {
                snapshotPins[generation] = remaining
            } else {
                snapshotPins.remove(generation)
            }
            reclaimUnpinnedGenerations()
        }
    }

    private fun reclaimUnpinnedGenerations() {
        val current = published?.generation
        val reclaimable = generationStores.filterKeys { generation ->
            generation != current && snapshotPins.getOrDefault(generation, 0) == 0
        }
        reclaimable.forEach { (generation, path) ->
            if (deleteClientGenerationRef(path)) {
                generationStores.remove(generation)
            }
        }
    }

    private fun deleteClientGenerationRef(path: Path): Boolean =
        path.parent.toFile().deleteRecursively()

    private fun buildFailure(execution: IndexBuildExecution, message: String): IndexinoException =
        failure(
            category =
                if (execution.exitCode == CliExitCodes.TOPOLOGY_FAILED) {
                    IndexFailureCategory.TOPOLOGY
                } else {
                    IndexFailureCategory.INTERNAL
                },
            code = "refresh_failed_${execution.exitCode}",
            message = message,
            retryable = true,
        )

    private fun failure(
        category: IndexFailureCategory,
        code: String,
        message: String,
        retryable: Boolean,
    ): IndexinoException = indexinoFailure(category, code, message, retryable)

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private class PublishedStore(val path: Path, val created: Boolean)

    private class PublishedGeneration(
        val storePath: Path,
        val revision: WorkspaceRevision,
        val generation: WorkspaceGenerationId,
    )
}

private fun requireScopeMatchesManifest(
    scope: IndexScope,
    observedIncludeDeps: Boolean,
    observedTopology: String,
    observedOverride: Boolean?,
) {
    val observed = observedOverride ?: observedIncludeDeps
    if (observed != scope.includesDependencies) {
        // S1 has no RefreshWarningEvent channel yet (S3). Fail rather than publish an index
        // whose closure does not match the requested scope; revisit as a warning in S3.
        val causeHint =
            when {
                scope.buildSystem != BuildSystem.BAZEL || observed -> ""
                observedTopology == "build-parse" ->
                    "; Bazel was not found on PATH — install Bazel or index this workspace " +
                        "through the CLI (degraded target-only topology is not published " +
                        "through the facade)"
                else ->
                    "; the Bazel dependency query failed for this target — check the target " +
                        "and its BUILD file (degraded target-only topology is not published " +
                        "through the facade)"
            }
        throw indexinoFailure(
            category = IndexFailureCategory.TOPOLOGY,
            code = "scope_include_deps_mismatch",
            message =
                "Resolved includeDeps=$observed but scope requested " +
                    "includesDependencies=${scope.includesDependencies}$causeHint",
            retryable = true,
        )
    }
}

private fun mapRefreshFailure(thrown: Throwable): IndexinoException = mapUnexpectedFailure(thrown)

private fun mapUnexpectedFailure(thrown: Throwable): IndexinoException {
    // Unexpected throwables map to INTERNAL with cause retained. Do not classify by exception
    // type/message. Specific codes are only for known failure modes (e.g. connect path IO).
    if (thrown is IndexinoException) {
        return thrown
    }
    val message = thrown.message?.takeIf { it.isNotBlank() } ?: thrown.javaClass.simpleName
    return indexinoFailure(
        category = IndexFailureCategory.INTERNAL,
        code = "internal",
        message = message,
        retryable = false,
        cause = thrown,
    )
}
