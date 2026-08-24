package dev.sebastiano.indexino.core.sourcelink

import dev.sebastiano.indexino.model.DependencyToGenerationEdge
import dev.sebastiano.indexino.model.LinkGenerationId
import dev.sebastiano.indexino.model.ResolvedComponentIdentity
import dev.sebastiano.indexino.model.SourceLinkRegistration
import dev.sebastiano.indexino.model.WorkspaceGenerationId
import java.security.MessageDigest
import java.util.HexFormat

/** Computes stable link-generation identifiers and dependency-to-generation edges. */
internal object LinkGenerationComputer {
    fun compute(
        registrations: List<SourceLinkRegistration>
    ): Pair<LinkGenerationId, List<DependencyToGenerationEdge>> {
        val edges =
            registrations
                .sortedBy { it.component.coordinate.value }
                .map { registration ->
                    DependencyToGenerationEdge.of(
                        component = registration.component,
                        linkedGeneration = registration.linkedGeneration,
                        linkGeneration = LinkGenerationId.of("pending"),
                        evidence = registration.evidence,
                    )
                }
        val fingerprintInput =
            registrations
                .sortedBy {
                    "${it.component.coordinate.value}\u0000${it.component.artifactDigest.value}"
                }
                .joinToString("\u0001") { registration ->
                    listOf(
                            registration.component.coordinate.value,
                            registration.component.artifactDigest.value,
                            registration.component.variant.orEmpty(),
                            registration.component.substitution.orEmpty(),
                            registration.linkedGeneration.value,
                            registration.sourceOriginId.value,
                            registration.evidence.value,
                            registration.checkout.revision.orEmpty(),
                            registration.checkout.tag.orEmpty(),
                            registration.checkout.dirty.toString(),
                        )
                        .joinToString("\u0002")
                }
        val linkGeneration = LinkGenerationId.of(sha256(fingerprintInput))
        return linkGeneration to
            edges.map { edge ->
                DependencyToGenerationEdge.of(
                    component = edge.component,
                    linkedGeneration = edge.linkedGeneration,
                    linkGeneration = linkGeneration,
                    evidence = edge.evidence,
                )
            }
    }

    fun isStale(
        current: LinkGenerationId,
        registrations: List<SourceLinkRegistration>,
        previousLinkedGenerations: Map<ResolvedComponentIdentity, WorkspaceGenerationId>,
    ): Boolean {
        val (next, _) = compute(registrations)
        if (current != next) return true
        return registrations.any { registration ->
            previousLinkedGenerations[registration.component] != registration.linkedGeneration
        }
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}
