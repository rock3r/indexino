package dev.sebastiano.indexino.distribution

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

@Tag("distribution")
class ShrunkCliDistributionTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `shrunk jar preserves the complete cli workload`() {
        val shrunkJar = requiredJar("indexino.shrunkJar")
        val unshrunkJar = requiredJar("indexino.unshrunkJar")
        val shrunkBytes = Files.size(shrunkJar)
        val unshrunkBytes = Files.size(unshrunkJar)
        println("CLI JAR sizes: unshrunk=$unshrunkBytes bytes, shrunk=$shrunkBytes bytes")
        assertTrue(shrunkBytes <= MAX_SHRUNK_JAR_BYTES, "Shrunk JAR is $shrunkBytes bytes")

        val help = runJar(shrunkJar, tempDir, "--help")
        assertEquals(0, help.exitCode, help.diagnostic())
        assertContains(help.stdout, "Usage: indexino")

        val workspace = createFixtureWorkspace()
        val index =
            runJar(
                shrunkJar,
                tempDir,
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

        val status = runJar(shrunkJar, tempDir, "status", "--project", workspace.toString())
        assertEquals(0, status.exitCode, status.diagnostic())
        assertContains(status.stdout, "selection-context")

        val freshIndex =
            runJar(
                shrunkJar,
                tempDir,
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

        val symbol = lookup(shrunkJar, workspace, "find-symbol", "--name", "Renderer")
        assertContains(symbol, "\"fqn\":\"sample.Renderer\"")
        assertEquals(
            symbol,
            lookup(shrunkJar, workspace, "find-symbol", "--name", "Renderer"),
            "find-symbol JSONL must be deterministic",
        )

        val references =
            lookup(shrunkJar, workspace, "find-references", "--symbol", "sample.Renderer#render")
        assertContains(references, "\"symbolFqn\":\"sample.Renderer#render\"")

        val javaSymbol = lookup(shrunkJar, workspace, "find-symbol", "--name", "JavaPanel")
        assertContains(javaSymbol, "\"fqn\":\"sample.JavaPanel\"")
        val javaReferences =
            lookup(shrunkJar, workspace, "find-references", "--symbol", "sample.JavaPanel#render")
        assertContains(javaReferences, "app/src/main/java/sample/JavaPanel.java")

        val resource =
            lookup(shrunkJar, workspace, "resolve-resource", "--type", "string", "--name", "title")
        assertContains(resource, "\"fqn\":\"res:string:title\"")
        val resourceReferences =
            lookup(shrunkJar, workspace, "find-references", "--symbol", "@string/title")
        assertContains(resourceReferences, "\"symbolFqn\":\"res:string:title\"")
        assertContains(resourceReferences, "app/src/main/res/layout/main.xml")

        val query =
            runJar(
                shrunkJar,
                tempDir,
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

        val commit = runCommand(workspace, "git", "rev-parse", "HEAD").stdout.trim()
        val indexRoot = workspace.resolve(".indexino/index/$commit")
        assertTrue(Files.isDirectory(indexRoot.resolve("base.xodus")))
        assertTrue(Files.isRegularFile(indexRoot.resolve("manifest.json")))
    }

    @Test
    fun `shrunk jar retains embedded idea home and complete service providers`() {
        val shrunkJar = requiredJar("indexino.shrunkJar")
        JarFile(shrunkJar.toFile()).use { jar ->
            assertTrue(jar.getEntry("idea-home/config/.keep") != null)
            assertTrue(jar.getEntry("idea-home/system/.keep") != null)
            assertTrue(jar.getEntry("idea-home/plugins/.keep") != null)
            val actualServices =
                jar.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.startsWith(SERVICE_PREFIX) && it.length > SERVICE_PREFIX.length }
                    .map { it.removePrefix(SERVICE_PREFIX) }
                    .toSet()
            assertEquals(EXPECTED_SERVICES.keys, actualServices, "Unexpected service descriptors")
            EXPECTED_SERVICES.forEach { (service, expectedProviders) ->
                val entry = jar.getJarEntry("$SERVICE_PREFIX$service")
                assertTrue(entry != null, "Missing service descriptor for $service")
                val providers =
                    jar.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines
                            .map(String::trim)
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .map { it.removePrefix("class = ") }
                            .toSet()
                    }
                assertEquals(expectedProviders, providers, "Unexpected providers for $service")
            }
        }
        assertServiceLoading(shrunkJar)
    }

    private fun assertServiceLoading(shrunkJar: Path) {
        val probeSource = tempDir.resolve("ServiceProbe.java")
        probeSource.writeText(
            """
            import java.util.ServiceLoader;
            import java.util.Set;
            import java.util.TreeSet;

            class ServiceProbe {
                public static void main(String[] args) throws Exception {
                    for (String service : args) {
                        Class<?> serviceClass = Class.forName(service);
                        Set<String> providers = new TreeSet<>();
                        for (Object provider : ServiceLoader.load(serviceClass)) {
                            providers.add(provider.getClass().getName());
                        }
                        System.out.println(service + "=" + String.join(",", providers));
                    }
                }
            }
            """
                .trimIndent()
        )
        val result =
            runCommand(
                tempDir,
                javaExecutable(),
                "--class-path",
                shrunkJar.toString(),
                probeSource.toString(),
                *EXPECTED_SERVICES.keys.toTypedArray(),
            )
        assertEquals(0, result.exitCode, result.diagnostic())
        val actual = result.stdout.lineSequence().filter(String::isNotBlank).toSet()
        val expected =
            EXPECTED_SERVICES.map { (service, providers) ->
                    service + "=" + providers.sorted().joinToString(",")
                }
                .toSet()
        assertEquals(expected, actual, "ServiceLoader providers differ")
    }

