package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.CacheActivityLock
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorkspaceRegistryStore
import dev.sebastiano.indexino.core.sourcelink.SerializedSourceLinkRegistration
import dev.sebastiano.indexino.core.sourcelink.SourceLinkRegistrySnapshot
import dev.sebastiano.indexino.core.sourcelink.SourceLinkRegistryStore
import dev.sebastiano.indexino.engine.RuntimeLease
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.model.LinkGenerationId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
    fun `gc keeps an unreferenced pack on its first observed pass`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-grace-")
        val pack = packPath(cacheRoot, "f".repeat(64))
        Files.createDirectories(pack.parent)
        Files.writeString(pack, "keep during grace")
        try {
            val first = CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            assertTrue(Files.exists(pack), first)
            assertTrue(first.contains("reclaimedPacks=0"), first)
            val beforeExpiry =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(30).toMillis() - 1,
                )
            assertTrue(Files.exists(pack), beforeExpiry)
            val expired =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(30).toMillis(),
                )
            assertFalse(Files.exists(pack), expired)
            assertTrue(expired.contains("reclaimedPacks=1"), expired)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gc resets persisted pack age when a generation references it again`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-reused-pack-")
        val key = "a".repeat(64)
        val pack = packPath(cacheRoot, key)
        Files.createDirectories(pack.parent)
        Files.writeString(pack, "reused")
        val manifests = WorkspaceGenerationManifestStore(cacheRoot, "b".repeat(16))
        fun publish(generation: String, keys: List<String>) =
            manifests.publish(
                WorkspaceGenerationManifest(
                    generation = generation,
                    workspaceRevisionFingerprint = generation,
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = generation,
                    packKeys = keys,
                )
            )
        try {
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            publish("reused", listOf(key))
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L + Duration.ofDays(15).toMillis())
            publish("unreferenced", emptyList())
            val secondObservation = 1_000L + Duration.ofDays(31).toMillis()
            val report = CacheMaintenance.gc(cacheRoot, nowEpochMillis = secondObservation)
            assertTrue(Files.exists(pack), report)
            val beforeExpiry =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = secondObservation + Duration.ofDays(30).toMillis() - 1,
                )
            assertTrue(Files.exists(pack), beforeExpiry)
            val expired =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = secondObservation + Duration.ofDays(30).toMillis(),
                )
            assertFalse(Files.exists(pack), expired)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gc retires old reconstructible writer staging without deleting its published pack`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-staging-gc-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-staging-workspace-")
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val key = "a".repeat(64)
        val pack = packPath(cacheRoot, key)
        val staging = cacheRoot.resolve("workspaces/$workspaceId/staging/in-process-writer")
        WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
            .publish(
                WorkspaceGenerationManifest(
                    generation = "current",
                    workspaceRevisionFingerprint = "current",
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = "current",
                    packKeys = listOf(key),
                )
            )
        Files.createDirectories(pack.parent)
        Files.writeString(pack, "published")
        Files.createDirectories(staging.resolve("index/commit"))
        val writer = staging.resolve("index/commit/writer")
        Files.writeString(writer, "reconstructible")
        writer.toFile().setWritable(false)
        val registry = WorkspaceRegistryStore(cacheRoot)
        registry.upsert(workspaceId, workspace, null)
        val lastUsed = checkNotNull(registry.entry(workspaceId)).lastUsedEpochMillis
        try {
            val tooYoung =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = lastUsed + Duration.ofDays(30).toMillis() - 1,
                )
            assertTrue(Files.exists(staging), tooYoung)
            val expired =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = lastUsed + Duration.ofDays(30).toMillis(),
                )
            assertFalse(Files.exists(staging), expired)
            assertTrue(Files.exists(pack), expired)
            assertTrue(expired.contains("reclaimedStaging=1"), expired)
        } finally {
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `gc preserves writer staging with an unfinished recovery checkpoint`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-staging-checkpoint-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-staging-workspace-")
        val workspaceId = InProcessCacheLayout.workspaceId(workspace)
        val staging = cacheRoot.resolve("workspaces/$workspaceId/staging/in-process-writer")
        val checkpoint = staging.resolve("index/commit/manifest.json.rollback")
        Files.createDirectories(checkpoint)
        Files.writeString(checkpoint.resolve("manifest.json"), "preserve for recovery")
        val registry = WorkspaceRegistryStore(cacheRoot)
        registry.upsert(workspaceId, workspace, null)
        val lastUsed = checkNotNull(registry.entry(workspaceId)).lastUsedEpochMillis
        try {
            val report =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = lastUsed + Duration.ofDays(31).toMillis(),
                )
            assertTrue(Files.exists(checkpoint.resolve("manifest.json")), report)
            assertTrue(report.contains("reclaimedStaging=0"), report)
        } finally {
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `quota evicts only an observed old unreferenced pack after minimum grace`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-quota-")
        val protectedKey = "c".repeat(64)
        WorkspaceGenerationManifestStore(cacheRoot, "d".repeat(16))
            .publish(
                WorkspaceGenerationManifest(
                    generation = "current",
                    workspaceRevisionFingerprint = "current",
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = "current",
                    packKeys = listOf(protectedKey),
                )
            )
        val protected = packPath(cacheRoot, protectedKey)
        val older = packPath(cacheRoot, "a".repeat(64))
        val younger = packPath(cacheRoot, "b".repeat(64))
        listOf(protected, older, younger).forEach { Files.createDirectories(it.parent) }
        Files.writeString(protected, "P".repeat(64))
        Files.writeString(older, "A".repeat(64))
        try {
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            Files.writeString(younger, "B".repeat(64))
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L + Duration.ofHours(1).toMillis())
            val totalBytes =
                Files.walk(cacheRoot).use { paths ->
                    paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
                }
            val quota = totalBytes - Files.size(older) + 1
            val beforeGrace =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(1).toMillis() - 1,
                    maxCacheBytes = quota,
                )
            assertTrue(Files.exists(older), beforeGrace)
            assertTrue(Files.exists(younger), beforeGrace)

            val afterGrace =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(1).toMillis(),
                    maxCacheBytes = quota,
                )
            assertFalse(Files.exists(older), afterGrace)
            assertTrue(Files.exists(younger), afterGrace)
            assertTrue(Files.exists(protected), afterGrace)
            assertTrue(afterGrace.contains("reclaimedPacks=1"), afterGrace)
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
            val first = CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            assertTrue(Files.exists(supersededPack), first)
            assertTrue(Files.exists(orphanedPack), first)
            val report =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(30).toMillis(),
                )

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
    fun `gc preserves superseded provider generation pinned by current source links`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-linked-gc-")
        val providerId = "a".repeat(16)
        val consumerId = "b".repeat(16)
        val oldPack = "c".repeat(64)
        val newPack = "d".repeat(64)
        val orphanPack = "e".repeat(64)
        val provider = WorkspaceGenerationManifestStore(cacheRoot, providerId)
        fun manifest(generation: String, pack: String) =
            WorkspaceGenerationManifest(
                generation = generation,
                workspaceRevisionFingerprint = generation,
                originId = "workspace",
                revision = null,
                stateFingerprint = generation,
                packKeys = listOf(pack),
            )
        provider.publish(manifest("old", oldPack))
        val oldExpanded = cacheRoot.resolve("workspaces/$providerId/generations/old/materialized")
        Files.createDirectories(oldExpanded)
        provider.publish(manifest("new", newPack))
        WorkspaceGenerationManifestStore(cacheRoot, consumerId)
            .publish(manifest("current", newPack).copy(linkGeneration = "links"))
        val links = SourceLinkRegistryStore(cacheRoot.resolve("workspaces/$consumerId"))
        val snapshot =
            SourceLinkRegistrySnapshot(
                linkGeneration = "links",
                registrations =
                    listOf(
                        SerializedSourceLinkRegistration(
                            coordinate = "sample:library:1",
                            artifactDigest = "digest",
                            repositoryIdentity = "provider",
                            checkoutPath = "provider",
                            sourceRoots = listOf("src/main/java"),
                            sourceOriginId = "workspace",
                            linkedGeneration = "old",
                            mappingBinaryPrefix = "sample",
                            mappingSourceRoot = "src/main/java",
                            evidence = "declared",
                        )
                    ),
            )
        val snapshotPath = links.generationPath(LinkGenerationId.of("links"))
        Files.createDirectories(snapshotPath.parent)
        Files.writeString(snapshotPath, Json.encodeToString(snapshot))
        listOf(oldPack, newPack, orphanPack).forEach { key ->
            val pack = packPath(cacheRoot, key)
            Files.createDirectories(pack.parent)
            Files.writeString(pack, key)
        }
        try {
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            val report =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(30).toMillis(),
                )

            assertTrue(Files.isDirectory(oldExpanded), report)
            assertTrue(Files.exists(packPath(cacheRoot, oldPack)), report)
            assertTrue(Files.exists(packPath(cacheRoot, newPack)), report)
            assertFalse(Files.exists(packPath(cacheRoot, orphanPack)), report)
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
            CacheMaintenance.gc(cacheRoot, nowEpochMillis = 1_000L)
            val uncertain =
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(1).toMillis(),
                    maxCacheBytes = 1,
                )
            assertTrue(Files.exists(orphanedPack), uncertain)
            assertTrue(Files.exists(expanded), uncertain)
            val attempt = runCatching {
                CacheMaintenance.gc(
                    cacheRoot,
                    nowEpochMillis = 1_000L + Duration.ofDays(30).toMillis(),
                )
            }
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
    fun `gc reclaims abandoned client refs only after all snapshot leases end`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-cache-refs-")
        val workspaceId = "a".repeat(16)
        val manifests = WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
        manifests.publish(
            WorkspaceGenerationManifest(
                generation = "current",
                workspaceRevisionFingerprint = "revision",
                originId = "workspace",
                revision = null,
                stateFingerprint = "state",
                packKeys = emptyList(),
            )
        )
        val abandonedRef =
            cacheRoot.resolve("workspaces/$workspaceId/refs/old-client/current/overlay-delta")
        val currentManifest =
            cacheRoot.resolve("workspaces/$workspaceId/generations/current/manifest.json")
        Files.createDirectories(abandonedRef)
        Files.writeString(abandonedRef.resolve("00000000.xd"), "orphaned snapshot")
        val outside = cacheRoot.resolve("outside/keep")
        Files.createDirectories(outside.parent)
        Files.writeString(outside, "must not follow client symlinks")
        Files.createSymbolicLink(abandonedRef.resolve("outside"), outside.parent)
        try {
            CacheActivityLock.acquire(cacheRoot).use {
                assertTrue(CacheMaintenance.gc(cacheRoot).contains("activeRuntime=true"))
                assertTrue(Files.exists(abandonedRef))
            }

            val report = CacheMaintenance.gc(cacheRoot)
            assertFalse(Files.exists(abandonedRef), report)
            assertTrue(Files.exists(outside), report)
            assertTrue(Files.exists(currentManifest), report)
            assertTrue(report.contains("reclaimedClientRefs=1"), report)
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
