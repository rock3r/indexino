package dev.sebastiano.indexino.core.git

import java.nio.file.Path

internal object GitHeadResolver {
    fun resolve(workspaceRoot: Path): String {
        val process =
            ProcessBuilder("git", "-C", workspaceRoot.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git rev-parse HEAD failed: $output" }
        return output
    }
}
