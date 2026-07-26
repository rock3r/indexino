@file:Suppress("RedundantSuspendModifier")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.cli.CliExitCodes
import dev.sebastiano.indexino.cli.IndexBuildExecution
import dev.sebastiano.indexino.cli.IndexBuildRunner
import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.path.IndexPathResolver
import dev.sebastiano.indexino.core.store.IndexStoreOpener
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.topology.BuildSystem as InternalBuildSystem
import dev.sebastiano.indexino.topology.TopologyRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

public class Indexino private constructor(private val workspace: Path) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val clientId = UUID.randomUUID().toString()
    private val storeRoot = InProcessCacheLayout.storeRoot(workspace)
    private val generationLock = Any()
    private val generationStores = mutableMapOf<WorkspaceGenerationId, Path>()
    private val snapshotPins = mutableMapOf<WorkspaceGenerationId, Int>()
    private var published: PublishedGeneration? = null

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

    public companion object {
        private val workspaceRefreshLocks = ConcurrentHashMap<Path, Any>()

        private fun refreshLockFor(workspace: Path): Any =
            workspaceRefreshLocks.computeIfAbsent(workspace) { Any() }

        /**
         * Test-only seam: replaces [Path.toRealPath] during [connectBlocking]. Production leaves
         * this null.
         */
        @Volatile internal var canonicalWorkspacePathForTests: ((Path) -> Path)? = null

        @JvmStatic
        public suspend fun connect(workspace: Path): Indexino = connectBlocking(workspace)

        @JvmStatic
        public fun connectBlocking(workspace: Path): Indexino {
            if (!Files.isDirectory(workspace)) {
                throw indexinoFailure(
                    category = IndexFailureCategory.INVALID_REQUEST,
                    code = "invalid_workspace",
                    message = "Workspace must be an existing directory",
                    retryable = false,
                )
            }
            val canonical =
                try {
                    canonicalWorkspacePathForTests?.invoke(workspace) ?: workspace.toRealPath()
                } catch (thrown: IOException) {
                    // Connect-time path resolution failures are IO (retryable). WORKSPACE_LOST is
                    // reserved for an already-bound workspace disappearing after connect.
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
            return Indexino(canonical)
        }
    }

    @OptIn(IndexinoInternalApi::class)
    public suspend fun refresh(request: RefreshRequest): RefreshHandle {
        ensureOpen()
        requireSupportedScope(request.scope)
        val refreshId = RefreshId.of(UUID.randomUUID().toString())
        val applications =
            request.plugins.map { plugin ->
                when (plugin.value) {
                    "dev.sebastiano.selection-context" -> "selection-context"
                    else ->
                        throw failure(
                            category = IndexFailureCategory.INVALID_REQUEST,
                            code = "unknown_plugin",
                            message = "Plugin ${plugin.value} is not loaded",
                            retryable = false,
                        )
                }
            }
        // Serialize in-process peers across run+copy so a shared commit writer cannot mutate while
        // another client copies it. Cross-process single-writer election remains S6.
        synchronized(refreshLockFor(workspace)) {
            try {
                val execution =
                    IndexBuildRunner(
                            project = workspace,
                            topologyRequest = request.scope.toTopologyRequest(),
                            applications = applications,
                            bazelQueryExecutor = null,
                            bazelProcessRunner = null,
                            progress = {},
                            machineProgress = null,
                            storeRootOverride = storeRoot,
                        )
                        .runDetailed()
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
                publishGenerationOrAbortIfClosed(manifest.commit, generation, revision)
                val changedFileCount = execution.changes?.changedFiles?.size ?: 0
                val removedFileCount = execution.changes?.deletedFiles?.size ?: 0
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
                return RefreshHandle(refreshId, result)
            } catch (thrown: IndexinoException) {
                throw thrown
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                // Contract: unexpected throwables become IndexinoException INTERNAL/TOPOLOGY with
                // the original retained as cause — never leak internal exception types.
                throw mapRefreshFailure(thrown)
            }
        }
    }

    private fun requireSupportedScope(scope: IndexScope) {
        if (scope.buildSystem == BuildSystem.BAZEL && !scope.includesDependencies) {
            // BazelTopology currently always expands deps(); honouring includeDeps=false would
            // silently flip CLI defaults. Until that topology work lands, require an explicit
            // includingDependencies() so the facade does not pretend target-only scopes work.
            throw failure(
                category = IndexFailureCategory.INVALID_REQUEST,
                code = "bazel_dependencies_required",
                message =
                    "Bazel scopes always include dependencies in S1; call " +
                        "IndexScope.bazel(target).includingDependencies()",
                retryable = false,
            )
        }
    }

    private fun publishGenerationOrAbortIfClosed(
        commit: String,
        generation: WorkspaceGenerationId,
        revision: WorkspaceRevision,
    ) {
        // Optional optimisation only — the under-lock check below remains load-bearing.
        if (closed.get()) {
            throw failure(
                category = IndexFailureCategory.CLOSED,
                code = "client_closed",
                message = "Indexino client is closed",
                retryable = false,
            )
        }
        val publishedStore = publishGenerationStore(commit, generation)
        afterPublishGenerationStoreForTests?.invoke()
        synchronized(generationLock) {
            // Mirror snapshot(): close may have raced during the long index+copy while
            // generationLock was free. Never publish onto a closed client (CAS close will not
            // run again to reclaim this copy).
            if (closed.get()) {
                // Undo exactly what this publish did: delete only a directory we created.
                if (publishedStore.created) {
                    publishedStore.path.parent.toFile().deleteRecursively()
                }
                throw failure(
                    category = IndexFailureCategory.CLOSED,
                    code = "client_closed",
                    message = "Indexino client is closed",
                    retryable = false,
                )
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

    public suspend fun snapshot(): IndexSnapshot {
        ensureOpen()
        val generation =
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
                val current =
                    published
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
        val openedStore =
            try {
                IndexStoreOpener.openForQuery(generation.storePath)
            } catch (thrown: IndexinoException) {
                releaseGeneration(generation.generation)
                throw thrown
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                // Release the pin before mapping/throwing so a failed open cannot leak disk.
                releaseGeneration(generation.generation)
                throw mapUnexpectedFailure(thrown)
            }
        return IndexSnapshot.create(
            store = openedStore,
            revision = generation.revision,
            generation = generation.generation,
            onClose = { releaseGeneration(generation.generation) },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
        val fingerprint =
            sha256(
                listOf(commit, scope, topology, includeDeps.toString(), sourcesContentHash)
                    .joinToString(separator = "\u0000")
            )
        val origin =
            SourceOriginRevision(
                originId = SourceOriginId.of("workspace"),
                revision = commit,
                stateFingerprint = sourcesContentHash,
                expectedRevision = null,
            )
        return WorkspaceRevision(fingerprint, listOf(origin))
    }

    private fun IndexManifest.toGenerationId(revision: WorkspaceRevision): WorkspaceGenerationId =
        WorkspaceGenerationId.of(
            sha256(
                // BasicFactSchemaVersion joins these inputs when the S2 generation manifest lands.
                listOf(
                        revision.fingerprint,
                        indexerVersion,
                        applications.sorted().joinToString("\u0001"),
                    )
                    .joinToString("\u0000")
            )
        )

    private fun publishGenerationStore(
        commit: String,
        generation: WorkspaceGenerationId,
    ): PublishedStore {
        val destination =
            InProcessCacheLayout.generationStore(workspace, clientId, generation.value)
        if (Files.isDirectory(destination)) {
            return PublishedStore(path = destination, created = false)
        }

        val source =
            IndexPathResolver(workspace, storeRootOverride = storeRoot).resolveBaseStore(commit)
        Files.createDirectories(destination.parent)
        val staging = destination.resolveSibling("${destination.fileName}.tmp-${UUID.randomUUID()}")
        try {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val target = staging.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (Files.exists(staging)) {
                staging.toFile().deleteRecursively()
            }
        }
        return PublishedStore(path = destination, created = true)
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
            path.parent.toFile().deleteRecursively()
            generationStores.remove(generation)
        }
    }

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
