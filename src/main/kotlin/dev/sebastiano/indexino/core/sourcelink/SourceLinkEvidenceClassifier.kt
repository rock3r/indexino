package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkCheckout
import dev.sebastiano.indexino.model.SourceLinkDiagnostic
import dev.sebastiano.indexino.model.SourceLinkEvidence

internal data class SourceLinkEvidenceResult(
    val evidence: SourceLinkEvidence,
    val diagnostics: List<SourceLinkDiagnostic>,
)

/** Classifies dependency-to-source links without mutating linked repositories. */
internal object SourceLinkEvidenceClassifier {
    fun classify(
        component: ResolvedComponentIdentity,
        checkout: SourceLinkCheckout,
        checkoutRoot: java.nio.file.Path,
        publishedSourceCompanionDigest: ArtifactDigest?,
        declaredOnly: Boolean,
    ): SourceLinkEvidenceResult {
        if (declaredOnly) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.DECLARED, emptyList())
        }

        val diagnostics = mutableListOf<SourceLinkDiagnostic>()

        publishedSourceCompanionDigest?.let { companion ->
            if (companion != component.artifactDigest) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "digest.mismatch",
                        "Published source companion digest ${companion.value} does not match " +
                            "resolved artifact digest ${component.artifactDigest.value}",
                    )
            } else {
                return SourceLinkEvidenceResult(SourceLinkEvidence.VERIFIED, emptyList())
            }
        }

        if (checkout.dirty) {
            diagnostics +=
                SourceLinkDiagnostic.of(
                    "checkout.dirty",
                    "Linked checkout is dirty and cannot satisfy release tag ${checkout.tag}",
                )
        }

        checkout.submoduleRevisions.forEach { (name, expectedRevision) ->
            val submoduleRoot = checkoutRoot.resolve(name)
            if (!java.nio.file.Files.isDirectory(submoduleRoot.resolve(".git"))) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "submodule.missing",
                        "Expected submodule '$name' is not present at ${submoduleRoot}",
                    )
                return@forEach
            }
            val actualRevision = gitHead(submoduleRoot)
            if (actualRevision != expectedRevision) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "submodule.mismatch",
                        "Submodule '$name' revision $actualRevision does not match expected $expectedRevision",
                    )
            }
        }

        if (diagnostics.isNotEmpty()) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.MISMATCH, diagnostics)
        }

        if (checkout.tag != null && !checkout.dirty) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.RECONSTRUCTED, emptyList())
        }

        return SourceLinkEvidenceResult(SourceLinkEvidence.DECLARED, emptyList())
    }

    private fun gitHead(directory: java.nio.file.Path): String =
        ProcessBuilder("git", "-C", directory.toString(), "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
}
