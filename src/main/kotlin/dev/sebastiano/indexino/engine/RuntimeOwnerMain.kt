package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import java.nio.file.Files
import java.nio.file.Path

/** Minimal process entry point used when the embeddable facade starts its local runtime owner. */
internal fun main(args: Array<String>) {
    require(args.size == 2) { "Expected a workspace path and auto-refresh mode" }
    val workspace = Path.of(args[0])
    val autoRefreshMode = AutoRefreshMode.valueOf(args[1])
    WorkspaceRuntime.start(workspace, InProcessCacheLayout.cacheRoot(), autoRefreshMode).use { runtime
        ->
        while (Files.exists(runtime.endpoint)) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }
}

private const val POLL_INTERVAL_MILLIS = 100L
