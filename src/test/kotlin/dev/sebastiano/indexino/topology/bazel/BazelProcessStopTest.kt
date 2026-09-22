package dev.sebastiano.indexino.topology.bazel

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RefreshStopped
import dev.sebastiano.indexino.engine.IndexingCoordinator
import dev.sebastiano.indexino.model.IndexinoInternalApi
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

@OptIn(IndexinoInternalApi::class)
class BazelProcessStopTest {
    @TempDir lateinit var workspace: Path

    @Test
    fun `stopped terminal follows direct client exit even while its output pipe is open`() {
        val marker = workspace.resolve("owned.pid")
        val unrelatedMarker = workspace.resolve("unrelated.pid")
        val unrelated = ProcessBuilder(command("open-output", unrelatedMarker)).start()
        val cleaned = AtomicBoolean()
        val published = AtomicBoolean()
        val operation =
            IndexingCoordinator.start(
                workspace,
                RefreshRequest.forScope(IndexScope.bazel("//invented:target")),
            ) { refresh ->
                try {
                    LiveBazelProcessRunner.runCommand(command("open-output", marker), workspace)
                    refresh.publishIfActive { published.set(true) }
                } catch (_: InterruptedException) {
                    // Explicit stop, not successful topology discovery.
                } finally {
                    cleaned.set(true)
                }
            }
        var owned: ProcessHandle? = null
        try {
            owned = awaitProcess(marker)
            awaitProcess(unrelatedMarker)
            operation.stop()
            assertIs<RefreshStopped>(operation.terminalEvent.get(8, TimeUnit.SECONDS))
            assertFalse(owned.isAlive, "Stopped must not precede owned client termination")
            assertTrue(cleaned.get(), "Stopped must follow the worker's finally block")
            assertFalse(published.get())
            assertTrue(unrelated.isAlive, "Cleanup must not target unrelated processes")
        } finally {
            owned?.let(::terminateFixture)
            terminateFixture(unrelated.toHandle())
        }
    }

    @Test
    fun `interrupted process wait reaps client and restores interrupt without losing output`() {
        val output = LiveBazelProcessRunner.runCommand(command("output"), workspace)
        assertEquals(7, output.exitCode)
        assertEquals(12001, output.lines.size)
        assertEquals("//invented:Source0.kt", output.lines.first())
        assertEquals("diagnostic", output.lines.last())

        val marker = workspace.resolve("closed.pid")
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val worker = Thread {
            try {
                LiveBazelProcessRunner.runCommand(command("closed-output", marker), workspace)
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        worker.start()
        var owned: ProcessHandle? = null
        try {
            owned = awaitProcess(marker)
            worker.interrupt()
            worker.join(8000)
            assertFalse(worker.isAlive)
            assertFalse(owned.isAlive, "An interrupted wait must not leak its child")
            assertIs<InterruptedException>(failure.get())
            assertTrue(interrupted.get())
        } finally {
            owned?.let(::terminateFixture)
            worker.join(5000)
        }
    }

    @Test
    fun `command deadline terminates its direct client before reporting timeout`() {
        val marker = workspace.resolve("deadline.pid")
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                LiveBazelProcessRunner.runCommand(
                    command("open-output", marker),
                    workspace,
                    timeoutMillis = 3000,
                )
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        worker.start()
        var owned: ProcessHandle? = null
        try {
            owned = awaitProcess(marker)
            worker.join(6500)
            assertFalse(worker.isAlive, "The availability deadline must bound the command wait")
            assertIs<TimeoutException>(failure.get())
            assertFalse(owned.isAlive)
        } finally {
            worker.interrupt()
            owned?.let(::terminateFixture)
            worker.join(5000)
        }
    }

    @Test
    fun `availability interruption propagates instead of reporting unavailable`() {
        val marker = workspace.resolve("probe.pid")
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                BazelTopology.isBazelAvailable(workspace, command("closed-output", marker))
            } catch (thrown: Throwable) {
                failure.set(thrown)
            }
        }
        worker.start()
        var owned: ProcessHandle? = null
        try {
            owned = awaitProcess(marker)
            worker.interrupt()
            worker.join(8000)
            assertFalse(worker.isAlive)
            assertIs<InterruptedException>(failure.get(), "Stop is not an unavailable tool")
            assertFalse(owned.isAlive)
        } finally {
            owned?.let(::terminateFixture)
            worker.join(5000)
        }
    }

    @Test
    fun `exhausted cleanup reports its own type and retains the original interruption`() {
        val process = UnstoppableProcess()
        val original = InterruptedException("invented stop")
        Thread.currentThread().interrupt()
        try {
            val failure =
                assertFailsWith<BazelClientCleanupException> {
                    LiveBazelProcessRunner.terminateClient(process, original)
                }
            assertSame(original, failure.cause)
            assertEquals(1, process.gracefulAttempts)
            assertEquals(1, process.forcedAttempts)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interrupted query cannot succeed or launch fallback discovery`() {
        for (exitCode in listOf(0, 1)) {
            var calls = 0
            try {
                assertFailsWith<InterruptedException> {
                    BazelTopology.queryWithFallback(
                        target = "//invented:target",
                        workspace = workspace,
                        runner =
                            BazelProcessRunner { _, _ ->
                                calls++
                                Thread.currentThread().interrupt()
                                BazelQueryOutcome(exitCode, listOf("//invented:Source.kt"))
                            },
                        onStderr = {},
                    )
                }
                assertEquals(1, calls, "Interrupted discovery must never retry")
            } finally {
                Thread.interrupted()
            }
        }
    }

    private fun command(
        mode: String,
        marker: Path = workspace.resolve("unused.pid"),
    ): List<String> {
        val executable =
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val classes =
            Path.of(BazelProcessFixture::class.java.protectionDomain.codeSource.location.toURI())
        return listOf(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-cp",
            classes.toString(),
            BazelProcessFixture::class.java.name,
            mode,
            marker.toString(),
        )
    }

    private fun awaitProcess(marker: Path): ProcessHandle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val pid = if (Files.exists(marker)) Files.readString(marker).toLongOrNull() else null
            if (pid != null) return ProcessHandle.of(pid).orElseThrow()
            Thread.sleep(10)
        }
        error("Fixture did not publish its process ID: $marker")
    }

    private fun terminateFixture(process: ProcessHandle) {
        if (process.isAlive) process.destroyForcibly()
        process.onExit().get(5, TimeUnit.SECONDS)
    }

    private class UnstoppableProcess : Process() {
        var gracefulAttempts = 0
        var forcedAttempts = 0

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun isAlive(): Boolean = true

        override fun waitFor(): Int = error("Only bounded waits are permitted")

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            Thread.sleep(minOf(10, unit.toMillis(timeout).coerceAtLeast(1)))
            return false
        }

        override fun exitValue(): Int = throw IllegalThreadStateException("Invented live process")

        override fun destroy() {
            gracefulAttempts++
        }

        override fun destroyForcibly(): Process {
            forcedAttempts++
            return this
        }
    }
}
