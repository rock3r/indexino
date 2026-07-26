package dev.sebastiano.indexino.api

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class InProcessCacheLayoutTest {
    @Test
    fun `workspace IDs leave room for the macOS socket path budget`() {
        val workspace = createTempDirectory("indexino-workspace-id-")
        val cache = createTempDirectory("indexino-cache-id-")
        val previous = System.getProperty("indexino.cache.dir")
        try {
            System.setProperty("indexino.cache.dir", cache.toString())

            val workspaceId = InProcessCacheLayout.workspaceRoot(workspace).fileName.toString()

            assertEquals(16, workspaceId.length)
        } finally {
            workspace.toFile().deleteRecursively()
            cache.toFile().deleteRecursively()
            if (previous == null) {
                System.clearProperty("indexino.cache.dir")
            } else {
                System.setProperty("indexino.cache.dir", previous)
            }
        }
    }
}
