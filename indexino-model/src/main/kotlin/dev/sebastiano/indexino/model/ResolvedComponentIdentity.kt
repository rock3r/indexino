package dev.sebastiano.indexino.model

public class ResolvedComponentIdentity
private constructor(
    public val coordinate: ResolvedComponentCoordinate,
    public val artifactDigest: ArtifactDigest,
    public val variant: String?,
    public val substitution: String?,
) {
    public companion object {
        @JvmStatic
        public fun of(
            coordinate: ResolvedComponentCoordinate,
            artifactDigest: ArtifactDigest,
            variant: String?,
            substitution: String?,
        ): ResolvedComponentIdentity =
            ResolvedComponentIdentity(
                coordinate = coordinate,
                artifactDigest = artifactDigest,
                variant = variant?.takeIf(String::isNotBlank),
                substitution = substitution?.takeIf(String::isNotBlank),
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ResolvedComponentIdentity &&
                coordinate == other.coordinate &&
                artifactDigest == other.artifactDigest &&
                variant == other.variant &&
                substitution == other.substitution

    override fun hashCode(): Int {
        var result = coordinate.hashCode()
        result = 31 * result + artifactDigest.hashCode()
        result = 31 * result + (variant?.hashCode() ?: 0)
        result = 31 * result + (substitution?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ResolvedComponentIdentity(coordinate=$coordinate, artifactDigest=$artifactDigest, " +
            "variant=$variant, substitution=$substitution)"
}
