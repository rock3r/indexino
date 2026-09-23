// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Opt-in controlled edits in an explicitly owned disposable checkout. */
internal object IncrementalAcceptanceDriver {
    private val watcherWait = 60.minutes

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 5) { "owned-root plan.json manual|watcher repeats report.json" }
        val root = Path.of(args[0]).toRealPath()
        require(Files.isRegularFile(root.resolve(".indexino-benchmark-owned")))
        val workspace = root.resolve("workspace").toRealPath()
        require(workspace.startsWith(root))
        val plan = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        require(plan.getValue("schema").jsonPrimitive.int == 1)
        require(args[2] in setOf("manual", "watcher"))
        val watcher = args[2] == "watcher"
        val repeats = args[3].toInt().also { require(it in 1..10) }
        val output = Path.of(args[4]).toAbsolutePath().normalize()
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        val workload = IncrementalWorkload(workspace, plan)
        val samples = mutableListOf<JsonObject>()
        val report = linkedMapOf<String, JsonElement>("status" to JsonPrimitive("incomplete"))
        if (watcher) report["watcherWaitMillis"] = JsonPrimitive(watcherWait.inWholeMilliseconds)
        fun checkpoint() {
            report["samples"] = JsonArray(samples)
            Files.writeString(output, JsonObject(report).toString() + "\n")
        }
        val previousCache = System.getProperty("indexino.cache.dir")
        try {
            System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
            val request = request(plan)
            workload.write(workload.sources, 0)
            val configuration =
                IndexinoConfiguration.forWorkspace(workspace)
                    .withRuntimeAttach(
                        if (watcher) RuntimeAttachMode.PREFER_DAEMON
                        else RuntimeAttachMode.IN_PROCESS
                    )
                    .withAutoRefresh(
                        if (watcher) AutoRefreshMode.ENABLED else AutoRefreshMode.DISABLED
                    )
            Indexino.connect(configuration).use { index ->
                try {
                    val seedStart = System.nanoTime()
                    refreshAndLog(index, request)
                    index.snapshot().use { check(workload.matches(it, workload.sources, 0)) }
                    report["seedReadyNanos"] = JsonPrimitive(System.nanoTime() - seedStart)
                    checkpoint()
                    repeat(repeats) { repetition ->
                        for (group in workload.groups) {
                            samples += measure(index, request, workload, group, watcher, repetition)
                            checkpoint()
                            workload.write(group.sources, 0)
                            if (watcher) {
                                awaitRestoredBaseline(
                                    watcherWait,
                                    { refreshAndLog(index, request) },
                                ) {
                                    index.snapshot().use {
                                        workload.matches(it, workload.sources, 0)
                                    }
                                }
                            } else {
                                refreshAndLog(index, request)
                                index.snapshot().use {
                                    check(workload.matches(it, workload.sources, 0))
                                }
                            }
                        }
                    }
                } finally {
                    if (watcher) index.shutdownRuntime()
                }
            }
            report["status"] = JsonPrimitive("passed")
        } catch (error: Exception) {
            report["failureType"] = JsonPrimitive(error.javaClass.simpleName)
            if (error is IndexinoException) {
                report["failureCode"] = JsonPrimitive(error.failure.code)
                report["failureCategory"] = JsonPrimitive(error.failure.category.value)
            }
            throw error
        } finally {
            try {
                workload.restoreOriginals()
            } finally {
                if (previousCache == null) System.clearProperty("indexino.cache.dir")
                else System.setProperty("indexino.cache.dir", previousCache)
                report["schema"] = JsonPrimitive(1)
                report["lane"] = JsonPrimitive(args[2])
                report["workload"] =
                    JsonPrimitive(
                        "controlled declaration rename and method arity change in existing source files"
                    )
                report["timing"] =
                    JsonPrimitive(
                        "readyNanos starts after final write and ends after correct public queries; " +
                            "totalNanos includes writes"
                    )
                checkpoint()
            }
        }
    }

    private suspend fun refreshAndLog(index: Indexino, request: RefreshRequest) =
        index.refresh(request, { System.err.println(it) }, null).await()

    internal suspend fun awaitRestoredBaseline(
        timeout: Duration,
        refresh: suspend () -> Unit,
        matches: suspend () -> Boolean,
    ) {
        withTimeout(timeout) {
            while (true) {
                // An explicit refresh can join the watcher-triggered V1 refresh that was still
                // publishing when we restored V0. Awaiting that handle alone is not a V0 barrier.
                refresh()
                if (matches()) break
                delay(50)
            }
        }
    }

    private fun request(plan: JsonObject): RefreshRequest {
        val target = plan.getValue("target").jsonPrimitive.content
        val scope =
            when (plan.getValue("buildSystem").jsonPrimitive.content) {
                "gradle" -> IndexScope.gradle(target)
                "bazel" -> IndexScope.bazel(target)
                else -> error("Unsupported build system")
            }
        return RefreshRequest.forScope(scope.includingDependencies())
    }

    private suspend fun measure(
        index: Indexino,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        group: IncrementalWorkload.Group,
        watcher: Boolean,
        repetition: Int,
    ): JsonObject =
        index.snapshot().use { previous ->
            check(workload.matches(previous, workload.sources, 0))
            val start = System.nanoTime()
            workload.write(group.sources, 1)
            val written = System.nanoTime()
            if (watcher) {
                withTimeout(watcherWait) {
                    while (true) {
                        val ready =
                            index.snapshot().use {
                                it.generation != previous.generation &&
                                    workload.matches(it, group.sources, 1)
                            }
                        if (ready) break
                        delay(50)
                    }
                }
            } else {
                val result = refreshAndLog(index, request)
                check(result.changes.changedFileCount == group.sources.size)
                check(result.changes.removedFileCount == 0)
                index.snapshot().use {
                    check(
                        it.generation != previous.generation &&
                            workload.matches(it, group.sources, 1)
                    )
                }
            }
            val ready = System.nanoTime()
            check(workload.matches(previous, workload.sources, 0)) { "Pinned snapshot changed" }
            val unchanged = workload.sources.filter { it !in group.sources }
            index.snapshot().use { check(workload.matches(it, unchanged, 0)) }
            buildJsonObject {
                put("scenario", group.id)
                put("repeat", repetition)
                put("changedFiles", group.sources.size)
                put("changedModules", group.sources.map { it.module }.distinct().size)
                put("writeNanos", written - start)
                put("readyNanos", ready - written)
                put("totalNanos", ready - start)
                put("pinnedVerified", true)
                put("queriesVerified", true)
            }
        }
}
