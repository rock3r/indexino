package dev.sebastiano.indexino.model

public class ArtifactDigest private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): ArtifactDigest {
            require(value.isNotBlank()) { "Artifact digest must not be blank" }
            return ArtifactDigest(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ArtifactDigest && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ArtifactDigest(value=$value)"
}
