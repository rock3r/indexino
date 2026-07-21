package dev.sebastiano.indexino.distribution

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("native-distribution")
class NativeDistributionTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `archive exposes the flat runtime metadata and license contract`() {
        val archive = requiredFile("indexino.nativeArchive")
        val target = requiredProperty("indexino.nativeTarget")
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().associateBy { it.name }
            assertTrue(entries.isNotEmpty(), "Native distribution is empty: $archive")
            assertTrue(entries.keys.all { it.startsWith("indexino/") })
            assertFalse(entries.keys.any { it.contains(".app/") }, "Tier 1 archives must be flat")

            val launcher = entries.getValue(launcherEntry(target))
            assertFalse(launcher.isDirectory)
            val applicationJar = entries.getValue("indexino/indexino-cli.jar")
            assertEquals(NORMALIZED_JAR_MTIME_MILLIS, applicationJar.time)
            val launcherConfiguration =
                zip.getInputStream(entries.getValue("indexino/app/indexino.json"))
                    .bufferedReader()
                    .use { it.readText() }
            assertContains(launcherConfiguration, "\"classPath\":[\"indexino-cli.jar\"]")
            assertContains(
                launcherConfiguration,
                "\"mainClass\":\"dev.sebastiano.indexino.cli.MainCommandKt\"",
            )
            assertContains(launcherConfiguration, "\"runOnFirstThread\":true")
            assertContains(launcherConfiguration, "\"useZgcIfSupportedOs\":false")
            assertContains(
                launcherConfiguration,
                "\"vmArgs\":[\"--enable-native-access=ALL-UNNAMED\"]",
            )
            assertFalse(entries.containsKey(runtimeJavaEntry(target)))

            val release =
                zip.getInputStream(entries.getValue("indexino/runtime/release"))
                    .bufferedReader()
                    .use { it.readText() }
            val runtimeModules =
                release
                    .lineSequence()
                    .first { it.startsWith("MODULES=\"") }
                    .removePrefix("MODULES=\"")
                    .removeSuffix("\"")
                    .split(' ')
                    .toSet()
            assertTrue(
                runtimeModules.containsAll(REQUIRED_MODULES),
                "Missing required modules: ${REQUIRED_MODULES - runtimeModules}",
            )

