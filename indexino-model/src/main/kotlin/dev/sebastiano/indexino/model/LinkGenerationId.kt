package dev.sebastiano.indexino.model

public class LinkGenerationId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): LinkGenerationId {
            require(value.isNotBlank()) { "Link generation ID must not be blank" }
            return LinkGenerationId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is LinkGenerationId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "LinkGenerationId(value=$value)"
}

public class DependencyToGenerationEdge
private constructor(
    public val component: ResolvedComponentIdentity,
    public val linkedGeneration: WorkspaceGenerationId,
    public val linkGeneration: LinkGenerationId,
    public val evidence: SourceLinkEvidence,
) {
    public companion object {
        @JvmStatic
        public fun of(
            component: ResolvedComponentIdentity,
            linkedGeneration: WorkspaceGenerationId,
            linkGeneration: LinkGenerationId,
            evidence: SourceLinkEvidence,
        ): DependencyToGenerationEdge =
            DependencyToGenerationEdge(
                component = component,
                linkedGeneration = linkedGeneration,
                linkGeneration = linkGeneration,
                evidence = evidence,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DependencyToGenerationEdge &&
                component == other.component &&
                linkedGeneration == other.linkedGeneration &&
                linkGeneration == other.linkGeneration &&
                evidence == other.evidence

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + linkedGeneration.hashCode()
        result = 31 * result + linkGeneration.hashCode()
        result = 31 * result + evidence.hashCode()
        return result
    }

    override fun toString(): String =
        "DependencyToGenerationEdge(component=$component, linkedGeneration=$linkedGeneration, " +
            "linkGeneration=$linkGeneration, evidence=$evidence)"
}
