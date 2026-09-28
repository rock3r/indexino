package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.IndexSnapshot
import dev.sebastiano.indexino.core.key.CodeIndexKey
import dev.sebastiano.indexino.core.record.CodeIndexRecord
import dev.sebastiano.indexino.core.store.CodeIndexStore
import dev.sebastiano.indexino.core.store.WorktreeOverlayIndexStore
import dev.sebastiano.indexino.core.xodus.XodusCodeIndexStore
import dev.sebastiano.indexino.model.CallQuery
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceOriginRevision
import dev.sebastiano.indexino.model.SymbolQuery
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import dev.sebastiano.indexino.model.WorkspaceRevision
import dev.sebastiano.indexino.producer.IndexBuildContext
import dev.sebastiano.indexino.producer.IndexedSource
import dev.sebastiano.indexino.producer.ProducerRegistry
import dev.sebastiano.indexino.producer.SourceContentSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** Private feasibility experiment: a complete one-file fact delta before durable publication. */
@OptIn(IndexinoInternalApi::class)
internal class LiveOverlayExperimentTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `complete live delta matches durable delta while old snapshot remains pinned`(): Unit =
        runBlocking {
            val workspace = Files.createDirectory(temporary.resolve("workspace"))
            val caller = "Caller.kt"
            val helper = "Helper.kt"
            Files.writeString(
                workspace.resolve(caller),
                "package sample\nfun oldCaller() = helper()\n",
            )
            Files.writeString(workspace.resolve(helper), "package sample\nfun helper() = 1\n")
            val sources = listOf(caller, helper).map { IndexedSource.workspace(workspace, it) }
            val original = SourceContentSnapshot.capture(sources)
            val base = MemoryStore()
            produce(base, workspace, sources, original, sources.toSet())
            base.freeze()

            IndexSnapshot.create(base, revision("original"), generation("original")).use { old ->
                Files.writeString(
                    workspace.resolve(caller),
                    "package sample\nfun newCaller() = helper()\n",
                )
                val changed =
                    SourceContentSnapshot.capture(
                        sources,
                        original.hashes(),
                        setOf(workspace.resolve(caller)),
                    )
                val tombstones =
                    listOf(WorktreeOverlayIndexStore.tombstonePrefixForSource("workspace", caller))
                val liveDelta = MemoryStore()
                produce(liveDelta, workspace, sources, changed, setOf(sources.first()))
                liveDelta.freeze()
                IndexSnapshot.create(
                        WorktreeOverlayIndexStore(base, liveDelta, tombstones),
                        revision("edited"),
                        generation("live"),
                    )
                    .use { live ->
                        val durablePath = temporary.resolve("durable-delta")
                        XodusCodeIndexStore.open(durablePath).use { durableDelta ->
                            produce(
                                durableDelta,
                                workspace,
                                sources,
                                changed,
                                setOf(sources.first()),
                            )
                        }
                        IndexSnapshot.create(
                                WorktreeOverlayIndexStore(
                                    base,
                                    XodusCodeIndexStore.open(durablePath, readOnly = true),
                                    tombstones,
                                ),
                                revision("edited"),
                                generation("durable"),
                            )
                            .use { durable ->
                                val liveNames = names(live)
                                assertEquals(liveNames, names(durable))
                                assertEquals(setOf("helper", "newCaller"), liveNames)
                                assertEquals(setOf("helper", "oldCaller"), names(old))
                                assertEquals(listOf("helper"), calls(live))
                                assertEquals(calls(live), calls(durable))
                                assertEquals(listOf("helper"), calls(old))
                                assertTrue(live.generation != durable.generation)
                            }
                    }
            }
        }

    @Test
    fun `live corpus probe verifies repeated Java edits and Kotlin edit against durable refresh`() {
        val root = Files.createDirectory(temporary.resolve("owned"))
        Files.writeString(root.resolve(".indexino-benchmark-owned"), "fixture")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":alpha\")\n")
        val caller = "alpha/src/main/kotlin/Caller.kt"
        val helper = "alpha/src/main/java/Helper.java"
        for ((relative, content) in
            listOf(
                caller to "package sample\nclass Caller {}\n",
                helper to "package sample; class Helper {}\n",
            )) {
            val file = workspace.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        val plan = temporary.resolve("plan.json")
        fun writePlan(changedFile: Int) =
            Files.writeString(
                plan,
                """{"schema":1,"buildSystem":"gradle","target":":alpha","files":[
                {"path":"$caller","module":":alpha","package":"sample"},
                {"path":"$helper","module":":alpha","package":"sample"}],
                "groups":[{"id":"small1","files":[$changedFile]}]}"""
                    .trimIndent(),
            )
        val report = temporary.resolve("probe.json")

        // The two calls compare JVM first-use with later-use across connections. The second
        // call additionally edits and reverts within one daemon. Timings are diagnostic only;
        // every edit must verify pinned, unchanged, and changed public snapshots.
        writePlan(1)
        repeat(2) { run ->
            val javaReport = temporary.resolve("java-$run.json")
            val args =
                arrayOf(
                    root.toString(),
                    plan.toString(),
                    javaReport.toString(),
                    "fixture-java-$run",
                    "manual",
                )
            LiveOverlayCorpusProbe.main(if (run == 1) args + "repeat" else args)
            val javaResult = Json.parseToJsonElement(Files.readString(javaReport)).jsonObject
            assertEquals("passed", javaResult.getValue("status").jsonPrimitive.content)
            assertEquals(
                "true",
                javaResult.getValue("oldPinAndUnchangedVerified").jsonPrimitive.content,
            )
            assertEquals("true", javaResult.getValue("publicQueriesVerified").jsonPrimitive.content)
            if (run == 1) {
                assertEquals(
                    "true",
                    javaResult.getValue("repeat_oldPinAndUnchangedVerified").jsonPrimitive.content,
                )
                assertEquals(
                    "true",
                    javaResult.getValue("repeat_publicQueriesVerified").jsonPrimitive.content,
                )
                assertTrue(
                    javaResult
                        .getValue("repeat_liveJavaProducerNanos")
                        .jsonPrimitive
                        .content
                        .toLong() > 0
                )
                assertTrue(
                    javaResult.getValue("repeat_durableReadyNanos").jsonPrimitive.content.toLong() >
                        0
                )
            }
            assertEquals(
                "package sample; class Helper {}\n",
                Files.readString(workspace.resolve(helper)),
            )
            println("live-overlay-java-run-$run: $javaResult")
        }
        writePlan(0)
        LiveOverlayCorpusProbe.main(
            arrayOf(root.toString(), plan.toString(), report.toString(), "fixture-1", "manual")
        )

        val result = Json.parseToJsonElement(Files.readString(report)).jsonObject
        assertEquals("passed", result.getValue("status").jsonPrimitive.content)
        assertEquals("manual", result.getValue("durableMode").jsonPrimitive.content)
        assertEquals("true", result.getValue("oldPinAndUnchangedVerified").jsonPrimitive.content)
        assertEquals("true", result.getValue("publicQueriesVerified").jsonPrimitive.content)
        assertEquals(
            "true",
            result.getValue("liveQueryLocalDurableQueryRemote").jsonPrimitive.content,
        )
        assertTrue(result.getValue("liveReadyNanos").jsonPrimitive.content.toLong() > 0)
        assertTrue(result.getValue("durableReadyNanos").jsonPrimitive.content.toLong() > 0)
        val facts = result.getValue("liveFactsNanos").jsonPrimitive.content.toLong()
        for (phase in
            listOf(
                "liveCaptureNanos",
                "liveOverlaySetupNanos",
                "liveJavaProducerNanos",
                "liveSnapshotCreateNanos",
                "liveQueryNanos",
            )) {
            val elapsed = result.getValue(phase).jsonPrimitive.content.toLong()
            assertTrue(elapsed > 0, phase)
            assertTrue(elapsed <= result.getValue("liveReadyNanos").jsonPrimitive.content.toLong())
            if (phase != "liveQueryNanos" && phase != "liveSnapshotCreateNanos")
                assertTrue(elapsed <= facts, phase)
        }
        assertEquals(
            "package sample\nclass Caller {}\n",
            Files.readString(workspace.resolve(caller)),
        )
        println("live-overlay-disposable-fixture: $result")
    }

    private fun produce(
        store: CodeIndexStore,
        workspace: Path,
        sources: List<IndexedSource>,
        snapshot: SourceContentSnapshot,
        changed: Set<IndexedSource>,
    ) {
        val context =
            IndexBuildContext(
                store = store,
                commitHash = "test-commit",
                workspaceRoot = workspace,
                sourceFiles = sources.map { it.path },
                sources = sources,
                sourceSnapshot = snapshot,
                changedSourceSet = changed,
            )
        ProducerRegistry.forApplications(emptyList()).forEach { producer ->
            producer.produce(context.copy(activePhase = producer.id), store)
        }
    }

    private suspend fun names(snapshot: IndexSnapshot): Set<String> =
        setOf("oldCaller", "newCaller", "helper").filterTo(linkedSetOf()) { name ->
            snapshot.findSymbols(SymbolQuery.named(name), QueryOptions.page(10)).items.isNotEmpty()
        }

    private suspend fun calls(snapshot: IndexSnapshot): List<String> =
        snapshot
            .findCalls(
                CallQuery.inFile(
                    SourceFile.of(SourceOriginId.of("workspace"), "Caller.kt", "Caller.kt")
                ),
                QueryOptions.page(10),
            )
            .items
            .map { it.calleeName }

    private fun revision(fingerprint: String) =
        WorkspaceRevision(
            fingerprint,
            listOf(SourceOriginRevision(SourceOriginId.of("workspace"), null, fingerprint, null)),
        )

    private fun generation(id: String) = WorkspaceGenerationId.of(id)

    private class MemoryStore : CodeIndexStore {
        private val records = linkedMapOf<CodeIndexKey, CodeIndexRecord>()
        private var frozen = false

        fun freeze() {
            frozen = true
        }

        override fun get(key: CodeIndexKey): CodeIndexRecord? = records[key]

        override fun put(key: CodeIndexKey, record: CodeIndexRecord) {
            check(!frozen)
            records[key] = record
        }

        override fun delete(key: CodeIndexKey) {
            check(!frozen)
            records.remove(key)
        }

        override fun prefixScan(prefix: String): Sequence<Pair<CodeIndexKey, CodeIndexRecord>> =
            records
                .asSequence()
                .filter { (key, _) -> key.value.startsWith(prefix) }
                .map { it.toPair() }

        override fun <T> transaction(block: () -> T): T = block()

        override fun close() = Unit
    }
}
