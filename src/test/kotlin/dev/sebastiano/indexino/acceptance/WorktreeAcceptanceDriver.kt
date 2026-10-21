// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.producer.JsonlIndexBuildProgressReporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
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

/** Destructive, test-only benchmark restricted to a marked disposable Git checkout. */
internal object WorktreeAcceptanceDriver {
    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 3) { "owned-root plan.json report.json" }
        val root = Path.of(args[0]).toRealPath()
        require(Files.isRegularFile(root.resolve(".indexino-benchmark-owned")))
        val workspace = root.resolve("workspace").toRealPath()
        require(workspace.startsWith(root))
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        val plan = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        require(plan.getValue("schema").jsonPrimitive.int == 1)
        val workload = IncrementalWorkload(workspace, plan)
        val repositories = BenchmarkRepositories(root, workspace, plan, workload)
        val worktrees = root.resolve("worktrees")
        require(!Files.exists(worktrees)) { "Private worktree directory already exists" }
        Files.createDirectory(worktrees)
        val same = worktrees.resolve("same")
        val diverged = worktrees.resolve("diverged")
        val samples = mutableListOf<JsonObject>()
        val report = linkedMapOf<String, JsonElement>("status" to JsonPrimitive("incomplete"))
        val previousCache = System.getProperty("indexino.cache.dir")
        var failure: Exception? = null
        fun checkpoint() {
            report["samples"] =
                JsonArray(
                    samples.map {
                        JsonObject(it + ("repositoryCount" to JsonPrimitive(repositories.count)))
                    }
                )
            Files.writeString(output, JsonObject(report).toString() + "\n")
        }
        try {
            repositories.detach()
            workload.write(workload.sources, 0)
            val seedCommit = repositories.commit(workspace)
            val request = request(plan)
            System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
            lateinit var originalIndex: Indexino
            val seedConnectNanos = measureNanoTime {
                originalIndex = Indexino.connect(configuration(workspace))
            }
            originalIndex.use { original ->
                val events = mutableListOf<JsonObject>()
                val seedNanos = measureNanoTime { refresh(original, request, events) }
                val pinned = original.snapshot()
                try {
                    val seedQueryNanos = measureNanoTime {
                        check(workload.matches(pinned, workload.sources, 0))
                    }
                    samples += buildJsonObject {
                        put("scenario", "newCheckoutSeed")
                        put("gitSetupNanos", 0)
                        put("connectNanos", seedConnectNanos)
                        put("refreshNanos", seedNanos)
                        put("queryNanos", seedQueryNanos)
                        put("queriesVerified", true)
                        put("phaseEvents", JsonArray(events))
                    }
                    checkpoint()
                    samples +=
                        siblingSample(
                            "sameCommit",
                            repositories,
                            same,
                            seedCommit,
                            request,
                            workload,
                            0,
                            events,
                        )
                    check(workload.matches(pinned, workload.sources, 0))
                    checkpoint()
                    samples += freshControl(same, request, workload, root.resolve("fresh-cache"))
                    checkpoint()

                    val setupStart = System.nanoTime()
                    repositories.add(diverged, seedCommit)
                    workload.sources.forEach {
                        Files.writeString(diverged.resolve(it.relative), it.content(1))
                    }
                    val divergedCommit = repositories.commit(diverged)
                    samples +=
                        readiness(
                            "divergedWorktree",
                            diverged,
                            request,
                            workload,
                            1,
                            System.nanoTime() - setupStart,
                        )
                    check(workload.matches(pinned, workload.sources, 0))
                    checkpoint()

                    samples +=
                        checkoutSample(
                            "branchSwitch",
                            workspace,
                            repositories,
                            divergedCommit,
                            request,
                            workload,
                            1,
                        )
                    check(workload.matches(pinned, workload.sources, 0)) {
                        "Pinned V0 snapshot changed"
                    }
                    checkpoint()
                    samples +=
                        checkoutSample(
                            "switchBack",
                            workspace,
                            repositories,
                            seedCommit,
                            request,
                            workload,
                            0,
                        )
                    check(workload.matches(pinned, workload.sources, 0)) {
                        "Pinned V0 snapshot changed"
                    }
                    report["pinnedVerified"] = JsonPrimitive(true)
                } finally {
                    pinned.close()
                }
            }
            report["status"] = JsonPrimitive("passed")
        } catch (error: Exception) {
            report["failureType"] = JsonPrimitive(error.javaClass.simpleName)
            failure = error
        } finally {
            try {
                repositories.close()
                report["cleanupVerified"] = JsonPrimitive(true)
            } catch (error: Exception) {
                report["status"] = JsonPrimitive("incomplete")
                report["cleanupFailureType"] = JsonPrimitive(error.javaClass.simpleName)
                failure?.addSuppressed(error)
                if (failure == null) failure = error
            } finally {
                if (previousCache == null) System.clearProperty("indexino.cache.dir")
                else System.setProperty("indexino.cache.dir", previousCache)
                report["schema"] = JsonPrimitive(1)
                report["timing"] =
                    JsonPrimitive(
                        "Git checkout, connect, refresh and query measured separately; instrumented"
                    )
                checkpoint()
            }
        }
        failure?.let { throw it }
    }

    private fun configuration(workspace: Path) =
        IndexinoConfiguration.forWorkspace(workspace)
            .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
            .withAutoRefresh(AutoRefreshMode.DISABLED)

    private fun request(plan: JsonObject): RefreshRequest {
        val base =
            when (plan.getValue("buildSystem").jsonPrimitive.content) {
                "gradle" -> IndexScope.gradle(plan.getValue("target").jsonPrimitive.content)
                "bazel" -> IndexScope.bazel(plan.getValue("target").jsonPrimitive.content)
                else -> error("Unsupported build system")
            }
        return RefreshRequest.forScope(base.includingDependencies())
    }

    private suspend fun siblingSample(
        scenario: String,
        repositories: BenchmarkRepositories,
        sibling: Path,
        commit: Map<String, String>,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        version: Int,
        seedEvents: List<JsonObject>,
    ): JsonObject {
        val start = System.nanoTime()
        repositories.add(sibling, commit)
        return readiness(
            scenario,
            sibling,
            request,
            workload,
            version,
            System.nanoTime() - start,
            seedEvents,
        )
    }

    private suspend fun freshControl(
        workspace: Path,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        cache: Path,
    ): JsonObject {
        val shared = System.getProperty("indexino.cache.dir")
        return try {
            System.setProperty("indexino.cache.dir", cache.toString())
            readiness("freshCacheControl", workspace, request, workload, 0, 0)
        } finally {
            System.setProperty("indexino.cache.dir", shared)
        }
    }

    private suspend fun checkoutSample(
        scenario: String,
        workspace: Path,
        repositories: BenchmarkRepositories,
        revision: Map<String, String>,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        version: Int,
    ): JsonObject {
        val start = System.nanoTime()
        repositories.checkout(revision)
        return readiness(scenario, workspace, request, workload, version, System.nanoTime() - start)
    }

    private suspend fun readiness(
        scenario: String,
        workspace: Path,
        request: RefreshRequest,
        workload: IncrementalWorkload,
        version: Int,
        gitSetupNanos: Long,
        priorEvents: List<JsonObject> = emptyList(),
    ): JsonObject {
        val events = mutableListOf<JsonObject>()
        lateinit var index: Indexino
        val connectNanos = measureNanoTime { index = Indexino.connect(configuration(workspace)) }
        index.use {
            val refreshNanos = measureNanoTime { refresh(it, request, events) }
            val queryNanos = measureNanoTime {
                it.snapshot().use { snapshot ->
                    check(workload.matches(snapshot, workload.sources, version))
                }
            }
            return buildJsonObject {
                put("scenario", scenario)
                put("gitSetupNanos", gitSetupNanos)
                put("connectNanos", connectNanos)
                put("refreshNanos", refreshNanos)
                put("queryNanos", queryNanos)
                put("queriesVerified", true)
                put("phaseEvents", JsonArray(events))
                if (priorEvents.isNotEmpty()) put("seedPhaseEvents", JsonArray(priorEvents))
            }
        }
    }

    private suspend fun refresh(
        index: Indexino,
        request: RefreshRequest,
        events: MutableList<JsonObject>,
    ) {
        val reporter = JsonlIndexBuildProgressReporter { line ->
            events += Json.parseToJsonElement(line).jsonObject
        }
        index.refresh(request, {}, reporter).await()
    }
}
