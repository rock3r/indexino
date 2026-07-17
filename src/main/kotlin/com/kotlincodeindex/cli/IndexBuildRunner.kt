package com.kotlincodeindex.cli

import com.kotlincodeindex.core.Version
import com.kotlincodeindex.core.git.GitHeadResolver
import com.kotlincodeindex.core.manifest.IndexManifest
import com.kotlincodeindex.core.manifest.ManifestFreshness
import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import com.kotlincodeindex.producer.FileHashProducer
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.IndexBuildProgressReporter
import com.kotlincodeindex.producer.ProducerRegistry
import com.kotlincodeindex.producer.SOURCE_CHANGE_DETECTION_PHASE
import com.kotlincodeindex.producer.SourceChangeDetector
import com.kotlincodeindex.producer.SourceChangeSet
import com.kotlincodeindex.topology.TopologyRequest
import com.kotlincodeindex.topology.TopologyResolver
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
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
) {
    fun run(): Int {
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
        machineProgress?.discoveryCompleted(sourceFiles.size)
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)
        val previewHash = previewHash(sourceFiles)
        val criteria =
            ManifestFreshness.criteriaFrom(
                commit = commit,
                scope = topologyResult.scope,
                sourcesContentHash = previewHash,
                applications = applications,
            )
        val existingManifest = manifestPath.takeIf { it.exists() }?.let(ManifestIO::read)
        if (existingManifest != null && ManifestFreshness.isFresh(existingManifest, criteria)) {
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
        forceFullRebuild: Boolean,
    ) {
        val store = XodusCodeIndexStore.open(resolver.resolveBaseStore(commit))
        try {
            val changes = detectChanges(store, sourceFiles, forceFullRebuild)
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
            ManifestIO.write(
                resolver.resolveManifest(commit),
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
                ),
            )
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
