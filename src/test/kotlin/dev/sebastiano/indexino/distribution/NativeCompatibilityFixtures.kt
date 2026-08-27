package dev.sebastiano.indexino.distribution

import java.nio.file.Path

internal object NativeCompatibilityFixtures {
    fun indexArguments(workspace: Path): Array<String> =
        arrayOf(
            "index",
            "--project",
            workspace.toString(),
            "--build-system",
            "gradle",
            "--gradle-module",
            ":app",
            "--applications",
            "dev.sebastiano.selection-context",
            "--no-auto-refresh",
        )
}
