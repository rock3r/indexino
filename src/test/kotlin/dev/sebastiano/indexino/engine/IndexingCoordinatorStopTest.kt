package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.RefreshFailed
import dev.sebastiano.indexino.api.RefreshHandle
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RefreshStopped
import dev.sebastiano.indexino.api.indexinoFailure
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

@OptIn(IndexinoInternalApi::class)
class IndexingCoordinatorStopTest {
    @TempDir lateinit var workspace: Path

    @Test
    fun `stop before worker binding interrupts the subsequently bound worker`() {
        val operation = InFlightRefresh(RefreshId.of("pre-bind")) {}
        val interrupted = AtomicBoolean()
        operation.stop()
        val worker = Thread {
            operation.bindWorker(Thread.currentThread())
            interrupted.set(Thread.interrupted())
        }
        worker.start()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(interrupted.get(), "A stop before binding must not lose its interrupt")
    }

    @Test
    fun `stopped refresh stays shared and nonterminal until worker cleanup finishes`() {
        val entered = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleaned = AtomicBoolean()
        val request = RefreshRequest.forScope(IndexScope.gradle(":invented"))
        val operation =
            IndexingCoordinator.start(workspace, request) {
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    // Cleanup deliberately remains active after the stop request.
                } finally {
                    cleanupEntered.countDown()
                    releaseCleanup.await(10, TimeUnit.SECONDS)
                    cleaned.set(true)
                }
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val handle =
                RefreshHandle.inFlight(
                    operation.id,
                    operation.result,
                    operation.terminalEvent,
                    operation::stop,
                )
            runBlocking {
                val waiter = launch(start = CoroutineStart.UNDISPATCHED) { handle.await() }
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) { handle.events().collect() }
                waiter.cancelAndJoin()
                collector.cancelAndJoin()
            }
            assertFalse(operation.isStopped(), "Observer cancellation must not stop shared work")
            assertFalse(operation.result.isDone)
            operation.stop()
            operation.stop()
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertFalse(operation.terminalEvent.isDone, "Stopped is a cleanup-complete event")
            assertFalse(operation.result.isDone)
            assertSame(operation, IndexingCoordinator.active(workspace).single().second)
            assertSame(operation, IndexingCoordinator.start(workspace, request) {})
            releaseCleanup.countDown()
            assertTrue(operation.terminalEvent.get(5, TimeUnit.SECONDS) is RefreshStopped)
            assertTrue(cleaned.get())
            assertTrue(operation.result.isCancelled)
            assertTrue(IndexingCoordinator.active(workspace).isEmpty())
        } finally {
            releaseCleanup.countDown()
        }
    }

    @Test
    fun `cleanup failure after stop is reported only after worker unwinds and never as stopped`() {
        val entered = CountDownLatch(1)
        val staged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure =
            indexinoFailure(
                IndexFailureCategory.INTERNAL,
                "client_cleanup_failed",
                "Client exit could not be confirmed",
                retryable = false,
            )
        val operation =
            IndexingCoordinator.start(
                workspace,
                RefreshRequest.forScope(IndexScope.bazel("//invented:cleanup")),
            ) { refresh ->
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    refresh.failAfterCleanup(failure)
                    staged.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            operation.stop()
            assertTrue(staged.await(5, TimeUnit.SECONDS))
            assertFalse(operation.terminalEvent.isDone)
            assertFalse(operation.result.isDone)
            release.countDown()
            val event = assertIs<RefreshFailed>(operation.terminalEvent.get(5, TimeUnit.SECONDS))
            assertSame(failure.failure, event.failure)
            val thrown =
                assertFailsWith<ExecutionException> { operation.result.get(5, TimeUnit.SECONDS) }
            assertSame(failure, thrown.cause)
            assertFalse(operation.result.isCancelled)
            assertTrue(IndexingCoordinator.active(workspace).isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `publication and stop have one ordered boundary`() {
        val stopped = InFlightRefresh(RefreshId.of("stop-first")) {}
        stopped.stop()
        val publishedAfterStop = AtomicBoolean()
        assertFailsWith<CancellationException> {
            stopped.publishIfActive { publishedAfterStop.set(true) }
        }
        assertFalse(publishedAfterStop.get())

        val operation = InFlightRefresh(RefreshId.of("publish-first")) {}
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptedStop = CountDownLatch(1)
        val returnedStop = CountDownLatch(1)
        val published = AtomicBoolean()
        val publisher = Thread {
            operation.publishIfActive {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                published.set(true)
            }
        }
        val stopper = Thread {
            attemptedStop.countDown()
            operation.stop()
            returnedStop.countDown()
        }
        publisher.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            stopper.start()
            assertTrue(attemptedStop.await(5, TimeUnit.SECONDS))
            assertFalse(
                returnedStop.await(250, TimeUnit.MILLISECONDS),
                "Stop must not return while an entered publication can still mutate state",
            )
            release.countDown()
            assertTrue(returnedStop.await(5, TimeUnit.SECONDS))
            assertTrue(published.get())
        } finally {
            release.countDown()
            publisher.join(5000)
            stopper.join(5000)
        }
    }
}
