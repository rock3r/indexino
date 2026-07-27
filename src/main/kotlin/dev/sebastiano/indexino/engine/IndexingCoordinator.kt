package dev.sebastiano.indexino.engine

import dev.sebastiano.indexino.api.RefreshEvent
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
import java.util.concurrent.atomic.AtomicReference

internal class InFlightRefresh(internal val id: RefreshId, private val onStop: () -> Unit) {
    internal val result: CompletableFuture<RefreshResult> = CompletableFuture()
    internal val terminalEvent: CompletableFuture<RefreshEvent> = CompletableFuture()
    private val stopped = AtomicBoolean()
    private val worker = AtomicReference<Thread?>()

    internal fun bindWorker(thread: Thread) {
        worker.set(thread)
    }

    @OptIn(IndexinoInternalApi::class)
    internal fun stop() {
        if (stopped.compareAndSet(false, true)) {
            terminalEvent.complete(RefreshStopped(id, resumable = true))
            result.cancel(false)
            onStop()
            worker.get()?.interrupt()
        }
    }

    internal fun checkActive() {
        if (stopped.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Refresh was stopped")
        }
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
                InFlightRefresh(RefreshId.of(java.util.UUID.randomUUID().toString())) {
                    activeRefreshes.remove(key)
                }
            refreshExecutor.execute {
                operation.bindWorker(Thread.currentThread())
                try {
                    task(operation)
                } finally {
                    activeRefreshes.remove(key, operation)
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
