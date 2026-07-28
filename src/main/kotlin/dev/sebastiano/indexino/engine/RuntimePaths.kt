package dev.sebastiano.indexino.engine

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Internal cache locations for one workspace runtime. */
internal object RuntimePaths {
    const val MAX_SOCKET_PATH_LENGTH = 102
    const val WORKSPACE_ID_LENGTH = 16
    private const val CACHE_ROOT_HASH_LENGTH = 16

    fun runtimeDirectory(cacheRoot: Path): Path = cacheRoot.resolve("runtime")

    fun socketPath(cacheRoot: Path, workspaceId: String): Path {
        require(workspaceId.length == WORKSPACE_ID_LENGTH) {
            "Workspace runtime IDs must be $WORKSPACE_ID_LENGTH hexadecimal characters"
        }
        val path = runtimeDirectory(cacheRoot).resolve("$workspaceId.sock")
        if (path.toString().length <= MAX_SOCKET_PATH_LENGTH) return path

        // AF_UNIX names have a kernel-sized path limit. The lease remains under the cache root,
        // while this deterministic per-cache endpoint keeps the actual socket name bindable.
        return Path.of("/tmp")
            .resolve("indexino-runtime-${cacheRootHash(cacheRoot)}")
            .resolve("$workspaceId.sock")
    }

    fun leasePath(cacheRoot: Path, workspaceId: String): Path =
        runtimeDirectory(cacheRoot).resolve("$workspaceId.lease.json")

    fun tombstonePath(cacheRoot: Path, workspaceId: String): Path =
        cacheRoot.resolve("registry").resolve("tombstones").resolve("$workspaceId.json")

    private fun cacheRootHash(cacheRoot: Path): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(cacheRoot.toString().toByteArray())
            )
            .take(CACHE_ROOT_HASH_LENGTH)
}
