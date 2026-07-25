package dev.sebastiano.indexino.model

public class RefreshId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): RefreshId {
            require(value.isNotBlank()) { "Refresh ID must not be blank" }
            return RefreshId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is RefreshId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RefreshId(value=$value)"
}
