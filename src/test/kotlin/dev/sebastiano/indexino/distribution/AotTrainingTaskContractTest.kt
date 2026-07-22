package dev.sebastiano.indexino.distribution

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("aot-training-contract")
class AotTrainingTaskContractTest {
    @TempDir lateinit var projectDirectory: Path

    @Test
    fun `committed training sources are checked out with LF line endings`() {
        val repository = Path.of("").toAbsolutePath()
        val fixture = "gradle/aot-training/fixture/app/src/main/kotlin/sample/Panel.kt"
        val process =
            ProcessBuilder("git", "check-attr", "eol", "--", fixture)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()

        assertEquals(0, process.waitFor(), output)
        assertEquals("$fixture: eol: lf", output)
    }

    @Test
    fun `training rejects a full sdk instead of mutating it`() {
        writeFixture(fullSdkRuntime = true)

        val result = runGradleAndFail("trainAot")

        assertContains(result.output, "Final runtime input must be stripped")
        assertFalse(projectDirectory.resolve("build/aot/classes.jsa").exists())
        assertTrue(projectDirectory.resolve("runtime/bin/javac").exists())
    }

    @Test
    fun `training stages immutable inputs with hermetic roast arguments`() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        writeFixture(fullSdkRuntime = false)
        val runtimeMarker = projectDirectory.resolve("runtime/lib/runtime.marker")
        val inputJar = projectDirectory.resolve("input/indexino-cli.jar")
        val runtimeBytes = Files.readAllBytes(runtimeMarker)
        val jarBytes = Files.readAllBytes(inputJar)
        val jarTimestamp = Files.getLastModifiedTime(inputJar)

        val first = runGradle("trainAot", hostileJvmEnvironment = true)

        assertEquals(TaskOutcome.SUCCESS, first.task(":trainAot")?.outcome)
        val cache = projectDirectory.resolve("build/aot/classes.jsa")
        assertEquals("aot-cache", cache.readText())
        assertEquals(runtimeBytes.toList(), Files.readAllBytes(runtimeMarker).toList())
        assertEquals(jarBytes.toList(), Files.readAllBytes(inputJar).toList())
        assertEquals(jarTimestamp, Files.getLastModifiedTime(inputJar))
        assertFalse(projectDirectory.resolve("runtime/bin/java").exists())

