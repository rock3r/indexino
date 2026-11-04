package dev.sebastiano.indexino.cli

import com.github.ajalt.clikt.testing.test
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.core.cache.CacheActivityLock
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifest
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.engine.RuntimeLease
import dev.sebastiano.indexino.engine.RuntimePaths
import dev.sebastiano.indexino.engine.RuntimeTombstoneStore
import dev.sebastiano.indexino.engine.WorkspaceRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DaemonStopCommandTest {
    @Test
    fun `daemon stop acknowledges workspace loss without a lease`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-tombstone-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-tombstone-workspace-")
        val workspaceId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        val tombstone = RuntimePaths.tombstonePath(cacheRoot, workspaceId)
        RuntimeTombstoneStore.write(tombstone, workspace)
        try {
            assertEquals(CliExitCodes.SUCCESS, DaemonStopCommand().stop(workspace, cacheRoot))
            assertFalse(Files.exists(tombstone))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `daemon stop is reachable from the root CLI`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-cli-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-cli-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val result =
                MainCommand().test(listOf("daemon", "stop", "--project", workspace.toString()))

            assertEquals(CliExitCodes.SUCCESS, result.statusCode)
            assertFalse(Files.exists(runtime.endpoint))
        } finally {
            runtime.close()
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }

    @Test
    fun `daemon stop purge forgets only an idle workspace`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-purge-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-purge-workspace-")
        val selected =
            cacheRoot
                .resolve("workspaces")
                .resolve(InProcessCacheLayout.workspaceId(workspace.toRealPath()))
        val sibling = cacheRoot.resolve("workspaces/sibling")
        val sharedPack = cacheRoot.resolve("chunks/pack")
        Files.createDirectories(selected)
        Files.writeString(selected.resolve("current"), "generation")
        Files.createDirectories(sibling)
        Files.writeString(sibling.resolve("current"), "other")
        Files.createDirectories(sharedPack.parent)
        Files.writeString(sharedPack, "shared")
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val command = listOf("daemon", "stop", "--project", workspace.toString(), "--purge")
        try {
            CacheActivityLock.acquire(cacheRoot).use {
                assertEquals(CliExitCodes.ANALYSIS_ERROR, MainCommand().test(command).statusCode)
                assertTrue(Files.exists(selected.resolve("current")))
            }
            assertEquals(CliExitCodes.SUCCESS, MainCommand().test(command).statusCode)
            assertFalse(Files.exists(selected))
            assertTrue(Files.exists(sibling.resolve("current")))
            assertTrue(Files.exists(sharedPack))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
        }
    }

    @Test
    fun `daemon stop purge closes its running owner before forgetting workspace state`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-live-purge-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-live-workspace-")
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        val selected =
            cacheRoot
                .resolve("workspaces")
                .resolve(InProcessCacheLayout.workspaceId(workspace.toRealPath()))
        Files.createDirectories(selected)
        Files.writeString(selected.resolve("current"), "generation")
        val lingeringLease = CacheActivityLock.acquire(cacheRoot)
        val releaseLease = CountDownLatch(1)
        val closer = Thread {
            releaseLease.await(5, TimeUnit.SECONDS)
            lingeringLease.close()
        }
        closer.start()
        val purgeStatus = AtomicReference<Int>()
        val purgeReturned = CountDownLatch(1)
        val command = listOf("daemon", "stop", "--project", workspace.toString(), "--purge")
        val purger = Thread {
            try {
                purgeStatus.set(MainCommand().test(command).statusCode)
            } finally {
                purgeReturned.countDown()
            }
        }
        try {
            purger.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Files.exists(runtime.endpoint) && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertFalse(Files.exists(runtime.endpoint))
            assertFalse(purgeReturned.await(100, TimeUnit.MILLISECONDS))
            assertTrue(Files.exists(selected.resolve("current")))
            releaseLease.countDown()
            assertTrue(purgeReturned.await(5, TimeUnit.SECONDS))
            assertEquals(CliExitCodes.SUCCESS, purgeStatus.get())
            assertFalse(Files.exists(selected))
        } finally {
            releaseLease.countDown()
            closer.join(5_000)
            purger.join(5_000)
            runtime.close()
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
        }
    }

    @Test
    fun `rejected purge retains workspace loss tombstone while a fork still depends on it`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-purge-fork-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-purge-base-")
        val selectedId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        val selected = cacheRoot.resolve("workspaces/$selectedId")
        Files.createDirectories(selected)
        Files.writeString(selected.resolve("current"), "base")
        WorkspaceGenerationManifestStore(cacheRoot, "f".repeat(16))
            .publish(
                WorkspaceGenerationManifest(
                    generation = "fork",
                    workspaceRevisionFingerprint = "fork",
                    originId = "workspace",
                    revision = null,
                    stateFingerprint = "fork",
                    packKeys = emptyList(),
                    representation = "overlay",
                    baseWorkspaceId = selectedId,
                    baseGeneration = "base",
                )
            )
        val tombstone = RuntimePaths.tombstonePath(cacheRoot, selectedId)
        RuntimeTombstoneStore.write(tombstone, workspace)
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            val command = listOf("daemon", "stop", "--project", workspace.toString(), "--purge")
            assertEquals(CliExitCodes.ANALYSIS_ERROR, MainCommand().test(command).statusCode)
            assertTrue(Files.exists(tombstone))
            assertTrue(Files.exists(selected.resolve("current")))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
        }
    }

    @Test
    fun `purge removes a selected stale lease without ignoring another live runtime`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stale-lease-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stale-workspace-")
        val selectedId = InProcessCacheLayout.workspaceId(workspace.toRealPath())
        val selected = cacheRoot.resolve("workspaces/$selectedId")
        val lease = RuntimePaths.leasePath(cacheRoot, selectedId)
        Files.createDirectories(selected)
        Files.writeString(selected.resolve("current"), "generation")
        Files.createDirectories(lease.parent)
        Files.writeString(
            lease,
            Json.encodeToString(
                RuntimeLease(
                    ownerPid = ProcessHandle.current().pid(),
                    ownerStartedAtMillis = 0,
                    startedAtMillis = 1,
                    protocolMajor = 1,
                    protocolMinor = 0,
                    endpoint = RuntimePaths.socketPath(cacheRoot, selectedId).toString(),
                    workspace = workspace.toString(),
                )
            ),
        )
        val otherLease =
            RuntimePaths.leasePath(cacheRoot, "f".repeat(RuntimePaths.WORKSPACE_ID_LENGTH))
        Files.writeString(otherLease, Files.readString(lease))
        val oldCache = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        try {
            val command = listOf("daemon", "stop", "--project", workspace.toString(), "--purge")
            assertEquals(CliExitCodes.ANALYSIS_ERROR, MainCommand().test(command).statusCode)
            assertTrue(Files.exists(selected.resolve("current")))
            assertTrue(Files.exists(lease))
            Files.delete(otherLease)
            assertEquals(CliExitCodes.SUCCESS, MainCommand().test(command).statusCode)
            assertFalse(Files.exists(selected))
            assertFalse(Files.exists(lease))
        } finally {
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (oldCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", oldCache)
        }
    }

    @Test
    fun `daemon stop explicitly shuts down the shared runtime`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-")
        val workspace =
            Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-stop-workspace-")
        val previousCacheRoot = System.getProperty("indexino.cache.dir")
        System.setProperty("indexino.cache.dir", cacheRoot.toString())
        val runtime = WorkspaceRuntime.start(workspace, cacheRoot)
        try {
            val exitCode = DaemonStopCommand().stop(workspace, cacheRoot)

            assertEquals(CliExitCodes.SUCCESS, exitCode)
            assertFalse(Files.exists(runtime.endpoint))
            assertFalse(
                Files.exists(
                    RuntimePaths.leasePath(
                        cacheRoot,
                        InProcessCacheLayout.workspaceId(workspace.toRealPath()),
                    )
                )
            )
        } finally {
            runtime.close()
            workspace.toFile().deleteRecursively()
            cacheRoot.toFile().deleteRecursively()
            if (previousCacheRoot == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCacheRoot)
        }
    }
}
