// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.ReferenceQuery
import dev.sebastiano.indexino.model.ResourceQuery
import dev.sebastiano.indexino.model.SourceLocation
import dev.sebastiano.indexino.model.SymbolQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Consumes only committed invented fixtures; corpus source files are never mutated. */
internal object RetrievalAcceptanceDriver {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 4) { "fixture-directory disposable-root manual|watcher report.json" }
        val fixture = Path.of(args[0]).toRealPath()
        val root = Path.of(args[1]).toAbsolutePath()
        require(!Files.exists(root)) { "Fixture execution requires a new disposable root" }
        val watcher =
            when (args[2]) {
                "manual" -> false
                "watcher" -> true
                else -> error("Unknown lane")
            }
        Files.createDirectories(root)
        val workspace = root.resolve("workspace")
        copyFixture(workspace, fixture)
        System.setProperty("indexino.cache.dir", root.resolve("cache").toString())
        val configuration =
            IndexinoConfiguration.forWorkspace(workspace)
                .withRuntimeAttach(
                    if (watcher) RuntimeAttachMode.PREFER_DAEMON else RuntimeAttachMode.IN_PROCESS
                )
                .withAutoRefresh(if (watcher) AutoRefreshMode.ENABLED else AutoRefreshMode.DISABLED)
        val truth = readObject(fixture.resolve("ground-truth.json"))
        val lifecycle = readObject(fixture.resolve("lifecycle.json"))
        check(truth.getValue("version").jsonPrimitive.int == 1)
        check(lifecycle.getValue("version").jsonPrimitive.int == 1)
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        val queryReports = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        val mutationSamples = mutableListOf<Long>()
        Indexino.connect(configuration).use { index ->
            try {
                index.refresh(request).await()
                index.snapshot().use { snapshot ->
                    for (case in truth.getValue("cases").jsonArray.map { it.jsonObject }) {
                        check(case.getValue("exhaustive").jsonPrimitive.boolean)
                        val expected = case.getValue("expected").jsonArray.map { it.jsonObject }
                        val actual = queryCase(snapshot, case)
                        val counts = compareRows(expected, actual)
                        val semantic = case.string("contract") == "semantic"
                        if (
                            !semantic &&
                                expected.groupingBy { it }.eachCount() !=
                                    actual.groupingBy { it }.eachCount()
                        ) {
                            failures += case.string("id")
                        }
                        repeat(10) { check(queryCase(snapshot, case) == actual) }
                        val timings =
                            List(100) {
                                measureNanoTime { check(queryCase(snapshot, case) == actual) }
                            }
                        queryReports += buildJsonObject {
                            put("id", case.string("id"))
                            put("contract", case.string("contract"))
                            put("heldOut", case.getValue("heldOut"))
                            put("language", case.string("language"))
                            put("category", case.string("category"))
                            put("expected", JsonArray(expected))
                            put("actual", JsonArray(actual))
                            put("truePositive", counts.first)
                            put("falsePositive", counts.second)
                            put("falseNegative", counts.third)
                            put("precision", ratio(counts.first, counts.first + counts.second))
                            put("recall", ratio(counts.first, counts.first + counts.third))
                            put("apiNanos", JsonArray(timings.map(::JsonPrimitive)))
                        }
                    }
                }
                Files.writeString(
                    Path.of(args[3]),
                    buildJsonObject {
                            put("schema", 1)
                            put("status", "incomplete")
                            put("lane", args[2])
                            put("syntacticFailures", JsonArray(failures.map(::JsonPrimitive)))
                            put("queries", JsonArray(queryReports))
                            put("lifecycle", "not-completed")
                        }
                        .toString() + "\n",
                )
                mutationSamples += runLifecycle(index, lifecycle, workspace, fixture, watcher)
            } finally {
                if (watcher) index.shutdownRuntime()
            }
        }
        Files.writeString(
            Path.of(args[3]),
            buildJsonObject {
                    put("schema", 1)
                    put("status", if (failures.isEmpty()) "passed" else "failed")
                    put("syntacticFailures", JsonArray(failures.map(::JsonPrimitive)))
                    put("lane", args[2])
                    put("queries", JsonArray(queryReports))
                    put("mutationNanos", JsonArray(mutationSamples.map(::JsonPrimitive)))
                }
                .toString() + "\n",
        )
        check(failures.isEmpty()) { "Syntactic cases failed: $failures" }
    }

    private suspend fun runLifecycle(
        index: Indexino,
        lifecycle: JsonObject,
        workspace: Path,
        fixture: Path,
        watcher: Boolean,
    ): List<Long> {
        val samples = mutableListOf<Long>()
        val request = RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
        val fqn = lifecycle.string("queryFqn")
        check(markerRows(index.snapshot(), fqn) == listOf(lifecycle.getValue("initial").jsonObject))
        repeat(5) {
            for (step in lifecycle.getValue("steps").jsonArray.map { it.jsonObject }) {
                index.snapshot().use { previous ->
                    val oldRows = symbolRows(previous, fqn)
                    val oldGeneration = previous.generation
                    mutate(workspace, step)
                    val expected = step.getValue("expected").jsonArray.map { it.jsonObject }
                    samples += measureNanoTime {
                        if (watcher) {
                            withTimeout(30_000) {
                                while (true) {
                                    val ready =
                                        index.snapshot().use { current ->
                                            current.generation != oldGeneration &&
                                                symbolRows(current, fqn) == expected
                                        }
                                    if (ready) break
                                    delay(50)
                                }
                            }
                        } else {
                            check(markerRows(index.snapshot(), fqn) == oldRows) {
                                "Manual lane auto-refreshed"
                            }
                            val refreshed = index.refresh(request).await()
                            check(refreshed.generation != oldGeneration)
                            check(markerRows(index.snapshot(), fqn) == expected)
                        }
                    }
                    check(symbolRows(previous, fqn) == oldRows) { "Pinned snapshot changed" }
                }
            }
            restoreFixture(workspace, fixture)
            index.refresh(request).await()
        }
        return samples
    }

    private fun mutate(workspace: Path, step: JsonObject) {
        val target = safePath(workspace, step.string("path"))
        when (step.string("operation")) {
            "edit" -> {
                check(Files.exists(target))
                Files.writeString(target, step.string("content"))
            }
            "add" -> {
                check(!Files.exists(target))
                Files.createDirectories(target.parent)
                Files.writeString(target, step.string("content"))
            }
            "rename" -> Files.move(target, safePath(workspace, step.string("destination")))
            "delete" -> check(Files.deleteIfExists(target))
            else -> error("Unknown fixture mutation")
        }
    }

    private fun restoreFixture(workspace: Path, fixture: Path) {
        // Keep workspace identity and watched directories alive between repeats.
        Files.walk(workspace).use { paths ->
            paths.filter(Files::isRegularFile).forEach(Files::delete)
        }
        copyFixture(workspace, fixture)
    }

    private fun copyFixture(workspace: Path, fixture: Path) {
        Files.walk(fixture.resolve("workspace")).use { paths ->
            paths.forEach { source ->
                check(!Files.isSymbolicLink(source)) { "Fixture symlink not allowed" }
                val target = workspace.resolve(fixture.resolve("workspace").relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
    }

    private suspend fun queryCase(snapshot: IndexSnapshot, case: JsonObject): List<JsonObject> {
        val files = case.getValue("scopeFiles").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val language = case.string("language")
        return when (case.string("kind")) {
            "symbols" ->
                collectPages {
                        snapshot.findSymbols(
                            SymbolQuery.named(case.getValue("symbol").jsonObject.string("fqn"))
                                .withMatch(NameMatchMode.FQN)
                                .withLanguage(language),
                            it,
                        )
                    }
                    .filter { it.location.file.path in files }
                    .map { row(it.location) }
            "references" -> {
                val target = case.getValue("symbol").jsonObject
                val declaration =
                    collectPages {
                            snapshot.findSymbols(
                                SymbolQuery.named(target.string("fqn"))
                                    .withMatch(NameMatchMode.FQN),
                                it,
                            )
                        }
                        .single {
                            it.location.file.path == target.string("file") &&
                                it.location.line == target.getValue("line").jsonPrimitive.int
                        }
                collectPages { snapshot.findReferences(ReferenceQuery.to(declaration.id), it) }
                    .filter { it.language == language && it.location.file.path in files }
                    .map { row(it.location) }
            }
            "resources" -> {
                val resource = case.getValue("resource").jsonObject
                check(language == "xml")
                collectPages {
                        snapshot.findResources(
                            ResourceQuery.of(
                                resource["namespace"]?.jsonPrimitive?.contentOrNull,
                                resource.string("type"),
                                resource.string("name"),
                            ),
                            it,
                        )
                    }
                    .filter { it.location.file.path in files }
                    .map { definition ->
                        buildJsonObject {
                            put("file", definition.location.file.path)
                            put("line", definition.location.line)
                            put(
                                "qualifiers",
                                JsonArray(
                                    definition.qualifiers
                                        .split('-')
                                        .filter(String::isNotBlank)
                                        .map(::JsonPrimitive)
                                ),
                            )
                        }
                    }
            }
            else -> error("Unknown fixture query kind")
        }.sortedBy { it.toString() }
    }

    private suspend fun symbolRows(snapshot: IndexSnapshot, fqn: String): List<JsonObject> =
        collectPages {
                snapshot.findSymbols(SymbolQuery.named(fqn).withMatch(NameMatchMode.FQN), it)
            }
            .map { row(it.location) }
            .sortedBy { it.toString() }

    private suspend fun markerRows(snapshot: IndexSnapshot, fqn: String): List<JsonObject> =
        snapshot.use {
            symbolRows(it, fqn)
        }

    private fun row(location: SourceLocation): JsonObject = buildJsonObject {
        put("file", location.file.path)
        put("line", location.line)
    }

    private fun ratio(numerator: Int, denominator: Int) =
        if (denominator == 0) JsonNull else JsonPrimitive(numerator.toDouble() / denominator)

    private fun readObject(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun safePath(workspace: Path, relative: String): Path {
        val target = workspace.resolve(relative).normalize()
        require(target.startsWith(workspace) && target != workspace) {
            "Fixture path escapes workspace"
        }
        return target
    }
}

internal fun compareRows(
    expected: List<JsonObject>,
    actual: List<JsonObject>,
): Triple<Int, Int, Int> {
    val wanted = expected.groupingBy { it }.eachCount()
    val found = actual.groupingBy { it }.eachCount()
    val truePositive = wanted.entries.sumOf { (row, count) -> minOf(count, found[row] ?: 0) }
    return Triple(truePositive, actual.size - truePositive, expected.size - truePositive)
}
