// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.core.cache.WorktreeOverlayPolicy
import dev.sebastiano.indexino.model.IndexinoInternalApi
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes
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

/**
 * Opt-in, destructive only inside a marked disposable checkout; reports successive public edits.
 */
@OptIn(IndexinoInternalApi::class)
internal object CumulativeOverlayCorpusDriver {
    @JvmStatic
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 5) { "owned-root plan.json report.json edit-count seed-wait-minutes" }
        val root = Path.of(args[0]).toRealPath()
        require(Files.isRegularFile(root.resolve(".indexino-benchmark-owned")))
        val workspace = root.resolve("workspace").toRealPath()
        require(workspace.startsWith(root))
        val cacheRoot = root.resolve("cache")
        require(!Files.exists(cacheRoot)) {
            "Cumulative experiment requires an empty private cache"
        }
        val output = Path.of(args[2]).toAbsolutePath().normalize()
        require(!output.startsWith(root) && Files.isDirectory(output.parent))
        val plan = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        require(plan.getValue("schema").jsonPrimitive.int == 1)
        val count = args[3].toInt().also { require(it in 2..30) }
        val seedWait = args[4].toInt().also { require(it in 1..120) }.minutes
        val workload = IncrementalWorkload(workspace, plan)
        val group = workload.groups.maxBy { it.sources.size }
        require(group.sources.size >= count)
        val edited = (0 until count).map { group.sources[it * group.sources.size / count] }
        val unchanged =
            workload.sources.firstOrNull { it !in edited }
                ?: error("A baseline control source is required")
        val target = plan.getValue("target").jsonPrimitive.content
        val scope =
            when (plan.getValue("buildSystem").jsonPrimitive.content) {
                "bazel" -> IndexScope.bazel(target)
                "gradle" -> IndexScope.gradle(target)
                else -> error("Unsupported build system")
            }
        val request = RefreshRequest.forScope(scope.includingDependencies())
        val samples = mutableListOf<JsonObject>()
        val report = linkedMapOf<String, JsonElement>("status" to JsonPrimitive("incomplete"))
        var stage = "seed"
        val progress = IncrementalAcceptanceDriver.Progress(30_000L, System.err::println)
        progress.stage(stage)
        val priorCache = System.getProperty("indexino.cache.dir")
        fun checkpoint() {
            report["samples"] = JsonArray(samples)
            Files.writeString(output, JsonObject(report).toString() + "\n")
        }
        try {
            System.setProperty("indexino.cache.dir", cacheRoot.toString())
            workload.write(workload.sources, 0)
            Indexino.connect(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                        .withAutoRefresh(AutoRefreshMode.DISABLED)
                )
                .use { index ->
                    val seedStart = System.nanoTime()
                    withTimeout(seedWait) {
                        index.refresh(request).await()
                        index.snapshot().use { check(workload.matches(it, workload.sources, 0)) }
                    }
                    report["seedReadyNanos"] = JsonPrimitive(System.nanoTime() - seedStart)
                    val workspaceId = InProcessCacheLayout.workspaceId(workspace)
                    val manifests = WorkspaceGenerationManifestStore(cacheRoot, workspaceId)
                    val baseGeneration = checkNotNull(manifests.current()).generation
                    checkpoint()
                    index.snapshot().use { seed ->
                        edited.forEachIndexed { ordinal, source ->
                            stage = "edit:$ordinal"
                            progress.stage(stage)
                            withTimeout(10.minutes) {
                                index.snapshot().use { previous ->
                                    val writeStart = System.nanoTime()
                                    workload.write(listOf(source), 1)
                                    val written = System.nanoTime()
                                    val lines = CopyOnWriteArrayList<String>()
                                    val result =
                                        index.refresh(request, { lines += it }, null).await()
                                    val published = System.nanoTime()
                                    check(result.changes.changedFileCount == 1)
                                    index.snapshot().use { current ->
                                        check(current.generation != previous.generation)
                                        check(
                                            workload.matches(current, edited.take(ordinal + 1), 1)
                                        )
                                        check(workload.matches(current, listOf(unchanged), 0))
                                        check(workload.matches(previous, listOf(source), 0))
                                        check(workload.matches(seed, edited.take(ordinal + 1), 0))
                                        val ready = System.nanoTime()
                                        val manifest = checkNotNull(manifests.current())
                                        check(
                                            manifest.representation ==
                                                WorktreeOverlayPolicy.REPRESENTATION_OVERLAY
                                        )
                                        check(manifest.baseGeneration == baseGeneration)
                                        check(manifest.generation == current.generation.value)
                                        samples += buildJsonObject {
                                            put("step", ordinal + 1)
                                            put("source", source.relative)
                                            put("writeNanos", written - writeStart)
                                            put("refreshNanos", published - written)
                                            put("queryNanos", ready - published)
                                            put("readyNanos", ready - written)
                                            put("queriesVerified", true)
                                            put(
                                                "overlayPackBytes",
                                                manifest.overlayPackKeys.sumOf { key ->
                                                    val file =
                                                        cacheRoot.resolve(
                                                            "chunks/${key.take(2)}/${key.substring(2, 4)}/$key"
                                                        )
                                                    Files.size(file)
                                                },
                                            )
                                            put(
                                                "refresh",
                                                IncrementalAcceptanceDriver.RefreshEvidence
                                                    .summarize(
                                                        "step-${ordinal + 1}",
                                                        lines,
                                                        root.toString(),
                                                    ),
                                            )
                                        }
                                        checkpoint()
                                    }
                                }
                            }
                        }
                        stage = "final-audit"
                        progress.stage(stage)
                        withTimeout(10.minutes) {
                            index.snapshot().use { final ->
                                check(workload.matches(final, edited, 1))
                                check(
                                    workload.matches(
                                        final,
                                        workload.sources.filterNot { it in edited },
                                        0,
                                    )
                                )
                                check(workload.matches(seed, workload.sources, 0))
                            }
                        }
                    }
                }
            report["status"] = JsonPrimitive("passed")
        } catch (error: Exception) {
            report["failureStage"] = JsonPrimitive(stage)
            report["failureType"] = JsonPrimitive(error.javaClass.simpleName)
            throw error
        } finally {
            try {
                workload.restoreOriginals()
            } finally {
                progress.close()
                if (priorCache == null) System.clearProperty("indexino.cache.dir")
                else System.setProperty("indexino.cache.dir", priorCache)
                report["schema"] = JsonPrimitive(1)
                report["mode"] = JsonPrimitive("manual-cumulative-whole-scope")
                report["selectedFiles"] = JsonPrimitive(edited.size)
                checkpoint()
            }
        }
    }
}
