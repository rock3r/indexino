package dev.sebastiano.indexino.distribution

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
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
        val aotCache = assertSeparateAotCacheInput(target)
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().associateBy { it.name }
            assertTrue(entries.isNotEmpty(), "Native distribution is empty: $archive")
            assertTrue(entries.keys.all { it.startsWith("indexino/") })
            assertFalse(entries.keys.any { it.contains(".app/") }, "Tier 1 archives must be flat")

            val launcher = entries.getValue(launcherEntry(target))
            assertFalse(launcher.isDirectory)
            val applicationJar = entries.getValue("indexino/indexino-cli.jar")
            if (target != MACOS_ARM64) {
                assertEquals(NORMALIZED_JAR_MTIME_MILLIS, applicationJar.time)
            }
            assertEquals(
                Files.size(requiredFile("indexino.normalizedApplicationJar")),
                applicationJar.size,
            )
            val packagedApplicationBytes = zip.getInputStream(applicationJar).use { it.readBytes() }
            assertTrue(
                packagedApplicationBytes.contentEquals(
                    Files.readAllBytes(requiredFile("indexino.normalizedApplicationJar"))
                ),
                "Packaged application JAR differs from the normalized training input",
            )
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
            assertFalse(launcherConfiguration.contains("AOTMode"))
            assertFalse(launcherConfiguration.contains("-Xlog:aot"))
            assertFalse(entries.containsKey(runtimeJavaEntry(target)))
            assertPackagedAotCache(zip, entries, aotCache, target)

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
            assertRuntimeLegalTreeMatches(
                zip,
                entries,
                requiredDirectory("indexino.targetRuntimeImage").resolve("legal"),
            )
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
            assertEquals(POSIX_FILE_MODE, entries.getValue(aotCacheEntry(target)).unixMode)
            assertEquals(
                POSIX_FILE_MODE,
                entries.getValue("indexino/licenses/indexino-LICENSE").unixMode,
            )
        }
    }

    private fun assertSeparateAotCacheInput(target: String): Path {
        val aotCache = requiredFile("indexino.aotCache")
        val targetRuntimeImage = requiredDirectory("indexino.targetRuntimeImage")
        assertTrue(Files.size(aotCache) > 0L, "AOT cache is empty")
        assertFalse(
            aotCache.toRealPath().startsWith(targetRuntimeImage.toRealPath()),
            "AOT training must not write into the final runtime input",
        )
        assertFalse(
            Files.exists(targetRuntimeImage.resolve(aotCacheRuntimePath(target))),
            "The final runtime input must remain cache-free",
        )
        return aotCache
    }

    private fun assertPackagedAotCache(
        zip: ZipFile,
        entries: Map<String, java.util.zip.ZipEntry>,
        aotCache: Path,
        target: String,
    ) {
        val packagedAotCache = entries.getValue(aotCacheEntry(target))
        assertTrue(
            zip.getInputStream(packagedAotCache)
                .use { it.readBytes() }
                .contentEquals(Files.readAllBytes(aotCache)),
            "Packaged AOT overlay differs from the task-owned cache",
        )
    }

    private fun aotCacheEntry(target: String): String =
        "indexino/runtime/${aotCacheRuntimePath(target)}"

    private fun aotCacheRuntimePath(target: String): String =
        if (target == WINDOWS_X64) "bin/server/classes.jsa" else "lib/server/classes.jsa"

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
        assertEquals(
            FileTime.fromMillis(NORMALIZED_JAR_MTIME_MILLIS),
            Files.getLastModifiedTime(installation.resolve("indexino-cli.jar")),
        )
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
    fun `strict verification loads the default AOT cache before and after relocation`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation =
            extractArchive(requiredFile("indexino.nativeArchive"), target, "aot-strict")
        configureAotVerification(installation, "on")
        val caller = tempDir.resolve("aot-strict-caller").createDirectories()
        val workspace = createFixtureWorkspace()

        var launcher = installation.resolve(launcherRelativePath(target))
        val diagnostic = assertAcceptedAotLaunch(launcher, caller, installation, target)
        writeAotDiagnostic(target, diagnostic)
        assertCompleteWorkload(launcher, caller, workspace)

        val relocatedParent = tempDir.resolve("aot-strict-relocated").createDirectories()
        val relocated = relocatedParent.resolve("indexino")
        Files.move(installation, relocated, StandardCopyOption.ATOMIC_MOVE)
        launcher = relocated.resolve(launcherRelativePath(target))
        assertAcceptedAotLaunch(launcher, caller, relocated, target)
        assertCompleteWorkload(launcher, caller, workspace)
        assertCallerRelativeInvocation(launcher, caller, workspace)
    }

    @Test
    fun `AOT launch performance and distribution sizes are reported`() {
        val target = requiredProperty("indexino.nativeTarget")
        val archive = requiredFile("indexino.nativeArchive")
        val aotInstallation = extractArchive(archive, target, "performance-aot")
        val offInstallation = extractArchive(archive, target, "performance-off")
        configureAotMode(offInstallation, "off")
        val aotLauncher = aotInstallation.resolve(launcherRelativePath(target))
        val offLauncher = offInstallation.resolve(launcherRelativePath(target))
        val caller = tempDir.resolve("performance-caller").createDirectories()
        val aotSamples = mutableListOf<Timing>()
        val offSamples = mutableListOf<Timing>()

        repeat(PERFORMANCE_SAMPLE_COUNT) { iteration ->
            if (iteration % 2 == 0) {
                aotSamples += measureLaunch(aotLauncher, caller, target, "aot-$iteration")
                offSamples += measureLaunch(offLauncher, caller, target, "off-$iteration")
            } else {
                offSamples += measureLaunch(offLauncher, caller, target, "off-$iteration")
                aotSamples += measureLaunch(aotLauncher, caller, target, "aot-$iteration")
            }
        }

        val runtime = aotInstallation.resolve("runtime")
        val report =
            """
            {
              "target": "$target",
              "sampleCountPerMode": $PERFORMANCE_SAMPLE_COUNT,
              "aot": {
                "medianWallSeconds": ${median(aotSamples.map(Timing::wallSeconds))},
                "medianUserSeconds": ${median(aotSamples.map(Timing::userSeconds))}
              },
              "aotModeOff": {
                "medianWallSeconds": ${median(offSamples.map(Timing::wallSeconds))},
                "medianUserSeconds": ${median(offSamples.map(Timing::userSeconds))}
              },
              "sizesBytes": {
                "zip": ${Files.size(archive)},
                "runtime": ${directorySize(runtime)},
                "jar": ${Files.size(aotInstallation.resolve("indexino-cli.jar"))},
                "aotCache": ${Files.size(runtime.resolve(aotCacheRuntimePath(target)))}
              }
            }
            """
                .trimIndent() + "\n"
        reportDirectory().resolve("aot-performance.json").writeText(report)
    }

    @Test
    fun `automatic mode loads a valid cache and completes the workload`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation =
            extractArchive(requiredFile("indexino.nativeArchive"), target, "aot-auto")
        configureAotVerification(installation, "auto")
        val caller = tempDir.resolve("aot-auto-caller").createDirectories()
        val workspace = createFixtureWorkspace()

        assertAcceptedAotLaunch(
            installation.resolve(launcherRelativePath(target)),
            caller,
            installation,
            target,
        )
        assertCompleteWorkload(
            installation.resolve(launcherRelativePath(target)),
            caller,
            workspace,
        )
    }

    @Test
    fun `strict mode rejects missing and corrupt caches`() {
        val target = requiredProperty("indexino.nativeTarget")
        listOf("missing", "corrupt").forEach { mutation ->
            val installation =
                extractArchive(
                    requiredFile("indexino.nativeArchive"),
                    target,
                    "aot-strict-$mutation",
                )
            configureAotVerification(installation, "on")
            mutateAotCache(installation, target, mutation)
            assertRejectedAotLaunch(
                installation.resolve(launcherRelativePath(target)),
                tempDir,
                expectSuccess = false,
            )
        }
    }

    @Test
    fun `automatic mode rejects missing and corrupt caches then completes the workload`() {
        val target = requiredProperty("indexino.nativeTarget")
        listOf("missing", "corrupt").forEach { mutation ->
            val installation =
                extractArchive(requiredFile("indexino.nativeArchive"), target, "aot-auto-$mutation")
            configureAotVerification(installation, "auto")
            mutateAotCache(installation, target, mutation)
            val launcher = installation.resolve(launcherRelativePath(target))
            assertRejectedAotLaunch(launcher, tempDir, expectSuccess = true)
            assertCompleteWorkload(
                launcher,
                tempDir.resolve("aot-auto-$mutation-caller").createDirectories(),
                createFixtureWorkspace("aot-auto-$mutation-fixture"),
            )
        }
    }

    @Test
    fun `strict mode rejects application JAR metadata drift`() {
        val target = requiredProperty("indexino.nativeTarget")
        val installation =
            extractArchive(requiredFile("indexino.nativeArchive"), target, "aot-jar-metadata")
        configureAotVerification(installation, "on")
        Files.setLastModifiedTime(
            installation.resolve("indexino-cli.jar"),
            FileTime.fromMillis(NORMALIZED_JAR_MTIME_MILLIS + 2_000L),
        )

        assertRejectedAotLaunch(
            installation.resolve(launcherRelativePath(target)),
            tempDir,
            expectSuccess = false,
        )
    }

    private fun configureAotVerification(installation: Path, mode: String) {
        val configuration = installation.resolve("app/indexino.json")
        val original = configuration.readText()
        val productionArgs = "\"vmArgs\":[\"--enable-native-access=ALL-UNNAMED\"]"
        val verificationArgs =
            "\"vmArgs\":[\"--enable-native-access=ALL-UNNAMED\",\"-XX:AOTMode=$mode\",\"-Xlog:aot=info\"]"
        assertContains(original, productionArgs)
        configuration.writeText(original.replace(productionArgs, verificationArgs))
    }

    private fun assertAcceptedAotLaunch(
        launcher: Path,
        caller: Path,
        installation: Path,
        target: String,
    ): ProcessResult {
        val result = runLauncher(launcher, caller, "--help")
        assertEquals(0, result.exitCode, result.diagnostic())
        val facts = AotLogParser.parse(result.stdout + result.stderr)
        assertTrue(facts.accepted, result.diagnostic())
        assertFalse(facts.rejected, result.diagnostic())
        assertEquals(true, facts.linkedClasses, result.diagnostic())
        val attemptedCache = Path.of(requireNotNull(facts.cachePath) { result.diagnostic() })
        assertEquals(
            installation.resolve("runtime").resolve(aotCacheRuntimePath(target)).toRealPath(),
            attemptedCache.toRealPath(),
        )
        return result
    }

    private fun writeAotDiagnostic(target: String, result: ProcessResult) {
        val diagnostic = buildString {
            appendLine("target=$target")
            appendLine("jbr=${requiredProperty("indexino.expectedJbrVersion")}")
            appendLine("exit=${result.exitCode}")
            appendLine("--- stdout ---")
            append(result.stdout)
            appendLine("--- stderr ---")
            append(result.stderr)
        }
        reportDirectory().resolve("aot-diagnostic.txt").writeText(diagnostic)
    }

    private fun configureAotMode(installation: Path, mode: String) {
        val configuration = installation.resolve("app/indexino.json")
        val original = configuration.readText()
        val productionArgs = "\"vmArgs\":[\"--enable-native-access=ALL-UNNAMED\"]"
        val modeArgs = "\"vmArgs\":[\"--enable-native-access=ALL-UNNAMED\",\"-XX:AOTMode=$mode\"]"
        assertContains(original, productionArgs)
        configuration.writeText(original.replace(productionArgs, modeArgs))
    }

    private fun measureLaunch(
        launcher: Path,
        caller: Path,
        target: String,
        sampleName: String,
    ): Timing =
        if (target == WINDOWS_X64) {
            measureWindowsLaunch(launcher, caller, sampleName)
        } else {
            val result = runCommand(caller, "/usr/bin/time", "-p", launcher.toString(), "--help")
            assertEquals(0, result.exitCode, result.diagnostic())
            Timing(
                wallSeconds = parseTime(result.stderr, "real"),
                userSeconds = parseTime(result.stderr, "user"),
            )
        }

    private fun measureWindowsLaunch(launcher: Path, caller: Path, sampleName: String): Timing {
        val stdout = tempDir.resolve("$sampleName.out")
        val stderr = tempDir.resolve("$sampleName.err")
        val script = tempDir.resolve("$sampleName.ps1")
        script.writeText(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}timer = [Diagnostics.Stopwatch]::StartNew()
            ${'$'}process = Start-Process -FilePath '${powershellQuote(launcher)}' `
                -ArgumentList '--help' `
                -WorkingDirectory '${powershellQuote(caller)}' `
                -NoNewWindow -Wait -PassThru `
                -RedirectStandardOutput '${powershellQuote(stdout)}' `
                -RedirectStandardError '${powershellQuote(stderr)}'
            ${'$'}timer.Stop()
            Write-Output ('INDEXINO_TIMING {0} {1}' -f `
                (${'$'}timer.Elapsed.TotalSeconds.ToString('R', [Globalization.CultureInfo]::InvariantCulture)), `
                (${'$'}process.UserProcessorTime.TotalSeconds.ToString('R', [Globalization.CultureInfo]::InvariantCulture)))
            exit ${'$'}process.ExitCode
            """
                .trimIndent()
        )
        val result =
            runCommand(
                caller,
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
            )
        assertEquals(0, result.exitCode, result.diagnostic())
        val timing = result.stdout.lineSequence().firstOrNull { it.startsWith("INDEXINO_TIMING ") }
        assertTrue(timing != null, result.diagnostic())
        val values = timing.removePrefix("INDEXINO_TIMING ").split(' ')
        return Timing(values[0].toDouble(), values[1].toDouble())
    }

    private fun parseTime(output: String, name: String): Double {
        val match = Regex("(?m)^$name ([0-9]+(?:\\.[0-9]+)?)\\s*$").find(output)
        return requireNotNull(match) { "Missing $name timing in:\n$output" }
            .groupValues[1]
            .toDouble()
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun directorySize(directory: Path): Long =
        Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
        }

    private fun reportDirectory(): Path =
        Path.of(requiredProperty("indexino.verificationReportDirectory")).createDirectories()

    private fun mutateAotCache(installation: Path, target: String, mutation: String) {
        val cache = installation.resolve("runtime").resolve(aotCacheRuntimePath(target))
        when (mutation) {
            "missing" -> Files.delete(cache)
            "corrupt" -> Files.write(cache, ByteArray(4_096) { 0x5a.toByte() })
            else -> error("Unsupported AOT cache mutation: $mutation")
        }
    }

    private fun assertRejectedAotLaunch(launcher: Path, caller: Path, expectSuccess: Boolean) {
        val result = runLauncher(launcher, caller, "--help")
        if (expectSuccess) {
            assertEquals(0, result.exitCode, result.diagnostic())
        } else {
            assertTrue(result.exitCode != 0, result.diagnostic())
        }
        val facts = AotLogParser.parse(result.stdout + result.stderr)
        assertFalse(facts.accepted, result.diagnostic())
        assertTrue(facts.rejected, result.diagnostic())
        assertTrue(facts.linkedClasses != true, result.diagnostic())
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
                "call \"${launcher}\" --help 1>\"$cmdOutput\" 2>\"$cmdError\"",
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
                "call \"${launcher}\" not-a-command " +
                    "1>\"$invalidCmdOutput\" 2>\"$invalidCmdError\"",
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
        assertContains(readRedirectedText(tempDir.resolve("invalid.err")), "no such subcommand")
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

    private fun createFixtureWorkspace(name: String = "fixture"): Path {
        val workspace = tempDir.resolve(name)
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
            [void][NativeConsole]::FreeConsole()
            if (-not [NativeConsole]::AllocConsole()) {
                throw "AllocConsole failed: ${'$'}([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
            }

            ${'$'}startup = New-Object NativeConsole+STARTUPINFO
            ${'$'}startup.cb = [Runtime.InteropServices.Marshal]::SizeOf(${'$'}startup)
            ${'$'}process = New-Object NativeConsole+PROCESS_INFORMATION
            ${'$'}commandLine = New-Object Text.StringBuilder '${powershellQuote(commandLine)}'
            ${'$'}created = [NativeConsole]::CreateProcess(
                '${powershellQuote(launcher)}',
                ${'$'}commandLine,
                [IntPtr]::Zero,
                [IntPtr]::Zero,
                ${'$'}false,
                0,
                [IntPtr]::Zero,
                '${powershellQuote(workspace)}',
                [ref]${'$'}startup,
                [ref]${'$'}process)
            if (-not ${'$'}created) { throw "CreateProcess failed: ${'$'}([Runtime.InteropServices.Marshal]::GetLastWin32Error())" }
            if (-not [NativeConsole]::SetConsoleCtrlHandler([IntPtr]::Zero, ${'$'}true)) {
                throw 'Could not ignore CTRL_C_EVENT in the verifier process'
            }

            try {
                Start-Sleep -Milliseconds 1500
                [UInt32]${'$'}exitCode = 0
                if (-not [NativeConsole]::GetExitCodeProcess(${'$'}process.hProcess, [ref]${'$'}exitCode)) {
                    throw 'GetExitCodeProcess failed before CTRL_C_EVENT'
                }
                if (${'$'}exitCode -ne 259) {
                    throw "Launcher exited before CTRL_C_EVENT with code ${'$'}exitCode"
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
                [void][NativeConsole]::FreeConsole()
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

    private fun assertRuntimeLegalTreeMatches(
        zip: ZipFile,
        entries: Map<String, java.util.zip.ZipEntry>,
        runtimeLegalRoot: Path,
    ) {
        val expectedFiles =
            Files.walk(runtimeLegalRoot).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .map { runtimeLegalRoot.relativize(it).toString().replace('\\', '/') }
                    .toList()
                    .toSet()
            }
        val packagedPrefix = "indexino/runtime/legal/"
        val packagedFiles =
            entries.values
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith(packagedPrefix) }
                .associateBy { it.name.removePrefix(packagedPrefix) }
        assertEquals(expectedFiles, packagedFiles.keys, "Packaged runtime legal inventory differs")
        expectedFiles.forEach { relativePath ->
            val expected = Files.readAllBytes(runtimeLegalRoot.resolve(relativePath))
            val actual =
                zip.getInputStream(packagedFiles.getValue(relativePath)).use { it.readBytes() }
            assertTrue(
                expected.contentEquals(actual),
                "Packaged runtime legal file differs: $relativePath",
            )
        }
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

    private data class Timing(val wallSeconds: Double, val userSeconds: Double)

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
                public static extern bool AllocConsole();

                [DllImport("kernel32.dll", SetLastError = true)]
                public static extern bool FreeConsole();

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
        const val PERFORMANCE_SAMPLE_COUNT = 5
        const val INTERRUPT_FIXTURE_FILE_COUNT = 2_000
        val REQUIRED_MODULES = setOf("jdk.compiler", "jdk.unsupported", "jdk.crypto.ec")
        val REQUIRED_JBR_LEGAL_FILES =
            setOf("LICENSE", "ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION", "README.JAVASE")
    }
}
