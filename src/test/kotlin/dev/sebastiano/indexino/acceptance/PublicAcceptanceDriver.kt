// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.BuildSystem
import dev.sebastiano.indexino.api.InProcessCacheLayout
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshOutcome
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.core.cache.WorkspaceGenerationManifestStore
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.producer.JsonlIndexBuildProgressReporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Test-runtime entry point. Never shipped in the CLI or thin publication. */
internal object PublicAcceptanceDriver {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size in 7..8) {
            "workspace cache gradle|bazel target includeDependencies contract.json report.json [instrumented]"
        }
        val instrumented = args.getOrNull(7)?.also { require(it == "instrumented") } != null
        val phaseEvents = mutableListOf<JsonObject>()
        val workspace = Path.of(args[0]).toRealPath()
        configureColdCache(workspace, Path.of(args[1]).toAbsolutePath())
        val scope = scope(args[2], args[3], args[4].toBooleanStrict())
        val contract = Json.parseToJsonElement(Files.readString(Path.of(args[5]))).jsonObject
        val expectedInventory =
            contract.getValue("sources").jsonArray.map { it.jsonPrimitive.content }
        val expectedSymbols = contract.getValue("symbols").jsonArray.map { it.jsonObject }
        require(expectedInventory.isNotEmpty()) { "Empty coverage denominator" }
        require(expectedInventory.size == expectedInventory.toSet().size) {
            "Duplicate expected source"
        }
        require(expectedSymbols.isNotEmpty()) { "No independently specified query expectations" }
        val configuration =
            IndexinoConfiguration.forWorkspace(workspace)
                .withAutoRefresh(AutoRefreshMode.DISABLED)
                .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
        val request = RefreshRequest.forScope(scope)
        val refreshSamples = mutableListOf<Long>()
        val queries = linkedMapOf<String, List<Long>>()
        var generation = ""
        var coldNanos: Long? = null
        var reopenNanos: Long? = null
        var completed = false
        var failureType: String? = null
        var observedInventory: List<String>? = null
        var sourceProvenance: JsonObject? = null
        try {
            Indexino.connect(configuration).use { index ->
                suspend fun refresh() =
                    if (instrumented) instrumentedRefresh(index, request, phaseEvents)
                    else index.refresh(request).await()
                // Inventory instrumentation only; semantic/lifecycle calls below use public APIs.
                observeInventory(index, workspace, expectedInventory) { observedInventory = it }
                coldNanos = measureNanoTime {
                    val result = refresh()
                    check(result.outcome == RefreshOutcome.UPDATED)
                    check(result.changes.changedFileCount == expectedInventory.size)
                    check(result.changes.removedFileCount == 0)
                    check(result.scope == scope)
                    generation = result.generation.value
                }
                sourceProvenance = readSourceProvenance(workspace, generation, scope)
                checkNotNull(observedInventory) { "Disconnected source-inventory instrumentation" }
                if (instrumented) {
                    check(phaseEvents.any(::isProducerEvent)) {
                        "Disconnected instrumentation: cold positive control observed no producer"
                    }
                }
                repeat(WARM_REPEATS) {
                    val eventOffset = phaseEvents.size
                    refreshSamples += measureNanoTime {
                        val result = refresh()
                        check(result.outcome == RefreshOutcome.UNCHANGED)
                        check(result.generation.value == generation)
                        check(result.changes.changedFileCount == 0)
                        check(result.changes.removedFileCount == 0)
                    }
                    if (instrumented) {
                        check(phaseEvents.drop(eventOffset).none(::isProducerEvent)) {
                            "Unchanged refresh invoked a core producer"
                        }
                    }
                }
                index.snapshot().use { snapshot ->
                    check(snapshot.generation.value == generation)
                    queries.putAll(measureQueries(snapshot, expectedSymbols))
                }
            }
            reopenNanos = verifyReopen(configuration, generation, expectedSymbols)
            completed = true
        } catch (error: Exception) {
            failureType = error.javaClass.simpleName
            throw error
        } finally {
            val report = buildJsonObject {
                put("schema", 1)
                put("status", if (completed) "passed" else "failed")
                failureType?.let { put("failureType", it) }
                put("lane", if (instrumented) "instrumented-diagnostic" else "public-api")
                put("generation", generation.takeIf { it.isNotEmpty() })
                put("coldApiNanos", coldNanos)
                put("warmRefreshApiNanos", JsonArray(refreshSamples.map(::JsonPrimitive)))
                put("reopenApiNanos", reopenNanos)
                put(
                    "queryApiNanos",
                    JsonObject(queries.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }),
                )
                put("inventoryObserved", observedInventory != null)
                put("sources", JsonArray(observedInventory.orEmpty().map(::JsonPrimitive)))
                put("sourceProvenance", sourceProvenance ?: JsonNull)
                put("diagnostics", diagnostics(instrumented, phaseEvents))
            }
            Files.writeString(Path.of(args[6]), report.toString() + "\n")
        }
    }

    private fun readSourceProvenance(
        workspace: Path,
        generation: String,
        scope: IndexScope,
    ): JsonObject {
        // Inventory provenance only; no semantic facts are read from storage.
        val manifest =
            checkNotNull(
                WorkspaceGenerationManifestStore(
                        InProcessCacheLayout.cacheRoot(),
                        InProcessCacheLayout.workspaceId(workspace),
                    )
                    .readGeneration(generation)
                    ?.compatibilityManifest
            )
        val expectedTopology =
            if (scope.buildSystem == BuildSystem.BAZEL) "bazel-query" else "gradle-parse"
        check(manifest.topology == expectedTopology)
        check(manifest.includeDeps == scope.includesDependencies)
        check(manifest.scope == scope.value)
        return buildJsonObject {
            put("topology", manifest.topology)
            put("includeDependencies", manifest.includeDeps)
            put("scope", manifest.scope)
        }
    }

    private suspend fun verifyReopen(
        configuration: IndexinoConfiguration,
        generation: String,
        expectedSymbols: List<JsonObject>,
    ): Long = measureNanoTime {
        Indexino.connect(configuration).use { index ->
            index.snapshot().use { snapshot ->
                check(snapshot.generation.value == generation)
                expectedSymbols.forEach { assertSymbol(snapshot, it) }
            }
        }
    }

    private suspend fun instrumentedRefresh(
        index: Indexino,
        request: RefreshRequest,
        events: MutableList<JsonObject>,
    ) = run {
        val reporter = JsonlIndexBuildProgressReporter { line ->
            events +=
                JsonObject(
                    Json.parseToJsonElement(line).jsonObject +
                        ("observedNanoTime" to JsonPrimitive(System.nanoTime()))
                )
        }
        fun boundary(name: String) {
            events += buildJsonObject {
                put("event", name)
                put("observedNanoTime", System.nanoTime())
            }
        }
        boundary("refresh_started")
        try {
            index.refresh(request, {}, reporter).await()
        } finally {
            boundary("refresh_finished")
        }
    }

    private fun scope(system: String, target: String, dependencies: Boolean): IndexScope {
        val base =
            when (system) {
                "gradle" -> IndexScope.gradle(target)
                "bazel" -> IndexScope.bazel(target)
                else -> error("Unknown build system")
            }
        return if (dependencies) base.includingDependencies() else base
    }

    private suspend fun measureQueries(
        snapshot: IndexSnapshot,
        expectedSymbols: List<JsonObject>,
    ): Map<String, List<Long>> {
        val queries = linkedMapOf<String, List<Long>>()
        for (expected in expectedSymbols) {
            val name = expected.getValue("name").jsonPrimitive.content
            repeat(QUERY_WARMUP) { assertSymbol(snapshot, expected) }
            queries[name] =
                List(QUERY_SAMPLES) { measureNanoTime { assertSymbol(snapshot, expected) } }
        }
        return queries
    }

    private fun configureColdCache(workspace: Path, cache: Path) {
        require(!cache.startsWith(workspace)) { "Cache must be outside the corpus" }
        require(!Files.exists(cache)) { "Cold driver requires a new cache directory" }
        Files.createDirectories(cache)
        System.setProperty("indexino.cache.dir", cache.toString())
    }

    private fun observeInventory(
        index: Indexino,
        workspace: Path,
        expected: List<String>,
        observed: (List<String>) -> Unit,
    ) {
        index.onRefreshSucceededForRuntime = { _, sources, _ ->
            val actual = sources.map { source ->
                check(source.originRoot.toRealPath() == workspace) { "Unexpected external origin" }
                source.path
            }
            observed(actual.sorted())
            check(actual.size == actual.toSet().size) { "Duplicate discovered source" }
            check(actual.toSet() == expected.toSet()) {
                "Coverage mismatch: missing=${expected.toSet() - actual.toSet()}; " +
                    "extra=${actual.toSet() - expected.toSet()}"
            }
        }
    }

    private fun isProducerEvent(event: JsonObject): Boolean =
        event["event"]?.jsonPrimitive?.content == "phase_started" &&
            event["phase"]?.jsonPrimitive?.content in
                setOf("kotlin-psi-symbols", "java-source", "xml-resources")

    private fun diagnostics(instrumented: Boolean, events: List<JsonObject>) = buildJsonObject {
        put("watcher", "disabled; separate lane required")
        put("phaseEvents", JsonArray(events))
        put(
            "analysisReuse",
            if (instrumented)
                "zero core producer phases on unchanged refresh; cold positive control"
            else "public changed-file counters; see separate instrumented diagnostic lane",
        )
        put("phaseTiming", "unavailable: public refresh does not separate capture/hash/topology")
    }

    private suspend fun assertSymbol(snapshot: IndexSnapshot, expected: JsonObject) {
        val name = expected.getValue("name").jsonPrimitive.content
        val symbols = collectPages { options ->
            snapshot.findSymbols(
                SymbolQuery.named(expected.getValue("fqn").jsonPrimitive.content)
                    .withMatch(NameMatchMode.FQN),
                options,
            )
        }
        val identities = symbols.map { it.name to it.location.file.path }
        check(identities == listOf(name to expected.getValue("path").jsonPrimitive.content)) {
            "Unexpected $name declarations: $identities"
        }
        check(symbols.single().location.line == expected.getValue("line").jsonPrimitive.int)
    }

    private const val WARM_REPEATS = 5
    private const val QUERY_WARMUP = 10
    private const val QUERY_SAMPLES = 100
}

internal suspend fun <T> collectPages(query: suspend (QueryOptions) -> QueryPage<T>): List<T> {
    val result = mutableListOf<T>()
    val seen = mutableSetOf<T>()
    repeat(10_000) {
        val page = query(QueryOptions.page(1, result.size))
        check(page.offset == result.size) { "Incorrect page offset" }
        check(page.items.size <= 1) { "Page exceeds requested bound" }
        check(page.items.all(seen::add)) { "Duplicate paginated record" }
        result.addAll(page.items)
        if (!page.hasMore) {
            page.totalCount?.let { totalCount ->
                check(totalCount == result.size) { "Omitted paginated records" }
            }
            return result
        }
        check(page.items.isNotEmpty()) { "Non-advancing pagination" }
    }
    error("Pagination exceeds safety bound")
}
