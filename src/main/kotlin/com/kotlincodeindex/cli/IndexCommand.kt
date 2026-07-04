package com.kotlincodeindex.cli

import com.kotlincodeindex.core.git.GitHeadResolver
import com.kotlincodeindex.core.manifest.IndexManifest
import com.kotlincodeindex.core.manifest.ManifestFreshness
import com.kotlincodeindex.core.manifest.ManifestIO
import com.kotlincodeindex.core.path.IndexPathResolver
import com.kotlincodeindex.core.xodus.XodusCodeIndexStore
import com.kotlincodeindex.producer.FileHashProducer
import com.kotlincodeindex.producer.IndexBuildContext
import com.kotlincodeindex.producer.ProducerRegistry
import com.kotlincodeindex.topology.bazel.BazelProcessRunner
import com.kotlincodeindex.topology.bazel.BazelQueryExecutor
import com.kotlincodeindex.topology.bazel.BazelTopology
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.file
import com.kotlincodeindex.core.Version
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

class IndexCommand : CliktCommand(name = "index") {
    private val project by option("--project")
        .file(mustExist = true, mustBeReadable = true)
        .required()
    private val bazelTarget by option("--bazel-target").required()
    private val applications by option("--applications")
        .split(",")
        .default(emptyList())

    override fun run() {
        val exitCode = runIndexedBuild(
            project = project.toPath(),
            bazelTarget = bazelTarget,
            applications = applications.filter { it.isNotBlank() },
            progress = { echo(it, err = true) },
        )
        if (exitCode != CliExitCodes.SUCCESS) {
            throw RuntimeException("index failed with exit code $exitCode")
        }
    }

    fun runIndexedBuild(
        project: Path,
        bazelTarget: String,
        applications: List<String>,
        queryExecutor: BazelQueryExecutor? = null,
        processRunner: BazelProcessRunner? = null,
        progress: (String) -> Unit = {},
    ): Int {
        val topologyResult = BazelTopology.resolveSources(
            bazelTarget,
            project,
            queryExecutor,
            processRunner,
            onStderr = progress,
        )
        if (topologyResult.sourceFiles.isEmpty()) {
            progress("topology discovery failed: no source files")
            return CliExitCodes.TOPOLOGY_FAILED
        }

        val sourceFiles = topologyResult.sourceFiles
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val manifestPath = resolver.resolveManifest(commit)

        val previewHash = FileHashProducer.combinedSourcesHash(project, sourceFiles)
        val criteria = ManifestFreshness.criteriaFrom(
            commit = commit,
            scope = bazelTarget,
            sourcesContentHash = previewHash,
            applications = applications,
        )

        if (manifestPath.exists()) {
            val existing = ManifestIO.read(manifestPath)
            if (ManifestFreshness.isFresh(existing, criteria)) {
                progress("index fresh for $bazelTarget @ $commit — skip rebuild")
                return CliExitCodes.SUCCESS
            }
        }

        val storePath = resolver.resolveBaseStore(commit)
        val store = XodusCodeIndexStore.open(storePath)
        try {
            val context = IndexBuildContext(
                store = store,
                commitHash = commit,
                scope = bazelTarget,
                sourceFiles = sourceFiles,
                workspaceRoot = project,
                progress = progress,
            )
            for (producer in ProducerRegistry.forApplications(applications)) {
                progress(producer.displayName)
                producer.produce(context, store)
            }

            ManifestIO.write(
                manifestPath,
                IndexManifest(
                    commit = commit,
                    indexerVersion = Version.NAME,
                    scope = bazelTarget,
                    topology = topologyResult.topology,
                    includeDeps = topologyResult.includeDeps,
                    sourceFileCount = sourceFiles.size,
                    sourcesContentHash = previewHash,
                    builtAt = Instant.now().toString(),
                    applications = applications,
                ),
            )
        } finally {
            store.close()
        }
        return CliExitCodes.SUCCESS
    }
}
