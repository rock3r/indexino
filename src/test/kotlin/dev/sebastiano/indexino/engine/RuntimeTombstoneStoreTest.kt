package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTombstoneStoreTest {
    @Test
    fun `workspace loss tombstone is persisted outside the workspace`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-tombstone-")
        try {
            val path =
                RuntimePaths.tombstonePath(cacheRoot, "a".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
            RuntimeTombstoneStore.write(path, Path.of("/deleted/workspace"))

            assertEquals("WORKSPACE_LOST", RuntimeTombstoneStore.read(path)?.code)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }
}
