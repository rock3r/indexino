package dev.sebastiano.indexino.script

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.CallSite
import dev.sebastiano.indexino.model.CallSiteId
import dev.sebastiano.indexino.model.ExperimentalIndexinoApi
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/** The non-suspending, snapshot-bound receiver exposed to trusted scripts. */
@ExperimentalIndexinoApi
@OptIn(IndexinoInternalApi::class)
public class IndexinoScriptContext
@IndexinoInternalApi
public constructor(public val snapshot: IndexSnapshot) {
    private val collectedFindings = CopyOnWriteArrayList<ScriptFinding>()
    private val activeFindings = ThreadLocal<MutableList<ScriptFinding>?>()

    public val calls: ScriptCallQueries = ScriptCallQueries(snapshot)

    public val context: IndexinoScriptContext
        get() = this

    public fun report(finding: ScriptFinding) {
        activeFindings.get()?.add(finding) ?: collectedFindings.add(finding)
    }

    public fun report(builder: ScriptFinding.Builder) {
        report(builder.build())
    }

    public fun <T> managedParallel(inputs: Iterable<T>, transform: (T) -> Unit) {
        val work = inputs.toList()
        if (work.size < 2) {
            work.forEach(transform)
            return
        }
        val destination = activeFindings.get() ?: collectedFindings
        Executors.newFixedThreadPool(MAX_PARALLELISM, Thread.ofVirtual().factory()).use { executor
            ->
            executor
                .invokeAll(
                    work.map { input ->
                        Callable {
                            val findings = mutableListOf<ScriptFinding>()
                            activeFindings.set(findings)
                            try {
                                transform(input)
                                findings
                            } finally {
                                activeFindings.remove()
                            }
                        }
                    }
                )
                .forEach { future ->
                    try {
                        destination.addAll(future.get())
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                }
        }
    }

    internal fun findings(): List<ScriptFinding> = collectedFindings.toList()

    private companion object {
        private const val MAX_PARALLELISM = 8
    }
}

/** Blocking call-site query helpers for the script receiver. */
@ExperimentalIndexinoApi
@OptIn(IndexinoInternalApi::class)
public class ScriptCallQueries
@IndexinoInternalApi
public constructor(private val snapshot: IndexSnapshot) {
    public fun byId(id: CallSiteId): CallSite? =
        find(CallQuery.byId(id), QueryOptions.page(1)).items.singleOrNull()

    public fun find(query: CallQuery, options: QueryOptions): QueryPage<CallSite> = runBlocking {
        snapshot.findCalls(query, options)
    }
}
