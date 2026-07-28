package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.InProcessCacheLayout
import java.nio.file.Files
import java.nio.file.Path

/** Minimal process entry point used when the embeddable facade starts its local runtime owner. */
internal fun main(args: Array<String>) {
    require(args.size == 1) { "Expected one workspace path" }
    WorkspaceRuntime.start(Path.of(args.single()), InProcessCacheLayout.cacheRoot()).use { runtime
        ->
        while (Files.exists(runtime.endpoint)) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }
}

private const val POLL_INTERVAL_MILLIS = 100L
