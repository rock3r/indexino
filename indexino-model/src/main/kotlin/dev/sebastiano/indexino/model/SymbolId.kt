package dev.sebastiano.indexino.model

public class SymbolId private constructor(public val value: String) {
    public companion object {
        @JvmStatic
        public fun of(value: String): SymbolId {
            require(value.isNotBlank()) { "Symbol ID must not be blank" }
            return SymbolId(value)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is SymbolId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SymbolId(value=$value)"
}
