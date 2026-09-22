package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.RefreshEvent
import dev.sebastiano.indexino.api.RefreshFailed
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RefreshResult
import dev.sebastiano.indexino.api.RefreshStopped
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class InFlightRefresh(
    internal val id: RefreshId,
    private val onFinished: (InFlightRefresh) -> Unit,
) {
    internal val result: CompletableFuture<RefreshResult> = CompletableFuture()
    internal val terminalEvent: CompletableFuture<RefreshEvent> = CompletableFuture()
    private val stopped = AtomicBoolean()
    private val stateLock = Any()
    private var worker: Thread? = null
    private var finished = false
    private var cleanupFailure: IndexinoException? = null

    internal fun bindWorker(thread: Thread) {
        synchronized(stateLock) {
            worker = thread
            if (stopped.get()) thread.interrupt()
        }
    }

    internal fun stop() {
        synchronized(stateLock) {
            if (finished || result.isDone || terminalEvent.isDone) return
            if (stopped.compareAndSet(false, true)) worker?.interrupt()
        }
    }

    internal fun failAfterCleanup(failure: IndexinoException) {
        synchronized(stateLock) { cleanupFailure = failure }
    }

    @OptIn(IndexinoInternalApi::class)
    internal fun workerFinished() {
        val failure =
            synchronized(stateLock) {
                worker = null
                finished = true
                cleanupFailure
            }
        onFinished(this)
        if (failure != null) {
            result.completeExceptionally(failure)
            terminalEvent.complete(RefreshFailed(id, failure.failure))
        } else if (stopped.get()) {
            result.cancel(false)
            terminalEvent.complete(RefreshStopped(id, resumable = true))
        }
    }

    internal fun checkActive() {
        if (stopped.get()) {
            throw CancellationException("Refresh was stopped")
        }
    }

    internal fun <T> publishIfActive(action: () -> T): T =
        synchronized(stateLock) {
            checkActive()
            action()
        }

    internal fun isStopped(): Boolean = stopped.get()
}

internal object IndexingCoordinator {
    private data class RefreshKey(val workspace: Path, val request: RefreshRequest)

    private val workspaceRefreshLocks = ConcurrentHashMap<Path, Any>()
    private val activeRefreshes = ConcurrentHashMap<RefreshKey, InFlightRefresh>()
    private val refreshExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "indexino-refresh").apply { isDaemon = true }
    }

    internal fun refreshLockFor(workspace: Path): Any =
        workspaceRefreshLocks.computeIfAbsent(workspace) { Any() }

    internal fun start(
        workspace: Path,
        request: RefreshRequest,
        task: (InFlightRefresh) -> Unit,
    ): InFlightRefresh =
        activeRefreshes.computeIfAbsent(RefreshKey(workspace, request)) { key ->
            val operation =
                InFlightRefresh(RefreshId.of(java.util.UUID.randomUUID().toString())) { operation ->
                    activeRefreshes.remove(key, operation)
                }
            refreshExecutor.execute {
                operation.bindWorker(Thread.currentThread())
                try {
                    if (!operation.isStopped()) task(operation)
                } finally {
                    operation.workerFinished()
                }
            }
            operation
        }

    internal fun active(workspace: Path): List<Pair<RefreshRequest, InFlightRefresh>> =
        activeRefreshes.entries
            .asSequence()
            .filter { (key, _) -> key.workspace == workspace }
            .map { (key, operation) -> key.request to operation }
            .toList()
}
