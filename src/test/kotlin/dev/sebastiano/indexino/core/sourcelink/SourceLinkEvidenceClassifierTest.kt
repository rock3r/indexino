package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.ResolvedComponentCoordinate
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkCheckout
import dev.sebastiano.indexino.model.SourceLinkEvidence
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceLinkEvidenceClassifierTest {
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        temporaryDirectories.forEach { it.toFile().deleteRecursively() }
        temporaryDirectories.clear()
    }

    @Test
    fun `verified when resolved artifact digest matches published source companion`() {
        val checkout = taggedCheckout("v1.0.0", dirty = false)
        val component = component("com.example:lib:1.0.0", "sha256:verified-digest")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = ArtifactDigest.of("sha256:verified-digest"),
                declaredOnly = false,
            )

        assertEquals(SourceLinkEvidence.VERIFIED, result.evidence)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `reconstructed when clean tagged checkout matches expected release without digest proof`() {
        val checkout = taggedCheckout("v1.0.0", dirty = false)
        val component = component("com.example:lib:1.0.0", "sha256:binary-only")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = null,
                declaredOnly = false,
            )

        assertEquals(SourceLinkEvidence.RECONSTRUCTED, result.evidence)
    }

    @Test
    fun `declared when caller opts into manual relevance without verification inputs`() {
        val checkout = taggedCheckout("v1.0.0", dirty = false)
        val component = component("com.example:lib:1.0.0", "sha256:binary-only")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = null,
                declaredOnly = true,
            )

        assertEquals(SourceLinkEvidence.DECLARED, result.evidence)
    }

    @Test
    fun `mismatch when dirty checkout conflicts with clean release tag expectation`() {
        val checkout = taggedCheckout("v1.0.0", dirty = true)
        val component = component("com.example:lib:1.0.0", "sha256:binary-only")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = null,
                declaredOnly = false,
            )

        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
        assertTrue(result.diagnostics.any { it.code == "checkout.dirty" })
    }

    @Test
    fun `mismatch when published source companion digest differs from resolved artifact`() {
        val checkout = taggedCheckout("v1.0.0", dirty = false)
        val component = component("com.example:lib:1.0.0", "sha256:resolved")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = ArtifactDigest.of("sha256:other"),
                declaredOnly = false,
            )

        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
        assertTrue(result.diagnostics.any { it.code == "digest.mismatch" })
    }

    @Test
    fun `mismatch when submodule revision does not match checkout`() {
        checkoutPath = temporaryDirectory("indexino-source-link-submodule-")
        val nestedRoot = checkoutPath.resolve("nested")
        nestedRoot
            .resolve("src/main/kotlin/Nested.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Nested")
        initializeGitRepository(nestedRoot)
        val checkout =
            SourceLinkCheckout.of(
                repositoryIdentity = "git@github.com:example/provider.git",
                checkoutPath = checkoutPath.toString(),
                revision = null,
                tag = null,
                dirty = false,
                submoduleRevisions = mapOf("nested" to "expected-sub-rev"),
                sourceRoots = listOf("src/main/kotlin"),
            )
        val component = component("com.example:lib:1.0.0", "sha256:binary-only")

        val result =
            SourceLinkEvidenceClassifier.classify(
                component = component,
                checkout = checkout,
                checkoutRoot = checkoutPath,
                publishedSourceCompanionDigest = null,
                declaredOnly = false,
            )

        assertEquals(SourceLinkEvidence.MISMATCH, result.evidence)
        assertTrue(result.diagnostics.any { it.code == "submodule.mismatch" })
    }

    private lateinit var checkoutPath: Path

    private fun taggedCheckout(tag: String, dirty: Boolean): SourceLinkCheckout {
        checkoutPath = temporaryDirectory("indexino-source-link-checkout-")
        checkoutPath
            .resolve("src/main/kotlin/Lib.kt")
            .also { it.parent.createDirectories() }
            .writeText("class Lib")
        initializeGitRepository(checkoutPath)
        runGit(checkoutPath, "tag", tag)
        if (dirty) {
            checkoutPath.resolve("src/main/kotlin/Lib.kt").writeText("class LibModified")
        }
        return SourceLinkCheckout.of(
            repositoryIdentity = "git@github.com:example/provider.git",
            checkoutPath = checkoutPath.toString(),
            revision = if (dirty) null else currentHeadRevision(),
            tag = tag,
            dirty = dirty,
            submoduleRevisions = emptyMap(),
            sourceRoots = listOf("src/main/kotlin"),
        )
    }

    private fun currentHeadRevision(): String =
        ProcessBuilder("git", "-C", checkoutPath.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()

    private fun component(coordinate: String, digest: String): ResolvedComponentIdentity =
        ResolvedComponentIdentity.of(
            coordinate = ResolvedComponentCoordinate.of(coordinate),
            artifactDigest = ArtifactDigest.of(digest),
            variant = "jvm",
            substitution = null,
        )

    private fun temporaryDirectory(prefix: String): Path =
        createTempDirectory(prefix).also(temporaryDirectories::add)

    private fun initializeGitRepository(directory: Path) {
        runGit(directory, "init")
        runGit(directory, "config", "user.email", "test@indexino.invalid")
        runGit(directory, "config", "user.name", "Indexino Test")
        runGit(directory, "add", ".")
        runGit(directory, "commit", "-m", "init")
    }

    private fun runGit(directory: Path, vararg args: String) {
        val command = listOf("git", "-C", directory.toString(), *args)
        val process = ProcessBuilder(command).redirectErrorStream(true).start().apply { waitFor() }
        check(process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: ${process.inputStream.bufferedReader().readText()}"
        }
    }
}
