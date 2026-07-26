package dev.sebastiano.indexino.model

public class ResolutionConfidence private constructor(public val value: String) {
    public companion object {
        @JvmField public val RESOLVED: ResolutionConfidence = ResolutionConfidence("RESOLVED")
        @JvmField public val HEURISTIC: ResolutionConfidence = ResolutionConfidence("HEURISTIC")
        @JvmField public val UNRESOLVED: ResolutionConfidence = ResolutionConfidence("UNRESOLVED")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ResolutionConfidence && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ResolutionConfidence(value=$value)"
}
