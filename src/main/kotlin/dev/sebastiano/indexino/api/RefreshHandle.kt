@file:Suppress("RedundantSuspendModifier")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexFailure
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

public class RefreshHandle
private constructor(
    public val id: RefreshId,
    private val resultFuture: CompletableFuture<RefreshResult>,
    private val terminalEvent: CompletableFuture<RefreshEvent>,
    private val stopAction: () -> Unit,
    @Suppress("UnusedParameter") marker: Unit,
) : AutoCloseable {
    @IndexinoInternalApi
    public constructor(
        id: RefreshId,
        result: RefreshResult,
    ) : this(
        id = id,
        resultFuture = CompletableFuture.completedFuture(result),
        terminalEvent = CompletableFuture.completedFuture(RefreshCompleted(id, result)),
        stopAction = {},
        marker = Unit,
    )

    public suspend fun await(): RefreshResult = awaitFuture(resultFuture)

    @OptIn(IndexinoInternalApi::class)
    public fun events(): Flow<RefreshEvent> = flow {
        emit(RefreshStarted(id))
        emit(awaitFuture(terminalEvent))
    }

    public suspend fun stop(): Unit = close()

    override fun close(): Unit = stopAction()

    private suspend fun <T> awaitFuture(future: CompletableFuture<T>): T =
        suspendCancellableCoroutine { continuation ->
            future.whenComplete { value, failure ->
                if (failure == null) {
                    continuation.resume(value) { _, _, _ -> }
                } else {
                    val unwrapped = (failure as? ExecutionException)?.cause ?: failure
                    continuation.resumeWith(Result.failure(unwrapped))
                }
            }
        }

    internal companion object {
        @OptIn(IndexinoInternalApi::class)
        internal fun inFlight(
            id: RefreshId,
            resultFuture: CompletableFuture<RefreshResult>,
            terminalEvent: CompletableFuture<RefreshEvent>,
            stopAction: () -> Unit,
        ): RefreshHandle = RefreshHandle(id, resultFuture, terminalEvent, stopAction, Unit)

        @OptIn(IndexinoInternalApi::class)
        internal fun failed(id: RefreshId, failure: IndexFailure): RefreshEvent =
            RefreshFailed(id, failure)
    }
}
