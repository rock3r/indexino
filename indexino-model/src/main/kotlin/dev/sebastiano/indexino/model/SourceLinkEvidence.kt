package dev.sebastiano.indexino.model

/** Provenance evidence for a dependency-to-source link. */
public class SourceLinkEvidence private constructor(public val value: String) {
    public companion object {
        /**
         * Producer metadata or reproducible-build evidence binds artifact digest to source
         * revision.
         */
        @JvmField public val VERIFIED: SourceLinkEvidence = SourceLinkEvidence("VERIFIED")

        /**
         * Release tag/commit matches a clean checkout including submodules, but binary equivalence
         * is not proven.
         */
        @JvmField public val RECONSTRUCTED: SourceLinkEvidence = SourceLinkEvidence("RECONSTRUCTED")

        /** User/agent declared relevance without established build revision. */
        @JvmField public val DECLARED: SourceLinkEvidence = SourceLinkEvidence("DECLARED")

        /** Coordinate, digest, ref, dirty state, generated sources, or submodules conflict. */
        @JvmField public val MISMATCH: SourceLinkEvidence = SourceLinkEvidence("MISMATCH")
    }

    /** Whether exact cross-repository semantic results may use this link. */
    public fun allowsExactCrossRepositorySemantics(): Boolean = this === VERIFIED

    /** Whether indexed queries may run with visibly qualified provenance. */
    public fun allowsQualifiedIndexedQueries(): Boolean =
        this === VERIFIED || this === RECONSTRUCTED

    /** Whether the link is a navigation hint only. */
    public fun isNavigationHintOnly(): Boolean = this === DECLARED || this === MISMATCH

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceLinkEvidence && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SourceLinkEvidence(value=$value)"
}
