package dev.sebastiano.indexino.core.git

import java.nio.file.Path

/**
 * Runs short Git commands whose stdout is parsed by Indexino.
 *
 * Stderr never reaches the returned output: Git prints warnings such as "LF will be replaced by
 * CRLF" (core.autocrlf, Git for Windows' system default) on stderr with a zero exit code, and a
 * merged stream would corrupt every parser and stamp built from that output. Stderr text is not
 * kept because no caller surfaces it: each site falls back silently when Git is unavailable or the
 * command fails.
 */
internal object GitProcessOutput {
    /**
     * Git's stdout for [arguments] run in [root], or null when Git cannot start or exits non-zero.
     */
    fun readStdout(root: Path, arguments: List<String>): String? =
        try {
            val process =
                ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output else null
        } catch (_: java.io.IOException) {
            null
        }
}
