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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

public class Indexino private constructor(private val workspace: Path) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val clientId = UUID.randomUUID().toString()
    private val storeRoot = InProcessCacheLayout.storeRoot(workspace)
    private val generationLock = Any()
    private val generationStores = mutableMapOf<WorkspaceGenerationId, Path>()
    private val snapshotPins = mutableMapOf<WorkspaceGenerationId, Int>()
    private var published: PublishedGeneration? = null

    public companion object {
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
            return Indexino(workspace.toRealPath())
        }
    }

    @OptIn(IndexinoInternalApi::class)
    public suspend fun refresh(request: RefreshRequest): RefreshHandle {
        ensureOpen()
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
        val revision = manifest.toWorkspaceRevision()
        val generation = manifest.toGenerationId(revision)
        val generationStore = publishGenerationStore(manifest.commit, generation)
        synchronized(generationLock) {
            generationStores[generation] = generationStore
            published =
                PublishedGeneration(
                    storePath = generationStore,
                    revision = revision,
                    generation = generation,
                )
            reclaimUnpinnedGenerations()
        }
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
    }

    public suspend fun snapshot(): IndexSnapshot {
        ensureOpen()
        val generation =
            synchronized(generationLock) {
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
        val openedStore = runCatching { IndexStoreOpener.openForQuery(generation.storePath) }
        if (openedStore.isFailure) {
            releaseGeneration(generation.generation)
        }
        val store = openedStore.getOrThrow()
        return IndexSnapshot.create(
            store = store,
            revision = generation.revision,
            generation = generation.generation,
            onClose = { releaseGeneration(generation.generation) },
        )
    }

    override fun close() {
        closed.set(true)
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

    private fun publishGenerationStore(commit: String, generation: WorkspaceGenerationId): Path {
        val destination =
            InProcessCacheLayout.generationStore(workspace, clientId, generation.value)
        if (Files.isDirectory(destination)) return destination

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
        return destination
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

    private class PublishedGeneration(
        val storePath: Path,
        val revision: WorkspaceRevision,
        val generation: WorkspaceGenerationId,
    )
}