    private fun lookup(jar: Path, workspace: Path, command: String, vararg args: String): String {
        val result = runJar(jar, tempDir, command, "--project", workspace.toString(), *args)
        assertEquals(0, result.exitCode, result.diagnostic())
        return result.stdout
    }

    private fun createFixtureWorkspace(): Path {
        val workspace = tempDir.resolve("fixture")
        workspace.resolve("app/src/main/kotlin/sample").createDirectories()
        workspace.resolve("app/src/main/java/sample").createDirectories()
        workspace.resolve("app/src/main/res/values").createDirectories()
        workspace.resolve("app/src/main/res/layout").createDirectories()
        workspace
            .resolve("settings.gradle.kts")
            .writeText("rootProject.name = \"shrunk-smoke\"\ninclude(\":app\")\n")
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
        runCommand(workspace, "git", "init")
        runCommand(workspace, "git", "config", "user.email", "distribution-test@example.invalid")
        runCommand(workspace, "git", "config", "user.name", "Distribution Test")
        runCommand(workspace, "git", "add", ".")
        runCommand(workspace, "git", "commit", "-m", "fixture")
        return workspace
    }

    private fun runJar(jar: Path, workingDirectory: Path, vararg args: String): ProcessResult =
        runCommand(
            workingDirectory,
            javaExecutable(),
            "-Duser.home=${workingDirectory.resolve("home")}",
            "-jar",
            jar.toString(),
            *args,
        )

    private fun runCommand(workingDirectory: Path, vararg command: String): ProcessResult {
        val process =
            ProcessBuilder(*command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false)
                .start()
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
                assertTrue(
                    terminated,
                    "Timed out and could not terminate: ${command.joinToString(" ")}",
                )
                error("Timed out: ${command.joinToString(" ")}")
            }
            return ProcessResult(process.exitValue(), stdout.get(), stderr.get())
        }
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun requiredJar(property: String): Path {
        val path = Path.of(requireNotNull(System.getProperty(property)) { "Missing $property" })
        assertTrue(Files.isRegularFile(path), "Missing JAR at $path")
        return path
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun diagnostic(): String = "exit=$exitCode\nstdout:\n$stdout\nstderr:\n$stderr"
    }

    private companion object {
        const val MAX_SHRUNK_JAR_BYTES = 25L * 1024 * 1024
        const val PROCESS_TIMEOUT_MINUTES = 3L
        const val PROCESS_KILL_TIMEOUT_SECONDS = 10L
        const val SERVICE_PREFIX = "META-INF/services/"
        val EXPECTED_SERVICES =
            mapOf(
                "kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader" to
                    setOf(
                        "kotlin.reflect.jvm.internal.impl.serialization.deserialization.builtins.BuiltInsLoaderImpl"
                    ),
                "kotlin.reflect.jvm.internal.impl.resolve.ExternalOverridabilityCondition" to
                    setOf(
                        "kotlin.reflect.jvm.internal.impl.load.java.FieldOverridabilityCondition",
                        "kotlin.reflect.jvm.internal.impl.load.java.ErasedOverridabilityCondition",
                        "kotlin.reflect.jvm.internal.impl.load.java.JavaIncompatibilityRulesOverridabilityCondition",
                    ),
                "org.jetbrains.kotlin.builtins.BuiltInsLoader" to
                    setOf(
                        "org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInsLoaderImpl"
                    ),
                "org.jetbrains.kotlin.resolve.ExternalOverridabilityCondition" to
                    setOf(
                        "org.jetbrains.kotlin.load.java.FieldOverridabilityCondition",
                        "org.jetbrains.kotlin.load.java.ErasedOverridabilityCondition",
                        "org.jetbrains.kotlin.load.java.JavaIncompatibilityRulesOverridabilityCondition",
                    ),
                "org.jetbrains.kotlin.resolve.jvm.KotlinToJvmSignatureMapper" to
                    setOf("org.jetbrains.kotlin.codegen.signature.KotlinToJvmSignatureMapperImpl"),
                "org.jetbrains.kotlin.util.ModuleVisibilityHelper" to
                    setOf("org.jetbrains.kotlin.cli.common.ModuleVisibilityHelperImpl"),
                "jetbrains.exodus.io.DataReaderWriterProvider" to
                    setOf(
                        "jetbrains.exodus.io.FileDataReaderWriterProvider",
                        "jetbrains.exodus.io.WatchingFileDataReaderWriterProvider",
                        "jetbrains.exodus.io.inMemory.MemoryDataReaderWriterProvider",
                    ),
                "com.github.ajalt.mordant.terminal.TerminalInterfaceProvider" to
                    setOf(
                        "com.github.ajalt.mordant.terminal.terminalinterface.jna.TerminalInterfaceProviderJna",
                        "com.github.ajalt.mordant.terminal.terminalinterface.ffm.TerminalInterfaceProviderFfm",
                        "com.github.ajalt.mordant.terminal.terminalinterface.nativeimage." +
                            "TerminalInterfaceProviderNativeImage",
                    ),
            )
    }
}
