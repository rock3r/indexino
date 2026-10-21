package dev.sebastiano.indexino.core.cache

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.SharedReadOnlyStore
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import java.nio.file.Files
import java.nio.file.Path

internal object WorktreeOverlayStoreOpener {
    fun openForQuery(
        cacheRoot: Path,
        workspace: Path,
        clientId: String,
        manifest: WorkspaceGenerationManifest,
        onBaseRef: (Path) -> Unit = {},
    ): CodeIndexStore = openResolvedStore(cacheRoot, workspace, manifest, clientId, onBaseRef)

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
        onBaseRef: (Path) -> Unit = {},
        isBase: Boolean = false,
    ): CodeIndexStore {
        if (manifest.representation != WorktreeOverlayPolicy.REPRESENTATION_OVERLAY) {
            val storePath =
                if (clientId == null) {
                    InProcessCacheLayout.sharedGenerationStore(workspace, manifest.generation)
                } else {
                    InProcessCacheLayout.generationStore(workspace, clientId, manifest.generation)
                }
            if (isBase && clientId != null) onBaseRef(storePath.parent)
            if (!Files.isDirectory(storePath)) {
                ContentAddressedPackCache(cacheRoot)
                    .materializeDirectory(manifest.packKeys.single(), storePath)
            }
            return SharedReadOnlyStore.open(storePath)
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
        val baseStore =
            openResolvedStore(
                cacheRoot,
                baseWorkspace,
                baseManifest,
                clientId,
                onBaseRef,
                isBase = true,
            )
        var completed = false
        try {
            if (isBase && clientId != null) {
                onBaseRef(overlayDeltaStorePath(workspace, clientId, manifest.generation).parent)
            }
            val overlayStore = openOverlayDeltaStore(cacheRoot, workspace, manifest, clientId)
            return WorktreeOverlayIndexStore(baseStore, overlayStore, manifest.tombstonePrefixes)
                .also { completed = true }
        } finally {
            if (!completed) baseStore.close()
        }
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
            return SharedReadOnlyStore.open(clientPath)
        }
        return SharedReadOnlyStore.open(sharedPath)
    }

    private fun materializeOverlayPack(cacheRoot: Path, overlayKey: String, destination: Path) {
        if (Files.isDirectory(destination)) return
        ContentAddressedPackCache(cacheRoot).materializeDirectory(overlayKey, destination)
    }
}
