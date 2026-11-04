package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
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
    fun `gc reclaims packs referenced only by superseded generations`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-gc-")
        val referenced = "a".repeat(64)
        val superseded = "b".repeat(64)
        val orphaned = "d".repeat(64)
        val workspaceId = "c".repeat(16)
        val manifests = WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
        manifests.publish(
            WorkspaceGenerationManifest(
                generation = "old",
                workspaceRevisionFingerprint = "r1",
                originId = "workspace",
                revision = null,
                stateFingerprint = "s1",
                packKeys = listOf(superseded),
            )
        )
        manifests.publish(
            WorkspaceGenerationManifest(
                generation = "current",
                workspaceRevisionFingerprint = "r2",
                originId = "workspace",
                revision = null,
                stateFingerprint = "s2",
                packKeys = listOf(referenced),
            )
        )
        val referencedPack = packPath(cacheRoot, referenced)
        val supersededPack = packPath(cacheRoot, superseded)
        val orphanedPack = packPath(cacheRoot, orphaned)
        Files.createDirectories(referencedPack.parent)
        Files.writeString(referencedPack, "keep")
        Files.createDirectories(supersededPack.parent)
        Files.writeString(supersededPack, "reclaim")
        Files.createDirectories(orphanedPack.parent)
        Files.writeString(orphanedPack, "remove")
        try {
            val report = CacheMaintenance.gc(cacheRoot)

            assertTrue(Files.exists(referencedPack))
            assertFalse(Files.exists(supersededPack), report)
            assertFalse(Files.exists(orphanedPack), report)
            assertTrue(report.contains("reclaimedPacks=2"), report)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `gc reclaims obsolete expanded stores but keeps current and fork base generations`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-expanded-gc-")
        val baseId = "a".repeat(16)
        val forkId = "b".repeat(16)
        val leafId = "c".repeat(16)
        val base = WorkspaceGenerationManifestStore(cacheRoot, baseId)
        val fork = WorkspaceGenerationManifestStore(cacheRoot, forkId)
        val leaf = WorkspaceGenerationManifestStore(cacheRoot, leafId)
        fun generation(id: String, name: String, directory: String): Path =
            cacheRoot.resolve("workspaces/$id/generations/$name/$directory").also {
                Files.createDirectories(it)
            }

        base.publish(
            WorkspaceGenerationManifest(
                generation = "base-orphan",
                workspaceRevisionFingerprint = "orphan",
                originId = "workspace",
                revision = null,
                stateFingerprint = "orphan",
                packKeys = listOf("d".repeat(64)),
            )
        )
        val oldBase = generation(baseId, "base-orphan", "materialized")
        base.publish(
            WorkspaceGenerationManifest(
                generation = "base-old",
                workspaceRevisionFingerprint = "old",
                originId = "workspace",
                revision = null,
                stateFingerprint = "old",
                packKeys = listOf("a".repeat(64)),
            )
        )
        val forkBase = generation(baseId, "base-old", "materialized")
        base.publish(
            WorkspaceGenerationManifest(
                generation = "base-current",
                workspaceRevisionFingerprint = "current",
                originId = "workspace",
                revision = null,
                stateFingerprint = "current",
                packKeys = listOf("b".repeat(64)),
            )
        )
        val currentBase = generation(baseId, "base-current", "materialized")
        fork.publish(
            WorkspaceGenerationManifest(
                generation = "fork-orphan",
                workspaceRevisionFingerprint = "orphan",
                originId = "workspace",
                revision = null,
                stateFingerprint = "orphan",
                packKeys = emptyList(),
                representation = "overlay",
                baseWorkspaceId = baseId,
                baseGeneration = "base-old",
                overlayPackKeys = listOf("d".repeat(64)),
            )
        )
        val oldOverlay = generation(forkId, "fork-orphan", "overlay-delta")
        fork.publish(
            WorkspaceGenerationManifest(
                generation = "fork-old",
                workspaceRevisionFingerprint = "old",
                originId = "workspace",
                revision = null,
                stateFingerprint = "old",
                packKeys = emptyList(),
                representation = "overlay",
                baseWorkspaceId = baseId,
                baseGeneration = "base-old",
                overlayPackKeys = listOf("c".repeat(64)),
            )
        )
        val forkedOverlay = generation(forkId, "fork-old", "overlay-delta")
        fork.publish(
            WorkspaceGenerationManifest(
                generation = "fork-current",
                workspaceRevisionFingerprint = "current",
                originId = "workspace",
                revision = null,
                stateFingerprint = "current",
                packKeys = listOf("e".repeat(64)),
            )
        )
        val currentFork = generation(forkId, "fork-current", "materialized")
        leaf.publish(
            WorkspaceGenerationManifest(
                generation = "leaf-current",
                workspaceRevisionFingerprint = "leaf",
                originId = "workspace",
                revision = null,
                stateFingerprint = "leaf",
                packKeys = emptyList(),
                representation = "overlay",
                baseWorkspaceId = forkId,
                baseGeneration = "fork-old",
                overlayPackKeys = listOf("f".repeat(64)),
            )
        )
        val currentOverlay = generation(leafId, "leaf-current", "overlay-delta")
        try {
            val report = CacheMaintenance.gc(cacheRoot)

            assertTrue(Files.exists(forkBase), report)
            assertTrue(Files.exists(currentBase), report)
            assertTrue(Files.exists(forkedOverlay), report)
            assertTrue(Files.exists(currentFork), report)
            assertTrue(Files.exists(currentOverlay), report)
            assertFalse(Files.exists(oldBase), report)
            assertFalse(Files.exists(oldOverlay), report)
            assertTrue(report.contains("reclaimedMaterializations=2"), report)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gc retains expanded stores when a current overlay base is unavailable`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-missing-base-")
        val workspaceId = "a".repeat(16)
        WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
            .publish(
                WorkspaceGenerationManifest(
                    generation = "current",
                    workspaceRevisionFingerprint = "current",
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = "current",
                    packKeys = emptyList(),
                    representation = "overlay",
                    baseWorkspaceId = "b".repeat(16),
                    baseGeneration = "missing",
                )
            )
        val expanded = cacheRoot.resolve("workspaces/$workspaceId/generations/old/materialized")
        Files.createDirectories(expanded)
        val orphanedPack = packPath(cacheRoot, "f".repeat(64))
        Files.createDirectories(orphanedPack.parent)
        Files.writeString(orphanedPack, "orphan")
        try {
            val attempt = runCatching { CacheMaintenance.gc(cacheRoot) }
            assertTrue(attempt.isSuccess, "GC aborted: ${attempt.exceptionOrNull()}")
            val report = attempt.getOrThrow()

            assertFalse(Files.exists(orphanedPack), report)
            assertTrue(Files.exists(expanded), report)
            assertTrue(report.contains("reclaimedMaterializations=0"), report)
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
