package dev.sebastiano.indexino.api

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal object InProcessCacheLayout {
    /** Mutable refresh staging only; published facts live in packs and generation manifests. */
    fun writerRoot(workspace: Path): Path =
        workspaceRoot(workspace).resolve("staging").resolve("in-process-writer")

    fun generationStore(workspace: Path, clientId: String, generation: String): Path =
        workspaceRoot(workspace)
            .resolve("refs")
            .resolve(clientId)
            .resolve(generation)
            .resolve("store")

    fun sharedGenerationStore(workspace: Path, generation: String): Path =
        workspaceRoot(workspace).resolve("generations").resolve(generation).resolve("materialized")

    fun sharedOverlayDeltaStore(workspace: Path, generation: String): Path =
        workspaceRoot(workspace).resolve("generations").resolve(generation).resolve("overlay-delta")

    fun overlayDeltaStore(workspace: Path, clientId: String, generation: String): Path =
        workspaceRoot(workspace)
            .resolve("refs")
            .resolve(clientId)
            .resolve(generation)
            .resolve("overlay-delta")

    fun overlayMetadataPath(workspace: Path, generation: String): Path =
        workspaceRoot(workspace).resolve("generations").resolve(generation).resolve("overlay.json")

    fun overlayBuildDelta(workspace: Path, commit: String): Path =
        writerRoot(workspace).resolve("overlay-build").resolve(commit).resolve("delta")

    fun workspaceRoot(workspace: Path): Path =
        cacheRoot().resolve("workspaces").resolve(workspaceId(workspace))

    fun cacheRoot(): Path {
        val explicit =
            System.getProperty(TEST_CACHE_PROPERTY)?.takeIf(String::isNotBlank)
                ?: System.getenv("INDEXINO_CACHE_DIR")?.takeIf(String::isNotBlank)
        if (explicit != null) {
            return runCatching { Path.of(explicit).toRealPath() }
                .getOrElse { Path.of(explicit).toAbsolutePath().normalize() }
        }

        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
        if (xdg != null) return Path.of(xdg).resolve("indexino")

        val home = Path.of(System.getProperty("user.home"))
        return if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
            home.resolve("Library").resolve("Caches").resolve("indexino")
        } else {
            home.resolve(".cache").resolve("indexino")
        }
    }

    fun workspaceId(workspace: Path): String {
        // Indexino canonicalizes the workspace once at connection entry. Repeating toRealPath()
        // here keeps sibling worktrees and CLI/cache callers aligned on one workspace id.
        val canonical =
            runCatching { workspace.toRealPath().toString() }
                .getOrElse { workspace.toAbsolutePath().normalize().toString() }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return HexFormat.of().formatHex(digest).take(WORKSPACE_ID_HEX_LENGTH)
    }

    private const val TEST_CACHE_PROPERTY = "indexino.cache.dir"
    private const val WORKSPACE_ID_HEX_LENGTH = 16
}
