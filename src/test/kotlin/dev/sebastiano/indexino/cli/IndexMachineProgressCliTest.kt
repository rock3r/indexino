package dev.sebastiano.indexino.cli

import dev.sebastiano.indexino.producer.normalizeWorkspaceRelativePath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IndexMachineProgressCliTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `machine progress starts discovery before source total is known`() {
        val workspace = createWorkspace()

        val result =
            runCli(
                "index",
                "--project",
                workspace.toString(),
                "--build-system",
                "gradle",
                "--gradle-module",
                ":app",
                "--progress-format",
                "jsonl",
            )

        assertEquals(0, result.exitCode, result.stderr)
        val firstEvent = result.stdout.lineSequence().first()
        assertEquals(
            "{\"version\":1,\"event\":\"discovery_started\",\"phase\":\"discovery\",\"phaseTotal\":null}",
            firstEvent,
        )
        assertNull(
            result.stdout.lineSequence().drop(1).firstOrNull { it.contains("discovery_started") }
        )
    }

    @Test
    fun `machine progress reports known producer phase progress`() {
        val result = runMachineProgress(createWorkspace())

        assertEquals(0, result.exitCode, result.stderr)
        val javaProgress =
            parseEvents(result.stdout).single {
                it.value("event") == "progress" && it.value("phase") == "java-source"
            }
        assertEquals("1", javaProgress.value("phaseCompleted"))
        assertEquals("1", javaProgress.value("phaseTotal"))
        assertEquals("app/src/main/java/sample/Panel.java", javaProgress.value("currentFile"))
    }

    @Test
    fun `machine progress normalizes workspace relative paths`() {
        assertEquals(
            "app/src/main/java/sample/Panel.java",
            normalizeWorkspaceRelativePath("app\\src/main/./java/sample/Panel.java"),
        )
    }

    @Test
    fun `machine progress counters are monotonic`() {
        val result = runMachineProgress(createWorkspace())

        assertEquals(0, result.exitCode, result.stderr)
        val counterEvents = parseEvents(result.stdout).filter { it["changedFiles"] != null }
        assertTrue(counterEvents.isNotEmpty())
        counterEvents.zipWithNext().forEach { (previous, next) ->
            assertTrue(next.intValue("changedFiles") >= previous.intValue("changedFiles"))
            assertTrue(next.intValue("unchangedFiles") >= previous.intValue("unchangedFiles"))
            assertTrue(next.intValue("removedFiles") >= previous.intValue("removedFiles"))
        }
    }

    @Test
    fun `machine progress reports incremental change counters`() {
        val workspace = createWorkspace()
        val sourceRoot = workspace.resolve("app/src/main/java/sample")
        Files.writeString(
            sourceRoot.resolve("Untouched.java"),
            "package sample; public class Untouched {}",
        )
        Files.writeString(
            sourceRoot.resolve("Removed.java"),
            "package sample; public class Removed {}",
        )
        assertEquals(0, runMachineProgress(workspace).exitCode)
        Files.writeString(
            sourceRoot.resolve("Panel.java"),
            "package sample; public class Panel { int changed; }",
        )
        Files.delete(sourceRoot.resolve("Removed.java"))

        val result = runMachineProgress(workspace)

        assertEquals(0, result.exitCode, result.stderr)
        val changes = parseEvents(result.stdout).single { it.value("event") == "changes_detected" }
        assertEquals("1", changes.value("changedFiles"))
        assertEquals("1", changes.value("unchangedFiles"))
        assertEquals("1", changes.value("removedFiles"))
    }

    @Test
    fun `machine progress ends with completed event`() {
        val result = runMachineProgress(createWorkspace())

        assertEquals(0, result.exitCode, result.stderr)
        val terminal = parseEvents(result.stdout).last()
        assertEquals("completed", terminal.value("event"))
        assertEquals("indexed", terminal.value("outcome"))
    }

    @Test
    fun `machine progress emits failed event for producer failure`() {
        val workspace = createWorkspace()
        Files.writeString(
            workspace.resolve("app/src/main/java/sample/Panel.java"),
            "package sample; public class Panel {",
        )

        val result = runMachineProgress(workspace)

        assertFalse(result.exitCode == 0, result.stderr)
        val failed = parseEvents(result.stdout).last()
        assertEquals("failed", failed.value("event"))
        assertEquals(result.exitCode.toString(), failed.value("exitCode"))
    }

    @Test
    fun `machine progress reports fresh completion without rebuilding`() {
        val workspace = createWorkspace()
        assertEquals(0, runMachineProgress(workspace).exitCode)

        val freshResult = runMachineProgress(workspace)

        assertEquals(0, freshResult.exitCode, freshResult.stderr)
        val terminal = parseEvents(freshResult.stdout).last()
        assertEquals("completed", terminal.value("event"))
        assertEquals("fresh", terminal.value("outcome"))
    }

    @Test
    fun `default progress remains human readable on stderr`() {
        val workspace = createWorkspace()
        val result =
            runCli(
                "index",
                "--project",
                workspace.toString(),
                "--build-system",
                "gradle",
                "--gradle-module",
                ":app",
            )

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("JavaSourceProducer"))
        assertTrue(result.stderr.contains("[1/1] app/src/main/java/sample/Panel.java"))
        assertFalse(result.stderr.contains("\"event\""))
    }

    private fun runMachineProgress(workspace: Path): CliResult =
        runCli(
            "index",
            "--project",
            workspace.toString(),
            "--build-system",
            "gradle",
            "--gradle-module",
            ":app",
            "--progress-format",
            "jsonl",
        )

    private fun parseEvents(output: String): List<JsonObject> =
        output
            .lineSequence()
            .filter(String::isNotBlank)
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()

    private fun JsonObject.value(name: String): String? = this[name]?.jsonPrimitive?.content

    private fun JsonObject.intValue(name: String): Int =
        value(name)?.toInt() ?: error("missing $name")

    private fun createWorkspace(): Path {
        val workspace = createTempDirectory("machine-progress-cli-")
        tempDirs.add(workspace)
        Files.writeString(workspace.resolve("settings.gradle.kts"), "include(\":app\")")
        val sourceRoot = workspace.resolve("app/src/main/java/sample")
        Files.createDirectories(sourceRoot)
        Files.writeString(sourceRoot.resolve("Panel.java"), "package sample; public class Panel {}")
        runGit(workspace, "init")
        runGit(workspace, "config", "user.email", "test@example.com")
        runGit(workspace, "config", "user.name", "Test User")
        runGit(workspace, "add", ".")
        runGit(workspace, "commit", "-m", "fixture")
        return workspace
    }

    private fun runCli(vararg args: String): CliResult {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                    javaExecutable,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.sebastiano.indexino.cli.MainCommandKt",
                    *args,
                )
                .start()
        var stdout = ""
        var stderr = ""
        val stdoutReader = thread { stdout = process.inputStream.bufferedReader().readText() }
        val stderrReader = thread { stderr = process.errorStream.bufferedReader().readText() }
        val exitCode = process.waitFor()
        stdoutReader.join()
        stderrReader.join()
        return CliResult(exitCode, stdout, stderr)
    }

    private fun runGit(workspace: Path, vararg args: String) {
        val process =
            ProcessBuilder(*listOf("git", "-C", workspace.toString(), *args).toTypedArray())
                .redirectErrorStream(true)
                .start()
        check(process.waitFor() == 0)
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)
}
