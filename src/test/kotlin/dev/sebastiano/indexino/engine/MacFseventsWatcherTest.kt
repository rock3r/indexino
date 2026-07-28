package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

class MacFseventsWatcherTest {
    @Test
    fun `native stream reports workspace changes`() {
        if (!System.getProperty("os.name").startsWith("Mac")) return
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-fsevents-")
        val events = AtomicInteger()
        val watcher = MacFseventsWatcher(workspace, { events.incrementAndGet() }, {})
        watcher.use {
            Thread.sleep(STARTUP_SETTLE_MILLIS)
            Files.writeString(workspace.resolve("changed.kt"), "class Changed")
            watcher.flushForTests()
            repeat(WAIT_ATTEMPTS) {
                if (events.get() > 0) return
                Thread.sleep(WAIT_MILLIS)
            }
        }
        val diagnostic =
            "Timed out waiting for FSEvents callback " +
                "(bridge callbacks=${watcher.callbackCountForTests()}, " +
                "id=${watcher.callbackIdForTests()})"
        assertTrue(events.get() > 0, diagnostic)
        workspace.toFile().deleteRecursively()
    }

    private companion object {
        const val WAIT_ATTEMPTS = 100
        const val WAIT_MILLIS = 50L
        const val STARTUP_SETTLE_MILLIS = 250L
    }
}
