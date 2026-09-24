// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import dev.sebastiano.indexino.api.AutoRefreshMode
import dev.sebastiano.indexino.api.IndexScope
import dev.sebastiano.indexino.api.Indexino
import dev.sebastiano.indexino.api.IndexinoConfiguration
import dev.sebastiano.indexino.api.IndexinoException
import dev.sebastiano.indexino.api.RefreshRequest
import dev.sebastiano.indexino.api.RuntimeAttachMode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

internal class IncrementalAcceptanceDriverTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `watcher baseline reset retries a joined stale refresh and remains bounded`(): Unit =
        runBlocking {
            var refreshes = 0
            val failure =
                runCatching {
                        IncrementalAcceptanceDriver.awaitRestoredBaseline(
                            500.milliseconds,
                            { refreshes++ },
                        ) {
                            refreshes >= 2
                        }
                    }
                    .exceptionOrNull()
            assertNull(failure, "The first joined V1 refresh cannot establish restored V0")
            assertEquals(2, refreshes, "The first joined V1 refresh cannot establish restored V0")

            assertFailsWith<TimeoutCancellationException> {
                IncrementalAcceptanceDriver.awaitRestoredBaseline(
                    100.milliseconds,
                    { refreshes++ },
                ) {
                    false
                }
            }
        }

    @Test
    fun `failed topology retains structured failure and diagnostic progress`() {
        val root = Files.createDirectory(temporary.resolve("owned-failure"))
        Files.writeString(root.resolve(".indexino-benchmark-owned"), "fixture")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "rootProject.name = \"empty\"")
        val source = workspace.resolve("Unindexed.java")
        Files.writeString(source, "class Unindexed {}")
        val plan = temporary.resolve("failure-plan.json")
        Files.writeString(
            plan,
            """
            {"schema":1,"buildSystem":"gradle","target":":","files":[
            {"path":"Unindexed.java","module":":","package":""}],
            "groups":[{"id":"small","files":[0]}]}
            """
                .trimIndent(),
        )
        val output = temporary.resolve("failure-report.json")
        val previous = System.err
        val diagnostics = ByteArrayOutputStream()
        try {
            PrintStream(diagnostics).use { stream ->
                System.setErr(stream)
                assertFailsWith<IndexinoException> {
                    IncrementalAcceptanceDriver.main(
                        arrayOf(root.toString(), plan.toString(), "manual", "1", output.toString())
                    )
                }
            }
        } finally {
            System.setErr(previous)
        }
        val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertEquals("refresh_failed_3", report["failureCode"]?.jsonPrimitive?.content)
        assertEquals("TOPOLOGY", report["failureCategory"]?.jsonPrimitive?.content)
        assertEquals("seed", report["failureStage"]?.jsonPrimitive?.content)
        assertTrue(diagnostics.toString().contains("topology discovery failed: no source files"))
        assertEquals("class Unindexed {}", Files.readString(source))
        assertFalse(Files.readString(output).contains(temporary.toString()))
    }

    @Test
    fun `readiness rejects updated declarations without updated caller`() = runBlocking {
        val workspace = Files.createDirectory(temporary.resolve("negative"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"negative\"",
        )
        val source = workspace.resolve("src/main/java/Seed.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package fixture; class Seed {}")
        val plan = buildJsonObject {
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "src/main/java/Seed.java")
                            put("module", ":")
                            put("package", "fixture")
                        }
                    )
                ),
            )
            put("groups", JsonArray(listOf(group("small", listOf(0)))))
        }
        val workload = IncrementalWorkload(workspace, plan)
        val previousCache = System.getProperty("indexino.cache.dir")
        try {
            System.setProperty("indexino.cache.dir", temporary.resolve("negative-cache").toString())
            workload.write(workload.sources, 1)
            Files.writeString(
                source,
                Files.readString(source)
                    .replace("static int caller() { return probe(17, 29); }", ""),
            )
            Indexino.connect(
                    IndexinoConfiguration.forWorkspace(workspace)
                        .withRuntimeAttach(RuntimeAttachMode.IN_PROCESS)
                        .withAutoRefresh(AutoRefreshMode.DISABLED)
                )
                .use { index ->
                    index
                        .refresh(
                            RefreshRequest.forScope(IndexScope.gradle(":").includingDependencies())
                        )
                        .await()
                    index.snapshot().use {
                        var mismatch: Pair<Int, String>? = null
                        assertFalse(
                            workload.matches(it, workload.sources, 1) { ordinal, predicate ->
                                mismatch = ordinal to predicate
                            },
                            "Missing caller must not be ready",
                        )
                        assertEquals(0 to "caller", mismatch)
                    }
                }
        } finally {
            if (previousCache == null) System.clearProperty("indexino.cache.dir")
            else System.setProperty("indexino.cache.dir", previousCache)
        }
    }

    @Test
    fun `manual matrix verifies changed declarations signatures and retained snapshots`() =
        matrix("manual")

    @Test
    fun `watcher matrix verifies changed declarations signatures and retained snapshots`() =
        matrix("watcher")

    private fun matrix(lane: String) {
        val root = Files.createDirectory(temporary.resolve("owned"))
        Files.writeString(root.resolve(".indexino-benchmark-owned"), "invented-fixture\n")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "include(\":alpha\", \":beta\", \":gamma\")\n",
        )
        val files =
            (0..2).flatMap { module ->
                (0..2).map { number ->
                    val name = listOf("alpha", "beta", "gamma")[module]
                    val kind = if (number == 0) "java" else "kotlin"
                    val extension = if (number == 0) "java" else "kt"
                    val relative = "$name/src/main/$kind/Seed$number.$extension"
                    val path = workspace.resolve(relative)
                    Files.createDirectories(path.parent)
                    Files.writeString(path, "package fixture.$name;\nclass Seed$number {}\n")
                    buildJsonObject {
                        put("path", relative)
                        put("module", name)
                        put("package", "fixture.$name")
                    }
                }
            }
        val plan = temporary.resolve("plan.json")
        Files.writeString(
            plan,
            buildJsonObject {
                    put("schema", 1)
                    put("buildSystem", "gradle")
                    put("target", ":")
                    put("files", JsonArray(files))
                    put(
                        "groups",
                        JsonArray(
                            listOf(
                                group("small", listOf(0)),
                                group("medium", listOf(0, 1, 2)),
                                group("large", listOf(0, 3, 6)),
                            )
                        ),
                    )
                }
                .toString(),
        )
        val output = temporary.resolve("report.json")
        IncrementalAcceptanceDriver.main(
            arrayOf(root.toString(), plan.toString(), lane, "2", output.toString())
        )
        val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertEquals("passed", report.getValue("status").jsonPrimitive.content)
        if (lane == "watcher") {
            assertEquals(
                "5400000",
                report["watcherWaitMillis"]?.jsonPrimitive?.content,
                "The Mac medium generation published near the previous 60-minute deadline",
            )
        }
        val samples = report.getValue("samples").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("small", "medium", "large", "small", "medium", "large"),
            samples.map { it.getValue("scenario").jsonPrimitive.content },
        )
        assertEquals(
            listOf(1, 3, 3, 1, 3, 3),
            samples.map { it.getValue("changedFiles").jsonPrimitive.content.toInt() },
        )
        assertEquals(
            listOf(1, 1, 3, 1, 1, 3),
            samples.map { it.getValue("changedModules").jsonPrimitive.content.toInt() },
        )
        samples.forEach {
            assertEquals("true", it.getValue("pinnedVerified").jsonPrimitive.content)
            assertEquals("true", it.getValue("queriesVerified").jsonPrimitive.content)
            assertTrue(it.getValue("readyNanos").jsonPrimitive.content.toLong() > 0)
        }
        assertEquals(
            "package fixture.alpha;\nclass Seed0 {}\n",
            Files.readString(workspace.resolve("alpha/src/main/java/Seed0.java")),
        )
        assertFalse(Files.readString(output).contains(temporary.toString()))
    }

    private fun group(name: String, files: List<Int>) = buildJsonObject {
        put("id", name)
        put("files", JsonArray(files.map(::JsonPrimitive)))
    }
}
