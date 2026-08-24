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
    fun `current keeps the last published generation when a newer directory is staged only`() {
        val root = createTempDirectory("indexino-generation-staged-").also(tempDirs::add)
        val store = WorkspaceGenerationManifestStore(root, "workspace-id")
        val first =
            WorkspaceGenerationManifest(
                generation = "generation-1",
                workspaceRevisionFingerprint = "revision-1",
                originId = "workspace",
                revision = null,
                stateFingerprint = "state-1",
                packKeys = listOf("aa".repeat(32)),
            )
        val stagedOnly =
            WorkspaceGenerationManifest(
                generation = "generation-2",
                workspaceRevisionFingerprint = "revision-2",
                originId = "workspace",
                revision = null,
                stateFingerprint = "state-2",
                packKeys = listOf("bb".repeat(32)),
            )
        store.publish(first)
        val stagedManifest =
            root.resolve("workspaces/workspace-id/generations/generation-2/manifest.json")
        java.nio.file.Files.createDirectories(stagedManifest.parent)
        java.nio.file.Files.writeString(
            stagedManifest,
            json.encodeToString(WorkspaceGenerationManifest.serializer(), stagedOnly),
        )

        assertEquals(first, store.current())
    }

    private val json =
        kotlinx.serialization.json.Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `generation manifest round trips compatibility metadata`() {
        val root = createTempDirectory("indexino-generation-compat-").also(tempDirs::add)
        val store = WorkspaceGenerationManifestStore(root, "workspace-id")
        val compatibility =
            dev.sebastiano.indexino.core.manifest.IndexManifest(
                commit = "commit",
                indexerVersion = "indexino-test",
                scope = ":ui",
                topology = "gradle",
                sourceFileCount = 2,
                sourcesContentHash = "hash",
                builtAt = "2026-01-01T00:00:00Z",
            )
        store.publish(
            WorkspaceGenerationManifest(
                generation = "generation-1",
                workspaceRevisionFingerprint = "revision",
                originId = "workspace",
                revision = "commit",
                stateFingerprint = "hash",
                packKeys = listOf("ab".repeat(32)),
                compatibilityManifest = compatibility,
            )
        )

        assertEquals(compatibility, store.current()?.compatibilityManifest)
    }

    @Test
    fun `publishes a generation manifest through an atomic current pointer`() {
        val root = createTempDirectory("indexino-generation-").also(tempDirs::add)
        val store = WorkspaceGenerationManifestStore(root, "workspace-id")
        val manifest =
            WorkspaceGenerationManifest(
                generation = "generation-1",
                workspaceRevisionFingerprint = "revision",
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
