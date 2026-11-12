package dev.sebastiano.indexino.distribution

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

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
            roast_sha=a7fd2942410e961b0129715f6c1d6d7c19f9c59caa241d6d378f74f09aeec56b
            cache_key=82e653e0e7bfc0f58f68dcae961f83be68e7f74d708bf3a3bc6c43ce959e1e31-a7fd2942410e961b0129715f6c1d6d7c19f9c59caa241d6d378f74f09aeec56b
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
        assertContains(distributions, "runtime/legal")
        assertContains(distributions, "notar")
        assertContains(distributions, "Authenticode")
        assertContains(distributions, "bundled-dependencies.txt")
    }

    @Test
    fun `tag workflow publishes Maven train and native CLI drafts on every release tag`() {
        val workflow = projectFile(".github/workflows/release.yml").readText()

        assertContains(workflow, "uses: ./.github/workflows/native-distributions.yml")
        assertContains(workflow, "release: true")
        assertContains(workflow, "needs: [release-gate, publish, native-distributions]")
        assertContains(workflow, "gh release create")
        assertContains(workflow, "--draft")
        assertContains(workflow, "--prerelease")
        assertContains(workflow, "RELEASE_NOTES-")
        assertContains(workflow, "Upload complete Maven Central release train")
        assertContains(workflow, "publishToMavenCentral")
        assertContains(workflow, "-PcentralAutomaticRelease=true")
        assertContains(
            projectFile("build.gradle.kts").readText(),
            "publishToMavenCentral(automaticRelease = centralAutomaticRelease)",
        )
        assertContains(workflow, "MAVEN_CENTRAL_USERNAME")
        assertContains(workflow, "SIGNING_IN_MEMORY_KEY")
        assertContains(workflow, "generateBundledDependencyInventory")
        assertFalse(
            workflow.contains("WINDOWS_CERTIFICATE_P12"),
            "Windows release must not require Authenticode credentials",
        )
        assertFalse(
            workflow.contains("WINDOWS_SIGNING_THUMBPRINT"),
            "Windows release must not require a signing thumbprint",
        )
        assertFalse(
            projectFile(".github/workflows/native-distributions.yml")
                .readText()
                .contains("sign-windows-distribution.ps1"),
            "Windows release must use the verified, unsigned native archive",
        )
        assertContains(projectFile("third-party/roast/LICENSE").readText(), "Apache License")
        assertContains(projectFile("build.gradle.kts").readText(), "licenses/roast-LICENSE")
        assertContains(projectFile("build.gradle.kts").readText(), "licenses/indexino-LICENSE")
        assertFalse(
            workflow.contains("verify-native-release-readiness.sh"),
            "Release must not depend on counsel approval script",
        )
        assertFalse(
            workflow.contains("maven-release:"),
            "Release must not split Maven-only GitHub assets from native ZIPs",
        )
        assertFalse(
            workflow.contains("native_release"),
            "Release must not gate native ZIP drafting behind repository variables",
        )
        assertFalse(
            File(projectDirectory, ".github/scripts/verify-native-release-readiness.sh").isFile,
            "Counsel approval script must be removed",
        )
        assertFalse(
            File(projectDirectory, "release/native-redistribution-manifest.json").isFile,
            "Counsel approval manifest must be removed",
        )
        assertTrue(
            projectFile("release/RELEASE_NOTES-0.2.0.md").isFile,
            "First release notes must be checked in",
        )
        assertTrue(
            projectFile(".github/scripts/setup-macos-release-secrets.sh").isFile,
            "macOS secret bootstrap script must be checked in",
        )
    }

    @Test
    fun `release native matrix notarizes immutable mac bytes and reverifies before checksums`() {
        val workflow = projectFile(".github/workflows/native-distributions.yml").readText()
        val build = projectFile("build.gradle.kts").readText()
        val signingScript =
            projectFile(".github/scripts/sign-notarize-macos-distribution.sh").readText()

        assertContains(workflow, "workflow_call:")
        assertContains(workflow, "timeout-minutes: 300")
        assertContains(workflow, "test -n \"\$INDEXINO_RELEASE_VERSION\"")
        assertContains(build, "excludeTags(\"native-performance\")")
        assertContains(
            workflow
                .substringBefore("workflow_call:")
                .substringAfter("      release:", missingDelimiterValue = "")
                .trimIndent(),
            """
            description: Sign and notarize macOS release bytes
            required: false
            type: boolean
            default: false
            """
                .trimIndent(),
        )
        assertContains(workflow, "if: inputs.release\n")
        assertContains(workflow, "if: inputs.release && matrix.target == 'macos-arm64'")
        assertContains(workflow, "MACOS_CERTIFICATE_P12")
        assertContains(workflow, "APPLE_APP_SPECIFIC_PASSWORD")
        assertContains(workflow, "sign-notarize-macos-distribution.sh")
        assertContains(workflow, "smoke-macos-release-distribution.sh")
        assertTrue(
            workflow.indexOf("smoke-macos-release-distribution.sh") <
                workflow.lastIndexOf(".sha256"),
            "Final macOS release smoke must precede release checksum generation",
        )
        assertContains(build, "INDEXINO_NATIVE_MACOS_ARM64_VERIFICATION_ARCHIVE")
        assertContains(signingScript, "codesign")
        assertContains(signingScript, "notarytool submit")
        assertContains(signingScript, "spctl")
        assertContains(signingScript, "does not seem to be an app")
        assertContains(signingScript, "stapler validate")
        assertContains(signingScript, "list-keychains -d user -s")
        assertContains(signingScript, "default-keychain -d user -s")
        assertContains(signingScript, "find-identity -v -p codesigning")
        assertContains(signingScript, "sign_nested_natives_in_jars")
        assertContains(signingScript, "jnilib|dylib")
        assertContains(signingScript, "notarization status is")
        assertContains(
            projectFile(".github/scripts/setup-macos-release-secrets.sh").readText(),
            "-legacy",
        )
        assertFalse(
            File(projectDirectory, ".github/scripts/generate-release-provenance.sh").isFile,
            "Aggregate provenance script must be removed",
        )
    }

    @Test
    fun `macOS release signs JAR natives before AOT training and leaves them unchanged after`() {
        val workflow = projectFile(".github/workflows/native-distributions.yml").readText()
        val build = projectFile("build.gradle.kts").readText()
        val signingScript =
            projectFile(".github/scripts/sign-notarize-macos-distribution.sh").readText()

        assertTrue(
            workflow.indexOf("Prepare signed macOS JAR inputs") <
                workflow.indexOf("- name: Verify native distribution") &&
                workflow.contains("- name: Prepare signed macOS JAR inputs"),
            "Nested Mach-O code signatures must precede AOT cache training",
        )
        assertContains(workflow, "INDEXINO_SIGNED_MAC_CLI_JAR")
        assertContains(workflow, "INDEXINO_SIGNED_MAC_WORKER_JAR")
        assertContains(build, "INDEXINO_SIGNED_MAC_CLI_JAR")
        assertContains(build, "INDEXINO_SIGNED_MAC_WORKER_JAR")
        assertContains(signingScript, "verify_nested_natives_in_jars")
        assertFalse(
            signingScript
                .substringAfter("readonly PAYLOAD_DIRECTORY=")
                .contains("sign_nested_natives_in_jars"),
            "Final archive signing must not mutate an AOT-trained JAR",
        )
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
        val command =
            listOf(bashExecutable(), ".github/scripts/native-archive-cache.sh") + arguments.toList()
        val process =
            ProcessBuilder(command).directory(projectDirectory).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), output)
    }

    private fun bashExecutable(): String {
        if (!System.getProperty("os.name").startsWith("Windows")) return "bash"
        val locator = ProcessBuilder("where.exe", "bash").redirectErrorStream(true).start()
        val paths = locator.inputStream.bufferedReader().use { it.readLines() }
        val bash =
            if (locator.waitFor() == 0)
                paths.map(String::trim).map(::File).firstOrNull {
                    it.isFile && it.parentFile.resolve("git.exe").isFile
                }
            else null
        assumeTrue(bash != null, "Native archive cache shell tests require Git for Windows Bash")
        return requireNotNull(bash).absolutePath
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
