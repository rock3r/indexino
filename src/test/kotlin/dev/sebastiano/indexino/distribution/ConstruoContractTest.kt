package dev.sebastiano.indexino.distribution

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Properties
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("construo-contract")
class ConstruoContractTest {
    @TempDir lateinit var projectDirectory: Path

    @Test
    fun `normalized application jar has deterministic archive safe metadata`() {
        val normalizedJar = Path.of(requiredProperty("indexino.normalizedCliJar"))
        assertTrue(Files.isRegularFile(normalizedJar), "Missing normalized application JAR")
        assertEquals("indexino-cli.jar", normalizedJar.fileName.toString())
        assertEquals(
            NORMALIZED_JAR_MTIME_MILLIS,
            Files.getLastModifiedTime(normalizedJar).toMillis(),
        )
        assertEquals(0, Files.getLastModifiedTime(normalizedJar).toMillis() / 1_000L % 2L)
        JarFile(normalizedJar.toFile()).use { jar ->
            assertEquals(
                "dev.sebastiano.indexino.cli.MainCommandKt",
                jar.manifest.mainAttributes.getValue("Main-Class"),
            )
        }
    }

    @Test
    fun `tier one downloads are immutable and complete`() {
        val pinsFile = Path.of(requiredProperty("indexino.nativeDistributionPins"))
        assertTrue(Files.isRegularFile(pinsFile), "Missing checked-in native distribution pins")
        val pins = Properties().apply { Files.newInputStream(pinsFile).use(::load) }
        assertEquals(
            requiredProperty("indexino.construoVersion"),
            pins.getProperty("construo.version"),
        )
        assertEquals("v1.6.0", pins.getProperty("roast.version"))
        assertEquals("25.0.3b508.16", pins.getProperty("jbr.version"))

        val targets = listOf("linuxX64", "macArm64", "windowsX64")
        targets.forEach { target ->
            val jdkUrl = requireNotNull(pins.getProperty("$target.jdkUrl"))
            val jdkSha256 = requireNotNull(pins.getProperty("$target.jdkSha256"))
            val roastSha256 = requireNotNull(pins.getProperty("$target.roastSha256"))
            assertTrue(jdkUrl.startsWith("https://"), "$target JBR URL is not HTTPS")
            assertTrue(SHA256.matches(jdkSha256), "$target JBR SHA-256 is invalid")
            assertTrue(SHA256.matches(roastSha256), "$target Roast SHA-256 is invalid")
        }
        assertTrue(pins.getProperty("windowsX64.roastAsset").contains("win-console"))
    }

