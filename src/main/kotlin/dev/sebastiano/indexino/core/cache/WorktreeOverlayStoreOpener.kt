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
        val baseStorePath =
            materializedGenerationStore(
                cacheRoot = cacheRoot,
                workspace = baseWorkspace,
                manifest = baseManifest,
            )
        val baseStore = XodusCodeIndexStore.open(baseStorePath, readOnly = true)
        val overlayStore =
            overlayDeltaStorePath(workspace, clientId, manifest.generation)
                .takeIf { Files.isDirectory(it) }
                ?.let { XodusCodeIndexStore.open(it, readOnly = true) }
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
        ContentAddressedPackCache(cacheRoot).materializeDirectory(overlayKey, destination)
        return destination
    }
}
