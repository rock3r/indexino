package dev.sebastiano.indexino.engine

import java.nio.file.Path

/** Internal cache locations for one workspace runtime. */
internal object RuntimePaths {
    const val MAX_SOCKET_PATH_LENGTH = 102
    const val WORKSPACE_ID_LENGTH = 16

    fun runtimeDirectory(cacheRoot: Path): Path = cacheRoot.resolve("runtime")

    fun socketPath(cacheRoot: Path, workspaceId: String): Path {
        require(workspaceId.length == WORKSPACE_ID_LENGTH) {
            "Workspace runtime IDs must be $WORKSPACE_ID_LENGTH hexadecimal characters"
        }
        val path = runtimeDirectory(cacheRoot).resolve("$workspaceId.sock")
        require(path.toString().length <= MAX_SOCKET_PATH_LENGTH) {
            "Runtime socket path exceeds the $MAX_SOCKET_PATH_LENGTH character AF_UNIX limit"
        }
        return path
    }

    fun leasePath(cacheRoot: Path, workspaceId: String): Path =
        runtimeDirectory(cacheRoot).resolve("$workspaceId.lease.json")

    fun tombstonePath(cacheRoot: Path, workspaceId: String): Path =
        cacheRoot.resolve("registry").resolve("tombstones").resolve("$workspaceId.json")
}