    @Test
    fun `released plugin exposes the complete distribution contract`() {
        val pluginVersion = requiredProperty("indexino.construoVersion")
        assertTrue(
            pluginVersion == "2.2.1",
            "Construo must be pinned to the validated 2.2.1 release",
        )
        writeFixture(pluginVersion, "http://127.0.0.1/unused-jdk.tar.gz")

        val input = projectDirectory.resolve("package-input").createDirectories()
        input.resolve("runtime/lib").createDirectories()
        input.resolve("indexino").writeText("launcher")
        input.resolve("runtime/lib/jspawnhelper").writeText("helper")
        input.resolve("indexino-cli.jar").writeText("application")
        val oddTimestamp = FileTime.fromMillis(1_700_000_001_000L)
        Files.walk(input).use { paths ->
            paths.forEach { Files.setLastModifiedTime(it, oddTimestamp) }
        }

        val result = runGradle("assertConstruoContract")

        assertTrue(result.task(":syntheticPackage")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":syntheticRoast")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":assertConstruoContract")?.outcome == TaskOutcome.SUCCESS)
        val entries =
            readCentralDirectory(projectDirectory.resolve("build/contract/synthetic.zip"))
                .associateBy(ZipEntryMetadata::name)
        assertEquals(493, entries.getValue("indexino").unixMode)
        assertEquals(493, entries.getValue("runtime/").unixMode)
        assertEquals(493, entries.getValue("runtime/lib/jspawnhelper").unixMode)
        assertEquals(420, entries.getValue("indexino-cli.jar").unixMode)
        assertEquals(420, entries.getValue("licenses/generated.txt").unixMode)
        assertEquals(0, entries.getValue("indexino-cli.jar").dosSecond % 2)
    }

    @Test
    fun `configured checksum fails closed before extraction`() {
        val pluginVersion = requiredProperty("indexino.construoVersion")
        val archive = "not a JDK archive".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/jdk.tar.gz") { exchange ->
                exchange.sendResponseHeaders(200, archive.size.toLong())
                exchange.responseBody.use { it.write(archive) }
            }
            server.start()
            writeFixture(pluginVersion, "http://127.0.0.1:${server.address.port}/jdk.tar.gz")

            val result = runGradleAndFail("verifyJdkLinuxX64")

            assertTrue(result.output.contains("SHA-256 mismatch"), result.output)
            assertFalse(
                projectDirectory.resolve("build/construo/jdk/linuxX64.tar.gz").toFile().exists(),
                "A checksum mismatch must remove the untrusted archive",
            )
        } finally {
            server.stop(0)
        }
    }

    @Suppress(
        "LongMethod"
    ) // Keeping the TestKit build script contiguous makes its Gradle model auditable.
    private fun writeFixture(pluginVersion: String, jdkUrl: String) {
        projectDirectory
            .resolve("settings.gradle.kts")
            .writeText(
                """
                pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
                rootProject.name = "construo-contract"
                """
                    .trimIndent()
            )
        projectDirectory
            .resolve("build.gradle.kts")
            .writeText(
                """
            import io.github.fourlastor.construo.ConstruoPluginExtension
            import io.github.fourlastor.construo.Target
            import io.github.fourlastor.construo.task.PackageTask
            import io.github.fourlastor.construo.task.jvm.CreateRuntimeImageTask
            import io.github.fourlastor.construo.task.jvm.RoastTask
            import org.gradle.api.DefaultTask
            import org.gradle.api.file.RegularFileProperty
            import org.gradle.api.tasks.OutputFile
            import org.gradle.api.tasks.TaskAction

            plugins {
                application
                id("io.github.fourlastor.construo") version "$pluginVersion"
            }

            abstract class GenerateOverlay : DefaultTask() {
                @get:OutputFile abstract val outputFile: RegularFileProperty

                @TaskAction
                fun generate() {
                    outputFile.get().asFile.apply {
                        parentFile.mkdirs()
                        writeText("generated")
                    }
                }
            }

            application { mainClass.set("sample.Main") }
            tasks.jar { manifest { attributes["Main-Class"] = "sample.Main" } }

            val globalPrep = tasks.register("globalPrep")
            val macPrep = tasks.register("macPrep")
            val generateOverlay = tasks.register<GenerateOverlay>("generateOverlay") {
                outputFile.set(layout.buildDirectory.file("generated/overlay.txt"))
            }

            construo {
                name.set("indexino")
                humanName.set("Indexino")
                mainClass.set("sample.Main")
                jarTask.set("jar")
                zipFolder.set("indexino")
                prePackageTasks.add(globalPrep.name)
                packageFiles.put("licenses/generated.txt", generateOverlay.flatMap { it.outputFile })
                roast {
                    version.set("v1.6.0")
                    useZgc.set(false)
                }
                targets {
                    create<Target.Linux>("linuxX64") {
                        architecture.set(Target.Architecture.X86_64)
                        jdkUrl.set("$jdkUrl")
                        jdkSha256.set("${"0".repeat(64)}")
                        roastSha256.set("${"1".repeat(64)}")
                        archiveFile.set(layout.buildDirectory.file("distributions/indexino-linux-x64.zip"))
                        packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
                    }
                    create<Target.MacOs>("macArm64") {
                        architecture.set(Target.Architecture.AARCH64)
                        jdkUrl.set("$jdkUrl")
                        jdkSha256.set("${"2".repeat(64)}")
                        roastSha256.set("${"3".repeat(64)}")
                        archiveFile.set(layout.buildDirectory.file("distributions/indexino-macos-arm64.zip"))
                        packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
                        appBundle.set(false)
                        prePackageTasks.add(macPrep.name)
                    }
                    create<Target.Windows>("windowsX64") {
                        architecture.set(Target.Architecture.X86_64)
                        jdkUrl.set("$jdkUrl")
                        jdkSha256.set("${"4".repeat(64)}")
                        roastSha256.set("${"5".repeat(64)}")
                        archiveFile.set(layout.buildDirectory.file("distributions/indexino-windows-x64.zip"))
                        packagingToolJdk.set(Target.PackagingToolJdk.TARGET_JDK)
                        useConsole.set(true)
                        useGpuHint.set(false)
                    }
                }
            }

            val syntheticPackage = tasks.register<PackageTask>("syntheticPackage") {
                from.set(layout.projectDirectory.dir("package-input"))
                executable.set(layout.projectDirectory.file("package-input/indexino"))
                archiveFile.set(layout.buildDirectory.file("contract/synthetic.zip"))
                packageFiles.put("licenses/generated.txt", generateOverlay.flatMap { it.outputFile })
            }

            val syntheticRoast = tasks.register<RoastTask>("syntheticRoast") {
                jdkRoot.set(layout.projectDirectory.dir("package-input/runtime"))
                appName.set("indexino")
                mainClassName.set("sample.Main")
                roastExe.set(layout.projectDirectory.file("package-input/indexino"))
                roastExeName.set("indexino")
                jarFile.set(layout.projectDirectory.file("package-input/indexino-cli.jar"))
                runOnFirstThread.set(true)
                vmArgs.set(emptyList())
                useZgc.set(false)
                useMainAsContextClassLoader.set(false)
                output.set(layout.buildDirectory.dir("contract/roast"))
            }

            tasks.register("assertConstruoContract") {
                dependsOn(syntheticPackage, syntheticRoast)
                doLast {
                    val extension = project.extensions.getByType(ConstruoPluginExtension::class.java)
                    check(extension.roast.version.get() == "v1.6.0")
                    val mac = extension.targets.named("macArm64").get() as Target.MacOs
                    val windows = extension.targets.named("windowsX64").get() as Target.Windows
                    check(!mac.appBundle.get())
                    check(windows.useConsole.get() && !windows.useGpuHint.get())

                    val packageMac = tasks.named("packageMacArm64").get() as PackageTask
                    val dependencyNames =
                        packageMac.taskDependencies.getDependencies(packageMac).map { it.name }.toSet()
                    check(globalPrep.name in dependencyNames)
                    check(macPrep.name in dependencyNames)
                    check(generateOverlay.name in dependencyNames)
                    check(packageMac.archiveFile.get().asFile.name == "indexino-macos-arm64.zip")
                    check(packageMac.from.get().asFile.invariantSeparatorsPath.endsWith("/construo/macArm64/roast"))
                    check(packageMac.into.get() == "indexino")

                    val normal =
                        tasks.named("createRuntimeImageMacArm64").get() as CreateRuntimeImageTask
                    val withNatives =
                        tasks.named("createRuntimeImageWithNativesMacArm64").get() as CreateRuntimeImageTask
                    check(normal.stripNativeCommands.get())
                    check(!withNatives.stripNativeCommands.get())
                    check(normal.output.get() != withNatives.output.get())
                    check(
                        normal.jdkRoot.get().asFile.invariantSeparatorsPath.endsWith(
                            "/construo/jdk/macArm64"
                        )
                    )
                    check(
                        PackageTask::class.java.getMethod("getArchiveFile")
                            .getAnnotation(OutputFile::class.java) != null
                    )
                    val stagedJar =
                        syntheticRoast.get().output.file("indexino-cli.jar").get().asFile
                    val sourceJar =
                        layout.projectDirectory.file("package-input/indexino-cli.jar").asFile
                    check(stagedJar.lastModified() == sourceJar.lastModified()) {
                        "RoastTask changed the application JAR mtime from " +
                            "${'$'}{sourceJar.lastModified()} to ${'$'}{stagedJar.lastModified()}"
                    }

                }
            }
            """
                    .trimIndent()
            )
    }

    private fun runGradle(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withArguments("--stacktrace", *arguments)
            .build()

    private fun runGradleAndFail(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withArguments("--stacktrace", *arguments)
            .buildAndFail()

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "Missing $name" }

    private fun readCentralDirectory(archive: Path): List<ZipEntryMetadata> {
        val bytes = Files.readAllBytes(archive)
        val entries = mutableListOf<ZipEntryMetadata>()
        var offset = 0
        while (offset <= bytes.size - CENTRAL_DIRECTORY_HEADER_SIZE) {
            if (littleEndianInt(bytes, offset) != CENTRAL_DIRECTORY_SIGNATURE) {
                offset++
                continue
            }
            val dosTime = littleEndianShort(bytes, offset + 12)
            val nameLength = littleEndianShort(bytes, offset + 28)
            val extraLength = littleEndianShort(bytes, offset + 30)
            val commentLength = littleEndianShort(bytes, offset + 32)
            val externalAttributes = littleEndianInt(bytes, offset + 38)
            val name =
                String(bytes, offset + CENTRAL_DIRECTORY_HEADER_SIZE, nameLength, Charsets.UTF_8)
            entries +=
                ZipEntryMetadata(
                    name = name,
                    unixMode = externalAttributes ushr 16 and 511,
                    dosSecond = (dosTime and 31) * 2,
                )
            offset += CENTRAL_DIRECTORY_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        return entries
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and
            0xff or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xff or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private data class ZipEntryMetadata(val name: String, val unixMode: Int, val dosSecond: Int)

    private companion object {
        const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
        const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
        const val NORMALIZED_JAR_MTIME_MILLIS = 1_700_000_000_000L
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
