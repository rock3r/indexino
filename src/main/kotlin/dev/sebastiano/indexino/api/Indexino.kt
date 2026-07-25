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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

public class Indexino private constructor(private val workspace: Path) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val storeRoot = InProcessCacheLayout.storeRoot(workspace)
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
        val generation = WorkspaceGenerationId.of(revision.fingerprint)
        published =
            PublishedGeneration(
                commit = manifest.commit,
                revision = revision,
                generation = generation,
            )
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
            published
                ?: throw failure(
                    category = IndexFailureCategory.INDEX_NOT_FOUND,
                    code = "index_not_found",
                    message = "No published index exists; call refresh first",
                    retryable = true,
                )
        val resolver = IndexPathResolver(workspace, storeRootOverride = storeRoot)
        return IndexSnapshot.create(
            store = IndexStoreOpener.openForQuery(resolver, generation.commit),
            revision = generation.revision,
            generation = generation.generation,
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
        val commit: String,
        val revision: WorkspaceRevision,
        val generation: WorkspaceGenerationId,
    )
}
