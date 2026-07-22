package dev.sebastiano.indexino.distribution

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeReleaseReadinessContractTest {
    private val projectDirectory = File(System.getProperty("user.dir"))

    @Test
    fun `ordinary CI retains publication and shrunk CLI proofs and adds Linux native smoke`() {
        val workflow = projectFile(".github/workflows/check.yml").readText()

        assertContains(workflow, "verifyMavenPublication")
        assertContains(workflow, "verifyShrunkCli")
        assertContains(workflow, "verifyNativeDistributionLinuxX64")
        assertContains(workflow, "smoke-linux-baseline.sh")
        assertContains(workflow, "ubuntu:22.04")
        assertFalse(workflow.contains("ubuntu:20.04"), "CI must not claim an unsupported baseline")
        assertContains(workflow, "retention-days: 7")
    }

    @Test
    fun `manual native matrix covers every Tier 1 target without caching AOT outputs`() {
        val workflow = projectFile(".github/workflows/native-distributions.yml").readText()
        val cacheScript = projectFile(".github/scripts/native-archive-cache.sh").readText()

        assertContains(workflow, "workflow_dispatch:")
        assertContains(workflow, "ubuntu-24.04")
        assertContains(workflow, "macos-15")
        assertContains(workflow, "windows-2022")
        assertContains(workflow, "verifyNativeDistributionLinuxX64")
        assertContains(workflow, "verifyNativeDistributionMacArm64")
        assertContains(workflow, "verifyNativeDistributionWindowsX64")
        assertContains(workflow, "steps.pins.outputs.cache_key")
        assertContains(workflow, "build/distributions/*.zip")
        assertContains(workflow, "build/distributions/*.sha256")
        assertContains(workflow, "build/reports/native-distributions")
        assertContains(workflow, "build/ci-logs")
        assertContains(workflow, "build/test-results/verifyNativeDistribution")
        assertContains(workflow, "retention-days: 7")
        assertFalse(workflow.contains("native-distributions/aot"), "Workflow must not cache AOT")
        assertFalse(workflow.contains("classes.jsa"), "Workflow must not cache AOT cache files")

        assertContains(cacheScript, "native-distributions.properties")
        assertContains(cacheScript, "sha256")
        assertContains(cacheScript, "-m http.server")
        assertContains(cacheScript, "INDEXINO_NATIVE_")
        assertContains(cacheScript, "_JDK_URL")
        assertContains(cacheScript, "_ROAST_URL")
    }

    @Test
    fun `native archive cache emits exact digest-derived pins`() {
        val result = runCacheScript("emit-pins", "linux-x64")

        assertEquals(0, result.exitCode, result.output)
        assertEquals(
            """
            jdk_sha=82e653e0e7bfc0f58f68dcae961f83be68e7f74d708bf3a3bc6c43ce959e1e31
            roast_sha=c917fdfda3247689636a00922b4b9e522ec02dc6eaa9eb75bf7cb8d7055e8e4b
            cache_key=82e653e0e7bfc0f58f68dcae961f83be68e7f74d708bf3a3bc6c43ce959e1e31-c917fdfda3247689636a00922b4b9e522ec02dc6eaa9eb75bf7cb8d7055e8e4b
            cache_path=.native-download-cache/linux-x64
            """
                .trimIndent(),
            result.output.trim(),
        )
    }

    @Test
    fun `native archive cache rejects a tampered restored archive before delegation`() {
        val temporaryDirectory = Files.createTempDirectory("indexino-native-cache-test")
        val cacheRoot = temporaryDirectory.resolve("cache")
        val targetCache = cacheRoot.resolve("linux-x64")
        Files.createDirectories(targetCache)
        Files.writeString(targetCache.resolve("jbrsdk-25.0.3-linux-x64-b508.16.tar.gz"), "tampered")
        val delegatedMarker = temporaryDirectory.resolve("delegated")

        val result =
            runCacheScript(
                "run",
                "linux-x64",
                cacheRoot.toString(),
                "--",
                "touch",
                delegatedMarker.toString(),
            )

        assertTrue(result.exitCode != 0, "Tampered archive unexpectedly passed verification")
        assertContains(result.output, "SHA-256 mismatch")
        assertFalse(Files.exists(delegatedMarker), "Delegated command ran after digest failure")
    }

    @Test
    fun `checksum cache key includes the distribution archive filename`() {
        val taskSource =
            projectFile("buildSrc/src/main/java/dev/sebastiano/indexino/buildlogic/Sha256File.java")
                .readText()

        assertContains(taskSource, "@PathSensitive(PathSensitivity.NAME_ONLY)")
    }

    @Test
    fun `distribution documentation declares tested support and release blockers`() {
        val distributions = projectFile("docs/DISTRIBUTIONS.md").readText()

        assertContains(distributions, "Ubuntu 22.04")
        assertContains(distributions, "glibc 2.35")
        assertContains(distributions, "macOS 15")
        assertContains(distributions, "Windows Server 2022")
        assertContains(distributions, "Git")
        assertContains(distributions, "Bazel")
        assertContains(distributions, "Gradle")
        assertContains(distributions, "AOT")
        assertContains(distributions, "redistribution")
        assertContains(distributions, "notar")
        assertContains(distributions, "Authenticode")
        assertContains(distributions, "No native release")
    }

    private fun projectFile(path: String): File {
        val file = projectDirectory.resolve(path)
        assertTrue(file.isFile, "Missing project file: $path")
        return file
    }

    private fun assertContains(text: String, expected: String) {
        assertTrue(text.contains(expected), "Expected project contract to contain: $expected")
    }

    private fun runCacheScript(vararg arguments: String): ProcessResult {
        val command = listOf("bash", ".github/scripts/native-archive-cache.sh") + arguments.toList()
        val process =
            ProcessBuilder(command).directory(projectDirectory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
