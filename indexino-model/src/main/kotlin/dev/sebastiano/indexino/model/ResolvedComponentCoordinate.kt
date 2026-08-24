package dev.sebastiano.indexino.model

public class ResolvedComponentCoordinate private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): ResolvedComponentCoordinate {
            require(value.isNotBlank()) { "Resolved component coordinate must not be blank" }
            return ResolvedComponentCoordinate(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ResolvedComponentCoordinate && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ResolvedComponentCoordinate(value=$value)"
}
