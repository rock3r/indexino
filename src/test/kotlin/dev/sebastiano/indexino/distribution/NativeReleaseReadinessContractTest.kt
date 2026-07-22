package dev.sebastiano.indexino.distribution

import java.io.File
import kotlin.test.Test
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
        assertContains(workflow, "ubuntu:20.04")
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
    fun `distribution documentation declares tested support and release blockers`() {
        val distributions = projectFile("docs/DISTRIBUTIONS.md").readText()

        assertContains(distributions, "Ubuntu 20.04")
        assertContains(distributions, "glibc 2.31")
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
}