        val staging = projectDirectory.resolve("build/tmp/trainAot/staging")
        assertEquals(
            staging.toRealPath().toString(),
            staging.resolve("probe.cwd").readText().trim(),
        )
        assertEquals(
            jarBytes.toList(),
            Files.readAllBytes(staging.resolve("indexino-cli.jar")).toList(),
        )
        assertEquals(jarTimestamp, Files.getLastModifiedTime(staging.resolve("indexino-cli.jar")))
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
            Files.getPosixFilePermissions(staging.resolve("indexino-cli.jar")),
        )
        assertTrue(staging.resolve("runtime/bin/java").exists())
        assertFalse(staging.resolve("runtime/bin/javac").exists())
        assertFalse(staging.resolve("runtime/bin/jlink").exists())
        assertEquals(
            Files.getPosixFilePermissions(projectDirectory.resolve("runtime")),
            Files.getPosixFilePermissions(staging.resolve("runtime")),
        )

        val arguments = staging.resolve("probe.args").readLines()
        assertEquals("-Xms64m", arguments[0])
        assertEquals("-Xmx512m", arguments[1])
        assertEquals("--enable-native-access=ALL-UNNAMED", arguments[2])
        assertEquals("-Dindexino.roastLauncher=true", arguments[3])
        assertEquals("-Duser.home=${staging.toRealPath().resolve("home")}", arguments[4])
        assertEquals("-Djava.io.tmpdir=${staging.toRealPath().resolve("tmp")}", arguments[5])
        assertTrue(arguments[6].startsWith("-XX:AOTCacheOutput="))
        assertFalse(arguments[6].endsWith("/build/aot/classes.jsa"))
        assertEquals(
            listOf(
                "-cp",
                "indexino-cli.jar",
                "dev.sebastiano.indexino.cli.MainCommandKt",
                "index",
                "--project",
                "training-workspace",
                "--build-system",
                "gradle",
                "--gradle-module",
                ":app",
                "--applications",
                "selection-context",
            ),
            arguments.drop(7),
        )
        val environment = staging.resolve("probe.env").readLines()
        assertTrue(environment.contains("PATH=${System.getenv("PATH")}"))
        listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS")
            .forEach { name -> assertTrue(environment.none { it.startsWith("$name=") }) }

        val second = runGradle("trainAot", hostileJvmEnvironment = true)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":trainAot")?.outcome)
    }

    private fun writeFixture(fullSdkRuntime: Boolean) {
        writeBuildFixture()
        writeRuntimeFixture(fullSdkRuntime)
        writeTargetJdkFixture()
        writeApplicationJarFixture()
        writeTrainingFixture()
    }

    private fun writeBuildFixture() {
        val source = Path.of(requiredProperty("indexino.aotTrainingTaskSource"))
        val fixtureSource =
            projectDirectory.resolve(
                "buildSrc/src/main/java/dev/sebastiano/indexino/buildlogic/AotTrainingTask.java"
            )
        fixtureSource.parent.createDirectories()
        Files.copy(source, fixtureSource, StandardCopyOption.REPLACE_EXISTING)
        projectDirectory
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"aot-task\"\n")
        projectDirectory
            .resolve("build.gradle.kts")
            .writeText(
                """
                import dev.sebastiano.indexino.buildlogic.AotTrainingTask

                tasks.register<AotTrainingTask>("trainAot") {
                    runtimeImage.set(layout.projectDirectory.dir("runtime"))
                    targetJdkRoot.set(layout.projectDirectory.dir("target-jdk"))
                    applicationJar.set(layout.projectDirectory.file("input/indexino-cli.jar"))
                    trainingFixture.set(layout.projectDirectory.dir("fixture"))
                    aotCache.set(layout.buildDirectory.file("aot/classes.jsa"))
                    targetOs.set("macos")
                    targetArchitecture.set("aarch64")
                    jbrDigest.set("fixture-jbr-digest")
                    normalizedJarTimestampMillis.set(1700000000000L)
                    modules.set(listOf("java.base", "jdk.compiler", "jdk.unsupported"))
                    mainClassName.set("dev.sebastiano.indexino.cli.MainCommandKt")
                    classPath.set("indexino-cli.jar")
                    roastWorkingDirectory.set(".")
                    fixtureVersion.set("1")
                    vmArgs.set(
                        listOf(
                            "--enable-native-access=ALL-UNNAMED",
                            "-Dindexino.roastLauncher=true",
                        )
                    )
                    trainingArguments.set(
                        listOf(
                            "index", "--project", "training-workspace",
                            "--build-system", "gradle", "--gradle-module", ":app",
                            "--applications", "selection-context",
                        )
                    )
                    minimumHeap.set("64m")
                    maximumHeap.set("512m")
                    javaExecutableName.set("java")
                    gitExecutable.set("git")
                    environmentVariables.put("PATH", providers.environmentVariable("PATH"))
                    environmentVariables.put(
                        "SystemRoot", providers.environmentVariable("SystemRoot").orElse("")
                    )
                    environmentVariables.put(
                        "WINDIR", providers.environmentVariable("WINDIR").orElse("")
                    )
                }
                """
                    .trimIndent()
            )
    }

    private fun writeRuntimeFixture(fullSdkRuntime: Boolean) {
        val runtime = projectDirectory.resolve("runtime")
        runtime.resolve("lib/server").createDirectories()
        runtime.resolve("lib/runtime.marker").writeText("final stripped runtime")
        runtime.resolve("release").writeText("JAVA_VERSION=fixture\n")
        runtime.resolve("bin").createDirectories()
        runtime.resolve("bin/runtime.dll").writeText("required runtime native library")
        Files.setPosixFilePermissions(
            runtime,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        if (fullSdkRuntime) {
            runtime.resolve("bin/javac").writeText("must remain immutable")
        }
    }

    private fun writeTargetJdkFixture() {
        val java = projectDirectory.resolve("target-jdk/bin/java")
        java.parent.createDirectories()
        java.writeText(
            """
            #!/bin/sh
            set -eu
            cache=""
            for argument in "${'$'}@"; do
              case "${'$'}argument" in
                -XX:AOTCacheOutput=*) cache="${'$'}{argument#*=}" ;;
              esac
            done
            test -n "${'$'}cache"
            printf 'aot-cache' > "${'$'}cache"
            printf '%s\n' "${'$'}PWD" > probe.cwd
            printf '%s\n' "${'$'}@" > probe.args
            env | sort > probe.env
            """
                .trimIndent()
        )
        Files.setPosixFilePermissions(
            java,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        projectDirectory.resolve("target-jdk/bin/javac").writeText("full sdk tool")
    }

    private fun writeApplicationJarFixture() {
        val inputJar = projectDirectory.resolve("input/indexino-cli.jar")
        inputJar.parent.createDirectories()
        inputJar.writeText("normalized application bytes")
        Files.setLastModifiedTime(inputJar, FileTime.fromMillis(1_700_000_000_000L))
        Files.setPosixFilePermissions(
            inputJar,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )
    }

    private fun writeTrainingFixture() {
        val fixture = projectDirectory.resolve("fixture")
        fixture.resolve("app/src/main/kotlin/sample").createDirectories()
        fixture.resolve("settings.gradle.kts").writeText("include(\":app\")\n")
        fixture
            .resolve("app/src/main/kotlin/sample/Panel.kt")
            .writeText("package sample\nclass Panel\n")
    }

    private fun runGradle(task: String, hostileJvmEnvironment: Boolean) =
        runner(hostileJvmEnvironment).withArguments(task, "--stacktrace").build()

    private fun runGradleAndFail(task: String) =
        runner(hostileJvmEnvironment = false).withArguments(task, "--stacktrace").buildAndFail()

    private fun runner(hostileJvmEnvironment: Boolean): GradleRunner {
        val runner = GradleRunner.create().withProjectDir(projectDirectory.toFile())
        if (!hostileJvmEnvironment) return runner
        return runner.withEnvironment(
            System.getenv() +
                mapOf(
                    "JAVA_TOOL_OPTIONS" to "-Dindexino.hostile.java.tool.options=true",
                    "_JAVA_OPTIONS" to "-Dindexino.hostile.underscore.java.options=true",
                    "JDK_JAVA_OPTIONS" to "-Dindexino.hostile.jdk.java.options=true",
                    "JDK_AOT_VM_OPTIONS" to "-Dindexino.hostile.jdk.aot.vm.options=true",
                )
        )
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing system property $name" }
}
