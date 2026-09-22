// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Invented caller fixture. No corpus is modified and all query IDs come from the current pin. */
internal object CallLifecycleAcceptanceDriver {
    private const val SOURCE = "src/main/kotlin/fixture/calls/"
    private val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 3) { "new-disposable-root manual|watcher report.json" }
        val root = Path.of(args[0]).toAbsolutePath()
        require(!Files.exists(root))
        val watcher =
            when (args[1]) {
                "manual" -> false
                "watcher" -> true
                else -> error("Unknown lane")
            }
        val workspace = root.resolve("workspace")
        Files.createDirectories(workspace.resolve(SOURCE))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"invented-calls\"\n",
        )
        Files.writeString(
            workspace.resolve(SOURCE + "Target.kt"),
            "package fixture.calls\nfun ping() = Unit\n",
        )
        resetCaller(workspace)
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        val configuration =
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(
                    if (watcher) RuntimeAttachMode.PREFER_DAEMON else RuntimeAttachMode.IN_PROCESS
                )
                .withAutoRefresh(if (watcher) AutoRefreshMode.ENABLED else AutoRefreshMode.DISABLED)
        val stages = mutableListOf<JsonObject>()
        val timings =
            linkedMapOf("references" to mutableListOf<Long>(), "callers" to mutableListOf<Long>())
        var status = "incomplete"
        var failure: String? = null
        try {
            Indexino.connect(configuration).use { index ->
                try {
                    index.refresh(request).await()
                    index.snapshot().use { snapshot ->
                        repeat(10) { assertRows(snapshot, listOf(SOURCE + "Caller.kt:3")) }
                        repeat(100) {
                            assertRows(snapshot, listOf(SOURCE + "Caller.kt:3"), timings)
                        }
                    }
                    repeat(5) { iteration ->
                        runStages(index, workspace, watcher, iteration, stages)
                    }
                    status = "passed"
                } finally {
                    if (watcher) index.shutdownRuntime()
                }
            }
        } catch (error: Exception) {
            failure =
                "${error.javaClass.simpleName}: ${error.message?.lineSequence()?.firstOrNull()}"
            throw error
        } finally {
            Files.writeString(
                Path.of(args[2]),
                buildJsonObject {
                        put("schema", 1)
                        put("status", status)
                        put("lane", "calls-${args[1]}")
                        failure?.let { put("failure", it) }
                        put("stages", JsonArray(stages))
                        put(
                            "queries",
                            JsonArray(
                                timings.map { (kind, values) ->
                                    buildJsonObject {
                                        put("id", kind)
                                        put("apiNanos", JsonArray(values.map(::JsonPrimitive)))
                                    }
                                }
                            ),
                        )
                    }
                    .toString() + "\n",
            )
        }
    }

    private suspend fun runStages(
        index: Indexino,
        workspace: Path,
        watcher: Boolean,
        iteration: Int,
        stages: MutableList<JsonObject>,
    ) {
        var previousRows = listOf(SOURCE + "Caller.kt:3")
        val expected =
            listOf(
                listOf(SOURCE + "Caller.kt:4"),
                listOf(SOURCE + "Caller.kt:4", SOURCE + "Another.kt:3"),
                listOf(SOURCE + "Moved.kt:4", SOURCE + "Another.kt:3"),
                listOf(SOURCE + "Another.kt:3"),
                emptyList(),
            )
        for ((ordinal, rows) in expected.withIndex()) {
            index.snapshot().use { previous ->
                assertRows(previous, previousRows)
                mutate(workspace, ordinal)
                val elapsed = measureNanoTime {
                    if (watcher) {
                        withTimeout(30_000) {
                            while (true) {
                                val updated =
                                    index.snapshot().use { current ->
                                        if (current.generation == previous.generation) false
                                        else {
                                            assertRows(current, rows)
                                            true
                                        }
                                    }
                                if (updated) break
                                delay(50)
                            }
                        }
                    } else {
                        index.snapshot().use { unchanged ->
                            check(unchanged.generation == previous.generation)
                            assertRows(unchanged, previousRows)
                        }
                        check(index.refresh(request).await().generation != previous.generation)
                        index.snapshot().use { assertRows(it, rows) }
                    }
                }
                assertRows(previous, previousRows)
                stages += buildJsonObject {
                    put("iteration", iteration)
                    put(
                        "operation",
                        listOf("edit", "add", "rename", "delete", "delete-last")[ordinal],
                    )
                    put("expected", JsonArray(rows.map(::JsonPrimitive)))
                    put("refreshOrWatcherNanos", elapsed)
                }
                previousRows = rows
            }
        }
        resetCaller(workspace)
        index.refresh(request).await()
    }

    private suspend fun assertRows(
        snapshot: IndexSnapshot,
        expected: List<String>,
        timings: Map<String, MutableList<Long>>? = null,
    ) {
        val target =
            collectPages {
                    snapshot.findSymbols(
                        SymbolQuery.named("fixture.calls.ping").withMatch(NameMatchMode.FQN),
                        it,
                    )
                }
                .single()
        check(target.location.file.path == SOURCE + "Target.kt" && target.location.line == 2)
        val callerIds = expected.associate { row ->
            val file = row.substringBeforeLast(':')
            val name = if (file == SOURCE + "Another.kt") "another" else "caller"
            val caller =
                collectPages {
                        snapshot.findSymbols(
                            SymbolQuery.named("fixture.calls.$name").withMatch(NameMatchMode.FQN),
                            it,
                        )
                    }
                    .single()
            check(caller.location.file.path == file && caller.location.line == 2)
            file to caller.id
        }
        val references = mutableListOf<String>()
        val calls = mutableListOf<String>()
        val referenceNanos = measureNanoTime {
            references +=
                collectPages { snapshot.findReferences(ReferenceQuery.to(target.id), it) }
                    .map { "${it.location.file.path}:${it.location.line}" }
        }
        val callerNanos = measureNanoTime {
            calls +=
                collectPages { snapshot.findCalls(CallQuery.to("ping"), it) }
                    .map {
                        check(it.candidateSymbolIds == listOf(target.id)) {
                            "Wrong callee candidates: ${it.candidateSymbolIds}"
                        }
                        check(
                            it.enclosingSymbolId == callerIds.getValue(it.range.start.file.path)
                        ) {
                            "Wrong generation-local caller identity: ${it.enclosingSymbolId}"
                        }
                        "${it.range.start.file.path}:${it.range.start.line}"
                    }
        }
        assertCallMutationRows(expected, references, calls)
        timings?.getValue("references")?.add(referenceNanos)
        timings?.getValue("callers")?.add(callerNanos)
    }

    private fun resetCaller(workspace: Path) {
        Files.writeString(
            workspace.resolve(SOURCE + "Caller.kt"),
            "package fixture.calls\nfun caller() {\n    ping()\n}\n",
        )
    }

    private fun mutate(workspace: Path, step: Int) {
        fun path(name: String) = workspace.resolve(SOURCE + name)
        when (step) {
            0 ->
                Files.writeString(
                    path("Caller.kt"),
                    "package fixture.calls\nfun caller() {\n    // moved call\n    ping()\n}\n",
                )
            1 ->
                Files.writeString(
                    path("Another.kt"),
                    "package fixture.calls\nfun another() {\n    ping()\n}\n",
                )
            2 -> Files.move(path("Caller.kt"), path("Moved.kt"))
            3 -> Files.delete(path("Moved.kt"))
            4 -> Files.delete(path("Another.kt"))
            else -> error("Unknown mutation")
        }
    }
}
