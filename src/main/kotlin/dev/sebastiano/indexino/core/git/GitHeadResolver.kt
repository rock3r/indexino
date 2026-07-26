package dev.sebastiano.indexino.core.git

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal object GitHeadResolver {
    const val FILESYSTEM_REVISION_PREFIX: String = "filesystem:"

    fun resolve(workspaceRoot: Path): String {
        val process =
            try {
                ProcessBuilder("git", "-C", workspaceRoot.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: java.io.IOException) {
                return filesystemRevision(workspaceRoot)
            }
        val output = process.inputStream.bufferedReader().readText().trim()
        return if (process.waitFor() == 0) output else filesystemRevision(workspaceRoot)
    }

    fun isFilesystemRevision(value: String): Boolean = value.startsWith(FILESYSTEM_REVISION_PREFIX)

    private fun filesystemRevision(workspaceRoot: Path): String {
        val canonical = workspaceRoot.toRealPath().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return FILESYSTEM_REVISION_PREFIX + HexFormat.of().formatHex(digest)
    }
}
