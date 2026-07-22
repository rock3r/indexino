package dev.sebastiano.indexino.distribution

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("native-distribution")
class NativeCompatibilityTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `completed process does not wait for descendant held output streams`() {
        val childPidFile = tempDir.resolve("output-holder.pid")
        val command =
            if (requiredProperty("indexino.nativeTarget") == WINDOWS_X64) {
                arrayOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "${'$'}child = Start-Process -FilePath 'powershell.exe' " +
                        "-ArgumentList '-NoProfile','-NonInteractive','-Command'," +
                        "'Start-Sleep -Seconds 3' -PassThru; " +
                        "Set-Content -LiteralPath '${powershellQuote(childPidFile)}' " +
                        "-Value ${'$'}child.Id",
                )
            } else {
                arrayOf(
                    "/bin/sh",
                    "-c",
                    "sleep 3 & printf '%s' \"${'$'}!\" > \"${'$'}1\"",
                    "sh",
                    childPidFile.toString(),
                )
            }
        val startedAt = System.nanoTime()
        var child: ProcessHandle? = null

        try {
            val result = runCommand(tempDir, 10L, TimeUnit.SECONDS, *command)
            assertEquals(0, result.exitCode, result.diagnostic())
            assertTrue(Files.isRegularFile(childPidFile), "Output-holder PID was not recorded")
            child = ProcessHandle.of(childPidFile.readText().trim().toLong()).orElse(null)
            assertTrue(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000L,
                "Output capture waited for a descendant-held stream",
            )
        } finally {
            child?.destroyForcibly()
            child?.onExit()?.get(2L, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `timeout cleanup includes descendants created during termination`() {
        assumeTrue(
            requiredProperty("indexino.nativeTarget") != WINDOWS_X64,
            "Late-descendant fixture uses POSIX signal traps",
        )
        val lateChildPidFile = tempDir.resolve("late-child.pid")
        val command =
            arrayOf(
                "/bin/sh",
                "-c",
                "trap 'sleep 30 & printf \"%s\" \"${'$'}!\" > \"${'$'}1\"; " +
                    "sleep 1; exit 0' TERM; while :; do sleep 1; done",
                "sh",
                lateChildPidFile.toString(),
            )
        var lateChild: ProcessHandle? = null

        try {
            assertFailsWith<AssertionError> {
                runCommand(tempDir, 500L, TimeUnit.MILLISECONDS, *command)
            }
            assertTrue(Files.isRegularFile(lateChildPidFile), "Late child PID was not recorded")
            lateChild = ProcessHandle.of(lateChildPidFile.readText().trim().toLong()).orElse(null)
            assertFalse(lateChild?.isAlive == true, "Late timeout descendant is still alive")
        } finally {
            lateChild?.destroyForcibly()
            lateChild?.onExit()?.get(2L, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `timed out compatibility process is terminated`() {
        val childPidFile = tempDir.resolve("timed-out-child.pid")
        val command =
            if (requiredProperty("indexino.nativeTarget") == WINDOWS_X64) {
                arrayOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "${'$'}child = Start-Process -FilePath 'powershell.exe' " +
                        "-ArgumentList '-NoProfile','-NonInteractive','-Command'," +
                        "'Start-Sleep -Seconds 30' -PassThru; " +
                        "Set-Content -LiteralPath '${powershellQuote(childPidFile)}' " +
                        "-Value ${'$'}child.Id; Wait-Process -Id ${'$'}child.Id",
                )
            } else {
                arrayOf(
                    "/bin/sh",
                    "-c",
                    "sleep 30 & child=${'$'}!; printf '%s' \"${'$'}child\" > \"${'$'}1\"; " +
                        "wait \"${'$'}child\"",
                    "sh",
                    childPidFile.toString(),
                )
            }
        val startedAt = System.nanoTime()

        val failure =
            assertFailsWith<AssertionError> {
                runCommand(tempDir, 500L, TimeUnit.MILLISECONDS, *command)
            }

        assertContains(failure.message.orEmpty(), "timed out")
        assertTrue(Files.isRegularFile(childPidFile), "Timed-out child PID was not recorded")
        val childPid = childPidFile.readText().trim().toLong()
        assertFalse(
            ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
            "Timed-out descendant $childPid is still alive",
        )
        assertTrue(
            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < 5L,
            "Timed-out process cleanup exceeded five seconds",
        )
    }

    @Test
    fun `all distribution entry points preserve the golden CLI contract`() {
        val target = requiredProperty("indexino.nativeTarget")
        val java =
            requiredDirectory("indexino.targetJdkRoot")
                .resolve("bin")
                .resolve(if (target == WINDOWS_X64) "java.exe" else "java")
        val thinClasspath = requiredProperty("indexino.thinRuntimeClasspath")
        val entryPoints =
            listOf(
                EntryPoint(
                    "thin",
                    listOf(
                        java.toString(),
                        "-Duser.home=${tempDir.resolve("home-thin")}",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        thinClasspath,
                        MAIN_CLASS,
                    ),
                ),
                EntryPoint(
                    "fat",
                    listOf(
                        java.toString(),
                        "-Duser.home=${tempDir.resolve("home-fat")}",
                        "--enable-native-access=ALL-UNNAMED",
                        "-jar",
                        requiredFile("indexino.unshrunkJar").toString(),
                    ),
                ),
                EntryPoint(
                    "r8",
                    listOf(
                        java.toString(),
                        "-Duser.home=${tempDir.resolve("home-r8")}",
                        "--enable-native-access=ALL-UNNAMED",
                        "-jar",
                        requiredFile("indexino.r8Jar").toString(),
                    ),
                ),
                EntryPoint(
                    "roast-$target",
                    listOf(
                        extractArchive(
                                requiredFile("indexino.nativeArchive"),
                                target,
                                "compatibility-install",
                            )
                            .resolve(if (target == WINDOWS_X64) "indexino.exe" else "indexino")
                            .toString()
                    ),
                ),
            )

        val template = createFixtureWorkspace()
        val caller = tempDir.resolve("compatibility-caller").createDirectories()
        val commit = runCommand(template, "git", "rev-parse", "HEAD").stdout.trim()

        val snapshots = entryPoints.associate { entryPoint ->
            val workspace = tempDir.resolve("workspace-${entryPoint.name}")
            copyTree(template, workspace)
            entryPoint.name to snapshot(entryPoint, caller, workspace, commit)
        }

        val golden = snapshots.getValue("thin")
        snapshots.forEach { (name, snapshot) ->
            assertEquals(golden.results, snapshot.results, "$name CLI behavior differs")
            assertEquals(golden.manifest, snapshot.manifest, "$name manifest differs")
        }
    }

    private fun snapshot(
        entryPoint: EntryPoint,
        caller: Path,
        workspace: Path,
        commit: String,
    ): Snapshot {
        val results =
            linkedMapOf(
                "help" to run(entryPoint, caller, "--help"),
                "invalid" to run(entryPoint, caller, "not-a-command"),
                "fresh-index" to run(entryPoint, caller, *indexArguments(workspace)),
                "status" to run(entryPoint, caller, "status", "--project", workspace.toString()),
                "symbol-jsonl" to
                    run(
                        entryPoint,
                        caller,
                        "find-symbol",
                        "--project",
                        workspace.toString(),
                        "--name",
                        "Renderer",
                    ),
                "query-jsonl" to
                    run(
                        entryPoint,
                        caller,
                        "query",
                        "--project",
                        workspace.toString(),
                        "--application",
                        "selection-context",
                        "--preset",
                        "interactive-in-sc",
                        "--format",
                        "jsonl",
                    ),
            )
        assertEquals(0, results.getValue("help").exitCode, results.getValue("help").diagnostic())
        assertTrue(
            results.getValue("invalid").exitCode != 0,
            results.getValue("invalid").diagnostic(),
        )
        results
            .filterKeys { it != "invalid" }
            .forEach { (_, result) -> assertEquals(0, result.exitCode, result.diagnostic()) }

        val manifest = workspace.resolve(".indexino/index/$commit/manifest.json")
        assertManifestSchema(manifest)
        assertTrue(Files.isDirectory(manifest.parent.resolve("base.xodus")))
        return Snapshot(
            results.mapValues { (_, result) -> result.normalized(workspace) },
            normalizedManifest(manifest),
        )
    }

    private fun normalizedManifest(manifest: Path): Map<String, JsonElement> =
        Json.parseToJsonElement(manifest.readText()).jsonObject.filterKeys { it != "builtAt" }

    private fun assertManifestSchema(manifest: Path) {
        val json = Json.parseToJsonElement(manifest.readText()).jsonObject
        assertEquals(
            setOf(
                "commit",
                "indexerVersion",
                "scope",
                "topology",
                "includeDeps",
                "sourceFileCount",
                "sourcesContentHash",
                "builtAt",
                "applications",
            ),
            json.keys,
        )
        assertEquals(
            requiredProperty("indexino.version"),
            json.getValue("indexerVersion").jsonPrimitive.content,
        )
    }

    private fun indexArguments(workspace: Path): Array<String> =
        arrayOf(
            "index",
            "--project",
            workspace.toString(),
            "--build-system",
            "gradle",
            "--gradle-module",
            ":app",
            "--applications",
            "selection-context",
        )

    private fun createFixtureWorkspace(): Path {
        val workspace = tempDir.resolve("compatibility-template")
        workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"compatibility\"\ninclude(\":app\")\n")
        workspace
            .resolve("app/src/main/kotlin/sample/Panel.kt")
            .writeText(
                """
                package sample

                class Renderer {
                    fun render() = Unit
                }

                @Composable
                fun Content() {
                    SelectionContainer { ActionButton() }
                }
                """
                    .trimIndent()
            )
        runCommand(workspace, "git", "init")
        runCommand(workspace, "git", "config", "user.email", "distribution-test@example.invalid")
        runCommand(workspace, "git", "config", "user.name", "Distribution Test")
        runCommand(workspace, "git", "add", ".")
        val commit =
            runCommand(workspace, "git", "-c", "commit.gpgSign=false", "commit", "-m", "fixture")
        assertEquals(0, commit.exitCode, commit.diagnostic())
        return workspace
    }

    private fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(
                        path,
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        }
    }

    private fun extractArchive(archive: Path, target: String, name: String): Path {
        val destination = tempDir.resolve(name).createDirectories()
        val result =
            when (target) {
                MACOS_ARM64 ->
                    runCommand(
                        destination,
                        "/usr/bin/ditto",
                        "-x",
                        "-k",
                        archive.toString(),
                        destination.toString(),
                    )
                LINUX_X64 ->
                    runCommand(
                        destination,
                        "unzip",
                        "-q",
                        archive.toString(),
                        "-d",
                        destination.toString(),
                    )
                WINDOWS_X64 ->
                    runCommand(
                        destination,
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        "Expand-Archive -LiteralPath '${powershellQuote(archive)}' " +
                            "-DestinationPath '${powershellQuote(destination)}' -Force",
                    )
                else -> error("Unsupported native target: $target")
            }
        assertEquals(0, result.exitCode, result.diagnostic())
        return destination.resolve("indexino")
    }

    private fun run(
        entryPoint: EntryPoint,
        workingDirectory: Path,
        vararg arguments: String,
    ): ProcessResult =
        runCommand(workingDirectory, *(entryPoint.commandPrefix + arguments).toTypedArray())

    private fun runCommand(workingDirectory: Path, vararg command: String): ProcessResult {
        return runCommand(workingDirectory, PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES, *command)
    }

    private fun runCommand(
        workingDirectory: Path,
        timeout: Long,
        timeoutUnit: TimeUnit,
        vararg command: String,
    ): ProcessResult {
        val result =
            runCapturedProcess(
                workingDirectory,
                command.toList(),
                timeout = timeout,
                timeoutUnit = timeoutUnit,
                terminationTimeout = PROCESS_TERMINATION_GRACE_SECONDS,
                terminationTimeoutUnit = TimeUnit.SECONDS,
            )
        return ProcessResult(result.exitCode, result.stdout, result.stderr)
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing $name" }

    private fun requiredFile(name: String): Path =
        Path.of(requiredProperty(name)).also { assertTrue(Files.isRegularFile(it), "Missing $it") }

    private fun requiredDirectory(name: String): Path =
        Path.of(requiredProperty(name)).also { assertTrue(Files.isDirectory(it), "Missing $it") }

    private fun powershellQuote(value: Any): String = value.toString().replace("'", "''")

    private data class EntryPoint(val name: String, val commandPrefix: List<String>)

    private data class Snapshot(
        val results: Map<String, ProcessResult>,
        val manifest: Map<String, JsonElement>,
    )

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun normalized(workspace: Path): ProcessResult {
            fun normalize(value: String): String {
                val workspacePath = workspace.toAbsolutePath().toString()
                val knownRepresentations =
                    listOf(
                            workspacePath.replace("\\", "\\\\"),
                            workspacePath,
                            workspacePath.replace('\\', '/'),
                        )
                        .distinct()
                return knownRepresentations
                    .fold(value) { normalized, path -> normalized.replace(path, "<workspace>") }
                    .replace(BUILT_AT_JSON) { match -> "${match.groupValues[1]}<volatile>" }
            }
            return copy(stdout = normalize(stdout), stderr = normalize(stderr))
        }

        fun diagnostic(): String = "exit=$exitCode\nstdout:\n$stdout\nstderr:\n$stderr"
    }

    private companion object {
        const val MAIN_CLASS = "dev.sebastiano.indexino.cli.MainCommandKt"
        const val PROCESS_TIMEOUT_MINUTES = 3L
        const val PROCESS_TERMINATION_GRACE_SECONDS = 2L
        const val MACOS_ARM64 = "macos-arm64"
        const val LINUX_X64 = "linux-x64"
        const val WINDOWS_X64 = "windows-x64"
        val BUILT_AT_JSON = Regex("""("builtAt":")[^"]+""")
    }
}
