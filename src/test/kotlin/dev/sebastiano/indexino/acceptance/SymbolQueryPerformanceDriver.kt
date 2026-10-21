@file:OptIn(dev.sebastiano.indexino.model.IndexinoInternalApi::class)

package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.SymbolRecord
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.NameMatchMode
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthetic persisted-index benchmark, separate from parser and public-corpus measurements. */
internal object SymbolQueryPerformanceDriver {
    private const val BATCH_SIZE = 1_000
    private const val WARMUPS = 10
    private const val SAMPLES = 100
    private const val MAX_RECORDS = 1_000_000
    private const val PREFIX_ROWS = 10

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 3) {
            "usage: SymbolQueryPerformanceDriver <fresh-root> <report> <records>"
        }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        val report = Path.of(args[1]).toAbsolutePath().normalize()
        val count = args[2].toInt()
        require(count in PREFIX_ROWS..MAX_RECORDS && count % PREFIX_ROWS == 0)
        require(Files.notExists(root) && !report.startsWith(root))
        require(Files.isDirectory(report.parent))
        Files.createDirectory(root)
        try {
            seedStore(root.resolve("store"), count)
            val suffix = (count - 1).toString().padStart(6, '0')
            val cases =
                linkedMapOf(
                    "fqn" to SymbolQuery.named("bench.Node$suffix").withMatch(NameMatchMode.FQN),
                    "exact" to SymbolQuery.named("Node$suffix"),
                    "alias" to SymbolQuery.named("Alternative$suffix"),
                    "prefix" to
                        SymbolQuery.named("Alternative${suffix.dropLast(1)}")
                            .withMatch(NameMatchMode.PREFIX),
                    "missing" to SymbolQuery.named("NoSuchInventedSymbol"),
                )
            val samples = linkedMapOf<String, List<Long>>()
            IndexSnapshot.create(
                    XodusCodeIndexStore.open(root.resolve("store"), readOnly = true),
                    WorkspaceRevision(
                        "performance",
                        listOf(
                            SourceOriginRevision(
                                SourceOriginId.of("workspace"),
                                null,
                                "performance",
                                null,
                            )
                        ),
                    ),
                    WorkspaceGenerationId.of("performance"),
                )
                .use { snapshot ->
                    val owner =
                        snapshot
                            .findSymbols(SymbolQuery.named("Container"), QueryOptions.page(1))
                            .items
                            .single()
                            .id
                    for ((label, query) in cases) {
                        val expected =
                            when (label) {
                                "missing" -> emptyList()
                                "prefix" ->
                                    (count - PREFIX_ROWS until count).map {
                                        "Node${it.toString().padStart(6, '0')}"
                                    }
                                else -> listOf("Node$suffix")
                            }
                        val measurements =
                            List(WARMUPS + SAMPLES) {
                                measureNanoTime {
                                    val page =
                                        snapshot.findSymbols(query, QueryOptions.page(PREFIX_ROWS))
                                    check(page.items.map { it.name }.sorted() == expected)
                                    check(
                                        !page.hasMore &&
                                            page.items.all {
                                                it.ownerId == owner && it.location.line == 2
                                            }
                                    )
                                }
                            }
                        samples[label] = measurements.drop(WARMUPS)
                    }
                }
            val json = buildJsonObject {
                put("symbolCount", count + 1)
                put("warmups", WARMUPS)
                put("samples", SAMPLES)
                put(
                    "limitation",
                    "Synthetic Xodus-backed public snapshot queries; excludes parsing, " +
                        "snapshot acquisition and process launch; OS cache uncontrolled",
                )
                put(
                    "queryNanos",
                    buildJsonObject {
                        samples.forEach { (name, values) ->
                            put(name, JsonArray(values.map(::JsonPrimitive)))
                        }
                    },
                )
            }
            report.writeText("$json\n")
            println(json)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun seedStore(path: Path, count: Int) {
        XodusCodeIndexStore.open(path).use { store ->
            for (start in 0 until count step BATCH_SIZE) {
                store.transaction {
                    for (index in start until minOf(start + BATCH_SIZE, count)) {
                        val suffix = index.toString().padStart(6, '0')
                        val record =
                            SymbolRecord(
                                fqn = "bench.Node$suffix",
                                relativeFile = "Node$suffix.kt",
                                line = 2,
                                column = 1,
                                kind = "class",
                                name = "Node$suffix",
                                aliases = listOf("Alternative$suffix"),
                                ownerFqn = "bench.Container",
                            )
                        store.put(
                            CodeIndexKey.symbolDefinition(record.fqn, record.relativeFile, 2, 1),
                            record,
                        )
                    }
                }
            }
            store.put(
                CodeIndexKey.sym("bench.Container"),
                SymbolRecord(
                    fqn = "bench.Container",
                    relativeFile = "Container.kt",
                    line = 1,
                    kind = "class",
                    name = "Container",
                ),
            )
        }
    }
}
