package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import java.nio.file.Files
import java.nio.file.Path

internal object WorktreeOverlayStoreOpener {
    fun openForQuery(
        cacheRoot: Path,
        workspace: Path,
        clientId: String,
        manifest: WorkspaceGenerationManifest,
    ): CodeIndexStore = openResolvedStore(cacheRoot, workspace, manifest, clientId)

    fun openForBuildBase(
        cacheRoot: Path,
        workspace: Path,
        manifest: WorkspaceGenerationManifest,
    ): CodeIndexStore = openResolvedStore(cacheRoot, workspace, manifest, clientId = null)

    private fun openResolvedStore(
        cacheRoot: Path,
        workspace: Path,
        manifest: WorkspaceGenerationManifest,
        clientId: String?,
    ): CodeIndexStore {
        if (manifest.representation != WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
            val storePath =
                InProcessCacheLayout.sharedGenerationStore(workspace, manifest.generation)
            return XodusCodeIndexStore.open(storePath, readOnly = true)
        }
        require(manifest.baseWorkspaceId != null && manifest.baseGeneration != null) {
            "Overlay manifest missing base generation reference"
        }
        val baseManifest =
            WorkspaceGenerationManifestStore(cacheRoot, manifest.baseWorkspaceId)
                .readGeneration(manifest.baseGeneration)
                ?: error("Base generation ${manifest.baseGeneration} is unavailable")
        val baseWorkspace =
            WorkspaceRegistryStore(cacheRoot).entry(manifest.baseWorkspaceId)?.path?.let(Path::of)
                ?: error("Base workspace ${manifest.baseWorkspaceId} is unknown")
        val baseStore = openResolvedStore(cacheRoot, baseWorkspace, baseManifest, clientId)
        val overlayStore = openOverlayDeltaStore(cacheRoot, workspace, manifest, clientId)
        return WorktreeOverlayIndexStore(baseStore, overlayStore, manifest.tombstonePrefixes)
    }

    fun materializedGenerationStore(
        cacheRoot: Path,
        workspace: Path,
        manifest: WorkspaceGenerationManifest,
    ): Path {
        val storePath = InProcessCacheLayout.sharedGenerationStore(workspace, manifest.generation)
        if (Files.isDirectory(storePath)) return storePath
        if (manifest.representation == WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
            error("Overlay generation ${manifest.generation} has no materialized store")
        }
        val packs = ContentAddressedPackCache(cacheRoot)
        manifest.packKeys.forEach { packKey -> packs.materializeDirectory(packKey, storePath) }
        return storePath
    }

    fun overlayDeltaStorePath(workspace: Path, clientId: String, generation: String): Path =
        InProcessCacheLayout.overlayDeltaStore(workspace, clientId, generation)

    fun materializeOverlayDelta(
        cacheRoot: Path,
        workspace: Path,
        clientId: String,
        manifest: WorkspaceGenerationManifest,
    ): Path? {
        val overlayKey = manifest.overlayPackKeys.singleOrNull() ?: return null
        val destination = overlayDeltaStorePath(workspace, clientId, manifest.generation)
        materializeOverlayPack(cacheRoot, overlayKey, destination)
        val sharedDestination =
            InProcessCacheLayout.sharedOverlayDeltaStore(workspace, manifest.generation)
        if (!Files.isDirectory(sharedDestination)) {
            materializeOverlayPack(cacheRoot, overlayKey, sharedDestination)
        }
        return destination
    }

    private fun openOverlayDeltaStore(
        cacheRoot: Path,
        workspace: Path,
        manifest: WorkspaceGenerationManifest,
        clientId: String?,
    ): CodeIndexStore? {
        if (manifest.overlayPackKeys.isEmpty()) return null
        val sharedPath =
            InProcessCacheLayout.sharedOverlayDeltaStore(workspace, manifest.generation)
        if (!Files.isDirectory(sharedPath)) {
            materializeOverlayPack(cacheRoot, manifest.overlayPackKeys.single(), sharedPath)
        }
        if (clientId != null) {
            val clientPath = overlayDeltaStorePath(workspace, clientId, manifest.generation)
            if (!Files.isDirectory(clientPath)) {
                materializeOverlayPack(cacheRoot, manifest.overlayPackKeys.single(), clientPath)
            }
        }
        return XodusCodeIndexStore.open(sharedPath, readOnly = true)
    }

    private fun materializeOverlayPack(cacheRoot: Path, overlayKey: String, destination: Path) {
        if (Files.isDirectory(destination)) return
        ContentAddressedPackCache(cacheRoot).materializeDirectory(overlayKey, destination)
    }
}
