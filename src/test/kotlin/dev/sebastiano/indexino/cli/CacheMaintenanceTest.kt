package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.engine.RuntimeLease
import dev.sebastiano.indexino.engine.RuntimePaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CacheMaintenanceTest {
    @Test
    fun `gc leaves packs untouched while a runtime is live`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-live-")
        val pack = packPath(cacheRoot, "e".repeat(64))
        Files.createDirectories(pack.parent)
        Files.writeString(pack, "keep while refreshing")
        val lease = RuntimePaths.leasePath(cacheRoot, "f".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        Files.createDirectories(lease.parent)
        Files.writeString(
            lease,
            Json.encodeToString(
                RuntimeLease(
                    ownerPid = ProcessHandle.current().pid(),
                    startedAtMillis = 1,
                    protocolMajor = 1,
                    protocolMinor = 0,
                    endpoint = "unused",
                    workspace = "unused",
                )
            ),
        )
        try {
            assertTrue(CacheMaintenance.gc(cacheRoot).contains("activeRuntime=true"))
            assertTrue(Files.exists(pack))
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gc reclaims only packs not referenced by generation manifests`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-gc-")
        val referenced = "a".repeat(64)
        val orphaned = "b".repeat(64)
        val workspaceId = "c".repeat(16)
        val manifest =
            cacheRoot
                .resolve("workspaces")
                .resolve(workspaceId)
                .resolve("generations")
                .resolve("g")
                .resolve("manifest.json")
        Files.createDirectories(manifest.parent)
        Files.writeString(
            manifest,
            Json.encodeToString(
                WorkspaceGenerationManifest(
                    generation = "g",
                    workspaceRevisionFingerprint = "r",
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = "s",
                    packKeys = listOf(referenced),
                )
            ),
        )
        val referencedPack = packPath(cacheRoot, referenced)
        val orphanedPack = packPath(cacheRoot, orphaned)
        Files.createDirectories(referencedPack.parent)
        Files.writeString(referencedPack, "keep")
        Files.createDirectories(orphanedPack.parent)
        Files.writeString(orphanedPack, "remove")
        try {
            val report = CacheMaintenance.gc(cacheRoot)

            assertTrue(Files.exists(referencedPack))
            assertFalse(Files.exists(orphanedPack), report)
            assertTrue(report.contains("reclaimedPacks=1"), report)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forget removes only the selected workspace state`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-forget-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-forget-workspace-")
        val otherWorkspace = "d".repeat(16)
        val workspaceRoot =
            cacheRoot
                .resolve("workspaces")
                .resolve(InProcessCacheLayout.workspaceId(workspace.toRealPath()))
        val otherRoot = cacheRoot.resolve("workspaces").resolve(otherWorkspace)
        Files.createDirectories(workspaceRoot)
        Files.createDirectories(otherRoot)
        try {
            assertEquals(CliExitCodes.SUCCESS, CacheMaintenance.forget(cacheRoot, workspace))

            assertFalse(Files.exists(workspaceRoot))
            assertTrue(Files.exists(otherRoot))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    private fun packPath(cacheRoot: Path, key: String): Path =
        cacheRoot.resolve("chunks").resolve(key.take(2)).resolve(key.substring(2, 4)).resolve(key)
}
