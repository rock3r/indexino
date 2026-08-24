package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.ArtifactDigest
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkCheckout
import dev.sebastiano.indexino.model.SourceLinkDiagnostic
import dev.sebastiano.indexino.model.SourceLinkEvidence
import java.nio.file.Path

internal data class SourceLinkEvidenceResult(
    val evidence: SourceLinkEvidence,
    val diagnostics: List<SourceLinkDiagnostic>,
)

/** Classifies dependency-to-source links without mutating linked repositories. */
internal object SourceLinkEvidenceClassifier {
    fun classify(
        component: ResolvedComponentIdentity,
        checkout: SourceLinkCheckout,
        checkoutRoot: Path,
        publishedSourceCompanionDigest: ArtifactDigest?,
        declaredOnly: Boolean,
    ): SourceLinkEvidenceResult {
        if (declaredOnly) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.DECLARED, emptyList())
        }

        val diagnostics = mutableListOf<SourceLinkDiagnostic>()
        collectCheckoutDiagnostics(checkout, checkoutRoot, diagnostics)
        collectCompanionDiagnostics(component, publishedSourceCompanionDigest, diagnostics)

        if (diagnostics.isNotEmpty()) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.MISMATCH, diagnostics)
        }

        if (
            publishedSourceCompanionDigest != null &&
                publishedSourceCompanionDigest == component.artifactDigest
        ) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.VERIFIED, emptyList())
        }

        if (checkout.tag != null && !checkout.dirty) {
            return SourceLinkEvidenceResult(SourceLinkEvidence.RECONSTRUCTED, emptyList())
        }

        return SourceLinkEvidenceResult(SourceLinkEvidence.DECLARED, emptyList())
    }

    private fun collectCheckoutDiagnostics(
        checkout: SourceLinkCheckout,
        checkoutRoot: Path,
        diagnostics: MutableList<SourceLinkDiagnostic>,
    ) {
        if (checkout.dirty) {
            diagnostics +=
                SourceLinkDiagnostic.of(
                    "checkout.dirty",
                    "Linked checkout is dirty and cannot satisfy release tag ${checkout.tag}",
                )
        }
        collectSubmoduleDiagnostics(checkout, checkoutRoot, diagnostics)
        collectRefDiagnostics(checkout, checkoutRoot, diagnostics)
    }

    private fun collectSubmoduleDiagnostics(
        checkout: SourceLinkCheckout,
        checkoutRoot: Path,
        diagnostics: MutableList<SourceLinkDiagnostic>,
    ) {
        checkout.submoduleRevisions.forEach { (name, expectedRevision) ->
            val submoduleRoot = checkoutRoot.resolve(name)
            val actualRevision = gitHeadOrNull(submoduleRoot)
            if (actualRevision == null) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "submodule.missing",
                        "Expected submodule '$name' is not present at ${submoduleRoot}",
                    )
            } else if (actualRevision != expectedRevision) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "submodule.mismatch",
                        "Submodule '$name' revision $actualRevision does not match expected $expectedRevision",
                    )
            }
        }
    }

    private fun collectRefDiagnostics(
        checkout: SourceLinkCheckout,
        checkoutRoot: Path,
        diagnostics: MutableList<SourceLinkDiagnostic>,
    ) {
        val headRevision = checkout.revision ?: gitHeadOrNull(checkoutRoot)
        val tag = checkout.tag ?: return
        val resolvedTagRevision = gitRevParse(checkoutRoot, tag)
        if (resolvedTagRevision == null) {
            diagnostics +=
                SourceLinkDiagnostic.of(
                    "ref.unresolved",
                    "Configured ref '$tag' does not resolve in ${checkout.checkoutPath}",
                )
            return
        }
        if (headRevision != null && resolvedTagRevision != headRevision) {
            diagnostics +=
                SourceLinkDiagnostic.of(
                    "ref.mismatch",
                    "Checkout HEAD $headRevision does not match configured ref '$tag' ($resolvedTagRevision)",
                )
        }
    }

    private fun collectCompanionDiagnostics(
        component: ResolvedComponentIdentity,
        publishedSourceCompanionDigest: ArtifactDigest?,
        diagnostics: MutableList<SourceLinkDiagnostic>,
    ) {
        publishedSourceCompanionDigest?.let { companion ->
            if (companion != component.artifactDigest) {
                diagnostics +=
                    SourceLinkDiagnostic.of(
                        "digest.mismatch",
                        "Published source companion digest ${companion.value} does not match " +
                            "resolved artifact digest ${component.artifactDigest.value}",
                    )
            }
        }
    }

    private fun gitHeadOrNull(directory: Path): String? =
        runCatching { gitRevParse(directory, "HEAD") }.getOrNull()

    private fun gitRevParse(directory: Path, ref: String): String? {
        val process =
            ProcessBuilder("git", "-C", directory.toString(), "rev-parse", ref)
                .redirectErrorStream(true)
                .start()
        if (process.waitFor() != 0) return null
        return process.inputStream.bufferedReader().readText().trim().takeIf(String::isNotBlank)
    }
}
