package dev.sebastiano.indexino.api

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal object InProcessCacheLayout {
    fun storeRoot(workspace: Path): Path =
        cacheRoot().resolve("workspaces").resolve(workspaceId(workspace)).resolve("legacy-store")

    fun generationStore(workspace: Path, clientId: String, generation: String): Path =
        workspaceRoot(workspace)
            .resolve("refs")
            .resolve(clientId)
            .resolve(generation)
            .resolve("store")

    fun workspaceRoot(workspace: Path): Path =
        cacheRoot().resolve("workspaces").resolve(workspaceId(workspace))

    fun cacheRoot(): Path {
        val explicit =
            System.getProperty(TEST_CACHE_PROPERTY)?.takeIf(String::isNotBlank)
                ?: System.getenv("INDEXINO_CACHE_DIR")?.takeIf(String::isNotBlank)
        if (explicit != null) return Path.of(explicit)

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
        // here would create a second unwrapped I/O failure window during construction.
        val digest = MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray())
        return HexFormat.of().formatHex(digest).take(WORKSPACE_ID_HEX_LENGTH)
    }

    private const val TEST_CACHE_PROPERTY = "indexino.cache.dir"
    private const val WORKSPACE_ID_HEX_LENGTH = 16
}
