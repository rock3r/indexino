package dev.sebastiano.indexino.model

public class SourceOriginId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): SourceOriginId {
            require(value.isNotBlank()) { "Source origin ID must not be blank" }
            return SourceOriginId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SourceOriginId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SourceOriginId(value=$value)"
}
