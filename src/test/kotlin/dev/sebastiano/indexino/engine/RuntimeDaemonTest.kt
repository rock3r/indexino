package dev.sebastiano.indexino.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeDaemonTest {
    @Test
    fun `failed lease release can be retried without closing the server twice`() {
        val cacheRoot = Files.createTempDirectory("indexino-daemon-close-retry-")
        val workspaceId = "b".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val leaseStore = RuntimeLeaseStore(cacheRoot)
        val lease =
            assertIs<RuntimeLeaseAcquisition.Owned>(
                    leaseStore.acquire(workspaceId, endpoint, cacheRoot)
                )
                .lease
        val closeCalls = AtomicInteger()
        val daemon =
            RuntimeDaemon(
                cacheRoot,
                workspaceId,
                leaseStore,
                lease,
                AutoCloseable { closeCalls.incrementAndGet() },
                endpoint,
            )
        val savedLease = leasePath.resolveSibling("saved-lease.json")
        Files.move(leasePath, savedLease)
        Files.write(leasePath, byteArrayOf(0x80.toByte()))
        try {
            assertFailsWith<IOException> { daemon.close() }
            assertEquals(1, closeCalls.get())
            Files.delete(leasePath)
            Files.move(savedLease, leasePath)
            daemon.close()

            assertNull(RuntimeLeaseStore.read(leasePath))
            assertEquals(1, closeCalls.get())
        } finally {
            if (Files.exists(savedLease)) {
                Files.deleteIfExists(leasePath)
                Files.move(savedLease, leasePath)
            }
            daemon.close()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `overlapping closes release once after server shutdown and preserve a successor lease`() {
        val cacheRoot = Files.createTempDirectory("indexino-daemon-close-")
        val workspaceId = "c".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
        val endpoint = RuntimePaths.socketPath(cacheRoot, workspaceId)
        val leasePath = RuntimePaths.leasePath(cacheRoot, workspaceId)
        val leaseStore = RuntimeLeaseStore(cacheRoot, clockMillis = { 1_000L })
        val lease =
            assertIs<RuntimeLeaseAcquisition.Owned>(
                    leaseStore.acquire(workspaceId, endpoint, cacheRoot)
                )
                .lease
        val closeEntered = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        val daemon =
            RuntimeDaemon(
                cacheRoot,
                workspaceId,
                leaseStore,
                lease,
                AutoCloseable {
                    if (closeCalls.incrementAndGet() == 1) {
                        closeEntered.countDown()
                        check(finishClose.await(5, TimeUnit.SECONDS))
                    }
                },
                endpoint,
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val closing = executor.submit { daemon.close() }
            try {
                assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
                executor.submit { daemon.close() }.get(5, TimeUnit.SECONDS)
                assertEquals(
                    lease.startedAtMillis,
                    RuntimeLeaseStore.read(leasePath)?.startedAtMillis,
                    "An overlapping close must not release the lease before server shutdown",
                )
                assertEquals(1, closeCalls.get())
            } finally {
                finishClose.countDown()
                closing.get(5, TimeUnit.SECONDS)
            }
            assertNull(RuntimeLeaseStore.read(leasePath))

            val successor =
                assertIs<RuntimeLeaseAcquisition.Owned>(
                        RuntimeLeaseStore(cacheRoot, clockMillis = { 2_000L })
                            .acquire(workspaceId, endpoint, cacheRoot)
                    )
                    .lease
            daemon.close()
            // Even a stale release must distinguish successive leases from the same process.
            leaseStore.release(workspaceId, lease)
            assertEquals(1, closeCalls.get())
            assertEquals(
                successor.startedAtMillis,
                RuntimeLeaseStore.read(leasePath)?.startedAtMillis,
            )
        } finally {
            finishClose.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            daemon.close()
            cacheRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `daemon starts with a long cache path`() {
        val cacheRoot = Files.createTempDirectory("indexino-daemon-").resolve("x".repeat(120))
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-workspace-")
        val start =
            RuntimeDaemon.start(cacheRoot, "e".repeat(RuntimePaths.WORKSPACE_ID_LENGTH), workspace)
        try {
            assertTrue(Files.exists(assertIs<RuntimeDaemonStart.Owned>(start).daemon.endpoint))
        } finally {
            (start as? RuntimeDaemonStart.Owned)?.daemon?.close()
            cacheRoot.parent.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one daemon owns the lease and endpoint until explicit shutdown`() {
        val cacheRoot = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-")
        val workspace = Files.createTempDirectory(Path.of("/tmp"), "indexino-daemon-workspace-")
        val workspaceId = "d".repeat(RuntimePaths.WORKSPACE_ID_LENGTH)
        val first =
            RuntimeDaemon.start(cacheRoot, workspaceId, workspace) { payload ->
                byteArrayOf(payload[2], payload[1], payload[0])
            }
        try {
            val owner = assertIs<RuntimeDaemonStart.Owned>(first)
            assertTrue(Files.exists(owner.daemon.endpoint))
            RuntimeConnection.connect(owner.daemon.endpoint).use { client ->
                kotlin.test.assertEquals(
                    listOf<Byte>(3, 2, 1),
                    client.request(byteArrayOf(1, 2, 3)).toList(),
                )
            }
            val second = RuntimeDaemon.start(cacheRoot, workspaceId, workspace)
            val attached = assertIs<RuntimeDaemonStart.Existing>(second)
            assertTrue(attached.lease.ownerPid > 0)
        } finally {
            (first as? RuntimeDaemonStart.Owned)?.daemon?.close()
            cacheRoot.toFile().deleteRecursively()
            workspace.toFile().deleteRecursively()
        }
    }
}
