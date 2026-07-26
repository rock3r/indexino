package dev.sebastiano.indexino.core.cache

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceGenerationManifestStoreTest {
    private val tempDirs = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `publishes a generation manifest through an atomic current pointer`() {
        val root = createTempDirectory("indexino-generation-").also(tempDirs::add)
        val store = WorkspaceGenerationManifestStore(root, "workspace-id")
        val manifest =
            WorkspaceGenerationManifest(
                generation = "generation-1",
                originId = "filesystem:origin",
                revision = null,
                stateFingerprint = "state",
                packKeys = listOf("ab".repeat(32)),
            )

        store.publish(manifest)

        assertEquals(manifest, store.current())
        assertTrue(
            root
                .resolve("workspaces/workspace-id/generations/generation-1/manifest.json")
                .toFile()
                .isFile
        )
        assertEquals(
            "generation-1",
            root.resolve("workspaces/workspace-id/current").toFile().readText().trim(),
        )
    }
}
