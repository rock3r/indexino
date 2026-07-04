package com.kotlincodeindex.cli

import com.kotlincodeindex.core.git.GitHeadResolver
import com.kotlincodeindex.core.manifest.IndexManifest
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
        if (exitCode != 0) {
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
        val sourceFiles = topologyResult.sourceFiles
        val commit = GitHeadResolver.resolve(project)
        val resolver = IndexPathResolver(project)
        val storePath = resolver.resolveBaseStore(commit)
        val store = XodusCodeIndexStore.open(storePath)
        try {
            val context = IndexBuildContext(
                store = store,
                commitHash = commit,
                scope = bazelTarget,
                sourceFiles = sourceFiles,
                workspaceRoot = project,
            )
            for (producer in ProducerRegistry.forApplications(applications)) {
                progress(producer.displayName)
                producer.produce(context, store)
            }

            val contentHash = combinedContentHash(context, sourceFiles)
            ManifestIO.write(
                resolver.resolveManifest(commit),
                IndexManifest(
                    commit = commit,
                    indexerVersion = Version.NAME,
                    scope = bazelTarget,
                    topology = topologyResult.topology,
                    includeDeps = topologyResult.includeDeps,
                    sourceFileCount = sourceFiles.size,
                    sourcesContentHash = contentHash,
                    builtAt = Instant.now().toString(),
                    applications = applications,
                ),
            )
        } finally {
            store.close()
        }
        return 0
    }

    private fun combinedContentHash(context: IndexBuildContext, sourceFiles: List<String>): String {
        val combined = sourceFiles.sorted().joinToString("\n") { path ->
            "$path:${FileHashProducer.contentHash(context.readSource(path))}"
        }
        return FileHashProducer.contentHash(combined)
    }
}
