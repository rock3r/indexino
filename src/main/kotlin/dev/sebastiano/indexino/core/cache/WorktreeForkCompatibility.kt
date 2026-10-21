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
    val previousOverlayPackKey: String? = null,
    val previousTombstones: List<String> = emptyList(),
)

internal object WorktreeForkCompatibility {
    @Suppress("ReturnCount")
    fun findCurrentWorkspaceBase(
        project: Path,
        cacheRoot: Path,
        criteria: ManifestFreshnessCriteria,
    ): WorktreeForkBase? {
        val workspaceId = dev.sebastiano.indexino.api.InProcessCacheLayout.workspaceId(project)
        val current =
            WorkspaceGenerationManifestStore(cacheRoot, workspaceId).current() ?: return null
        val compatibility = current.compatibilityManifest ?: return null
        if (!isForkCompatibleBase(compatibility, criteria)) return null
        if (current.basicFactSchemaVersion != criteria.basicFactSchemaVersion) return null
        val baseId =
            if (current.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                current.baseWorkspaceId ?: return null
            } else workspaceId
        val baseGeneration =
            if (current.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                current.baseGeneration ?: return null
            } else current.generation
        val basePath =
            if (baseId == workspaceId) project
            else WorkspaceRegistryStore(cacheRoot).entry(baseId)?.path?.let(Path::of) ?: return null
        if (current.overlayPackKeys.size > 1) return null
        return WorktreeForkBase(
            baseWorkspaceId = baseId,
            baseGeneration = baseGeneration,
            baseWorkspacePath = basePath,
            baseManifest = compatibility,
            unchanged = false,
            overlayChainDepth =
                if (current.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
                    current.overlayChainDepth
                } else 1,
            previousOverlayPackKey = current.overlayPackKeys.singleOrNull(),
            previousTombstones = current.tombstonePrefixes,
        )
    }

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
