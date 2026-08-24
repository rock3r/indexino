package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.core.manifest.IndexManifest
import dev.sebastiano.indexino.core.manifest.ManifestFreshnessCriteria
import java.nio.file.Files
import java.nio.file.Path

internal data class WorktreeForkBase(
    val baseWorkspaceId: String,
    val baseGeneration: String,
    val baseWorkspacePath: Path,
    val baseManifest: IndexManifest,
    val unchanged: Boolean,
    val overlayChainDepth: Int,
)

internal object WorktreeForkCompatibility {
    fun findCompatibleBase(
        project: Path,
        cacheRoot: Path,
        criteria: ManifestFreshnessCriteria,
    ): WorktreeForkBase? {
        val gitCommonDir = GitWorktreeLayout.commonDir(project) ?: return null
        val registry = WorkspaceRegistryStore(cacheRoot)
        val projectPath =
            runCatching { project.toRealPath().toString() }
                .getOrElse { project.toAbsolutePath().normalize().toString() }
        val candidates =
            registry.entries().filter { entry ->
                entry.gitCommonDir == gitCommonDir && entry.path != projectPath
            }
        val deepest =
            candidates
                .mapNotNull { candidate -> resolveForkCandidate(candidate, cacheRoot, criteria) }
                .maxWithOrNull(
                    compareBy<ForkCandidate> { it.overlayDepth }.thenBy { it.entry.path }
                ) ?: return null
        if (
            deepest.published.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY &&
                deepest.published.overlayChainDepth >= WorktreeOverlayPolicy.MAX_CHAIN_DEPTH
        ) {
            return null
        }
        return deepest.toForkBase(criteria)
    }

    private data class ForkCandidate(
        val entry: WorkspaceRegistryEntry,
        val published: WorkspaceGenerationManifest,
        val basePath: Path,
    ) {
        val overlayDepth: Int =
            if (published.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                published.overlayChainDepth
            } else {
                0
            }

        fun toForkBase(criteria: ManifestFreshnessCriteria): WorktreeForkBase {
            val compatibility = checkNotNull(published.compatibilityManifest)
            return WorktreeForkBase(
                baseWorkspaceId = entry.workspaceId,
                baseGeneration = published.generation,
                baseWorkspacePath = basePath,
                baseManifest = compatibility,
                unchanged = compatibility.sourcesContentHash == criteria.sourcesContentHash,
                overlayChainDepth =
                    if (published.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                        published.overlayChainDepth + 1
                    } else {
                        1
                    },
            )
        }
    }

    private fun resolveForkCandidate(
        candidate: WorkspaceRegistryEntry,
        cacheRoot: Path,
        criteria: ManifestFreshnessCriteria,
    ): ForkCandidate? {
        val basePath = Path.of(candidate.path)
        if (!Files.isDirectory(basePath)) return null
        val published =
            WorkspaceGenerationManifestStore(cacheRoot, candidate.workspaceId).current()
                ?: return null
        val compatibility = published.compatibilityManifest ?: return null
        if (!isForkCompatibleBase(compatibility, criteria)) return null
        if (published.basicFactSchemaVersion != criteria.basicFactSchemaVersion) return null
        return ForkCandidate(candidate, published, basePath)
    }

    private fun isForkCompatibleBase(
        compatibility: IndexManifest,
        criteria: ManifestFreshnessCriteria,
    ): Boolean =
        compatibility.commit == criteria.commit &&
            compatibility.indexerVersion == criteria.indexerVersion &&
            compatibility.basicFactSchemaVersion == criteria.basicFactSchemaVersion &&
            compatibility.scope == criteria.scope &&
            compatibility.includeDeps == criteria.includeDeps &&
            compatibility.applications.sorted() == criteria.applications.sorted() &&
            compatibility.pluginCoordinates == criteria.pluginCoordinates
}

internal object GitWorktreeLayout {
    fun commonDir(project: Path): String? {
        if (!Files.exists(project.resolve(".git"))) return null
        val process =
            try {
                ProcessBuilder("git", "-C", project.toString(), "rev-parse", "--git-common-dir")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: java.io.IOException) {
                return null
            }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0 || output.isBlank()) return null
        val resolved =
            Path.of(output).let { parsed ->
                if (parsed.isAbsolute) parsed else project.resolve(parsed)
            }
        return runCatching { resolved.toRealPath().toString() }
            .getOrElse { resolved.toAbsolutePath().normalize().toString() }
    }
}