            val packagedLicense = entries.getValue("indexino/licenses/indexino-LICENSE")
            val packagedLicenseBytes = zip.getInputStream(packagedLicense).use { it.readBytes() }
            assertTrue(
                packagedLicenseBytes.contentEquals(
                    Files.readAllBytes(requiredFile("indexino.applicationLicense"))
                ),
                "Packaged application license differs from LICENSE",
            )
            runtimeModules.forEach { module ->
                REQUIRED_JBR_LEGAL_FILES.forEach { file ->
                    assertTrue(
                        entries.containsKey("indexino/runtime/legal/$module/$file"),
                        "Missing JBR legal file $file for $module",
                    )
                }
            }
        }

        if (target != WINDOWS_X64) {
            val entries = readZipCentralDirectory(archive).associateBy(ZipEntryMetadata::name)
            assertEquals(POSIX_EXECUTABLE_MODE, entries.getValue(launcherEntry(target)).unixMode)
            assertEquals(POSIX_DIRECTORY_MODE, entries.getValue("indexino/runtime/").unixMode)
            assertEquals(
                POSIX_EXECUTABLE_MODE,
                entries.getValue("indexino/runtime/lib/jspawnhelper").unixMode,
            )
            assertEquals(POSIX_FILE_MODE, entries.getValue("indexino/indexino-cli.jar").unixMode)
            assertEquals(
                POSIX_FILE_MODE,
                entries.getValue("indexino/licenses/indexino-LICENSE").unixMode,
            )
        }
    }

    @Test
    fun `packaging tools resolve inside the pinned target JBR`() {
        val target = requiredProperty("indexino.nativeTarget")
        val targetJdkRoot = requiredDirectory("indexino.targetJdkRoot").toRealPath()
        val expectedJbrVersion = requiredProperty("indexino.expectedJbrVersion")
        val expectedVersion = expectedJbrVersion.substringBefore('b')
        val expectedBuild = expectedJbrVersion.substringAfter('b')
        val extension = if (target == WINDOWS_X64) ".exe" else ""
        mapOf("jlink" to "--version", "jdeps" to "--version", "javap" to "-version").forEach {
            (tool, versionArgument) ->
            val executable = targetJdkRoot.resolve("bin/$tool$extension").toRealPath()
            assertTrue(executable.startsWith(targetJdkRoot))
            val result = runCommand(targetJdkRoot, executable.toString(), versionArgument)
            assertEquals(0, result.exitCode, result.diagnostic())
            assertContains(result.stdout + result.stderr, expectedVersion)
        }
        val java = targetJdkRoot.resolve("bin/java$extension").toRealPath()
        val javaVersion = runCommand(targetJdkRoot, java.toString(), "-version")
        assertEquals(0, javaVersion.exitCode, javaVersion.diagnostic())
        assertContains(
            javaVersion.stdout + javaVersion.stderr,
            "$expectedVersion+9-b$expectedBuild",
        )
    }

    @Test
    fun `standard extraction preserves the actual launcher and process helper`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation = extractArchive(requiredFile("indexino.nativeArchive"), target, "install")
        val launcher = installation.resolve(launcherRelativePath(target))
        assertTrue(Files.isRegularFile(launcher), "Missing launcher: $launcher")
        assertFalse(Files.exists(installation.resolve(runtimeJavaRelativePath(target))))
        if (target != WINDOWS_X64) {
            val processHelper = installation.resolve("runtime/lib/jspawnhelper")
            assertTrue(Files.isExecutable(launcher), "Launcher is not executable: $launcher")
            assertTrue(
                Files.isExecutable(processHelper),
                "jspawnhelper is not executable: $processHelper",
            )
        }

        val help = runLauncher(launcher, tempDir.resolve("caller").createDirectories(), "--help")
        assertEquals(0, help.exitCode, help.diagnostic())
        assertContains(help.stdout, "Usage: indexino")
    }

    @Test
    fun `actual launcher preserves the complete cli workload after relocation`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation =
            extractArchive(requiredFile("indexino.nativeArchive"), target, "workload")
        val caller = tempDir.resolve("arbitrary-caller").createDirectories()
        var launcher = installation.resolve(launcherRelativePath(target))
        val workspace = createFixtureWorkspace()

        assertCompleteWorkload(launcher, caller, workspace)
        assertCallerRelativeInvocation(launcher, caller, workspace)

        val relocatedParent = tempDir.resolve("relocated").createDirectories()
        val relocated = relocatedParent.resolve("indexino")
        Files.move(installation, relocated, StandardCopyOption.ATOMIC_MOVE)
        launcher = relocated.resolve(launcherRelativePath(target))

        assertCallerRelativeInvocation(launcher, caller, workspace)
        val query =
            runLauncher(
                launcher,
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
            )
        assertEquals(0, query.exitCode, query.diagnostic())
        assertContains(query.stdout, "\"callee\":\"ActionButton\"")
    }

    @Test
    fun `missing git reports the external tool requirement`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation = extractArchive(requiredFile("indexino.nativeArchive"), target, "no-git")
        val launcher = installation.resolve(launcherRelativePath(target))
        val workspace = createFixtureWorkspace()
        val emptyPath = tempDir.resolve("empty-path").createDirectories()
        val result =
            runLauncher(
                launcher,
                tempDir,
                "index",
                "--project",
                workspace.toString(),
                "--build-system",
                "gradle",
                "--gradle-module",
                ":app",
                environment = mapOf("PATH" to emptyPath.toString()),
            )
        assertTrue(result.exitCode != 0, result.diagnostic())
        assertContains(result.stdout + result.stderr, "git")
        assertContains(result.stdout + result.stderr, "Cannot run program")
    }

    @Test
    fun `windows console launchers wait redirect and propagate failures`() {
        val target = requiredProperty("indexino.nativeTarget")
        assumeTrue(target == WINDOWS_X64, "Windows console contract runs on Windows x64 only")

        val installation = extractArchive(requiredFile("indexino.nativeArchive"), target, "windows")
        val launcher = installation.resolve(launcherRelativePath(target))
        val powershellOutput = tempDir.resolve("powershell.out")
        val powershellError = tempDir.resolve("powershell.err")
        val powershell =
            runCommand(
                tempDir,
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                powershellInvocation(launcher, powershellOutput, powershellError, "--help"),
            )
        assertEquals(0, powershell.exitCode, powershell.diagnostic())
        assertContains(readRedirectedText(powershellOutput), "Usage: indexino")

        val cmdOutput = tempDir.resolve("cmd.out")
        val cmdError = tempDir.resolve("cmd.err")
        val cmd =
            runCommand(
                tempDir,
                "cmd.exe",
                "/d",
                "/s",
                "/c",
                "\"${launcher}\" --help 1>\"$cmdOutput\" 2>\"$cmdError\"",
            )
        assertEquals(0, cmd.exitCode, cmd.diagnostic())
        assertContains(cmdOutput.readText(), "Usage: indexino")

        val invalidCmdOutput = tempDir.resolve("cmd-invalid.out")
        val invalidCmdError = tempDir.resolve("cmd-invalid.err")
        val invalidCmd =
            runCommand(
                tempDir,
                "cmd.exe",
                "/d",
                "/s",
                "/c",
                "\"${launcher}\" not-a-command 1>\"$invalidCmdOutput\" 2>\"$invalidCmdError\"",
            )
        assertTrue(invalidCmd.exitCode != 0, invalidCmd.diagnostic())
        assertContains(invalidCmdError.readText(), "no such subcommand")

        val invalid =
            runCommand(
                tempDir,
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                powershellInvocation(
                    launcher,
                    tempDir.resolve("invalid.out"),
                    tempDir.resolve("invalid.err"),
                    "not-a-command",
                ),
            )
        assertTrue(invalid.exitCode != 0, invalid.diagnostic())
        assertContains(tempDir.resolve("invalid.err").readText(), "no such subcommand")
    }

    @Test
    fun `windows console launcher terminates on ctrl c`() {
        val target = requiredProperty("indexino.nativeTarget")
        assumeTrue(target == WINDOWS_X64, "Windows Ctrl-C contract runs on Windows x64 only")

        val installation = extractArchive(requiredFile("indexino.nativeArchive"), target, "ctrl-c")
        val launcher = installation.resolve(launcherRelativePath(target))
        val workspace = createInterruptFixtureWorkspace()
        val script = tempDir.resolve("verify-ctrl-c.ps1")
        script.writeText(ctrlCVerificationScript(launcher, workspace))

        val result =
            runCommand(
                tempDir,
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
            )
        assertEquals(0, result.exitCode, result.diagnostic())
        assertContains(result.stdout, "CTRL_C_EVENT terminated launcher")
    }

    private fun assertCompleteWorkload(launcher: Path, caller: Path, workspace: Path) {
        assertIndexLifecycle(launcher, caller, workspace)
        assertLookups(launcher, caller, workspace)

        val commit = runCommand(workspace, "git", "rev-parse", "HEAD").stdout.trim()
        val indexRoot = workspace.resolve(".indexino/index/$commit")
        assertTrue(Files.isDirectory(indexRoot.resolve("base.xodus")))
        assertTrue(Files.isRegularFile(indexRoot.resolve("manifest.json")))
    }

    private fun assertCallerRelativeInvocation(launcher: Path, caller: Path, workspace: Path) {
        val relativeLauncher = caller.relativize(launcher)
        val relativeWorkspace = caller.relativize(workspace)
        val status =
            if (launcher.fileName.toString().endsWith(".exe")) {
                runCommand(
                    caller,
                    "cmd.exe",
                    "/d",
                    "/s",
                    "/c",
                    "call \"$relativeLauncher\" status --project \"$relativeWorkspace\"",
                )
            } else {
                runCommand(
                    caller,
                    relativeLauncher.toString(),
                    "status",
                    "--project",
                    relativeWorkspace.toString(),
                )
            }
        assertEquals(0, status.exitCode, status.diagnostic())
        assertContains(status.stdout, "selection-context")
    }

    private fun assertIndexLifecycle(launcher: Path, caller: Path, workspace: Path) {
        val index =
            runLauncher(
                launcher,
                caller,
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
        assertEquals(0, index.exitCode, index.diagnostic())

        val status = runLauncher(launcher, caller, "status", "--project", workspace.toString())
        assertEquals(0, status.exitCode, status.diagnostic())
        assertContains(status.stdout, "selection-context")

        val freshIndex =
            runLauncher(
                launcher,
                caller,
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
        assertEquals(0, freshIndex.exitCode, freshIndex.diagnostic())
        assertContains(freshIndex.stderr, "index fresh")
    }

    private fun assertLookups(launcher: Path, caller: Path, workspace: Path) {
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "find-symbol",
            "Renderer",
            "sample.Renderer",
        )
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "find-references",
            "sample.Renderer#render",
            "sample.Renderer#render",
        )
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "find-symbol",
            "JavaPanel",
            "sample.JavaPanel",
        )
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "find-references",
            "sample.JavaPanel#render",
            "app/src/main/java/sample/JavaPanel.java",
        )
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "resolve-resource",
            "string:title",
            "res:string:title",
        )
        assertLookupContains(
            launcher,
            caller,
            workspace,
            "find-references",
            "@string/title",
            "app/src/main/res/layout/main.xml",
        )

        val query =
            runLauncher(
                launcher,
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
            )
        assertEquals(0, query.exitCode, query.diagnostic())
        assertContains(query.stdout, "\"callee\":\"ActionButton\"")
    }

    private fun assertLookupContains(
        launcher: Path,
        caller: Path,
        workspace: Path,
        command: String,
        query: String,
        expected: String,
    ) {
        val arguments =
            when (command) {
                "find-symbol" -> arrayOf("--name", query)
                "find-references" -> arrayOf("--symbol", query)
                "resolve-resource" ->
                    arrayOf(
                        "--type",
                        query.substringBefore(':'),
                        "--name",
                        query.substringAfter(':'),
                    )
                else -> error("Unsupported lookup command: $command")
            }
        val result =
            runLauncher(launcher, caller, command, "--project", workspace.toString(), *arguments)
        assertEquals(0, result.exitCode, result.diagnostic())
        assertContains(result.stdout, expected)
    }

    private fun createFixtureWorkspace(): Path {
        val workspace = tempDir.resolve("fixture")
        workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace.resolve("app/src/main/java/sample").createDirectories()
        workspace.resolve("app/src/main/res/values").createDirectories()
        workspace.resolve("app/src/main/res/layout").createDirectories()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"native-smoke\"\ninclude(\":app\")\n")
        workspace
            .resolve("app/src/main/kotlin/sample/Panel.kt")
            .writeText(
                """
                package sample

                class Renderer {
                    fun render() = Unit
                }

                fun callRenderer(renderer: Renderer) {
                    renderer.render()
                }

                @Composable
                fun Content() {
                    SelectionContainer { ActionButton() }
                }
                """
                    .trimIndent()
            )
        workspace
            .resolve("app/src/main/java/sample/JavaPanel.java")
            .writeText(
                """
                package sample;

                public final class JavaPanel {
                    public void render() {}
                    public void call() { render(); }
                }
                """
                    .trimIndent()
            )
        workspace
            .resolve("app/src/main/res/values/strings.xml")
            .writeText("<resources><string name=\"title\">Title</string></resources>\n")
        workspace
            .resolve("app/src/main/res/layout/main.xml")
            .writeText(
                """
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:text="@string/title" />
                """
                    .trimIndent()
            )
        initializeGitFixture(workspace)
        return workspace
    }

    private fun createInterruptFixtureWorkspace(): Path {
        val workspace = tempDir.resolve("interrupt-fixture")
        val sources = workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"interrupt-fixture\"\ninclude(\":app\")\n")
        repeat(INTERRUPT_FIXTURE_FILE_COUNT) { index ->
            sources
                .resolve("Generated$index.kt")
                .writeText("package sample\nclass Generated$index { fun value() = $index }\n")
        }
        initializeGitFixture(workspace)
        return workspace
    }

    private fun initializeGitFixture(workspace: Path) {
        runCommand(workspace, "git", "init")
        runCommand(workspace, "git", "config", "user.email", "distribution-test@example.invalid")
        runCommand(workspace, "git", "config", "user.name", "Distribution Test")
        runCommand(workspace, "git", "add", ".")
        val commit =
            runCommand(workspace, "git", "-c", "commit.gpgSign=false", "commit", "-m", "fixture")
        assertEquals(0, commit.exitCode, commit.diagnostic())
    }

    private fun ctrlCVerificationScript(launcher: Path, workspace: Path): String {
        val commandLine =
            "\"$launcher\" index --project \"$workspace\" " +
                "--build-system gradle --gradle-module :app"
        val nativeConsoleBase64 =
            Base64.getEncoder().encodeToString(nativeConsoleTypeDefinition.toByteArray())
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}nativeConsoleSource = [Text.Encoding]::UTF8.GetString(
                [Convert]::FromBase64String('$nativeConsoleBase64'))
            Add-Type -TypeDefinition ${'$'}nativeConsoleSource

            ${'$'}startup = New-Object NativeConsole+STARTUPINFO
            ${'$'}startup.cb = [Runtime.InteropServices.Marshal]::SizeOf(${'$'}startup)
            ${'$'}process = New-Object NativeConsole+PROCESS_INFORMATION
            ${'$'}commandLine = New-Object Text.StringBuilder '${powershellQuote(commandLine)}'
            ${'$'}created = [NativeConsole]::CreateProcess(
                ${'$'}null,
                ${'$'}commandLine,
                [IntPtr]::Zero,
                [IntPtr]::Zero,
                ${'$'}false,
                0x00000010,
                [IntPtr]::Zero,
                '${powershellQuote(workspace)}',
                [ref]${'$'}startup,
                [ref]${'$'}process)
            if (-not ${'$'}created) { throw "CreateProcess failed: ${'$'}([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }

            try {
                Start-Sleep -Milliseconds 1500
                [UInt32]${'$'}exitCode = 0
                if (-not [NativeConsole]::GetExitCodeProcess(${'$'}process.hProcess, [ref]${'$'}exitCode)) {
                    throw 'GetExitCodeProcess failed before CTRL_C_EVENT'
                }
                if (${'$'}exitCode -ne 259) {
                    throw "Launcher exited before CTRL_C_EVENT with code ${'$'}exitCode"
                }
                [void][NativeConsole]::FreeConsole()
                if (-not [NativeConsole]::AttachConsole(${'$'}process.dwProcessId)) {
                    throw "AttachConsole failed: ${'$'}([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
                }
                if (-not [NativeConsole]::SetConsoleCtrlHandler([IntPtr]::Zero, ${'$'}true)) {
                    throw 'Could not ignore CTRL_C_EVENT in the verifier process'
                }
                if (-not [NativeConsole]::GenerateConsoleCtrlEvent(0, 0)) {
                    throw "GenerateConsoleCtrlEvent failed: ${'$'}([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
                }
                Start-Sleep -Milliseconds 100
                [void][NativeConsole]::FreeConsole()

                if ([NativeConsole]::WaitForSingleObject(${'$'}process.hProcess, 15000) -ne 0) {
                    [void][NativeConsole]::TerminateProcess(${'$'}process.hProcess, 1)
                    throw 'Launcher did not terminate within 15 seconds of CTRL_C_EVENT'
                }
                if (-not [NativeConsole]::GetExitCodeProcess(${'$'}process.hProcess, [ref]${'$'}exitCode)) {
                    throw 'GetExitCodeProcess failed after CTRL_C_EVENT'
                }
                if (${'$'}exitCode -eq 0 -or ${'$'}exitCode -eq 259) {
                    throw "CTRL_C_EVENT produced invalid exit code ${'$'}exitCode"
                }
                Write-Output "CTRL_C_EVENT terminated launcher with exit code ${'$'}exitCode"
            } finally {
                [void][NativeConsole]::CloseHandle(${'$'}process.hThread)
                [void][NativeConsole]::CloseHandle(${'$'}process.hProcess)
            }
            """
            .trimIndent()
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

    private fun runLauncher(
        launcher: Path,
        workingDirectory: Path,
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult =
        runCommand(
            workingDirectory,
            launcher.toAbsolutePath().toString(),
            *arguments,
            environment = environment,
        )

    private fun runCommand(
        workingDirectory: Path,
        vararg command: String,
        environment: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val processBuilder = ProcessBuilder(*command).directory(workingDirectory.toFile())
        processBuilder.environment().putAll(environment)
        val process = processBuilder.redirectErrorStream(false).start()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val stdout = executor.submit<String> { process.inputStream.bufferedReader().readText() }
            val stderr = executor.submit<String> { process.errorStream.bufferedReader().readText() }
            val completed = process.waitFor(PROCESS_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (!completed) {
                val terminated =
                    process
                        .destroyForcibly()
                        .waitFor(PROCESS_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                process.inputStream.close()
                process.errorStream.close()
                stdout.cancel(true)
                stderr.cancel(true)
                assertTrue(terminated, "Could not terminate: ${command.joinToString(" ")}")
                error("Timed out: ${command.joinToString(" ")}")
            }
            return ProcessResult(process.exitValue(), stdout.get(), stderr.get())
        }
    }

    private fun launcherEntry(target: String): String =
        "indexino/${launcherRelativePath(target).toString().replace('\\', '/')}"

    private fun launcherRelativePath(target: String): Path =
        if (target == WINDOWS_X64) Path.of("indexino.exe") else Path.of("indexino")

    private fun runtimeJavaEntry(target: String): String =
        "indexino/${runtimeJavaRelativePath(target).toString().replace('\\', '/')}"

    private fun runtimeJavaRelativePath(target: String): Path =
        Path.of("runtime", "bin", if (target == WINDOWS_X64) "java.exe" else "java")

    private fun powershellInvocation(
        launcher: Path,
        stdout: Path,
        stderr: Path,
        argument: String,
    ): String =
        "& '${powershellQuote(launcher)}' '$argument' " +
            "1>'${powershellQuote(stdout)}' 2>'${powershellQuote(stderr)}'; " +
            "exit ${'$'}LASTEXITCODE"

    private fun powershellQuote(value: Any): String = value.toString().replace("'", "''")

    private fun readRedirectedText(path: Path): String {
        val bytes = Files.readAllBytes(path)
        return when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            bytes.size >= 3 &&
                bytes[0] == 0xef.toByte() &&
                bytes[1] == 0xbb.toByte() &&
                bytes[2] == 0xbf.toByte() ->
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing $name" }

    private fun requiredFile(property: String): Path {
        val path = Path.of(requiredProperty(property))
        assertTrue(Files.isRegularFile(path), "Missing file at $path")
        return path
    }

    private fun requiredDirectory(property: String): Path {
        val path = Path.of(requiredProperty(property))
        assertTrue(Files.isDirectory(path), "Missing directory at $path")
        return path
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun diagnostic(): String = "exit=$exitCode\nstdout:\n$stdout\nstderr:\n$stderr"
    }

    private companion object {
        val nativeConsoleTypeDefinition =
            """
            using System;
            using System.Runtime.InteropServices;
            using System.Text;

            public static class NativeConsole {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                public struct STARTUPINFO {
                    public Int32 cb;
                    public string lpReserved;
                    public string lpDesktop;
                    public string lpTitle;
                    public Int32 dwX;
                    public Int32 dwY;
                    public Int32 dwXSize;
                    public Int32 dwYSize;
                    public Int32 dwXCountChars;
                    public Int32 dwYCountChars;
                    public Int32 dwFillAttribute;
                    public Int32 dwFlags;
                    public Int16 wShowWindow;
                    public Int16 cbReserved2;
                    public IntPtr lpReserved2;
                    public IntPtr hStdInput;
                    public IntPtr hStdOutput;
                    public IntPtr hStdError;
                }

                [StructLayout(LayoutKind.Sequential)]
                public struct PROCESS_INFORMATION {
                    public IntPtr hProcess;
                    public IntPtr hThread;
                    public UInt32 dwProcessId;
                    public UInt32 dwThreadId;
                }

                [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
                public static extern bool CreateProcess(
                    string applicationName,
                    StringBuilder commandLine,
                    IntPtr processAttributes,
                    IntPtr threadAttributes,
                    bool inheritHandles,
                    UInt32 creationFlags,
                    IntPtr environment,
                    string currentDirectory,
                    ref STARTUPINFO startupInfo,
                    out PROCESS_INFORMATION processInformation);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool FreeConsole();

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool AttachConsole(UInt32 processId);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool GenerateConsoleCtrlEvent(UInt32 ctrlEvent, UInt32 processGroupId);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern UInt32 WaitForSingleObject(IntPtr handle, UInt32 milliseconds);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool GetExitCodeProcess(IntPtr process, out UInt32 exitCode);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool TerminateProcess(IntPtr process, UInt32 exitCode);

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool CloseHandle(IntPtr handle);
            }
            """
                .trimIndent()

        const val MACOS_ARM64 = "macos-arm64"
        const val LINUX_X64 = "linux-x64"
        const val WINDOWS_X64 = "windows-x64"
        const val NORMALIZED_JAR_MTIME_MILLIS = 1_700_000_000_000L
        const val POSIX_EXECUTABLE_MODE = 493
        const val POSIX_DIRECTORY_MODE = 493
        const val POSIX_FILE_MODE = 420
        const val PROCESS_TIMEOUT_MINUTES = 5L
        const val PROCESS_KILL_TIMEOUT_SECONDS = 10L
        const val INTERRUPT_FIXTURE_FILE_COUNT = 2_000
        val REQUIRED_MODULES = setOf("jdk.compiler", "jdk.unsupported", "jdk.crypto.ec")
        val REQUIRED_JBR_LEGAL_FILES =
            setOf("LICENSE", "ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION", "README.JAVASE")
    }
}
