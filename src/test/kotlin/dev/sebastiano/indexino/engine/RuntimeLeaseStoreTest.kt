package dev.sebastiano.indexino.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeLeaseStoreTest {
    @Test
    fun `a reused PID with a different start time is reclaimed`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-reused-pid-")
        try {
            val workspaceId = "c".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
            val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
            val first =
                RuntimeLeaseStore(
                    cacheRoot,
                    pidProvider = { 101L },
                    isProcessAlive = { true },
                    processStartedAtMillis = { 1_000L },
                )
            val replacement =
                RuntimeLeaseStore(
                    cacheRoot,
                    pidProvider = { 202L },
                    isProcessAlive = { true },
                    processStartedAtMillis = { pid -> if (pid == 101L) 2_000L else 3_000L },
                )

            first.acquire(workspaceId, endpoint, Path.of("/workspace"))
            val acquired = replacement.acquire(workspaceId, endpoint, Path.of("/workspace"))

            assertIs<RuntimeLeaseAcquisition.Owned>(acquired)
            assertEquals(202L, acquired.lease.ownerPid)
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a live lease makes a second owner attach instead of acquiring`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-lease-")
        try {
            val workspaceId = "a".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
            val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
            val first =
                RuntimeLeaseStore(cacheRoot, pidProvider = { 101L }, isProcessAlive = { true })
            val second =
                RuntimeLeaseStore(cacheRoot, pidProvider = { 202L }, isProcessAlive = { true })

            val owner = first.acquire(workspaceId, endpoint, Path.of("/workspace"))
            val attached = second.acquire(workspaceId, endpoint, Path.of("/workspace"))

            assertIs<RuntimeLeaseAcquisition.Owned>(owner)
            val existing = assertIs<RuntimeLeaseAcquisition.Existing>(attached)
            assertEquals(101L, existing.lease.ownerPid)
            assertEquals(endpoint.toString(), existing.lease.endpoint)
            assertEquals(
                101L,
                RuntimeLeaseStore.read(RuntimePaths.leasePath(cacheRoot, workspaceId))?.ownerPid,
            )
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a dead lease is replaced before a new owner binds`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-runtime-stale-lease-")
        try {
            val workspaceId = "b".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
            val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
            val staleOwner =
                RuntimeLeaseStore(cacheRoot, pidProvider = { 101L }, isProcessAlive = { false })
            val successor =
                RuntimeLeaseStore(cacheRoot, pidProvider = { 202L }, isProcessAlive = { false })

            staleOwner.acquire(workspaceId, endpoint, Path.of("/workspace"))
            Files.createDirectories(endpoint.parent)
            Files.writeString(endpoint, "stale socket placeholder")
            val replacement = successor.acquire(workspaceId, endpoint, Path.of("/workspace"))

            assertIs<RuntimeLeaseAcquisition.Owned>(replacement)
            assertEquals(
                202L,
                RuntimeLeaseStore.read(RuntimePaths.leasePath(cacheRoot, workspaceId))?.ownerPid,
            )
            kotlin.test.assertFalse(Files.exists(endpoint))
        } finally {
            cacheRoot.toFile().deleteRecursively()
        }
    }
}
