package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.core.git.GitHeadResolver
import dev.sebastiano.indexino.core.manifest.IndexManifestOrigin
import dev.sebastiano.indexino.producer.FileHashProducer
import dev.sebastiano.indexino.producer.IndexedSource
import java.nio.file.Path
import kotlin.io.path.readText

/** Builds current origin provenance for manifest freshness checks and publication. */
internal object ManifestOriginResolver {
    fun resolve(
        workspace: Path,
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
                            },
                    )
                IndexManifestOrigin(
                    originId = originId,
                    revision =
                        GitHeadResolver.resolve(originRoot)
                            .takeUnless(GitHeadResolver::isFilesystemRevision),
                    stateFingerprint = stateFingerprint,
                    expectedRevision =
                        externalOriginMetadata[originRoot.toRealPath()]?.second
                            ?: expectedSubmoduleRevision(workspace, originRoot),
                    dirty = isGitDirty(originRoot),
                )
            }
            .sortedBy { it.originId }

    private fun isGitDirty(originRoot: Path): Boolean {
        val process =
            runCatching {
                    ProcessBuilder(
                            "git",
                            "-C",
                            originRoot.toString(),
                            "status",
                            "--porcelain",
                            "--",
                            ".",
                        )
                        .redirectErrorStream(true)
                        .start()
                }
                .getOrNull() ?: return false
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() == 0 &&
            output.lineSequence().filter(String::isNotBlank).any { status ->
                val path = status.drop(PORCELAIN_STATUS_PREFIX_LENGTH).trim()
                path.split('/').none { segment -> segment == TRANSITIONAL_INDEX_DIRECTORY }
            }
    }

    private fun expectedSubmoduleRevision(workspace: Path, originRoot: Path): String? {
        val canonicalWorkspace = workspace.toRealPath()
        if (originRoot == canonicalWorkspace || !originRoot.startsWith(canonicalWorkspace)) return null
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

    private const val PORCELAIN_STATUS_PREFIX_LENGTH = 3
    private const val TRANSITIONAL_INDEX_DIRECTORY = ".indexino"
}
